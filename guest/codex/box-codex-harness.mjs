#!/usr/bin/env node
/**
 * Codex App Server adapter for Box.
 *
 * One adapter process owns one Codex App Server process and one durable Codex
 * thread for a Box task. Box only sees its harness-independent JSONL vocabulary;
 * all Codex JSON-RPC stays behind this file.
 */
import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import {
  access, mkdir, open, readFile, rename, unlink, writeFile,
} from 'node:fs/promises'
import { constants as fsConstants } from 'node:fs'
import { createInterface } from 'node:readline'
import { dirname, join } from 'node:path'

const APP_SERVER = process.env.BOX_CODEX_APP_SERVER
  ?? '/opt/local-agent/codex/bin/codex-app-server'
const CWD = process.env.BOX_SESSION_CWD ?? '/workspace'
const SESSION_ID = process.env.BOX_SESSION_ID ?? ''
const CODEX_HOME = process.env.CODEX_HOME ?? '/workspace/.config/codex'
const BOX_STATE_ROOT = process.env.BOX_CODEX_STATE_ROOT
  ?? '/workspace/.config/box/codex/sessions'
const ATTACHMENT_WAIT_MS = Number(process.env.BOX_ATTACHMENT_WAIT_MS ?? 30_000)
const ATTACHMENT_POLL_MS = 100
const LOCK_WAIT_MS = 15_000

if (!SESSION_ID || !/^[A-Za-z0-9._-]+$/.test(SESSION_ID)) {
  throw new Error('BOX_SESSION_ID is required and must be a safe filename component')
}

const STATE_FILE = join(BOX_STATE_ROOT, `${SESSION_ID}.json`)
const LOCK_FILE = join(BOX_STATE_ROOT, `${SESSION_ID}.lock`)

let permissionMode = 'default'
let rpc = null
let rpcStarting = null
let threadId = null
let activeTurnId = null
let interruptRequested = false
let promptChain = Promise.resolve()
let shuttingDown = false
let lockOwned = false
let messageBuffers = new Map()
let reasoningBuffers = new Map()
const pendingApprovals = new Map()

function emit(event) {
  process.stdout.write(`${JSON.stringify({ at: Date.now(), ...event })}\n`)
}

function diagnostic(message) {
  process.stderr.write(`[box-codex] ${message}\n`)
}

function errorMessage(error) {
  if (error instanceof Error) return error.message
  return String(error)
}

function approvalPolicy() {
  if (permissionMode === 'bypassPermissions') return 'never'
  if (permissionMode === 'acceptEdits') return 'on-request'
  return 'untrusted'
}

async function acquireSessionLock() {
  await mkdir(BOX_STATE_ROOT, { recursive: true })
  const deadline = Date.now() + LOCK_WAIT_MS

  while (true) {
    try {
      const handle = await open(LOCK_FILE, 'wx', 0o600)
      await handle.writeFile(`${process.pid}\n`)
      await handle.close()
      lockOwned = true
      return
    } catch (error) {
      if (error?.code !== 'EEXIST') throw error
    }

    let owner = null
    try {
      owner = Number((await readFile(LOCK_FILE, 'utf8')).trim())
    } catch {}

    let alive = false
    if (Number.isInteger(owner) && owner > 1) {
      try {
        process.kill(owner, 0)
        alive = true
      } catch {}
    }

    if (!alive) {
      await unlink(LOCK_FILE).catch(() => {})
      continue
    }

    if (Date.now() >= deadline) {
      throw new Error(`another Codex harness still owns Box session ${SESSION_ID}`)
    }
    await new Promise(resolve => setTimeout(resolve, 100))
  }
}

async function releaseSessionLock() {
  if (!lockOwned) return
  lockOwned = false
  await unlink(LOCK_FILE).catch(() => {})
}

async function loadThreadState() {
  try {
    const state = JSON.parse(await readFile(STATE_FILE, 'utf8'))
    if (state?.version !== 1 || typeof state.threadId !== 'string' || !state.threadId) {
      throw new Error(`invalid Codex thread state in ${STATE_FILE}`)
    }
    return state
  } catch (error) {
    if (error?.code === 'ENOENT') return null
    throw error
  }
}

async function persistThreadState(id) {
  await mkdir(dirname(STATE_FILE), { recursive: true })
  const temp = `${STATE_FILE}.${process.pid}.${randomUUID()}.tmp`
  const body = JSON.stringify({
    version: 1,
    threadId: id,
    codexVersion: '0.147.0',
  }) + '\n'
  await writeFile(temp, body, { mode: 0o600 })
  await rename(temp, STATE_FILE)
}

class RpcClient {
  constructor(child) {
    this.child = child
    this.nextId = 1
    this.pending = new Map()
    this.closed = false

    child.stderr.setEncoding('utf8')
    child.stderr.on('data', chunk => process.stderr.write(chunk))

    const lines = createInterface({ input: child.stdout, crlfDelay: Infinity })
    lines.on('line', line => this.onLine(line))
    child.on('error', error => this.close(error))
    child.on('exit', (code, signal) => {
      this.close(new Error(`Codex App Server stopped (${signal ?? `exit ${code}`})`))
    })
  }

  request(method, params) {
    if (this.closed) return Promise.reject(new Error('Codex App Server is closed'))
    const id = this.nextId++
    return new Promise((resolve, reject) => {
      this.pending.set(String(id), { resolve, reject, method })
      this.write({ method, id, ...(params === undefined ? {} : { params }) })
    })
  }

  notify(method, params) {
    this.write({ method, ...(params === undefined ? {} : { params }) })
  }

  respond(id, result) {
    this.write({ id, result })
  }

  respondError(id, code, message) {
    this.write({ id, error: { code, message } })
  }

  write(message) {
    if (this.closed) return
    this.child.stdin.write(`${JSON.stringify(message)}\n`)
  }

  onLine(line) {
    let message
    try {
      message = JSON.parse(line)
    } catch {
      diagnostic('ignored malformed JSON from Codex App Server')
      return
    }

    if (message.method && Object.prototype.hasOwnProperty.call(message, 'id')) {
      void handleServerRequest(message)
      return
    }
    if (message.method) {
      handleNotification(message.method, message.params ?? {})
      return
    }
    if (Object.prototype.hasOwnProperty.call(message, 'id')) {
      const waiter = this.pending.get(String(message.id))
      if (!waiter) return
      this.pending.delete(String(message.id))
      if (message.error) {
        const err = new Error(`${waiter.method}: ${message.error.message ?? 'request failed'}`)
        err.rpc = message.error
        waiter.reject(err)
      } else {
        waiter.resolve(message.result)
      }
    }
  }

  close(error) {
    if (this.closed) return
    this.closed = true
    for (const waiter of this.pending.values()) waiter.reject(error)
    this.pending.clear()
    if (rpc === this) rpc = null
    // A thread id is only usable by the App Server process that loaded/created it. Clear
    // the in-memory binding so the next prompt starts a replacement App Server and resumes
    // the durable id from STATE_FILE instead of attempting a request on this closed client.
    threadId = null
    activeTurnId = null
    interruptRequested = false
    messageBuffers.clear()
    reasoningBuffers.clear()
    if (!shuttingDown) {
      for (const requestId of pendingApprovals.keys()) {
        emit({ type: 'permission_resolved', requestId, decision: 'deny' })
      }
      pendingApprovals.clear()
      emit({
        type: 'error',
        message: 'Codex App Server stopped unexpectedly.',
        detail: errorMessage(error),
        recoverable: true,
      })
      emit({ type: 'activity', activity: { kind: 'idle' } })
    }
  }
}

async function startRpc() {
  if (rpcStarting) return rpcStarting
  if (rpc && !rpc.closed) return rpc

  rpcStarting = (async () => {
    await mkdir(CODEX_HOME, { recursive: true })
    const child = spawn(APP_SERVER, [
      '--listen', 'stdio://',
      '-c', 'cli_auth_credentials_store="file"',
    ], {
      cwd: CWD,
      env: {
        ...process.env,
        CODEX_HOME,
        HOME: process.env.HOME ?? '/home/agent',
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    const client = new RpcClient(child)
    rpc = client

    await client.request('initialize', {
      clientInfo: {
        name: 'box_android',
        title: 'Box',
        version: '1',
      },
      capabilities: {},
    })
    client.notify('initialized')
    return client
  })()

  try {
    return await rpcStarting
  } finally {
    rpcStarting = null
  }
}

async function requireAuthentication(client) {
  const result = await client.request('account/read', { refreshToken: false })
  if (result?.requiresOpenaiAuth === false) return
  if (result?.account) return

  throw new Error(
    'Codex is not signed in. In the Box terminal run '
    + '`/opt/local-agent/codex/bin/box-codex-login` once, then retry this task.',
  )
}

async function ensureThread() {
  if (threadId) return threadId
  const client = await startRpc()
  await requireAuthentication(client)

  const saved = await loadThreadState()
  const params = {
    cwd: CWD,
    approvalPolicy: approvalPolicy(),
    sandbox: 'danger-full-access',
  }

  if (saved) {
    try {
      const resumed = await client.request('thread/resume', {
        threadId: saved.threadId,
        ...params,
      })
      threadId = resumed?.thread?.id ?? saved.threadId
      if (threadId !== saved.threadId) {
        throw new Error('Codex resumed a different thread than Box requested')
      }
      return threadId
    } catch (error) {
      throw new Error(
        `Box has Codex thread ${saved.threadId} for this task, but Codex could not resume it. `
        + `The existing Box transcript is intact and no replacement thread was created. `
        + `Details: ${errorMessage(error)}`,
      )
    }
  }

  const started = await client.request('thread/start', params)
  const created = started?.thread?.id
  if (!created) throw new Error('thread/start returned no Codex thread id')
  // Persist before accepting any user turn. A crash after thread/start but before
  // this rename can orphan one empty Codex thread, but can never make an old Box
  // transcript silently point at a fresh model context.
  await persistThreadState(created)
  threadId = created
  return threadId
}

async function waitForAttachment(path) {
  const deadline = Date.now() + ATTACHMENT_WAIT_MS
  while (true) {
    try {
      await access(path, fsConstants.R_OK)
      return
    } catch (error) {
      if (Date.now() >= deadline) {
        throw new Error(`attachment did not arrive in the guest: ${path}`)
      }
      await new Promise(resolve => setTimeout(resolve, ATTACHMENT_POLL_MS))
    }
  }
}

async function codexInput(command) {
  const attachments = Array.isArray(command.attachments) ? command.attachments : []
  await Promise.all(attachments.map(item => waitForAttachment(item.guestPath)))

  const input = [{
    type: 'text',
    text: command.text ?? '',
    text_elements: [],
  }]
  const pathOnly = []

  for (const item of attachments) {
    const mime = String(item.mimeType ?? '')
    if (mime.startsWith('image/')) {
      input.push({ type: 'localImage', path: item.guestPath })
    } else if (mime.startsWith('audio/')) {
      input.push({ type: 'localAudio', path: item.guestPath })
    } else {
      const name = item.name || item.guestPath.split('/').pop() || 'attachment'
      pathOnly.push(`- ${name}${mime ? ` (${mime})` : ''}: ${item.guestPath}`)
    }
  }

  if (pathOnly.length) {
    input[0].text += `${input[0].text ? '\n\n' : ''}`
      + 'The user attached these files. They already exist inside the Box VM; '
      + 'inspect them with your normal file tools when relevant:\n'
      + pathOnly.join('\n')
  }

  return input
}

async function runPrompt(command) {
  emit({
    type: 'user_message',
    text: command.text ?? '',
    attachments: Array.isArray(command.attachments) ? command.attachments : [],
  })
  emit({ type: 'activity', activity: { kind: 'starting', label: 'Starting Codex' } })

  try {
    const input = await codexInput(command)
    const id = await ensureThread()
    emit({ type: 'activity', activity: { kind: 'thinking', label: 'Codex' } })
    const result = await rpc.request('turn/start', {
      threadId: id,
      clientUserMessageId: `box-${SESSION_ID}-${randomUUID()}`,
      input,
      approvalPolicy: approvalPolicy(),
    })
    activeTurnId = result?.turn?.id ?? null
    if (!activeTurnId) throw new Error('turn/start returned no Codex turn id')

    if (interruptRequested) {
      interruptRequested = false
      await interrupt()
    }
  } catch (error) {
    emit({
      type: 'error',
      message: 'Codex could not start this turn.',
      detail: errorMessage(error),
      recoverable: true,
    })
    emit({ type: 'activity', activity: { kind: 'idle' } })
  }
}

async function interrupt() {
  if (!threadId || !activeTurnId || !rpc || rpc.closed) {
    interruptRequested = true
    return
  }

  const turn = activeTurnId
  interruptRequested = false
  try {
    await rpc.request('turn/interrupt', { threadId, turnId: turn })
  } catch (error) {
    diagnostic(`turn/interrupt failed: ${errorMessage(error)}`)
  }
}

function approvalRequestId(id) {
  return `codex-rpc-${String(id)}`
}

async function handleServerRequest(message) {
  const { method, id, params = {} } = message
  if (!rpc) return

  if (
    method !== 'item/commandExecution/requestApproval'
    && method !== 'item/fileChange/requestApproval'
  ) {
    rpc.respondError(id, -32601, `Box does not implement Codex client request ${method}`)
    return
  }

  if (permissionMode === 'bypassPermissions') {
    rpc.respond(id, { decision: 'accept' })
    return
  }

  const requestId = approvalRequestId(id)
  const fileChange = method === 'item/fileChange/requestApproval'
  const ask = fileChange
    ? {
        kind: 'generic',
        title: 'Codex wants to change files',
        description: params.reason ?? 'Codex requested approval for a file change.',
        details: params.grantRoot ? [['Write access', params.grantRoot]] : [],
        alwaysAllowScope: 'codex:file-changes',
      }
    : {
        kind: 'run_command',
        command: params.command ?? 'Command requested by Codex',
        workingDirectory: params.cwd ?? CWD,
        rationale: params.reason ?? null,
        destructive: false,
        alwaysAllowScope: 'codex:commands',
      }

  pendingApprovals.set(requestId, {
    rpcId: id,
    method,
  })
  emit({ type: 'permission_requested', requestId, ask })
  emit({ type: 'activity', activity: { kind: 'awaiting_permission', requestId } })
}

function decide(command) {
  const pending = pendingApprovals.get(command.requestId)
  if (!pending || !rpc || rpc.closed) return
  pendingApprovals.delete(command.requestId)

  const allowed = command.decision === 'allow' || command.decision === 'allow_always'
  const always = command.decision === 'allow_always'
  rpc.respond(pending.rpcId, {
    decision: allowed ? (always ? 'acceptForSession' : 'accept') : 'decline',
  })
  emit({
    type: 'permission_resolved',
    requestId: command.requestId,
    decision: allowed ? (always ? 'allow_always' : 'allow') : 'deny',
  })
  emit({ type: 'activity', activity: { kind: 'working', label: 'Working' } })
}

function itemFrom(params) {
  return params?.item ?? null
}

function genericArguments(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  return Object.entries(value).slice(0, 20).map(([key, val]) => [
    key,
    typeof val === 'string' ? val : JSON.stringify(val),
  ])
}

function toolFor(item) {
  switch (item?.type) {
    case 'commandExecution':
      return {
        kind: 'shell',
        command: item.command ?? '',
        workingDirectory: item.cwd ?? CWD,
      }
    case 'fileChange':
      return {
        kind: 'generic',
        name: 'Edit files',
        arguments: [],
      }
    case 'mcpToolCall':
      return {
        kind: 'generic',
        name: `${item.server ?? 'MCP'} · ${item.tool ?? 'tool'}`,
        arguments: genericArguments(item.arguments),
      }
    case 'dynamicToolCall':
      return {
        kind: 'generic',
        name: item.namespace ? `${item.namespace} · ${item.tool}` : (item.tool ?? 'Tool'),
        arguments: genericArguments(item.arguments),
      }
    case 'collabAgentToolCall':
      return {
        kind: 'task',
        description: `Codex ${item.tool ?? 'sub-agent'}`,
        prompt: item.prompt ?? null,
        agentType: 'codex',
      }
    case 'webSearch':
      return {
        kind: 'search',
        query: item.query ?? item.searchQuery ?? 'Web search',
        scope: 'web',
      }
    default:
      return null
  }
}

function finishOutcome(item) {
  if (item?.type === 'commandExecution') {
    const ok = item.status === 'completed' && (item.exitCode == null || item.exitCode === 0)
    if (ok) {
      return {
        status: 'success',
        output: item.aggregatedOutput ?? '',
        exitCode: item.exitCode ?? 0,
      }
    }
    if (item.status === 'declined') return { status: 'denied' }
    if (item.status === 'cancelled') return { status: 'cancelled' }
    return {
      status: 'failure',
      message: `Command ${item.status ?? 'failed'}.`,
      output: item.aggregatedOutput ?? '',
      exitCode: item.exitCode ?? null,
    }
  }

  const status = item?.status
  if (status === 'failed') return { status: 'failure', message: 'Codex tool failed.' }
  if (status === 'declined') return { status: 'denied' }
  if (status === 'cancelled') return { status: 'cancelled' }
  return { status: 'success' }
}

function mapChangeKind(kind) {
  const value = String(kind ?? '').toLowerCase()
  if (value.includes('add') || value.includes('create')) return 'create'
  if (value.includes('delete')) return 'delete'
  if (value.includes('rename') || value.includes('move')) return 'rename'
  return 'modify'
}

function handleItemStarted(params) {
  const item = itemFrom(params)
  const tool = toolFor(item)
  if (!tool || !item?.id) return
  emit({ type: 'tool_started', callId: item.id, tool })
  emit({ type: 'activity', activity: { kind: 'working', label: 'Working' } })
}

function handleItemCompleted(params) {
  const item = itemFrom(params)
  if (!item?.id) return

  if (item.type === 'agentMessage') {
    const text = item.text ?? messageBuffers.get(item.id) ?? ''
    messageBuffers.delete(item.id)
    emit({ type: 'message', messageId: item.id, text, complete: true })
    return
  }

  if (item.type === 'reasoning') {
    const text = [...(item.summary ?? []), ...(item.content ?? [])].join('\n').trim()
      || reasoningBuffers.get(item.id) || ''
    reasoningBuffers.delete(item.id)
    if (text) emit({ type: 'thinking', messageId: item.id, text, complete: true })
    return
  }

  if (item.type === 'fileChange') {
    for (const change of item.changes ?? []) {
      if (!change?.path || typeof change.diff !== 'string') continue
      emit({
        type: 'file_changed',
        callId: item.id,
        path: change.path,
        patch: change.diff,
        changeKind: mapChangeKind(change.kind),
      })
    }
  }

  const tool = toolFor(item)
  if (tool) emit({ type: 'tool_finished', callId: item.id, outcome: finishOutcome(item) })
}

function handleNotification(method, params) {
  switch (method) {
    case 'item/agentMessage/delta': {
      const id = params.itemId
      if (!id || !params.delta) return
      const text = (messageBuffers.get(id) ?? '') + params.delta
      messageBuffers.set(id, text)
      emit({ type: 'message', messageId: id, text, complete: false })
      return
    }
    case 'item/reasoning/summaryTextDelta':
    case 'item/reasoning/textDelta': {
      const id = params.itemId
      if (!id || !params.delta) return
      const text = (reasoningBuffers.get(id) ?? '') + params.delta
      reasoningBuffers.set(id, text)
      emit({ type: 'thinking', messageId: id, text, complete: false })
      return
    }
    case 'item/started':
      handleItemStarted(params)
      return
    case 'item/completed':
      handleItemCompleted(params)
      return
    case 'item/commandExecution/outputDelta':
    case 'command/exec/outputDelta':
    case 'item/fileChange/outputDelta':
    case 'item/mcpToolCall/progress':
      if (params.itemId && params.delta) {
        emit({ type: 'tool_progress', callId: params.itemId, chunk: params.delta })
      } else if (params.itemId && params.message) {
        emit({ type: 'tool_progress', callId: params.itemId, chunk: params.message })
      }
      return
    case 'turn/plan/updated':
      emit({
        type: 'task_progress',
        planId: params.turnId ?? `codex-plan-${SESSION_ID}`,
        items: (params.plan ?? []).map(step => ({
          text: step.step ?? '',
          state: step.status === 'completed'
            ? 'completed'
            : step.status === 'inProgress' ? 'in_progress' : 'pending',
        })),
      })
      return
    case 'turn/started':
      activeTurnId = params.turn?.id ?? params.turnId ?? activeTurnId
      emit({ type: 'activity', activity: { kind: 'working', label: 'Working' } })
      return
    case 'turn/completed': {
      const turn = params.turn ?? {}
      if (!activeTurnId || turn.id === activeTurnId || params.turnId === activeTurnId) {
        activeTurnId = null
      }
      if (turn.status === 'failed' && turn.error) {
        emit({
          type: 'error',
          message: turn.error.message ?? 'Codex turn failed.',
          detail: turn.error.codexErrorInfo ? JSON.stringify(turn.error.codexErrorInfo) : null,
          recoverable: true,
        })
      }
      emit({ type: 'activity', activity: { kind: 'idle' } })
      return
    }
    case 'error':
      emit({
        type: 'error',
        message: params.error?.message ?? params.message ?? 'Codex reported an error.',
        detail: params.error?.codexErrorInfo
          ? JSON.stringify(params.error.codexErrorInfo)
          : null,
        recoverable: true,
      })
      return
    case 'warning':
    case 'configWarning':
      diagnostic(params.message ?? JSON.stringify(params))
      return
    default:
      // New app-server notifications must be forward-compatible. If Box cannot
      // represent them honestly, silence is better than a fake activity card.
      return
  }
}

async function handle(command) {
  switch (command?.type) {
    case 'prompt':
      promptChain = promptChain.then(() => runPrompt(command), () => runPrompt(command))
      break
    case 'interrupt':
      await interrupt()
      break
    case 'decision':
      decide(command)
      break
    case 'permission_mode':
      permissionMode = command.mode ?? 'default'
      break
    // Box's current model setting is Claude-specific. Codex deliberately follows
    // its own configured/default model until Box has a per-harness model catalog.
    // Viewport/connect/subagent commands have no honest Codex mapping here yet.
    default:
      break
  }
}

async function shutdown() {
  if (shuttingDown) return
  shuttingDown = true

  for (const [requestId, pending] of pendingApprovals) {
    try { rpc?.respond(pending.rpcId, { decision: 'cancel' }) } catch {}
    emit({ type: 'permission_resolved', requestId, decision: 'deny' })
  }
  pendingApprovals.clear()

  if (threadId && activeTurnId && rpc && !rpc.closed) {
    await rpc.request('turn/interrupt', { threadId, turnId: activeTurnId }).catch(() => {})
  }
  if (rpc?.child) {
    try { rpc.child.stdin.end() } catch {}
    setTimeout(() => {
      try { rpc?.child.kill('SIGTERM') } catch {}
    }, 1000).unref()
  }
  await releaseSessionLock()
}

await acquireSessionLock()
emit({ type: 'session_started', cwd: CWD })
emit({ type: 'activity', activity: { kind: 'idle' } })
// Starting a newly-created/explicitly activated Codex task may pre-fault the native
// App Server while the user is reading or typing. No thread is created until the
// first prompt, and historical transcript replay never launches this adapter.
void startRpc().catch(error => {
  emit({
    type: 'error',
    message: 'Codex App Server could not start.',
    detail: errorMessage(error),
    recoverable: true,
  })
})

const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => {
  if (!line.trim()) return
  try {
    const command = JSON.parse(line)
    void handle(command).catch(error => diagnostic(errorMessage(error)))
  } catch {
    diagnostic('ignored a malformed Box command')
  }
})
input.on('close', () => {
  void shutdown().finally(() => {
    emit({ type: 'session_ended', outcome: { status: 'interrupted' } })
  })
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    void shutdown().finally(() => process.exit(0))
  })
}

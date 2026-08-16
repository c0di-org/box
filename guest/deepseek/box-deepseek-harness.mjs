#!/usr/bin/env node
/**
 * DeepSeek Harness adapter for Box.
 *
 * Box speaks one newline-delimited event vocabulary to every guest harness. DSH
 * already publishes a stable programmatic boundary — its ACP JSON-RPC server —
 * so this wrapper translates between the two rather than reaching into Cordis
 * internals. One wrapper process owns one ACP session for the lifetime of one
 * Box task.
 *
 * The first version intentionally accepts ACP's presentation limits: committed
 * assistant text, cancellation and one-shot permission requests cross the
 * bridge, while DSH reasoning/tool presentation remains in DSH's own session
 * log. Keeping that limitation here makes the integration depend on published
 * APIs and gives Box a useful harness to test before a richer native plugin is
 * justified.
 */

import { spawn } from 'node:child_process'
import { access, mkdir, readFile } from 'node:fs/promises'
import { constants as fsConstants } from 'node:fs'
import { Readable, Writable } from 'node:stream'
import { createInterface } from 'node:readline'
import {
  ClientSideConnection,
  ndJsonStream,
  PROTOCOL_VERSION,
} from '@agentclientprotocol/sdk'

const NODE = process.env.BOX_DEEPSEEK_NODE ?? '/opt/local-agent/deepseek/node/bin/node'
const ACP_BIN = process.env.BOX_DEEPSEEK_ACP_BIN
  ?? '/opt/local-agent/deepseek/app/node_modules/@deepseek-ai/dsh-acp-demo/lib/bin.js'
const CONFIG = process.env.BOX_DEEPSEEK_CONFIG ?? '/opt/local-agent/deepseek/app/box.cordis.yml'
const CWD = process.env.BOX_SESSION_CWD ?? '/workspace'
const DSH_HOME = process.env.DSH_HOME ?? '/workspace/.config/dsh'
const SESSION_ROOT = process.env.DSH_SESSION_ROOT ?? `${DSH_HOME}/sessions`
const API_KEY_FILE = process.env.BOX_DEEPSEEK_API_KEY_FILE
  ?? '/workspace/.config/box/deepseek-api-key'
const ATTACHMENT_WAIT_MS = 10_000
const ATTACHMENT_POLL_MS = 100

let permissionMode = 'default'
let acp = null
let promptChain = Promise.resolve()
let messageCounter = 0
const pendingPermissions = new Map()

function emit(event) {
  process.stdout.write(`${JSON.stringify({ at: Date.now(), ...event })}\n`)
}

function diagnostic(message) {
  process.stderr.write(`[box-deepseek] ${message}\n`)
}

function errorMessage(error) {
  if (error instanceof Error) return error.message
  return String(error)
}

async function readApiKey() {
  if (process.env.DEEPSEEK_API_KEY?.trim()) return process.env.DEEPSEEK_API_KEY.trim()
  try {
    const value = (await readFile(API_KEY_FILE, 'utf8')).trim()
    return value || null
  } catch (error) {
    if (error?.code === 'ENOENT') return null
    throw error
  }
}

async function waitForAttachment(path) {
  const deadline = Date.now() + ATTACHMENT_WAIT_MS
  while (true) {
    try {
      await access(path, fsConstants.R_OK)
      return
    } catch (error) {
      if (Date.now() >= deadline) throw new Error(`attachment did not arrive in the guest: ${path}`)
      await new Promise(resolve => setTimeout(resolve, ATTACHMENT_POLL_MS))
    }
  }
}

export async function promptText(command) {
  const attachments = Array.isArray(command.attachments) ? command.attachments : []
  await Promise.all(attachments.map(item => waitForAttachment(item.guestPath)))
  if (attachments.length === 0) return command.text ?? ''

  const references = attachments.map(item => {
    const name = item.name || item.guestPath.split('/').pop() || 'attachment'
    const mime = item.mimeType ? ` (${item.mimeType})` : ''
    return `- ${name}${mime}: ${item.guestPath}`
  })
  return `${command.text ?? ''}\n\nThe user attached these files. They already exist inside the Box VM; inspect them with your normal file tools when relevant:\n${references.join('\n')}`
}

function permissionResponse(allowed) {
  return allowed
    ? { outcome: { outcome: 'selected', optionId: 'allow-once' } }
    : { outcome: { outcome: 'selected', optionId: 'reject-once' } }
}

async function requestPermission(params) {
  if (permissionMode === 'bypassPermissions') return permissionResponse(true)

  const requestId = `deepseek-${params.toolCall?.toolCallId ?? crypto.randomUUID()}`
  emit({
    type: 'permission_requested',
    requestId,
    ask: {
      kind: 'generic',
      title: 'DeepSeek wants to use a tool',
      description: 'DeepSeek Harness requested permission for the next tool action.',
      alwaysAllowScope: 'deepseek:tools',
    },
  })
  emit({ type: 'activity', activity: { kind: 'awaiting_permission', requestId } })

  const allowed = await new Promise(resolve => {
    pendingPermissions.set(requestId, resolve)
  })
  emit({
    type: 'permission_resolved',
    requestId,
    decision: allowed ? 'allow' : 'deny',
  })
  emit({ type: 'activity', activity: { kind: 'working', label: 'Working' } })
  return permissionResponse(allowed)
}

async function startAcp() {
  if (acp != null) return acp

  const apiKey = await readApiKey()
  if (!apiKey) {
    throw new Error(
      `DeepSeek is not configured. Put a DeepSeek API key in ${API_KEY_FILE} `
      + 'from the Box terminal, then send the task again.',
    )
  }

  await mkdir(DSH_HOME, { recursive: true })
  await mkdir(SESSION_ROOT, { recursive: true })

  const child = spawn(NODE, [ACP_BIN, '--config', CONFIG], {
    cwd: CWD,
    env: {
      ...process.env,
      DEEPSEEK_API_KEY: apiKey,
      DSH_HOME,
      DSH_SESSION_ROOT: SESSION_ROOT,
      BOX_SESSION_CWD: CWD,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', chunk => process.stderr.write(chunk))

  const exited = new Promise((_, reject) => {
    child.once('error', reject)
    child.once('exit', (code, signal) => {
      acp = null
      reject(new Error(`DeepSeek Harness stopped (${signal ?? `exit ${code}`})`))
    })
  })
  // This rejection is consumed by every startup/prompt race below; observing it
  // here also prevents Node from treating a quiet child exit as unhandled.
  exited.catch(() => {})

  const updates = {
    sessionUpdate(params) {
      const update = params.update
      if (update.sessionUpdate !== 'agent_message_chunk') return Promise.resolve()
      if (update.content?.type !== 'text' || !update.content.text) return Promise.resolve()
      emit({
        type: 'message',
        messageId: `deepseek-message-${++messageCounter}`,
        text: update.content.text,
        complete: true,
      })
      return Promise.resolve()
    },
    requestPermission,
  }

  const conn = new ClientSideConnection(
    () => updates,
    ndJsonStream(
      Writable.toWeb(child.stdin),
      Readable.toWeb(child.stdout),
    ),
  )

  await Promise.race([
    (async () => {
      await conn.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities: {} })
      const created = await conn.newSession({ cwd: CWD, mcpServers: [] })
      acp = { child, conn, sessionId: created.sessionId, exited }
    })(),
    exited,
  ])

  return acp
}

async function runPrompt(command) {
  const text = await promptText(command)
  emit({
    type: 'user_message',
    text: command.text ?? '',
    attachments: Array.isArray(command.attachments) ? command.attachments : [],
  })
  emit({ type: 'activity', activity: { kind: 'thinking', label: 'DeepSeek' } })

  try {
    const state = await startAcp()
    await Promise.race([
      state.conn.prompt({
        sessionId: state.sessionId,
        prompt: [{ type: 'text', text }],
      }),
      state.exited,
    ])
  } catch (error) {
    emit({
      type: 'error',
      message: 'DeepSeek Harness could not complete this turn.',
      detail: errorMessage(error),
      recoverable: true,
    })
    diagnostic(errorMessage(error))
  } finally {
    emit({ type: 'activity', activity: { kind: 'idle' } })
  }
}

async function interrupt() {
  if (acp == null) return
  try {
    await acp.conn.cancel({ sessionId: acp.sessionId })
  } catch (error) {
    diagnostic(`cancel failed: ${errorMessage(error)}`)
  }
}

function decide(command) {
  const resolve = pendingPermissions.get(command.requestId)
  if (resolve == null) return
  pendingPermissions.delete(command.requestId)
  resolve(command.decision === 'allow' || command.decision === 'allow_always')
}

async function handle(command) {
  switch (command?.type) {
    case 'prompt':
      // Box can enqueue input while a turn is still settling. ACP permits one
      // prompt in flight per session, so preserve the user's ordering here.
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
    // Claude-specific model switching, viewport hints, Box connect results and
    // targeted subagent interruption are deliberately ignored in this ACP-backed
    // first integration. Unknown commands are forward-compatible by design.
    default:
      break
  }
}

async function shutdown() {
  for (const resolve of pendingPermissions.values()) resolve(false)
  pendingPermissions.clear()
  if (acp != null) {
    try { acp.child.stdin.end() } catch {}
    setTimeout(() => {
      try { acp?.child.kill('SIGTERM') } catch {}
    }, 1000).unref()
  }
}

emit({ type: 'session_started', cwd: CWD })
emit({ type: 'activity', activity: { kind: 'idle' } })

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

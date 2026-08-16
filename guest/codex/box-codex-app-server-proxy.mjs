#!/usr/bin/env node
import { spawn } from 'node:child_process'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { createInterface } from 'node:readline'

const NATIVE = process.env.BOX_CODEX_NATIVE_APP_SERVER ?? '/opt/local-agent/codex/bin/codex-app-server'
const WORKSPACE = process.env.BOX_SESSION_CWD ?? '/workspace'
const SOCKET = process.env.BOX_TOOL_SOCKET ?? ''
const MODEL_STATE = process.env.BOX_HARNESS_MODEL_STATE ?? resolve(WORKSPACE, '.config/box/harness-models.json')
const MCP_SERVER = process.env.BOX_CODEX_MCP_SERVER ?? '/opt/local-agent/box-tools/box-mcp-server.mjs'
const CODEX_VERSION = process.env.BOX_CODEX_VERSION ?? '0.147.0'

const child = spawn(NATIVE, process.argv.slice(2), {
  cwd: WORKSPACE,
  env: process.env,
  stdio: ['pipe', 'pipe', 'pipe'],
})
child.stderr.pipe(process.stderr)

let sequence = 0
const pending = new Map()

function write(stream, value) {
  stream.write(`${JSON.stringify(value)}\n`)
}

async function selectedModel() {
  try {
    const state = JSON.parse(await readFile(MODEL_STATE, 'utf8'))
    const selection = state?.selections?.codex
    if (!selection?.model) return null
    if (selection.codexVersion && selection.codexVersion !== CODEX_VERSION) return null
    return String(selection.model)
  } catch {
    return null
  }
}

async function clearSelectedModel() {
  try {
    const state = JSON.parse(await readFile(MODEL_STATE, 'utf8'))
    if (!state?.selections?.codex) return
    delete state.selections.codex
    await mkdir(dirname(MODEL_STATE), { recursive: true })
    const temp = `${MODEL_STATE}.${process.pid}.tmp`
    await writeFile(temp, `${JSON.stringify(state)}\n`, { mode: 0o600 })
    await rename(temp, MODEL_STATE)
  } catch {}
}

function withBoxMcp(config) {
  if (!SOCKET) return config ?? null
  const current = config && typeof config === 'object' && !Array.isArray(config) ? config : {}
  const servers = current.mcp_servers && typeof current.mcp_servers === 'object'
    ? current.mcp_servers
    : {}
  return {
    ...current,
    mcp_servers: {
      ...servers,
      box: {
        command: '/usr/bin/node',
        args: [MCP_SERVER],
        env: {
          BOX_TOOL_SOCKET: SOCKET,
          BOX_SESSION_CWD: WORKSPACE,
        },
        required: true,
        tool_timeout_sec: 900,
      },
    },
  }
}

async function mutateRequest(message) {
  const method = message.method
  const params = message.params && typeof message.params === 'object' ? { ...message.params } : {}
  const model = await selectedModel()
  let injectedModel = false

  if (method === 'thread/start' || method === 'thread/resume') {
    params.config = withBoxMcp(params.config)
  }
  if ((method === 'thread/start' || method === 'thread/resume' || method === 'turn/start') && model) {
    params.model = model
    injectedModel = true
  }
  return { ...message, params, injectedModel, model }
}

function modelRejected(error) {
  const text = JSON.stringify(error ?? '').toLowerCase()
  return text.includes('model') && (
    text.includes('unsupported') || text.includes('not found') || text.includes('unknown')
    || text.includes('unavailable') || text.includes('not available') || text.includes('invalid')
  )
}

async function sendClientRequest(original, retryWithoutModel = false) {
  const mutated = await mutateRequest(original)
  if (retryWithoutModel) delete mutated.params.model
  const nativeId = `box-client-${process.pid}-${++sequence}`
  pending.set(nativeId, {
    originalId: original.id,
    original,
    injectedModel: mutated.injectedModel && !retryWithoutModel,
    retried: retryWithoutModel,
  })
  const { injectedModel: _injected, model: _model, ...wire } = mutated
  write(child.stdin, { ...wire, id: nativeId })
}

const fromClient = createInterface({ input: process.stdin, crlfDelay: Infinity })
fromClient.on('line', line => {
  if (!line.trim()) return
  let message
  try { message = JSON.parse(line) } catch {
    child.stdin.write(`${line}\n`)
    return
  }
  if (message.method && Object.prototype.hasOwnProperty.call(message, 'id')) {
    void sendClientRequest(message)
  } else {
    write(child.stdin, message)
  }
})

const fromServer = createInterface({ input: child.stdout, crlfDelay: Infinity })
fromServer.on('line', line => {
  if (!line.trim()) return
  let message
  try { message = JSON.parse(line) } catch {
    process.stdout.write(`${line}\n`)
    return
  }
  const record = pending.get(String(message.id))
  if (!record) {
    write(process.stdout, message)
    return
  }
  pending.delete(String(message.id))
  if (message.error && record.injectedModel && !record.retried && modelRejected(message.error)) {
    void clearSelectedModel().finally(() => sendClientRequest(record.original, true))
    return
  }
  write(process.stdout, { ...message, id: record.originalId })
})

child.on('exit', (code, signal) => {
  for (const record of pending.values()) {
    write(process.stdout, {
      id: record.originalId,
      error: { code: -32000, message: `Codex App Server exited (${signal ?? code})` },
    })
  }
  pending.clear()
  process.exitCode = code ?? 1
})

process.stdin.on('end', () => child.stdin.end())
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => { try { child.kill(signal) } catch {} })
}

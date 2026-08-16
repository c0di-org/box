#!/usr/bin/env node
import net from 'node:net'
import { randomUUID } from 'node:crypto'
import { chmod, mkdir, realpath, rm, stat } from 'node:fs/promises'
import { dirname, extname, isAbsolute, resolve, sep } from 'node:path'
import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'

const BASE_HARNESS = process.env.BOX_CODEX_BASE_HARNESS ?? '/opt/local-agent/codex/box-codex-harness.mjs'
const PROXY = process.env.BOX_CODEX_APP_SERVER_PROXY ?? '/opt/local-agent/codex/box-codex-app-server-proxy.mjs'
const CWD = resolve(process.env.BOX_SESSION_CWD ?? '/workspace')
const SESSION_ID = process.env.BOX_SESSION_ID ?? 'codex'
const SOCKET = process.env.BOX_TOOL_SOCKET
  ?? resolve(CWD, '.config/box/codex/bridge', `${SESSION_ID}.sock`)
const pendingConnects = new Map()

function emit(event) {
  process.stdout.write(`${JSON.stringify({ v: 1, at: Date.now(), ...event })}\n`)
}
function reply(socket, id, value) {
  socket.write(`${JSON.stringify({ id, ...value })}\n`)
}
function mimeFor(path) {
  switch (extname(path).toLowerCase()) {
    case '.png': return 'image/png'
    case '.jpg': case '.jpeg': return 'image/jpeg'
    case '.gif': return 'image/gif'
    case '.webp': return 'image/webp'
    case '.pdf': return 'application/pdf'
    case '.html': return 'text/html'
    case '.json': return 'application/json'
    case '.md': return 'text/markdown'
    case '.txt': return 'text/plain'
    default: return 'application/octet-stream'
  }
}

async function safeWorkspaceFile(rawPath) {
  const given = String(rawPath ?? '').trim()
  if (!given) throw new Error('No path was given.')
  if (!isAbsolute(given)) throw new Error('Box show requires an absolute workspace path.')
  const root = await realpath(CWD)
  const target = await realpath(resolve(given))
  if (target !== root && !target.startsWith(`${root}${sep}`)) {
    throw new Error('Box will only show files inside this task workspace.')
  }
  const info = await stat(target)
  if (!info.isFile()) throw new Error('Box can only show regular files.')
  return target
}

async function handleShow(args) {
  const kind = String(args?.kind ?? (args?.path ? 'document' : '')).toLowerCase()
  if (kind === 'computer') {
    emit({ type: 'artifact', kind: 'computer' })
    return { message: 'The Box desktop is available to the person.' }
  }
  if (kind === 'preview') {
    const guestPort = Number(args?.guestPort)
    if (!Number.isInteger(guestPort) || guestPort < 1 || guestPort > 65535) {
      throw new Error('A preview needs a valid guestPort.')
    }
    const raw = String(args?.url ?? `http://127.0.0.1:${guestPort}/`)
    const url = new URL(raw)
    if (!['127.0.0.1', 'localhost', '::1'].includes(url.hostname)) {
      throw new Error('Box previews must use a guest loopback URL.')
    }
    emit({ type: 'artifact', kind: 'preview', url: raw, guestPort })
    return { message: 'The local preview is available in Box.' }
  }
  if (kind !== 'document') throw new Error(`Unsupported Box artifact kind: ${kind || 'missing'}`)
  const guestPath = await safeWorkspaceFile(args?.path)
  const name = String(args?.name ?? guestPath.split(sep).pop() ?? 'artifact').slice(0, 200)
  const mimeType = String(args?.mimeType ?? mimeFor(guestPath)).slice(0, 120)
  emit({ type: 'artifact', kind: 'document', guestPath, name, mimeType })
  return { message: `${name} is available in Box.` }
}

async function startBridge() {
  await mkdir(dirname(SOCKET), { recursive: true })
  await rm(SOCKET, { force: true })
  const server = net.createServer(socket => {
    socket.setEncoding('utf8')
    let buffer = ''
    socket.on('data', chunk => {
      buffer += chunk
      let newline
      while ((newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline)
        buffer = buffer.slice(newline + 1)
        if (!line.trim()) continue
        void (async () => {
          let request
          try { request = JSON.parse(line) } catch {
            reply(socket, null, { ok: false, error: 'Malformed Box bridge request' })
            return
          }
          try {
            if (request.kind === 'show') {
              reply(socket, request.id, { ok: true, result: await handleShow(request.args) })
              return
            }
            if (request.kind === 'connect') {
              const service = String(request.args?.service ?? '')
              if (service !== 'github') throw new Error(`Box cannot connect ${service || 'that service'}.`)
              const requestId = `codex-connect-${randomUUID()}`
              pendingConnects.set(requestId, { socket, bridgeId: request.id })
              emit({
                type: 'connect_requested', requestId, service: 'github',
                reason: String(request.args?.reason ?? '').slice(0, 400) || null,
              })
              return
            }
            throw new Error(`Unsupported Box bridge request: ${request.kind}`)
          } catch (error) {
            reply(socket, request.id, { ok: false, error: error instanceof Error ? error.message : String(error) })
          }
        })()
      }
    })
  })
  await new Promise((resolveListen, reject) => {
    server.once('error', reject)
    server.listen(SOCKET, () => { server.off('error', reject); resolveListen() })
  })
  await chmod(SOCKET, 0o600).catch(() => {})
  return server
}

const bridge = await startBridge()
const child = spawn(process.execPath, [BASE_HARNESS], {
  cwd: CWD,
  env: {
    ...process.env,
    BOX_CODEX_APP_SERVER: PROXY,
    BOX_TOOL_SOCKET: SOCKET,
  },
  stdio: ['pipe', 'pipe', 'pipe'],
})
child.stderr.pipe(process.stderr)
const childOut = createInterface({ input: child.stdout, crlfDelay: Infinity })
childOut.on('line', line => process.stdout.write(`${line}\n`))

const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => {
  if (!line.trim()) return
  let command
  try { command = JSON.parse(line) } catch {
    child.stdin.write(`${line}\n`)
    return
  }
  if (command.type === 'connect_result') {
    const pending = pendingConnects.get(command.requestId)
    if (!pending) return
    pendingConnects.delete(command.requestId)
    const result = {
      connected: Boolean(command.connected),
      ...(command.login ? { login: String(command.login) } : {}),
      ...(Number.isInteger(command.repositories) ? { repositories: command.repositories } : {}),
    }
    reply(pending.socket, pending.bridgeId, { ok: true, result })
    emit({ type: 'connect_resolved', requestId: command.requestId, connected: result.connected })
    return
  }
  child.stdin.write(`${line}\n`)
})
input.on('close', () => child.stdin.end())

async function shutdown() {
  for (const [requestId, pending] of pendingConnects) {
    reply(pending.socket, pending.bridgeId, { ok: true, result: { connected: false } })
    emit({ type: 'connect_resolved', requestId, connected: false })
  }
  pendingConnects.clear()
  bridge.close()
  await rm(SOCKET, { force: true }).catch(() => {})
  try { child.kill('SIGTERM') } catch {}
}
child.on('exit', async (code, signal) => {
  bridge.close()
  await rm(SOCKET, { force: true }).catch(() => {})
  process.exitCode = code ?? (signal ? 1 : 0)
})
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => { void shutdown() })
}

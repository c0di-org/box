import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createInterface } from 'node:readline'
import test from 'node:test'

const ROOT = new URL('..', import.meta.url).pathname
const CONTROL = join(ROOT, 'box-codex-control.mjs')
const PROXY = join(ROOT, 'box-codex-app-server-proxy.mjs')
const PRODUCT = join(ROOT, 'box-codex-product-harness.mjs')
const MCP = new URL('../../box-tools/box-mcp-server.mjs', import.meta.url).pathname
const MOCK = new URL('./mock-product-app-server.mjs', import.meta.url).pathname
const FAKE = new URL('./fake-base-harness.mjs', import.meta.url).pathname

function linesOf(stream) {
  const values = []
  const lines = createInterface({ input: stream, crlfDelay: Infinity })
  lines.on('line', line => { try { values.push(JSON.parse(line)) } catch {} })
  return values
}
async function waitFor(values, predicate, timeout = 3000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    const found = values.find(predicate)
    if (found) return found
    await new Promise(r => setTimeout(r, 10))
  }
  throw new Error(`timeout; values=${JSON.stringify(values)}`)
}
async function runControl(root, operation, args = [], extraEnv = {}, onEvent = null) {
  const workspace = join(root, 'workspace')
  const home = join(workspace, '.config/codex')
  await mkdir(workspace, { recursive: true })
  const child = spawn(process.execPath, [CONTROL, operation, ...args], {
    cwd: workspace,
    env: { ...process.env, BOX_CODEX_NATIVE_APP_SERVER: MOCK, BOX_SESSION_CWD: workspace, CODEX_HOME: home, ...extraEnv },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  const output = linesOf(child.stdout)
  if (onEvent) onEvent(child, output)
  let stderr = ''
  child.stderr.setEncoding('utf8'); child.stderr.on('data', c => { stderr += c })
  const code = await new Promise(resolve => child.once('exit', resolve))
  return { code, output, stderr, workspace, home }
}

test('account device flow stays ephemeral and survives a fresh control process', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const first = await runControl(root, 'account-login')
  assert.equal(first.code, 0)
  const device = first.output.find(e => e.type === 'auth_device_code')
  assert.deepEqual(device, { type: 'auth_device_code', verificationUrl: 'https://auth.example/device', userCode: 'ABCD-EFGH' })
  assert.ok(first.output.some(e => e.type === 'account_state' && e.state === 'signed_in' && e.account === 'dev@example.com'))
  assert.doesNotMatch(JSON.stringify(first.output), /RAW_SECRET_NEVER_FORWARD/)
  assert.equal(first.stderr, '')

  const restarted = await runControl(root, 'account-status')
  assert.ok(restarted.output.some(e => e.type === 'account_state' && e.state === 'signed_in'))
})

test('account logout clears the persisted App Server account state', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const login = await runControl(root, 'account-login')
  assert.equal(login.code, 0)
  const logout = await runControl(root, 'account-logout')
  assert.equal(logout.code, 0)
  assert.ok(logout.output.some(e => e.type === 'account_state' && e.state === 'signed_out'))
  const restarted = await runControl(root, 'account-status')
  assert.ok(restarted.output.some(e => e.type === 'account_state' && e.state === 'signed_out'))
})

test('account login cancellation calls the official cancel method and reports cancellation', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  let cancelled = false
  const result = await runControl(root, 'account-login', [], { MOCK_LOGIN_FAIL: '0' }, (child, output) => {
    void (async () => {
      await waitFor(output, e => e.type === 'auth_device_code')
      child.stdin.write(`${JSON.stringify({ type: 'cancel' })}\n`)
      cancelled = true
    })()
  })
  assert.ok(cancelled)
  assert.ok(result.output.some(e => e.type === 'auth_cancelled'))
})

test('account login failure is represented without credential material', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const result = await runControl(root, 'account-login', [], { MOCK_LOGIN_FAIL: '1' })
  assert.equal(result.code, 1)
  assert.ok(result.output.some(e => e.type === 'auth_failed' && e.message === 'denied'))
  assert.doesNotMatch(JSON.stringify(result.output), /RAW_SECRET_NEVER_FORWARD/)
})

test('Codex model catalog selection is dynamic, per-harness, persisted, and stale choices fall back', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  let result = await runControl(root, 'models')
  const catalog = result.output.find(e => e.type === 'model_catalog')
  assert.deepEqual(catalog.models.map(m => m.id), ['gpt-box-default', 'gpt-box-fast'])
  assert.equal(catalog.selected, null)

  await runControl(root, 'model-set', ['gpt-box-fast'])
  const statePath = join(result.workspace, '.config/box/harness-models.json')
  let state = JSON.parse(await readFile(statePath, 'utf8'))
  assert.equal(state.selections.codex.model, 'gpt-box-fast')
  // An unrelated provider's state survives Codex writes.
  state.selections['future-harness'] = { model: 'other' }
  await writeFile(statePath, JSON.stringify(state))
  await runControl(root, 'model-set', ['gpt-box-default'])
  state = JSON.parse(await readFile(statePath, 'utf8'))
  assert.equal(state.selections['future-harness'].model, 'other')
  assert.equal(state.selections.codex.model, 'gpt-box-default')

  const missing = await runControl(root, 'model-set', ['model-that-disappeared'])
  assert.ok(missing.output.some(e => e.type === 'model_selected' && e.fallback === true && e.selected === null))
  state = JSON.parse(await readFile(statePath, 'utf8'))
  assert.equal(state.selections.codex, undefined)
})

async function startProxy(root, extraEnv = {}) {
  const workspace = join(root, 'workspace')
  await mkdir(workspace, { recursive: true })
  const modelState = join(workspace, '.config/box/harness-models.json')
  const log = join(root, 'proxy-rpc.jsonl')
  const child = spawn(process.execPath, [PROXY, '--listen', 'stdio://'], {
    cwd: workspace,
    env: { ...process.env, BOX_CODEX_NATIVE_APP_SERVER: MOCK, BOX_SESSION_CWD: workspace, BOX_HARNESS_MODEL_STATE: modelState, MOCK_CODEX_LOG: log, BOX_TOOL_SOCKET: join(root, 'box.sock'), ...extraEnv },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  const output = linesOf(child.stdout)
  return { child, output, workspace, modelState, log }
}
function rpc(child, output, id, method, params) {
  child.stdin.write(`${JSON.stringify({ id, method, ...(params === undefined ? {} : { params }) })}\n`)
  return waitFor(output, m => m.id === id)
}

test('App Server proxy injects Box MCP plus selected model into supported official calls', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const p = await startProxy(root)
  await mkdir(join(p.workspace, '.config/box'), { recursive: true })
  await writeFile(p.modelState, JSON.stringify({ version: 1, selections: { codex: { model: 'gpt-box-fast', codexVersion: '0.147.0' } } }))
  await rpc(p.child, p.output, 1, 'initialize', { clientInfo: { name: 'test', version: '1' }, capabilities: {} })
  await rpc(p.child, p.output, 2, 'thread/start', { cwd: p.workspace })
  await rpc(p.child, p.output, 3, 'turn/start', { threadId: 'thread-1', input: [] })
  p.child.stdin.end(); await new Promise(r => p.child.once('exit', r))
  const log = (await readFile(p.log, 'utf8')).trim().split('\n').map(JSON.parse)
  const start = log.find(m => m.method === 'thread/start')
  assert.equal(start.params.model, 'gpt-box-fast')
  assert.equal(start.params.config.mcp_servers.box.command, '/usr/bin/node')
  assert.match(start.params.config.mcp_servers.box.args[0], /box-tools\/box-mcp-server\.mjs$/)
  assert.equal(start.params.config.mcp_servers.box.env.BOX_SESSION_CWD, p.workspace)
  assert.equal(log.find(m => m.method === 'turn/start').params.model, 'gpt-box-fast')
})

test('a running proxy picks up a persisted model change on the next turn', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const p = await startProxy(root)
  await mkdir(join(p.workspace, '.config/box'), { recursive: true })
  await writeFile(p.modelState, JSON.stringify({ version: 1, selections: { codex: { model: 'gpt-box-fast', codexVersion: '0.147.0' } } }))
  await rpc(p.child, p.output, 1, 'turn/start', { threadId: 'thread-1', input: [] })
  await writeFile(p.modelState, JSON.stringify({ version: 1, selections: { codex: { model: 'gpt-box-default', codexVersion: '0.147.0' } } }))
  await rpc(p.child, p.output, 2, 'turn/start', { threadId: 'thread-1', input: [] })
  p.child.stdin.end(); await new Promise(r => p.child.once('exit', r))
  const turns = (await readFile(p.log, 'utf8')).trim().split('\n').map(JSON.parse).filter(m => m.method === 'turn/start')
  assert.deepEqual(turns.map(m => m.params.model), ['gpt-box-fast', 'gpt-box-default'])
})

test('unsupported persisted model retries the rejected request once without overriding App Server', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const p = await startProxy(root, { MOCK_REJECT_MODEL: '1' })
  await mkdir(join(p.workspace, '.config/box'), { recursive: true })
  await writeFile(p.modelState, JSON.stringify({ version: 1, selections: { codex: { model: 'gpt-box-fast', codexVersion: '0.147.0' } } }))
  const response = await rpc(p.child, p.output, 1, 'thread/start', { cwd: p.workspace })
  assert.ok(response.result?.thread)
  const state = JSON.parse(await readFile(p.modelState, 'utf8'))
  assert.equal(state.selections.codex, undefined)
  p.child.stdin.end(); await new Promise(r => p.child.once('exit', r))
  const log = (await readFile(p.log, 'utf8')).trim().split('\n').map(JSON.parse).filter(m => m.method === 'thread/start')
  assert.equal(log.length, 2)
  assert.equal(log[0].params.model, 'gpt-box-fast')
  assert.equal(log[1].params.model, undefined)
})

async function startProduct(root) {
  const workspace = join(root, 'workspace')
  const socket = join(root, 'bridge.sock')
  await mkdir(workspace, { recursive: true })
  const child = spawn(process.execPath, [PRODUCT], {
    cwd: workspace,
    env: { ...process.env, BOX_CODEX_BASE_HARNESS: FAKE, BOX_CODEX_APP_SERVER_PROXY: PROXY, BOX_SESSION_CWD: workspace, BOX_SESSION_ID: 'product-test', BOX_TOOL_SOCKET: socket },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  const output = linesOf(child.stdout)
  await waitFor(output, e => e.type === 'session_started')
  return { child, output, workspace, socket }
}
async function mcpCall(p, id, name, args) {
  const child = spawn(process.execPath, [MCP], { env: { ...process.env, BOX_TOOL_SOCKET: p.socket, BOX_SESSION_CWD: p.workspace }, stdio: ['pipe', 'pipe', 'pipe'] })
  const output = linesOf(child.stdout)
  child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2024-11-05' } })}\n`)
  await waitFor(output, e => e.id === 1)
  child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args } })}\n`)
  return { child, result: await waitFor(output, e => e.id === id) }
}

test('Box MCP show round-trips files/images and rejects path escape without a fake Codex event', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const p = await startProduct(root)
  const image = join(p.workspace, 'result.png'); await writeFile(image, 'png')
  let call = await mcpCall(p, 2, 'show', { kind: 'document', path: image })
  assert.equal(call.result.result.isError, false)
  const artifact = await waitFor(p.output, e => e.type === 'artifact' && e.kind === 'document')
  assert.equal(artifact.guestPath, image); assert.equal(artifact.mimeType, 'image/png')
  call.child.stdin.end(); call.child.kill()

  const outside = join(root, 'outside.txt'); await writeFile(outside, 'secret')
  const before = p.output.filter(e => e.type === 'artifact').length
  call = await mcpCall(p, 3, 'show', { kind: 'document', path: outside })
  assert.equal(call.result.result.isError, true)
  await new Promise(r => setTimeout(r, 50))
  assert.equal(p.output.filter(e => e.type === 'artifact').length, before)
  call.child.stdin.end(); call.child.kill(); p.child.stdin.end(); p.child.kill()
})

test('Box MCP GitHub request uses existing connect wire and returns only non-secret resolution', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const p = await startProduct(root)
  const callPromise = mcpCall(p, 7, 'connect', { service: 'github', reason: 'clone a private repository' })
  const request = await waitFor(p.output, e => e.type === 'connect_requested')
  assert.equal(request.service, 'github')
  p.child.stdin.write(`${JSON.stringify({ type: 'connect_result', requestId: request.requestId, connected: true, login: 'octocat', repositories: 3, token: 'SHOULD_NOT_EXIST' })}\n`)
  const call = await callPromise
  assert.equal(call.result.result.structuredContent.connected, true)
  assert.equal(call.result.result.structuredContent.login, 'octocat')
  assert.doesNotMatch(JSON.stringify(call.result), /SHOULD_NOT_EXIST/)
  await waitFor(p.output, e => e.type === 'connect_resolved' && e.requestId === request.requestId && e.connected === true)
  call.child.stdin.end(); call.child.kill(); p.child.stdin.end(); p.child.kill()
})

test('Box MCP GitHub decline returns an honest disconnected result', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-product-'))
  const p = await startProduct(root)
  const callPromise = mcpCall(p, 9, 'connect', { service: 'github', reason: 'read a private issue' })
  const request = await waitFor(p.output, e => e.type === 'connect_requested')
  p.child.stdin.write(`${JSON.stringify({ type: 'connect_result', requestId: request.requestId, connected: false })}\n`)
  const call = await callPromise
  assert.equal(call.result.result.structuredContent.connected, false)
  assert.match(call.result.result.content[0].text, /not connected/i)
  await waitFor(p.output, e => e.type === 'connect_resolved' && e.requestId === request.requestId && e.connected === false)
  call.child.stdin.end(); call.child.kill(); p.child.stdin.end(); p.child.kill()
})

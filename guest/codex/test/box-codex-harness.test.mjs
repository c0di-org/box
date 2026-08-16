import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createInterface } from 'node:readline'
import test from 'node:test'

const HARNESS = new URL('../box-codex-harness.mjs', import.meta.url).pathname
const MOCK = new URL('./mock-codex-app-server.mjs', import.meta.url).pathname

async function startHarness(root, extraEnv = {}) {
  const workspace = join(root, 'workspace')
  const stateRoot = join(workspace, '.config/box/codex/sessions')
  const log = join(root, `rpc-${Date.now()}-${Math.random()}.jsonl`)
  await mkdir(workspace, { recursive: true })
  const child = spawn(process.execPath, [HARNESS], {
    cwd: workspace,
    env: {
      ...process.env,
      BOX_CODEX_APP_SERVER: MOCK,
      BOX_SESSION_CWD: workspace,
      BOX_SESSION_ID: 'session-test',
      BOX_CODEX_STATE_ROOT: stateRoot,
      CODEX_HOME: join(workspace, '.config/codex'),
      MOCK_CODEX_LOG: log,
      ...extraEnv,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  const events = []
  const lines = createInterface({ input: child.stdout, crlfDelay: Infinity })
  lines.on('line', line => events.push(JSON.parse(line)))
  let stderr = ''
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', chunk => { stderr += chunk })

  async function waitFor(predicate, timeoutMs = 3000) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const found = events.find(predicate)
      if (found) return found
      await new Promise(resolve => setTimeout(resolve, 10))
    }
    throw new Error(`timed out waiting for event; stderr=${stderr}; events=${JSON.stringify(events)}`)
  }
  return { child, events, log, waitFor, workspace, stateRoot, stderr: () => stderr }
}

async function stop(h) {
  h.child.stdin.end()
  await new Promise(resolve => h.child.once('exit', resolve))
}

async function rpcLog(path) {
  const text = await readFile(path, 'utf8')
  return text.trim().split('\n').filter(Boolean).map(line => JSON.parse(line))
}

test('creates and atomically persists a Codex thread before the first turn', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-'))
  const h = await startHarness(root)
  await h.waitFor(e => e.type === 'session_started')
  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'hello', attachments: [] })}\n`)
  await h.waitFor(e => e.type === 'message' && e.complete === true)

  const state = JSON.parse(await readFile(join(h.stateRoot, 'session-test.json'), 'utf8'))
  assert.equal(state.threadId, 'thread-box-test')
  const log = await rpcLog(h.log)
  const start = log.find(m => m.method === 'thread/start')
  const turn = log.find(m => m.method === 'turn/start')
  assert.equal(start.params.cwd, h.workspace)
  assert.equal(start.params.sandbox, 'danger-full-access')
  assert.equal(start.params.approvalPolicy, 'untrusted')
  assert.deepEqual(turn.params.input, [{ type: 'text', text: 'hello', text_elements: [] }])
  await stop(h)
})

test('recreates the wrapper by resuming the persisted thread, never starting a replacement', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-'))
  let h = await startHarness(root)
  await h.waitFor(e => e.type === 'session_started')
  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'first', attachments: [] })}\n`)
  await h.waitFor(e => e.type === 'message' && e.complete === true)
  await stop(h)

  h = await startHarness(root)
  await h.waitFor(e => e.type === 'session_started')
  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'second', attachments: [] })}\n`)
  await h.waitFor(e => e.type === 'message' && e.complete === true)
  const log = await rpcLog(h.log)
  assert.ok(log.some(m => m.method === 'thread/resume' && m.params.threadId === 'thread-box-test'))
  assert.ok(!log.some(m => m.method === 'thread/start'))
  await stop(h)
})

test('resume failure is surfaced and does not silently create a fresh model thread', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-'))
  const stateRoot = join(root, 'workspace/.config/box/codex/sessions')
  await mkdir(stateRoot, { recursive: true })
  await writeFile(join(stateRoot, 'session-test.json'), JSON.stringify({ version: 1, threadId: 'missing-thread', codexVersion: '0.147.0' }))
  const h = await startHarness(root, { MOCK_RESUME_FAIL: '1' })
  await h.waitFor(e => e.type === 'session_started')
  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'continue', attachments: [] })}\n`)
  const error = await h.waitFor(e => e.type === 'error' && String(e.detail).includes('no replacement thread was created'))
  assert.match(error.detail, /missing-thread/)
  const log = await rpcLog(h.log)
  assert.ok(log.some(m => m.method === 'thread/resume'))
  assert.ok(!log.some(m => m.method === 'thread/start'))
  await stop(h)
})

test('maps rich Codex events, approvals, and official interrupt to Box wire events', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-'))
  const h = await startHarness(root)
  await h.waitFor(e => e.type === 'session_started')
  h.child.stdin.write(`${JSON.stringify({ type: 'permission_mode', mode: 'acceptEdits' })}\n`)
  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'tools', attachments: [] })}\n`)

  await h.waitFor(e => e.type === 'tool_started' && e.callId === 'cmd-1')
  await h.waitFor(e => e.type === 'tool_progress' && e.callId === 'cmd-1')
  const permission = await h.waitFor(e => e.type === 'permission_requested')
  assert.equal(permission.ask.kind, 'run_command')
  h.child.stdin.write(`${JSON.stringify({ type: 'decision', requestId: permission.requestId, decision: 'allow_always' })}\n`)
  await h.waitFor(e => e.type === 'permission_resolved' && e.decision === 'allow_always')
  h.child.stdin.write(`${JSON.stringify({ type: 'interrupt' })}\n`)
  const deadline = Date.now() + 3000
  let log = []
  while (Date.now() < deadline) {
    log = await rpcLog(h.log)
    if (log.some(m => m.method === 'turn/interrupt')) break
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  assert.equal(log.find(m => m.method === 'thread/start').params.approvalPolicy, 'on-request')
  assert.ok(log.some(m => m.id === 900 && m.result?.decision === 'acceptForSession'))
  assert.ok(log.some(m => m.method === 'turn/interrupt'))
  await stop(h)
})

test('uses structured local image input and honest workspace paths for other attachments', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-'))
  const h = await startHarness(root)
  await h.waitFor(e => e.type === 'session_started')
  const image = join(h.workspace, 'image.png')
  const pdf = join(h.workspace, 'notes.pdf')
  await writeFile(image, 'fake')
  await writeFile(pdf, 'fake')
  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'inspect', attachments: [
    { guestPath: image, name: 'image.png', mimeType: 'image/png' },
    { guestPath: pdf, name: 'notes.pdf', mimeType: 'application/pdf' },
  ] })}\n`)
  await h.waitFor(e => e.type === 'message' && e.complete === true)
  const log = await rpcLog(h.log)
  const turn = log.find(m => m.method === 'turn/start')
  assert.ok(turn.params.input.some(i => i.type === 'localImage' && i.path === image))
  assert.match(turn.params.input[0].text, /notes\.pdf .*application\/pdf.*notes\.pdf/s)
  await stop(h)
})

test('restarts a crashed App Server and resumes the durable thread before the next turn', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-'))
  const marker = join(root, 'crashed-once')
  const h = await startHarness(root, { MOCK_CRASH_ON_TURN: '1', MOCK_CRASH_MARKER: marker })
  await h.waitFor(e => e.type === 'session_started')
  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'crash once', attachments: [] })}\n`)
  await h.waitFor(e => e.type === 'error' && e.message === 'Codex App Server stopped unexpectedly.')

  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'continue after crash', attachments: [] })}\n`)
  await h.waitFor(e => e.type === 'message' && e.complete === true)
  const log = await rpcLog(h.log)
  assert.equal(log.filter(m => m.method === 'thread/start').length, 1)
  assert.ok(log.some(m => m.method === 'thread/resume' && m.params.threadId === 'thread-box-test'))
  assert.ok(log.some(m => m.method === 'turn/start' && m.params.input?.[0]?.text === 'continue after crash'))
  await stop(h)
})

test('serializes eager App Server initialization with the first prompt', async () => {
  const root = await mkdtemp(join(tmpdir(), 'box-codex-'))
  const h = await startHarness(root, { MOCK_INITIALIZE_DELAY_MS: '200' })
  await h.waitFor(e => e.type === 'session_started')
  h.child.stdin.write(`${JSON.stringify({ type: 'prompt', text: 'race init', attachments: [] })}\n`)
  await h.waitFor(e => e.type === 'message' && e.complete === true)
  const log = await rpcLog(h.log)
  assert.equal(log.filter(m => m.method === 'initialize').length, 1)
  assert.ok(log.findIndex(m => m.method === 'initialize') < log.findIndex(m => m.method === 'account/read'))
  await stop(h)
})

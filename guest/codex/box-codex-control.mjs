#!/usr/bin/env node
import { spawn } from 'node:child_process'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { createInterface } from 'node:readline'

const APP_SERVER = process.env.BOX_CODEX_NATIVE_APP_SERVER ?? '/opt/local-agent/codex/bin/codex-app-server'
const CODEX_HOME = process.env.CODEX_HOME ?? '/workspace/.config/codex'
const CWD = process.env.BOX_SESSION_CWD ?? '/workspace'
const MODEL_STATE = process.env.BOX_HARNESS_MODEL_STATE ?? resolve(CWD, '.config/box/harness-models.json')
const CODEX_VERSION = process.env.BOX_CODEX_VERSION ?? '0.147.0'
const operation = process.argv[2] ?? 'account-status'
const argument = process.argv[3] ?? null

await mkdir(CODEX_HOME, { recursive: true })
const child = spawn(APP_SERVER, ['--listen', 'stdio://', '-c', 'cli_auth_credentials_store="file"'], {
  cwd: CWD,
  env: { ...process.env, CODEX_HOME, HOME: process.env.HOME ?? '/home/agent' },
  stdio: ['pipe', 'pipe', 'pipe'],
})
let nextId = 1
const pending = new Map()
let loginId = null
let finished = false

function emit(event) { process.stdout.write(`${JSON.stringify(event)}\n`) }
function send(value) { child.stdin.write(`${JSON.stringify(value)}\n`) }
function request(method, params) {
  const id = nextId++
  return new Promise((resolvePromise, reject) => {
    pending.set(String(id), { resolve: resolvePromise, reject, method })
    send({ method, id, ...(params === undefined ? {} : { params }) })
  })
}
function accountEvent(result) {
  const account = result?.account
  if (!account) return { type: 'account_state', state: 'signed_out' }
  const email = account?.type === 'chatgpt' ? account.email ?? null : null
  const plan = account?.type === 'chatgpt' ? account.planType ?? null : null
  return {
    type: 'account_state', state: 'signed_in',
    ...(email ? { account: email } : {}),
    ...(plan ? { plan: String(plan) } : {}),
  }
}

const lines = createInterface({ input: child.stdout, crlfDelay: Infinity })
lines.on('line', line => {
  let message
  try { message = JSON.parse(line) } catch { return }
  if (message.method === 'account/login/completed') {
    const params = message.params ?? {}
    if (loginId && params.loginId && params.loginId !== loginId) return
    if (params.success) {
      void request('account/read', { refreshToken: false })
        .then(result => { emit(accountEvent(result)); finish(0) })
        .catch(error => fail('Sign-in completed but the account could not be read.', error))
    } else {
      emit({ type: 'auth_failed', message: params.error ?? 'Codex sign-in failed.' })
      finish(1)
    }
    return
  }
  if (!Object.prototype.hasOwnProperty.call(message, 'id')) return
  const waiter = pending.get(String(message.id))
  if (!waiter) return
  pending.delete(String(message.id))
  if (message.error) waiter.reject(new Error(`${waiter.method}: ${message.error.message}`))
  else waiter.resolve(message.result)
})
child.stderr.on('data', () => {})
child.on('exit', (code, signal) => {
  for (const waiter of pending.values()) waiter.reject(new Error(`Codex App Server exited (${signal ?? code})`))
  pending.clear()
  if (!finished) {
    emit({ type: 'control_failed', message: 'Codex App Server stopped before the request completed.' })
    finished = true
    process.exitCode = 1
  }
})

function finish(code) {
  if (finished) return
  finished = true
  try { commands.close() } catch {}
  try { child.stdin.end() } catch {}
  setTimeout(() => { try { child.kill('SIGTERM') } catch {} }, 250).unref()
  process.exitCode = code
}
function fail(message, _error) {
  // App Server errors are not UI protocol: keep them off stdout so future versions cannot surface
  // credential material through an unexpected diagnostic field.
  emit({ type: 'control_failed', message })
  finish(1)
}

async function allModels() {
  const models = []
  let cursor = null
  do {
    const result = await request('model/list', { ...(cursor ? { cursor } : {}), limit: 100, includeHidden: false })
    for (const item of result?.data ?? []) {
      if (item?.hidden) continue
      const id = String(item.model ?? item.id ?? '').trim()
      if (!id) continue
      models.push({
        id,
        label: String(item.displayName ?? id),
        summary: String(item.description ?? '').replace(/\s+/g, ' ').trim().slice(0, 180),
        isDefault: Boolean(item.isDefault),
      })
    }
    cursor = result?.nextCursor ?? null
  } while (cursor)
  return models
}
async function readSelection() {
  try {
    const value = JSON.parse(await readFile(MODEL_STATE, 'utf8'))
    const selected = value?.selections?.codex
    return selected?.codexVersion === CODEX_VERSION ? selected.model ?? null : null
  } catch { return null }
}
async function writeSelection(model) {
  let value = { version: 1, selections: {} }
  try { value = JSON.parse(await readFile(MODEL_STATE, 'utf8')) } catch {}
  value.version = 1
  value.selections = value.selections && typeof value.selections === 'object' ? value.selections : {}
  if (model) value.selections.codex = { model, codexVersion: CODEX_VERSION }
  else delete value.selections.codex
  await mkdir(dirname(MODEL_STATE), { recursive: true })
  const temp = `${MODEL_STATE}.${process.pid}.tmp`
  await writeFile(temp, `${JSON.stringify(value)}\n`, { mode: 0o600 })
  await rename(temp, MODEL_STATE)
}

const commands = createInterface({ input: process.stdin, crlfDelay: Infinity })
commands.on('line', line => {
  let command
  try { command = JSON.parse(line) } catch { return }
  if (command.type !== 'cancel') return
  if (loginId) {
    void request('account/login/cancel', { loginId })
      .catch(() => null)
      .finally(() => { emit({ type: 'auth_cancelled' }); finish(0) })
  } else {
    emit({ type: 'auth_cancelled' })
    finish(0)
  }
})

try {
  await request('initialize', {
    clientInfo: { name: 'box_android_control', title: 'Box', version: '1' },
    capabilities: {},
  })
  send({ method: 'initialized' })

  switch (operation) {
    case 'overview': {
      emit(accountEvent(await request('account/read', { refreshToken: false })))
      const models = await allModels()
      const selected = await readSelection()
      if (selected && !models.some(model => model.id === selected)) await writeSelection(null)
      emit({ type: 'model_catalog', models, selected: models.some(model => model.id === selected) ? selected : null })
      finish(0)
      break
    }
    case 'account-status':
      emit(accountEvent(await request('account/read', { refreshToken: false })))
      finish(0)
      break
    case 'account-login': {
      const current = await request('account/read', { refreshToken: false })
      if (current?.account) {
        emit(accountEvent(current)); finish(0); break
      }
      const login = await request('account/login/start', { type: 'chatgptDeviceCode' })
      loginId = login?.loginId ?? null
      if (!loginId || !login?.verificationUrl || !login?.userCode) {
        throw new Error('Codex did not return a device-code login URL and code')
      }
      // This process is always launched as an ephemeral guest session. Do not mirror this event
      // into a conversation log or Android Log: the one-time code exists only on this live pipe.
      emit({ type: 'auth_device_code', verificationUrl: login.verificationUrl, userCode: login.userCode })
      break
    }
    case 'account-logout':
      await request('account/logout')
      emit({ type: 'account_state', state: 'signed_out' })
      finish(0)
      break
    case 'models': {
      const models = await allModels()
      const selected = await readSelection()
      if (selected && !models.some(model => model.id === selected)) await writeSelection(null)
      emit({ type: 'model_catalog', models, selected: models.some(model => model.id === selected) ? selected : null })
      finish(0)
      break
    }
    case 'model-set': {
      const models = await allModels()
      const picked = models.find(model => model.id === argument)
      if (!picked) {
        await writeSelection(null)
        emit({ type: 'model_selected', selected: null, fallback: true })
      } else {
        await writeSelection(picked.id)
        emit({ type: 'model_selected', selected: picked.id, fallback: false })
      }
      finish(0)
      break
    }
    case 'model-clear':
      await writeSelection(null)
      emit({ type: 'model_selected', selected: null, fallback: false })
      finish(0)
      break
    default:
      throw new Error(`Unknown Box Codex control operation: ${operation}`)
  }
} catch (error) {
  fail('Codex control request failed.', error)
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (loginId) void request('account/login/cancel', { loginId }).catch(() => {}).finally(() => finish(130))
    else finish(130)
  })
}

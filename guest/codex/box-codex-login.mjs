#!/usr/bin/env node
import { spawn } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { createInterface } from 'node:readline'

const APP_SERVER = process.env.BOX_CODEX_APP_SERVER
  ?? '/opt/local-agent/codex/bin/codex-app-server'
const CODEX_HOME = process.env.CODEX_HOME ?? '/workspace/.config/codex'
const CWD = process.env.BOX_SESSION_CWD ?? '/workspace'

await mkdir(CODEX_HOME, { recursive: true })
const child = spawn(APP_SERVER, [
  '--listen', 'stdio://',
  '-c', 'cli_auth_credentials_store="file"',
], {
  cwd: CWD,
  env: { ...process.env, CODEX_HOME, HOME: process.env.HOME ?? '/home/agent' },
  stdio: ['pipe', 'pipe', 'inherit'],
})

let nextId = 1
const pending = new Map()
let loginId = null

function send(value) {
  child.stdin.write(`${JSON.stringify(value)}\n`)
}

function request(method, params) {
  const id = nextId++
  return new Promise((resolve, reject) => {
    pending.set(String(id), { resolve, reject, method })
    send({ method, id, ...(params === undefined ? {} : { params }) })
  })
}

const lines = createInterface({ input: child.stdout, crlfDelay: Infinity })
lines.on('line', line => {
  let message
  try { message = JSON.parse(line) } catch { return }

  if (message.method === 'account/login/completed') {
    const p = message.params ?? {}
    if (loginId && p.loginId && p.loginId !== loginId) return
    if (p.success) {
      console.log('\nCodex sign-in completed. Credentials are stored inside /workspace.')
      cleanup(0)
    } else {
      console.error(`\nCodex sign-in failed: ${p.error ?? 'unknown error'}`)
      cleanup(1)
    }
    return
  }

  if (Object.prototype.hasOwnProperty.call(message, 'id')) {
    const waiter = pending.get(String(message.id))
    if (!waiter) return
    pending.delete(String(message.id))
    if (message.error) waiter.reject(new Error(`${waiter.method}: ${message.error.message}`))
    else waiter.resolve(message.result)
  }
})

child.on('exit', (code, signal) => {
  for (const waiter of pending.values()) {
    waiter.reject(new Error(`Codex App Server exited (${signal ?? code})`))
  }
  pending.clear()
})

let cleaning = false
function cleanup(code) {
  if (cleaning) return
  cleaning = true
  try { child.stdin.end() } catch {}
  setTimeout(() => { try { child.kill('SIGTERM') } catch {} }, 250).unref()
  process.exitCode = code
  lines.close()
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (loginId) {
      void request('account/login/cancel', { loginId }).catch(() => {}).finally(() => cleanup(130))
    } else {
      cleanup(130)
    }
  })
}

try {
  await request('initialize', {
    clientInfo: { name: 'box_android_login', title: 'Box Codex Login', version: '1' },
    capabilities: {},
  })
  send({ method: 'initialized' })

  const current = await request('account/read', { refreshToken: false })
  if (current?.account) {
    console.log('Codex is already signed in.')
    cleanup(0)
  } else {
    const login = await request('account/login/start', { type: 'chatgptDeviceCode' })
    loginId = login?.loginId
    if (!loginId || !login?.verificationUrl || !login?.userCode) {
      throw new Error('Codex did not return a device-code login URL and code')
    }
    console.log('Open this URL on any signed-in device:')
    console.log(login.verificationUrl)
    console.log('\nEnter this one-time code:')
    console.log(login.userCode)
    console.log('\nWaiting for Codex sign-in to complete. Press Ctrl-C to cancel.')
  }
} catch (error) {
  console.error(`Codex sign-in could not start: ${error instanceof Error ? error.message : error}`)
  cleanup(1)
}

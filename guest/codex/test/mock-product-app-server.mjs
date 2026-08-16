#!/usr/bin/env node
import { createInterface } from 'node:readline'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const home = process.env.CODEX_HOME ?? process.cwd()
const authMarker = resolve(home, 'mock-auth.json')
const logPath = process.env.MOCK_CODEX_LOG
const failLogin = process.env.MOCK_LOGIN_FAIL === '1'
const rejectModel = process.env.MOCK_REJECT_MODEL === '1'

async function loggedIn() {
  try { await readFile(authMarker); return true } catch { return false }
}
async function log(value) {
  if (!logPath) return
  await writeFile(logPath, `${JSON.stringify(value)}\n`, { flag: 'a' })
}
function send(value) { process.stdout.write(`${JSON.stringify(value)}\n`) }

const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => { void (async () => {
  let m
  try { m = JSON.parse(line) } catch { return }
  await log(m)
  if (!Object.prototype.hasOwnProperty.call(m, 'id')) return
  switch (m.method) {
    case 'initialize': send({ id: m.id, result: { serverInfo: { name: 'mock' } } }); return
    case 'account/read':
      send({ id: m.id, result: { requiresOpenaiAuth: true, account: (await loggedIn()) ? { type: 'chatgpt', email: 'dev@example.com', planType: 'pro', accessToken: 'RAW_SECRET_NEVER_FORWARD' } : null } }); return
    case 'account/login/start':
      send({ id: m.id, result: { type: 'chatgptDeviceCode', loginId: 'login-1', verificationUrl: 'https://auth.example/device', userCode: 'ABCD-EFGH' } })
      setTimeout(async () => {
        if (!failLogin) { await mkdir(home, { recursive: true }); await writeFile(authMarker, '{}') }
        send({ method: 'account/login/completed', params: { loginId: 'login-1', success: !failLogin, error: failLogin ? 'denied' : null } })
      }, 120)
      return
    case 'account/login/cancel': send({ id: m.id, result: {} }); return
    case 'account/logout':
      await writeFile(authMarker, '', { flag: 'w' }).catch(() => {})
      await import('node:fs/promises').then(fs => fs.rm(authMarker, { force: true }))
      send({ id: m.id, result: {} }); return
    case 'model/list':
      send({ id: m.id, result: { data: [
        { id: 'default-id', model: 'gpt-box-default', displayName: 'Box Default', description: 'Default test model', isDefault: true, hidden: false },
        { id: 'fast-id', model: 'gpt-box-fast', displayName: 'Box Fast', description: 'Fast test model', isDefault: false, hidden: false },
      ], nextCursor: null } }); return
    case 'thread/start':
    case 'thread/resume':
    case 'turn/start':
      if (rejectModel && m.params?.model) { send({ id: m.id, error: { code: -32602, message: `unsupported model ${m.params.model}` } }); return }
      send({ id: m.id, result: m.method === 'turn/start' ? { turn: { id: 'turn-1' } } : { thread: { id: 'thread-1' } } }); return
    default: send({ id: m.id, result: {} })
  }
})() })

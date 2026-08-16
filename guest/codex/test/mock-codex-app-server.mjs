#!/usr/bin/env node
import { appendFile, mkdir } from 'node:fs/promises'
import { createInterface } from 'node:readline'
import { dirname } from 'node:path'

const LOG = process.env.MOCK_CODEX_LOG
const THREAD_ID = process.env.MOCK_THREAD_ID ?? 'thread-box-test'
const TURN_ID = process.env.MOCK_TURN_ID ?? 'turn-box-test'
const RESUME_FAIL = process.env.MOCK_RESUME_FAIL === '1'

async function log(value) {
  if (!LOG) return
  await mkdir(dirname(LOG), { recursive: true })
  await appendFile(LOG, `${JSON.stringify(value)}\n`)
}
function send(value) { process.stdout.write(`${JSON.stringify(value)}\n`) }

const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => {
  void (async () => {
    const message = JSON.parse(line)
    await log(message)
    const { id, method, params } = message

    // Client response to our approval request.
    if (!method && Object.prototype.hasOwnProperty.call(message, 'id')) return
    if (!Object.prototype.hasOwnProperty.call(message, 'id')) return

    switch (method) {
      case 'initialize':
        send({ id, result: { userAgent: 'mock-codex' } })
        break
      case 'account/read':
        send({ id, result: { account: { type: 'chatgpt', email: 'box@example.test', planType: 'pro' }, requiresOpenaiAuth: true } })
        break
      case 'thread/start':
        send({ id, result: { thread: { id: THREAD_ID } } })
        break
      case 'thread/resume':
        if (RESUME_FAIL) send({ id, error: { code: -32001, message: 'missing thread' } })
        else send({ id, result: { thread: { id: params.threadId } } })
        break
      case 'turn/start':
        send({ id, result: { turn: { id: TURN_ID, status: 'inProgress' } } })
        send({ method: 'turn/started', params: { threadId: THREAD_ID, turn: { id: TURN_ID, status: 'inProgress' } } })
        send({ method: 'item/agentMessage/delta', params: { threadId: THREAD_ID, turnId: TURN_ID, itemId: 'msg-1', delta: 'hello' } })
        send({ method: 'item/completed', params: { threadId: THREAD_ID, turnId: TURN_ID, item: { type: 'agentMessage', id: 'msg-1', text: 'hello world' } } })
        send({ method: 'item/started', params: { threadId: THREAD_ID, turnId: TURN_ID, item: { type: 'commandExecution', id: 'cmd-1', command: 'printf ok', cwd: '/workspace', status: 'inProgress' } } })
        send({ method: 'item/commandExecution/outputDelta', params: { threadId: THREAD_ID, turnId: TURN_ID, itemId: 'cmd-1', delta: 'ok' } })
        send({ method: 'item/commandExecution/requestApproval', id: 900, params: { threadId: THREAD_ID, turnId: TURN_ID, itemId: 'cmd-1', command: 'printf ok', cwd: '/workspace', reason: 'test approval' } })
        break
      case 'turn/interrupt':
        send({ id, result: {} })
        send({ method: 'turn/completed', params: { threadId: THREAD_ID, turn: { id: TURN_ID, status: 'interrupted' } } })
        break
      default:
        send({ id, error: { code: -32601, message: `mock does not implement ${method}` } })
    }
  })().catch(error => {
    process.stderr.write(`${error.stack ?? error}\n`)
    process.exitCode = 1
  })
})

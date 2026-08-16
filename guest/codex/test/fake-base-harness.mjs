#!/usr/bin/env node
import { createInterface } from 'node:readline'
process.stdout.write(`${JSON.stringify({ v: 1, type: 'session_started', at: Date.now(), cwd: process.env.BOX_SESSION_CWD })}\n`)
const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => {
  let m
  try { m = JSON.parse(line) } catch { return }
  if (m.type === 'prompt') process.stdout.write(`${JSON.stringify({ v: 1, type: 'user_message', at: Date.now(), text: m.text ?? '' })}\n`)
})

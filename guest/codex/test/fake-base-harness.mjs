#!/usr/bin/env node
import { createInterface } from 'node:readline'

process.stdout.write(`${JSON.stringify({ type: 'session', sessionId: process.env.BOX_SESSION_ID ?? 'test' })}\n`)
const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => process.stdout.write(`${line}\n`))

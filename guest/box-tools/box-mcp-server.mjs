#!/usr/bin/env node
import net from 'node:net'
import { randomUUID } from 'node:crypto'
import { createInterface } from 'node:readline'

const SOCKET = process.env.BOX_TOOL_SOCKET
if (!SOCKET) {
  process.stderr.write('[box-mcp] BOX_TOOL_SOCKET is not set\n')
  process.exit(2)
}

function send(value) {
  process.stdout.write(`${JSON.stringify(value)}\n`)
}

function bridge(kind, args) {
  return new Promise((resolve, reject) => {
    const id = randomUUID()
    const socket = net.createConnection(SOCKET)
    let buffer = ''
    const timer = setTimeout(() => {
      socket.destroy()
      reject(new Error('Box did not answer the tool request in time'))
    }, 15 * 60 * 1000)
    timer.unref()
    socket.setEncoding('utf8')
    socket.on('connect', () => socket.write(`${JSON.stringify({ id, kind, args })}\n`))
    socket.on('data', chunk => {
      buffer += chunk
      const newline = buffer.indexOf('\n')
      if (newline < 0) return
      const line = buffer.slice(0, newline)
      clearTimeout(timer)
      socket.end()
      try {
        const response = JSON.parse(line)
        if (response.ok) resolve(response.result ?? {})
        else reject(new Error(response.error ?? 'Box rejected the request'))
      } catch (error) {
        reject(error)
      }
    })
    socket.on('error', error => { clearTimeout(timer); reject(error) })
  })
}

const tools = [
  {
    name: 'show',
    description: 'Put a Box artifact in front of the person: a workspace file/image, a local preview, or the Box desktop. Use this instead of telling them to find a path manually.',
    inputSchema: {
      type: 'object',
      properties: {
        kind: { type: 'string', enum: ['document', 'preview', 'computer'] },
        path: { type: 'string', description: 'Absolute path under the Box workspace for a document/image.' },
        name: { type: 'string' },
        mimeType: { type: 'string' },
        url: { type: 'string', description: 'Loopback URL for a preview served inside the guest.' },
        guestPort: { type: 'integer', minimum: 1, maximum: 65535 },
      },
    },
  },
  {
    name: 'connect',
    description: 'Ask the person to connect an external service through Box. For GitHub, Box performs the existing device/install flow and only returns the connection outcome; no token is exposed to you.',
    inputSchema: {
      type: 'object',
      required: ['service'],
      properties: {
        service: { type: 'string', enum: ['github'] },
        reason: { type: 'string', maxLength: 400 },
      },
      additionalProperties: false,
    },
  },
]

async function callTool(params) {
  const name = params?.name
  const args = params?.arguments ?? {}
  if (name === 'show') {
    const result = await bridge('show', args)
    return {
      content: [{ type: 'text', text: result.message ?? 'Shown in Box. The person may or may not open it.' }],
      structuredContent: { shown: true },
      isError: false,
    }
  }
  if (name === 'connect') {
    const result = await bridge('connect', args)
    const text = result.connected
      ? `Box connected GitHub${result.login ? ` as ${result.login}` : ''}${Number.isInteger(result.repositories) ? ` with ${result.repositories} repositories available` : ''}. Normal git/gh commands can now use the guest credential helper.`
      : 'GitHub was not connected. Do not claim that git/gh has authenticated access.'
    return {
      content: [{ type: 'text', text }],
      structuredContent: {
        connected: Boolean(result.connected),
        ...(result.login ? { login: result.login } : {}),
        ...(Number.isInteger(result.repositories) ? { repositories: result.repositories } : {}),
      },
      isError: false,
    }
  }
  throw new Error(`Unknown Box tool: ${name}`)
}

const input = createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => {
  if (!line.trim()) return
  let message
  try { message = JSON.parse(line) } catch { return }
  if (!Object.prototype.hasOwnProperty.call(message, 'id')) return
  void (async () => {
    try {
      switch (message.method) {
        case 'initialize':
          send({
            jsonrpc: '2.0', id: message.id,
            result: {
              protocolVersion: message.params?.protocolVersion ?? '2024-11-05',
              capabilities: { tools: {} },
              serverInfo: { name: 'box', version: '1' },
            },
          })
          break
        case 'ping':
          send({ jsonrpc: '2.0', id: message.id, result: {} })
          break
        case 'tools/list':
          send({ jsonrpc: '2.0', id: message.id, result: { tools } })
          break
        case 'tools/call':
          send({ jsonrpc: '2.0', id: message.id, result: await callTool(message.params) })
          break
        default:
          send({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not found' } })
      }
    } catch (error) {
      send({
        jsonrpc: '2.0', id: message.id,
        result: {
          content: [{ type: 'text', text: `Box tool failed: ${error instanceof Error ? error.message : String(error)}` }],
          isError: true,
        },
      })
    }
  })()
})

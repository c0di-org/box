/**
 * `mcp__box__connect`: the agent asking for an account, and waiting while somebody goes and gets it.
 *
 * The waiting is the whole design, so it is what this pins. An agent that hits a 403 on a clone
 * has three options and two of them are bad — end the turn to explain, or go reading the token out
 * of a file — and the third only works if the tool call genuinely holds until the person is done.
 * A tool that returned "ask them yourself" immediately would pass a shallower test and be useless.
 *
 * The SDK and zod are stubbed for the reason given at the top of test_harness_artifacts.mjs.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * A stub that calls `connect` and then reports whether it was still waiting.
 *
 * `CALLED>` is emitted the moment the handler is entered and `TOLD>` only once it returns, so the
 * order of those two lines against the app's reply is the proof that the call blocked rather than
 * resolving on its own.
 */
const STUB_SDK = `
export function tool(name, description, inputSchema, handler) {
  return { name, description, inputSchema, handler };
}
export function createSdkMcpServer({ name, version, tools = [], instructions, alwaysLoad }) {
  return { type: 'sdk', name, version, instructions, alwaysLoad, tools };
}
export function query({ prompt, options }) {
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: process.cwd(), tools: [] };
    const box = options.mcpServers?.box ?? null;
    let n = 0;
    for await (const turn of prompt) {
      n += 1;
      const match = /CONNECT (\\{.*\\})/.exec(turn.message.content);
      if (!match || !box) {
        yield { type: 'assistant', uuid: 'a' + n, message: { role: 'assistant', content: [{ type: 'text', text: 'TOLD>no tool' }] } };
        continue;
      }
      const input = JSON.parse(match[1]);
      const callId = 'call-' + n;
      yield {
        type: 'assistant',
        uuid: 'a' + n,
        message: { role: 'assistant', content: [{ type: 'tool_use', id: callId, name: 'mcp__box__connect', input }] },
      };
      const pending = box.tools.find((t) => t.name === 'connect').handler(input, {});
      yield { type: 'assistant', uuid: 'c' + n, message: { role: 'assistant', content: [{ type: 'text', text: 'CALLED>' }] } };
      const answer = await pending;
      yield {
        type: 'user',
        uuid: 'u' + n,
        message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: callId, content: answer.content, is_error: answer.isError === true }] },
      };
      yield {
        type: 'assistant',
        uuid: 'r' + n,
        message: { role: 'assistant', content: [{ type: 'text', text: 'TOLD>' + answer.content.map((part) => part.text).join('') }] },
      };
    }
    yield { type: 'result', subtype: 'success', result: 'done', num_turns: n };
  })();
  stream.setPermissionMode = async () => {};
  return stream;
}
`;

const STUB_ZOD = `
const chain = () => {
  const self = {};
  for (const method of ['optional', 'int', 'describe']) self[method] = () => self;
  return self;
};
export const z = { string: chain, number: chain, boolean: chain, enum: chain };
`;

function module_(root, name, source) {
  const dir = join(root, 'node_modules', ...name.split('/'));
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'package.json'), JSON.stringify({
    name, version: '0.0.0-stub', type: 'module', exports: './index.mjs',
  }));
  writeFileSync(join(dir, 'index.mjs'), source);
}

/**
 * Drives one connect request.
 *
 * `answer` is what the app writes back, or null to walk away without answering at all — which is
 * not a hypothetical: it is the phone being put down, and the harness has to survive it.
 */
function connect({ input = { service: 'github' }, answer = null, closeWithoutAnswering = false } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'box-connect-'));
  module_(root, '@anthropic-ai/claude-agent-sdk', STUB_SDK);
  module_(root, 'zod', STUB_ZOD);
  const harness = join(root, 'box-claude-harness.mjs');
  copyFileSync(join(here, '..', 'harness', 'box-claude-harness.mjs'), harness);

  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: { ...process.env, BOX_SESSION_CWD: root, ANTHROPIC_API_KEY: 'stub-key-unused' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  return new Promise((resolve, reject) => {
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      const event = JSON.parse(line);
      events.push(event);
      if (event.type !== 'connect_requested') return;
      if (closeWithoutAnswering) return child.stdin.end();
      child.stdin.write(`${JSON.stringify({ ...answer, type: 'connect_result', requestId: event.requestId })}\n`);
    });
    child.on('error', reject);
    child.on('close', () => resolve(events));
    setTimeout(() => { child.kill(); reject(new Error('the harness did not finish')); }, 25000);

    child.stdin.write(`${JSON.stringify({ type: 'prompt', text: `CONNECT ${JSON.stringify(input)}` })}\n`);
    // Left open: the tool is supposed to hold the turn, and closing stdin here would end the wait
    // for a reason that has nothing to do with the person.
    if (!closeWithoutAnswering) {
      const stop = setInterval(() => {
        if (events.some((event) => event.text?.startsWith?.('TOLD>'))) { clearInterval(stop); child.stdin.end(); }
      }, 20);
    }
  });
}

const told = (events) =>
  events.filter((event) => event.type === 'message' && event.text?.startsWith('TOLD>'))
    .map((event) => event.text.slice(5));
const order = (events) => events
  .filter((event) => event.type === 'connect_requested' || event.text === 'CALLED>' || event.text?.startsWith('TOLD>'))
  .map((event) => (event.type === 'connect_requested' ? 'asked' : event.text === 'CALLED>' ? 'called' : 'told'));

test('the agent asks, and the app is told what for', async () => {
  const events = await connect({
    input: { service: 'github', reason: 'to clone garfbargle/box' },
    answer: { connected: true, login: 'codi', repositories: 3 },
  });

  const asked = events.find((event) => event.type === 'connect_requested');
  assert.equal(asked.service, 'github');
  // The only explanation the person gets before deciding, so it has to reach the app intact.
  assert.equal(asked.reason, 'to clone garfbargle/box');
});

test('the call waits for the person instead of returning an instruction', async () => {
  const events = await connect({ answer: { connected: true, login: 'codi', repositories: 1 } });
  // The app is only answered once it has seen the request, so `told` landing last is the proof
  // that the call held the turn across the round trip. This ordering is the feature: everything
  // else about the flow is decoration if the tool does not wait.
  assert.deepEqual(order(events), ['asked', 'called', 'told']);
});

test('a connected agent is told to get on with it, and not to look for a token', async () => {
  const events = await connect({ answer: { connected: true, login: 'codi', repositories: 3 } });

  const result = told(events)[0];
  assert.match(result, /connected as codi/i);
  assert.match(result, /3 repositories/);
  assert.match(result, /do not look for a token/i);
  assert.match(result, /Carry on/);
});

test('one repository is not "1 repositories"', async () => {
  const events = await connect({ answer: { connected: true, login: 'codi', repositories: 1 } });
  assert.match(told(events)[0], /1 repository for this box/);
});

test('declining is an answer, not an error to retry', async () => {
  const events = await connect({ answer: { connected: false } });

  const result = told(events)[0];
  assert.match(result, /did not connect/i);
  assert.match(result, /Do not ask again in this turn/);
  // is_error would read to the model as something that went wrong, which invites the retry the
  // sentence above is asking it not to make.
  const outcome = events.find((event) => event.type === 'tool_finished');
  assert.equal(outcome, undefined, 'connect draws no tool card, so it reports no outcome either');
});

test('the request draws no tool card, because the connect card is the card', async () => {
  const events = await connect({ answer: { connected: true, login: 'codi' } });
  assert.deepEqual(events.filter((event) => event.type === 'tool_started' || event.type === 'tool_finished'), []);
});

test('Box going away ends the wait rather than stranding the model on it', async () => {
  const events = await connect({ closeWithoutAnswering: true });
  assert.match(told(events)[0], /Box closed before they answered/);
});

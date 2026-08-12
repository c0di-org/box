/**
 * The harness protocol end to end: prompt in, events out, and a permission answered mid-run.
 *
 * This is the milestone in one test. The SDK is stubbed — no credentials, no network, no model —
 * because what is being pinned here is Box's side of the contract: that a running harness can ask,
 * block, be answered from outside, and act on the answer. A sheet that renders but never decides
 * is the failure this exists to catch.
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
 * A stub SDK that asks permission once, then reports what it was told — and under which mode.
 *
 * `setPermissionMode` is on the query object at runtime but not in the published types, so the
 * stub carries it for the same reason the harness feature-detects it: this is the surface Box
 * actually leans on, and a stub without it would pin the wrong contract.
 */
const STUB_SDK = `
export function query({ prompt, options }) {
  let mode = options.permissionMode;
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: options.cwd, tools: [] };

    // Drain the first prompt so streaming-input mode behaves like the real thing.
    const iterator = prompt[Symbol.asyncIterator]();
    const first = await iterator.next();

    const decision = await options.canUseTool(
      'Bash',
      { command: 'npm install', cwd: options.cwd },
      { signal: new AbortController().signal, suggestions: [{ type: 'addRules', rules: [] }] },
    );

    yield {
      type: 'assistant',
      uuid: 'a1',
      message: {
        role: 'assistant',
        content: [
          { type: 'text', text: 'You said ' + decision.behavior + ' to ' + first.value.message.content + ' in ' + mode },
          { type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'npm install' } },
        ],
      },
    };
    yield {
      type: 'user',
      uuid: 'u1',
      message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't1', content: 'added 12 packages' }] },
    };
    yield { type: 'result', subtype: 'success', result: 'done', num_turns: 1 };
  })();
  stream.setPermissionMode = async (next) => { mode = next; };
  return stream;
}
`;

function stubbedHarness() {
  const root = mkdtempSync(join(tmpdir(), 'box-session-'));
  const pkg = join(root, 'node_modules', '@anthropic-ai', 'claude-agent-sdk');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({
    name: '@anthropic-ai/claude-agent-sdk',
    version: '0.0.0-stub',
    type: 'module',
    exports: './index.mjs',
  }));
  writeFileSync(join(pkg, 'index.mjs'), STUB_SDK);
  // Run the real harness from inside this tree so its import resolves to the stub.
  const harness = join(root, 'box-claude-harness.mjs');
  copyFileSync(join(here, '..', 'harness', 'box-claude-harness.mjs'), harness);
  return { root, harness };
}

/**
 * Runs the harness, answering the first permission request with `decision`.
 *
 * [mode] is written before the prompt, which is how the app does it: the permission mode is told
 * to a session on attach, so it is settled before the first turn can start.
 */
function runSession(decision, { mode } = {}) {
  const { root, harness } = stubbedHarness();
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: {
      ...process.env,
      BOX_SESSION_CWD: root,
      // Only a gate value: the stub never calls a model, and nothing here is a real credential.
      ANTHROPIC_API_KEY: 'stub-key-unused',
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  return new Promise((resolve, reject) => {
    if (mode) child.stdin.write(JSON.stringify({ type: 'permission_mode', mode }) + '\n');
    child.stdin.write(JSON.stringify({ type: 'prompt', text: 'clone it' }) + '\n');
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      const event = JSON.parse(line);
      events.push(event);
      if (event.type === 'permission_requested') {
        child.stdin.write(JSON.stringify({
          type: 'decision', requestId: event.requestId, decision,
        }) + '\n');
      }
      if (event.type === 'session_ended') child.stdin.end();
    });
    child.on('error', reject);
    child.on('close', () => resolve(events));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);
  });
}

const kinds = (events) => events.map((event) => event.type);

test('a session asks, waits to be answered, and acts on the answer', async () => {
  const events = await runSession('allow');
  const order = kinds(events);

  assert.deepEqual(order.slice(0, 2), ['session_started', 'activity']);
  // The ask precedes its resolution, and both precede anything the agent said afterwards.
  assert.ok(order.indexOf('permission_requested') < order.indexOf('permission_resolved'));
  assert.ok(order.indexOf('permission_resolved') < order.indexOf('message'));

  const ask = events.find((event) => event.type === 'permission_requested');
  assert.equal(ask.ask.kind, 'run_command');
  assert.equal(ask.ask.command, 'npm install');

  // The harness genuinely received "allow" — this text came back through the SDK callback.
  const said = events.find((event) => event.type === 'message');
  assert.match(said.text, /You said allow/);
  assert.equal(events.at(-1).outcome.status, 'completed');
});

test('denying reaches the harness as a denial rather than a silent allow', async () => {
  const events = await runSession('deny');

  const resolved = events.find((event) => event.type === 'permission_resolved');
  assert.equal(resolved.decision, 'deny');
  const said = events.find((event) => event.type === 'message');
  assert.match(said.text, /You said deny/);
});

test('a tool call and its result arrive as two events sharing one callId', async () => {
  const events = await runSession('allow');

  const started = events.find((event) => event.type === 'tool_started');
  const finished = events.find((event) => event.type === 'tool_finished');
  // The UI folds these into a single card; it can only do that if the ids match.
  assert.equal(started.callId, finished.callId);
  assert.equal(started.tool.kind, 'shell');
  assert.equal(finished.outcome.status, 'success');
  assert.match(finished.outcome.output, /added 12 packages/);
});

test('every event is a complete line of JSON carrying a protocol version', async () => {
  const events = await runSession('allow');
  assert.ok(events.length > 0);
  for (const event of events) {
    assert.equal(event.v, 1);
    assert.equal(typeof event.at, 'number');
    assert.equal(typeof event.type, 'string');
  }
});

test('the mode the app chose is the mode the SDK runs under', async () => {
  const events = await runSession('allow', { mode: 'bypassPermissions' });

  const said = events.find((event) => event.type === 'message');
  assert.match(said.text, /in bypassPermissions/);
  // And it is in the log, so a transcript where nothing was ever asked says why.
  const echo = events.find((event) => event.type === 'permission_mode');
  assert.equal(echo.mode, 'bypassPermissions');
});

test('a mode this harness does not know leaves it asking', async () => {
  const events = await runSession('allow', { mode: 'yolo' });

  assert.equal(events.find((event) => event.type === 'permission_mode'), undefined);
  const said = events.find((event) => event.type === 'message');
  assert.match(said.text, /in default/);
  // The failure mode that matters: an unreadable setting must never widen what is allowed.
  assert.ok(events.some((event) => event.type === 'permission_requested'));
});

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

/** A stub SDK that asks permission once, then reports what it was told. */
const STUB_SDK = `
export function query({ prompt, options }) {
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
          { type: 'text', text: 'You said ' + decision.behavior + ' to ' + first.value.message.content },
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
  // Echoed to stderr so a test can see which name the harness reached for: Box's three modes are
  // its own vocabulary, and the mapping to the SDK's is the part that can silently be wrong.
  stream.setPermissionMode = async (mode) => {
    process.stderr.write('[stub] setPermissionMode ' + mode + '\\n');
  };
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

/** Runs the harness, answering the first permission request with `decision`. */
function runSession(decision, onStarted = null) {
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
  let diagnostics = '';
  child.stderr.on('data', (chunk) => { diagnostics += String(chunk); });
  return new Promise((resolve, reject) => {
    child.stdin.write(JSON.stringify({ type: 'prompt', text: 'clone it' }) + '\n');
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      const event = JSON.parse(line);
      events.push(event);
      if (event.type === 'session_started' && onStarted) {
        child.stdin.write(JSON.stringify(onStarted) + '\n');
      }
      if (event.type === 'permission_requested') {
        child.stdin.write(JSON.stringify({
          type: 'decision', requestId: event.requestId, decision,
        }) + '\n');
      }
      if (event.type === 'session_ended') child.stdin.end();
    });
    child.on('error', reject);
    child.on('close', () => {
      events.diagnostics = diagnostics;
      resolve(events);
    });
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);
  });
}

const kinds = (events) => events.map((event) => event.type);

test('a session asks, waits to be answered, and acts on the answer', async () => {
  const events = await runSession('allow');
  const order = kinds(events);

  // A session opens by saying it started and how much it will ask about — the composer's mode
  // control reads the second of those rather than assuming.
  assert.deepEqual(order.slice(0, 2), ['session_started', 'permission_mode']);
  assert.equal(events[1].mode, 'ask');
  assert.ok(order.indexOf('activity') < order.indexOf('permission_requested'));
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

test('changing what the agent asks about reaches the SDK, and is said back', async () => {
  const events = await runSession('allow', { type: 'set_permission_mode', mode: 'accept_edits' });

  // Box says "accept_edits"; the SDK's own name for it is "acceptEdits".
  assert.match(events.diagnostics, /setPermissionMode acceptEdits/);
  const said = events.filter((event) => event.type === 'permission_mode');
  // Once at the start, once for the change — the composer never has to assume.
  assert.deepEqual(said.map((event) => event.mode), ['ask', 'accept_edits']);
});

test('a mode the harness does not know changes nothing at all', async () => {
  const events = await runSession('allow', { type: 'set_permission_mode', mode: 'telepathy' });

  assert.equal(events.diagnostics.includes('setPermissionMode'), false);
  assert.deepEqual(
    events.filter((event) => event.type === 'permission_mode').map((event) => event.mode),
    ['ask'],
  );
});

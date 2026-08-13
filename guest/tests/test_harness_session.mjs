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
          { type: 'text', text: 'You said ' + decision.behavior + ' to ' + first.value.message.content + ' in ' + mode
            + (options.allowDangerouslySkipPermissions ? ' with bypass allowed' : ' with bypass refused') },
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

/**
 * A stub that answers every prompt, so a session can be watched across more than one turn.
 *
 * The streaming-input shape the real SDK has: one query, one `result` per reply, and the stream
 * only ending when the prompts do.
 */
const TWO_TURN_SDK = `
export function query({ prompt }) {
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: '/workspace', tools: [] };
    for await (const turn of prompt) {
      yield {
        type: 'assistant',
        uuid: 'a-' + turn.message.content,
        message: { role: 'assistant', content: [{ type: 'text', text: 'heard ' + turn.message.content }] },
      };
      yield { type: 'result', subtype: 'success', result: 'heard ' + turn.message.content, num_turns: 1 };
    }
  })();
  stream.setPermissionMode = async () => {};
  return stream;
}
`;

function stubbedHarness(sdk = STUB_SDK) {
  const root = mkdtempSync(join(tmpdir(), 'box-session-'));
  const pkg = join(root, 'node_modules', '@anthropic-ai', 'claude-agent-sdk');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({
    name: '@anthropic-ai/claude-agent-sdk',
    version: '0.0.0-stub',
    type: 'module',
    exports: './index.mjs',
  }));
  writeFileSync(join(pkg, 'index.mjs'), sdk);
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

test('the session is launched allowed to bypass, or "Approve everything" cannot take', async () => {
  const events = await runSession('allow');

  // Not decoration. `bypassPermissions` is refused — up front and through `setPermissionMode` —
  // for a session that was not launched with the allowance, and the refusal is silent from the
  // user's side: the mode appears to change, the banner says it has, and every tool call goes on
  // stopping to ask.
  const said = events.find((event) => event.type === 'message');
  assert.match(said.text, /with bypass allowed/);
});

test('a reply ends the turn without ending the session', async () => {
  const events = await runSession('allow');
  const order = kinds(events);

  // Exactly one ending, at the end. A `result` per turn used to be reported as the session
  // finishing, which drew a "Task finished" rule under every single reply — carrying a second
  // copy of the answer above it — and left the next turn with no working indicator.
  assert.equal(order.filter((type) => type === 'session_ended').length, 1);
  assert.equal(order.at(-1), 'session_ended');

  // And nothing repeats the prose. The SDK's `result` is the final message verbatim, which the
  // conversation has already said, better and in full.
  assert.equal(events.at(-1).outcome.summary, undefined);
  assert.equal(events.at(-1).outcome.status, 'completed');

  // The turn hands back with an idle, which is what takes Stop out of the header — and it comes
  // after the agent has said its piece, not before.
  const idle = events.filter((event) => event.type === 'activity' && event.activity.kind === 'idle');
  assert.equal(idle.length, 1);
  assert.ok(order.lastIndexOf('activity') > order.lastIndexOf('message'));
});

test('a second prompt says the agent is working again', async () => {
  // The gap this covers: the SDK narrates a session once, at init, and says nothing when a later
  // turn begins. Without an activity of Box's own, a conversation's second question ran with no
  // working indicator and no way to stop it.
  const { root, harness } = stubbedHarness(TWO_TURN_SDK);
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: { ...process.env, BOX_SESSION_CWD: root, ANTHROPIC_API_KEY: 'stub-key-unused' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = await new Promise((resolve, reject) => {
    const seen = [];
    let asked = 0;
    child.stdin.write(JSON.stringify({ type: 'prompt', text: 'first' }) + '\n');
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      const event = JSON.parse(line);
      seen.push(event);
      // One turn is over; ask the next thing exactly the way a user would.
      if (event.type === 'activity' && event.activity.kind === 'idle') {
        asked += 1;
        if (asked === 1) child.stdin.write(JSON.stringify({ type: 'prompt', text: 'second' }) + '\n');
        else child.stdin.end();
      }
    });
    child.on('error', reject);
    child.on('close', () => resolve(seen));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);
  });

  const second = events.findIndex((event) => event.type === 'user_message' && event.text === 'second');
  assert.ok(second >= 0, 'the second turn never reached the log');
  const after = events.slice(second);
  assert.ok(after.some((event) => event.type === 'activity' && event.activity.kind === 'thinking'));
  // And the session is still one session: the first reply did not end it.
  assert.equal(kinds(events).filter((type) => type === 'session_ended').length, 1);
});

test('a mode this harness does not know leaves it asking', async () => {
  const events = await runSession('allow', { mode: 'yolo' });

  assert.equal(events.find((event) => event.type === 'permission_mode'), undefined);
  const said = events.find((event) => event.type === 'message');
  assert.match(said.text, /in default/);
  // The failure mode that matters: an unreadable setting must never widen what is allowed.
  assert.ok(events.some((event) => event.type === 'permission_requested'));
});

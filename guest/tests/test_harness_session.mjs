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

/**
 * A stub that blocks on a permission and then takes another turn.
 *
 * The shape the queueing rule is about: a tool call is parked on a person, and a message typed
 * while it is parked has to survive to the next turn rather than being dropped on the floor.
 */
const QUEUEING_SDK = `
export function query({ prompt, options }) {
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: options.cwd, tools: [] };
    const turns = prompt[Symbol.asyncIterator]();
    const first = await turns.next();
    await options.canUseTool(
      'Bash',
      { command: 'npm install', cwd: options.cwd },
      { signal: new AbortController().signal, suggestions: [] },
    );
    yield {
      type: 'assistant',
      uuid: 'a1',
      message: { role: 'assistant', content: [{ type: 'text', text: 'first was ' + first.value.message.content }] },
    };
    yield { type: 'result', subtype: 'success', result: 'ok', num_turns: 1 };
    const second = await turns.next();
    yield {
      type: 'assistant',
      uuid: 'a2',
      message: { role: 'assistant', content: [{ type: 'text', text: 'second was ' + second.value.message.content }] },
    };
    yield { type: 'result', subtype: 'success', result: 'ok', num_turns: 2 };
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

  assert.deepEqual(order.slice(0, 3), ['activity', 'session_started', 'activity']);
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

test('the wait for Claude Code to start is narrated rather than silent', async () => {
  const events = await runSession('allow');

  // Under the stub this is instant. On the real thing the SDK import and the CLI's own start-up
  // sit behind it and take minutes on emulated ARM, with nothing else to show for the wait — so
  // what is pinned here is the order: the label is out before anything that could be slow begins.
  const first = events[0];
  assert.equal(first.type, 'activity');
  // `starting`, not `thinking`: there is no agent behind this wait, and saying `thinking` made
  // Box draw it as the agent's own activity line -- the CLI appearing to answer before it existed.
  assert.equal(first.activity.kind, 'starting');
  assert.match(first.activity.label, /Claude Code/);
  assert.ok(events.indexOf(first) < events.findIndex((event) => event.type === 'session_started'));

  // And a second, so the longer half of the wait is not the same frame as the first half.
  const labels = events
    .filter((event) => event.type === 'activity' && event.activity.label)
    .map((event) => event.activity.label);
  assert.deepEqual(labels.slice(0, 2), ['Getting Claude Code ready', 'Waking the agent']);

  // No trailing ellipsis on the wire. The view appends its own, and a label carrying one rendered
  // as "Starting Claude Code......" on the device.
  for (const label of labels.slice(0, 2)) assert.ok(!label.endsWith('\u2026'), label);
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

test('a message typed while a request is waiting is queued, not lost', async () => {
  /*
   * The composer no longer switches off while an agent is blocked on a permission, so this is the
   * promise behind that: the prompt goes now, waits behind the tool call the person has not
   * answered yet, and is picked up the moment the turn moves. Nothing about it is special-cased —
   * it is the same queue a message typed during a three-minute boot goes through.
   */
  const { root, harness } = stubbedHarness(QUEUEING_SDK);
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: { ...process.env, BOX_SESSION_CWD: root, ANTHROPIC_API_KEY: 'stub-key-unused' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = await new Promise((resolve, reject) => {
    const seen = [];
    child.stdin.write(JSON.stringify({ type: 'prompt', text: 'clone it' }) + '\n');
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      const event = JSON.parse(line);
      seen.push(event);
      if (event.type === 'permission_requested') {
        // Typed while the agent is parked, and sent before anyone answers.
        child.stdin.write(JSON.stringify({ type: 'prompt', text: 'and run the tests' }) + '\n');
        child.stdin.write(JSON.stringify({
          type: 'decision', requestId: event.requestId, decision: 'allow',
        }) + '\n');
      }
      if (event.type === 'session_ended') child.stdin.end();
    });
    child.on('error', reject);
    child.on('close', () => resolve(seen));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);
  });

  // It reached the log the moment it was sent, so the transcript never looked like it swallowed it.
  const echo = events.filter((event) => event.type === 'user_message').map((event) => event.text);
  assert.deepEqual(echo, ['clone it', 'and run the tests']);

  // And it reached the model, on the turn after the one it was typed during.
  const said = events.filter((event) => event.type === 'message').map((event) => event.text);
  assert.equal(said.length, 2);
  assert.match(said[0], /first was clone it/);
  assert.match(said[1], /second was and run the tests/);
});

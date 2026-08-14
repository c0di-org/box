/**
 * The model command: which model answers, and what happens when it changes under a live session.
 *
 * The SDK is stubbed with one that stamps every reply with the model it is currently running as,
 * because that is the only question worth pinning here. The harness's own `model` event says what
 * Box *asked* for; the stamp says what the agent would actually have answered as, and the gap
 * between those two is exactly where this can go wrong — a picker that moves, a log that agrees
 * with it, and a session quietly still on the old model.
 *
 * Three stubs rather than one, because the interesting cases are all failures: a CLI too old to
 * have `setModel`, and one that refuses the switch. Both have to be *reported* — a silent failure
 * leaves Box drawing one model over a session answering as another, and the only visible
 * difference between them is how good the answers are.
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
 * Answers each prompt with the model it is running as, so a turn shows which one took it.
 *
 * `options.model` is read once at query time and `setModel` moves it afterwards — the same two
 * doors the real SDK offers, and the harness has to use both.
 */
const ECHO_MODEL_SDK = `
export function query({ prompt, options }) {
  let current = options?.model ?? 'default';
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: process.cwd(), tools: [] };
    let n = 0;
    for await (const turn of prompt) {
      n += 1;
      yield {
        type: 'assistant',
        uuid: 'a' + n,
        message: { role: 'assistant', content: [{ type: 'text', text: 'SAW>' + current + '|' + turn.message.content }] },
      };
    }
    yield { type: 'result', subtype: 'success', result: 'done', num_turns: n };
  })();
  stream.setPermissionMode = async () => {};
  stream.setModel = async (model) => { current = model ?? 'default'; };
  return stream;
}
`;

/** An older Claude Code: it runs, but the session cannot be moved once it has started. */
const NO_SET_MODEL_SDK = ECHO_MODEL_SDK.replace(
  'stream.setModel = async (model) => { current = model ?? \'default\'; };',
  '',
);

/** One that has the call and says no — a model the account cannot reach, for instance. */
const REFUSING_SDK = ECHO_MODEL_SDK.replace(
  'stream.setModel = async (model) => { current = model ?? \'default\'; };',
  'stream.setModel = async () => { throw new Error("model not available"); };',
);

function stubbedHarness(sdkSource) {
  const root = mkdtempSync(join(tmpdir(), 'box-model-'));
  const pkg = join(root, 'node_modules', '@anthropic-ai', 'claude-agent-sdk');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({
    name: '@anthropic-ai/claude-agent-sdk',
    version: '0.0.0-stub',
    type: 'module',
    exports: './index.mjs',
  }));
  writeFileSync(join(pkg, 'index.mjs'), sdkSource);
  const harness = join(root, 'box-claude-harness.mjs');
  copyFileSync(join(here, '..', 'harness', 'box-claude-harness.mjs'), harness);
  return { root, harness };
}

/**
 * Writes [commands] in order, waiting for each prompt to be answered before sending the next.
 *
 * Same discipline as the viewport test, and load-bearing for the same reason: a model change sent
 * while a turn is in flight is a different question from one sent between turns, and writing every
 * line at once would test neither.
 */
function run(commands, { sdk = ECHO_MODEL_SDK, boxModel } = {}) {
  const { root, harness } = stubbedHarness(sdk);
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: {
      ...process.env,
      BOX_SESSION_CWD: root,
      ANTHROPIC_API_KEY: 'stub-key-unused',
      ...(boxModel === undefined ? {} : { BOX_MODEL: boxModel }),
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  let answered = null;
  return new Promise((resolve, reject) => {
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      const event = JSON.parse(line);
      events.push(event);
      if (event.type === 'message' && event.text.startsWith('SAW>') && answered) {
        const done = answered;
        answered = null;
        done();
      }
    });
    child.on('error', reject);
    child.on('close', () => resolve(events));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);

    (async () => {
      for (const command of commands) {
        const turn = command.type === 'prompt'
          ? new Promise((done) => { answered = done; })
          : null;
        child.stdin.write(JSON.stringify(command) + '\n');
        if (turn) await turn;
      }
      child.stdin.end();
    })().catch(reject);
  });
}

/** ["opus|hello", ...] — which model took each turn, and what it was asked. */
const turns = (events) =>
  events.filter((event) => event.type === 'message' && event.text.startsWith('SAW>'))
    .map((event) => event.text.slice(4));

const models = (events) =>
  events.filter((event) => event.type === 'model').map((event) => event.model);

const errors = (events) => events.filter((event) => event.type === 'error');

test('the model a session opens on comes from its environment, before any line is read', async () => {
  // The path every new conversation actually takes. No `setModel` is involved: the query is built
  // with the model already in hand, which is why this is pinned against a CLI that has no such
  // call at all.
  const events = await run([{ type: 'prompt', text: 'hello' }], {
    sdk: NO_SET_MODEL_SDK,
    boxModel: 'sonnet',
  });

  assert.deepEqual(turns(events), ['sonnet|hello']);
  assert.deepEqual(errors(events), []);
});

test('an unstated model leaves the choice to Claude Code', async () => {
  const events = await run([{ type: 'prompt', text: 'hello' }]);

  assert.deepEqual(turns(events), ['default|hello']);
  assert.deepEqual(models(events), []);
});

test('a model sent as a command still reaches the first turn', async () => {
  // The other door, and the one Box uses on a session that is already up. It lands before the
  // first turn because a prompt is queued behind the settings that arrived with it.
  const events = await run([{ type: 'model', model: 'sonnet' }, { type: 'prompt', text: 'hello' }]);

  assert.deepEqual(turns(events), ['sonnet|hello']);
  // Into the log too, because the transcript is the record of which model said what.
  assert.deepEqual(models(events), ['sonnet']);
});

test('changing it mid-session moves the session, not just the next one', async () => {
  const events = await run([
    { type: 'prompt', text: 'one' },
    { type: 'model', model: 'haiku' },
    { type: 'prompt', text: 'two' },
  ], { boxModel: 'opus' });

  assert.deepEqual(turns(events), ['opus|one', 'haiku|two']);
  assert.deepEqual(models(events), ['haiku']);
  assert.deepEqual(errors(events), []);
});

test('setting the model it is already on says nothing to anyone', async () => {
  // Box broadcasts standing settings to every session on attach, so a session opening on `opus`
  // and immediately being told `opus` is the ordinary case rather than an odd one — and it must
  // cost neither a control round trip nor a second line in the transcript.
  const events = await run([
    { type: 'model', model: 'opus' },
    { type: 'model', model: 'opus' },
    { type: 'prompt', text: 'hello' },
  ], { boxModel: 'opus' });

  assert.deepEqual(models(events), []);
  assert.deepEqual(turns(events), ['opus|hello']);
});

test('a Claude Code too old to switch says so, rather than appearing to have switched', async () => {
  const events = await run([
    { type: 'prompt', text: 'one' },
    { type: 'model', model: 'haiku' },
    { type: 'prompt', text: 'two' },
  ], { sdk: NO_SET_MODEL_SDK, boxModel: 'opus' });

  // The session it was asked about cannot move.
  assert.deepEqual(turns(events), ['opus|one', 'opus|two']);
  // But it is reported rather than silently ignored, and the choice is kept for the next session
  // — which opens on it through the environment, no `setModel` required.
  assert.equal(errors(events).length, 1);
  assert.match(errors(events)[0].message, /could not change which model/i);
  assert.deepEqual(models(events), ['haiku']);
});

test('a refused change is rolled back, so the log never claims a model that was not used', async () => {
  const events = await run([
    { type: 'prompt', text: 'one' },
    { type: 'model', model: 'sonnet' },
    { type: 'prompt', text: 'two' },
  ], { sdk: REFUSING_SDK, boxModel: 'opus' });

  // The stub refuses every switch, so both turns ran on the model the session opened with.
  assert.deepEqual(turns(events), ['opus|one', 'opus|two']);
  assert.equal(errors(events).length, 1);
  // Said twice on purpose: asked for, then taken back. A transcript that stopped at the first
  // would be a record of a change that did not happen.
  assert.deepEqual(models(events), ['sonnet', 'opus']);
});

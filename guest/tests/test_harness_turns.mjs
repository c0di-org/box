/**
 * When a turn is acknowledged, and when it is not.
 *
 * The defect this pins is in the two adjacent lines the harness used to run on a `prompt`:
 *
 *     emit({ type: 'user_message', text });   // stdout, then the log on disk. Permanent.
 *     pushPrompt({ text, attachments });      // an in-memory array
 *
 * Die between them and the durable half survives while the real half does not — the log says the
 * user spoke, the model's context does not, and nothing reconciles them. So the echo cannot be a
 * receipt, and `turn_accepted` exists to be one. Which means the only thing worth testing here is
 * *when* it is emitted: after the model's queue has taken the turn, not when it was read off
 * stdin. A `turn_accepted` beside the echo would be the same lie in a new event.
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
 * A stub that refuses to look at the prompt stream until it is told to.
 *
 * The gap it recreates is the real one: starting Claude Code is minutes of work, all of it before
 * the first prompt can be read, and a turn typed into that window is the turn that went missing.
 * The stub says something over the SDK stream *before* it looks at the prompt stream, which gives
 * the test a line to measure against — a `turn_accepted` emitted on receipt lands before that
 * line, and one emitted on delivery lands after it. Timing alone would not separate the two.
 */
const SLOW_SDK = `
export function query({ prompt, options }) {
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: options.cwd, tools: [] };
    // The startup window, in miniature. The turn is written to stdin immediately and the harness
    // reads and echoes it during this sleep -- while the model is still busy starting and has not
    // asked for anything.
    await new Promise((resolve) => setTimeout(resolve, 300));
    // Said *after* the turn has been read and *before* the prompt stream is touched. That is what
    // makes it a usable marker: an acknowledgement written beside the echo lands before this line,
    // and one written where the generator yields lands after it.
    yield {
      type: 'assistant',
      uuid: 'a0',
      message: { role: 'assistant', content: [{ type: 'text', text: 'not asked yet' }] },
    };
    const turns = prompt[Symbol.asyncIterator]();
    const first = await turns.next();
    yield {
      type: 'assistant',
      uuid: 'a1',
      message: { role: 'assistant', content: [{ type: 'text', text: 'got ' + first.value.message.content }] },
    };
    yield { type: 'result', subtype: 'success', result: 'ok', num_turns: 1 };
  })();
  stream.setPermissionMode = async () => {};
  return stream;
}
`;

function stubbedHarness(sdk) {
  const root = mkdtempSync(join(tmpdir(), 'box-turns-'));
  const pkg = join(root, 'node_modules', '@anthropic-ai', 'claude-agent-sdk');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({
    name: '@anthropic-ai/claude-agent-sdk',
    version: '0.0.0-stub',
    type: 'module',
    exports: './index.mjs',
  }));
  writeFileSync(join(pkg, 'index.mjs'), sdk);
  const harness = join(root, 'box-claude-harness.mjs');
  copyFileSync(join(here, '..', 'harness', 'box-claude-harness.mjs'), harness);
  return { root, harness };
}

/** Runs the harness with one prompt and collects every event it emits. */
function runTurn(prompt) {
  const { root, harness } = stubbedHarness(SLOW_SDK);
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: { ...process.env, BOX_SESSION_CWD: root, ANTHROPIC_API_KEY: 'stub-key-unused' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  return new Promise((resolve, reject) => {
    child.stdin.write(JSON.stringify(prompt) + '\n');
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      events.push(JSON.parse(line));
      if (events[events.length - 1].type === 'session_ended') child.stdin.end();
    });
    child.on('error', reject);
    child.on('close', () => resolve(events));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);
  });
}

const kinds = (events) => events.map((event) => event.type);

test('a turn is acknowledged only once the model has been given it', async () => {
    const events = await runTurn({ type: 'prompt', text: 'clone it', turnId: 't-abc' });
    const order = kinds(events);
    const said = (text) => events.findIndex((event) => event.type === 'message' && event.text.includes(text));

    const echoed = order.indexOf('user_message');
    const accepted = order.indexOf('turn_accepted');

    assert.ok(echoed >= 0, 'the turn was echoed into the log');
    assert.ok(accepted >= 0, 'the turn was acknowledged');
    assert.ok(echoed < accepted, 'the echo comes first and is not itself an acknowledgement');

    // The assertion with teeth, and the reason the stub is shaped the way it is. The turn is read
    // and echoed while the model is still starting; only then does the stub say "not asked yet",
    // and only after that does it ask for a turn. So an acknowledgement written beside the echo --
    // the two adjacent lines this whole change is about -- lands *before* that marker, and only
    // one written where the generator yields lands after it.
    assert.ok(echoed < said('not asked yet'), 'the turn was echoed while the model was still starting');
    assert.ok(said('not asked yet') < accepted, 'the turn was acknowledged before the model asked for it');
    // And it acknowledges delivery, not the answer: it precedes anything said back, or it would be
    // useless for deciding whether to redeliver.
    assert.ok(accepted < said('got '), 'the acknowledgement precedes the reply');

    assert.equal(events.find((event) => event.type === 'turn_accepted').turnId, 't-abc');
    // The echo carries the same name, which is how the app knows which copy on screen it clears.
    assert.equal(events.find((event) => event.type === 'user_message').turnId, 't-abc');
  });

test('a harness says up front that it acknowledges turns', async () => {
  const events = await runTurn({ type: 'prompt', text: 'hi', turnId: 't-1' });
  const started = events.find((event) => event.type === 'session_started');

  // Box gates redelivery on this. Without it a turn given to an older image would sit unanswered
  // forever and be sent again at every start -- one silent duplicate per restart.
  assert.equal(started.acknowledgesTurns, true);
  // And it is said before the acknowledgement it promises, or it could not be relied on to decide
  // anything about the turn that went missing during startup.
  const order = kinds(events);
  assert.ok(order.indexOf('session_started') < order.indexOf('turn_accepted'));
});

test('a turn with no name is not acknowledged', async () => {
  // What every turn looks like from a Box older than this. Nothing is acknowledged, nothing is
  // claimed, and the app is told as much by `acknowledgesTurns` rather than left waiting.
  const events = await runTurn({ type: 'prompt', text: 'hi' });

  assert.equal(events.some((event) => event.type === 'turn_accepted'), false);
  assert.equal(events.find((event) => event.type === 'user_message').turnId, undefined);
});

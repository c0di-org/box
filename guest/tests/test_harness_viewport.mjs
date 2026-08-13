/**
 * The viewport command: what the person is reading on, told to the model with the turn.
 *
 * The SDK is stubbed with one that echoes every prompt it is handed, because the whole question
 * here is what reaches the model and how often — the harness emits nothing for a viewport, so the
 * event log cannot answer it. What is pinned: the note arrives ahead of the first turn, it is not
 * repeated while nothing changes, it comes back when something does, and an unreadable one is
 * ignored rather than guessed at.
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

/** Echoes each prompt back as an assistant message, so every turn is visible in the log. */
const STUB_SDK = `
export function query({ prompt }) {
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: process.cwd(), tools: [] };
    let n = 0;
    for await (const turn of prompt) {
      n += 1;
      yield {
        type: 'assistant',
        uuid: 'a' + n,
        message: { role: 'assistant', content: [{ type: 'text', text: 'SAW>' + turn.message.content }] },
      };
    }
    yield { type: 'result', subtype: 'success', result: 'done', num_turns: n };
  })();
  stream.setPermissionMode = async () => {};
  return stream;
}
`;

function stubbedHarness() {
  const root = mkdtempSync(join(tmpdir(), 'box-viewport-'));
  const pkg = join(root, 'node_modules', '@anthropic-ai', 'claude-agent-sdk');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({
    name: '@anthropic-ai/claude-agent-sdk',
    version: '0.0.0-stub',
    type: 'module',
    exports: './index.mjs',
  }));
  writeFileSync(join(pkg, 'index.mjs'), STUB_SDK);
  const harness = join(root, 'box-claude-harness.mjs');
  copyFileSync(join(here, '..', 'harness', 'box-claude-harness.mjs'), harness);
  return { root, harness };
}

/**
 * Writes [commands] in order, waiting for the model to answer each prompt before sending the next.
 *
 * The waiting is the point rather than tidiness. The note is attached when a prompt is *handed to
 * the model*, not when it is queued -- an answer being composed now should describe the window it
 * will be read on now, not the one the person was holding when they typed. Writing five lines at
 * once would collapse that distinction and pin nothing.
 */
function run(commands) {
  const { root, harness } = stubbedHarness();
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: { ...process.env, BOX_SESSION_CWD: root, ANTHROPIC_API_KEY: 'stub-key-unused' },
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

const seen = (events) =>
  events.filter((event) => event.type === 'message' && event.text.startsWith('SAW>'))
    .map((event) => event.text.slice(4));

const WIDE = { type: 'viewport', layout: 'wide', widthDp: 1280, hardwareKeyboard: true };
const PHONE = { type: 'viewport', layout: 'compact', widthDp: 411, hardwareKeyboard: false };

test('the viewport reaches the model with the turn, and the user still said only what they typed', async () => {
  const events = await run([WIDE, { type: 'prompt', text: 'summarise this' }]);
  const turns = seen(events);

  assert.equal(turns.length, 1);
  assert.match(turns[0], /^\[box\] The person is reading you on a wide window, 1280dp across, with a hardware keyboard\.\n\n/);
  assert.match(turns[0], /summarise this$/);

  // The transcript is not the model's context: what the person typed is all they are shown saying.
  const said = events.find((event) => event.type === 'user_message');
  assert.equal(said.text, 'summarise this');
});

test('it is not repeated while nothing changes, and returns when something does', async () => {
  const events = await run([
    PHONE,
    { type: 'prompt', text: 'one' },
    { type: 'prompt', text: 'two' },
    WIDE,
    { type: 'prompt', text: 'three' },
  ]);
  const turns = seen(events);

  assert.equal(turns.length, 3);
  assert.match(turns[0], /compact window, 411dp across, typing on the phone's on-screen keyboard/);
  // Nothing moved, so the second turn is the user's words and nothing else.
  assert.equal(turns[1], 'two');
  assert.match(turns[2], /wide window, 1280dp across, with a hardware keyboard/);
});

test('a viewport this harness cannot read is ignored rather than guessed at', async () => {
  const events = await run([
    { type: 'viewport', layout: 'dex', widthDp: 1280, hardwareKeyboard: true },
    { type: 'viewport', layout: 'wide', widthDp: 'quite wide' },
    { type: 'prompt', text: 'hello' },
  ]);

  assert.deepEqual(seen(events), ['hello']);
});

test('an unstated viewport says nothing at all', async () => {
  const events = await run([{ type: 'prompt', text: 'hello' }]);

  assert.deepEqual(seen(events), ['hello']);
});

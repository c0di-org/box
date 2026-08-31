/**
 * A session that has started but has not been spoken to says so.
 *
 * Box opens a harness when a conversation is *looked at*, so that the SDK import and the CLI's
 * own start-up are behind us before anyone types. The cost of that pre-warm was invisible: the
 * SDK narrates nothing of its own until `init`, and in streaming-input mode the CLI withholds
 * `init` until it has a first user message — so the last thing a looked-at session ever said was
 * "Waking the agent", and it said it for as long as the box was up. On a phone that is
 * indistinguishable from a wedged agent, which is exactly how it was read. See issue #71.
 *
 * The stub below is the important half of this test: like the real CLI, it yields nothing at all
 * until a prompt reaches it. A stub that announced itself first would pass whatever the harness
 * did.
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

/** Silent until spoken to, which is what the CLI does in streaming-input mode. */
const STUB_SDK = `
export function query({ prompt }) {
  const stream = (async function* () {
    let n = 0;
    for await (const turn of prompt) {
      n += 1;
      // Only now, because only now is there a conversation to have.
      if (n === 1) yield { type: 'system', subtype: 'init', session_id: 's1', cwd: process.cwd(), tools: [] };
      yield {
        type: 'assistant',
        uuid: 'a' + n,
        message: { role: 'assistant', content: [{ type: 'text', text: 'SAW>' + turn.message.content }] },
      };
      yield { type: 'result', subtype: 'success', result: 'done', num_turns: n };
    }
  })();
  stream.setPermissionMode = async () => {};
  return stream;
}
`;

function stubbedHarness() {
  const root = mkdtempSync(join(tmpdir(), 'box-idle-'));
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
 * Starts a harness and gives it [commands] once it has gone quiet, collecting everything it says.
 *
 * "Gone quiet" rather than a fixed wait: the point of the first assertion is that the harness
 * reaches a resting state on its own, so the test has to find that rest rather than assume when
 * it happens.
 */
function runIdle(commands, { quietMs = 400, until = null } = {}) {
  const { root, harness } = stubbedHarness();
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: { ...process.env, BOX_SESSION_CWD: root, ANTHROPIC_API_KEY: 'stub-key-unused' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  return new Promise((resolve, reject) => {
    let quiet = null;
    let spoken = false;
    const speak = () => {
      if (spoken) return;
      spoken = true;
      for (const command of commands) child.stdin.write(JSON.stringify(command) + '\n');
      // Nothing more is coming, and the harness only ends when its input does.
      if (!until) child.stdin.end();
    };
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      const event = JSON.parse(line);
      events.push(event);
      if (until && until(event)) child.stdin.end();
      clearTimeout(quiet);
      quiet = setTimeout(speak, quietMs);
    });
    quiet = setTimeout(speak, quietMs * 4);
    child.on('error', reject);
    child.on('close', () => resolve(events));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);
  });
}

const activities = (events) =>
  events.filter((event) => event.type === 'activity').map((event) => event.activity);

test('a session nobody has typed into settles at idle rather than under a starting label', async () => {
  const events = await runIdle([]);
  const seen = activities(events);

  // The two labels of the start-up itself, in order, and then the end of it.
  assert.deepEqual(seen.slice(0, 2).map((it) => it.label), [
    'Getting Claude Code ready',
    'Waking the agent',
  ]);
  // The last thing it says before anyone speaks to it. This is the whole bug: without it the
  // transcript's final word is "Waking the agent" and the task wears a working indicator forever.
  assert.deepEqual(seen[2], { kind: 'idle' });
  assert.equal(seen.length, 3);
});

test('a prompt that arrived during start-up is never reported as idle', async () => {
  // Written immediately, so it is sitting in the pipe before the harness reads a line — the
  // ordinary case, because the app queues a turn the moment the box is up. Idle here would put a
  // resting session in front of the user with work already in flight.
  const events = await runIdle(
    [{ type: 'prompt', text: 'clone it' }],
    { quietMs: 0, until: (event) => event.type === 'message' },
  );
  const seen = activities(events);

  // Nothing rests before the work starts. `idle` after the turn is the ordinary end of one, and
  // is what puts the composer's full attention back on typing — see the `result` case.
  const working = seen.findIndex((it) => it.kind === 'thinking');
  assert.ok(working >= 0, 'the queued turn was never reported as work');
  assert.ok(seen.slice(0, working).every((it) => it.kind !== 'idle'));

  const said = events.find((event) => event.type === 'message');
  assert.equal(said.text, 'SAW>clone it');
});

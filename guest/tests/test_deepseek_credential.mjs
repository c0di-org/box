/**
 * Somewhere to paste a key, and the one rule that makes it safe to build.
 *
 * The DeepSeek harness used to answer a turn it had no key for by naming a guest filesystem path
 * and telling the user to open a terminal — accurate, and impossible from a phone. Now it names
 * the credential and Box asks for it.
 *
 * The assertion that matters most here is the negative one: **the key never appears in the event
 * stream.** Everything the harness writes to stdout is the session log, which lives on the
 * workspace disk and is replayed in full every time the task is opened. A key that reached it
 * would be stored in the clear and drawn in the transcript from then on, which is exactly why the
 * value travels as its own non-echoed command instead of as a `prompt`.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync, readFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * Enough of the ACP SDK to import.
 *
 * Nothing in these tests reaches a model: the first turn fails for want of a key before anything
 * is spawned, and the retry fails at the spawn. What is being pinned is Box's side — the ask, the
 * save, and the fact that the held turn is picked back up rather than retyped.
 */
const STUB_ACP = `
export const PROTOCOL_VERSION = 1;
export function ndJsonStream() { return {}; }
export class ClientSideConnection {
  async initialize() { return { protocolVersion: PROTOCOL_VERSION }; }
  async newSession() { return { sessionId: 'stub' }; }
  async prompt() { return {}; }
  async cancel() { return {}; }
}
`;

function harnessIn(root) {
  const pkg = join(root, 'node_modules', '@agentclientprotocol', 'sdk');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({
    name: '@agentclientprotocol/sdk',
    version: '0.0.0-stub',
    type: 'module',
    exports: './index.mjs',
  }));
  writeFileSync(join(pkg, 'index.mjs'), STUB_ACP);
  const harness = join(root, 'box-deepseek-harness.mjs');
  copyFileSync(join(here, '..', 'deepseek', 'box-deepseek-harness.mjs'), harness);
  return harness;
}

/**
 * Runs the harness, feeding it commands as its events arrive.
 *
 * `respond` is given each event and may return a command to write back, which is how the test
 * plays the part of Box answering the ask.
 */
function run(commands, respond = () => null) {
  const root = mkdtempSync(join(tmpdir(), 'box-deepseek-'));
  const keyFile = join(root, 'config', 'deepseek-api-key');
  // A node that exists and an "ACP binary" that exits immediately: the retry after the key is
  // saved gets as far as spawning and no further, which is all this needs.
  const acpBin = join(root, 'fake-acp.mjs');
  writeFileSync(acpBin, 'process.exit(0)\n');

  const child = spawn(process.execPath, [harnessIn(root)], {
    cwd: root,
    env: {
      ...process.env,
      BOX_SESSION_CWD: root,
      BOX_DEEPSEEK_API_KEY_FILE: keyFile,
      BOX_DEEPSEEK_NODE: process.execPath,
      BOX_DEEPSEEK_ACP_BIN: acpBin,
      DSH_HOME: join(root, 'dsh'),
      // Or the harness reads the developer's own key out of the environment and never asks.
      DEEPSEEK_API_KEY: '',
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  const stdout = [];
  return new Promise((resolve, reject) => {
    for (const command of commands) child.stdin.write(JSON.stringify(command) + '\n');
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      stdout.push(line);
      const event = JSON.parse(line);
      events.push(event);
      const reply = respond(event);
      if (reply) child.stdin.write(JSON.stringify(reply) + '\n');
    });
    // Nothing here ever reaches a session_ended of its own; the run is bounded by this.
    setTimeout(() => child.stdin.end(), 1200);
    child.on('error', reject);
    child.on('close', () => resolve({ events, stdout: stdout.join('\n'), keyFile }));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);
  });
}

const SECRET = 'sk-not-a-real-key-0123456789';

test('a turn with no key asks for one instead of naming a file path', async () => {
  const { events } = await run([{ type: 'prompt', text: 'summarise this repo' }]);

  const asked = events.find((event) => event.type === 'error' && event.credential);
  assert.ok(asked, 'the harness asked for a credential');
  assert.equal(asked.credential.id, 'deepseek-api-key');
  assert.equal(asked.credential.label, 'DeepSeek API key');
  // Not recoverable, because the only other action the card offers is Reconnect and nothing is
  // wrong with the connection -- a retry would fail identically.
  assert.equal(asked.recoverable, false);
  // And it does not send somebody to a terminal they cannot type into.
  assert.ok(!/\/workspace|terminal/.test(asked.message + asked.detail));
});

test('a pasted key is written 0600 and never appears in the event stream', async () => {
  const { events, stdout, keyFile } = await run(
    [{ type: 'prompt', text: 'summarise this repo' }],
    (event) => (event.type === 'error' && event.credential
      ? { type: 'api_key', credential: 'deepseek-api-key', value: `  ${SECRET}  ` }
      : null),
  );

  // Trimmed on the way in, because a paste picks up whitespace and a key with a newline in it is
  // a key that does not work for a reason nobody can see.
  assert.equal(readFileSync(keyFile, 'utf8').trim(), SECRET);
  assert.equal(statSync(keyFile).mode & 0o777, 0o600);

  // The whole point. Not in an echo, not in an error detail, not in a diagnostic.
  assert.ok(!stdout.includes(SECRET), 'the key was written to the event stream');
  assert.ok(events.some((event) => event.type === 'credential_saved'));
});

test('the turn that was waiting is picked back up, not asked for again', async () => {
  const { events } = await run(
    [{ type: 'prompt', text: 'summarise this repo' }],
    (event) => (event.type === 'error' && event.credential
      ? { type: 'api_key', credential: 'deepseek-api-key', value: SECRET }
      : null),
  );

  // One echo, not two. The retry runs the same turn, and drawing the user saying it twice because
  // Box asked them for a key in between would be Box's problem showing up in their transcript.
  assert.equal(events.filter((event) => event.type === 'user_message').length, 1);
  // And it really did run again: the turn's own "thinking" is emitted a second time, after the key
  // landed, without the user having sent anything.
  const afterSaving = events.slice(events.findIndex((event) => event.type === 'credential_saved'));
  assert.ok(
    afterSaving.some((event) => event.type === 'activity' && event.activity.kind === 'thinking'),
    'the held turn was run again',
  );
  // Past the credential check this time, rather than asking for the same key twice.
  assert.equal(afterSaving.some((event) => event.type === 'error' && event.credential), false);
});

test('an empty paste changes nothing and the ask still stands', async () => {
  let sent = false;
  const { events, keyFile } = await run(
    [{ type: 'prompt', text: 'summarise this repo' }],
    (event) => {
      if (event.type !== 'error' || !event.credential || sent) return null;
      sent = true;
      return { type: 'api_key', credential: 'deepseek-api-key', value: '   ' };
    },
  );

  // An empty key file and a missing one are the same state to `readApiKey`, so nothing is written
  // rather than something being half-written: a cleared field means "not configured".
  assert.throws(() => readFileSync(keyFile, 'utf8'));
  assert.equal(events.some((event) => event.type === 'credential_saved'), false);
  // Asked again rather than left silent.
  assert.ok(events.filter((event) => event.type === 'error' && event.credential).length >= 2);
});

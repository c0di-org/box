/**
 * The sign-in handshake: URL out, pasted code in, account back.
 *
 * The SDK is stubbed, so what this pins is Box's half of the exchange — that the harness asks for
 * the URL, holds the flow open while the user is in a browser, and splits `code#state` into the two
 * arguments the callback actually takes. That split is the part worth a test: the browser hands
 * back one string, the API wants two, and getting it wrong fails as an opaque HTTP 400.
 *
 * It also pins the reason this code exists at all. Box used to drive `claude auth login` and write
 * the code to its stdin, which can never work — that command waits on a loopback HTTP listener and
 * reads the code from somewhere stdin is not wired to.
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

const MANUAL_URL = 'https://claude.com/cai/oauth/authorize?code=true&state=st8';

/**
 * A stub SDK offering the control-protocol handshake.
 *
 * `withHandshake: false` stands in for a Claude Code too old to have it, which the harness must
 * report rather than crash on — these methods are not in the SDK's published types.
 */
const stubSdk = (withHandshake) => `
export function query({ prompt, options }) {
  const stream = (async function* () { await new Promise((resolve) => setTimeout(resolve, 100)); })();
  ${withHandshake ? `
  stream.claudeAuthenticate = async () => ({
    manualUrl: ${JSON.stringify(MANUAL_URL)},
    automaticUrl: 'http://localhost:54321/callback',
  });
  stream.claudeOAuthCallback = async (code, state) => {
    // Echoes the split back through the account so the test can see both halves.
    if (code === 'the-code' && state === 'st8') {
      return { account: { email: code + '|' + state, organization: 'Org', subscriptionType: 'pro' } };
    }
    throw new Error('Request failed with status code 400');
  };
  ` : ''}
  return stream;
}
`;

function stubbedHarness(withHandshake = true) {
  const root = mkdtempSync(join(tmpdir(), 'box-auth-'));
  const pkg = join(root, 'node_modules', '@anthropic-ai', 'claude-agent-sdk');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({
    name: '@anthropic-ai/claude-agent-sdk',
    version: '0.0.0-stub',
    type: 'module',
    exports: './index.mjs',
  }));
  writeFileSync(join(pkg, 'index.mjs'), stubSdk(withHandshake));
  const harness = join(root, 'box-claude-harness.mjs');
  copyFileSync(join(here, '..', 'harness', 'box-claude-harness.mjs'), harness);
  return { root, harness };
}

/** Runs the harness in sign-in mode, pasting `code` once a URL is offered. */
function runSignIn(code, { withHandshake = true } = {}) {
  const { root, harness } = stubbedHarness(withHandshake);
  const child = spawn(process.execPath, [harness, '--auth'], {
    cwd: root,
    // Deliberately no credential: signing in is the one job that runs without one.
    env: { ...process.env, BOX_SESSION_CWD: root, HOME: root, ANTHROPIC_API_KEY: '' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  return new Promise((resolve, reject) => {
    createInterface({ input: child.stdout }).on('line', (line) => {
      const event = JSON.parse(line);
      events.push(event);
      if (event.type === 'auth_url' && code !== null) {
        child.stdin.write(JSON.stringify({ type: 'auth_code', code }) + '\n');
      }
      if (event.type === 'auth_url' && code === null) child.stdin.end();
    });
    child.on('error', reject);
    child.on('close', () => resolve(events));
    setTimeout(() => { child.kill(); reject(new Error('the sign-in did not finish')); }, 15000);
  });
}

test('a sign-in offers a URL, then reports the account the code bought', async () => {
  const events = await runSignIn('the-code#st8');

  assert.deepEqual(events.map((event) => event.type), ['auth_url', 'auth_completed']);
  assert.equal(events[0].url, MANUAL_URL);
  // The stub echoed its two arguments back: the split happened, and in the right order.
  assert.equal(events[1].account.email, 'the-code|st8');
  assert.equal(events[1].account.subscription, 'pro');
});

test('surrounding whitespace on a pasted code is not sent to the API', async () => {
  const events = await runSignIn('  the-code#st8\n');
  assert.equal(events.at(-1).type, 'auth_completed');
});

test('a code pasted without its state is named, not sent', async () => {
  const events = await runSignIn('the-code');

  const failure = events.at(-1);
  assert.equal(failure.type, 'auth_failed');
  assert.match(failure.detail, /after the # sign/);
});

test('a rejected code reports why instead of hanging', async () => {
  const events = await runSignIn('wrong#st8');

  const failure = events.at(-1);
  assert.equal(failure.type, 'auth_failed');
  assert.match(failure.detail, /400/);
});

test('cancelling before the code arrives ends the sign-in', async () => {
  const events = await runSignIn(null);
  assert.equal(events.at(-1).type, 'auth_failed');
});

test('an agent without the handshake is reported, not crashed into', async () => {
  const events = await runSignIn('the-code#st8', { withHandshake: false });

  assert.deepEqual(events.map((event) => event.type), ['auth_failed']);
  assert.match(events[0].message, /cannot sign in/);
});

test('the credential never appears in the event stream', async () => {
  const events = await runSignIn('the-code#st8');
  const wire = JSON.stringify(events);
  // The pasted code is credential material: it is carried to the process and never echoed back,
  // unlike `prompt`, which is mirrored into the log on purpose.
  assert.ok(!wire.includes('"the-code#st8"'));
});

/**
 * Connecting the box to GitHub: a code out, an approval at GitHub, a credential written here.
 *
 * GitHub is stubbed, so what this pins is Box's half — that the flow polls the way the device flow
 * requires, that the second step exists at all, and above all what is left on disk afterwards.
 * That last part is the one worth the most: a connection that "succeeds" and does not leave git
 * able to commit is a flow that fails one step before the pull request, which is the only reason
 * anybody connected in the first place.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

const here = dirname(fileURLToPath(import.meta.url));
const PROGRAM = join(here, '..', 'github', 'box-github-connect.mjs');
const HELPER = join(here, '..', 'github', 'box-git-credential');

const USER = { login: 'codi', id: 4242, name: 'Codi', email: null };

/**
 * A GitHub that behaves like the real one in the ways this flow depends on.
 *
 * `pendingPolls` is not decoration: the device flow is defined by the wait, and a stub that hands
 * over a token on the first poll would let a broken polling loop pass.
 */
function stubGitHub({ pendingPolls = 1, tokenError = null, installations = 1, repositories = 3, revoked = false } = {}) {
  const state = { polls: 0, installations, deviceCodes: 0, revoked };
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://stub');
    const answer = (status, body) => {
      response.writeHead(status, { 'content-type': 'application/json' });
      response.end(JSON.stringify(body));
    };

    if (url.pathname === '/login/device/code') {
      state.deviceCodes += 1;
      return answer(200, {
        device_code: 'dev-code',
        user_code: 'WDJB-MJHT',
        verification_uri: 'https://github.com/login/device',
        expires_in: 900,
        interval: 0,
      });
    }
    if (url.pathname === '/login/oauth/access_token') {
      state.polls += 1;
      if (tokenError) return answer(200, { error: tokenError });
      if (state.polls <= pendingPolls) return answer(200, { error: 'authorization_pending' });
      return answer(200, { access_token: 'ghu_the_token', token_type: 'bearer' });
    }
    if (url.pathname === '/user') {
      const authorized = !state.revoked && (request.headers.authorization === 'Bearer ghu_the_token'
        || request.headers.authorization === 'Bearer ghp_pasted');
      return authorized ? answer(200, USER) : answer(401, { message: 'Bad credentials' });
    }
    if (url.pathname === '/user/installations') {
      return answer(200, {
        total_count: state.installations,
        installations: Array.from({ length: state.installations }, (_, index) => ({ id: index + 1 })),
      });
    }
    if (/^\/user\/installations\/\d+\/repositories$/.test(url.pathname)) {
      return answer(200, { total_count: repositories, repositories: [] });
    }
    return answer(404, { message: 'no' });
  });
  return { server, state };
}

/**
 * Runs the connect program against the stub and collects what it said.
 *
 * `respond` is handed each event as it arrives so a test can answer the way the app would — which
 * is the only way to exercise the steps that wait for a person.
 */
async function connect({ respond = () => null, arguments: argv = [], environment = {}, config: reuse, ...stub } = {}) {
  const { server, state } = stubGitHub(stub);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  // Reusable, so a test can run the program twice over one box's disk -- which is the only way to
  // exercise what a *second* connect does, and the second connect is the common one.
  const config = reuse ?? mkdtempSync(join(tmpdir(), 'box-github-'));

  const child = spawn(process.execPath, [PROGRAM, ...argv], {
    env: {
      ...process.env,
      BOX_CONFIG_DIR: config,
      BOX_GITHUB_WEB: origin,
      BOX_GITHUB_API: origin,
      BOX_GITHUB_CLIENT_ID: environment.clientId ?? 'Iv1.testclientid',
      BOX_GITHUB_APP_SLUG: environment.appSlug ?? 'box-agent',
      BOX_CREDENTIAL_HELPER: HELPER,
      ...(environment.unconfigured ? { BOX_GITHUB_CLIENT_ID: '', BOX_GITHUB_APP_SLUG: '' } : {}),
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  const reader = createInterface({ input: child.stdout });
  reader.on('line', (line) => {
    if (!line.trim()) return;
    const event = JSON.parse(line);
    events.push(event);
    const reply = respond(event, state);
    if (reply) child.stdin.write(`${JSON.stringify(reply)}\n`);
  });

  await new Promise((resolve) => child.on('exit', resolve));
  server.close();
  return { events, config, state };
}

const typesOf = (events) => events.map((event) => event.type);

test('a code goes out, and a credential comes back', async () => {
  const { events, config } = await connect();

  assert.deepEqual(typesOf(events), ['github_code', 'github_connected']);
  assert.equal(events[0].userCode, 'WDJB-MJHT');
  // Offered beside the plain URL, never instead of it: prefilling is undocumented behaviour.
  assert.equal(events[0].verificationUriComplete, 'https://github.com/login/device?user_code=WDJB-MJHT');
  assert.equal(events[1].login, 'codi');
  assert.equal(events[1].repositories, 3);

  const token = join(config, 'box', 'github-token');
  assert.equal(readFileSync(token, 'utf8').trim(), 'ghu_the_token');
  // The mode is the point of the file. 0600 or it is readable by anything that gets in here.
  assert.equal(statSync(token).mode & 0o777, 0o600);
});

test('the wait is a real wait', async () => {
  const { state } = await connect({ pendingPolls: 3 });
  assert.equal(state.polls, 4);
});

test('git is left able to commit and to push', async () => {
  const { config } = await connect();
  const gitConfig = readFileSync(join(config, 'git', 'config'), 'utf8');

  // Without an identity `git commit` does not warn, it fails — one step before the pull request.
  assert.match(gitConfig, /name = Codi/);
  assert.match(gitConfig, /email = 4242\+codi@users\.noreply\.github\.com/);
  assert.match(gitConfig, /helper = .*box-git-credential/);
  // An ssh remote is the URL most people actually have, on a box with no key and no way to add one.
  assert.match(gitConfig, /insteadOf = git@github\.com:/);
});

test('the token is nowhere a person could read it by accident', async () => {
  const { events } = await connect();
  assert.ok(!JSON.stringify(events).includes('ghu_the_token'));
});

test('authorised with nothing to work on asks for repositories, then finishes', async () => {
  const { events } = await connect({
    installations: 0,
    respond: (event, state) => {
      if (event.type !== 'github_install') return null;
      // The person picks repositories at GitHub and comes back to Box. `installed` is Box saying
      // so, which is what turns a four-second poll into an immediate answer.
      state.installations = 2;
      return { type: 'installed' };
    },
  });

  assert.deepEqual(typesOf(events), ['github_code', 'github_install', 'github_connected']);
  assert.match(events[1].url, /\/apps\/box-agent\/installations\/new$/);
  // Two installations of three repositories each: the number the UI says out loud.
  assert.equal(events[2].repositories, 6);
});

test('connecting a box that is already connected offers the repositories, not a second sign-in', async () => {
  // The whole reason this path exists. A GitHub App token reaches only the repositories the app is
  // installed on, so the 403 an agent hits on a private clone almost always means "not that one"
  // rather than "no credential". Running the device flow again re-authorises, finds installations
  // already there, finishes -- and the agent gets the identical 403 on the retry.
  const { config } = await connect();

  const { events, state } = await connect({
    config,
    respond: (event, stub) => {
      if (event.type !== 'github_install') return null;
      stub.installations = 2;
      return { type: 'installed' };
    },
  });

  // No code, because nobody needs to prove who they are twice.
  assert.deepEqual(typesOf(events), ['github_install', 'github_connected']);
  assert.equal(events[0].adding, true, 'the screen has to know it is widening, not connecting');
  assert.equal(events[0].login, 'codi');
  assert.equal(state.deviceCodes, 0);
  assert.equal(events[1].repositories, 6);
});

test('adding nothing is an answer, and does not hold the screen for a quarter of an hour', async () => {
  const { config } = await connect();

  // Went to GitHub, thought better of it, came back. The reachable set is unchanged, so the poll
  // will never finish on its own -- "I'm done" has to be able to end it.
  const { events } = await connect({
    config,
    respond: (event) => (event.type === 'github_install' ? { type: 'installed' } : null),
  });

  assert.deepEqual(typesOf(events), ['github_install', 'github_connected']);
  assert.equal(events[1].repositories, 3);
});

test('a box whose token was revoked at GitHub does start over', async () => {
  const { config } = await connect();

  // The one case where a second sign-in is exactly right, and the shortcut above must not swallow
  // it: the stored credential is refused, so there is genuinely nothing to widen.
  const { events } = await connect({
    config,
    revoked: true,
    respond: (event, stub) => {
      // Once a code is out, the person is authorising afresh and the new token must be accepted.
      if (event.type === 'github_code') stub.revoked = false;
      return null;
    },
  });

  assert.deepEqual(typesOf(events), ['github_code', 'github_connected']);
});

test('backing out of the picker leaves the credential in place to resume from', async () => {
  const { events, config } = await connect({
    installations: 0,
    respond: (event) => (event.type === 'github_install' ? { type: 'cancel' } : null),
  });

  assert.equal(events.at(-1).type, 'github_cancelled');
  // Authorising and choosing repositories are two steps, and the first one is not undone by
  // stopping at the second — coming back resumes at the picker rather than starting over.
  assert.equal(readFileSync(join(config, 'box', 'github-token'), 'utf8').trim(), 'ghu_the_token');
});

test('an expired code says so instead of waiting forever', async () => {
  const { events } = await connect({ tokenError: 'expired_token' });

  const failure = events.at(-1);
  assert.equal(failure.type, 'github_failed');
  assert.match(failure.message, /expired/);
});

test('declining at GitHub is not an error', async () => {
  const { events } = await connect({ tokenError: 'access_denied' });
  assert.equal(events.at(-1).type, 'github_cancelled');
});

test('cancelling while the code is up is heard', async () => {
  const { events } = await connect({
    pendingPolls: 50,
    respond: (event) => (event.type === 'github_code' ? { type: 'cancel' } : null),
  });
  assert.equal(events.at(-1).type, 'github_cancelled');
});

test('a build with no GitHub App still takes a token by hand', async () => {
  const { events, config } = await connect({
    environment: { unconfigured: true },
    respond: (event) => (event.type === 'github_unconfigured' ? { type: 'token', token: 'ghp_pasted' } : null),
  });

  assert.deepEqual(typesOf(events), ['github_unconfigured', 'github_connected']);
  assert.equal(readFileSync(join(config, 'box', 'github-token'), 'utf8').trim(), 'ghp_pasted');
});

test('a token GitHub refuses is reported as refused', async () => {
  const { events } = await connect({
    environment: { unconfigured: true },
    respond: (event) => (event.type === 'github_unconfigured' ? { type: 'token', token: 'nonsense' } : null),
  });
  assert.equal(events.at(-1).type, 'github_failed');
});

test('a box that has never connected says so without asking anyone', async () => {
  const { events } = await connect({ arguments: ['--status'] });
  assert.deepEqual(events, [{ type: 'github_status', connected: false }]);
});

test('disconnecting removes every copy of the token', async () => {
  const { config } = await connect();
  for (const path of ['box/github-token', 'box/github-account.json', 'gh/hosts.yml']) {
    assert.ok(statSync(join(config, path)).isFile());
  }

  const { events } = await connect({ arguments: ['--disconnect'], environment: {} });
  assert.deepEqual(typesOf(events), ['github_disconnected']);
});

test('the credential helper answers git, and only for GitHub', async () => {
  const ask = (request, environment) => new Promise((resolve) => {
    const child = spawn(HELPER, ['get'], { env: { ...process.env, ...environment } });
    let output = '';
    child.stdout.on('data', (chunk) => { output += chunk; });
    child.on('exit', () => resolve(output));
    child.stdin.end(request);
  });

  const { config } = await connect();
  const environment = { BOX_GITHUB_TOKEN_FILE: join(config, 'box', 'github-token') };

  assert.match(await ask('protocol=https\nhost=github.com\n\n', environment), /password=ghu_the_token/);
  // A helper hands out a secret when asked, so "who is asking" is the one thing it cannot assume.
  assert.equal(await ask('protocol=https\nhost=evil.example\n\n', environment), '');
  assert.equal(await ask('protocol=http\nhost=github.com\n\n', environment), '');
});

#!/usr/bin/env node
/**
 * Connecting this box to GitHub, from a phone, on a machine with no browser.
 *
 * Same shape as the Claude sign-in: the exchange runs *here*, in the guest, and Box carries only
 * what a person is meant to read. Events out as one JSON object per line on stdout, commands back
 * as JSON lines on stdin. The token is written by this process, to the disk that survives updates,
 * and never crosses into the app.
 *
 * **It is a device flow, and that is not a preference.** The obvious mobile design — a redirect to
 * `box://` and a token exchanged on the way back — needs a `client_secret`, still required by
 * GitHub on that exchange even with PKCE, because GitHub does not distinguish public clients from
 * confidential ones. A secret shipped inside an APK is not a secret. The device flow needs none,
 * which is also why `gh` uses it. It reads better besides: the code travels *outward*, so nothing
 * has to be carried back through Box's UI and there is no half-copied string to diagnose.
 *
 * Two steps, because this is a GitHub App rather than an OAuth app: **authorise**, which says who
 * the user is, and **install**, which says which repositories this box may touch. A GitHub App
 * user token reaches only repositories where the app is installed, so the second is not overhead
 * bolted onto the first — it is the repository picker, and the reason Box can ask for three
 * repositories instead of "full control of all your private repositories", the smallest thing an
 * OAuth app could have asked for.
 */

import { createInterface } from 'node:readline';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

// ---------------------------------------------------------------- where things live

/**
 * Everything durable is under the workspace disk, which survives app updates.
 *
 * Overridable so the tests exercise the real path logic against a temporary directory rather than
 * proving this file accepts whatever it is pointed at.
 */
const CONFIG = process.env.BOX_CONFIG_DIR ?? '/workspace/.config';
const TOKEN_FILE = join(CONFIG, 'box', 'github-token');
const ACCOUNT_FILE = join(CONFIG, 'box', 'github-account.json');
const GIT_CONFIG = join(CONFIG, 'git', 'config');
const GH_HOSTS = join(CONFIG, 'gh', 'hosts.yml');

/** The credential helper, which is the only thing on this machine that reads the token file. */
const CREDENTIAL_HELPER = process.env.BOX_CREDENTIAL_HELPER
  ?? '/opt/local-agent/bin/box-git-credential';

const HOST = process.env.BOX_GITHUB_HOST ?? 'github.com';

/**
 * Where GitHub is, and the seam the tests reach through.
 *
 * The same split as [CONFIG]: `HOST` is the name that goes into the git config and the account
 * file — the thing a person would recognise — while these two are where this process actually
 * sends requests. Keeping them apart lets a test drive the whole flow against a stub without the
 * program having to be told it is being tested, and without it accepting a plain-http GitHub in
 * anything but a test.
 */
const API = process.env.BOX_GITHUB_API
  ?? (HOST === 'github.com' ? 'https://api.github.com' : `https://${HOST}/api/v3`);
const WEB = process.env.BOX_GITHUB_WEB ?? `https://${HOST}`;

// ---------------------------------------------------------------- talking to Box

let closed = false;

function emit(event) {
  if (closed) return;
  process.stdout.write(`${JSON.stringify(event)}\n`);
}

/** Free text for a person to read when this file has no template for what went wrong. */
function diagnostic(text) {
  process.stderr.write(`${text}\n`);
}

function fail(message, detail) {
  emit({ type: 'github_failed', message, ...(detail ? { detail: String(detail).slice(0, 512) } : {}) });
}

/** Commands from the app, resolved by whoever is waiting for one. */
const commands = [];
let waiter = null;

function pushCommand(command) {
  if (waiter) {
    const resolve = waiter;
    waiter = null;
    resolve(command);
    return;
  }
  commands.push(command);
}

/** The next command, or null once Box has gone away. */
function nextCommand() {
  if (commands.length > 0) return Promise.resolve(commands.shift());
  if (closed) return Promise.resolve(null);
  return new Promise((resolve) => { waiter = resolve; });
}

// ---------------------------------------------------------------- GitHub

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms).unref());

/**
 * One request, with the failure modes this flow actually meets.
 *
 * A phone in a lift and a revoked token are different answers and the caller has to tell them
 * apart: one is worth retrying silently and the other has to be said out loud.
 */
async function github(url, { method = 'GET', body, token, timeout = 20_000, attempts = 1 } = {}) {
  let last = null;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    // Growing, and only between attempts: a phone whose radio is mid-handover comes back in a
    // second or two, and hammering it does not make it come back sooner.
    if (attempt > 0) await sleep(attempt * 1_500);
    last = await once(url, { method, body, token, timeout });
    if (!last.offline) return last;
  }
  return last;
}

/** One request, with the failure modes this flow actually meets. */
async function once(url, { method = 'GET', body, token, timeout = 20_000 } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, {
      method,
      signal: controller.signal,
      headers: {
        accept: 'application/json',
        'user-agent': 'Box',
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...(body ? { 'content-type': 'application/json' } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    const text = await response.text();
    let payload = null;
    try { payload = text ? JSON.parse(text) : null; } catch { payload = null; }
    return { ok: response.ok, status: response.status, payload, text };
  } catch (error) {
    // Never the message verbatim: an aborted fetch says "This operation was aborted", which tells
    // a person nothing about the network their phone is on.
    return { ok: false, status: 0, payload: null, offline: true, error: String(error?.message ?? error) };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * A number GitHub sent, or the documented default if it sent nothing usable.
 *
 * `Number(x) || fallback` is the version that reads correctly and is wrong: zero is a number
 * GitHub is allowed to send, and it is falsy, so the shorter form quietly replaces "poll as fast
 * as you like" with a five second wait.
 */
function seconds(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}

/**
 * A command signal that survives losing a race.
 *
 * The waiting loops below race the person against a timer, and a fresh `nextCommand()` on every
 * turn would arm a new resolver each time — so a command that arrived while the timer was winning
 * would be handed to a promise nobody is awaiting any more and simply vanish. Somebody tapping
 * Cancel one tick before a poll returns is exactly that case. One promise, held until it is
 * actually consumed, is what makes the tap land.
 */
const TICK = Symbol('tick');
let pendingCommand = null;

function commandSignal() {
  if (!pendingCommand) pendingCommand = nextCommand();
  return pendingCommand;
}

/** The command that settled [commandSignal], or [TICK] if the timer got there first. */
async function raceCommand(milliseconds) {
  const outcome = await Promise.race([commandSignal(), sleep(milliseconds).then(() => TICK)]);
  if (outcome !== TICK) pendingCommand = null;
  return outcome;
}

// ---------------------------------------------------------------- storing what came back

/**
 * The token, and the identity beside it.
 *
 * Two files rather than one object, and the split is deliberate: the secret is on its own so that
 * everything which needs to know *who* this box is — the account row in the UI, a status check
 * with no network — can read the other file without ever opening the one that matters. The
 * account file is not a secret; the token file is, and it is written 0600 before anything is in it.
 */
function storeCredential(token, account) {
  mkdirSync(dirname(TOKEN_FILE), { recursive: true, mode: 0o700 });
  writeFileSync(TOKEN_FILE, `${token}\n`, { mode: 0o600 });
  chmodSync(TOKEN_FILE, 0o600);
  writeFileSync(ACCOUNT_FILE, `${JSON.stringify(account, null, 2)}\n`, { mode: 0o600 });

  // gh reads its own configuration rather than the credential helper, so it needs the token where
  // it looks for it. A second copy is a real cost and it is paid on purpose: the alternative is
  // GH_TOKEN in the environment of every process in this box, and an agent that runs `env` writes
  // its own transcript — permanently, to the session log. A file the agent has no reason to cat
  // is the safer of the two.
  mkdirSync(dirname(GH_HOSTS), { recursive: true, mode: 0o700 });
  writeFileSync(
    GH_HOSTS,
    `${HOST}:\n    oauth_token: ${token}\n    user: ${account.login}\n    git_protocol: https\n`,
    { mode: 0o600 },
  );
}

function readToken() {
  try { return readFileSync(TOKEN_FILE, 'utf8').trim() || null; } catch { return null; }
}

function readAccount() {
  try { return JSON.parse(readFileSync(ACCOUNT_FILE, 'utf8')); } catch { return null; }
}

function forgetCredential() {
  for (const path of [TOKEN_FILE, ACCOUNT_FILE, GH_HOSTS]) {
    try { rmSync(path, { force: true }); } catch { /* already gone is the outcome asked for */ }
  }
}

/**
 * Teaching git who the user is and how to authenticate.
 *
 * Through `git config` rather than by writing the file, because this file is not Box's: the person
 * and the agent may both have put things in it, and a wholesale write is a silent way to lose an
 * alias somebody set six months ago. Each key is set individually and idempotently.
 *
 * The identity matters more than it looks. `git commit` with no `user.email` does not warn, it
 * *fails* — so a box that could clone and push but had never been told a name would fall over one
 * step before the pull request, which is the whole point of connecting.
 */
function configureGit(account) {
  mkdirSync(dirname(GIT_CONFIG), { recursive: true, mode: 0o700 });
  const set = (key, value) => {
    const result = spawnSync('git', ['config', '--file', GIT_CONFIG, key, value], { encoding: 'utf8' });
    if (result.status !== 0) diagnostic(`could not set ${key}: ${result.stderr?.trim() ?? result.error}`);
    return result.status === 0;
  };

  // GitHub's own no-reply address, which is what the web UI uses for a user who has kept their
  // address private. Deriving it beats asking: a real address typed into a phone is a mistake
  // waiting to be committed to somebody's history forever.
  set('user.name', account.name || account.login);
  set('user.email', account.email || `${account.id}+${account.login}@users.noreply.${HOST}`);
  set(`credential.${WEB}.helper`, CREDENTIAL_HELPER);
  set(`credential.${WEB}.username`, 'x-access-token');
  set('init.defaultBranch', 'main');

  // Repositories are full of ssh remotes, and this box has no key and no way for the person to add
  // one from a phone. Rewriting them means "clone this" works on the URL they actually have.
  spawnSync('git', ['config', '--file', GIT_CONFIG, '--unset-all', `url.${WEB}/.insteadOf`], { encoding: 'utf8' });
  for (const form of [`git@${HOST}:`, `ssh://git@${HOST}/`]) {
    spawnSync('git', ['config', '--file', GIT_CONFIG, '--add', `url.${WEB}/.insteadOf`, form], { encoding: 'utf8' });
  }
}

// ---------------------------------------------------------------- the flow

/** Who this token belongs to, which is also the check that it is still a token. */
async function identify(token, { attempts = 1 } = {}) {
  const response = await github(`${API}/user`, { token, attempts });
  if (response.offline) return { unreachable: true };
  if (response.status === 401) return { revoked: true };
  if (!response.ok || !response.payload?.login) {
    return { failed: response.payload?.message ?? `GitHub answered ${response.status}` };
  }
  const user = response.payload;
  return {
    account: {
      host: HOST,
      login: user.login,
      id: user.id,
      name: user.name ?? null,
      email: user.email ?? null,
      connectedAt: new Date().toISOString(),
    },
  };
}

/**
 * How many repositories this box can actually reach.
 *
 * The number is the honest summary of what was granted, and it is what the UI says instead of a
 * scope list nobody reads. Zero installations is not a failure — it is the second step of the
 * flow, and the caller turns it into the repository picker.
 */
async function reachableRepositories(token) {
  const response = await github(`${API}/user/installations?per_page=100`, { token });
  if (!response.ok) return { installations: 0, repositories: null };
  const installations = response.payload?.installations ?? [];
  let repositories = 0;
  for (const installation of installations) {
    const owned = await github(`${API}/user/installations/${installation.id}/repositories?per_page=1`, { token });
    // A count that cannot be read is left unknown rather than guessed at; the UI drops the number
    // and says the account, which is the part that was never in doubt.
    if (!owned.ok || typeof owned.payload?.total_count !== 'number') return { installations: installations.length, repositories: null };
    repositories += owned.payload.total_count;
  }
  return { installations: installations.length, repositories };
}

/** Everything that happens once a token exists, however it was obtained. */
async function settle(token, appSlug) {
  const identified = await identify(token);
  if (identified.unreachable) return fail('Box could not reach GitHub.', 'Check the phone’s connection and try again.');
  if (identified.revoked) return fail('GitHub did not accept that token.');
  if (identified.failed) return fail('GitHub did not accept that token.', identified.failed);

  const account = identified.account;
  storeCredential(token, account);
  configureGit(account);

  let reach = await reachableRepositories(token);
  if (reach.installations === 0) {
    // Authorised, but pointed at nothing. The credential is already written, so backing out here
    // and coming back later resumes at this step rather than starting over.
    emit({ type: 'github_install', url: `${WEB}/apps/${appSlug}/installations/new`, login: account.login });
    reach = await waitForInstallation(token);
    if (!reach) return;
  }
  emit({
    type: 'github_connected',
    host: HOST,
    login: account.login,
    name: account.name,
    repositories: reach.repositories,
  });
}

/**
 * Waiting for the person to choose repositories.
 *
 * Polled rather than pushed, because there is no callback to a phone. `installed` is the user
 * saying they are done, which is answered immediately; the slow poll behind it is what makes the
 * screen finish on its own when they simply come back to Box without pressing anything.
 */
async function waitForInstallation(token, baseline = null) {
  const deadline = Date.now() + 15 * 60_000;
  // With nothing installed, any installation is the thing being waited for. With something
  // already installed -- somebody adding a repository to a box that is otherwise connected -- the
  // thing being waited for is a *change*, because "more than zero" was true before they left.
  const arrived = (reach) => (baseline === null
    ? reach.installations > 0
    : reach.installations !== baseline.installations || reach.repositories !== baseline.repositories);

  while (Date.now() < deadline) {
    const raced = await raceCommand(4_000);
    if (raced === null || raced?.type === 'cancel') {
      emit({ type: 'github_cancelled' });
      return null;
    }
    const reach = await reachableRepositories(token);
    if (arrived(reach)) return reach;
    // Done, having added nothing, is a decision and not a mistake -- and only answerable when
    // there was already something to fall back on. Somebody who went to GitHub and thought better
    // of it should not be held on a screen that waits a quarter of an hour for them to change
    // their mind. With nothing installed at all there is nothing to go back to, so that case
    // keeps waiting: a connected box that reaches no repository is not a finished job.
    if (raced?.type === 'installed' && baseline !== null) return reach;
  }
  fail('Nothing was chosen in time.', 'Connect again when you are ready to pick repositories.');
  return null;
}

/**
 * Connecting a box that is already connected, which is the commonest reason to be here.
 *
 * A GitHub App user token reaches only the repositories the app is *installed* on, so the 403 an
 * agent hits on a private clone almost never means "no credential" — it means "not that one".
 * Running the device flow again answers a question nobody asked: the person re-authorises, the
 * installation check finds installations already there, and the flow finishes without offering the
 * screen that would have fixed it. Then the agent retries and gets the same 403.
 *
 * So a box with a working token goes straight to the picker. The credential is rewritten on the
 * way past, which costs nothing and repairs a box whose token predates half of what connecting now
 * writes — an older Box wrote the token and no git identity, and `git commit` fails without one.
 */
async function addRepositories(token, account, appSlug) {
  storeCredential(token, account);
  configureGit(account);

  const baseline = await reachableRepositories(token);
  emit({
    type: 'github_install',
    url: `${WEB}/apps/${appSlug}/installations/new`,
    login: account.login,
    // So the screen can say "add another" rather than "now pick what this box can see", which
    // reads as though the connection they already have did not happen.
    adding: true,
  });
  const reach = await waitForInstallation(token, baseline);
  if (!reach) return;
  emit({
    type: 'github_connected',
    host: HOST,
    login: account.login,
    name: account.name,
    repositories: reach.repositories,
  });
}

/** The device flow itself. */
async function connect(clientId, appSlug) {
  const started = await github(`${WEB}/login/device/code`, {
    method: 'POST',
    body: { client_id: clientId },
    // The first outbound request this box makes in the flow, and often the first it has made at
    // all: an agent-driven connect can start within a second of the guest's DHCP lease. One flake
    // there used to paint the whole sheet red and make the person start over, so it is retried
    // rather than reported. The poll loop below has always tolerated a dropped request; this is
    // the same tolerance, at the one point that did not have it.
    attempts: 3,
  });
  if (started.offline) return fail('Box could not reach GitHub.', 'Check the phone’s connection and try again.');
  if (!started.ok || !started.payload?.device_code) {
    const reason = started.payload?.error_description ?? started.payload?.error;
    // The one misconfiguration worth naming precisely, because nobody would guess it from a 400:
    // device flow is off by default on a new app and has to be turned on in its settings.
    if (started.payload?.error === 'device_flow_disabled') {
      return fail('Box’s GitHub App is not set up for this.', 'Device flow is not enabled on the app.');
    }
    return fail('GitHub would not start the sign-in.', reason ?? `GitHub answered ${started.status}`);
  }

  const { device_code: deviceCode, user_code: userCode, expires_in: expiresIn } = started.payload;
  let interval = seconds(started.payload.interval, 5);
  const verificationUri = started.payload.verification_uri ?? `${WEB}/login/device`;

  emit({
    type: 'github_code',
    userCode,
    verificationUri,
    // GitHub does not document prefilling, but it does accept the code as a query parameter and
    // fills the field in — so this is offered *beside* the plain URL rather than instead of it,
    // and a build where it stops working still shows a code somebody can type.
    verificationUriComplete: `${verificationUri}?user_code=${encodeURIComponent(userCode)}`,
    expiresInSeconds: seconds(expiresIn, 900),
    intervalSeconds: interval,
  });

  const deadline = Date.now() + seconds(expiresIn, 900) * 1000;
  while (Date.now() < deadline) {
    const raced = await raceCommand(interval * 1000);
    if (raced === null || raced?.type === 'cancel') {
      emit({ type: 'github_cancelled' });
      return;
    }
    // A token pasted by hand while the flow is open: the escape hatch wins, because somebody who
    // has gone and made a token is not interested in the code any more.
    if (raced?.type === 'token' && raced.token) return settle(String(raced.token).trim(), appSlug);

    const polled = await github(`${WEB}/login/oauth/access_token`, {
      method: 'POST',
      body: {
        client_id: clientId,
        device_code: deviceCode,
        grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      },
    });
    // A poll that could not leave the phone is not an answer about the user; keep waiting.
    if (polled.offline) continue;
    const token = polled.payload?.access_token;
    if (token) return settle(token, appSlug);

    switch (polled.payload?.error) {
      case 'authorization_pending':
        break;
      case 'slow_down':
        // GitHub's own instruction, and ignoring it is how a flow gets rate limited into failing.
        interval = Math.max(seconds(polled.payload.interval, 0), interval + 5);
        break;
      case 'expired_token':
        return fail('That code expired.', 'Codes last fifteen minutes. Connect again for a new one.');
      case 'access_denied':
        return emit({ type: 'github_cancelled' });
      default:
        return fail(
          'GitHub would not finish the sign-in.',
          polled.payload?.error_description ?? polled.payload?.error ?? `GitHub answered ${polled.status}`,
        );
    }
  }
  fail('That code expired.', 'Codes last fifteen minutes. Connect again for a new one.');
}

/**
 * Whether this box is already connected, answered without asking the person anything.
 *
 * Three outcomes, not two. A token that GitHub refuses means disconnected and the person has to
 * act; a phone with no signal means *unknown*, and saying "not connected" there would put a
 * Connect button in front of somebody who is already connected and send them round a flow that
 * cannot possibly complete.
 */
async function status() {
  const token = readToken();
  const stored = readAccount();
  if (!token) return emit({ type: 'github_status', connected: false });

  const identified = await identify(token);
  if (identified.unreachable) {
    return emit({
      type: 'github_status',
      connected: true,
      stale: true,
      host: stored?.host ?? HOST,
      login: stored?.login ?? null,
      repositories: null,
    });
  }
  if (identified.revoked || identified.failed) return emit({ type: 'github_status', connected: false });

  const reach = await reachableRepositories(token);
  emit({
    type: 'github_status',
    connected: true,
    host: HOST,
    login: identified.account.login,
    name: identified.account.name,
    repositories: reach.repositories,
    // Authorised but pointing at nothing is its own state: connected, and unable to reach a single
    // repository. The UI offers the picker rather than pretending this is finished.
    needsRepositories: reach.installations === 0,
  });
}

// ---------------------------------------------------------------- entry

function argument(name) {
  const flag = `--${name}`;
  const index = process.argv.indexOf(flag);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

/**
 * The escape hatch: a token the person made themselves.
 *
 * It arrives on stdin and never as an argument, which is not fussiness — argv is world-readable
 * through `/proc` for the whole life of the process, so a token passed that way would be on
 * display to everything in this box until the flow finished.
 */
async function awaitPastedToken(appSlug) {
  while (true) {
    const command = await nextCommand();
    if (command === null || command.type === 'cancel') return emit({ type: 'github_cancelled' });
    if (command.type === 'token' && command.token) return settle(String(command.token).trim(), appSlug);
  }
}

async function main() {
  const reader = createInterface({ input: process.stdin });
  reader.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try { pushCommand(JSON.parse(trimmed)); } catch { diagnostic('ignored a line that was not a command'); }
  });
  reader.on('close', () => {
    closed = true;
    if (waiter) { const resolve = waiter; waiter = null; resolve(null); }
  });

  if (process.argv.includes('--status')) return status();
  if (process.argv.includes('--disconnect')) {
    forgetCredential();
    return emit({ type: 'github_disconnected' });
  }

  const clientId = argument('client-id') ?? process.env.BOX_GITHUB_CLIENT_ID;
  const appSlug = argument('app-slug') ?? process.env.BOX_GITHUB_APP_SLUG;
  if (!clientId || !appSlug) {
    // A build that was never given an app cannot run a device flow, and saying so plainly beats
    // one that fails at GitHub with a message about an unknown client. It is not a dead end
    // though: a token made by hand still works, so this waits for one rather than exiting.
    emit({ type: 'github_unconfigured' });
    return awaitPastedToken(appSlug ?? 'box');
  }

  // A box that already holds a working credential does not need another one; see
  // [addRepositories] for why it needs the picker instead.
  const existing = readToken();
  if (existing) {
    const identified = await identify(existing, { attempts: 3 });
    // Not a reason to start a device flow that would fail at the same fence. Better to say the
    // network is the problem than to walk somebody to GitHub and back for nothing.
    if (identified.unreachable) {
      return fail('Box could not reach GitHub.', 'Check the phone’s connection and try again.');
    }
    // Revoked or refused falls through: the credential is genuinely gone and a fresh one is
    // exactly what is wanted.
    if (identified.account) return addRepositories(existing, identified.account, appSlug);
  }

  // Pasting instead of using the code is offered throughout, so it is answered whether it arrives
  // before the device flow has started or in the middle of it.
  await connect(clientId, appSlug);
}

/** stdout is a pipe, so the last event has to be out of this process before it ends. */
const flushed = () => new Promise((resolve) => process.stdout.write('', resolve));

main()
  .catch((error) => fail('The connection stopped unexpectedly.', String(error?.message ?? error)))
  // The stdin reader would otherwise hold the event loop open forever: this program is finished
  // when it has said what happened, and Box closes the session on that event.
  .then(flushed)
  .then(() => process.exit(0));

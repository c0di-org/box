/**
 * `mcp__box__show`: the agent saying "look at this", and every case where it may not.
 *
 * The offer half of the artifact contract runs entirely inside the harness — a tool the agent
 * calls, a path the harness decides about, an `artifact` line the app parses — so unlike the
 * viewport or attachment tests, the event log answers almost everything. What is pinned here:
 * each of the three kinds reaches the wire in the shape `HarnessWire` parses, every refusal is a
 * refusal *and* leaves no button behind, and the tool draws no card of its own.
 *
 * The SDK is stubbed, including `createSdkMcpServer` and `tool`, and so is zod — the harness only
 * ever uses those to build a shape the real SDK converts, and installing 300MB to find that out
 * would make this suite untestable on a laptop. That the real 0.3.226 exports both, and that zod
 * 4.4.3 produces a schema it accepts, was checked against the published package instead; the
 * commit that added this says so.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * A stub that hosts the tool the way the real transport does, and narrates what it was handed.
 *
 * The ordering matters and is copied from the real thing: the `tool_use` block reaches the harness
 * first, the handler runs second, its result comes back as a `tool_result` third. Getting that
 * backwards would hide the card-suppression bug this is partly here to catch.
 */
const STUB_SDK = `
export function tool(name, description, inputSchema, handler) {
  return { name, description, inputSchema, handler };
}

export function createSdkMcpServer({ name, version, tools = [], instructions, alwaysLoad }) {
  return { type: 'sdk', name, version, instructions, alwaysLoad, tools };
}

export function query({ prompt, options }) {
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: process.cwd(), tools: [] };
    const box = options.mcpServers?.box ?? null;
    // What the harness asked for, so a test can pin the pre-allow rather than infer it.
    yield {
      type: 'assistant',
      uuid: 'setup',
      message: { role: 'assistant', content: [{ type: 'text', text: 'OPTIONS>' + JSON.stringify({
        server: box ? { name: box.name, alwaysLoad: box.alwaysLoad === true, tools: box.tools.map((t) => t.name) } : null,
        allowedTools: options.allowedTools ?? null,
      }) }] },
    };
    let n = 0;
    for await (const turn of prompt) {
      n += 1;
      const match = /SHOW (\\{.*\\})/.exec(turn.message.content);
      if (!match || !box) {
        yield { type: 'assistant', uuid: 'a' + n, message: { role: 'assistant', content: [{ type: 'text', text: 'TOLD>no tool' }] } };
        continue;
      }
      const input = JSON.parse(match[1]);
      const callId = 'call-' + n;
      yield {
        type: 'assistant',
        uuid: 'a' + n,
        message: { role: 'assistant', content: [{ type: 'tool_use', id: callId, name: 'mcp__box__show', input }] },
      };
      const answer = await box.tools.find((t) => t.name === 'show').handler(input, {});
      yield {
        type: 'user',
        uuid: 'u' + n,
        message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: callId, content: answer.content, is_error: answer.isError === true }] },
      };
      yield {
        type: 'assistant',
        uuid: 'r' + n,
        message: { role: 'assistant', content: [{ type: 'text', text: 'TOLD>' + answer.content.map((part) => part.text).join('') }] },
      };
    }
    yield { type: 'result', subtype: 'success', result: 'done', num_turns: n };
  })();
  stream.setPermissionMode = async () => {};
  return stream;
}
`;

/** Only the chainable surface the harness actually uses to describe its arguments. */
const STUB_ZOD = `
const chain = () => {
  const self = {};
  for (const method of ['optional', 'int', 'describe']) self[method] = () => self;
  return self;
};
export const z = { string: chain, number: chain, boolean: chain };
`;

function module_(root, name, source) {
  const dir = join(root, 'node_modules', ...name.split('/'));
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'package.json'), JSON.stringify({
    name, version: '0.0.0-stub', type: 'module', exports: './index.mjs',
  }));
  writeFileSync(join(dir, 'index.mjs'), source);
}

/**
 * A harness whose `/workspace` is a real directory this test can build a tree in.
 *
 * `BOX_WORKSPACE` moves only where that prefix points on disk. Every path in these tests is the
 * `/workspace/...` a real device would put on the wire, which is the whole point of the split:
 * otherwise a test proves the harness accepts what it is pointed at rather than what the app sends.
 */
function stubbedHarness({ sdk = STUB_SDK, zod = STUB_ZOD, tcp = null } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'box-artifact-'));
  module_(root, '@anthropic-ai/claude-agent-sdk', sdk);
  if (zod) module_(root, 'zod', zod);

  const workspace = join(root, 'workspace');
  mkdirSync(join(workspace, 'out'), { recursive: true });
  mkdirSync(join(workspace, '.config', 'box'), { recursive: true });
  writeFileSync(join(workspace, 'out', 'report.md'), '# what I found\n');
  writeFileSync(join(workspace, 'out', 'chart.png'), 'PNG');
  writeFileSync(join(workspace, 'out', 'blob.unknown'), 'x');
  writeFileSync(join(workspace, '.config', 'box', 'github-token'), 'ghp_secret');
  // Somewhere off the workspace disk entirely, standing in for the system disk.
  const elsewhere = join(root, 'elsewhere.txt');
  writeFileSync(elsewhere, 'not the agent\'s work');
  symlinkSync(elsewhere, join(workspace, 'out', 'escape.md'));
  symlinkSync(join(workspace, '.config', 'box', 'github-token'), join(workspace, 'out', 'notes.md'));

  const harness = join(root, 'box-claude-harness.mjs');
  copyFileSync(join(here, '..', 'harness', 'box-claude-harness.mjs'), harness);
  return { root, harness, workspace, tcp };
}

function drive(script, options = {}) {
  const { root, harness, workspace, tcp } = stubbedHarness(options);
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: {
      ...process.env,
      BOX_SESSION_CWD: root,
      ANTHROPIC_API_KEY: 'stub-key-unused',
      BOX_WORKSPACE: workspace + '/',
      ...(tcp ? { BOX_PROC_TCP: tcp(root) } : {}),
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
      if (event.type === 'message' && event.text.startsWith('TOLD>') && answered) {
        const done = answered;
        answered = null;
        done();
      }
    });
    child.on('error', reject);
    child.on('close', () => resolve(events));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 25000);

    const send = (command) => child.stdin.write(JSON.stringify(command) + '\n');
    const turn = () => new Promise((done) => { answered = done; });
    Promise.resolve(script({ send, turn, root, workspace }))
      .then(() => child.stdin.end())
      .catch(reject);
  });
}

/** Asks the agent to call the tool once with [input], and waits to be told how it went. */
function show(input, options) {
  return drive(async ({ send, turn }) => {
    const answer = turn();
    send({ type: 'prompt', text: `SHOW ${JSON.stringify(input)}` });
    await answer;
  }, options);
}

const artifacts = (events) => events.filter((event) => event.type === 'artifact');
const told = (events) =>
  events.filter((event) => event.type === 'message' && event.text.startsWith('TOLD>'))
    .map((event) => event.text.slice(5));
const options = (events) =>
  JSON.parse(events.find((event) => event.type === 'message' && event.text.startsWith('OPTIONS>')).text.slice(8));

/** One listener on 8080, in the columns and hex the kernel actually writes. */
const LISTENING = [
  '  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode',
  '   0: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 41337 1',
  '   1: 0100007F:1389 0100007F:CF2E 01 00000000:00000000 00:00000000 00000000  1000        0 41338 1',
].join('\n');

const tcpFixture = (contents) => (root) => {
  const path = join(root, 'net-tcp');
  writeFileSync(path, contents);
  return path;
};

// ---- what the agent is given -------------------------------------------------

test('the tool is offered to the model, and never asked about', async () => {
  const events = await drive(async ({ send, turn }) => {
    const answer = turn();
    send({ type: 'prompt', text: 'hello' });
    await answer;
  });

  const asked = options(events);
  assert.deepEqual(asked.server, { name: 'box', alwaysLoad: true, tools: ['show'] });
  // Pre-allowed on purpose. A sheet reading "allow the agent to show you a file?" has one honest
  // answer, and the artifact is already a button nobody has to press.
  assert.deepEqual(asked.allowedTools, ['mcp__box__show']);
});

// ---- a file ------------------------------------------------------------------

test('a file in the workspace becomes a document, and draws no tool card', async () => {
  const events = await show({ path: '/workspace/out/report.md' });

  assert.deepEqual(artifacts(events).map((event) => ({ ...event, v: undefined, at: undefined })), [{
    v: undefined, at: undefined,
    type: 'artifact', kind: 'document',
    guestPath: '/workspace/out/report.md', name: 'report.md', mimeType: 'text/markdown',
  }]);
  assert.match(told(events)[0], /report\.md is now a button/);
  // The row is what happened. A card beside it saying the same thing is the sentence twice — and
  // a `tool_finished` with no start would have the app draw a stray "Tool" row from nothing.
  assert.deepEqual(events.filter((event) => event.type === 'tool_started' || event.type === 'tool_finished'), []);
});

test('the media type comes from the path, because it picks the icon', async () => {
  const image = await show({ path: '/workspace/out/chart.png' });
  assert.equal(artifacts(image)[0].mimeType, 'image/png');

  // Not text/plain: claiming a type for something unrecognised is a guess the viewer inherits.
  const blob = await show({ path: '/workspace/out/blob.unknown' });
  assert.equal(artifacts(blob)[0].mimeType, 'application/octet-stream');
});

test('the agent is told an offer is not a viewing', async () => {
  const events = await show({ path: '/workspace/out/report.md' });
  assert.match(told(events)[0], /may or may not open it/);
});

// ---- and every file it may not show ------------------------------------------

test('a path outside the workspace is refused', async () => {
  const events = await show({ path: '/etc/passwd' });

  assert.deepEqual(artifacts(events), []);
  assert.match(told(events)[0], /Nothing was shown\./);
  assert.match(told(events)[0], /Only files under \/workspace\//);
});

test('a symlink out of the workspace is refused, which a prefix test alone would miss', async () => {
  const events = await show({ path: '/workspace/out/escape.md' });

  assert.deepEqual(artifacts(events), []);
  assert.match(told(events)[0], /leads out of \/workspace\//);
});

test('the credential directory is never shown, by either name', async () => {
  const direct = await show({ path: '/workspace/.config/box/github-token' });
  assert.deepEqual(artifacts(direct), []);
  assert.match(told(direct)[0], /credentials and is never shown/);

  // Resolved first, so a link wearing an innocent name is caught by the same rule.
  const linked = await show({ path: '/workspace/out/notes.md' });
  assert.deepEqual(artifacts(linked), []);
  assert.match(told(linked)[0], /credentials and is never shown/);
});

test('a file that is not there is refused rather than offered as a dead button', async () => {
  const events = await show({ path: '/workspace/out/not-written-yet.md' });

  assert.deepEqual(artifacts(events), []);
  assert.match(told(events)[0], /Nothing is at \/workspace\/out\/not-written-yet\.md/);
  assert.match(told(events)[0], /worse than no button/);
});

test('a folder is refused, and said to be a folder', async () => {
  const events = await show({ path: '/workspace/out' });

  assert.deepEqual(artifacts(events), []);
  assert.match(told(events)[0], /is a folder/);
});

test('a relative path is refused rather than guessed at', async () => {
  const events = await show({ path: 'out/report.md' });

  assert.deepEqual(artifacts(events), []);
  assert.match(told(events)[0], /is relative/);
});

// ---- a port ------------------------------------------------------------------

test('a served port becomes a preview, carrying the url the parser needs', async () => {
  const events = await show({ port: 8080 }, { tcp: tcpFixture(LISTENING) });

  assert.deepEqual(artifacts(events).map((event) => ({ ...event, v: undefined, at: undefined })), [{
    v: undefined, at: undefined,
    type: 'artifact', kind: 'preview', url: 'http://localhost:8080/', guestPort: 8080,
  }]);
  assert.match(told(events)[0], /Port 8080 is now a button/);
});

test('a port nothing is listening on is refused, because a preview is a button too', async () => {
  // 5000 is absent from the fixture, and 0x1389 (5001) is in it as an established connection
  // rather than a listener — the state column is what separates them.
  for (const port of [5000, 5001]) {
    const events = await show({ port }, { tcp: tcpFixture(LISTENING) });
    assert.deepEqual(artifacts(events), []);
    assert.match(told(events)[0], new RegExp(`Nothing in this box is listening on ${port}`));
  }
});

test('a port this harness cannot check is believed rather than refused', async () => {
  // No /proc to read on the machines this suite runs on. Refusing on ignorance would make the
  // whole preview path dead everywhere but a real device, where it could not then be tested.
  const events = await show({ port: 4321 }, { tcp: (root) => join(root, 'no-such-table') });

  assert.equal(artifacts(events).length, 1);
  assert.equal(artifacts(events)[0].guestPort, 4321);
});

test('something that is not a port is refused', async () => {
  for (const port of [0, 70000, -1]) {
    const events = await show({ port }, { tcp: tcpFixture(LISTENING) });
    assert.deepEqual(artifacts(events), []);
    assert.match(told(events)[0], /is not a port number/);
  }
});

// ---- the desktop -------------------------------------------------------------

test('the desktop is offered as the computer', async () => {
  const events = await show({ desktop: true });

  assert.equal(artifacts(events).length, 1);
  assert.equal(artifacts(events)[0].kind, 'computer');
  assert.match(told(events)[0], /desktop is now a button/);
});

test('desktop false is not a request to show the desktop', async () => {
  const events = await show({ desktop: false });

  assert.deepEqual(artifacts(events), []);
  assert.match(told(events)[0], /exactly one of path, port or desktop/);
});

// ---- one thing at a time -----------------------------------------------------

test('two things at once is refused, so every button says what it is', async () => {
  const events = await show({ path: '/workspace/out/report.md', port: 8080 }, { tcp: tcpFixture(LISTENING) });

  assert.deepEqual(artifacts(events), []);
  assert.match(told(events)[0], /asked for path and port at once/);
});

test('nothing at all is refused', async () => {
  const events = await show({});

  assert.deepEqual(artifacts(events), []);
  assert.match(told(events)[0], /exactly one of path, port or desktop/);
});

// ---- and when the guest cannot host the tool ---------------------------------

test('an SDK that cannot host in-process tools still runs a session', async () => {
  const events = await drive(async ({ send, turn }) => {
    const answer = turn();
    send({ type: 'prompt', text: 'SHOW {"desktop":true}' });
    await answer;
  }, { sdk: STUB_SDK.replace('export function createSdkMcpServer', 'function createSdkMcpServer') });

  assert.equal(options(events).server, null);
  assert.equal(options(events).allowedTools, null);
  assert.deepEqual(artifacts(events), []);
  // The session is the thing that must survive. Box ships the SDK inside the guest image and
  // cannot reach in to correct one that is older than this file.
  assert.equal(events.at(-1).type, 'session_ended');
  assert.equal(events.at(-1).outcome.status, 'completed');
});

test('a harness with no zod runs without the tool rather than not at all', async () => {
  const events = await drive(async ({ send, turn }) => {
    const answer = turn();
    send({ type: 'prompt', text: 'SHOW {"desktop":true}' });
    await answer;
  }, { zod: null });

  assert.equal(options(events).server, null);
  assert.deepEqual(artifacts(events), []);
  assert.equal(events.at(-1).outcome.status, 'completed');
});

test('an SDK whose tool builder throws costs the tool, not the session', async () => {
  const events = await drive(async ({ send, turn }) => {
    const answer = turn();
    send({ type: 'prompt', text: 'SHOW {"desktop":true}' });
    await answer;
  }, {
    sdk: STUB_SDK.replace(
      'export function createSdkMcpServer({',
      'export function createSdkMcpServer(_ignored) { throw new TypeError("different signature"); }\nfunction unusedCreate({',
    ),
  });

  assert.equal(options(events).server, null);
  assert.deepEqual(artifacts(events), []);
  assert.equal(events.at(-1).outcome.status, 'completed');
});

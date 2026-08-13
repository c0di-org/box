/**
 * Files the user showed the agent: what reaches the model, and what happens when one is late.
 *
 * The waiting is the part worth pinning. An attachment is written on the phone and copied into the
 * box a second or so later, so the prompt naming it can easily arrive first — and an agent that
 * looked at that moment would tell the user it cannot see the picture they are holding. The
 * harness holds the turn instead, and these tests are the only place that is checked.
 *
 * The SDK is stubbed with one that echoes every turn, because none of this is visible in the event
 * log: what the model was handed is the whole question.
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

/** A harness whose inbox is a real directory this test can write into, and which gives up fast. */
function stubbedHarness({ waitMs = 4000 } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'box-attach-'));
  const pkg = join(root, 'node_modules', '@anthropic-ai', 'claude-agent-sdk');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({
    name: '@anthropic-ai/claude-agent-sdk',
    version: '0.0.0-stub',
    type: 'module',
    exports: './index.mjs',
  }));
  writeFileSync(join(pkg, 'index.mjs'), STUB_SDK);
  const inbox = join(root, 'inbox');
  mkdirSync(inbox, { recursive: true });
  const harness = join(root, 'box-claude-harness.mjs');
  copyFileSync(join(here, '..', 'harness', 'box-claude-harness.mjs'), harness);
  return { root, harness, inbox, waitMs };
}

/**
 * Starts the harness and hands control to [script], which drives stdin and can wait for turns.
 *
 * The inbox path is the guest's, always — `/workspace/shared/inbox/` is what the app puts on the
 * wire and what the harness refuses to look outside of. `BOX_INBOX` only moves where that prefix
 * points on disk, so the paths in these tests are the paths a real device would send.
 */
function drive(script, options) {
  const { root, harness, inbox, waitMs } = stubbedHarness(options);
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: {
      ...process.env,
      BOX_SESSION_CWD: root,
      ANTHROPIC_API_KEY: 'stub-key-unused',
      BOX_INBOX: inbox + '/',
      BOX_ATTACHMENT_WAIT_MS: String(waitMs),
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
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 25000);

    const send = (command) => child.stdin.write(JSON.stringify(command) + '\n');
    const turn = () => new Promise((done) => { answered = done; });
    Promise.resolve(script({ send, turn, inbox }))
      .then(() => child.stdin.end())
      .catch(reject);
  });
}

const seen = (events) =>
  events.filter((event) => event.type === 'message' && event.text.startsWith('SAW>'))
    .map((event) => event.text.slice(4));

const shot = {
  guestPath: '/workspace/shared/inbox/20260812-214755-shot.png',
  name: 'shot.png',
  mimeType: 'image/png',
  bytes: 4,
};

test('the turn waits for a file that is still being copied in', async () => {
  const events = await drive(async ({ send, turn, inbox }) => {
    const answer = turn();
    send({ type: 'prompt', text: 'what is this?', attachments: [shot] });
    // Nothing is on this side yet, which is the normal case: the copy is driven by a watch on the
    // phone and is about a second behind the keystroke.
    await new Promise((resolve) => setTimeout(resolve, 600));
    writeFileSync(join(inbox, '20260812-214755-shot.png'), 'PNG!');
    await answer;
  });

  const turns = seen(events);
  assert.equal(turns.length, 1);
  assert.match(turns[0], /\[box\] They attached a file, which is now at:/);
  assert.match(turns[0], /\/workspace\/shared\/inbox\/20260812-214755-shot\.png {2}\(shot\.png, image\/png\)/);
  assert.match(turns[0], /what is this\?$/);
});

test('a file that never arrives is said to be missing rather than described', async () => {
  const events = await drive(async ({ send, turn }) => {
    const answer = turn();
    send({ type: 'prompt', text: 'what is this?', attachments: [shot] });
    await answer;
  }, { waitMs: 500 });

  const turns = seen(events);
  assert.equal(turns.length, 1);
  assert.match(turns[0], /have not reached this box; say so rather than guessing/);
  assert.match(turns[0], /shot\.png/);
  // And the agent is never told a path it would find nothing at.
  assert.ok(!turns[0].includes('which is now at'));
});

test('the transcript records the attachment, so a replayed conversation still shows it', async () => {
  const events = await drive(async ({ send, turn, inbox }) => {
    const answer = turn();
    writeFileSync(join(inbox, '20260812-214755-shot.png'), 'PNG!');
    send({ type: 'prompt', text: 'look', attachments: [shot] });
    await answer;
  });

  // The log is the only source a restored transcript has: the app draws the thumbnail from this.
  const said = events.find((event) => event.type === 'user_message');
  assert.equal(said.text, 'look');
  assert.deepEqual(said.attachments, [shot]);
});

test('a path outside the inbox is refused, and the turn still happens', async () => {
  const events = await drive(async ({ send, turn }) => {
    const answer = turn();
    send({
      type: 'prompt',
      text: 'read this',
      attachments: [
        { guestPath: '/workspace/.config/box/credentials.json', name: 'creds', mimeType: 'application/json', bytes: 1 },
        { guestPath: '/workspace/shared/inbox/../../.config/box/credentials.json', name: 'creds', mimeType: 'application/json', bytes: 1 },
      ],
    });
    await answer;
  }, { waitMs: 500 });

  const turns = seen(events);
  assert.deepEqual(turns, ['read this']);
  // Nothing was attached, so nothing is claimed about anything — not even that it went missing.
  const said = events.find((event) => event.type === 'user_message');
  assert.equal(said.attachments, undefined);
});

test('a turn with nothing attached is exactly what the person typed', async () => {
  const events = await drive(async ({ send, turn }) => {
    const answer = turn();
    send({ type: 'prompt', text: 'just words' });
    await answer;
  });

  assert.deepEqual(seen(events), ['just words']);
});

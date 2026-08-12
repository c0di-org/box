/**
 * Stopping one sub-agent, end to end.
 *
 * The same shape as `test_harness_session.mjs`: a real harness process, a stubbed SDK, and a
 * decision arriving from outside mid-run. What is pinned here is the half that cannot be tested by
 * translating a message — that a stop typed on a phone reaches the SDK's per-task cancel, that the
 * card it belongs to is closed as stopped, and that the delegate's own aborted report does not then
 * reopen it as a success. That last one is the bug this test exists for: the sub-agent still
 * returns a tool_result after being stopped, and letting it through told the user their sub-agent
 * finished normally seconds after they watched themselves stop it.
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
 * A stub SDK that spawns a sub-agent and waits to be told to stop it.
 *
 * `task_started` is the only place the sub-agent's two identities appear together — the tool_use id
 * its messages carry, and the task id `stopTask` takes — so the stub emits it exactly as the real
 * one does. The final tool_result is the sub-agent's aborted report, which the harness must drop.
 */
const STUB_SDK = `
export function query({ prompt, options }) {
  let stopped = null;
  const stream = (async function* () {
    yield { type: 'system', subtype: 'init', session_id: 's1', cwd: options.cwd, tools: [] };
    const iterator = prompt[Symbol.asyncIterator]();
    await iterator.next();

    yield {
      type: 'assistant',
      uuid: 'a1',
      parent_tool_use_id: null,
      message: {
        role: 'assistant',
        content: [
          { type: 'text', text: 'Sending a sub-agent.' },
          {
            type: 'tool_use',
            id: 'toolu_task',
            name: 'Task',
            input: { description: 'Audit runtime-api', prompt: 'List the public declarations.', subagent_type: 'Explore' },
          },
        ],
      },
    };
    yield {
      type: 'system',
      subtype: 'task_started',
      task_id: 'task-7',
      tool_use_id: 'toolu_task',
      description: 'Audit runtime-api',
      subagent_type: 'Explore',
      uuid: 'sys1',
      session_id: 's1',
    };

    // The delegate, in its own voice, under its own parent id.
    yield {
      type: 'assistant',
      uuid: 'a2',
      parent_tool_use_id: 'toolu_task',
      message: {
        role: 'assistant',
        content: [
          { type: 'text', text: 'Starting from the entry points.' },
          { type: 'tool_use', id: 'toolu_grep', name: 'Grep', input: { pattern: 'public ' } },
        ],
      },
    };

    // Held open until the harness calls stopTask, which is what the test is waiting to see.
    await new Promise((resolve) => { stopped = resolve; });

    // The aborted report. Arrives whether or not anybody stopped it.
    yield {
      type: 'user',
      uuid: 'u1',
      parent_tool_use_id: null,
      message: {
        role: 'user',
        content: [{ type: 'tool_result', tool_use_id: 'toolu_task', content: 'Interrupted by user' }],
      },
    };
    yield { type: 'result', subtype: 'success', result: 'done', num_turns: 1 };
  })();

  stream.stopTask = async (taskId) => {
    // Echoed back through a diagnostic so the test can see which id the harness reached for.
    process.stderr.write('[stub] stopTask ' + taskId + '\\n');
    if (stopped) stopped();
  };
  stream.interrupt = async () => {};
  return stream;
}
`;

function stubbedHarness() {
  const root = mkdtempSync(join(tmpdir(), 'box-subagent-'));
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
 * Runs a session, sending `command` once the sub-agent's card has opened.
 *
 * Waiting for the event rather than sleeping is the point: a stop that arrives before the harness
 * knows the task id is a different case, and this one is about the ordinary one.
 *
 * `until` is for the cases where nothing stops: the stub holds the sub-agent open until a real stop
 * lands, so a test about a stop that names nobody has to say for itself when it has seen enough.
 */
function runSession(command, until = null) {
  const { root, harness } = stubbedHarness();
  const child = spawn(process.execPath, [harness], {
    cwd: root,
    env: {
      ...process.env,
      BOX_SESSION_CWD: root,
      ANTHROPIC_API_KEY: 'stub-key-unused',
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const events = [];
  let diagnostics = '';
  child.stderr.on('data', (chunk) => { diagnostics += String(chunk); });

  return new Promise((resolve, reject) => {
    child.stdin.write(JSON.stringify({ type: 'prompt', text: 'audit the api' }) + '\n');
    const reader = createInterface({ input: child.stdout });
    reader.on('line', (line) => {
      const event = JSON.parse(line);
      events.push(event);
      if (event.type === 'tool_started' && event.tool?.kind === 'task') {
        child.stdin.write(JSON.stringify(command(event.callId)) + '\n');
      }
      if (until?.(event)) {
        child.kill();
        resolve({ events, diagnostics });
      }
      if (event.type === 'session_ended') child.stdin.end();
    });
    child.on('error', reject);
    child.on('close', () => resolve({ events, diagnostics }));
    setTimeout(() => { child.kill(); reject(new Error('harness did not finish')); }, 15000);
  });
}

test('a sub-agent narrates itself under its own id', async () => {
  const { events } = await runSession((subAgentId) => ({ type: 'stop_subagent', subAgentId }));

  const spawned = events.find((event) => event.type === 'tool_started' && event.tool.kind === 'task');
  assert.equal(spawned.callId, 'toolu_task');
  assert.equal(spawned.tool.agentType, 'Explore');
  // The call that spawns it belongs to the agent that spawned it.
  assert.equal('subAgentId' in spawned, false);

  const said = events.find((event) => event.type === 'message' && event.subAgentId);
  assert.equal(said.subAgentId, 'toolu_task');
  assert.match(said.text, /entry points/);

  const used = events.find((event) => event.type === 'tool_started' && event.callId === 'toolu_grep');
  assert.equal(used.subAgentId, 'toolu_task');
  assert.equal(used.tool.kind, 'search');
});

test('a stop reaches the SDK as a cancel of that one task', async () => {
  const { diagnostics } = await runSession((subAgentId) => ({ type: 'stop_subagent', subAgentId }));

  // The task id, not the tool_use id: they are different identifiers for the same sub-agent, and
  // only one of them stops anything.
  assert.match(diagnostics, /stopTask task-7/);
});

test('a stopped sub-agent is closed as stopped, and stays that way', async () => {
  const { events } = await runSession((subAgentId) => ({ type: 'stop_subagent', subAgentId }));

  const finishes = events.filter((event) => event.type === 'tool_finished' && event.callId === 'toolu_task');
  // Exactly one: the delegate's own "Interrupted by user" report is dropped rather than
  // overwriting this with a success.
  assert.equal(finishes.length, 1);
  assert.equal(finishes[0].outcome.status, 'cancelled');
  // And the session it belonged to carried on to its own end.
  assert.equal(events.at(-1).type, 'session_ended');
  assert.equal(events.at(-1).outcome.status, 'completed');
});

test('a stop for a sub-agent Box cannot name says so instead of stopping the session', async () => {
  const { events, diagnostics } = await runSession(
    () => ({ type: 'stop_subagent', subAgentId: 'toolu_nobody' }),
    (event) => event.type === 'error',
  );

  const complaint = events.find((event) => event.type === 'error');
  assert.match(complaint.message, /could not stop/i);
  assert.equal(complaint.recoverable, true);
  // Nothing was cancelled, and in particular not the whole run.
  assert.equal(diagnostics.includes('stopTask'), false);
  assert.equal(events.some((event) => event.type === 'tool_finished' && event.outcome.status === 'cancelled'), false);
});

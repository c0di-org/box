/**
 * The Claude-specific half of the harness: tool names, permission asks and SDK messages translated
 * into Box's vocabulary. No SDK call, no network — run with `node --test`.
 *
 * The patch cases matter most. The permission sheet exists so someone can approve an edit without
 * opening a terminal, so a diff with invented line numbers would be worse than no diff at all.
 *
 * The attribution cases matter for the same reason one level up: a sub-agent's work shown as the
 * parent's is a transcript that misreports who did what.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const {
  describeTool,
  describeAsk,
  answeredInput,
  editPatch,
  createPatch,
  translateAssistant,
  translateToolResults,
} = await import('../harness/box-claude-harness.mjs');

/**
 * The events one translation call writes.
 *
 * `emit` writes a whole line to stdout synchronously, which is the property the app depends on, so
 * borrowing stdout for the duration of the call is enough to read what a translation says. Nothing
 * else writes while `run` is on the stack.
 */
function emitted(run) {
  const lines = [];
  const original = process.stdout.write.bind(process.stdout);
  process.stdout.write = (chunk) => { lines.push(String(chunk)); return true; };
  try {
    run();
  } finally {
    process.stdout.write = original;
  }
  return lines.join('').trim().split('\n').filter(Boolean).map((line) => JSON.parse(line));
}

const assistant = (content, parent = null) => ({
  type: 'assistant',
  uuid: 'a1',
  parent_tool_use_id: parent,
  message: { role: 'assistant', content },
});

const scratch = mkdtempSync(join(tmpdir(), 'box-harness-'));

function fixture(name, contents) {
  const path = join(scratch, name);
  writeFileSync(path, contents);
  return path;
}

test('a shell tool keeps its command and working directory', () => {
  const tool = describeTool('Bash', { command: 'npm test', cwd: '/workspace/app' });
  assert.equal(tool.kind, 'shell');
  assert.equal(tool.command, 'npm test');
  assert.equal(tool.workingDirectory, '/workspace/app');
});

test('a read tool turns offset and limit into a range', () => {
  const tool = describeTool('Read', { file_path: '/workspace/main.js', offset: 10, limit: 5 });
  assert.equal(tool.kind, 'read_file');
  assert.equal(tool.from, 10);
  assert.equal(tool.to, 15);
});

test('an unmodelled tool degrades to a labelled card rather than a raw dump', () => {
  const tool = describeTool('SomeFutureTool', { alpha: 1, beta: { nested: true } });
  assert.equal(tool.kind, 'generic');
  assert.equal(tool.name, 'SomeFutureTool');
  assert.deepEqual(tool.arguments[0], ['alpha', '1']);
  // Structure is written out, never dropped and never punctuated: a degraded card still has to be
  // honest, and the card it degrades to promises in as many words that it is not raw JSON.
  assert.equal(tool.arguments[1][1], 'nested: true');
});

test('an edit patch carries the real line number of the change', () => {
  const path = fixture('config.js', 'one\ntwo\nthree\nfour\n');
  const patch = editPatch(path, 'three', 'THREE');
  // "three" is on line 3, and the sheet shows that number to the person approving it.
  assert.match(patch, /^@@ -3,1 \+3,1 @@/);
  assert.match(patch, /^-three$/m);
  assert.match(patch, /^\+THREE$/m);
});

test('an edit Box cannot locate degrades instead of inventing a position', () => {
  const path = fixture('other.js', 'alpha\n');
  assert.equal(editPatch(path, 'not-in-the-file', 'x'), null);
  assert.equal(editPatch(join(scratch, 'missing.js'), 'a', 'b'), null);
});

test('an unreadable edit still asks, with a stated reason rather than a silent gap', () => {
  const ask = describeAsk('Edit', {
    file_path: join(scratch, 'missing.js'),
    old_string: 'a',
    new_string: 'b',
  });
  assert.equal(ask.kind, 'edit_file');
  assert.equal(ask.patch, null);
  assert.match(ask.rationale, /could not read/i);
});

test('a new file is asked for as a creation of every one of its lines', () => {
  const patch = createPatch('/workspace/new.txt', 'first\nsecond');
  assert.match(patch, /^@@ -0,0 \+1,2 @@/);
  assert.match(patch, /^\+first$/m);
  assert.match(patch, /^\+second$/m);
});

test('a command may never be blanket-approved in one tap', () => {
  const ask = describeAsk('Bash', { command: 'rm -rf build', cwd: '/workspace' });
  assert.equal(ask.kind, 'run_command');
  // Editing offers "always allow"; arbitrary shell deliberately does not.
  assert.equal(ask.alwaysAllowScope, null);
  assert.equal(describeAsk('Write', { file_path: '/w/a.txt', content: 'x' }).alwaysAllowScope,
    'edits in this project');
});

test('a network ask names the host, not the whole URL', () => {
  const ask = describeAsk('WebFetch', { url: 'https://example.com/a/b?c=d', prompt: 'read docs' });
  assert.equal(ask.kind, 'network_access');
  assert.equal(ask.host, 'example.com');
  assert.equal(ask.alwaysAllowScope, 'requests to example.com');
});

test('a malformed URL still produces an ask instead of throwing', () => {
  const ask = describeAsk('WebFetch', { url: 'not a url' });
  assert.equal(ask.kind, 'network_access');
  assert.equal(ask.host, 'not a url');
});

test('a sub-agent is a first-class tool call carrying what it was asked to do', () => {
  const tool = describeTool('Task', {
    description: 'Audit runtime-api',
    prompt: 'List every public declaration and note which carry KDoc.',
    subagent_type: 'Explore',
  });
  assert.equal(tool.kind, 'task');
  assert.equal(tool.description, 'Audit runtime-api');
  assert.equal(tool.agentType, 'Explore');
  assert.match(tool.prompt, /^List every public declaration/);
});

test('a sub-agent with no description still gets a name to show', () => {
  assert.equal(describeTool('Task', { subagent_type: 'Explore' }).description, 'Explore');
  assert.equal(describeTool('Task', {}).description, 'Sub-agent');
});

test('what the session\'s own agent says carries no attribution', () => {
  const events = emitted(() => translateAssistant(assistant([{ type: 'text', text: 'On it.' }])));
  assert.equal(events.length, 1);
  assert.equal(events[0].type, 'message');
  // Absent, not null-and-present: an older app reading this line sees exactly what it used to.
  assert.equal('subAgentId' in events[0], false);
});

test('everything a sub-agent says and does is stamped with the sub-agent', () => {
  const events = emitted(() => translateAssistant(assistant([
    { type: 'text', text: 'Starting from the entry points.' },
    { type: 'thinking', thinking: 'nine files' },
    { type: 'tool_use', id: 'c1', name: 'Grep', input: { pattern: 'public ' } },
  ], 'a1')));

  assert.deepEqual(events.map((event) => event.type), ['message', 'thinking', 'tool_started']);
  for (const event of events) assert.equal(event.subAgentId, 'a1');
  // Still the same structured card it would be in the parent's own transcript.
  assert.equal(events[2].tool.kind, 'search');
});

test('a tool result belonging to a sub-agent finishes the sub-agent\'s own card', () => {
  const events = emitted(() => translateToolResults({
    type: 'user',
    parent_tool_use_id: 'a1',
    message: {
      role: 'user',
      content: [{ type: 'tool_result', tool_use_id: 'c1', content: '63 matches' }],
    },
  }));

  assert.equal(events.length, 1);
  assert.equal(events[0].callId, 'c1');
  assert.equal(events[0].subAgentId, 'a1');
  assert.match(events[0].outcome.output, /63 matches/);
});

test('a Task block is the sub-agent card, and its id is the sub-agent', () => {
  const events = emitted(() => translateAssistant(assistant([
    { type: 'tool_use', id: 'a1', name: 'Task', input: { description: 'Audit runtime-api' } },
  ])));

  assert.equal(events[0].type, 'tool_started');
  assert.equal(events[0].callId, 'a1');
  assert.equal(events[0].tool.kind, 'task');
  // The call that spawns a sub-agent belongs to the agent that spawned it, not to the delegate.
  assert.equal('subAgentId' in events[0], false);
});

test('a checklist update is a plan, and draws no tool card at either end', () => {
  const started = emitted(() => translateAssistant(assistant([
    {
      type: 'tool_use',
      id: 'todo-1',
      name: 'TodoWrite',
      input: { todos: [{ content: 'clone the repo', status: 'in_progress' }] },
    },
  ])));

  assert.deepEqual(started.map((event) => event.type), ['task_progress']);
  assert.deepEqual(started[0].items, [{ text: 'clone the repo', state: 'in_progress' }]);

  // The other half. A `tool_finished` whose start the app never saw is not dropped there — it has
  // the app build a placeholder card labelled "Tool" out of nothing, one after every checklist.
  const finished = emitted(() => translateToolResults({
    type: 'user',
    message: {
      role: 'user',
      content: [{ type: 'tool_result', tool_use_id: 'todo-1', content: 'Todos have been modified' }],
    },
  }));

  assert.deepEqual(finished, []);
});

test('sending a sub-agent is asked for in those words, and never blanket-approved', () => {
  const ask = describeAsk('Task', {
    description: 'Audit runtime-api',
    prompt: 'List every public declaration.',
    subagent_type: 'Explore',
  });
  assert.match(ask.description, /Audit runtime-api/);
  assert.deepEqual(ask.details[0], ['Kind', 'Explore']);
  // One sub-agent is one cost; "always allow" would answer for every later one too.
  assert.equal(ask.alwaysAllowScope, null);
});

/**
 * The question round trip.
 *
 * `AskUserQuestion` is answered through the permission result rather than beside it: the tool's own
 * input carries an `answers` field the SDK documents as "collected by the permission component",
 * and a host fills it in by handing the input back as `updatedInput`. These pin both ends of that
 * — the ask the sheet is drawn from, and the input an answer turns into.
 */
const question = (over = {}) => ({
  questions: [
    {
      question: 'Which model should the sub-agent use?',
      header: 'Model',
      multiSelect: false,
      options: [
        { label: 'Sonnet', description: 'Faster, cheaper.' },
        { label: 'Opus', description: 'Better at long reasoning.' },
      ],
    },
  ],
  ...over,
});

test('a question is asked as a question, not as a permission to weigh', () => {
  const ask = describeAsk('AskUserQuestion', question());

  assert.equal(ask.kind, 'question');
  assert.equal(ask.questions.length, 1);
  assert.equal(ask.questions[0].text, 'Which model should the sub-agent use?');
  assert.equal(ask.questions[0].header, 'Model');
  assert.deepEqual(ask.questions[0].options.map((option) => option.label), ['Sonnet', 'Opus']);
  assert.equal(ask.questions[0].options[0].description, 'Faster, cheaper.');
  // "Always allow" here would answer questions nobody has read yet.
  assert.equal(ask.alwaysAllowScope, null);
});

test('a question with nothing to choose between is dropped rather than drawn', () => {
  const ask = describeAsk('AskUserQuestion', {
    questions: [
      { question: 'Which one?', header: 'Pick', options: [{ label: '' }] },
      { question: '', header: 'Empty', options: [{ label: 'Yes' }] },
    ],
  });

  // A sheet whose only job is to be answerable must not be handed a question that cannot be.
  assert.deepEqual(ask.questions, []);
});

test('an answer becomes the tool’s own input, with the answers filled in', () => {
  const input = question();
  const answered = answeredInput('AskUserQuestion', input, {
    'Which model should the sub-agent use?': 'Opus',
  });

  assert.deepEqual(answered.answers, { 'Which model should the sub-agent use?': 'Opus' });
  // The questions ride along untouched: this is the same call, now carrying its answer.
  assert.deepEqual(answered.questions, input.questions);
  assert.notEqual(answered, input);
});

test('an answer to a question that was never asked is dropped, not passed on', () => {
  // The app can be older or newer than the guest image. An answer keyed to a question this call
  // did not ask would otherwise reach the model looking exactly like one somebody gave.
  assert.equal(answeredInput('AskUserQuestion', question(), { 'Some other question?': 'Opus' }), null);
  assert.equal(answeredInput('AskUserQuestion', question(), {}), null);
  assert.equal(answeredInput('AskUserQuestion', question(), undefined), null);
  assert.equal(answeredInput('Bash', { command: 'ls' }, { 'Which?': 'Opus' }), null);
});

test('a multi-select answer keeps the shape the tool documents for it', () => {
  const input = { questions: [{ question: 'Which ones?', header: 'Scope', multiSelect: true, options: [{ label: 'a' }, { label: 'b' }] }] };
  const answered = answeredInput('AskUserQuestion', input, { 'Which ones?': 'a, b' });

  assert.equal(answered.answers['Which ones?'], 'a, b');
});

test('a question draws a card saying what was asked, in words', () => {
  // Under `bypassPermissions` no sheet is ever drawn, so this card is the only record that the
  // agent asked at all.
  const tool = describeTool('AskUserQuestion', question());

  assert.equal(tool.kind, 'generic');
  assert.equal(tool.name, 'Asked you');
  assert.deepEqual(tool.arguments, [['Model', 'Which model should the sub-agent use?']]);
});

test('a structured argument reaches a key/value card as words, never as JSON', () => {
  // `ToolCall.Generic` promises "never raw JSON" in as many words, and kept it only for arguments
  // that happened to be strings.
  const tool = describeTool('SomeUnmodelledTool', question());
  const [, value] = tool.arguments[0];

  assert.doesNotMatch(value, /[{}[\]"]/);
  assert.match(value, /Which model should the sub-agent use\?/);
  assert.match(value, /options: 2 items/);
});

test('the same rule holds on the permission sheet a tool Box does not model falls back to', () => {
  const ask = describeAsk('SomeUnmodelledTool', { plan: { steps: ['one', 'two'], dryRun: true } });
  const [, value] = ask.details[0];

  assert.doesNotMatch(value, /[{}[\]"]/);
  assert.equal(value, 'steps: one; two, dryRun: true');
});

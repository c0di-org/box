/**
 * The Claude-specific half of the harness: tool names and permission asks translated into Box's
 * vocabulary. Pure functions, no SDK call, no network — run with `node --test`.
 *
 * The patch cases matter most. The permission sheet exists so someone can approve an edit without
 * opening a terminal, so a diff with invented line numbers would be worse than no diff at all.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const { describeTool, describeAsk, editPatch, createPatch } =
  await import('../harness/box-claude-harness.mjs');

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
  // Objects are stringified, never dropped: a degraded card still has to be honest.
  assert.equal(tool.arguments[1][1], '{"nested":true}');
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

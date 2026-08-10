#!/usr/bin/env node
/**
 * Box's Claude Code harness.
 *
 * Runs the Claude Agent SDK inside the guest and narrates it in Box's own event vocabulary: one
 * JSON object per line on stdout, decisions and follow-up prompts as JSON lines on stdin.
 *
 * That wire format is the harness-agnostic boundary. The Android side knows only these events, so
 * a Cursor or opencode harness is a *different program emitting the same lines* — never a second
 * vocabulary for the UI to learn. Everything Claude-specific stops here.
 *
 * Two rules the app depends on:
 *   1. The event log is append-only. Nothing is mutated; a tool call that finishes emits a second
 *      event referencing the first by callId, and streamed text re-emits under one messageId.
 *   2. A credential never appears in an event, ever. It is read from disk and handed to the SDK.
 */

import { createInterface } from 'node:readline';
import { readFileSync, realpathSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const PROTOCOL = 1;

// ---------------------------------------------------------------- output

/**
 * One event, one line, written whole.
 *
 * process.stdout.write of a string under ~64KiB is delivered as one chunk on a pipe, which is what
 * keeps a reader on the other side from ever seeing half an event. Anything genuinely large (a
 * diff, command output) is truncated before it gets here rather than risking a split line.
 */
function emit(event) {
  process.stdout.write(JSON.stringify({ v: PROTOCOL, at: Date.now(), ...event }) + '\n');
}

/** stderr is for humans reading logcat. It is never part of the event stream. */
function diagnostic(message) {
  process.stderr.write(`[box-harness] ${message}\n`);
}

const MAX_TEXT = 32 * 1024;
const clip = (text, limit = MAX_TEXT) =>
  typeof text === 'string' && text.length > limit ? text.slice(0, limit) + '\n…truncated' : text;

// ---------------------------------------------------------------- input

/** Resolvers for permission requests the user has not answered yet, keyed by requestId. */
const pendingPermissions = new Map();

/** Prompts waiting to be fed into the SDK, and whoever is waiting for the next one. */
const promptQueue = [];
let promptWaiter = null;
let inputClosed = false;

function pushPrompt(text) {
  if (promptWaiter) {
    const resolve = promptWaiter;
    promptWaiter = null;
    resolve(text);
  } else {
    promptQueue.push(text);
  }
}

function nextPrompt() {
  if (promptQueue.length > 0) return Promise.resolve(promptQueue.shift());
  if (inputClosed) return Promise.resolve(null);
  return new Promise((resolve) => { promptWaiter = resolve; });
}

let activeQuery = null;

function handleCommand(line) {
  let command;
  try {
    command = JSON.parse(line);
  } catch {
    diagnostic('ignoring a line that was not JSON');
    return;
  }
  switch (command.type) {
    case 'prompt': {
      const text = String(command.text ?? '');
      // Echoed into the log so the transcript has one source of truth in one order. The app could
      // record what the user typed itself, but then a replay would have to interleave two logs and
      // guess where each turn belonged.
      emit({ type: 'user_message', text });
      pushPrompt(text);
      break;
    }
    case 'decision': {
      const resolve = pendingPermissions.get(command.requestId);
      if (!resolve) {
        // A decision for a request that already resolved: the user answered a sheet that had been
        // abandoned, or the harness moved on. Dropping it is correct and must not be fatal.
        diagnostic(`decision for unknown request ${command.requestId}`);
        return;
      }
      pendingPermissions.delete(command.requestId);
      resolve(command);
      break;
    }
    case 'interrupt':
      if (activeQuery) activeQuery.interrupt().catch(() => {});
      break;
    default:
      diagnostic(`unknown command ${command.type}`);
  }
}

// ---------------------------------------------------------------- tool translation

/**
 * Claude Code's tool names to Box's structured tool cards.
 *
 * Anything unmapped becomes `generic`, which the UI renders as a labelled key/value card —
 * degraded, but never a raw JSON dump. That is the fallback the UI contract asks for.
 */
function describeTool(name, input = {}) {
  switch (name) {
    case 'Bash':
    case 'BashOutput':
      return {
        kind: 'shell',
        command: clip(input.command ?? '', 4096),
        workingDirectory: input.cwd ?? process.cwd(),
      };
    case 'Read':
      return {
        kind: 'read_file',
        path: input.file_path ?? input.path ?? '',
        from: input.offset ?? null,
        to: input.offset != null && input.limit != null ? input.offset + input.limit : null,
      };
    case 'Edit':
    case 'MultiEdit':
    case 'NotebookEdit':
      return {
        kind: 'edit_file',
        path: input.file_path ?? input.notebook_path ?? '',
        summary: input.edits ? `${input.edits.length} edits` : null,
      };
    case 'Write':
      return {
        kind: 'write_file',
        path: input.file_path ?? '',
        bytes: typeof input.content === 'string' ? Buffer.byteLength(input.content) : null,
      };
    case 'Grep':
      return { kind: 'search', query: input.pattern ?? '', scope: input.path ?? null };
    case 'Glob':
      return { kind: 'search', query: input.pattern ?? '', scope: input.path ?? null };
    case 'WebFetch':
      return { kind: 'fetch', url: input.url ?? '' };
    case 'WebSearch':
      return { kind: 'search', query: input.query ?? '', scope: 'the web' };
    default:
      return {
        kind: 'generic',
        name,
        arguments: Object.entries(input)
          .slice(0, 8)
          .map(([key, value]) => [key, clip(String(typeof value === 'object' ? JSON.stringify(value) : value), 512)]),
      };
  }
}

/**
 * A real unified diff for the permission sheet, with real line numbers.
 *
 * The sheet's whole job is to let someone approve an edit without opening a terminal, so a patch
 * with invented line numbers would be worse than none. The file is read to locate the edit; if it
 * cannot be read the ask degrades to a summary rather than lying about position.
 */
function editPatch(path, oldString, newString) {
  let contents;
  try {
    contents = readFileSync(path, 'utf8');
  } catch {
    return null;
  }
  const index = contents.indexOf(oldString);
  if (index < 0) return null;

  const startLine = contents.slice(0, index).split('\n').length;
  const removed = oldString.split('\n');
  const added = newString.split('\n');
  const body = [
    ...removed.map((line) => `-${line}`),
    ...added.map((line) => `+${line}`),
  ].join('\n');
  return `@@ -${startLine},${removed.length} +${startLine},${added.length} @@\n${body}`;
}

function createPatch(path, content) {
  const lines = content.split('\n');
  return `@@ -0,0 +1,${lines.length} @@\n${lines.map((line) => `+${line}`).join('\n')}`;
}

/** Everything the sheet needs to explain the risk without a round trip. */
function describeAsk(name, input = {}) {
  switch (name) {
    case 'Edit': {
      const patch = editPatch(input.file_path, input.old_string ?? '', input.new_string ?? '');
      return {
        kind: 'edit_file',
        path: input.file_path ?? '',
        patch,
        changeKind: 'modify',
        rationale: patch ? null : 'Box could not read the file to show the exact change.',
        alwaysAllowScope: 'edits in this project',
      };
    }
    case 'Write':
      return {
        kind: 'edit_file',
        path: input.file_path ?? '',
        patch: createPatch(input.file_path ?? '', input.content ?? ''),
        changeKind: 'create',
        rationale: null,
        alwaysAllowScope: 'edits in this project',
      };
    case 'Bash':
      return {
        kind: 'run_command',
        command: clip(input.command ?? '', 4096),
        workingDirectory: input.cwd ?? process.cwd(),
        rationale: input.description ?? null,
        // No always-allow for shell: a blanket yes to arbitrary commands is not a promise Box
        // should let someone make in one tap.
        alwaysAllowScope: null,
      };
    case 'WebFetch': {
      let host = input.url ?? '';
      try { host = new URL(input.url).host; } catch { /* keep the raw string */ }
      return {
        kind: 'network_access',
        host,
        purpose: input.prompt ? clip(input.prompt, 200) : 'Fetch a page',
        alwaysAllowScope: `requests to ${host}`,
      };
    }
    default:
      return {
        kind: 'generic',
        title: `Allow ${name}?`,
        description: 'The agent wants to use a tool Box does not model yet.',
        details: Object.entries(input).slice(0, 6).map(([key, value]) => [
          key,
          clip(String(typeof value === 'object' ? JSON.stringify(value) : value), 512),
        ]),
        alwaysAllowScope: null,
      };
  }
}

// ---------------------------------------------------------------- permissions

let nextRequestId = 0;

/**
 * The permission round-trip.
 *
 * The SDK pauses the tool call until this resolves and does not time out, so blocking on a person
 * who put their phone in their pocket is a supported case rather than a hack. The only thing that
 * ends the wait early is the query itself being cancelled.
 */
function canUseTool(toolName, input, { signal, suggestions }) {
  return new Promise((resolve) => {
    const requestId = `perm-${++nextRequestId}`;
    const ask = describeAsk(toolName, input);

    const settle = (decision) => {
      if (!pendingPermissions.has(requestId)) return;
      pendingPermissions.delete(requestId);
      resolve(decision);
    };

    pendingPermissions.set(requestId, (command) => {
      const allowed = command.decision === 'allow' || command.decision === 'allow_always';
      emit({ type: 'permission_resolved', requestId, decision: command.decision });
      if (!allowed) {
        resolve({
          behavior: 'deny',
          message: 'The person using Box declined this.',
        });
        return;
      }
      resolve({
        behavior: 'allow',
        // Carrying the SDK's own suggestions back is what makes "always allow" stick for the rest
        // of the session instead of asking again on the very next edit.
        ...(command.decision === 'allow_always' && suggestions
          ? { updatedPermissions: suggestions }
          : {}),
      });
    });

    signal?.addEventListener('abort', () => {
      emit({ type: 'permission_resolved', requestId, decision: 'abandoned' });
      settle({ behavior: 'deny', message: 'The task was interrupted.' });
    }, { once: true });

    emit({ type: 'permission_requested', requestId, ask });
  });
}

// ---------------------------------------------------------------- translation

const toolNames = new Map();
/** TodoWrite is a checklist, not a tool card: it drives the plan block instead. */
let planId = 0;

function translateAssistant(message) {
  for (const block of message.message?.content ?? []) {
    if (block.type === 'text' && block.text) {
      emit({ type: 'message', messageId: message.uuid, text: clip(block.text), complete: true });
    } else if (block.type === 'thinking' && block.thinking) {
      emit({ type: 'thinking', messageId: message.uuid, text: clip(block.thinking), complete: true });
    } else if (block.type === 'tool_use') {
      if (block.name === 'TodoWrite') {
        emit({
          type: 'task_progress',
          planId: `plan-${++planId}`,
          items: (block.input?.todos ?? []).map((todo) => ({
            text: todo.content ?? todo.activeForm ?? '',
            state: todo.status ?? 'pending',
          })),
        });
        continue;
      }
      toolNames.set(block.id, block.name);
      emit({ type: 'tool_started', callId: block.id, tool: describeTool(block.name, block.input) });

      // An edit the user already approved still has to show up as a change in the transcript.
      if (block.name === 'Write' && block.input?.file_path) {
        emit({
          type: 'file_changed',
          callId: block.id,
          path: block.input.file_path,
          patch: createPatch(block.input.file_path, block.input.content ?? ''),
          changeKind: 'create',
        });
      } else if (block.name === 'Edit' && block.input?.file_path) {
        const patch = editPatch(block.input.file_path, block.input.old_string ?? '', block.input.new_string ?? '');
        if (patch) {
          emit({
            type: 'file_changed',
            callId: block.id,
            path: block.input.file_path,
            patch,
            changeKind: 'modify',
          });
        }
      }
    }
  }
}

function translateToolResults(message) {
  for (const block of message.message?.content ?? []) {
    if (block.type !== 'tool_result') continue;
    const output = Array.isArray(block.content)
      ? block.content.filter((part) => part.type === 'text').map((part) => part.text).join('\n')
      : String(block.content ?? '');
    emit({
      type: 'tool_finished',
      callId: block.tool_use_id,
      outcome: block.is_error
        ? { status: 'failure', message: clip(output, 2048) || 'The tool failed.', output: clip(output) }
        : { status: 'success', output: clip(output), summary: null },
    });
    toolNames.delete(block.tool_use_id);
  }
}

// ---------------------------------------------------------------- main

async function* prompts() {
  while (true) {
    const text = await nextPrompt();
    if (text === null) return;
    yield {
      type: 'user',
      message: { role: 'user', content: text },
      parent_tool_use_id: null,
    };
  }
}

/**
 * Loaded on demand, not at module scope.
 *
 * A missing or half-installed harness is a normal state — it is exactly what a first run looks
 * like — and it has to arrive in the transcript as something Box can render, not as an
 * ERR_MODULE_NOT_FOUND stack trace on stderr that the UI never sees. Keeping the import lazy also
 * lets the translation above be tested without the 295MB dependency present.
 */
async function loadSdk() {
  try {
    return (await import('@anthropic-ai/claude-agent-sdk')).query;
  } catch (error) {
    emit({
      type: 'error',
      message: 'Claude Code is not installed in this workspace yet.',
      detail: clip(String(error?.message ?? error), 512),
      recoverable: false,
    });
    emit({ type: 'session_ended', outcome: { status: 'failed', message: 'Harness missing' } });
    return null;
  }
}

async function main() {
  const cwd = process.env.BOX_SESSION_CWD || '/workspace';

  // The credential is read here and handed straight to the SDK. It is never emitted, never logged,
  // and never placed in an argv the process list could show.
  const credentialPath = process.env.BOX_CREDENTIAL_FILE;
  if (credentialPath) {
    try {
      const stored = JSON.parse(readFileSync(credentialPath, 'utf8'));
      if (stored.apiKey) process.env.ANTHROPIC_API_KEY = stored.apiKey;
      if (stored.authToken) process.env.ANTHROPIC_AUTH_TOKEN = stored.authToken;
      if (stored.baseUrl) process.env.ANTHROPIC_BASE_URL = stored.baseUrl;
    } catch {
      diagnostic('no usable credential file');
    }
  }

  if (!process.env.ANTHROPIC_API_KEY && !process.env.ANTHROPIC_AUTH_TOKEN) {
    emit({
      type: 'error',
      message: 'Box is not signed in yet.',
      detail: 'Add your Anthropic API key in Box to let the agent work.',
      recoverable: false,
    });
    emit({ type: 'session_ended', outcome: { status: 'failed', message: 'Not signed in' } });
    return;
  }

  const query = await loadSdk();
  if (!query) return;

  const reader = createInterface({ input: process.stdin });
  reader.on('line', handleCommand);
  reader.on('close', () => {
    inputClosed = true;
    if (promptWaiter) { const resolve = promptWaiter; promptWaiter = null; resolve(null); }
  });

  emit({ type: 'session_started', cwd, harness: 'claude-code' });

  activeQuery = query({
    prompt: prompts(),
    options: {
      cwd,
      canUseTool,
      permissionMode: 'default',
      includePartialMessages: false,
    },
  });

  let ended = false;
  try {
    for await (const message of activeQuery) {
      switch (message.type) {
        case 'system':
          if (message.subtype === 'init') {
            emit({ type: 'activity', activity: { kind: 'thinking' } });
          } else if (message.subtype === 'task_progress') {
            emit({ type: 'activity', activity: { kind: 'working', label: message.description } });
          }
          break;
        case 'assistant':
          translateAssistant(message);
          break;
        case 'user':
          translateToolResults(message);
          break;
        case 'tool_progress':
          emit({ type: 'tool_progress', callId: message.tool_use_id, chunk: '' });
          break;
        case 'result':
          ended = true;
          emit({
            type: 'session_ended',
            outcome: message.subtype === 'success'
              ? { status: 'completed', summary: clip(message.result, 2048) }
              : { status: 'failed', message: message.subtype },
          });
          break;
        default:
          break;
      }
    }
    // session_ended is terminal: nothing may follow it. Reporting "idle" afterwards would tell the
    // UI a finished session is waiting for typing. If the stream ended without a result at all,
    // say so rather than leaving the transcript running forever.
    if (!ended) {
      emit({ type: 'session_ended', outcome: { status: 'interrupted' } });
    }
  } catch (error) {
    emit({
      type: 'error',
      message: 'The agent stopped unexpectedly.',
      detail: clip(String(error?.message ?? error), 2048),
      recoverable: true,
    });
    emit({ type: 'session_ended', outcome: { status: 'failed', message: 'The agent stopped' } });
  }
}

// Only when run as a program. Imported — by the translation tests — this stays inert.
//
// Both sides are resolved through realpath first: `import.meta.url` is always the real path, while
// argv[1] is whatever the caller typed. If the harness is reached through a symlink — /var on
// macOS, or any install that links into place — comparing them raw silently decides this is not
// the entry point, and the process exits having done nothing at all.
const isEntryPoint = (() => {
  if (!process.argv[1]) return false;
  try {
    return import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href;
  } catch {
    return false;
  }
})();

if (isEntryPoint) {
  main().catch((error) => {
    diagnostic(`fatal: ${error?.stack ?? error}`);
    process.exit(1);
  });
}

export { describeTool, describeAsk, editPatch, createPatch };

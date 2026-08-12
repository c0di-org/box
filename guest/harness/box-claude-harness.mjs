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
 *
 * Sub-agents live in that same flat log: a Task tool call names one, and everything the delegate
 * then says or does carries its `subAgentId`. See `attribution` and `stopSubAgent`.
 */

import { createInterface } from 'node:readline';
import { existsSync, readFileSync, realpathSync } from 'node:fs';
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

/**
 * Waits for everything already emitted to reach the pipe.
 *
 * Only needed where the process exits on purpose: writes to a pipe are asynchronous, so exiting
 * straight after an `emit` can drop the very event that says how things ended. The empty write is
 * queued behind the real ones, so its callback runs once they have been handed to the OS.
 */
function flushed() {
  return new Promise((resolve) => process.stdout.write('', resolve));
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

/** The code the user brings back from their browser, waiting for the sign-in to ask for it. */
const authCodeQueue = [];
let authCodeWaiter = null;

function pushAuthCode(code) {
  if (authCodeWaiter) {
    const resolve = authCodeWaiter;
    authCodeWaiter = null;
    resolve(code);
  } else {
    authCodeQueue.push(code);
  }
}

function nextAuthCode() {
  if (authCodeQueue.length > 0) return Promise.resolve(authCodeQueue.shift());
  if (inputClosed) return Promise.resolve(null);
  return new Promise((resolve) => { authCodeWaiter = resolve; });
}

let activeQuery = null;

/**
 * How permission is answered, in the SDK's own vocabulary.
 *
 * Owned by the app: Box has one setting for the whole box, and the app tells every harness what it
 * is on attach, ahead of the first prompt. Starting at 'default' matters — a harness that came up
 * before the app could speak to it asks, which is the state a user can always recover from.
 */
const PERMISSION_MODES = new Set(['default', 'acceptEdits', 'bypassPermissions']);
let permissionMode = 'default';

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
    case 'auth_code': {
      // Never echoed. This is the one command whose payload is credential material, so unlike
      // `prompt` it does not get mirrored into the event log.
      pushAuthCode(String(command.code ?? ''));
      break;
    }
    case 'permission_mode': {
      const mode = String(command.mode ?? '');
      if (!PERMISSION_MODES.has(mode)) {
        diagnostic(`ignoring unknown permission mode ${mode}`);
        return;
      }
      permissionMode = mode;
      // Two paths on purpose. A query that has not been created yet reads the variable when it is;
      // one already running is told, because the SDK will not consult `canUseTool` again for a
      // mode it has already resolved against. `setPermissionMode` exists on the query object at
      // runtime but not in the published types, so its absence is a diagnostic rather than a crash
      // — the next session opens in the right mode either way.
      if (activeQuery && typeof activeQuery.setPermissionMode === 'function') {
        activeQuery.setPermissionMode(mode).catch((error) => {
          diagnostic(`could not change permission mode: ${error?.message ?? error}`);
        });
      }
      // Into the log as well as into the SDK. The transcript is the record of what happened, and
      // "nothing asked for the next hour" is only honest if the log says why.
      emit({ type: 'permission_mode', mode });
      break;
    }
    case 'interrupt':
      if (activeQuery) activeQuery.interrupt().catch(() => {});
      break;
    case 'stop_subagent':
      // Its own command rather than an `interrupt` carrying a sub-agent id, because an older
      // harness reads an unknown field while obeying the type it knows — and would stop the whole
      // session. An unknown type is dropped with a diagnostic, which is the right failure.
      stopSubAgent(String(command.subAgentId ?? '')).catch(() => {});
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
    case 'Task':
    case 'Agent':
      // A sub-agent, and the one tool whose card outlives the call: everything the delegate then
      // does arrives stamped with this block's id. Left as `generic` it was one opaque card
      // spinning for the whole life of a sub-agent that was, meanwhile, invisible.
      return {
        kind: 'task',
        description: input.description ?? input.subagent_type ?? 'Sub-agent',
        prompt: clip(input.prompt ?? '', 2048) || null,
        agentType: input.subagent_type ?? null,
      };
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
    case 'Task':
    case 'Agent':
      // Reached only if the guest's settings make spawning one an ask — which is what Box's own
      // conventions tell the agent to do anyway. Without this case the sheet said "a tool Box does
      // not model yet" about the one decision here that costs a second model its own run.
      return {
        kind: 'generic',
        title: 'Send a sub-agent?',
        description: input.description
          ? `It wants to hand this off: ${clip(input.description, 200)}`
          : 'It wants to hand a piece of work to a sub-agent.',
        details: [
          ...(input.subagent_type ? [['Kind', String(input.subagent_type)]] : []),
          ...(input.prompt ? [['Asked to', clip(input.prompt, 400)]] : []),
        ],
        // Never a blanket yes. Each sub-agent is its own cost in tokens and in a phone's time, and
        // "always allow" would answer for the ones nobody has thought of yet.
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

/**
 * Live sub-agents, keyed by the tool_use id of the Task block that started them.
 *
 * That id is the one the rest of Box knows a sub-agent by, because it is the only identity the
 * sub-agent's own messages carry: every message and tool result it produces arrives with
 * `parent_tool_use_id` set to it. The SDK's task id is a *different* identifier, known only from
 * `task_started` — and it is the one `stopTask` wants, which is the whole reason this map exists.
 */
const subAgents = new Map();

/**
 * Sub-agents already reported as stopped.
 *
 * A stopped sub-agent still returns a tool_result — its aborted report — and letting that through
 * would finish the card a second time as a success. The card would then say the delegate completed
 * normally, moments after the user watched themselves stop it.
 */
const stoppedSubAgents = new Set();

/**
 * Stops one sub-agent, or says plainly why it could not.
 *
 * `stopTask` is a real per-task cancel, which is what makes this honest rather than a UI gesture:
 * the sub-agent is told to stand down and the agent that sent it carries on with what it hears
 * back. It is also not in the SDK's published surface for every version Box may find in a guest,
 * so its absence is a reportable condition and never a crash.
 */
async function stopSubAgent(subAgentId) {
  if (stoppedSubAgents.has(subAgentId)) return;
  const taskId = subAgents.get(subAgentId);
  if (!taskId || !activeQuery || typeof activeQuery.stopTask !== 'function') {
    emit({
      type: 'error',
      message: 'Box could not stop that sub-agent.',
      detail: taskId
        ? 'The installed agent does not offer per-task stopping.'
        : 'It had already finished, or never reported itself as a task.',
      recoverable: true,
    });
    return;
  }
  try {
    await activeQuery.stopTask(taskId);
  } catch (error) {
    emit({
      type: 'error',
      message: 'Box could not stop that sub-agent.',
      detail: clip(String(error?.message ?? error), 512),
      recoverable: true,
    });
    return;
  }
  // Marked before the event, so the aborted tool_result that follows is dropped rather than
  // overwriting this with a success.
  stoppedSubAgents.add(subAgentId);
  subAgents.delete(subAgentId);
  emit({ type: 'tool_finished', callId: subAgentId, outcome: { status: 'cancelled' } });
}

/**
 * Who produced this message.
 *
 * `parent_tool_use_id` is the SDK's whole answer to that question, and ignoring it — which this
 * harness used to do — is what flattened a sub-agent into the transcript of the agent that sent it:
 * two voices under one byline, with the delegate's tool cards indistinguishable from its parent's.
 * Stamped onto the event instead, it becomes the identity the app nests a card around.
 */
function attribution(message) {
  const parent = message.parent_tool_use_id;
  return parent ? { subAgentId: parent } : {};
}

function translateAssistant(message) {
  const from = attribution(message);
  for (const block of message.message?.content ?? []) {
    if (block.type === 'text' && block.text) {
      emit({
        type: 'message', messageId: message.uuid, text: clip(block.text), complete: true, ...from,
      });
    } else if (block.type === 'thinking' && block.thinking) {
      emit({
        type: 'thinking', messageId: message.uuid, text: clip(block.thinking), complete: true, ...from,
      });
    } else if (block.type === 'tool_use') {
      if (block.name === 'TodoWrite') {
        emit({
          type: 'task_progress',
          planId: `plan-${++planId}`,
          items: (block.input?.todos ?? []).map((todo) => ({
            text: todo.content ?? todo.activeForm ?? '',
            state: todo.status ?? 'pending',
          })),
          ...from,
        });
        continue;
      }
      toolNames.set(block.id, block.name);
      emit({
        type: 'tool_started', callId: block.id, tool: describeTool(block.name, block.input), ...from,
      });

      // An edit the user already approved still has to show up as a change in the transcript.
      if (block.name === 'Write' && block.input?.file_path) {
        emit({
          type: 'file_changed',
          callId: block.id,
          path: block.input.file_path,
          patch: createPatch(block.input.file_path, block.input.content ?? ''),
          changeKind: 'create',
          ...from,
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
            ...from,
          });
        }
      }
    }
  }
}

function translateToolResults(message) {
  const from = attribution(message);
  for (const block of message.message?.content ?? []) {
    if (block.type !== 'tool_result') continue;
    // The delegate's own aborted report. Its card has already said it was stopped, by the person
    // reading it, and that is the truer of the two answers.
    if (stoppedSubAgents.has(block.tool_use_id)) continue;
    const output = Array.isArray(block.content)
      ? block.content.filter((part) => part.type === 'text').map((part) => part.text).join('\n')
      : String(block.content ?? '');
    emit({
      type: 'tool_finished',
      callId: block.tool_use_id,
      outcome: block.is_error
        ? { status: 'failure', message: clip(output, 2048) || 'The tool failed.', output: clip(output) }
        : { status: 'success', output: clip(output), summary: null },
      ...from,
    });
    toolNames.delete(block.tool_use_id);
    subAgents.delete(block.tool_use_id);
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

/**
 * Whether some credential appears to be present. Diagnostics only — never a gate.
 *
 * Deliberately loose about where a profile lives, for the same reason Box's sign-in screen does not
 * match on exact wording: the layout moves between CLI versions. A miss here costs one stderr line.
 */
function hasSomeCredential() {
  if (process.env.ANTHROPIC_API_KEY || process.env.ANTHROPIC_AUTH_TOKEN) return true;
  const configDir = process.env.CLAUDE_CONFIG_DIR
    || (process.env.HOME ? `${process.env.HOME}/.claude` : null);
  return configDir ? existsSync(`${configDir}/.credentials.json`) : false;
}

/** Loose match on what an auth failure tends to say, across SDK and CLI versions. */
const AUTH_FAILURE = /unauthor|authentication|not logged in|invalid api key|api key|oauth|credential/i;

// ---------------------------------------------------------------- sign-in

/**
 * Signing in, brokered through the phone.
 *
 * The obvious approach — spawn `claude auth login` and type the code into its stdin — cannot work,
 * and it is worth writing down why so nobody re-derives it. That command prints the manual URL but
 * then waits *only* on a loopback HTTP listener it opened for the browser to hit. The pasted code
 * is delivered by a different entry point, which the standalone command never wires to stdin. A
 * code typed at it is read by nobody and the process hangs until it is killed.
 *
 * The SDK's control protocol is the supported way in, and it is the same handshake Claude Code's
 * own login screen uses: ask for the URLs, hand back the code, get told who signed in. Box uses the
 * *manual* URL because the automatic one redirects to loopback inside the guest, which is not a
 * place the phone's browser can reach.
 *
 * These three methods exist on the query object at runtime but are not in the SDK's published
 * types, so their absence is treated as a real, reportable condition rather than a crash.
 */
async function runAuth(query, cwd) {
  const session = query({
    prompt: (async function* () { await new Promise(() => {}); })(),
    options: { cwd, permissionMode: 'default', includePartialMessages: false },
  });
  activeQuery = session;

  // The message stream is what pumps the transport, so a control response only arrives if somebody
  // is iterating. Nothing here cares about the messages themselves.
  (async () => {
    try {
      for await (const _ of session) { /* drained so control responses are read */ }
    } catch (error) {
      diagnostic(`auth stream ended: ${error?.message ?? error}`);
    }
  })();

  if (typeof session.claudeAuthenticate !== 'function') {
    emit({
      type: 'auth_failed',
      message: 'This version of Claude Code cannot sign in from Box.',
      detail: 'The installed agent does not offer the sign-in handshake Box needs.',
    });
    return;
  }

  let manualUrl;
  try {
    ({ manualUrl } = await session.claudeAuthenticate(true));
  } catch (error) {
    emit({ type: 'auth_failed', message: 'Could not start the sign-in.', detail: clip(String(error?.message ?? error), 1024) });
    return;
  }
  if (!manualUrl) {
    emit({ type: 'auth_failed', message: 'Claude Code did not offer a sign-in link.' });
    return;
  }
  emit({ type: 'auth_url', url: manualUrl });

  const pasted = await nextAuthCode();
  if (pasted == null) {
    emit({ type: 'auth_failed', message: 'The sign-in was cancelled.' });
    return;
  }

  // What the browser hands back is `code#state`, and the callback takes the two separately. A code
  // pasted without its state is the common half-copy, and it is worth naming rather than sending
  // a request that will fail obscurely.
  const separator = pasted.trim().indexOf('#');
  if (separator <= 0) {
    emit({
      type: 'auth_failed',
      message: 'That code looks incomplete.',
      detail: 'Copy the whole code from the browser, including the part after the # sign.',
    });
    return;
  }
  const code = pasted.trim().slice(0, separator);
  const state = pasted.trim().slice(separator + 1);

  try {
    const result = await session.claudeOAuthCallback(code, state);
    const account = result?.account ?? {};
    emit({
      type: 'auth_completed',
      account: {
        email: account.email ?? null,
        organization: account.organization ?? null,
        subscription: account.subscriptionType ?? null,
      },
    });
  } catch (error) {
    // The message here describes a rejected authorisation code, not a credential, so it is safe to
    // pass along — and it is the only thing that can tell an expired code from a mistyped one.
    emit({
      type: 'auth_failed',
      message: 'Claude did not accept that code.',
      detail: clip(String(error?.message ?? error), 1024),
    });
  }
}

async function main() {
  const cwd = process.env.BOX_SESSION_CWD || '/workspace';

  // The credential is read here and handed straight to the SDK. It is never emitted, never logged,
  // and never placed in an argv the process list could show.
  // Signing in is the one job that runs with no credential by definition, so it skips the whole
  // credential-resolution preamble below rather than reporting a missing key as a problem.
  const authMode = process.argv.includes('--auth');

  const credentialPath = authMode ? null : process.env.BOX_CREDENTIAL_FILE;
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

  // An env credential is not the only way to be signed in, and this is deliberately not a gate.
  //
  // Box's sign-in runs Claude Code's own `auth login`, which writes an OAuth profile into the
  // guest's config directory — no API key exists anywhere in that flow. Refusing to start without
  // one would reject the *supported* way to sign in. The SDK has its own resolution order and knows
  // about profiles this file does not, so the honest thing is to let it try and report what it
  // actually says. Box's sign-in screen is the real gate; this is a backstop, and a permissive
  // backstop that occasionally attempts a doomed query beats one that refuses a valid credential.
  if (!authMode && !hasSomeCredential()) {
    diagnostic('no credential found up front; letting the SDK resolve');
  }

  const query = await loadSdk();
  if (!query) return;

  const reader = createInterface({ input: process.stdin });
  reader.on('line', handleCommand);
  reader.on('close', () => {
    inputClosed = true;
    if (promptWaiter) { const resolve = promptWaiter; promptWaiter = null; resolve(null); }
    if (authCodeWaiter) { const resolve = authCodeWaiter; authCodeWaiter = null; resolve(null); }
  });

  if (authMode) {
    await runAuth(query, cwd);
    // Nothing follows a sign-in. The query was opened only to carry the handshake and would
    // otherwise sit on its never-ending prompt stream forever.
    await flushed();
    process.exit(0);
  }

  emit({ type: 'session_started', cwd, harness: 'claude-code' });

  activeQuery = query({
    prompt: prompts(),
    options: {
      cwd,
      // Kept even under a mode that never calls it: the SDK only reaches `canUseTool` when the
      // permission flow falls through to a prompt, so this is the ask path rather than a gate, and
      // dropping it when the mode is permissive would mean rebuilding the query to get it back.
      canUseTool,
      permissionMode,
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
          } else if (message.subtype === 'task_started' || message.subtype === 'task_progress') {
            // The only place the two ids for one sub-agent appear together: the tool_use id its
            // own messages carry, and the task id `stopTask` takes. Without this pairing a sub-agent
            // can be watched but not stopped.
            if (message.tool_use_id && message.task_id) {
              subAgents.set(message.tool_use_id, message.task_id);
            }
            if (message.subtype === 'task_progress') {
              emit({ type: 'activity', activity: { kind: 'working', label: message.description } });
            }
          }
          break;
        case 'assistant':
          translateAssistant(message);
          break;
        case 'user':
          translateToolResults(message);
          break;
        case 'tool_progress':
          emit({
            type: 'tool_progress',
            callId: message.tool_use_id,
            chunk: '',
            ...attribution(message),
          });
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
    const raw = String(error?.message ?? error);
    // An auth failure is the one error whose text is never repeated. It can quote the key it
    // rejected, and the event stream and logcat are exactly where a credential must never land —
    // so this branch says what to do about it and discards the original entirely.
    if (AUTH_FAILURE.test(raw)) {
      emit({
        type: 'error',
        message: 'Box is not signed in yet.',
        detail: 'Sign in to Claude in Box, then send this again.',
        recoverable: false,
      });
      emit({ type: 'session_ended', outcome: { status: 'failed', message: 'Not signed in' } });
      return;
    }
    emit({
      type: 'error',
      message: 'The agent stopped unexpectedly.',
      detail: clip(raw, 2048),
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

export { describeTool, describeAsk, editPatch, createPatch, translateAssistant, translateToolResults };

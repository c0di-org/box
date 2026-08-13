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
import { existsSync, readFileSync, realpathSync, statSync } from 'node:fs';
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

/**
 * Turns waiting to be fed into the SDK, and whoever is waiting for the next one.
 *
 * A turn is `{ text, attachments }` rather than a bare string, because what the person said and
 * what they showed arrive together and have to stay together — the attachment belongs to the turn
 * it was sent with, not to whichever turn happens to be running when the file lands.
 */
const promptQueue = [];
let promptWaiter = null;
let inputClosed = false;

function pushPrompt(turn) {
  if (promptWaiter) {
    const resolve = promptWaiter;
    promptWaiter = null;
    resolve(turn);
  } else {
    promptQueue.push(turn);
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

/**
 * What the person is reading this on, as last reported by the app. Null until it says.
 *
 * Deliberately not a device: the app derives it from its own window, because a fold changes class
 * mid-process and a DeX window is resized by dragging a corner. An agent holds an answer for a
 * whole session, so a device type told once would go stale in a way a layout never does -- which
 * is why this arrives again on every change and is re-stated to the model whenever it differs.
 */
const VIEWPORT_LAYOUTS = new Set(['compact', 'wide']);
let viewport = null;
let viewportTold = null;

/** Prefixed to the next prompt when it has changed. Never emitted: a resize is not conversation. */
function viewportNote(view) {
  const where = view.layout === 'wide'
    ? `a wide window, ${view.widthDp}dp across`
    : `a compact window, ${view.widthDp}dp across`;
  const typing = view.hardwareKeyboard
    ? 'with a hardware keyboard'
    : "typing on the phone's on-screen keyboard";
  return `[box] The person is reading you on ${where}, ${typing}.`;
}

/**
 * The one directory inside the shared folder that files from the phone land in.
 *
 * This is the wire contract and it is fixed: it is the prefix the app sends, the prefix every
 * attachment is checked against, and the path the agent is told. It does not move.
 *
 * [INBOX_ON_DISK] is where this process looks for those files, which is the same place on a
 * device and somewhere writable under test — off a device there is no `/workspace` to create.
 * Keeping the two apart matters more than it looks: it means a test exercises the real paths the
 * app puts on the wire, rather than proving the harness accepts whatever it is pointed at.
 */
const INBOX = '/workspace/shared/inbox/';
const INBOX_ON_DISK = process.env.BOX_INBOX ?? INBOX;

/** Where to look for something the agent will be told is at [guestPath]. */
const onDisk = (guestPath) => INBOX_ON_DISK + guestPath.slice(INBOX.length);

/**
 * How long to wait for a file the app says it has sent.
 *
 * It is already written on the phone by the time the prompt is sent; what is being waited for is
 * the copy into this box, which is driven by an inotify watch on the other side and normally takes
 * about a second. The ceiling is generous because the alternative failure is the expensive one:
 * an agent that looked too early tells the user it cannot see the picture they are looking at.
 */
const ATTACHMENT_WAIT_MS = Number(process.env.BOX_ATTACHMENT_WAIT_MS ?? 30_000);

/**
 * What the app said it attached, keeping only what this harness is willing to act on.
 *
 * The path is checked rather than trusted. It arrives over a pipe from the app, which is not a
 * hostile party — but "read whatever path you are sent" is a capability worth not having, and
 * confining it to the inbox costs one comparison. Anything else is dropped with a diagnostic.
 */
function readAttachments(value) {
  if (!Array.isArray(value)) return [];
  const kept = [];
  for (const item of value) {
    const guestPath = String(item?.guestPath ?? '');
    if (!guestPath.startsWith(INBOX) || guestPath.slice(INBOX.length).includes('/')) {
      diagnostic(`ignoring an attachment outside the inbox: ${guestPath}`);
      continue;
    }
    kept.push({
      guestPath,
      name: String(item?.name ?? guestPath.slice(INBOX.length)),
      mimeType: String(item?.mimeType ?? 'application/octet-stream'),
      bytes: Number.isFinite(Number(item?.bytes)) ? Number(item.bytes) : 0,
    });
  }
  return kept;
}

/** Resolves once every file is on this side, or once waiting has stopped being worth it. */
async function awaitAttachments(attachments) {
  const deadline = Date.now() + ATTACHMENT_WAIT_MS;
  const arrived = [];
  const missing = [];
  for (const attachment of attachments) {
    const path = onDisk(attachment.guestPath);
    let here = false;
    while (true) {
      // Size as well as existence: the copy is not atomic, so a file can be visible while it is
      // still being written, and an agent reading half a screenshot is worse than one waiting.
      here = existsSync(path) && settled(path, attachment.bytes);
      if (here || Date.now() >= deadline) break;
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    (here ? arrived : missing).push(attachment);
  }
  return { arrived, missing };
}

/**
 * True when the whole file is here, not just the start of it.
 *
 * The app sends the size it actually wrote, so this is a real completeness check rather than a
 * guess. Zero means it did not say, which an older app or a stream of unknown length can both
 * produce, and there is nothing better to do then than believe the file exists.
 */
function settled(path, expected) {
  let size;
  try { size = statSync(path).size; } catch { return false; }
  return expected > 0 ? size >= expected : true;
}

/** What the model is told about the files, in the turn they came with. */
function attachmentNote({ arrived, missing }) {
  const lines = [];
  if (arrived.length > 0) {
    lines.push(arrived.length === 1
      ? '[box] They attached a file, which is now at:'
      : `[box] They attached ${arrived.length} files, which are now at:`);
    for (const one of arrived) lines.push(`  ${one.guestPath}  (${one.name}, ${one.mimeType})`);
  }
  if (missing.length > 0) {
    lines.push('[box] These were attached but have not reached this box; say so rather than guessing:');
    for (const one of missing) lines.push(`  ${one.name}`);
  }
  return lines.join('\n');
}

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
      const attachments = readAttachments(command.attachments);
      // Echoed into the log so the transcript has one source of truth in one order. The app could
      // record what the user typed itself, but then a replay would have to interleave two logs and
      // guess where each turn belonged. The attachments ride in that echo for the same reason: the
      // thumbnail on a restored transcript is drawn from this line, not from anything the app kept.
      emit(attachments.length > 0 ? { type: 'user_message', text, attachments } : { type: 'user_message', text });
      pushPrompt({ text, attachments });
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
    case 'connect_result': {
      const resolve = pendingConnects.get(command.requestId);
      if (!resolve) {
        diagnostic(`connect result for unknown request ${command.requestId}`);
        return;
      }
      pendingConnects.delete(command.requestId);
      // Deliberately not the credential: this says whether it worked and who it worked as, which
      // is everything the agent needs and nothing it should be holding.
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
    case 'viewport': {
      const layout = String(command.layout ?? '');
      const widthDp = Number(command.widthDp);
      if (!VIEWPORT_LAYOUTS.has(layout) || !Number.isFinite(widthDp) || widthDp <= 0) {
        diagnostic(`ignoring an unreadable viewport: ${line}`);
        return;
      }
      viewport = {
        layout,
        widthDp: Math.round(widthDp),
        hardwareKeyboard: command.hardwareKeyboard === true,
      };
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
    case 'AskUserQuestion':
      // A card, not silence, and deliberately so. Under `bypassPermissions` nothing reaches the
      // permission flow, so no sheet is drawn and no answer is collected — and a turn where the
      // agent asked, was never shown to anyone, and was told nobody answered has to leave *some*
      // record of what it wanted. This is that record.
      return {
        kind: 'generic',
        name: 'Asked you',
        arguments: askedQuestions(input).map((question) => [
          question.header || 'Question',
          question.text,
        ]),
      };
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
          .map(([key, value]) => [key, clip(readable(value), 512)]),
      };
  }
}

/**
 * The tool input an answered question produces, or null when there is nothing to answer.
 *
 * `AskUserQuestion` is answered by handing its own input back with the `answers` field filled in.
 * The SDK's tool schema describes that field, in as many words, as "collected by the permission
 * component", and `PermissionResult.updatedInput` is how a permission component hands anything
 * back. So an answer is not a second round trip running alongside the permission one — it *is* the
 * permission result, and the sheet Box already has is the component in question.
 *
 * Only keys naming a question this call actually asked survive. The app is the half of the pair
 * that can be older or newer than the guest image, and an answer keyed to a question nobody asked
 * would otherwise reach the model looking exactly like one somebody did.
 */
function answeredInput(toolName, input, answers) {
  if (toolName !== 'AskUserQuestion') return null;
  if (!answers || typeof answers !== 'object') return null;
  const asked = new Set(askedQuestions(input).map((question) => question.text));
  const kept = Object.entries(answers).filter(
    ([question, answer]) => asked.has(question) && typeof answer === 'string' && answer !== '',
  );
  if (kept.length === 0) return null;
  return { ...input, answers: Object.fromEntries(kept) };
}

/**
 * The questions out of an `AskUserQuestion` input, in the shape the sheet draws.
 *
 * Renamed on the way past — `question` becomes `text` — because the app's model already has a
 * `Question` and a field called `question` inside it reads like a stutter. Options with no label
 * are dropped and so is a question left with none: an option nobody can tell apart from another is
 * not a choice, and a question with no answers is a dead end on a sheet whose only job is to be
 * answerable.
 */
function askedQuestions(input) {
  return (Array.isArray(input.questions) ? input.questions : [])
    .map((question) => ({
      text: clip(String(question?.question ?? ''), 1024),
      header: clip(String(question?.header ?? ''), 64),
      multiSelect: question?.multiSelect === true,
      options: (Array.isArray(question?.options) ? question.options : [])
        .map((option) => ({
          label: clip(String(option?.label ?? ''), 256),
          description: option?.description ? clip(String(option.description), 512) : null,
        }))
        .filter((option) => option.label !== ''),
    }))
    .filter((question) => question.text !== '' && question.options.length > 0);
}

/**
 * A value written out for a key/value card.
 *
 * `ToolCall.Generic` promises in as many words to render "a labelled key/value card, never raw
 * JSON", and that promise was kept only for arguments that happened to be strings: anything
 * structured went through `JSON.stringify` and landed on the card as punctuation. Every unmodelled
 * tool taking a structured argument hit it.
 *
 * So structure is flattened into prose instead. One level down, an object becomes its `key: value`
 * pairs and a list becomes its items; below that a nested value is named by its shape rather than
 * spelled out, because a card is a glance and the tool's own output is where detail belongs.
 */
function readable(value, depth = 0) {
  if (value === null || value === undefined) return '';
  if (Array.isArray(value)) {
    if (depth > 1) return value.length === 1 ? '1 item' : `${value.length} items`;
    return value.map((item) => readable(item, depth + 1)).filter(Boolean).join('; ');
  }
  if (typeof value === 'object') {
    if (depth > 1) return Object.keys(value).join(', ');
    return Object.entries(value)
      .map(([key, nested]) => [key, readable(nested, depth + 1)])
      .filter(([, text]) => text !== '')
      .map(([key, text]) => `${key}: ${text}`)
      .join(', ');
  }
  return String(value);
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
    case 'AskUserQuestion':
      // Not a risk to weigh — a question to answer. It arrives here because the answer *is* the
      // permission result: the tool's own input carries an `answers` field the SDK documents as
      // "collected by the permission component", and the host fills it in by returning an
      // `updatedInput`. So the sheet that exists to ask "may it?" is the same sheet that has to
      // ask "which?", and this is the ask that tells it so.
      return {
        kind: 'question',
        questions: askedQuestions(input),
        // Never. "Always allow questions" would answer future questions nobody has read, which is
        // the one thing a question must not do.
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
          clip(readable(value), 512),
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
      const answered = allowed ? answeredInput(toolName, input, command.answers) : null;
      emit({
        // An answered question is not a granted permission, and calling it one puts the wrong
        // sentence in the transcript. The distinction is drawn on the way out rather than on the
        // way in: the app sends a plain `allow` with the answers attached, so a guest image that
        // predates questions still *allows* the call instead of failing it, and only a harness
        // that actually applied them says they were applied.
        type: 'permission_resolved',
        requestId,
        decision: answered ? 'answer' : command.decision,
        ...(answered ? { answers: answered.answers } : {}),
      });
      if (!allowed) {
        resolve({
          behavior: 'deny',
          message: 'The person using Box declined this.',
        });
        return;
      }
      resolve({
        behavior: 'allow',
        // The answer, handed back as the tool's own input. This is the whole of the question round
        // trip: no second channel, no new command, just the field the tool was always going to
        // read, filled in by the only part of Box that ever spoke to a person.
        ...(answered ? { updatedInput: answered } : {}),
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
 * Calls whose result must not become a tool card either, because their start never did.
 *
 * `mcp__box__show` and `TodoWrite`: one is an artifact row, the other a checklist, and both say
 * what happened somewhere other than a tool card. The set is keyed by tool_use id rather than
 * being a name test on the way out, because a `tool_result` carries no name — only the id of the
 * call it answers. Dropping only the start is not enough: the app builds a placeholder card out
 * of a `tool_finished` whose beginning it never saw, which is the stray "Tool" row.
 */
const silentCalls = new Set();

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
      if (block.name === SHOW_TOOL || block.name === CONNECT_TOOL) {
        // No tool card. The artifact row and the connect card *are* what happened, and a card
        // beside either one reading "showed you the thing" is the same sentence twice.
        silentCalls.add(block.id);
        continue;
      }
      if (block.name === 'TodoWrite') {
        // No tool card either: the plan block is where a checklist update shows up.
        silentCalls.add(block.id);
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
    if (silentCalls.delete(block.tool_use_id)) continue;
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

// ---------------------------------------------------------------- showing things

/**
 * The workspace disk, and where this process looks for it.
 *
 * The same split as [INBOX], for the same reason: `/workspace` is the path the agent names, the
 * path that goes on the wire, and the path the app reads back — while [WORKSPACE_ON_DISK] is
 * where this process goes looking, which off a device is a temporary directory because there is
 * no `/workspace` to create. Keeping them apart means a test exercises the paths a real device
 * would carry rather than proving the harness accepts whatever it is pointed at.
 */
const WORKSPACE = '/workspace/';
const WORKSPACE_ON_DISK = process.env.BOX_WORKSPACE ?? WORKSPACE;

/**
 * The one place inside the workspace an artifact may never point at.
 *
 * `/workspace/.config` is where guest/agent-conventions.md puts this box's credentials — the
 * GitHub token, and whatever else it is signed in to. Named explicitly rather than caught by some
 * rule about dotfiles, because `/workspace/src/box/.github/workflows/ci.yml` is a perfectly
 * reasonable thing to show someone and a blanket rule would refuse it.
 */
const CREDENTIALS = WORKSPACE + '.config/';

/** Extension to media type. Decides the icon on the row, and nothing else. */
const MEDIA_TYPES = {
  css: 'text/css', csv: 'text/csv', diff: 'text/x-diff', gif: 'image/gif', htm: 'text/html',
  html: 'text/html', jpeg: 'image/jpeg', jpg: 'image/jpeg', js: 'text/javascript',
  json: 'application/json', log: 'text/plain', md: 'text/markdown', patch: 'text/x-diff',
  pdf: 'application/pdf', png: 'image/png', py: 'text/x-python', svg: 'image/svg+xml',
  toml: 'text/plain', ts: 'text/typescript', txt: 'text/plain', webp: 'image/webp',
  xml: 'text/xml', yaml: 'text/yaml', yml: 'text/yaml',
};

function mediaType(name) {
  const dot = name.lastIndexOf('.');
  // Not text/plain: the app reads a document artifact as text, and claiming a type for something
  // this list has never heard of would be a guess the file viewer then has to live with.
  return dot > 0 ? MEDIA_TYPES[name.slice(dot + 1).toLowerCase()] ?? 'application/octet-stream'
    : 'application/octet-stream';
}

/**
 * One file the agent may put in front of the user, or the reason it may not.
 *
 * An artifact is a *button*, and this is the function that decides what a button the agent labels
 * itself is allowed to open. That is why the bound is tighter than `readAttachments`' rather than
 * looser: the app draws the agent's `name`, so a row reading "report.md" that opens the GitHub
 * token is a button whose target the person cannot see before they tap it. Four rules, each
 * earning its place:
 *
 *   - **Under `/workspace`.** The workspace disk is where the agent's own work lives, which is
 *     what "look at this" is about. The system disk holds the image — identical on every device,
 *     nothing the agent made, and replaced whole on the next update.
 *   - **Resolved, not string-matched.** `realpathSync` first, because a symlink at
 *     `/workspace/report.md` pointing into `/etc` passes every prefix test ever written until the
 *     link is followed. It also settles existence: a file that is not there cannot be shown.
 *   - **Never the credential directory,** checked after resolution so a link into it is caught too.
 *   - **A regular file.** A directory or a device node is not something the file viewer can draw.
 *
 * No size check. The contract puts that in one place on purpose — the panel reads the path through
 * the same reader the Files panel uses, so "too big" already has exactly one answer and a second
 * one here would be a second thing to keep right.
 */
function showable(rawPath) {
  const given = String(rawPath ?? '').trim();
  if (!given) {
    return { refusal: 'No path was given.' };
  }
  if (!given.startsWith('/')) {
    return { refusal: `'${given}' is relative. Give the absolute path; this does not guess what a relative one is relative to.` };
  }
  if (!given.startsWith(WORKSPACE)) {
    return {
      refusal: `Only files under ${WORKSPACE} can be shown, and '${given}' is not one. Everything else here is on the system disk, which is replaced wholesale by the next update — copy it into ${WORKSPACE} and show that.`,
    };
  }

  let root;
  try {
    root = realpathSync(WORKSPACE_ON_DISK);
  } catch {
    return { refusal: `This box has no ${WORKSPACE}.` };
  }
  const prefix = root.endsWith('/') ? root : root + '/';

  let resolved;
  try {
    resolved = realpathSync(WORKSPACE_ON_DISK + given.slice(WORKSPACE.length));
  } catch {
    return {
      refusal: `Nothing is at ${given}. Finish writing the file before showing it — a button that opens nothing is worse than no button.`,
    };
  }

  let info;
  try {
    info = statSync(resolved);
  } catch {
    return { refusal: `${given} cannot be read.` };
  }
  if (info.isDirectory()) {
    return { refusal: `${given} is a folder. Show one file, or write the listing to a file and show that.` };
  }
  if (!info.isFile()) {
    return { refusal: `${given} is not a regular file.` };
  }

  if (!resolved.startsWith(prefix)) {
    return { refusal: `${given} leads out of ${WORKSPACE}, so it is not the agent's own work and is not shown.` };
  }
  const guestPath = WORKSPACE + resolved.slice(prefix.length);
  if (guestPath.startsWith(CREDENTIALS)) {
    return { refusal: `${CREDENTIALS} holds this box's credentials and is never shown to anyone, including its owner.` };
  }

  const name = guestPath.slice(guestPath.lastIndexOf('/') + 1);
  return { artifact: { kind: 'document', guestPath, name, mimeType: mediaType(name) } };
}

/**
 * Where to read the kernel's TCP tables. A single path, or several separated by `:`.
 *
 * Overridable for the same reason [INBOX_ON_DISK] is: these tests do not run on Linux, and a
 * fixture is the only way to pin the refusal as well as the pass.
 */
const PROC_TCP = process.env.BOX_PROC_TCP ?? '/proc/net/tcp:/proc/net/tcp6';

/**
 * Whether anything in this box is listening on [port].
 *
 * Checked because a preview is a button too. Offering one for a port with no server behind it
 * hands the person a WebView full of connection error, and the agent that did it does not find
 * out — so the honest place to fail is here, in a tool result the agent can read and act on.
 *
 * Unreadable tables mean an unknowable answer, and the call then is the one `settled` makes about
 * a size it was not told: believe the agent. Refusing on ignorance would break this everywhere
 * `/proc` is not Linux's, which includes every machine these tests run on.
 */
function listening(port) {
  const wanted = ':' + port.toString(16).toUpperCase().padStart(4, '0');
  let read = false;
  for (const file of PROC_TCP.split(':')) {
    let table;
    try {
      table = readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    read = true;
    for (const line of table.split('\n').slice(1)) {
      // sl  local_address rem_address st … — `st` is the connection state and 0A is LISTEN.
      const columns = line.trim().split(/\s+/);
      if (columns.length >= 4 && columns[1].endsWith(wanted) && columns[3] === '0A') return true;
    }
  }
  return !read;
}

/**
 * `mcp__box__show` — the agent-facing half of the artifact contract.
 *
 * Everything else about an artifact was already built: the wire, the parser, the row, the three
 * things a tap can open. What was missing was any way for an agent to start one, so on a real
 * device the whole surface was reachable only from the in-process fake.
 *
 * An in-process MCP tool rather than a line the agent is told to `echo`, because the emitting has
 * to be the harness's own: a path is refused before it becomes a button, and the agent is told
 * *why* in a tool result it can act on. Told to print its own protocol lines, an agent would be
 * writing unvalidated events into the log the app trusts, and would have no way to learn it got
 * one wrong.
 *
 * One tool for all three kinds, because to the person they are one thing — a button that appears
 * in the conversation — and splitting them into three would make the model choose a mechanism
 * before it has decided what it wants to say.
 */
function showTool(tool, z) {
  return tool(
    'show',
    [
      'Put something in front of the person you are talking to: a file you wrote, a port you are',
      'serving on, or the desktop you are working beside.',
      '',
      'They get a button in the conversation and tap it if they want it. It never interrupts them,',
      'and it is not a substitute for saying what the thing is — offer it alongside your answer,',
      'not instead of one.',
      '',
      'Give exactly one of path, port or desktop.',
    ].join('\n'),
    {
      path: z.string().optional()
        .describe('An absolute path under /workspace to a file you have finished writing. It opens in a text viewer, so it is for things that read as text — not an image.'),
      port: z.number().int().optional()
        .describe('A port something in this box is already listening on. Box forwards it to the phone and loads it in a browser panel.'),
      desktop: z.boolean().optional()
        .describe('True to offer the live desktop — worth it once you have something drawn on it.'),
    },
    async (args) => {
      const asked = [
        args.path != null ? 'path' : null,
        args.port != null ? 'port' : null,
        args.desktop ? 'desktop' : null,
      ].filter(Boolean);
      if (asked.length !== 1) {
        return refused(asked.length === 0
          ? 'Give exactly one of path, port or desktop.'
          : `That asked for ${asked.join(' and ')} at once. One thing per call, so the person can tell what each button is.`);
      }

      if (args.path != null) {
        const { artifact, refusal } = showable(args.path);
        if (refusal) return refused(refusal);
        emit({ type: 'artifact', ...artifact });
        return shown(`${artifact.name} is now a button in the conversation.`);
      }

      if (args.port != null) {
        const port = Number(args.port);
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
          return refused(`${args.port} is not a port number.`);
        }
        if (!listening(port)) {
          return refused(`Nothing in this box is listening on ${port}. Start the server, wait for it to bind, then show it.`);
        }
        // The url is the guest's own view of the port and is deliberately not what the WebView
        // loads: opening one asks the runtime to forward it and the panel loads the loopback
        // address it hands back. It is sent because the parser drops a preview without one, and
        // because it is the honest thing to put in the log for a replayed transcript to show.
        emit({ type: 'artifact', kind: 'preview', url: `http://localhost:${port}/`, guestPort: port });
        return shown(`Port ${port} is now a button in the conversation.`);
      }

      emit({ type: 'artifact', kind: 'computer' });
      return shown('The desktop is now a button in the conversation.');
    },
  );
}

/** Said to the model, never to the person: nothing appeared, and here is what to do about it. */
const refused = (why) => ({
  content: [{ type: 'text', text: `Nothing was shown. ${why}` }],
  isError: true,
});

/**
 * Said to the model when a button did appear.
 *
 * The second sentence is the load-bearing one. An offer is not a viewing, and an agent that
 * believes it is writes "as you can see in the diagram" to someone looking at an unpressed button.
 */
const shown = (what) => ({
  content: [{ type: 'text', text: `${what} They may or may not open it, so do not write as though they already have.` }],
});

/** The full name the model calls [showTool] by, and the name the log has to recognise it under. */
const SHOW_TOOL = 'mcp__box__show';

/** The full name of [connectTool], suppressed in the transcript for the same reason as show. */
const CONNECT_TOOL = 'mcp__box__connect';

/** Connection requests the person has not finished yet, keyed by requestId. */
const pendingConnects = new Map();
let nextConnectId = 0;

/**
 * `mcp__box__connect` — the agent asking for an account it does not have.
 *
 * This box can clone a public repository and nothing else until somebody connects GitHub, and the
 * agent is the first to find that out: it is holding a 403 from a `git clone` the person asked
 * for. The question is what it does next, and every answer other than this one is bad. Stopping to
 * say "you need to connect GitHub" ends the turn and loses the thread. Reading a token out of a
 * file — which this box's conventions used to describe — puts a credential in the agent's context
 * and one echo away from a session log kept on disk.
 *
 * So it asks, and *waits*. The SDK pauses a tool call for as long as it takes and does not time
 * out — the same property that makes a permission sheet on a pocketed phone legitimate — so the
 * person can go to GitHub, pick their repositories, and come back to an agent that carries on with
 * the same clone in the same turn.
 *
 * A tool rather than a printed line, on the `show` precedent: the harness emits the event, so the
 * app is never parsing an agent's prose for an intent, and the agent is told what happened in a
 * result it can act on.
 */
function connectTool(tool, z) {
  return tool(
    'connect',
    [
      'Ask the person to connect an account this box does not have yet. Use it when work you were',
      'asked to do needs GitHub — a private clone, a push, a pull request — rather than stopping to',
      'explain that you cannot.',
      '',
      'It waits while they do it, so call it and carry on with what you were doing when it returns.',
      'You never see the credential: git and gh are simply authenticated afterwards.',
    ].join('\n'),
    {
      service: z.enum(['github']).describe('The account to connect. GitHub is the only one so far.'),
      reason: z.string().optional()
        .describe('Half a line on what you need it for, in their words — "to clone garfbargle/box". It is shown above the button, so it is the only explanation they get before deciding.'),
    },
    async (args) => {
      const requestId = `connect-${++nextConnectId}`;
      const settled = new Promise((resolve) => pendingConnects.set(requestId, resolve));
      emit({
        type: 'connect_requested',
        requestId,
        service: args.service ?? 'github',
        // Clipped rather than refused: this is a caption, and a long one is a formatting problem
        // rather than a reason to fail a request the person is waiting on.
        reason: clip(String(args.reason ?? ''), 160) || null,
      });
      const outcome = await settled;

      if (outcome?.connected) {
        const account = outcome.login ? `as ${outcome.login}` : '';
        const reach = typeof outcome.repositories === 'number'
          ? ` They chose ${outcome.repositories} repositor${outcome.repositories === 1 ? 'y' : 'ies'} for this box, so anything outside that set will still be refused.`
          : '';
        return {
          content: [{
            type: 'text',
            text: `GitHub is connected ${account}. git and gh are authenticated here and your commits are attributed correctly — do not look for a token, and never put one in a command.${reach} Carry on with what you were doing.`,
          }],
        };
      }
      // Not an error. Declining is an answer, and one the agent should absorb and work around
      // rather than retry — an isError result reads as something that went wrong and invites one.
      return {
        content: [{
          type: 'text',
          text: outcome?.message
            ?? 'They did not connect GitHub. Do not ask again in this turn; say what you can still do without it, and let them come back to it.',
        }],
      };
    },
  );
}

/**
 * The `box` server, or null if this guest's SDK cannot host one.
 *
 * Null rather than a throw, on the `setPermissionMode` and `stopTask` precedent: the guest image
 * carries whichever SDK it was built with, Box cannot reach in to correct it, and a session that
 * runs without a way to show things is enormously better than one that will not start. The
 * absence is a stderr line, and the agent simply never sees the tool.
 */
async function boxServer(sdk) {
  if (typeof sdk.createSdkMcpServer !== 'function' || typeof sdk.tool !== 'function') {
    diagnostic('this SDK cannot host in-process tools, so the agent has no way to show anything');
    return null;
  }
  let z;
  try {
    ({ z } = await import('zod'));
  } catch (error) {
    // A declared dependency, so this is a broken install rather than a version difference.
    diagnostic(`zod is missing, so the agent has no way to show anything: ${error?.message ?? error}`);
    return null;
  }
  try {
    return sdk.createSdkMcpServer({
      name: 'box',
      version: '1.0.0',
      instructions: 'Box is a chat app on an Android phone; this agent runs in a Linux VM inside it. Use show to put a file, a served port, or the desktop in front of the person as a button in the conversation, and connect to ask them for an account this box does not have yet.',
      // Kept out of tool search. It is one tool, and the alternative is the model spending a round
      // trip on a fully emulated phone to discover the thing it is being told about in its prompt.
      alwaysLoad: true,
      tools: [showTool(sdk.tool, z), connectTool(sdk.tool, z)],
    });
  } catch (error) {
    // The exports being present is not the same as their signatures being the ones this file was
    // written against. Everything above this point is a check; this is the one that cannot be
    // made in advance, and letting it escape would take the whole session down over a tool.
    diagnostic(`this SDK would not host box's tools: ${error?.message ?? error}`);
    return null;
  }
}

// ---------------------------------------------------------------- main

async function* prompts() {
  while (true) {
    const turn = await nextPrompt();
    if (turn === null) return;
    const { text, attachments = [] } = turn;
    // Blocking here is the point: this generator *is* the model's queue, so waiting for the file
    // holds the turn rather than the whole harness, and everything else — a decision, an
    // interrupt, a viewport — keeps being read off stdin while it waits.
    const files = attachments.length > 0 ? attachmentNote(await awaitAttachments(attachments)) : '';
    // The viewport rides with the turn rather than arriving as one of its own. A turn of its own
    // would cost a model round trip on a machine that is fully emulated, and would do it every
    // time someone rotated the phone. Only sent when it differs from what was last said, so a
    // session that never changes shape mentions it exactly once.
    const note = viewport && JSON.stringify(viewport) !== viewportTold ? viewportNote(viewport) : null;
    if (note) viewportTold = JSON.stringify(viewport);
    yield {
      type: 'user',
      // The event log already carries the user's own words, emitted by `prompt` above; this is the
      // only copy the notes appear in, so nothing draws them in the transcript as something they said.
      message: { role: 'user', content: [note, files, text].filter(Boolean).join('\n\n') },
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
 *
 * The whole module rather than `query` alone, because `createSdkMcpServer` and `tool` are read off
 * it too and their absence has to be survivable separately — see `boxServer`.
 */
async function loadSdk() {
  try {
    return await import('@anthropic-ai/claude-agent-sdk');
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

  const sdk = await loadSdk();
  if (!sdk) return;
  const query = sdk.query;

  const reader = createInterface({ input: process.stdin });
  reader.on('line', handleCommand);
  reader.on('close', () => {
    inputClosed = true;
    if (promptWaiter) { const resolve = promptWaiter; promptWaiter = null; resolve(null); }
    if (authCodeWaiter) { const resolve = authCodeWaiter; authCodeWaiter = null; resolve(null); }
    // A connect request outlives the sheet by design, so the one thing that must end its wait is
    // Box itself going away. Without this the model sits on a tool call nothing can ever answer.
    for (const [requestId, resolve] of pendingConnects) resolve({ connected: false, message: 'Box closed before they answered.' });
    pendingConnects.clear();
  });

  if (authMode) {
    await runAuth(query, cwd);
    // Nothing follows a sign-in. The query was opened only to carry the handshake and would
    // otherwise sit on its never-ending prompt stream forever.
    await flushed();
    process.exit(0);
  }

  emit({ type: 'session_started', cwd, harness: 'claude-code' });

  const box = await boxServer(sdk);

  activeQuery = query({
    prompt: prompts(),
    options: {
      cwd,
      // Kept even under a mode that never calls it: the SDK only reaches `canUseTool` when the
      // permission flow falls through to a prompt, so this is the ask path rather than a gate, and
      // dropping it when the mode is permissive would mean rebuilding the query to get it back.
      canUseTool,
      permissionMode,
      // A question reaches a person whatever the mode is set to.
      //
      // `canUseTool` is the ask path, and under `bypassPermissions` nothing falls through to it —
      // so a question asked in that mode would be put to nobody and then reported to the agent as
      // one the user declined to answer. Pinning this one tool to `ask` puts it back on the only
      // path that can reach a sheet. Between a setting turned on to stop being interrupted and the
      // one tool whose entire purpose is to interrupt, the tool wins: it is the more specific
      // instruction, and it is the one the agent chose deliberately.
      hooks: {
        PreToolUse: [
          {
            matcher: 'AskUserQuestion',
            hooks: [
              async () => ({
                continue: true,
                hookSpecificOutput: {
                  hookEventName: 'PreToolUse',
                  permissionDecision: 'ask',
                  permissionDecisionReason: 'Only the person using Box can answer this.',
                },
              }),
            ],
          },
        ],
      },
      includePartialMessages: false,
      // Showing something is the one tool that is never asked about. A sheet reading "allow the
      // agent to show you a file?" has one honest answer, and asking is worse than not: the
      // artifact is *itself* a button nobody has to press, so the consent is the tap, and a
      // permission prompt in front of it makes the person answer the same question twice.
      // Neither is ever asked about. A sheet reading "allow the agent to ask you to connect
      // GitHub?" asks the same question the connect card is about to ask, one screen earlier.
      ...(box ? { mcpServers: { box }, allowedTools: [SHOW_TOOL, CONNECT_TOOL] } : {}),
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

export {
  describeTool,
  describeAsk,
  askedQuestions,
  answeredInput,
  readable,
  editPatch,
  createPatch,
  translateAssistant,
  translateToolResults,
};

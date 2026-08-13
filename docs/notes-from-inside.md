# Notes from inside

Findings that can only be made from **inside a running Box** — by the agent, on the
device, against the image the user is actually holding. Everything here was observed
from the guest at a known image commit, not inferred from reading this repository on a
laptop.

The value of the genre is that some defects are invisible from outside. A tool that
returns a plausible result to the model while showing the user nothing looks healthy in
the source and healthy on screen; it is only wrong from the seat where both ends are
visible at once. This document is written from that seat.

**Observed on image `6206ff49ca6e63c4a6a8c43573493890a4745bfe`, 2026-08-13.**

## How to use this document

Each finding is tagged with what it would take to invalidate it. On a new image, re-run
the **Re-check** line. A finding that no longer reproduces should be moved to the
"Resolved" section with the image commit that fixed it, rather than deleted — the
history of what was once broken is the point.

## What the agent cannot do from in here

Stated up front, because it bounds every claim below.

- **No JDK, no Android SDK, no Docker.** The Box app cannot be built or run from the
  guest. Every claim about Kotlin behaviour is read from source, never executed.
- **Fully emulated ARM64 under QEMU on a phone.** Slow. Reading and reasoning beats
  speculative rebuilds.
- `/usr/src/box` is the baked source at the running commit — authoritative for *what
  shipped*, and replaced wholesale by the next image.
- `ps` is unavailable (no `procps`); `/proc` works. A process grep that looks like
  "nothing is running" is usually `ps: command not found`.

---

## 1. `AskUserQuestion` is inert, and fails silently in the worst direction

**Severity: high.** The agent cannot put a choice in front of the user. Worse, it does
not *know* it cannot: the tool returns a normal-looking result, so the agent believes it
asked and was ignored.

### What the user sees

A tool card titled `AskUserQuestion` showing truncated raw JSON, then a permission line
— *"Allow AskUserQuestion? · You allowed this"* — and then nothing. No options, no
prompt. The user approves *the act of asking* and is then never asked.

### What the agent sees

The tool result `The user did not answer the questions.` — indistinguishable from a user
who saw the question and declined to engage. The agent's reasonable next move is to
apologise for the non-answer or to proceed on an assumption, both of which are wrong.

This is the part that makes it worth a document rather than a bug report. A tool that
*failed* would be fine: the agent would see an error and adapt. This one launders a
missing UI into a factual claim about the user's behaviour.

### Why: two independent halves, both required

**Half A — Box models no question tool.** `app/.../agent/AgentEvent.kt` defines
`sealed interface ToolCall` with exactly seven modelled kinds — `Shell`, `ReadFile`,
`EditFile`, `WriteFile`, `Search`, `Fetch`, and `Task`. Everything else falls to:

```kotlin
/** Anything Box does not model yet. Renders as a labelled key/value card, never raw JSON. */
data class Generic(
    val name: String,
    val arguments: List<Pair<String, String>> = emptyList(),
) : ToolCall
```

`AskUserQuestion` lands here. There is no `ToolCall.Question`, nothing in
`ui/TranscriptItems.kt` that renders selectable options, and no path for a selection to
travel back. Grepping the whole Kotlin tree for `AskUserQuestion` returns nothing — Box
has never heard of it.

**Half B — the guest harness never opts in to dialogs.** This is the half that explains
the *instant* no-answer rather than a hang, and it is not in this repository's Kotlin at
all — it is in `guest/harness/box-claude-harness.mjs` and the SDK it drives
(`@anthropic-ai/claude-agent-sdk` 0.3.226, pinned in `guest/harness/package.json`).

The SDK exposes a dedicated blocking-dialog channel: an `onUserDialog` callback plus a
`supportedDialogKinds` declaration. Its own type definitions are explicit that the
declaration is load-bearing, and that the CLI fails closed without it:

> Dialog kinds this consumer's `onUserDialog` can actually render […] Providing
> `onUserDialog` alone does NOT opt the consumer into receiving dialogs — the CLI only
> emits a dialog kind declared here.
>
> The CLI fails closed on absence: a dialog kind not declared here is never emitted to
> this session — **the flow behind it degrades to its no-dialog behavior**. Omitting the
> option entirely means no dialogs are emitted, even with `onUserDialog` wired.

The harness passes neither option. So the question is never offered to the host at all,
and the flow behind it takes its no-dialog path immediately. That matches the observed
timing exactly: the result comes back at once, not after a wait.

### Confidence, stated honestly

- **Half A is proven.** Read directly from source at the shipped commit.
- **Half B is strongly supported but not proven.** The fail-closed contract is quoted
  from the SDK's own `sdk.d.ts`; the instant no-answer is consistent with it. What has
  *not* been established is the specific `dialogKind` string `AskUserQuestion` uses. The
  CLI's answer-handling strings are visible in the shipped binary (`The user answered:`,
  `Your questions have been answered:`, `Before going idle the user had selected:`,
  alongside `The user did not answer the questions.`), which confirms a real answer path
  exists and has more than one shape — but the emitting call site was not located.

Anyone implementing the fix should pin that string first rather than trust this document
for it.

### The good news: the return channel already exists

`app/.../agent/AgentBackend.kt` already carries a request/response round trip keyed by
request id:

```kotlin
suspend fun resolvePermission(sessionId: String, requestId: String, decision: PermissionDecision)
```

with the guest end in the harness — `emit({ type: 'permission_requested', requestId, ask })`,
a `pendingPermissions` map, and a resolver that settles the promise when the answer
arrives. A question round trip is the same shape with a wider answer type: N options
instead of allow/deny. The plumbing does not need inventing, only widening.

Sketch, deliberately not a patch:

1. Harness declares `supportedDialogKinds` and implements `onUserDialog`, emitting a
   `question_requested` line and awaiting a `question_answered` command — mirroring the
   existing permission pair.
2. `HarnessWire.parse` grows the matching arm; unknown lines already degrade to silence,
   so an older APK against a newer guest stays safe.
3. A `ToolCall.Question` variant, rendered in `TranscriptItems.kt` as tappable options.
4. `AgentBackend.resolveQuestion(sessionId, requestId, answers)` alongside
   `resolvePermission`.

**Interim mitigation, costing nothing:** until the round trip exists, the tool should not
be offered. An agent that knows to ask in prose loses only the buttons. An agent that
believes it asked loses the answer *and* charges the user a permission tap for nothing.

**Re-check:** call `AskUserQuestion` with two options. Fixed when the options render and
the choice returns. Still broken if the result is `The user did not answer the
questions.`

---

## 2. `ToolCall.Generic` renders raw JSON, contradicting its own contract

**Severity: low — cosmetic, but it is a promise the code does not keep.**

The doc comment says "Renders as a labelled key/value card, **never raw JSON**".
Arguments are carried as `List<Pair<String, String>>`, so an argument whose *value* is
JSON is stringified straight onto the card. Observed on screen:

```
questions  [{"question":"Now that the inbound channel works, what's wo…
```

Any unmodelled tool taking a structured argument hits this — `AskUserQuestion` is just
the first one that mattered. Either the comment should be softened or `Generic` should
pretty-print values that parse as JSON.

**Re-check:** invoke any unmodelled tool with an object- or array-valued argument and
look at the card.

---

## 3. The persistence claim is now demonstrated, not asserted

`/workspace` survives an image swap. This had been asserted in the docs and believed on
faith; it is now evidenced.

- A canary file was written to `/workspace` on 2026-08-12 at image `9cbeb134`.
- On 2026-08-13 `/usr/src/box/BUILD-INFO` read `6206ff49` — the system disk was
  genuinely replaced, which is the precondition. Without that, nothing was tested.
- The canary was still present and readable.

Worth keeping as a standing check precisely because it is the kind of guarantee that is
cheap to state and expensive to get wrong.

**Re-check:** compare `BUILD-INFO` against the last recorded commit; if it changed, look
for the canary.

---

## 4. Inbound files work; the other three directions are untested

`(+)` in the chat prompt is confirmed working end to end: a picked JPEG arrived at
`/workspace/shared/inbox/<timestamp>-<name>` and the turn named the path, so a file the
user sends is a file the agent can simply read. This closes the harder half of the
inbound problem — the agent can now be *shown* things.

Still unverified from in here:

- **Share target.** Whether Box appears in the Android share sheet (`ACTION_SEND`).
- **Outbound.** Whether a file the agent leaves in `/workspace/shared` reaches the phone.
  This one matters more than it looks: the guest conventions tell the agent that writing
  to `/workspace/shared` is how you hand someone a file they can keep. If that is not
  true on this device, the agent will confidently hand users files they cannot reach.
- **`/workspace` from a file manager.** Currently requires entering computer mode and
  clicking Files.

**Re-check:** agent writes a file to `/workspace/shared`, session ends, user looks for it
on the phone.

---

## Resolved since the last image

- **No inbound file channel** *(broken at `9cbeb134`, fixed by `6206ff49`)* — the `(+)`
  attach button now delivers files to `/workspace/shared/inbox/`.
- **Agent could not see its own desktop** *(broken at `9cbeb134`, fixed by `6206ff49`)* —
  `scrot` is in the image; `scrot /tmp/screen.png` against `:0` works, so GUI work is no
  longer done blind.

---

## Suggested convention

Re-run this document against each new image and update it in place — including moving
newly-fixed items into **Resolved** with the commit that fixed them. Its worth is not the
snapshot but the diff between images, and a stale copy is worse than none: it invites
trust in checks nobody re-ran.

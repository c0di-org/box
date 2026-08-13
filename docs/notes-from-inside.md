# Notes from inside

Findings that can only be made from **inside a running Box** — by the agent, on the
device, against the image the user is actually holding. Everything in the observation
column here was seen from the guest at a known image commit, not inferred from reading
this repository on a laptop.

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

One distinction the document has to keep making, because getting it wrong is how the
first version of finding 1 went astray: **observed**, **read from source**, and
**reasoned from a contract** are three different strengths of claim. Only the first
needs a device. The other two can be checked from a laptop, and where one has been, this
says so.

## What the agent cannot do from in here

Stated up front, because it bounds every claim below.

- **No JDK, no Android SDK, no Docker.** The Box app cannot be built or run from the
  guest. Every claim about Kotlin behaviour is read from source, never executed.
- **No installed copy of the Agent SDK to read.** The harness's dependencies are not
  vendored into the image, so the guest can see `guest/harness/package.json` — which
  pins the version — but not the package it names. Anything about the SDK's own contract
  therefore has to be checked from somewhere with a network, against that pinned
  version. Finding 1 is the cautionary tale: guessing at it from the outside produced a
  confident, wrong mechanism.
- **Fully emulated ARM64 under QEMU on a phone.** Slow. Reading and reasoning beats
  speculative rebuilds.
- `/usr/src/box` is the baked source at the running commit — authoritative for *what
  shipped*, and replaced wholesale by the next image.
- `ps` is unavailable (no `procps`); `/proc` works. A process grep that looks like
  "nothing is running" is usually `ps: command not found`.

---

## 1. `AskUserQuestion` was inert, and failed silently in the worst direction

**Severity: high. Diagnosed, and fixed — but the fix had not run on a device when this
was written.** Read the last two sub-sections before trusting it.

### What the user saw

A tool card titled `AskUserQuestion` showing truncated raw JSON, then a permission line
— *"Allow AskUserQuestion? · You allowed this"* — and then nothing. No options, no
prompt. The user approved *the act of asking* and was then never asked.

### What the agent saw

The tool result `The user did not answer the questions.` — indistinguishable from a user
who saw the question and declined to engage. The agent's reasonable next move is to
apologise for the non-answer or to proceed on an assumption, both of which are wrong.

This is the part that makes it worth a document rather than a bug report. A tool that
*failed* would be fine: the agent would see an error and adapt. This one laundered a
missing UI into a factual claim about the user's behaviour.

### Why: one mechanism, and it was hiding in plain sight

`AskUserQuestion` is an ordinary tool, with an ordinary input and output typed in the
SDK's `sdk-tools.d.ts`. It does not travel on a separate dialog channel. The answer path
is a single optional field on its **input**:

```ts
export interface AskUserQuestionInput {
  questions: [ /* … */ ];
  /**
   * User answers collected by the permission component
   */
  answers?: { [k: string]: string };
  /* … */
}
```

"Collected by the permission component" is the whole of it. The host is expected to
render the questions *in its permission surface* and return the tool's own input with
`answers` filled in:

```ts
export declare type PermissionResult = {
    behavior: 'allow';
    updatedInput?: Record<string, unknown>;
    /* … */
};
```

Box's `canUseTool` resolved `{ behavior: 'allow' }` with no `updatedInput` at all. So the
tool ran with `answers` undefined and said, accurately from its own point of view, that
the user had not answered.

That also explains the shape of what the user saw, which no other theory did: they were
shown a permission line **because the permission sheet is exactly where the question was
supposed to be**. Nothing was missing from the screen by accident; the sheet was asked
the wrong question and answered it correctly.

### The wrong turn this document took first, recorded on purpose

The first version of this finding blamed `supportedDialogKinds` — an SDK option the
harness genuinely does not pass, whose own type definitions genuinely do say the CLI
fails closed without it. Every quoted fact was accurate. The conclusion was still wrong:
that option governs a *different* family of blocking dialogs, and the only kind its
documentation names is `refusal_fallback_prompt`. Declaring it would have changed
nothing.

The tell was there and was written down as an open question — "the specific `dialogKind`
string was not established". It was not established because there is no such string for
this tool. An unresolved detail at the centre of a mechanism is usually not a detail.

It is left in the record because the failure mode is worth more than the fix: a
plausible mechanism, assembled from true quotations, that sends the next person to
implement a channel that does not exist. The pinned SDK was one `npm pack` away the
whole time.

### What changed

The round trip runs through the permission channel that was already there, because that
*is* the channel:

- The harness describes an `AskUserQuestion` call as a new ask kind, `question`, carrying
  the questions and their options rather than a risk to weigh.
- `HarnessWire` parses it into `PermissionAsk.Questions`; a question with no answerable
  options degrades to the ordinary allow/deny ask rather than drawing a dead end.
- The question is drawn inline on its own transcript card — header chip, options with
  their descriptions, multi-select where asked for, and a free-text "Something else",
  which exists because the tool tells the model not to write an "Other" option on the
  grounds that the host will supply one. "Answer" stays disabled until every question has
  something in it. It briefly lived in the permission sheet, and `BoxApp` now filters
  questions out of `sheetTarget`: a question already stops the work, so the card *is* the
  interruption and a modal over it interrupts the same person twice.
- The answer comes back as `PermissionDecision.Answered`, goes down the wire as a plain
  `allow` **carrying an `answers` field**, and the harness folds it into `updatedInput`.
  A plain allow on the wire is deliberate: a guest image older than this change reads an
  allow it already understands and ignores a field it has never heard of, which loses the
  answer and nothing else. A new decision word would have been read as "not allowed" and
  failed the call outright.
- Answers are dropped unless they key a question that call actually asked, on both sides.
  The app and the guest image are upgraded independently, and an answer to a question
  nobody asked would otherwise reach the model looking exactly like one somebody gave.
- A `PreToolUse` hook pins this one tool to `ask` regardless of permission mode. Without
  it, `bypassPermissions` skips the permission flow entirely — and a question asked in
  that mode would be put to nobody and then reported as one the user declined to answer,
  which is this same bug with a setting in front of it.

### What has *not* been established

- **None of it has run.** No guest image was rebuilt and nothing was executed on a
  device. The Kotlin compiles and its unit tests pass; the harness's node tests pass.
  That is a long way from a person tapping an option on a phone.
- **The `PreToolUse` hook is reasoned from the SDK's types, not observed.** That a hook
  returning `permissionDecision: 'ask'` routes through `canUseTool` under
  `bypassPermissions` follows from the contract and has not been watched happening. If it
  turns out not to, the mode is the one case where a question still reaches nobody — and
  the tool card the harness now draws for `AskUserQuestion` is what keeps that case from
  being silent.
- **The SDK evidence above was read on a laptop**, from `@anthropic-ai/claude-agent-sdk`
  at `0.3.226`, the version `guest/harness/package.json` pins. It is not a guest
  observation, and the guest could not have made it — see the second bullet under "what
  the agent cannot do from in here".

**Re-check:** call `AskUserQuestion` with two options. Fixed when the options render and
the choice comes back in the tool result. Still broken if the result is `The user did not
answer the questions.` Then do it again with the permission mode set to allow everything,
which is the half that rests on the hook.

---

## 2. `ToolCall.Generic` rendered raw JSON, contradicting its own contract

**Severity: low — cosmetic, but it was a promise the code did not keep. Fixed.**

The doc comment says "Renders as a labelled key/value card, **never raw JSON**".
Arguments are carried as `List<Pair<String, String>>`, so an argument whose *value* was
JSON arrived pre-stringified. Observed on screen:

```
questions  [{"question":"Now that the inbound channel works, what's wo…
```

Worth noting where the fault actually was, because the first version of this finding
filed it against the Kotlin: the `JSON.stringify` was in the **harness**, in the guest.
The Kotlin comment was a promise broken a layer upstream, by the half of the pair that
ships in the image. Structure is now written out as words — an object becomes its
`key: value` pairs, a list becomes its items, and anything nested past that is named by
its shape — so any unmodelled tool with a structured argument gets a readable card.

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

Findings 1 and 2 are *not* listed here, and will not be until an image carrying them has
been run on a device and their **Re-check** lines have passed. A fix that
compiles is not a fix that reproduces, and this section is the one place in the document
that is allowed to mean "verified".

---

## Suggested convention

Re-run this document against each new image and update it in place — including moving
newly-fixed items into **Resolved** with the commit that fixed them. Its worth is not the
snapshot but the diff between images, and a stale copy is worse than none: it invites
trust in checks nobody re-ran.

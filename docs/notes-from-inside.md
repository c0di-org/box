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

**Finding 1 re-checked and passed on image `9617ace1b2197543` (app commit
`e8c0b6a`), 2026-08-14** — the first time its round trip has run on a device. See the
finding for what was watched. Nothing else in this document was re-run that day, so every
other observation below still dates from the image above.

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

**Severity: high. Diagnosed, fixed, and — as of 2026-08-14 — watched working on a
phone.** The account below is left in the order it was learned, because the wrong turn it
records is worth more than the fix.

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

### What was established, on a phone, 2026-08-14

Image `9617ace1b2197543`, app commit `e8c0b6a`, Galaxy Z Fold 7. The tour was started from
the chip and the whole round trip was read out of the session log rather than off the
screen:

```
tool_started         "Asked you" · "Let's build one small thing together… What should it be about?"
permission_requested kind:"question" · 4 options
permission_resolved  decision:"answer" · answers:{"…What should it be about?":"cats"}
tool_finished        "The user answered: …=\"cats\""
```

`The user did not answer the questions.` — the string this whole finding is about — does
not occur anywhere in that session. The options rendered on their own card, a person
typed a free-text answer into it, and the answer reached the model, which opened its next
message with "Cats it is."

Two things were being tested at once and both passed:

- **The agent chose the tool.** The previous attempt failed here, not in the plumbing: the
  wording in `guest/agent-conventions.md` telling it to call `AskUserQuestion` and not to
  ask in prose was itself the untested part. It held.
- **The `PreToolUse` hook works.** This was luck rather than design — the permission mode
  had been switched to *Approve everything* at 20:32:14, and the question was asked at
  20:33:01. So the call ran under `bypassPermissions`, fell through to `canUseTool`
  anyway, and reached a person. That is the half that was reasoned from the SDK's types
  and never watched; it no longer needs a separate run.

### What is still only read, not observed

- **The SDK evidence in this finding was read on a laptop**, from
  `@anthropic-ai/claude-agent-sdk` at `0.3.226`, the version `guest/harness/package.json`
  pins. It is not a guest observation, and the guest could not have made it — see the
  second bullet under "what the agent cannot do from in here". The behaviour it predicts
  has now been seen; the reading of the types has not been independently confirmed.

**Re-check:** call `AskUserQuestion` with two options. Passing looks like the four log
lines above. Still broken if the tool result is `The user did not answer the questions.`
Worth re-running under both permission modes, since only `bypassPermissions` exercises the
hook and a run in `default` would not notice it regressing.

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

## 5. The tour's own first command does not exist in the image

**Severity: low, and badly placed.** `guest/agent-conventions.md` told the agent to open
the tour with:

```bash
uname -a; nproc; free -h
```

`free` ships in `procps`, which this image does not install — the same reason `ps` is
missing, already noted at the top of this document. So the first command of the first
minute anybody spends with Box returns:

```
/bin/bash: line 1: free: command not found
```

Observed on image `9617ace1b2197543`, 2026-08-14. The agent recovered by itself with
`head -3 /proc/meminfo` and the user is unlikely to have noticed, which is the only reason
this is low severity rather than embarrassing. It is worth its own entry because of where
it sat: a file of instructions written on a laptop, naming a tool nobody had run in the
place the instructions would run. The conventions file is now part of the shipped image
and is testable the same way the code is.

**Fixed** by replacing the call with `head -3 /proc/meminfo`.

**Re-check:** run the tour and read its first shell card. Any `command not found` in the
opening three commands is this finding again.

---

## Resolved since the last image

- **No inbound file channel** *(broken at `9cbeb134`, fixed by `6206ff49`)* — the `(+)`
  attach button now delivers files to `/workspace/shared/inbox/`.
- **Agent could not see its own desktop** *(broken at `9cbeb134`, fixed by `6206ff49`)* —
  `scrot` is in the image; `scrot /tmp/screen.png` against `:0` works, so GUI work is no
  longer done blind.

- **`AskUserQuestion` was inert** *(finding 1; broken at `6206ff49`, fixed and verified on
  image `9617ace1b2197543`, 2026-08-14)* — the question renders as its own card, the
  answer reaches the model, and it does so under `bypassPermissions` too. The finding is
  kept in full above rather than reduced to this line, because the mechanism it gets wrong
  on the way is the useful part.

Finding 2 is *not* listed here, and will not be until an image carrying it has been run on
a device and its **Re-check** line has passed. Finding 5 was fixed in the same change that
recorded it and has not been re-run either. A fix that compiles is not a fix that
reproduces, and this section is the one place in the document that is allowed to mean
"verified".

---

## Suggested convention

Re-run this document against each new image and update it in place — including moving
newly-fixed items into **Resolved** with the commit that fixed them. Its worth is not the
snapshot but the diff between images, and a stale copy is worse than none: it invites
trust in checks nobody re-ran.

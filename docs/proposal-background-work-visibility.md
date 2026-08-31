# Proposal: show that background work is still running

**Date:** 2026-08-30
**Status:** proposal. Written in the guest, which has no JDK or Android SDK, so
nothing here has been compiled. The design is grounded in the existing code; the
patch is not.

## The problem, as the user hit it

> *"from my side, it looks like the chat is ended, i don't know if you have a
> background task to pick up when the thing ends or not — there's no visibility
> on this side"*

During a long Android build the agent started work in the background and ended
its turn. The work continued, and the agent was woken and posted again when it
finished — so nothing was broken. But for the twenty minutes in between, the
conversation was indistinguishable from one that had simply stopped.

The user had to ask whether anything was still happening. That question is the
bug.

## Why it happens

[`AgentActivity`](../app/src/main/kotlin/dev/localagent/workstation/agent/AgentEvent.kt)
models what the session is doing: `Thinking`, `Working`, `Starting`,
`AwaitingPermission`, `AwaitingInput`, `Ended`, and

```kotlin
/** Session exists but nothing is happening; the composer is live. */
data object Idle : AgentActivity
```

When a turn ends with background work still running, the session lands in `Idle`
— and that comment is then false. Something *is* happening. There is no state for
"the agent is not speaking, but work it started is still going", so the UI draws
the one thing it has, which claims the opposite.

## This has happened once already, and was fixed the same way

`Starting` exists because of an identical mistake, and its comment in
`box-claude-harness.mjs` reads:

> *Box has no state for "not up yet", so that window showed a bare "Thinking…" —
> indistinguishable from a wedged session, watched for a quarter of an hour on
> the first message after every start.*

Same shape: a real machine state with no model behind it, so the UI asserted
something untrue and the user was left watching. The fix was a new activity, not
a new widget — which is what makes it the precedent to follow rather than an
argument for a spinner.

## Proposal

Add one variant, modelled directly on `Starting`:

```kotlin
/**
 * The turn is over but work the agent started is still running, and it will be
 * woken when that finishes.
 *
 * Deliberately outside [Transcript.isBusy], on Starting's precedent: there is no
 * turn to interrupt, so the header must not offer "Stop", and the composer stays
 * live because the user can and should be able to talk while this runs.
 */
data class Background(val label: String, val count: Int = 1) : AgentActivity
```

Wire side, in `HarnessWire.activity()`:

```kotlin
"background" -> AgentActivity.Background(
    json.trimmedLabel() ?: "Working in the background",
    json.optInt("count", 1),
)
```

**Unknown kinds already fall through to `Idle`**, so an older app meeting a newer
harness degrades to exactly today's behaviour. That is the same "degraded, never
wrong" property the codebase cites for attachments and `subAgentId`, and it means
the two sides need not ship together.

The harness emits it when a turn ends with tasks outstanding, and clears it on
the next real activity event. The UI draws it where `Starting` is drawn — as Box
reporting on its own machinery, not in the agent's voice, because the agent is
not the one doing the talking here.

## What it should say

`Starting` earned its keep by being specific: "Getting Claude Code ready" rather
than a spinner. The same applies. `Building the APK — you can keep typing` tells
the user what is running and that they are not blocked. A bare "1 task running"
would be an improvement, but a weaker one.

The label is the agent's to supply, since only it knows what the work is.

## Open questions for review

- **Where it renders.** `Starting` has a home in the UI already; whether this
  belongs in the same place or below the composer is a design call, not a
  technical one.
- **Whether `count` earns its place.** One label may be enough. Included because
  "3 tasks running" is a different message from "still building".
- **Whether the harness can always know.** It sees the tasks it started; work
  spawned and detached from a shell may be invisible to it. Better to under-claim
  than to leave a stale indicator up.

## Not addressed here

The related annoyance in the same session — stopping a background task did not
stop its children, so two runs interleaved into one log — is the agent harness's
own process handling and outside this repo. `guest/agentd/agentd.py` already
spawns with `start_new_session=True` and signals with `os.killpg`, which is the
correct behaviour and needs no change.

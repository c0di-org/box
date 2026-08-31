# A turn the user sent, and nobody received

An investigation, not a fix. Observed on the image built from `d0af4db` on 31 Aug 2026, from
inside the guest, by the agent that was supposed to have received the message.

## What happened

The user opened Box, tapped an existing task, and watched it say *waiting for computer* and then
*Setting up Claude Code*. It looked ready, so they typed **"and 2 times 2?"** and sent it. Nothing
happened; the task dropped into a disconnected state. They backed out, went back in, watched
*connecting Claude Code* a second time, and the conversation came back to life.

Five minutes later they sent "?" — and the agent, seeing a transcript that went straight from its
own "4" to a bare "?", asked what they had meant. When they said "I asked 2 times 2", the agent
told them their message had said something else. It had not. The agent was reading a history with
a hole in it and was confidently wrong about the user's own words.

That last part is why this is worth a document rather than a bug report. The lost turn is a
delivery defect. The agent contradicting the user about what they had typed is what the delivery
defect *does to the product*, and it is the more expensive half.

## The screenshot is the evidence

In the user's transcript, "and 2 times 2?" is a solid, timestamped, right-aligned bubble —
identical to every message that really did arrive. It is not drawn as queued. `QueuedMessage`
(`app/.../ui/ConversationPane.kt:931`) renders an undelivered turn at 12% alpha with **"Waiting for
the computer"** underneath it, precisely so that this state is never mistakable for a sent one. So
Box was not showing a message in flight. Box was showing a message it believed had landed.

It had not landed. The agent's context has no such turn.

## Where it died

In the guest harness, handling a `prompt` command — `guest/harness/box-claude-harness.mjs:313`:

```js
emit(attachments.length > 0 ? { type: 'user_message', text, attachments } : { type: 'user_message', text });
pushPrompt({ text, attachments });
```

Two lines, two completely different lifetimes.

`emit` writes a JSON line to stdout. Stdout is the session log: `AgentSessionHost.consume`
(`runtime-qemu/.../AgentSessionHost.kt:98`) appends every chunk to a file on disk before notifying
any listener. That line is now permanent. It is what draws the bubble, on this launch and on every
replay forever after.

`pushPrompt` appends to `promptQueue` (`box-claude-harness.mjs:85`), a plain JavaScript array in
the harness process's heap. It waits there until the `prompts()` generator pulls it and yields it
to the model.

If the process dies in between, the durable half survives and the real half does not. On restart
the harness resumes the Claude Code session from the SDK's own transcript, which never received the
turn. **The log says the user spoke; the model's context does not.** Nothing reconciles the two,
and nothing ever notices they disagree.

The window is not narrow. The harness starts reading stdin long before the SDK query exists —
`box-claude-harness.mjs:1760` notes that starting Claude Code is "minutes of work here, all of it
before the first prompt can be read". That window is exactly the *Setting up Claude Code* the user
was looking at when they decided it was safe to type.

## Why four other layers each thought it was fine

The single dropped turn is the symptom. The reason it was *silent* is that every layer between the
composer and the model reports success at the boundary it can see, and no layer carries an
acknowledgement from the layer that actually does the work.

| Layer | What it treats as delivery | Why that is not delivery |
| --- | --- | --- |
| `IAgentSession.aidl` | `oneway void write(in byte[] data)` | Fire-and-forget by construction. There is no return value in which a failure could arrive. |
| `AgentSessionHost.write` (`:56`) | Bytes handed to `GuestSession.write` | A write arriving when `session` is null hits `?: return` and is dropped with no log line and no signal back. A later failure is `Log.e` only — the caller returned long ago. |
| `GuestAgentBackend.Record.write` (`:774`) | The `oneway` call returned without throwing | Correct and careful about *ordering*, but it stops retaining the command at this point. Only a null handle or a thrown exception puts it in `outbox`. |
| `BoxViewModel` (`:550`) | The harness echoed the text back | The echo is `emit`ted **before** the model has the turn. It proves the harness parsed the line, nothing more. |
| The harness | `pushPrompt` returned | The queue is in-memory and dies with the process. |

Each is locally reasonable. Composed, they are a chain of five hops in which the only end-to-end
statement anybody makes is "no one threw an exception yet".

Two consequences worth stating separately, because they are the ones that turned a lost message
into a lost message *nobody could recover*:

- **`onAttached` fires when the process is spawned, not when it can work.** `AgentSessionHost.start`
  calls `deliver { it.onAttached(handle, ...) }` (`:93`) as soon as `runtime.openSession` returns,
  so `record.handle` goes non-null and `SessionConnection` goes `Live` while the harness still has
  minutes of Node startup ahead of it. That is the "it seemed like it worked" the user reported —
  and it is what makes `Record.write` take the live path instead of the queue.
- **The visible queue and the real queue are different objects, and neither backs the other up.**
  `BoxUiState.queued` is display-only, in-memory, cleared by the echo. `Record.outbox` is the real
  one, and it was empty because the write had "succeeded". So when `onClosed` fired, this session
  was not in the set `runtimeStateReceiver` reattaches (`GuestAgentBackend.kt:289` filters on a
  non-empty outbox), `flushOutbox` had nothing to replay, and **no code path in the app re-sends a
  turn after a session dies mid-flight.** `flushHeldPrompts` is the only replay there is, and it is
  gated on `heldForSignIn`.

## The contract has no word for this

`docs/ui-contract.md` § 2:

```kotlin
suspend fun send(sessionId: String, text: String, attachments: List<Attachment>)
```

It returns `Unit`. There is nowhere in the `AgentBackend` interface for a delivery receipt to
live, so no implementation can be held to one and no UI can wait for one. The defect is in the
contract before it is in any of the code above.

## What would actually fix it, and why it is smaller than what is there now

The instinct is to add retries at each hop. That would make five unreliable acknowledgements into
five unreliable acknowledgements with timers. The cheaper move is to have **one** acknowledgement,
sent by the component that actually did the work, and to delete the four guesses.

1. **Give a turn an id.** The app already builds the command in `promptCommand`
   (`GuestAgentBackend.kt:454`); add a `turnId`.
2. **Acknowledge from where the truth is.** The harness emits `{"type": "turn_accepted", "turnId":
   …}` at the point `prompts()` yields the turn to the model (`box-claude-harness.mjs:1465`) —
   after the queue, not before it. That is the first instant at which anyone can honestly say the
   turn is the model's problem now.
3. **Keep it until then.** `Record.write` retains a turn in `outbox` until its ack arrives, rather
   than until the `oneway` write returns. Reattachment already flushes the outbox; this makes the
   existing machinery cover the case it was built for.
4. **Make the harness's queue crash-safe**, or accept that a crash before the ack means a replay.
   Either is fine — with an id, a redelivered turn is detectable rather than a duplicate.

The simplification is step 5, and it is the point of the exercise:

5. **Delete the echo-matching heuristic.** `BoxViewModel.kt:550` currently clears the visible copy
   by searching for a queued prompt whose *text* matches the echo, with a comment conceding that
   "the same message sent twice stays visible twice". That fuzzy match exists only because the turn
   has no identity. Given an id, it becomes a lookup, and the `user_message` echo goes back to
   being what it should have been all along — a transcript entry, not a delivery receipt.

Net: one new field, one new event, one honest ack, and a heuristic removed. Fewer moving parts than
today, not more.

## The general principle

> Report success from the component that did the work, not from the last boundary you could see.

Box is otherwise unusually careful about this, which is why it is worth naming. `AgentSessionHost`
appends to the log *before* notifying listeners so an attach between the two cannot miss a chunk
(`:106`). `publish` refuses to resurrect a record the backend has let go of
(`GuestAgentBackend.kt:832`). `flushOutbox` puts undelivered commands back (`:814`). The codebase
already knows this lesson in three places. The prompt path is where it was not applied, and the
cost of that gap is not a dropped packet — it is an agent telling a user, with total confidence,
that they did not say the thing they said.

### A sibling worth checking while someone is in here

`GuestAgentBackend.Listener.onData` (`:705`) drops a live chunk on `tryEmit` failure and logs
`"dropped a live chunk; the log replay will still carry it"`. That is probably true. It is also
the same shape of reasoning as the four rows in the table above — a local guess about a remote
outcome, never verified. Worth an actual test rather than a comment.

## What has *not* been done

- **Not reproduced.** This is a code-reading diagnosis, made from inside the guest, that matches
  the observed symptom and the screenshot precisely. Nobody has forced a harness crash in the
  startup window and watched the turn vanish.
- **No fix, no test.** Left deliberately for whoever picks this up.
- **The first test to write is the positive one.** Assert that a turn sent during harness startup
  *arrives in the model's context after a restart*. A test that only asserts the disconnected state
  is drawn would pass against today's code, which already draws it correctly — and still loses the
  message.

# Two unverified assumptions in the session log path

An investigation, not a fix. Read on the image built from `d0af4db` on 31 Aug 2026, found while
tracing a lost turn (see the companion investigation, `docs/turn-delivery.md`). Neither has been
reproduced on device; both are stated precisely enough to be tested.

They share a shape with that other finding, which is the reason for writing them down together. In
each case a component takes a local outcome — a call that did not throw, a buffer that had room —
as evidence about something happening somewhere else, and writes a comment saying why that is
fine. The comments are the interesting part: they are where the assumption is recorded, and they
are the only thing standing in for a test.

## 1. A dropped live chunk is not recovered, and splices the transcript

`GuestAgentBackend.Listener.onData` (`app/.../agent/GuestAgentBackend.kt:703`):

```kotlin
override fun onData(offset: Long, chunk: ByteArray) {
    // tryEmit rather than emit: this is a binder thread and must never block `:computer`.
    if (!record.chunks.tryEmit(offset to chunk)) {
        Log.w(TAG, "dropped a live chunk; the log replay will still carry it")
    }
    readStatus(record, offset, chunk)
}
```

`tryEmit` rather than `emit` is right, and the reason given is right: this is a binder thread and
blocking it would block `:computer`. The problem is everything after the `if`.

### The drop is reachable

`record.chunks` is declared at `:161` with `extraBufferCapacity = 256` and
`onBufferOverflow = BufferOverflow.SUSPEND`. `tryEmit` cannot suspend, so against a `SUSPEND`
policy it returns `false` the moment the buffer is full. The code already knows this — that is what
the branch is for.

There is a concrete path to filling it, and it runs through the slowest machine Box supports:

1. `events()` is a `channelFlow` (`:346`). Its `send` suspends once the downstream channel fills.
2. The downstream is `BoxViewModel`'s collector (`BoxViewModel.kt:538`), which calls
   `builder.build()` and pushes a whole new `Transcript` into Compose state **on every event**
   (`:562`).
3. `emitLines` → `send` therefore suspends inside `gate.withLock` (`:371`), which stops the
   `chunks` collector draining.
4. The 256-chunk buffer fills, and `onData` starts dropping.

A fast-streaming turn on a fully emulated phone is exactly that: many lines per second arriving
from the guest, each costing a transcript rebuild and a recomposition to consume.

### The replay does not carry it

The comment's claim is that the log on disk is a safety net. It is — once. `events()` reads the log
at `:390`, drains what it held, sets `replayed = true`, and never reads the file again. After that
point the cursor advances only from live chunks. A chunk dropped after the replay is recovered only
if the whole collector restarts: the user leaves and re-opens the conversation, or the UI process
dies.

So the sentence is true of a chunk dropped *during* startup and false of one dropped during a busy
turn — which is when the buffer actually fills.

### The gap is silently swallowed, and the next line is spliced

This is the part that makes it worse than a missing line. `SessionLogCursor.accept`
(`app/.../agent/SessionLogCursor.kt`):

```kotlin
fun accept(offset: Long, bytes: ByteArray): List<String> {
    val end = offset + bytes.size
    if (end <= consumed) return emptyList()
    val skip = (consumed - offset).coerceAtLeast(0L).toInt()
    consumed = end
    return drain(bytes.copyOfRange(skip, bytes.size))
}
```

`offset > consumed` **is** the signal that a chunk went missing — it is the one arithmetic fact
that distinguishes a gap from an overlap. `consumed - offset` goes negative exactly then, and
`coerceAtLeast(0L)` discards it. The cursor is written entirely for the overlap direction, and the
doc comment says so: *"Chunks wholly behind the watermark were in the file already and are dropped.
A chunk that straddles it … contributes only its tail."* Both sentences are about data arriving
twice. Neither is about data not arriving.

Three consequences follow, in order of increasing unpleasantness:

- **The gap is not reported.** No log line, no event, no diagnostic. The one place that could tell
  cannot tell.
- **The loss is made permanent.** `consumed = end` moves the watermark past bytes that were never
  read, so a subsequent `readFile` will `skip(consumed)` straight over them. The safety net is cut
  down by the failure it was meant to catch.
- **The transcript is corrupted, not merely shortened.** `drain` holds a trailing partial line in
  `pending` until the rest arrives. When a chunk is dropped mid-line, `pending` keeps the truncated
  head, and the next surviving chunk's first fragment is welded onto it. That spliced string goes
  to `HarnessWire.parse`, whose result is `?.let`-ed at `:362` — so a splice that fails to parse
  disappears without a word, and a splice that happens to parse becomes a transcript line nobody
  ever emitted.

### Nobody has tested the gap

`SessionLogCursorTest` has nine cases:

| Test | Direction |
| --- | --- |
| a log written while nobody was attached is replayed in full | — |
| a chunk already in the file is not delivered twice | overlap |
| a chunk straddling the point the file was read contributes only its tail | overlap |
| a line split across two chunks is held back until it is whole | continuity |
| a multi-byte character split across chunks survives | continuity |
| a second read picks up only what was appended since the first | overlap |
| a session with no log yet reads as empty rather than failing | — |
| the whole transcript is delivered once across a dead and restarted reader | overlap |
| a resumed session continues the log's numbering rather than starting over | — |

Every case that tests the watermark tests it in the duplicate direction. The suite is a careful,
thorough proof that nothing is delivered twice, and it says nothing whatsoever about something not
being delivered at all. `guest/agent-conventions.md` already names this failure mode — *"tests that
only prove refusal prove very little"* — and this is a clean instance of it in the app.

### What a fix looks like

The cursor should be able to say "I have a hole". Something like: if `offset > consumed`, that is a
gap; return it to the caller rather than coercing it away. The caller (`events()`) already holds
the log path and already knows how to read from a watermark, so the honest recovery is to re-read
the file from `consumed` and carry on — the bytes are on disk, written by `AgentSessionHost.consume`
*before* the callback fired, which is precisely the ordering (`AgentSessionHost.kt:106`) that makes
recovery possible.

Then `pending` must be cleared when a gap is detected, or the splice happens anyway.

**Write the positive test first:** drop a chunk from the middle of a stream and assert that every
line either arrives intact or is re-read from the log — and specifically that no line is ever
delivered welded to another. A test asserting only that the reader survives a gap would pass today.

## 2. `AgentSessionHost.write` drops bytes with no trace at all

`runtime-qemu/.../AgentSessionHost.kt:54`:

```kotlin
override fun write(data: ByteArray) {
    val live = synchronized(lock) { session } ?: return
    scope.launch {
        runCatching { live.write(data) }
            .onFailure { Log.e(TAG, "Could not reach session $sessionId", it) }
    }
}
```

Two different disappearances in five lines:

- `?: return` — a write arriving when `session` is null is discarded with **no log line at all**.
  Not a warning, not a diagnostic. The two sibling methods below it (`closeInput`, `cancel`) do the
  same, and for those it is defensible; for `write` the bytes were a user's turn or a permission
  decision.
- The `Log.e` on failure is after the fact by construction. `IAgentSession.write` is `oneway`
  (`runtime-qemu/.../aidl/IAgentSession.aidl`), so the caller in the app process returned before
  this coroutine ran and no failure can propagate to it.

The window for the first is real: `session` is assigned at `:90`, after the suspending
`runtime.openSession` returns. A `write` arriving before then finds null. The app is careful about
this — `Record.outbox` exists specifically because "`openAgentSession` returns before `onAttached`
arrives" (`GuestAgentBackend.kt:199`) — so in the normal path the app holds the command instead of
writing it. But `AgentSessionHost` cannot rely on its caller being careful, and when the assumption
does break there is nothing in the log to show it happened.

The minimal fix is one `Log.w` naming the session and the byte count, so that this becomes
visible instead of inferable. That is worth doing whether or not the larger acknowledgement work in
the companion investigation is ever picked up, and it is independent of it.

## Why these are one document

Both are the same reasoning error as the lost turn, and the fix for all three is the same
discipline rather than three unrelated patches:

> A component may only report what it actually observed. When it must assume something about
> another process, the assumption gets a test — not a comment.

Box's codebase is unusually good at this in the places where it was bitten. The log is appended to
before listeners are notified so an attach in between cannot miss a chunk. `publish` refuses to
resurrect a released record. `flushOutbox` puts undelivered commands back. Every one of those is a
lesson someone learned the hard way and encoded. The three findings collected here and in
`docs/turn-delivery.md` are the places where the same lesson was written as prose instead.

## What has *not* been done

- **Neither is reproduced.** Both are read from source. Finding 1 in particular describes a
  backpressure path that is argued, not measured — filling the 256-chunk buffer on device is the
  first thing to try, and if it turns out to be unreachable in practice that is a useful result and
  should be written back here.
- **No fix and no test.** Left open deliberately.
- **Finding 1 deserves a device measurement before a redesign.** If a busy turn never fills the
  buffer, the cheap fix is the gap detection alone; if it fills routinely, the transcript rebuild
  per event at `BoxViewModel.kt:562` is the pressure source and is worth its own look.

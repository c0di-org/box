# The UI contract

Box's UI is built against interfaces, not against the runtime. Everything below lives in `app/`
and is satisfied today by an in-process fake, so the conversation surface can be built, demoed and
screenshotted with no VM running. This document is the handshake for whoever implements the real
thing.

## 1. Agent events — `app/.../agent/AgentEvent.kt`

The transcript is an **append-only event log**. Two rules:

1. **Nothing is mutated in place.** A tool call that finishes emits a second event referencing the
   first by `callId`. A checklist that advances re-emits all its items under the same `planId`. A
   streaming message re-emits growing text under the same `messageId`. A backend can therefore be
   a dumb pipe, and a transcript can be rebuilt from cold storage by replaying the same events.
2. **Structured, never stringly.** Tool calls arrive as `ToolCall` variants (`Shell`, `ReadFile`,
   `EditFile`, `WriteFile`, `Search`, `Fetch`) and file edits as a parsed `FileDiff` — never as
   JSON the UI has to guess at. Anything Box does not model yet lands in `ToolCall.Generic`, which
   renders as a labelled key/value card. Degraded, but never a raw dump.

Event kinds: `SessionStarted` · `SessionEnded` · `UserMessage` · `AgentMessage` · `AgentThinking` ·
`ToolCallStarted` · `ToolCallProgress` · `ToolCallFinished` · `FileChanged` ·
`PermissionRequested` · `PermissionResolved` · `TaskProgress` · `ActivityChanged` ·
`ArtifactOffered` · `AgentError`.

### Why the log is not what the UI draws

`TranscriptBuilder` folds the log into a `Transcript` — a list of `TranscriptItem`s where a call
and its result are **one** card, a message streamed in 40 chunks is **one** bubble, and a checklist
that ticked four times is **one** block. The event log stays replay-friendly; the view stays
collapsed. `TranscriptBuilderTest` pins the four collapses the UI depends on.

The builder tolerates orphans: a `ToolCallFinished` whose `ToolCallStarted` was lost to a reconnect
renders as a completed call rather than disappearing.

### Permission is a first-class event

`PermissionAsk` carries everything the sheet needs to explain the risk without a round trip — a
parsed diff, a command line plus working directory, a hostname. `alwaysAllowScope` is a
human-readable string ("edits in this project"); `null` hides the always-allow button entirely.
Decisions are `Allow` / `AllowAlways(scope)` / `Deny` / `Abandoned`, and `Abandoned` is what a
dismissed sheet produces — dismissing never approves.

## 2. What the UI needs from a harness driver — `agent/AgentBackend.kt`

```kotlin
interface AgentBackend {
    val harnesses: StateFlow<List<HarnessDescriptor>>
    val sessions: StateFlow<List<SessionSummary>>
    fun events(sessionId: String): Flow<AgentEvent>          // replay, then live
    fun connection(sessionId: String): StateFlow<SessionConnection>
    suspend fun startSession(harnessId: String, prompt: String?): String
    suspend fun send(sessionId: String, text: String)
    suspend fun resolvePermission(sessionId: String, requestId: String, decision: PermissionDecision)
    suspend fun interrupt(sessionId: String)
    suspend fun closeSession(sessionId: String)
}
```

Two requirements that are easy to miss:

- `events()` must replay history before live events, and collecting twice must produce the same
  prefix. The UI relies on it to restore a transcript after process death.
- `connection()` is **orthogonal** to whether the agent is busy. A finished session can be
  disconnected and a running one can survive a reconnect. `Disconnected` is a normal state, not an
  error: the VM takes ~90s to boot and Android reclaims it whenever it likes.

`FakeAgentBackend` implements all of this with a scripted "clone project and run" flow that pauses
on a real permission request. It is the demo path and the development target.

## 3. What the UI needs from the runtime layer — `computer/DesktopTransport.kt`

Neither of these exists yet; both are declared so the panes have a shape to slot into.

```kotlin
interface DesktopTransport {
    val state: StateFlow<DesktopState>
    suspend fun attach(surface: Surface, widthPx: Int, heightPx: Int)
    suspend fun detach()
    suspend fun send(input: DesktopInput)      // only while the user holds control
    suspend fun setControl(holder: ControlHolder)
}

interface PreviewTransport {
    suspend fun forward(guestPort: Int): Result<String>   // loopback URL a WebView can load
    suspend fun release(guestPort: Int)
}
```

- Frames go through an Android `Surface`, not a bitmap stream: copying 60fps of ARGB across a
  process boundary would cost more than the VM does.
- `ControlHolder` is runtime-enforced, not a UI convention. The agent drives by default; when the
  user takes over, guest-agent input is suspended until they hand it back. The "Agent has control
  / Take over" pair in the computer header is a view of this state.
- `release` exists so a forwarded port never outlives the session that asked for it.

Until these land, `DesktopSlot` renders an inert placeholder at the real aspect ratio (so the
three-pane layout is measured against what will fill it), and "Open computer" / "Open preview"
are wired but report that the transport is still being built.

## 4. Layout

Three layouts, chosen from **window size only** — never device type, because a Fold changes class
mid-process and DeX windows are resized by dragging a corner.

| Layout | Width | Panes |
| --- | --- | --- |
| `Single` | Compact (< 600dp) | One at a time, bottom nav. The conversation pushes over the list. |
| `Dual` | Medium/Expanded < 1180dp | Session list + conversation. |
| `Triple` | ≥ 1180dp | Session list + conversation + the agent's computer. |

The decision is made once in `BoxApp`; every pane below is written to be dropped into any of the
three without knowing which it landed in.

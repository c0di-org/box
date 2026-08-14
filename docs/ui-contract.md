# The UI contract

Box's UI is built against interfaces, not against the runtime. Everything below lives in `app/`,
and each interface has two implementations: `GuestAgentBackend`, which drives a real harness in
the VM, and an in-process fake, so the conversation surface can be built, demoed and screenshotted
with no VM running. This document is the handshake for anyone implementing another one.

## 1. Agent events — `app/.../agent/AgentEvent.kt`

The transcript is an **append-only event log**. Two rules:

1. **Nothing is mutated in place.** A tool call that finishes emits a second event referencing the
   first by `callId`. A checklist that advances re-emits all its items under the same `planId`. A
   streaming message re-emits growing text under the same `messageId`. A backend can therefore be
   a dumb pipe, and a transcript can be rebuilt from cold storage by replaying the same events.
2. **Structured, never stringly.** Tool calls arrive as `ToolCall` variants (`Shell`, `ReadFile`,
   `EditFile`, `WriteFile`, `Search`, `Fetch`, `Task`) and file edits as a parsed `FileDiff` —
   never as JSON the UI has to guess at. Anything Box does not model yet lands in
   `ToolCall.Generic`, which renders as a labelled key/value card. Degraded, but never a raw dump.

Event kinds: `SessionStarted` · `SessionEnded` · `UserMessage` · `AgentMessage` · `AgentThinking` ·
`ToolCallStarted` · `ToolCallProgress` · `ToolCallFinished` · `FileChanged` ·
`PermissionRequested` · `PermissionResolved` · `ConnectRequested` · `ConnectResolved` ·
`TaskProgress` · `ActivityChanged` · `ArtifactOffered` · `AgentError` · `CaughtUp`.

`CaughtUp` is the odd one and it is not decoration: a log is replayed from the beginning every
time somebody opens a task, so a replayed question and a live one are the same bytes. It marks
the boundary, and only what is still outstanding when the log runs out is put in front of
anybody. See [github-auth.md](github-auth.md) for the failure it prevents.

### A turn ending is not the session ending

`SessionEnded` is terminal and happens once. A conversation has many turns and a session has one
ending, and the difference cost Box both halves of it: the Claude Agent SDK emits a `result` per
*turn* in streaming-input mode, the harness reported each one as `SessionEnded`, and so every
single reply got a "Task finished" rule under it — carrying `result`, which is the final assistant
message verbatim, printed a second time in small grey type — while the transcript sat marked ended,
with no working indicator and no Stop, until something else happened to narrate itself.

A turn ending is an `ActivityChanged` to `Idle`, and a turn starting is one to `Thinking`. A
harness has to say both: nothing else in the log distinguishes an agent thinking for two minutes
from an agent that finished and went quiet. `Transcript.isBusy` is what the header and the "Latest"
pill draw from, and the fold gives a `UserMessage` the benefit of the doubt — a message sent into
an idle or ended session marks it busy immediately, because the gap before the first thing comes
back is exactly the one where a still conversation is the wrong answer.

`SessionOutcome.Completed.summary` is for a caption, and the transcript gives it one line. It is
not a place to repeat what the agent already said; Box's own harness sends none at all.

### Sub-agents are in the same log

An agent that delegates does not get a second event stream. `ToolCall.Task` names a sub-agent —
carrying its `description`, the `prompt` it was given and its `agentType` — and the
`ToolCallStarted.callId` of that call **is** the sub-agent's identity. Everything the sub-agent then
says or does is an ordinary event carrying that id in `AgentEvent.subAgentId`:

| | |
| --- | --- |
| `subAgentId == null` | the session's own agent. Every event can be this. |
| `subAgentId == "toolu_7"` | the sub-agent named by the `Task` call whose `callId` is `toolu_7`. |

Only the events a sub-agent can genuinely produce carry it: `AgentMessage`, `AgentThinking`,
`ToolCallStarted` / `Progress` / `Finished`, `FileChanged`, `TaskProgress`. The rest have one author
by construction, and permission is deliberately not among them — the sheet answers one request at a
time, on behalf of the session, so attributing an ask would offer a choice the sheet does not have.

The `Task` call finishes like any other tool call, and its outcome is how the card ends:
`Success` when the sub-agent reported back, `Cancelled` when the user stopped it.

On the wire sub-agent attribution is one optional field. A harness line gains `"subAgentId": "toolu_7"`, and a
harness that has never heard of sub-agents omits it and keeps working — one author, as before.
`ToolCall.Task` is the tool kind `"task"`. Stopping one is a **new stdin command**, deliberately not
a field on `interrupt`:

```json
{"type": "stop_subagent", "subAgentId": "toolu_7"}
```

An older harness reads an unknown *field* while obeying the type it recognises, so
`{"type":"interrupt","subAgentId":…}` would stop the whole session on exactly the guests least able
to explain why. An unknown *type* is dropped with a diagnostic, which is the failure this should
have.

### Why the log is not what the UI draws

`TranscriptBuilder` folds the log into a `Transcript` — a list of `TranscriptItem`s where a call
and its result are **one** card, a message streamed in 40 chunks is **one** bubble, a checklist
that ticked four times is **one** block, and a sub-agent's whole transcript is **one** expandable
`TranscriptItem.SubAgent` holding its own folded items. The event log stays replay-friendly; the
view stays collapsed. `TranscriptBuilderTest` pins the collapses the UI depends on.

The builder tolerates orphans: a `ToolCallFinished` whose `ToolCallStarted` was lost to a reconnect
renders as a completed call rather than disappearing.

### Permission is a first-class event

`PermissionAsk` carries everything the card needs to explain the risk without a round trip — a
parsed diff, a command line plus working directory, a hostname, a question and its options. `alwaysAllowScope` is a
human-readable string ("edits in this project"); `null` hides the always-allow button entirely.
Decisions are `Allow` / `AllowAlways(scope)` / `Deny` / `Answered(answers)` / `Abandoned`, and
`Abandoned` is what a dismissed sheet produces — dismissing never approves.

**Several requests can be outstanding at once.** One turn can ask for two commands and block on
both. `Transcript.pendingPermissions` is therefore a list, oldest first — `pendingPermission` is
just its head, the one the composer's hint points at — and each request is answered by id, in any
order. Two rules fall out of that and both were once broken:

- Only a `PermissionResolved` for *that* id, or the session ending, stops a request from being
  outstanding. Nothing else may clear one. An `ActivityChanged` used to, so a parallel turn
  narrating itself while blocked silently discarded a live question, and no surface could raise it
  again.
- Every unanswered request renders its own inline decision in the transcript. A modal can only ever
  be about one of them, so the modal cannot be the only way to answer.

`AllowAlways` widens a rule, so it also answers any request already outstanding under the same
scope. Otherwise "always allow" visibly does nothing to the sibling ask that raised it.

**The sheet is opened, never raised.** It used to put itself up the moment anything was asked,
which on a phone meant appearing over whatever the user was doing: the keyboard went down as it
arrived and came back after the answer, so a request landing mid-sentence cost them their place
twice. The inline card is a complete decision, so the sheet is now what someone asks for by
tapping a card — the case it serves is wanting to read a whole diff first, which is real and is
not every request.

What replaces the modal's one virtue, that it followed the user anywhere, is a scroll: the oldest
unanswered request is brought onto the screen when it *changes*, which covers both one arriving
while the transcript is scrolled back and the next coming up behind each answer. It stays put when
the card is already fully on screen, so answering several that are visible together does not move
the list once per tap. See `TranscriptList` in `ui/ConversationPane.kt`.

**A question is answered on its card, and never opens the sheet at all.** `AskUserQuestion` is an
ordinary permission ask — the answer travels back as `PermissionDecision.Answered` inside the
permission round trip, because the tool's own `answers` field is where it goes — and its options,
their descriptions, the multi-select rule and the free-text reply are all drawn inline by
`ui/QuestionForm.kt`. The form briefly lived in the sheet, on the reasoning that a card carried no
answer to duplicate so raising a modal cost nothing. It cost the same thing every modal costs: a
question already stops the work, so the card *is* the interruption, and a sheet over it interrupts
the same person twice about the same thing. `BoxApp` filters questions out of `sheetTarget`, so the
sheet can state plainly that it never draws one.

Half-finished ticks live in an `AnswerStore` held by the conversation pane, not in the card. The
card is a row in a `LazyColumn` and would lose them the moment someone scrolled up to re-read the
paragraph the question is about — which is the most likely thing they will do.

**An outstanding request does not stop the composer.** Send used to switch off with a line reading
"Answer the request above", which was the sheet's urgency left behind after the sheet: the field
still took typing, so a thought went in, send did nothing, and the only sign was small grey text.
A prompt written while a tool call is parked goes into the harness's queue and is picked up when
the turn moves — the same queue that holds a message through a three-minute boot — so the message
goes, and the strip above the composer says what is still waiting with a way to scroll to it.
`test_harness_session.mjs` pins the round trip.

## 2. What the UI needs from a harness driver — `agent/AgentBackend.kt`

```kotlin
interface AgentBackend {
    val harnesses: StateFlow<List<HarnessDescriptor>>
    val sessions: StateFlow<List<SessionSummary>>
    val permissionMode: StateFlow<AgentPermissionMode>
    suspend fun setPermissionMode(mode: AgentPermissionMode)
    val agentModel: StateFlow<AgentModel>
    suspend fun setAgentModel(model: AgentModel)
    suspend fun setViewport(viewport: AgentViewport)
    fun events(sessionId: String): Flow<AgentEvent>          // replay, then live
    fun connection(sessionId: String): StateFlow<SessionConnection>
    suspend fun startSession(harnessId: String, prompt: String?, attachments: List<Attachment>): String
    suspend fun send(sessionId: String, text: String, attachments: List<Attachment>)
    suspend fun resolvePermission(sessionId: String, requestId: String, decision: PermissionDecision)
    suspend fun resolveConnect(sessionId: String, requestId: String, outcome: ConnectOutcome)
    suspend fun interrupt(sessionId: String)
    suspend fun interruptSubAgent(sessionId: String, subAgentId: String)
    suspend fun closeSession(sessionId: String)
}
```

Two requirements that are easy to miss:

- `events()` must replay history before live events, and collecting twice must produce the same
  prefix. The UI relies on it to restore a transcript after process death.
- `connection()` is **orthogonal** to whether the agent is busy. A finished session can be
  disconnected and a running one can survive a reconnect. `Disconnected` is a normal state, not an
  error: the VM takes ~90s to boot and Android reclaims it whenever it likes.

`permissionMode` is one setting for the whole box — `Ask`, `AcceptEdits`, `Everything` — and not a
per-session one: it says how far the user currently trusts the agents they are running, and a
conversation quietly keeping its own answer is how someone ends up approving everything in a session
they had forgotten was set that way. The values are the Claude Agent SDK's own permission modes, so
a backend passes them through rather than implementing them; on the wire it is one more stdin
command, `{"type": "permission_mode", "mode": "bypassPermissions"}`, told to a session before its
first prompt and again whenever it changes. Anything but `Ask` is drawn as a banner above every
conversation for as long as it is in force — a box that is silently approving everything looks
exactly like a box with nothing to approve, and that is the one confusion this must never cause.

`agentModel` is the third setting of the same shape — one value for the whole box, persisted, and
stated to every session. It carries a **full model id** (`claude-opus-5`, `claude-opus-4-5`), never
an alias like `opus`. The alias is the tempting choice and the wrong one: it resolves to one model
per family, so it hides exactly the fact the control exists to show — whether this box is answering
as Opus 4.5 or Opus 5 — and leaves someone who wants the older model on purpose with no way to ask.
The usual objection, that pinned ids go stale, does not bite here: the Claude Code that resolves
them is baked into the guest image, and that image is built from this same tree, so the list and
the resolver ship together or not at all.

It is the one standing setting that travels **two** ways, and both are load-bearing. A new session
is opened with `BOX_MODEL` in its environment, because the harness builds its query before it has
read a single line of stdin — the reader is attached first, but a line from a pipe is I/O and
`query()` is reached in the microtask before any of it is delivered. A session that learned its
model only from the command would therefore open on the CLI's default and be corrected a round trip
later, through a `setModel` an older Claude Code may not have. The command,
`{"type": "model", "model": "claude-sonnet-5"}`, is what moves a session that is *already running*:
the harness asks the SDK to switch, so a conversation mid-task answers its next turn as the new
model without being restarted.

That last property is why the control is on the composer beside the permission mode rather than on
the box sheet beside the machine size. Changing the model is something people do *during* a task —
plan on Opus, hand the mechanical half to Haiku, come back for the review — and a setting two taps
outside the conversation is one nobody reaches for mid-thought. It is drawn as its name rather than
a glyph, because "Opus 4.5" and "Opus 5" are the same icon and the name is the whole point.

A switch the guest cannot make has to reach the transcript, and both failures are pinned in
`test_harness_model.mjs`. A Claude Code too old to have `setModel` reports rather than appearing to
have switched, and keeps the choice for the next session, which opens on it through the
environment. A refused switch is rolled back — in the harness *and* in the log, which emits the
model twice, asked for and then taken back. Silence in either case would leave Box drawing one
model over a session answering as another, and the only visible difference between those two is how
good the answers are.

`bypassPermissions` has one more requirement, and missing it is silent. The CLI refuses the mode
outright — at launch and through `setPermissionMode`, whose rejection reads "the session was not
launched with `--dangerously-skip-permissions`" — unless the query was created with
`allowDangerouslySkipPermissions: true`. Without it, Approve everything left the guest in
`default`, every tool call went on stopping to ask, and the banner said the opposite. The option is
an allowance and not a mode: a session still runs in whatever `permissionMode` says, and all it
adds is that the answer the user picked is a legal one. A harness that cannot make a mode take must
say so in the transcript rather than on stderr; it is the one failure nothing else can show.

The control for it sits on the composer, next to send, with the same menu on a long-press of send
itself. It started in the header's overflow menu and moved because of where it is wanted: "stop
asking me about this" is a thought someone has *while* being asked, and a setting nobody finds is
one that turns into fatigue at the sheet instead.

### Artifacts

`ArtifactOffered` carries an `Artifact`: `Computer`, `Preview(url, guestPort)`, or
`Document(guestPath, name, mimeType)`. On the wire it is
`{"type": "artifact", "kind": "document", "guestPath": …, "name": …, "mimeType": …}`.

`Preview` is a guest port. Opening one asks the runtime to forward it — QEMU's user-mode network
stack does that itself, through the human monitor, with no proxy process and no change to the
guest — and the panel loads the resulting `http://127.0.0.1:<port>/` in a WebView. The forward is
bound to loopback because a dev server the agent started is for the person holding the phone, not
for the network they are on, and it is released when the panel closes rather than left to the VM's
lifetime.

`Document` exists because most of what an agent makes needs no server. Requiring one to show a
picture would mean starting a web server to hand over a PNG — absurd on a machine this size. It
carries no bytes: the path is read when the user asks for it, through the same reader the Files
panel uses, so it obeys the same size ceiling as everything else Box shows from the guest rather
than inventing a second answer to "too big". Opening one puts it in the Files panel — a view onto
the machine that floats over it, one at a time, which is what the panel already is.

The degradation rule has one deliberate exception here. Everywhere else an unknown kind renders as
a labelled card; an artifact this build cannot open is **dropped**, because an artifact is a
button, and a row offering to open something that opens nothing is worse than no row.

The offer half is a tool the agent calls, `mcp__box__show`, hosted in-process by the Claude
harness through the Agent SDK's `createSdkMcpServer`. One tool for all three kinds, taking exactly
one of `path`, `port` or `desktop`, because to the person they are one thing — a button that
appears in the conversation — and three tools would make the model pick a mechanism before it had
decided what it wanted to say.

A tool rather than a line the agent is told to print, and that is the load-bearing choice: the
harness emits the event, so a path is refused *before* it becomes a button and the agent is told
why in a result it can act on. An agent echoing its own protocol lines would be writing
unvalidated events into the log the app trusts, with no way to learn it got one wrong.

Two rules the harness enforces, both following from an artifact being a button whose label the
agent chooses and whose target the person cannot see before tapping:

- **A document is resolved with `realpath` and must land under `/workspace`, outside
  `/workspace/.config`, and be a regular file that exists.** Tighter than the inbox bound on
  attachments rather than looser — the app draws the agent's `name`, so a row reading "report.md"
  that opens the GitHub token is exactly what this refuses. Resolution rather than a prefix test
  because a symlink defeats the prefix test; existence because a button that opens nothing is
  worse than no button. There is deliberately no size check: the panel reads the path through the
  same reader the Files panel uses, so "too big" keeps having one answer.
- **A preview's port must already have a listener,** checked in `/proc/net/tcp`. Same reasoning:
  offering a port nothing serves hands the user a WebView full of connection error, and the honest
  place to fail is a tool result the agent reads. An unreadable table means an unknowable answer
  and the agent is believed.

`show` is passed in `allowedTools`, alongside `connect`, so it is never asked about. A sheet
reading "allow the agent to show you a file?" has one honest answer, and the artifact is itself a
button nobody has to press — the consent is the tap, and a prompt in front of it asks the same
question twice. It draws no tool card either: the artifact row is what happened, and a card beside
it saying so is the same sentence twice.

An artifact carries no `subAgentId` even when a delegate offered it. That is a choice, not an
omission: a button is addressed to the person, and the person is reading the main thread — one
buried inside a collapsed sub-agent fold is one they will not find.

`FakeAgentBackend` offers all three too, which is what keeps the path exercised without a VM.

### Attachments ride on the turn

`UserMessage` carries an `Attachment` list beside its text — `guestPath`, `name`, `mimeType`,
`bytes` — because the UI has to draw a thumbnail and rule 2 above says it must never do that by
parsing prose. On the wire it is one optional field on `prompt`, following `subAgentId`'s
precedent: a harness that has never heard of attachments ignores it and the agent gets the text.
Degraded, never wrong, which is what makes a field acceptable here where `stop_subagent` needed a
type of its own.

Nothing is carried in that field but a path. The file itself goes into the shared folder under
`inbox/`, so it reaches the guest by the sync that folder already has, and `/workspace/shared/inbox`
is the only place a harness will look — an attachment naming anything else is dropped with a
diagnostic. Two consequences worth stating rather than discovering:

- **The copy is about a second behind the keystroke,** so the harness waits for each file to be
  whole before handing the turn to the model. Without that, the failure is the one the feature
  exists to remove: the user shows the agent a picture and the agent says it cannot see it. If a
  file never arrives the model is told *that*, rather than a path with nothing at it.
- **Deleting is one-way in both directions.** A file the user deletes on the phone leaves the
  box's copy where it is, so there is no unsend. The composer says so; do not quietly imply
  otherwise.

The harness echoes the attachments back into the log with the `user_message` it emits, which is
what a restored transcript draws from. The app keeps no second record.

`setViewport` tells the agent what it is being read on, so it can write for a phone in one hand or
for a keyboard and a monitor rather than splitting the difference forever. On the wire it is
`{"type": "viewport", "layout": "wide", "widthDp": 1280, "hardwareKeyboard": true}`, and it follows
`permission_mode` exactly: stated to a session before its first prompt, and again whenever it
changes.

What it deliberately does **not** carry is a device type. "Is this DeX" is the question this
contract already refuses, and it refuses it harder here — a layout that goes stale is corrected by
the next frame, while an agent told once that it is talking to a phone believes that for the rest
of the session, through the fold opening and the window being dragged wider. So every field is
derived from the window that `BoxWindowSize` measures, re-sent on change, and `hardwareKeyboard` —
the one fact that comes from the configuration rather than the window — earns its place only
because it is re-sent too. It is carried apart from `widthDp` because it answers a different
question: not how much can be shown, but what it is reasonable to ask the *person* to type.

It is a new command type rather than a field on an existing one, for the `stop_subagent` reason: a
harness that has never heard of `viewport` drops it with a diagnostic and goes on writing as it
always has, which is a harmless failure. Values keep their JSON types — `widthDp` is a number —
because the half of the pair that would otherwise have to be lenient about `"1280"` is the half
that ships inside the guest image, where Box cannot reach it to settle the disagreement.

Nothing about it is persisted, unlike the permission mode. A window size restored from disk
describes a window that no longer exists, and a backend that has never been told is in an honest
state: it says nothing, and the agent writes the way it always has.

`interruptSubAgent` is not `interrupt` with an argument. Stopping the session throws away
everything in flight; stopping a sub-agent asks one delegate to stand down and lets the agent that
sent it carry on with whatever it hears back. A backend that cannot single one out should do nothing
rather than fall back to interrupting the session.

A transcript is readable **with the box closed**. Session logs are files in `:computer`'s private
storage and they outlive the VM by design, so `events()` must replay one whenever the log can be
reached — which needs the `:computer` process bound, not a booted guest. `GuestAgentBackend`
decides this with `attachPlan`, and the two rules it exists to hold are: reading a log never starts
a VM, and reading one is not a session ending — a task that stopped to ask a question yesterday
must not come back as Finished, or jump to the top of the list, for having been looked at. The
conversation stays honest about it: the connection is `Ended` and the box's own "Your box is closed
· Open" banner sits above the history.

`closeSession` is reachable from the list, by swiping a row from the end, as well as from the task's
own header menu. Both go through one path: the row leaves the list immediately and the close itself
waits out an undo snackbar (`BoxUiState.closingTaskId`). Nothing is told to the agent during that
window, which is what makes the undo free — once the close lands, the record, the index entry and
the guest's copy of the session are all gone.

`FakeAgentBackend` implements all of this with a scripted "clone project and run" flow that pauses
on a real permission request. It is the demo path and the development target. A second script —
"Audit the public API" — delegates to a sub-agent that parks rather than finishing, because a
delegate that completes in eight seconds is one nobody can try the Stop button on.

## 3. What the UI needs from the runtime layer — `computer/DesktopTransport.kt`

```kotlin
interface DesktopTransport {
    val state: StateFlow<DesktopState>
    val wantedGuestScreen: StateFlow<GuestScreen?>
    suspend fun attach(surface: Surface, widthPx: Int, heightPx: Int, preview: Boolean = false)
    suspend fun detach(surface: Surface)
    suspend fun send(input: DesktopInput)      // only while the user holds control
    suspend fun setControl(holder: ControlHolder)
}
```

- Frames go through an Android `Surface`, not a bitmap stream: copying 60fps of ARGB across a
  process boundary would cost more than the VM does. `VncDesktop` satisfies this from the UI
  process, reading QEMU's VNC server on an app-private socket, so no frames cross a process
  boundary at all.
- More than one surface attaches at once, because the box's header, the inline pane and the full
  window are three views of one machine. `preview` marks the ones that are only looked at, so a
  thumbnail never decides how big the guest's screen should be.
- `ControlHolder` is runtime-enforced, not a UI convention. Opening the Computer destination takes
  control unless an agent is mid-task, and leaving hands it back; guest-agent input is suspended
  for as long as the user holds it. The "Take over / You're driving" button in the computer's bar
  is a view of this state.

Port forwarding is not part of this interface. It is `IRuntimeControl.forwardPort`, because only
`:computer` reaches the VM's monitor; `releasePort` exists so a forward never outlives the panel
that asked for it.

## 4. Layout

Two destinations, and the layout question only applies to one of them.

**Computer takes the whole window at every size.** The machine is the surface — live, interactive,
with the pointer and keyboard going into it — and the agent, terminal and files float over it one
panel at a time (`ComputerPanel`). It is deliberately not a pane beside the chat: a wide window
used to give it the narrowest of three columns, non-interactive, which is how "there is a real
Linux computer in here" ended up reading as a screenshot.

### Driving it

Two hands, one cursor. A mouse is absolute — it reports where it is, RFB says where the pointer is,
and hover, which is most of what a mouse does and what every menu in the guest reacts to, arrives
with a coordinate attached. A finger is not a pointer, so touch drives a **trackpad** instead: drag
to move, tap to click, two fingers to right-click, two fingers to scroll, tap-then-drag to drag.
Landing the cursor under the fingertip instead would put the target beneath the thing aiming at it,
offer no hover at all, and cap precision at the width of a finger on a desktop drawn for a mouse.
`GuestPointer` holds the one cursor both hands move and converts the trackpad's deltas back into
the absolute coordinate the protocol wants, so the guest is still told where the pointer is on
every event. While a finger is driving, Box draws a cursor of its own — the guest's is however far
behind the emulated machine currently is, and the gap between the two is the only honest reading of
that there is.

**With no keyboard or pointer attached, the computer draws its own keyboard.** Not the IME: a stock
soft keyboard has no Control, no Alt, no Super, no Escape and no function row, and `Ctrl+C` in the
guest's terminal matters here more than autocorrect does. It is also a band in the layout rather
than an overlay, so the guest is resized once when it appears instead of on every inset change, and
the bar above it drags the split between screen and keys — which trades key size against screen,
and walks the keyboard into two halves under two thumbs as it shrinks. Sweep across the middle of
the keys and the keyboard becomes a trackpad for a few seconds, so pointing does not cost a mode
switch or a permanent strip of layout. `HardwareInput` decides by capability, never by device name:
DeX, a Bluetooth keyboard and a USB mouse all read the same, and plugging one in takes the drawn
keys away in the same beat. The menu overrides it in both directions, and offers the system IME for
anyone who wants dictation or another language.

**Tasks** picks between two layouts from **window size only** — never device type, because a Fold
changes class mid-process and DeX windows are resized by dragging a corner.

| Layout | Width | Panes |
| --- | --- | --- |
| `Single` | Compact (< 600dp) | One at a time. The conversation pushes over the list. |
| `Wide` | Medium and up | Task list + conversation. |

The decision is made once in `BoxApp`; every pane below is written to be dropped into either
without knowing which it landed in.

There is no bottom navigation bar. The computer is the top of the task list — the box's header
carries the machine's own screen, as large as the column can make it — and a button in the
conversation's header; both are always on screen, which the old nav bar was not.

That header is the only chrome the home surface has: the mark, the word "Box", one LED for the
machine's state, one overflow into the box's details, and under it whatever the stage owns — the
screen when open, a hairline and a phase while opening, one line and one word when closed. It was
a top bar stacked on a "Computer / Debian · in use" card with a second overflow menu going to the
same sheet.

## 5. Opening the box

`BoxStage` is the whole of it: `Closed`, `Working`, `Open`. What the home surface does with each is
`BoxUiState.boxOwnsWindow` — the box gets the window when there is nothing else worth showing, and
becomes the header the moment there is. The opening carries a composer, because the useful thing to do
with a three-minute boot is queue the first task; `BoxUiState.queued` holds it until the guest can
take it. The first opening on a device ends in a full-window greeting with both doors on it
(`readyGreeting`, persisted in `OpeningHistory`); every later one ends in a snackbar and a haptic.

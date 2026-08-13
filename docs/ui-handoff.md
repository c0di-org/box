# UI rework — handoff

Written for whoever picks this up next with no memory of building it. Read
[ui-contract.md](ui-contract.md) first if you are implementing a backend against this; read this
document if you are changing the UI itself.

## What changed and why

Box was a **VM manager**: bottom nav was Home | Terminal | Files, and Home was about starting and
stopping a virtual machine. That inverted the product. The VM is substrate; the product is a
conversation with an agent that happens to have a real computer.

So: the destinations became **Tasks | Computer**, the app opens on the task list, and the terminal
and file browser stopped being top-level destinations — they are tools you reach through the
computer, when you want to poke at something the agent did.

**Since then, the computer got the window back.** Demoting the VM was right; drawing it as a small
non-interactive picture in the third column of a wide layout was not, and it made "use the machine
yourself" a sub-feature two taps inside a tab. The Computer destination now *is* the machine —
full window at every size, live, driven by the pointer and keyboard — with the agent, terminal and
files floating over it one panel at a time. Someone who never speaks to an agent can install Box,
press Computer, and use Debian. The bottom nav bar went with that change: the computer is the
first row of the task list, carrying its own live screen, and a button in the conversation header.

The visual language did not change. `BoxTheme.kt` / `BoxTypography.kt` are as they were, plus two
constants (`BoxUserBubble` / `BoxUserBubbleLight`) — the user's own turns are the one non-green
surface in the app, because green means *the agent's* work and status, and that contrast is what
keeps a long transcript scannable.

## Where things live

Everything is under `app/`. Nothing in this change touches `runtime-qemu/` or `guest/`.

```
app/src/main/kotlin/dev/localagent/workstation/
├── agent/                     the contract + the fake
│   ├── AgentEvent.kt          THE CONTRACT. Read this first.
│   ├── FileDiff.kt            parsed diff model + a tolerant unified-diff parser
│   ├── Session.kt             HarnessDescriptor, SessionSummary, SessionStatus, SessionConnection
│   ├── AgentBackend.kt        what the UI needs from a harness driver
│   ├── Transcript.kt          TranscriptBuilder: folds the event log into what gets drawn
│   └── FakeAgentBackend.kt    scripted session, no VM. The whole UI is built against this.
├── computer/
│   └── DesktopTransport.kt    the guest screen over RFB, plus the preview port forwarder (unbuilt)
├── ui/
│   ├── BoxApp.kt              the shell: two destinations, two layouts, the sheets
│   ├── BoxWindowSize.kt       BoxLayout {Single, Wide} from window size class
│   ├── SessionsPane.kt        home: the box panel over one flat task list
│   ├── ConversationPane.kt    header, banners, transcript list, composer
│   ├── TranscriptItems.kt     one renderer per TranscriptItem variant
│   ├── PermissionSheet.kt     the important one
│   ├── CodeView.kt            syntax highlighter + DiffView + CodeBlock
│   ├── ComputerPane.kt        the machine, full window, with floating panels on it
│   ├── YourBox.kt             the box: closed, opening, the one greeting, the row
│   ├── WorkspaceTools.kt      terminal + files, moved out of the old BoxApp.kt
│   ├── RuntimeStatus.kt       statePresentation, StatusPill, RuntimeGate, DiagnosticsSheet
│   └── BoxMarks.kt            the Box cube + per-harness geometric marks
├── BoxUiState.kt              one state object; `boxStage` and `boxOwnsWindow` drive home
├── BoxViewModel.kt            two independent sources: AgentBackend and RuntimeService
└── MainActivity.kt            wiring only

app/src/test/kotlin/.../agent/  TranscriptBuilderTest, UnifiedDiffTest (9 tests, JVM, no device)
```

`BoxApp.kt` went from ~1400 lines of one-file app to ~465 lines of shell. The old contents were
not deleted — `HomeScreen` became `ComputerPane`'s overview, `TerminalScreen`/`FilesScreen` moved
to `WorkspaceTools.kt`, and the state presentation and diagnostics sheet moved to
`RuntimeStatus.kt`.

## The one idea to hold onto

**The event log is not what the screen shows.** `AgentEvent` is an append-only stream; a
`TranscriptItem` is a collapsed view of several events. A tool call and its result and its
streamed stdout are three-plus events and *one* card. A checklist that ticked four times is four
events and *one* block.

If you are adding something to the transcript, the question is always: what does the backend
emit (append-only, never mutated), and how does `TranscriptBuilder` fold it (keyed, replaced in
place)? Get both halves or the thing will duplicate itself on replay.

`TranscriptBuilderTest` pins the four folds the UI depends on. If you change the reducer and those
still pass, you probably did not break a transcript.

## State of play

Working, against the fake:

- One flat task list under the box, live Active / Needs you / Finished status
- Conversation: streamed prose, tool cards (collapsed to a one-line summary, expandable to
  output), checklists, diffs, permission records, artifact offers, error cards, session-ended rule
- Permission sheet with a syntax-highlighted diff and Allow / Deny / Always-allow
- Both task layouts, verified live on an emulator by changing `wm density` with the app running
- Empty / loading / disconnected / VM-not-ready states throughout

Deliberately inert:

- "Open preview" — wired, and posts a snackbar saying the port-forwarding transport is still
  being built

Not built:

- Persistence. Sessions live in the fake's memory and die with the process.
- Session rename, search, notifications for `NeedsYou`.
- **Anything in the guest that offers an artifact.** `AgentEvent.ArtifactOffered` is parsed from
  the wire and all three kinds draw, but no harness emits an `artifact` line — so today they come
  only from `FakeAgentBackend`. What is missing is the agent-facing half: a way for an agent to
  say "look at this", which is a design question the artifact contract does not answer.

## Gotchas

- **`:app:assembleStockDebug` fails without the guest images.** `guest/image/out/*.qcow2` is
  gitignored, so a fresh clone cannot build the stock flavour. Use `:app:assembleAvfDebug` for UI
  work — it needs no assets and is what this was developed against.
- **`BoxViewModel` needs `@JvmOverloads`** on its constructor. The default ViewModel factory
  reflects for a single-`Application` constructor; the optional `backend` parameter (for tests and
  previews) hides it otherwise, and you get a `NoSuchMethodException` at first composition.
- **Insets are handled once,** by `safeDrawingPadding()` on the root in `BoxApp`. That includes
  the IME, so do *not* add `imePadding()` to the composer or the terminal — you will double-pad
  when the keyboard opens.
- **The diff renderer needs `IntrinsicSize.Max`.** Rows live inside a `horizontalScroll`, so
  `fillMaxWidth` alone sizes each row to its own text and the added/removed wash stops mid-line.
  The `Column(Modifier.width(IntrinsicSize.Max))` in `DiffView` is what makes the washes run edge
  to edge.
- **`TextOverflow.MiddleEllipsis` does not exist** at Compose BOM 2025.01.00. Use `Ellipsis`.
- **The highlighter is not a lexer.** `highlightLine` paints a per-character colour buffer in
  priority order, last pass wins, one line at a time. A multi-line string or block comment will
  colour wrong. That was a deliberate trade for a ~100-line function that cannot throw inside a
  permission sheet; if you need real highlighting, replace it wholesale rather than patching it.
- **Chat never blocks on the VM.** The runtime state renders as a dismissable banner, not a gate.
  A user should be able to live in the conversation while the computer is off, booting, or dead.
  Only the Computer destination's tools gate on `RuntimeState.Ready` (via `RuntimeGate`).

## Demoing it

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew :app:assembleAvfDebug
```

Install, open, tap **Clone project and run** under ChatGPT. The script runs for about 15 seconds
and stops at a permission request — that is the fastest path to the sheet. Allow takes the happy
branch; Deny takes a different one where the task is marked skipped and the agent asks whether to
proceed anyway. Adjust `FakeAgentBackend(scope, pace = …)` to speed the script up or slow it down.

The seeded **Review PR #42** session is deliberately disconnected with an error card, so the
unhappy path is one tap away too.

To check the layouts without a foldable:

```bash
adb shell wm size 2400x1080 && adb shell wm density 200   # → three panes
adb shell wm size 1800x1200 && adb shell wm density 280   # → two panes
adb shell wm size reset && adb shell wm density reset
```

## If you are the one implementing the backend

[ui-contract.md](ui-contract.md) is the document for you. The short version: implement
`AgentBackend`, emit `AgentEvent`s, and delete nothing in `agent/` except `FakeAgentBackend`.
`BoxViewModel` takes the backend as a constructor parameter and defaults to the fake, so swapping
it is one line in `MainActivity`.

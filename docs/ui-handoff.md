# UI rework — handoff

Written for whoever picks this up next with no memory of building it. Read
[ui-contract.md](ui-contract.md) first if you are implementing a backend against this; read this
document if you are changing the UI itself.

## What changed and why

Box was a **VM manager**: bottom nav was Home | Terminal | Files, and Home was about starting and
stopping a virtual machine. That inverted the product. The VM is substrate; the product is a
conversation with an agent that happens to have a real computer.

So: nav is now **Conversations | Computer**, the app opens on the session list, and the terminal
and file browser stopped being top-level destinations — they are secondary tools inside Computer,
reached when you want to poke at something the agent did.

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
│   └── DesktopTransport.kt    interfaces the runtime layer still owes us. Nothing implements them.
├── ui/
│   ├── BoxApp.kt              the shell: 3 layouts, both sheets, the nav bar
│   ├── BoxWindowSize.kt       BoxLayout {Single, Dual, Triple} from window size class
│   ├── SessionsPane.kt        harness-grouped session list + New conversation
│   ├── ConversationPane.kt    header, banners, transcript list, composer
│   ├── TranscriptItems.kt     one renderer per TranscriptItem variant
│   ├── PermissionSheet.kt     the important one
│   ├── CodeView.kt            syntax highlighter + DiffView + CodeBlock
│   ├── ComputerPane.kt        computer destination, desktop slot, runtime status card
│   ├── WorkspaceTools.kt      terminal + files, moved out of the old BoxApp.kt
│   ├── RuntimeStatus.kt       statePresentation, StatusPill, RuntimeGate, DiagnosticsSheet
│   └── BoxMarks.kt            the Box cube + per-harness geometric marks
├── BoxUiState.kt              one state object; `groups` derives the grouped session list
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

- Session list grouped by harness, collapsible, live Active / Needs you / Finished status
- Conversation: streamed prose, tool cards (collapsed to a one-line summary, expandable to
  output), checklists, diffs, permission records, artifact offers, error cards, session-ended rule
- Permission sheet with a syntax-highlighted diff and Allow / Deny / Always-allow
- Three layouts, verified live on an emulator by changing `wm density` with the app running
- Empty / loading / disconnected / VM-not-ready states throughout

Deliberately inert:

- The live desktop — `DesktopSlot` renders a placeholder at the real 16:10 aspect ratio, so the
  three-pane layout is measured against what will fill it rather than a stand-in that shrinks
- "Open computer" / "Open preview" — wired to `BoxViewModel.openArtifact`, which switches to the
  Computer destination and posts a snackbar saying the transport is still being built
- "Take over" — same; `ControlHolder` is modelled but nothing enforces it yet

Not built:

- Persistence. Sessions live in the fake's memory and die with the process.
- Attachments in the composer, session rename, search, notifications for `NeedsYou`.

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

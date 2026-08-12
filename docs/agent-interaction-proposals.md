# Reaching each other — four proposals

Four changes to the channel between the person holding the phone and the agent inside the box.
They came out of a session on a real device on 2026-08-12, the day the guest first came up
working: the user asked the agent what it wanted, and these are the things it could not do.

Read [ui-contract.md](ui-contract.md) first. Three of the four are additions to that contract, and
two of them are **finishing something already declared there** rather than inventing anything.

**Status: proposals.** Nothing here is built. The box that produced this document has no JDK and
no Android SDK by design, so none of it has been compiled — the file paths and hook points are
read from source, but every line of suggested code is unverified. Treat it as a design review, not
a patch.

Suggested order is **1 → 2 → 3 → 4**. #1 moved to the front after being tested on the device: the
share sheet does not offer Box, and the shared folder it was supposed to fall back on is
app-private, so there is currently no practical way to get a file *in* at all. #2 is one line and
can ride along in the same image build.

---

## 1. Getting images and files *in*

### What is there now

`AndroidManifest.xml` declares exactly two intent filters: `MAIN`/`LAUNCHER`, and
`DOCUMENTS_PROVIDER` on `SharedFolderProvider`. That provider is why the box folder appears in the
system Files app and in every app's Open/Save dialog, and it is the right primitive — the sync
machinery underneath it (`SharedFolderSync`, `SharedSync`, `BoxFiles`) already handles getting
bytes across the boundary in both directions.

But it makes Box a destination you navigate *to*, never something you send *to*. The handoff
document already lists **"Attachments in the composer"** under *Not built*; this is that item,
plus the door next to it.

### Tested on the device, and it is worse than "inconvenient"

On 2026-08-12 the user tried to send both an image and a `.txt` from the Android share sheet.
**Box was not offered as a target for either.** That is the expected consequence of the manifest
above — with no `ACTION_SEND` filter there is nothing for the share sheet to list — but it is worth
recording as an observation rather than an inference, because the conclusion it forces is stronger
than the one this document originally drew.

The shared folder is **app-private**. `SharedFolder.on()` returns `context.filesDir/shared`, so on
disk it is:

```
/data/data/dev.localagent.workstation.<flavour>/files/shared
```

Nothing browses that. There is no path under Internal storage to navigate to, and a file manager
cannot dig its way in — by design, and the source says so. The only door is the DocumentsProvider,
which surfaces as a root titled **Box** ("Shared with your box") in the *system document picker*.

Which leaves a user with no working inbound path at all:

| Route | Result |
| --- | --- |
| Share sheet | Box is not a target. **Tested, fails.** |
| File manager → Internal storage | The folder is app-private. Not reachable, by design. |
| Samsung My Files, Files by Google | Third-party provider roots are often not listed at all. |
| System document picker (SAF) drawer | Works — if you reach a picker that has a drawer, from an app that offers Save-to. |

So "just put it in the shared folder" is not a fallback that makes the share target optional. On a
real device, in the hands of the person who owns the box, it is close to unreachable. **This is not
a convenience feature; it is the only practical inbound channel, and it does not exist yet.**

There is a symmetric problem worth its own look: a file the *agent* leaves in the shared folder is
just as hard for the user to get at, so "I've put it in your box folder" may be no more useful to
them than a path inside the VM. Outbound was not tested on the device and is not proposed on here,
but it should not be assumed to work either.

### What is missing

Both doors into the conversation:

| Door | Where | Why this one |
| --- | --- | --- |
| **Share target** | Android share sheet | The screenshot is already in your hand, in another app. Share → Box. |
| **`(+)` in the composer** | Next to send | You are already typing to the agent and want to show it something. No app switch. |

They are not redundant. The share sheet wins when the file exists first and the intent to send it
comes second; the `(+)` wins when the conversation exists first — which is most of the time, and
is the one the user asked for by name.

The motivating case is small and completely blocked today: showing the agent a screenshot of the
DeX desktop it is running inside. Right now that can only be described in prose.

### Proposal

**Manifest** — add to `MainActivity`, which is already `exported`:

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="image/*" />
    <data android:mimeType="text/*" />
    <data android:mimeType="application/pdf" />
</intent-filter>
```

Deliberately not `*/*`. A share target that offers itself for everything is one that clutters
every share sheet on the device, and Box has no answer for an arbitrary binary beyond putting it
on a disk. Widen it when there is a reason.

**Composer** — the `(+)` sits in the control cluster that already exists next to send, where the
permission-mode control lives. Use `ActivityResultContracts.PickVisualMedia` for images (it needs
no storage permission and gives the system photo picker) and `OpenDocument` for anything else.

**Landing place** — both doors write into the shared folder, under `inbox/`, via the existing
sync. The guest sees a real path at `/workspace/shared/inbox/<name>`, which is a path the agent
can simply read. Two consequences of the sync's semantics that must be in the UI copy, not
discovered later:

- **Their side wins on conflict**, so a name collision keeps the phone's copy and parks the other
  as `name.from-box`. Attachments should be named to avoid the question entirely — timestamp or
  content hash prefix, not `screenshot.png`.
- **Deleting is one-way in both directions**, and this is the bullet to get right. A file the
  guest removes comes back on the next copy, because the phone is the source of truth — so the
  agent must never "clean up" the inbox. But a file the *user* deletes is the mirror image: the
  box keeps its copy and simply stops carrying it out (`SharedSync.plan`, the `onPhone == null &&
  inBox != null` case). **Deleting an attachment on the phone does not remove it from the box.**
  There is no user-facing way to unsend one, which matters more for an inbox than anywhere else in
  the shared folder — an inbox is exactly where someone puts a thing they might want back. The UI
  copy has to say so at the point of attaching, and the retraction path is either its own proposal
  or an accepted limitation, but it cannot be left implied.

### Contract impact

An attachment is part of the user's turn, so `UserMessage` gains a structured list — the contract's
"structured, never stringly" rule means the UI must not be parsing paths out of prose to draw a
thumbnail:

```kotlin
data class Attachment(val guestPath: String, val name: String, val mimeType: String, val bytes: Long)
```

On the wire this follows the `subAgentId` precedent — one optional field that an older harness
omits and keeps working. The failure mode when it is ignored is *degraded, not destructive*: the
agent gets the text without the picture. That is the difference from the `interrupt` /
`stop_subagent` case, where an ignored field would have been actively wrong, and it is why a field
is acceptable here where a new type was required there.

One honest tension: belt and braces argues for also naming the guest path in the message text, so
a harness that has never heard of attachments still receives something actionable. That is
stringly, and the contract dislikes it. The recommendation is to do it anyway — the structured
field stays the source of truth for rendering, the text line is purely a fallback for a harness the
UI does not control.

---

## 2. Letting the agent see its own desktop

### What is there now

`local-agent-desktop.service` runs a real X session — `xinit openbox-session -- X :0 vt1` as user
`agent` — and the user can see it through the Computer destination over RFB. The socket is there:
`/tmp/.X11-unix/X0`.

The agent cannot see any of it. `guest/packages.txt` installs `xserver-xorg-core`, `xinit`,
`openbox`, `xterm` and `dbus-x11`, and no capture tool whatsoever: no `scrot`, `import`, `xwd`,
`maim`, or `ffmpeg`. Anything with a window in it is therefore built blind — the agent can launch
a GUI and then has no way to find out what it drew.

That service's comment says the desktop is "for the person, not for the agent", and that ordering
is right and should not change. This does not ask the agent to depend on the desktop. It asks that
when the desktop happens to be up, the agent is not the only party in the room who cannot see it.

### Proposal

One line in `guest/packages.txt`, which `build-image.sh` reads straight into the package list:

```
scrot
```

`scrot` over the alternatives on purpose: it writes PNG directly, which is the format that can be
read back without a conversion step, and it costs a couple of megabytes against ImageMagick's
fifty-odd for `import`. `xdotool` is the obvious companion for actually *driving* a GUI rather than
just watching it, and is worth a second line if this proves useful — but sight before hands.

**There is a second half, and it is the half that will be forgotten.** The agent's shell has no
`DISPLAY` set — it is empty in the session this was written from, even though the server is
running. So `scrot` alone still fails. Either export `DISPLAY=:0` into the agent's environment
(`local-agentd.service`), or document the invocation:

```sh
DISPLAY=:0 scrot -o /workspace/shared/screen.png
```

Writing to `/workspace/shared` means the screenshot is also something the *user* can open, which
makes this useful in the other direction too — "here is what your desktop looks like right now"
becomes an answerable question.

Cost: one line, plus one environment variable. Requires an image rebuild, so it only lands on the
device with the next app update.

---

## 3. Telling the agent what it is being read on

### What is there now

Nothing reaches the agent. It cannot distinguish a phone held in one hand from a DeX session with
a keyboard, mouse and a monitor, so it writes the same way for both — which is wrong for at least
one of them at all times.

`BoxWindowSize.kt` already computes exactly the right signal for the UI's own purposes, and its
doc comment contains the warning that should govern this whole proposal:

> Derived from the *window*, never from the device: a Fold changes class mid-process when it
> opens, and DeX windows are resized by dragging a corner.

### Proposal

**Do not send a device type.** "Is this DeX" is the question the contract already rejected, for
reasons that apply with more force here — the agent holds its answer for a whole session, so a
stale one lasts longer than a stale layout does.

Send the window-derived facts the UI already computes, and re-send them when they change:

```json
{"type": "viewport", "layout": "wide", "widthDp": 1280, "hardwareKeyboard": true}
```

`permission_mode` is the precedent to copy exactly — a stdin command told to a session before its
first prompt and again whenever it changes. This should be a new command type rather than a field
on an existing one, per the `stop_subagent` reasoning: a harness that does not understand
`viewport` should drop it with a diagnostic and carry on writing as it always has, which is a
harmless failure.

What the agent does with it is a matter of style, not capability, but the difference is real:
dense output, long commands and wide tables when there is a keyboard and 1280dp; short answers,
fewer scrolls and tappable next steps on a compact window. `hardwareKeyboard` is worth carrying
separately from width because it changes what is reasonable to ask the *user* to type, which
width alone does not tell you.

**`hardwareKeyboard` is the one field here that does not exist yet**, and it is worth being exact
about why. `rememberBoxLayout` derives `Single`/`Wide` from the window's width and nothing else, so
`layout` and `widthDp` are a straight read of what the UI already knows. A keyboard fact is not in
there and cannot be: it comes from `Configuration.keyboard` / `keyboardHidden`, which is device
state, which is the category this proposal just spent a paragraph refusing to send.

The distinction that makes it acceptable is *re-sent*, not *derived*. What the contract rejected is
a fact told once and believed for a session — "this is a DeX device" — and `hardwareKeyboard`
arriving on every config change is no more stale than `widthDp` is. It is a keyboard being
*attached right now*, not a device type. So: derive it from `Configuration`, send it through the
same command, and let it change. If that feels like too much for a first cut, drop the field and
ship `layout` and `widthDp` alone — they are free, and an agent that knows the window is 1280dp
wide has already guessed most of what the keyboard would have told it.

Cheap to send, changes every message.

---

## 4. Rendering what the agent makes

### What is there now

More than the conversation that produced this assumed — this proposal shrank considerably on
contact with the source. The design already exists:

- `AgentEvent.ArtifactOffered` is a first-class event kind.
- `Artifact` is a sealed interface: `Computer`, and `Preview(url, guestPort)`.
- `TranscriptBuilder` folds consecutive offers into one `TranscriptItem.Artifacts` row.
- `ArtifactRow` in `TranscriptItems.kt` draws it.
- `Markdown.kt` already renders markdown inline in the transcript.
- `ComputerPane` already has the pattern for a floating panel over the machine, one at a time.

What is missing is the transport. `PreviewTransport` is declared in `DesktopTransport.kt` and not
implemented, so `BoxViewModel.openPreview` posts a snackbar saying so. The handoff document lists
it under *Deliberately inert*.

So this is two pieces of work, and only the first is large.

### 4a. Implement `PreviewTransport` — the web panel

```kotlin
suspend fun forward(guestPort: Int): Result<String>   // loopback URL a WebView can load
suspend fun release(guestPort: Int)
```

This is the port forwarder, and it unblocks an affordance that is already wired end to end. The
panel it opens into should be a `ComputerPanel` — the pattern is established, and a web preview is
the same kind of thing as the terminal and the file browser: a view onto the machine that floats
over it one at a time.

`release` exists so a forwarded port never outlives the session that asked for it; honour it.

### 4b. Artifacts that are files, not ports

A markdown document or a PNG needs no server, and making the agent start one to show a picture
would be absurd. That needs a new variant alongside `Preview`:

```kotlin
data class Document(val guestPath: String, val name: String, val mimeType: String) : Artifact
```

Which gives the three renderings the user asked for:

| Kind | Where it goes |
| --- | --- |
| Web page / dev server | Panel, via `PreviewTransport` (4a) |
| Markdown | **Both.** Inline in the transcript for something short; panel for a document. |
| Image, screenshot, diff | Inline in the transcript, tap to open full |

Markdown deliberately goes both ways rather than picking one. A four-line summary belongs in the
flow of the conversation; a twelve-page report belongs in a panel that can be scrolled without
losing your place in the transcript. The agent chooses by offering an artifact or simply speaking,
which it can already do.

Note `MAX_PREVIEW_CHARS` (128 KiB) in `BoxViewModel` as the existing precedent for what a guest is
allowed to hand over in one piece; a document artifact should respect the same ceiling so the two
paths do not disagree about what "too big" means.

### Contract impact

Additive. A new `Artifact` variant is a new sealed-interface case, and the contract's own
degradation rule applies — an unknown artifact kind should render as a labelled row that does
nothing, in the spirit of `ToolCall.Generic`: degraded, but never a raw dump.

---

## Summary

| # | Change | Where | Size |
| --- | --- | --- | --- |
| 1 | Share target + composer `(+)` | `AndroidManifest.xml`, `ConversationPane.kt`, contract | Small–medium |
| 2 | `scrot` + `DISPLAY=:0` | `guest/packages.txt`, `local-agentd.service` | One line, plus an env var |
| 3 | `viewport` stdin command | Harness protocol, `BoxWindowSize.kt`, `BoxViewModel` | Small |
| 4a | `PreviewTransport` | `computer/DesktopTransport.kt` | The only large one |
| 4b | `Artifact.Document` | `AgentEvent.kt`, `TranscriptItems.kt` | Small |

Three of the five are small and one is already designed and waiting for a transport.

#1 is the one to do first, and the device test is why. Everything else here improves a channel
that works; #1 is a channel that does not exist — a user holding a screenshot of their own box has
no way to show it to the agent running inside it. #2 costs one line in the same image build and
stops the agent working blind, so the two travel together well.

# Box in three shapes — field notes

A walk through the shipping app on real hardware, 12 Aug 2026: a Galaxy Z Fold 7 running
`box-minimal-claude@35435cb76e5eb827` (guest built from `6206ff4`), driven over Wi-Fi adb.

Not an emulator pass. `tools/screenshots.sh` already photographs `UiGalleryActivity` on emulators,
which is the right way to get a repeatable picture of a state nobody can reach on a desk. This was
the other half: the app in the shapes a user actually meets it in, with a real Linux VM behind it —
folded (cover screen), open (inner screen), and in Samsung DeX on a 34" 3440×1440 monitor.

Everything below was seen, not inferred. Where something is a guess it says so.

## Taking these pictures

`tools/device-shots.sh` drives and photographs the app per display:

```bash
tools/device-shots.sh list                  # which shapes exist, and where Box is
tools/device-shots.sh --dex shot opening    # cropped to Box's own window
tools/device-shots.sh --dex tap 270 158     # window-relative, so a script survives a resize
```

Three things it has to handle, all of which silently produce garbage otherwise:

- **`screencap` and `input` take different display ids.** `screencap -d` wants the 19-digit
  physical id; `input -d` and `am start --display` want the logical one (0, 1, 10). A logical id
  passed to `screencap` does not error — it writes a PNG of a warning banner.
- **A fold has two internal panels and only one is ever on.** Nothing in adb wakes the dark one;
  that is the hinge's job. The phone-shaped shot has to be taken with the phone shut.
- **A DeX screenshot photographs the whole desktop** — browser tabs, mail, notifications. Cropping
  to Box's window is the default for that reason, not for tidiness.

## The big one: the computer could not be driven at all

**"Take over" was a no-op, end to end.** Tapping it correctly flipped the pill to "You're driving",
and then every click and keystroke was silently discarded. Verified on the desktop: the guest's X
cursor never moved from where it sat, and a typed command never reached the `xterm` prompt.

The cause was not in the UI, which is wired correctly — `interactive` follows
`state.desktopControl == ControlHolder.User`, `guestSize` follows the live picture, and
`DesktopView` forwards pointer and key events without filtering on event source. It was one level
below, in `QemuCommand.headless`: the machine was built with a GPU, disks, network and serial, and
**no input device of any kind**.

QEMU's VNC server delivers RFB pointer and key events into QEMU's input subsystem, which routes
them to registered input devices. `-M virt` registers none — there is no PS/2 controller outside
x86, and no USB controller unless one is asked for. Nothing logs a warning. The events simply stop.

Fixed by giving the machine something to be touched:

```
-device virtio-tablet-pci,romfile=
-device virtio-keyboard-pci,romfile=
```

Tablet rather than mouse because the pointer is absolute everywhere above that line — RFB carries
absolute coordinates and `DesktopView.toGuest` maps a touch straight to a guest pixel. A
`virtio-mouse` reports relative deltas, which would need an acceleration model and would still
drift from where the finger is. The guest already ships `xserver-xorg-input-libinput`, so it can
bind the device without an image change.

**This change also needed a guard.** `-loadvm` restores a guest's memory into a machine that has to
match the one it left, device for device, and it fails *after* rolling the disks back toward the
snapshot. `pendingResume()` only compared the image identity, which catches a new Debian but not a
new device on the same Debian — so adding these two devices would have turned every already-paused
box into a failed reopen. `QemuCommand.machine()` now fingerprints the launch command itself and
the suspend note records it, so a machine that changed shape discards the snapshot and boots cold.
Derived from the command rather than a constant somebody has to remember to bump, because the
failure it prevents is silent and a reminder would not be.

**Verified on the device after the fix.** The guest's cursor now follows a tap — it had been frozen
in one spot through every previous attempt — and a typed command reaches the shell:

```
agent@355cb8862545:/workspace$ uname -srm; ls -d /workspace/.config && echo CREDS-SURVIVED
Linux 6.1.0-50-arm64 aarch64
/workspace/.config
CREDS-SURVIVED
```

That single line settles three things at once: input arrives, the machine is a real arm64 Linux
6.1, and `/workspace` came through the image swap intact.

## The guest's screen now follows the window

**Fixed 12 Aug 2026, and verified on the phone.** The desktop was a fixed 1280×800 landscape
rectangle letterboxed into every shape. On the cover screen it used about 31% of the pane and the
terminal in it could not be read. It is now portrait, edge to edge, and legible.

`docs/assets/screenshots/device/phone-computer-letterboxed.png` and `phone-computer-fitted.png` are
the same machine before and after.

### The route this document proposed does not exist

The plan above was the obvious one: Box's RFB client already decodes `DesktopSize`, so have it ask.
It cannot. `DesktopSize` (-223) is server-to-client only — a client asks with `SetDesktopSize`
(message 251) and the server answers with `ExtendedDesktopSize` (-308) — and **this build's QEMU
does not implement either**. The prebuilt `libqemu-system-aarch64.so` is 5.1.0 and is not stripped,
so it can simply be asked:

```
$ nm libqemu-system-aarch64.so | grep -iE 'desktop_resize|ui_info'
0000000000a08c74 t vnc_desktop_resize      # server tells client the size changed
0000000000836164 t virtio_gpu_ui_info      # virtio-gpu can be told a size
00000000009e8ad4 T dpy_set_ui_info         # and this is how
```

`vnc_desktop_resize` and no `vnc_desktop_resize_ext`, and no handler for message 251 anywhere. The
negotiation runs one way only: QEMU can announce a resize, and nothing on the phone can request
one. Sending a `SetDesktopSize` would have been read as an unknown message type and desynchronised
the stream — a worse failure than the letterboxing.

### What works instead: the guest resizes itself

`virtio_gpu_ui_info` and `dpy_set_ui_info` exist, so the machine *can* change shape; the VNC server
is just not wired to ask it. But the guest can ask itself, and Box already has a channel into the
guest — agentd, which runs as `agent` with `DISPLAY=:0` already set so that `scrot` can see the
session. `x11-xserver-utils` is already installed. So:

- `GuestScreenFit` (app) decides what size the screen should be, from the views showing it.
- `IRuntimeControl.setDisplaySize` carries it to `:computer`, the only process that reaches the VM.
- `GuestDisplayMode` (runtime) builds an `xrandr` invocation and agentd runs it.
- QEMU notices the console changed shape and sends the `DesktopSize` rectangle **that the RFB
  client has always decoded**. The receiving half was already correct and untouched.

**No guest image rebuild.** Everything above is host-side; the guest needed nothing it did not
already have. This was checked before any code was written, by driving `xrandr` by hand in the
guest's own `xterm` through the app — the screen went portrait and stayed drivable.

### Things that were not obvious

- **`virtio-gpu` takes a mode it has never heard of.** It reports `maximum 8192 x 8192` and the
  modesetting driver accepts an invented modeline, so there is no need to pick from a list. The
  timings are made up and consistent rather than real — no cable, nothing clocking these pixels.
- **The output is `Virtual-1` but must not be assumed.** That name comes from the kernel's
  virtio-gpu driver; the script discovers it.
- **agentd refuses a working directory outside `/workspace` and `/home/agent`.** A resize does not
  touch a file, so `/` looked harmless — and was rejected with the message a real escape attempt
  gets, leaving the screen silently the wrong shape.
- **A mode set on an emulated GPU is slow.** 15 seconds looked generous and expired on a freshly
  booted guest; it takes 30–60s cold. It is now retried, because the usual reason it fails is that
  the desktop session had not started yet — `local-agent-desktop.service` restarts every five
  seconds and can lose a race with udev, and a half-started X accepts the connection and then does
  not answer.
- **The soft keyboard is not a window resize.** `BoxApp` applies `safeDrawingPadding`, which
  includes the IME, so opening the keyboard shrank the pane and the guest dutifully followed it to
  1080×1360. The keyboard's inset is now added back before the size is reported: the machine
  follows the window, not the keyboard.
- **Thumbnails must not count.** The task list's live computer row is a surface too, and following
  the largest attached surface naively would resize the guest down to 230px whenever the user left
  the computer. A surface under 400×300 is understood as a preview of a screen, not a screen.

### The screen no longer blanks

Opening the computer after a while showed a phone-sized field of black with `Guest disabled
display.` in 8px type in the middle — QEMU's text, not anything Box chose to say. X blanks an idle
screen and drops DRM scanout with it. Blanking exists to save a backlight and this machine has
none, so the same script now turns the screensaver and DPMS off and forces the display on. It lives
there rather than in the image because it then also reaches a box that is already running.

## Cross-cutting, every shape

### The same thing is said twice, in two places, at once

In the `Wide` layout the box's state gets a card in the task list *and* a banner over the
transcript, both reading "Your box is paused", both offering "Open", about six inches apart. In
`Single` these are different screens and never collide; the duplication is a wide-layout artifact
nobody sees on a phone.

Worse with permission. A pending request draws **an inline card in the transcript with Deny/Allow,
and simultaneously a bottom sheet with Deny/Allow** over the composer. Two live copies of the same
decision, with the sheet covering the "Answer the request above." hint that points at the other
one.

*Better:* one of them owns the decision per layout. The sheet is right for `Single` — it is
reachable with a thumb and it follows you. In `Wide` the inline card is already in view and in
context, and the sheet should not appear at all. With the sheet gone the composer becomes
"Answer the request above · **Review**", which is very close to the right `Wide` behaviour
already; it just should not take a dismissal to get there.

### "Approving everything" contradicts the screen under it

The banner reads *"Approving everything — Box is not asking before the agent acts"* while a
permission prompt sits directly beneath it asking the user to approve something. Both cannot be
true. Whatever the underlying flag means, as written it describes the app's behaviour, and the
behaviour visibly differs.

*Better:* say what it will do next rather than what it is — and suppress or restate it entirely
while a request is outstanding.

### Two banners stack before any content

"Approving everything" and "Your box is paused" stack above the transcript, so the conversation
starts a fifth of the way down a 1384px-tall pane. Neither is dismissible.

### Every task is called "New conversation"

The list showed two entries, both "New conversation", both "Claude Code · /workspace". Nothing
distinguishes them — not the work, not the time, not the result. With two it is annoying; the
design is for many, and at ten it is unusable.

*Better:* title from the first user message, the way every chat app does, and replace the
duplicated `agent · path` subtitle with what changed or what is waiting.

## Desktop (DeX)

The shape with the most to gain and the least attention so far.

- **The whole selling point renders.** A real Debian desktop with an `xterm` at
  `agent@…:/workspace$`, live, inside the phone, on a 34" monitor. Worth putting in the README —
  there is currently no desktop shot at all.
- **The guest never adapts to the window.** `virtio-gpu-pci,xres=1280,yres=800` is fixed, so the
  desktop is letterboxed into whatever the window is and upscaled — 1280×800 stretched into
  1716×1244, with bars. On a maximised ultrawide it would be far worse. Box's RFB client already
  offers `DesktopSize`, so the negotiation exists; nothing drives it. *This is the single biggest
  desktop-mode win available.*
- **An empty pane the size of a laptop screen says "Pick a task"** — two words and an icon in
  1716×1384. On desktop this is where the computer itself, or the most recent conversation, should
  be.
- **The composer is live while nothing is selected.** "Ask Box anything…" is enabled and focusable
  under a pane that says "Pick a task". Either typing starts a new task — in which case say so —
  or it should be disabled.
- **"Box" is the header twice**, in the sidebar and again over the transcript. The right-hand one
  should name the selected task.
- **"New task" is bottom-left**, as far from the list it appends to as the window allows, and on
  the opposite side from the composer.
- **Keyboard affordances already exist and are good.** The permission sheet advertises
  "Tab to choose · Enter to confirm · Esc to leave it unanswered", and Esc genuinely works. That
  instinct — a desktop window is driven from the keyboard — should spread to the rest of the app.

### Smaller, fixable

- The paused card clips its own third line: "Your box is paused / **Just as you left it**" is cut
  off mid-descender. The two-line variants ("Nothing is running.", "~7s left") fit, so it is the
  three-line case only.
- Both the inline permission card and the sheet truncate the command with an ellipsis in a
  1716px-wide window. There is room to show it.

## Tablet (unfolded, 2184×1968)

The `Wide` layout is the one that most looks like a finished product: list on the left with the
computer as its first row, conversation filling the rest, composer under it. The computer row
carries a genuinely live thumbnail — the `xterm` inside it is legible at 230px wide — which is the
contract in §4 of `ui-contract.md` doing exactly what it promises.

Two things specific to this shape:

- **The letterboxing is far worse than on desktop.** The inner screen is nearly square (1.11) and
  the guest is fixed at 16:10, so the desktop occupies a band across the middle with roughly 45% of
  the panel black. The same root cause as the DeX note above, but the aspect mismatch is much
  larger here, and this is the shape a Fold user is in most often.
- **The "↓ Latest" pill overlaps the message it is floating over**, landing mid-sentence and hiding
  words ("Pretty much a fresh box. F… 'm sitting:"). It needs to clear the text — inset above the
  composer, or with the transcript padded to reserve its lane.

Moving Box between displays works well and is worth knowing: `am start --display` carries the
computer view across with its state intact, terminal scrollback and all.

## Phone (cover screen, 1080×2520)

The `Single` layout is the healthiest of the three, and it is where most of the wide-layout
complaints above simply do not arise: one banner rather than two stacked, no duplicated box-state
card, no permission asked twice. The conversation pushes over the list and the back arrow brings it
back, exactly as §4 of `ui-contract.md` describes. The task list drops the box-state card entirely
while the box is running and leads with the Computer row and its live thumbnail, which is the right
call.

Worth knowing when folding: **the logical display ids swap.** Open, the inner panel is display 0
and the cover is 1; shut, the cover becomes 0. Anything that remembers a display id across a hinge
event is remembering the wrong screen — which is why `device-shots.sh` resolves shapes by panel
type and geometry every time rather than caching.

Two problems, one of them the worst instance of a problem seen everywhere:

- **The computer is nearly unusable on a phone, and this is the shape the product leads with.** The
  guest's fixed 1280×800 landscape desktop is fitted whole into a portrait pane, so it lands at
  1080×675 inside roughly 2190px of height: **the desktop is about 31% of the pane and the rest is
  black bar**, and the terminal text inside it is far too small to read. It is the worst of both
  options — small enough to be illegible *and* wasteful of the screen. Fitting the width and
  letting the user pan, offering landscape, or resizing the guest would each beat this.
- **The "↓ Latest" pill overlaps the transcript here too**, and it costs more on a narrow column:
  it sat across a full line of the agent's answer, hiding several words rather than a couple.

Also good, and worth keeping: the header adapts properly — the pill shortens from
"Agent is working · Take over" to "Take over", and the panel buttons drop their labels for icons.

## The bigger changes, in the order I would do them

1. ~~**Let the guest's screen follow the window.**~~ **Done — 12 Aug 2026.** Not by the route this
   proposed, which turned out to be closed; see *The guest's screen now follows the window* below.
2. **Decide who owns a permission request per layout.** Today `Wide` shows the inline card and the
   bottom sheet at once, with the sheet covering the hint that points at the card. Pick one per
   layout rather than rendering both.
3. **Name tasks after their work.** "New conversation · Claude Code · /workspace", repeated, is not
   a list. Title from the first message; use the subtitle for state, not for a path that never
   varies.
4. **Give the desktop shape its own home surface.** "Pick a task" in a 1716×1384 pane is a phone
   empty state that got stretched. On desktop the computer itself is the obvious default content.
5. **Make the banners a single, ranked strip.** Two undismissible stacked banners, one of which
   contradicts the screen beneath it, is where the transcript starts today.

## Evidence

In `docs/assets/screenshots/device/`, all taken on the Fold 7:

| File | What it shows |
| --- | --- |
| `dex-computer.png` | The Debian desktop in DeX on a 34" monitor — the README shot |
| `dex-driving.png` | Take-over working after the fix: a typed command and its output |
| `dex-tasks.png` | The `Wide` task list, and both stacked banners |
| `dex-opening.png` | "Opening your box · ~7s left" |
| `dex-permission-asked-twice.png` | The inline card and the sheet, both live at once |
| `tablet-computer.png` | The letterboxing on the nearly-square inner screen |
| `tablet-wide.png` | `Wide` on the inner screen, and the "↓ Latest" pill over the text |
| `phone-tasks.png` | `Single` task list: computer row, live thumbnail, no box-state card |
| `phone-conversation.png` | `Single` conversation, and the "↓ Latest" pill over a full line |
| `phone-computer-letterboxed.png` | The computer on a phone, before — ~70% of the pane is black bar |
| `phone-computer-fitted.png` | The same machine after: portrait, edge to edge, terminal legible |

Two of these (`dex-permission-asked-twice.png`, `tablet-wide.png`) contain real conversation text
from the device they were taken on. They are fine as evidence in this document; check them before
any of them goes anywhere public.

## Credentials survive an image update — and the reason the last one did not

Your own conversation in the app asked whether the login credential would survive this update. It
does, and the mechanism is worth writing down because the previous answer was different.

Credentials live in `/workspace/.config/` (`CREDENTIALS` in `box-claude-harness.mjs`), on the
workspace disk. `/workspace` is never replaced — not by an update, not by a different image — so an
image swap keeps them. This deploy exercised exactly that: the system disk, kernel and initrd were
replaced, `/workspace` was not.

The reason one was lost before is that `tools/deploy.sh --image` used to imply `--wipe`, because a
rebuilt image was otherwise silently ignored. Wiping took `/workspace` with it, and the credential
directory with that. That coupling was removed on 12 Aug 2026; `--image` no longer wipes.

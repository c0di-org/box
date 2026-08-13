# Stock runtime

The stock flavor uses an APK-installed, ARM64 `qemu-system-aarch64` build inside
`RuntimeService`'s `:computer` process. It starts only after the user asks for a
local-computer task.

The guest image describes itself. `guest/image/out/image.json` gives it an id, a version
derived from its payload digests, and a payload list keyed by role; the app build stages
that manifest into the APK alongside the payloads, and provisioning installs each payload
by role rather than by filename. Two things follow: a device can tell whether the image in
the APK is the one it is already running, and more than one image can exist on it.

At provision time the app verifies each payload against the digest the manifest gives it,
and installs it under a key taken from the image's id:

| Path | Role | Replaced by an update? |
| --- | --- | --- |
| `images/<id>/kernel`, `images/<id>/initrd.img` | Direct-boot files QEMU only reads | Yes |
| `images/<id>/installed.json` | The manifest of the last completed install | Yes |
| `disks/<id>/system.qcow2` | Guest OS changes and installed packages | Yes, when the version differs |
| `disks/<id>/workspace.qcow2` | Projects, repositories, and generated work | **Never** |

That last row is the invariant the rest is built around: the workspace is the user's Linux
machine, and nothing — an app update, a new image, or an explicit reinstall — replaces it
once it exists. The version is what lets the other three be replaced without it. Versions
of one id share a directory, so an update is an update; different ids do not, so a second
image gets its own workspace rather than inheriting somebody else's. The rules live in
`GuestImageInstall` and `GuestImageLayout`, which carry no Android dependency and are
covered by JVM unit tests.

QEMU's `virt` board uses TCG. QMP and the `agentd` virtio-serial channel bind Unix
sockets below `filesDir/computer/sockets`; no control endpoint is exposed on the
network. `QemuCommand` is the single source of truth for that launch contract, and takes
the image's resolved paths rather than knowing any filenames itself.

The `agentd` channel speaks [protocol v2](../protocol/agentd-v2.md): length-prefixed
binary frames multiplexing many logical streams over that one port, so a command's
output streams as it happens, a stream can be cancelled, and a chatty process
throttles itself against the app instead of filling its heap. `AgentdClient` owns
the protocol vocabulary; `AgentdConnection` owns framing and flow control and has no
Android dependency, so both are covered by JVM unit tests.

## The machine has to be given hands and a screen

Two things about `-M virt` cost a day each on the device, and neither logs a warning.

**It registers no input devices at all.** QEMU's VNC server delivers RFB pointer and key events
into QEMU's input subsystem, which routes them to registered devices; on `virt` there is no PS/2
controller and no USB controller unless one is asked for, so the events simply stop. "Take over"
switched on correctly and then silently discarded every click. `QemuCommand` therefore adds:

```
-device virtio-tablet-pci,romfile=
-device virtio-keyboard-pci,romfile=
```

Tablet rather than mouse because the coordinate on the wire is absolute — RFB carries absolute
positions, and `GuestPointer` integrates a finger's trackpad deltas into one on the host side,
where the size of a fingertip and of the pane are both known. A `virtio-mouse` reports relative
deltas and would need an acceleration model inside a device driver that knows neither.

**Adding a device invalidates every existing snapshot.** `-loadvm` restores memory into a machine
that has to match the one it left, device for device, and it fails *after* rolling the disks back.
Comparing image identity alone would have turned every paused box into a failed reopen, so
`QemuCommand.machine()` fingerprints the launch command itself and the suspend note records it —
derived rather than a constant somebody has to remember to bump, because the failure it prevents
is silent.

## The guest resizes its own screen

The desktop was a fixed 1280×800 letterboxed into every window: about 31% of a cover screen, with
the rest black bar. The obvious fix is closed. RFB's `DesktopSize` (-223) is server-to-client
only; a client asks with `SetDesktopSize` (251) and is answered with `ExtendedDesktopSize` (-308),
and **this build's QEMU implements neither**. The prebuilt `libqemu-system-aarch64.so` is 5.1.0
and unstripped, so it can be asked directly:

```
$ nm libqemu-system-aarch64.so | grep -iE 'desktop_resize|ui_info'
0000000000a08c74 t vnc_desktop_resize      # server tells client the size changed
0000000000836164 t virtio_gpu_ui_info      # virtio-gpu can be told a size
00000000009e8ad4 T dpy_set_ui_info         # and this is how
```

`vnc_desktop_resize` with no `_ext`, and no handler for message 251 anywhere. Sending one would
have been read as an unknown message type and desynchronised the stream.

So the guest is asked to resize itself instead, over the channel Box already has. `GuestScreenFit`
decides the size from the attached surfaces, `IRuntimeControl.setDisplaySize` carries it to
`:computer`, `GuestDisplayMode` builds an `xrandr` invocation and agentd runs it — and QEMU then
announces the new console shape over the `DesktopSize` rectangle the RFB client always decoded.
Entirely host-side; the guest needed nothing it did not already have.

Six things about it are not obvious:

- **`virtio-gpu` accepts a mode it has never heard of.** It reports `maximum 8192 x 8192` and the
  modesetting driver takes an invented modeline, so there is no list to pick from. The timings are
  made up and consistent — no cable, nothing clocking these pixels.
- **The output is `Virtual-1` but must not be assumed.** That name comes from the kernel's
  virtio-gpu driver; the script discovers it.
- **agentd refuses a working directory outside `/workspace` and `/home/agent`.** A resize touches
  no file, so `/` looked harmless, and was rejected with the message a real escape attempt gets —
  leaving the screen silently the wrong shape.
- **A mode set on an emulated GPU is slow**: 30–60 s cold. It is retried, because the usual reason
  it fails is that `local-agent-desktop.service` lost a race with udev, and a half-started X
  accepts the connection and then does not answer.
- **The soft keyboard is not a window resize.** `BoxApp` applies `safeDrawingPadding`, which
  includes the IME, so opening the keyboard shrank the pane and the guest followed it. The
  keyboard's inset is added back before the size is reported: the machine follows the window, not
  the keyboard.
- **Thumbnails must not count.** The task list's live computer row is a real surface at a real
  size, so following the largest attached one naively resized the guest down to 230px whenever the
  user walked back to their tasks. A surface under 400×300 is a preview of a screen, not a screen.

The same script turns the screensaver and DPMS off. X blanks an idle screen and drops DRM scanout
with it, so opening the computer after a while showed QEMU's own `Guest disabled display.` in 8px
type on black. Blanking exists to save a backlight and this machine has none. It lives in the
script rather than in the image so that it also reaches a box that is already running.

**Still open:** the *screen* follows the window, and the windows on it do not. X clients keep the
size they were mapped at, so a terminal that filled a 1280×800 desktop is a small window in the
corner of a 1968×1960 one. Mapping the first terminal maximised would put the space to use; it is
a guest-image change, which the screen-size work deliberately needed none of.

## One VM run per process

QEMU is linked into `:computer` and entered through `qemu_init`, which is
**once-per-process**. It writes globals that `qemu_cleanup` does not undo, and the
first of them asserts `!exec_dir[0]` — so a second run in the same process is not a
restart, it is a `SIGABRT` that takes `:computer` with it. Stopping the computer and
starting it again is an ordinary thing to do, and it crashed until this was handled.

So the process is the unit that gets consumed. `NativeQemu.hasRun()` reports whether
this process has spent its run, `QemuProcessLifetime` holds the rule, and
`RuntimeService` retires the process once the VM has exited — `START_NOT_STICKY`
means the next start simply arrives in a fresh one. The native layer also refuses a
second `qemu_init` outright, so a mistake here surfaces as an error rather than a
crash.

The UI keeps a binding to `:computer` for the whole time the computer is meant to be
alive, not only once it is `Ready`. That binding is the only notice Box gets when the
VM process dies mid-startup, which is when it is most likely to; `ComputerLoss`
decides whether a disconnect is an expected retirement or a failure worth showing.

## Putting the box away

A cold boot is the single largest cost in the product: the guest is fully emulated
ARM64 under TCG, and on a Galaxy Z Fold 7 it takes **86–116 s** from QEMU launch to a
ready agent, nearly all of it the guest waiting on emulated udev. Until now that price
was paid every time the box was not already running, which put "close it when it is
idle" in direct opposition to "have it there when you want it".

`suspendRuntime()` writes the guest's memory into its own qcow2 with `savevm` and
quits; `start()` finds the note the save left behind and hands the snapshot to a fresh
QEMU with `-loadvm`. Measured on the same device:

| | |
| --- | --- |
| Cold boot to ready agent | 86.4 s, 116.4 s |
| Save (`savevm`, ~430 MB of guest memory) | 0.56–3.6 s |
| Reopen to ready agent | 0.94 s, 0.97 s, 0.99 s, 1.07 s |

The last of those was measured after churning 6 GB through the page cache, so the
number is not an artefact of the snapshot still being warm.

Three things make this fit the process rule above rather than fight it. QEMU 5.1 —
the build in this APK — has no QMP `savevm`, so both operations go through
`human-monitor-command`; `snapshot-save` arrived in 6.0. The snapshot lives *inside*
the system disk, so it is invalidated by anything that replaces that disk, and
`SuspendedVm` records which image it belongs to for exactly that reason. And the note
is consumed *before* QEMU is handed it, never after: `loadvm` reverts the disks to the
snapshot, so a note that outlived a failed load would eventually roll `/workspace`
backwards. Booting cold is always safe; loading twice is not.

What does not survive is an agent that was mid-task. agentd kills every child when its
host disconnects, and QEMU tells a restored guest that the host it remembers is gone —
so the teardown happens either way. `quiesceGuest()` therefore does it deliberately,
before the snapshot, on a healthy guest with a real clock. Files the agent had already
written are unaffected; the disks and the guest's memory are captured together.

`RuntimeService` puts an untouched box away by itself after 15 minutes, counting a
running agent session as activity however quiet it looks. That timer is only defensible
because of the table above: it is allowed to act without asking precisely because being
wrong costs about a second.

## The cold start that is left

The table above stops at "ready agent", and until recently that was the end of the story.
It is not: after the guest is up, the *first task* pays a second start-up that nothing in
this document was measuring — the Claude Code CLI coming to life inside the emulation.

For a while that cost looked enormous. An agent running inside the box measured its own
start at **~11.5 minutes**, and a bare `claude --version` at **1 m 5 s**. Both numbers were
real and both were misleading: they were taken while eight harnesses were starting at once,
because opening the box opened every conversation in the list. With that fixed, the same
commands on the same phone:

| | During the eight-way fan-out | One session, idle box |
| --- | --- | --- |
| `claude --version` | 65 s | **4.35 s** |
| SDK import (295 MB) | 111 s | **9.95 s** |
| Send to reply, warm session | — | **4.7 s** |
| Guest load average | 14.94 | 0.28 |

So the CLI is not slow here in the way it appeared to be. What remains is genuinely a
cold-cache cost, and it is worth being precise about when it is paid.

**Opening a task already pre-warms.** `query()` is called at harness start, not behind the
first prompt: measured on a cold-booted box, a `claude` process exists within five seconds
of opening a task and before any message is typed. The window in which the CLI warms is
therefore the window in which the person is reading the screen and typing, which is the
best place for it. Nothing needs adding here — this was checked precisely because it looked
like an obvious thing to add.

**What is not covered is the boot itself.** Between tapping Open and opening a task,
nothing warms: 145 s of measured boot during which the disk holding the SDK and the CLI is
never read. The first task opened after that pays for pulling ~300 MB off an emulated
virtio disk into a cold page cache.

**And an image update always lands there.** `RuntimeStorage` clears the suspend note when a
provisioning plan is non-empty, for the good reason given above — the snapshot lives inside
the disk being replaced. But the consequence is that the slow path is not the rare one: it
follows every Box update that carries a new guest, which is exactly when someone has just
installed something and is looking at it.

### What a snapshot does and does not carry

Worth stating plainly, because it decides what a fix can look like. `quiesceGuest()`
deliberately reaps the guest's children before saving, so a restored box has **an idle
agentd and no CLI** — a warm process from days ago is not something to want back, and
agentd would kill it on reconnect regardless. But `savevm` writes the guest's memory, and
that memory **includes the page cache**. The expensive half survives; the disposable half
does not. That is the right split, and it is already what the code does.

### Proposal: seed a snapshot after provisioning, rather than after use

If a snapshot carries the page cache, one can be made deliberately instead of only as a
side effect of the person closing their box. After an image is provisioned — a first
install, or an update — Box could, once and in the background: boot the guest, read the SDK
and the CLI binary through the page cache, `savevm`, and quit. The next open is then the
~1 s restore in the table above, onto a warm cache, rather than 145 s onto a cold one.

Nothing new is needed to do it. `suspendRuntime()` already saves and quits; `SuspendedVm`
already records the image identity and machine that make a note refusable. The change is
*when* it is called, not what it does.

Open questions, none of them answered yet:

- **When to run it.** A background boot costs battery and heat on a phone that just took an
  app update. Opportunistic — charging, screen off — is the obvious answer and the obvious
  place to get it wrong, because "charging and idle" on a phone that is never plugged in
  means the seeding never happens and the cold path is still the normal one.
- **What counts as warm.** Reading the payloads with `cat > /dev/null` is cheap and honest;
  running `claude --version` additionally exercises whatever the binary touches on start.
  Neither has been measured against a cold first task, and the difference between them is
  the whole question of whether this is worth doing.
- **Whether the snapshot can be built rather than made.** Shipping a snapshot inside the
  image would remove the on-device boot entirely, but a snapshot is tied to the QEMU build
  and the machine type that produced it — `SuspendedVm.machine` exists because that
  mismatch is already a real failure. This is probably a no, and should be ruled out
  explicitly rather than left as an idea.
- **Cost on disk.** The saved memory is ~430 MB inside the system qcow2, which is a real
  fraction of a phone's free space to spend on a box the person has not opened yet.

The measurement that would settle whether any of this is worth building is one nobody has
taken: **time from a freshly provisioned image to a first reply, cold**, against the same
path with a seeded snapshot. Everything above is arithmetic and mechanism until that number
exists.

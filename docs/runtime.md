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

### The measurement, taken

It has now been taken, on the same Galaxy Z Fold 7, against a guest image rebuilt from
`main` and provisioned onto the phone in the same operation. Every number below comes
from the runtime log and the session `.ndjson`, not from watching the screen.

**Provisioning is not the cost.** Replacing the installed image with a new 947 MB one
took **2.1 s**, and the open that followed reached a ready agent in **120.7 s**. A
second cold open of that same image — nothing to provision, no snapshot to load —
reached Ready in **94.9 s**. An image update therefore costs about two seconds more
than any other cold start. What makes it expensive is not the install; it is that the
install invalidates the snapshot, and so guarantees a cold start.

The rest of the path, end to end, on that freshly provisioned image:

| | |
| --- | --- |
| Tap Open → guest Ready | 94.9 s |
| Open a task → harness process alive | 35.3 s |
| SDK import (295 MB, cold page cache) | 67.6 s |
| Open a task → `session_started` | 102.9 s |
| Send → the harness receives the message | 39.5 s |
| Send → first reply | 283.7 s |
| **Tap Open → first reply** | **455 s — 7 min 35 s** |

So the answer is not tens of seconds. It is seven and a half minutes, on a box that had
just been updated, for a two-word greeting.

The wait is at least legible now. Through it the transcript read `Starting Claude
Code…`, then `Starting session…`, then `Thinking…` — the labels added for exactly this
window, confirmed on a device for the first time here. They render as
`Starting Claude Code……`, because the label already ends in an ellipsis and the view
appends its own; worth one character of a fix.

**The largest term, though, is not the one this section was about.** A probe taken at
the end of that run found **three `node` and three `claude` processes** inside a guest
with two emulated cores. Box had started a harness for every task in the list — the two
that already existed and the one just opened — all within seven seconds of each other,
about forty seconds after the guest became ready, and without anyone opening the two old
ones. The preceding run behaved identically: no task was opened at all, and two
harnesses started anyway.

That is the fan-out from the top of this section, in a narrower form. Not "opening the
box opens every conversation", but "the box becoming ready starts a harness for every
conversation". It is why the SDK import above reads 67.6 s against a warm single-session
9.95 s, and why a two-word reply spent 244 s in its model turn against a warm 4.7 s.
Those figures are three-way contention as much as they are cold cache, and no reading of
this table should treat them as the cost of being cold alone.

### So: is the seeded snapshot worth building?

Not first, and not yet. The measurement does not rule it out — the test set above was
whether the cold first reply is only tens of seconds, and at 455 s it plainly is not.
But the measurement does price the fix, and the price is poor next to what sits beside
it.

A seeded snapshot removes the boot and warms the page cache, and does nothing else. It
cannot carry a CLI, by deliberate design: `quiesceGuest()` reaps the guest's children
before saving. So against the 455 s it would take out the 94.9 s boot outright, and pull
the 67.6 s SDK import down towards its 9.95 s warm figure. Call it 150 s, generously —
leaving five minutes, and leaving the two largest remaining terms untouched.

Not starting three harnesses to open one task costs nothing on disk, needs no
opportunistic charging window, and removes contention from every line of that table
including the model turn. It also has to come first on its own merits: a snapshot
restored into a three-way fan-out is 430 MB of warm memory spent on the same congestion,
and until it is fixed there is no way to measure what the snapshot alone would buy.

The order is therefore: fix the fan-out, re-measure this table, and only then decide
whether the boot that remains is worth 430 MB of somebody's phone. The open questions
above keep their force — they are simply not the next thing to answer.

### The fan-out, fixed

It was not a missing guard. The guard was there and was being defeated. The outbox does
two jobs — it queues what could not be delivered, and it is how `runtimeStateReceiver`
decides which sessions were *waiting on the box* and must be given a harness. A turn
earns that. A standing setting does not, and the UI broadcasts one: `setViewport` runs as
soon as the window has measured itself, on every launch, over every record. So every
restored conversation began life with a viewport command in its outbox, and "was anyone
waiting" became true of all of them.

Nothing needed queueing: `onAttached` already states the mode and viewport to every
harness ahead of anything else it reads. Re-measured on the same phone, with the same
three tasks in the list:

| | Before | After |
| --- | --- | --- |
| Guest processes after opening the box | 3 `node`, 3 `claude` | 1 `node`, 1 `claude` |
| SDK import, cold page cache | 67.6 s | 44.0 s |
| Send → first reply | 283.7 s | 4.05 s |

The one process that starts is the conversation being looked at, which is the intended
behaviour. The two figures are not one experiment: the import is like-for-like — cold
cache, one session rather than three — while the reply also had a warm CLI behind it, and
is here only to show that a single session reaches the 4.7 s warm figure the contention
was hiding.

So the boot is now most of what is left, rather than a fifth of it, and the question this
section opened with is live again on much better terms. What has changed alongside it is
that keeping a box is now a choice the user can see: "Open faster" saves an idle box
after three minutes instead of fifteen, or closes it instead of saving, and says that a
saved copy costs about 430 MB. A seeded snapshot would extend that same setting to the
one open it cannot help with — the first after an update — and it should be measured
against this table rather than the old one.

## Current implementation state

The storage/verification, agent protocol, guest-image source and isolated service
are implemented. The remaining blocker for a real device boot is a reproducible
Android NDK build of QEMU and its native dependencies (GLib, pixman and libslirp)
as APK-installed libraries. We must not substitute a writable-storage executable
or a Termux dependency for it.

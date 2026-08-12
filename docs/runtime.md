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

## Current implementation state

The storage/verification, agent protocol, guest-image source and isolated service
are implemented. The remaining blocker for a real device boot is a reproducible
Android NDK build of QEMU and its native dependencies (GLib, pixman and libslirp)
as APK-installed libraries. We must not substitute a writable-storage executable
or a Termux dependency for it.

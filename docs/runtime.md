# Stock runtime

The stock flavor uses an APK-installed, ARM64 `qemu-system-aarch64` build inside
`RuntimeService`'s `:computer` process. It starts only after the user asks for a
local-computer task.

At provision time the app verifies the signed manifest hash for `base-system.qcow2`,
then creates two app-private mutable qcow2 files:

| Disk | Purpose |
| --- | --- |
| `system-overlay.qcow2` | Guest operating-system changes and installed packages |
| `workspace.qcow2` | Projects, repositories, and generated work |

The verified asset set includes the Debian kernel and initrd for direct boot. QEMU's `virt` board uses TCG. QMP and the `agentd` virtio-serial channel bind Unix
sockets below `filesDir/computer/sockets`; no control endpoint is exposed on the
network. `QemuCommand` is the single source of truth for that launch contract.

The `agentd` channel speaks [protocol v2](../protocol/agentd-v2.md): length-prefixed
binary frames multiplexing many logical streams over that one port, so a command's
output streams as it happens, a stream can be cancelled, and a chatty process
throttles itself against the app instead of filling its heap. `AgentdClient` owns
the protocol vocabulary; `AgentdConnection` owns framing and flow control and has no
Android dependency, so both are covered by JVM unit tests.

## Current implementation state

The storage/verification, agent protocol, guest-image source and isolated service
are implemented. The remaining blocker for a real device boot is a reproducible
Android NDK build of QEMU and its native dependencies (GLib, pixman and libslirp)
as APK-installed libraries. We must not substitute a writable-storage executable
or a Termux dependency for it.

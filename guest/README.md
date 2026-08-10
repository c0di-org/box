# Guest image

`build-image.sh` is the reproducible base-system image builder for an ARM64 Linux
CI runner. It creates Debian Bookworm VM data, a non-root `agent` account, and
the private `agentd` service on QEMU's virtio serial port.

The local Mac is used to test an image with QEMU; release images are built in a
locked ARM64 CI container and published with a versioned manifest and SHA-256.
Mutable `system-overlay.qcow2` and `workspace.qcow2` disks are created on the
Android device during provisioning and are never replaced by base-image updates.

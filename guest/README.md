# Guest image

`build-image.sh` is the reproducible base-system image builder for an ARM64 Linux
CI runner. It creates Debian Bookworm VM data, a non-root `agent` account, and
the private `agentd` service on QEMU's virtio serial port.

`agentd/agentd.py` is the whole guest control service, deliberately one file so the
entire host-facing attack surface can be audited in one place. It speaks
[protocol v2](../protocol/agentd-v2.md) and runs as the unprivileged `agent` user on
a private virtio-serial port. `tests/test_agentd.py` drives the real service over a
socketpair, so streaming, cancellation, PTYs and backpressure are covered without a
VM:

```bash
python3 -m unittest discover -s guest/tests
```

The local Mac is used to test an image with QEMU; release images are built in a
locked ARM64 CI container and published with a versioned manifest and SHA-256.
Mutable `system-overlay.qcow2` and `workspace.qcow2` disks are created on the
Android device during provisioning and are never replaced by base-image updates.

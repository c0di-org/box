# Guest image

`build-image.sh` builds the guest: the ARM64 Debian Bookworm system disk that ships
inside the APK and boots as the user's Linux box. It creates the VM data, a non-root
`agent` account, and the private `agentd` service on QEMU's virtio serial port.

`agentd/agentd.py` is the whole guest control service, deliberately one file so the
entire host-facing attack surface can be audited in one place. It speaks
[protocol v2](../protocol/agentd-v2.md) and runs as the unprivileged `agent` user on
a private virtio-serial port. `tests/test_agentd.py` drives the real service over a
socketpair, so streaming, cancellation, PTYs and backpressure are covered without a
VM:

```bash
python3 -m unittest discover -s guest/tests
```

## Building the image

`build-image.sh` needs an ARM64 Debian environment with `mmdebstrap`, `e2fsprogs`,
`qemu-utils` and root. Don't try to satisfy that on the Mac directly — `build-container.sh`
already provides it:

```bash
./guest/build-container.sh
```

On Apple Silicon the `linux/arm64` container is native, not emulated, so a full
rebuild is roughly 5–10 minutes on a warm apt cache. Docker Desktop must be running.
Output lands in `guest/image/out/`:

| file | what it is |
| --- | --- |
| `image.json` | what this image is: id, version, payload roles, what it contains |
| `base-system.qcow2` | the read-only Debian rootfs (~425 MB) |
| `kernel`, `initrd.img` | extracted from the guest's `linux-image-arm64` |
| `workspace.qcow2` | an empty ext4 workspace disk |
| `*.sha256` | regenerated on every build; the app build verifies against these |

**Rebuild whenever `guest/agentd/agentd.py`, `guest/packages.txt` or anything in
`guest/systemd/` changes.** The image carries a copy of `agentd.py`, so a stale image
boots an old service against a new client — which fails as a protocol error at
handshake, not as an obvious build error.

### What `image.json` is for

Nothing else names these files. `app/build.gradle.kts` reads the manifest for its payload
list, and `RuntimeStorage` installs each payload by *role* — `kernel`, `initrd`, `system`,
`workspace` — rather than by position in a list that three places had to agree on by hand.

The `version` is a hash of the four payload digests, which makes it the answer to the one
question a device with a box already on it needs to ask: is this the same image? It has to
be derived rather than bumped, because the failure it fixes is a forgotten step — a version
somebody had to remember to raise would be forgotten in exactly the loop this repairs. It
carries no timestamp, so two builds of the same tree agree, the same rule `BUILD-INFO`
follows.

`id` is the machine rather than the build of it, and it is the directory an image installs
into on the device. Two ids can sit on a phone at once, each with its own `/workspace`;
two versions of one id are the same machine updated, and share it.

`contains` is what an image advertises — a desktop, which harnesses and where. Nothing
reads it yet. It is written now so that images already on devices can answer the question
when something finally asks.

`app/build.gradle.kts`'s `prepareStockGuestAssets` re-hashes every payload and fails the
build on any mismatch with its `.sha256` *or* with the digest in `image.json`, so a
half-copied or stale image cannot silently ship, and the two halves of a build cannot come
from different images. That check is the reason the build script regenerates the checksums
itself rather than leaving them to be written by hand.

`guest/image/out/` is gitignored and must stay that way — `base-system.qcow2` is well
over GitHub's 100 MB limit.

### Why the image fights systemd's default timeouts

QEMU runs under TCG on the phone, so everything in the guest is emulated and slow in a
way no CI runner reproduces. `systemd-udev-trigger` needs about 92 seconds to coldplug
the virtual hardware — just past systemd's 90-second default device timeout. When it
loses that race, `dev-vdb.device` is considered failed even though the kernel
enumerated the disk at 15 seconds, `workspace.mount` fails with it, and
`local-agentd.service` never starts because it `Requires` the mount. The boot looks
completely healthy on the console right up to the point where nothing is listening.

The builder therefore sets `DefaultDeviceTimeoutSec=300s`, gives the workspace mount a
matching `x-systemd.device-timeout`, and masks background maintenance that only
competes for emulated CPU during boot (`e2scrub_reap` alone spent 80 seconds of the
first boot). If you add packages that pull in new boot-time units, re-check the boot
console before assuming the image is fine.

To build into a different directory (useful when a Gradle build might be reading the
current one), override `OUT_DIR` with a path inside the mounted workspace:

```bash
docker run --rm --platform linux/arm64 --privileged -v "$PWD:/workspace" \
  -e OUT_DIR=/workspace/guest/image/out-new local-agent-guest-builder
```

On the device, the system disk is replaced whenever the installed version differs from
the one in the APK; the `workspace.qcow2` beside it is created once and is never replaced
by an image update.

# Development

Everything the [README](../README.md) leaves out: building by hand, getting the VM up on
a device, and watching it when it doesn't come up.

## What you need

- JDK 17 and an Android SDK. `tools/deploy.sh` writes `local.properties` itself from
  `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `~/Library/Android/sdk` — which matters in a git
  worktree, where Gradle otherwise fails on a missing SDK rather than finding the one two
  directories up.
- Docker, but only to rebuild the guest image. See [guest/README.md](../guest/README.md).
- An arm64 device. The VM is ARM64 Debian; an x86 emulator is not a target.

## The normal cycle

```bash
./tools/deploy.sh
```

Builds the APK, installs it, launches it. `--no-launch` installs only, `--wipe` drops the
installed guest first, and `--image` rebuilds the guest image and implies `--wipe`.

**Use `--image` whenever anything under `guest/` changed.** The image carries a copy of
`agentd.py` and the harness, and `RuntimeStorage` installs `base-system.qcow2` with
`preserveExisting=true` on purpose — an app update must never wipe the user's Linux box.
The consequence is that a rebuilt image is silently ignored on a device that already has
one, with nothing reporting that it was skipped.

To drop the guest system disk by hand, keeping the workspace:

```bash
adb shell run-as dev.localagent.workstation.stock rm -f files/computer/disks/system.qcow2
```

## By hand

The guest image is an input to the APK, so it comes first:

```bash
./guest/build-container.sh
```

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleStockDebug
```

```bash
adb install -r app/build/outputs/apk/stock/debug/app-stock-debug.apk
```

## Tests

Three suites, no device needed for any of them. JVM — the app's event model and the QEMU
transport:

```bash
./gradlew :app:testStockDebugUnitTest :runtime-qemu:testDebugUnitTest
```

The guest harness is a Node program covering the event protocol and the sign-in handshake:

```bash
node --test guest/tests/*.mjs
```

And `agentd` is Python, driven over a socketpair so streaming, cancellation, PTYs and
backpressure are covered without booting anything:

```bash
python3 -m unittest discover -s guest/tests
```

There is no CI, and no instrumented or UI tests.

## Starting the VM on a device

Normally: tap **Set up** on a fresh install, then **Start**. There is no plain adb route
in — `RuntimeService` is not exported, so `am start-foreground-service` against it fails
with *"Requires permission not exported from uid"*.

Debug builds compile in `VmProbeActivity`, which exists only because `am startservice` is
refused for a background start on modern Android, so a foreground service needs an
Activity to stand behind it:

```bash
adb shell am start -n dev.localagent.workstation.stock/dev.localagent.workstation.VmProbeActivity --es runtime_action dev.localagent.runtime.qemu.START
```

`STOP` and `EXEC_PROBE` are the other two actions; `EXEC_PROBE` takes `--es probe_command
'<shell>'` and is ignored unless the build is debuggable. Quote the whole `am` line for the
*device* shell, or it eats `;` and `$()`.

## Watching a boot

```bash
adb logcat -s LocalAgentRuntime:I BoxRuntime:I BoxGuestSerial:D BoxDesktop:D
```

A healthy launch logs `QMP confirmed running guest`, then `Guest agent confirmed ready`,
then `QEMU runtime launch accepted`. `BoxGuestSerial` carries the guest console on debug
builds, in heavily fragmented chunks — reassemble before reading. The other tags worth
knowing are `BoxAgentBackend`, `BoxAgentSession` and `BoxGuestAuth`.

Two failure modes that look like nothing at all:

- **QEMU dies silently.** The prebuilt QEMU is Limbo-patched and logs its own errors
  through `__android_log_print`, not stderr, so a bad option produces an empty stderr
  capture and a process that vanishes. Its stdout and stderr are also written unbuffered
  to a file in app storage.
- **`:computer` disappears.** `adb shell dumpsys activity exit-info` distinguishes a
  deliberate `exit(1)` (`reason=1 EXIT_SELF`) from a crash or the low-memory killer in one
  step. Reach for it before guessing.

## Why it's slow

QEMU runs under TCG, not KVM — Android doesn't grant HYP mode, so an arm64 guest on an
arm64 host is still fully emulated. `systemd-udev-trigger` needs ~92 s to coldplug the
virtual hardware, just past systemd's 90 s `DefaultDeviceTimeoutSec`; losing that race
fails `dev-vdb.device`, then `workspace.mount`, then `local-agentd.service`, while the
console still looks like a clean boot. `guest/build-image.sh` raises the device timeout to
300 s and masks boot-time maintenance units for exactly this reason.

Measured tap-to-Ready on a Galaxy Z Fold 7 (SM-F966U1, Android 16): **~170 s** on the
agentd-v2 image — 171 s cooled, 168 s on a first cold provision, 252 s with the SoC hot
from back-to-back runs. The larger image that carries the desktop measured ~110 s warm and
~4.5 min on a first cold provision. Heat dominates, so record the AP temperature
(`adb shell dumpsys thermalservice`) beside any timing or the comparison means nothing.

## Naming

The Gradle project is still `LocalAgentWorkstation` and the package is still
`dev.localagent.workstation`. Box is the product name; the rename hasn't happened.

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

Builds the APK, installs it, launches it. `--no-launch` installs only, `--image` rebuilds
the guest image first, and `--wipe` drops the installed guest — workspace included —
before installing.

**Use `--image` whenever anything under `guest/` changed.** The image carries a copy of
`agentd.py` and the harness, so a stale one boots an old service against a new client.

`--image` no longer implies `--wipe`. Each image build writes `guest/image/out/image.json`
describing the image, including a version derived from the payload digests, so a rebuild
is a *different image* and the app installs it on the next start: kernel, initrd and system
disk are replaced, and `/workspace` is kept. Only the user's disk is preserved by mere
existence now; the rest is preserved by identity.

That is the fix for a real trap. `RuntimeStorage` preserved the disks by filename, on
purpose — an app update must never wipe the user's Linux box — which also meant a rebuilt
image was silently skipped on any device that had one, with nothing reporting it. The
symptom was a protocol error at the agentd handshake, minutes into a boot, and the only
cure was uninstalling the app and losing the workspace with it.

To reinstall the same image version over itself, still keeping the workspace (debug builds
only — `RuntimeService` refuses it otherwise):

```bash
adb shell am start -n dev.localagent.workstation.stock/dev.localagent.workstation.VmProbeActivity --es runtime_action dev.localagent.runtime.qemu.REPROVISION_IMAGE
```

Or by hand, which does the same thing the long way — the next start reinstalls whatever is
missing:

```bash
adb shell run-as dev.localagent.workstation.stock rm -rf files/computer/images/box-minimal-claude
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

## The mark

The app icon lives in `Marketing/app-icon.jpeg` — three lit faces of a cube, separated by
seams that glow green. Nothing hand-traces it. `tools/gen-icon.py` holds the faces as
polygons in the artwork's own pixel space and writes every place the mark appears:

```bash
python3 tools/gen-icon.py
```

That is the launcher's foreground, background and monochrome layers, the splash icon, the
pre-O fallbacks, the notification silhouette, and `BoxMarkArt.kt`, which is the same
geometry as Kotlin so `BoxMark` draws the icon rather than something that resembles it.
Its output is committed; run it when the artwork changes.

The one thing worth knowing before editing it: a VectorDrawable cannot blur, so the glow
is a stack of round-joined strokes, laid down widest and faintest first, fitted to green
measured perpendicular to a seam in the artwork. The broad far bloom is too faint to be
worth a stroke and is a radial gradient on the icon's background layer instead. Both fall
out of `FALLOFF` and `BANDS` at the top of the script.

One thing that looks like a bug and is not: on a home screen the icon sits inside a pale
ring. That is the launcher, not the artwork. Pixel Launcher shrinks any icon that fills
its whole mask and fills the margin with a lightened sample of the icon's own background,
which is invisible for the white-backed icons around it and obvious for a black one. Paint
the background layer magenta and the ring goes pale magenta with it.

## The screenshots

The pictures in the README are taken, not drawn. Retake all of them after any UI change:

```bash
./tools/screenshots.sh
```

That boots a phone emulator and a tablet emulator, walks a list of scenes on each in both
themes, and writes `docs/assets/screenshots/<device>-<name>-<theme>.png`. Roughly ten
minutes cold, three or four with `--keep` from a previous run. While iterating, narrow it:

```bash
./tools/screenshots.sh --phone --dark --scene permission --keep
```

What it photographs is `UiGalleryActivity`, a debug-only entry point that runs the shell
against canned state. It exists because the interesting screens all need a Linux machine
and an emulator has none. It does *not* hand-write the conversation: it plays the app's
own `FakeAgentBackend` at zero pace and folds the events through the real
`TranscriptBuilder`, so a scene is a position in that script — change the script and the
screenshots change with it. The gallery is also usable by hand, which is often the fastest
way to look at a state:

```bash
adb shell am start -n dev.localagent.workstation.avf/dev.localagent.workstation.UiGalleryActivity --es scene permission
```

Three things about the setup are worth knowing before changing it. It builds the **avf**
flavor, whose only relevance here is that it carries no guest image — `stock` will not
build without a 500 MB qcow2 that an emulator could not run anyway. The tablet is a real
second AVD rather than a resized phone, because the layout being proven is chosen from the
window. And the guest's desktop is left blank, because an emulator has no VM to draw — the
real desktop is photographed on hardware by `tools/device-shots.sh` instead.

Adding a scene means three edits: `SCENES` and `GalleryModel.enter` in
`UiGalleryActivity.kt`, and a line in `SHOTS` in the script.

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

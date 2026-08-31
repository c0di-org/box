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
That includes `guest/agent-conventions.md` — the agent's instructions are baked in, so a
wording change there needs a full image build like any other guest file.

**After `--image`, wait before touching the app.** The first start provisions the new
image and then *seeds* it: it boots the guest, warms the harness, saves a snapshot and
shuts the VM down again. Measured on a Fold 7, 2026-08-14:

```
Seed reached a ready agent in 83410ms
Seed warmed the harness in 231994ms (exit=0)
Seeded a saved box in 324580ms total
```

Five and a half minutes, and the app looks usable throughout. A task started inside that
window dies with the seed VM — *"The computer stopped before this finished."* — which
reads like a crash and is not one. Watch for the `Seeded a saved box` line before sending
anything:

```bash
adb logcat -s LocalAgentRuntime:I
```

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

Assembling a `stock` APK stages the guest image into it and then reads the packaged APK
back to check the image arrived. Both halves are part of `assembleStockDebug`, so a build
that has no image at `guest/image/out/` now fails and says to run `build-container.sh`,
rather than handing back an APK that installs and then reports that no guest image is
installed. The `avf` flavor carries no guest and is unaffected.

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

There are no instrumented or UI tests. All three suites above run again in CI, before a
release is published.

## Releases

Box is distributed through **Library**, which signs it centrally. `.github/workflows/library-unsigned-apk.yml`
builds a deliberately **unsigned** APK and uploads it as an artifact; Library takes that artifact,
signs it, and publishes the GitHub release with a `provenance.json` beside it. There is no tag
trigger and no signing secret in this repository — direct release signing was retired in `0026358`.

```bash
gh workflow run library-unsigned-apk.yml -f version=0.1.4
```

The version input becomes `versionName`; `versionCode` comes from the workflow run number, so it
climbs on its own and a rebuild is always installable over the last one.

Three things about it are worth knowing.

**The guest image is built in CI, not committed.** It is an input to the APK and it is gitignored,
so the workflow builds it exactly the way you would, with `guest/build-container.sh`. That job runs
on `ubuntu-24.04-arm` because the builder is a `linux/arm64` container: native there, against
binfmt emulation on an x64 runner, which is the difference between minutes and the better part of
an hour.

**The APK is over a gigabyte**, because the Linux image is inside it. The `library-guest-image`
artifact is another gigabyte and expires the next day.

**The catalog is polled, not pushed.** Library cuts a `catalog-*` release containing `catalog.json`
a couple of minutes after yours; phones pick it up on their own schedule, which is slower. A
release that has not appeared on a device yet is almost always this, and not a failure.

### You cannot install a local build over a Library one

Local builds are signed with the debug key. Library signs with its own. Android refuses an
in-place upgrade across different signing certificates, and **the only way past it is an uninstall,
which destroys `/workspace` — the agent's home, its credential, and every session log.** So this
matters most in exactly the situation where you are tempted to rush: a device that is misbehaving
and a fix you have built locally.

Check before building anything. `dumpsys` prints `Signature.hashCode()`, and the same number can
be computed from a keystore's certificate:

```bash
adb shell dumpsys package dev.localagent.workstation.stock | grep signatures
keytool -exportcert -keystore ~/.android/debug.keystore -alias androiddebugkey \
  -storepass android -file /tmp/debug.der
python3 -c "h=1
for b in open('/tmp/debug.der','rb').read(): h=(31*h+(b-256 if b>127 else b))&0xFFFFFFFF
print('%08x'%h)"
```

Two matching numbers mean your build can go on that phone. Two different ones mean it cannot, and
finding that out here costs seconds instead of a gigabyte-sized install that fails.

A Library-published APK carries the answer directly: `provenance.json` on the release has
`signingCertSha256`, and two releases sharing it upgrade in place.

### Guest changes cost a release

Anything under `guest/` — the harness, `agentd`, `build-image.sh`, the packages list — is only real
once an image is built and shipped. There is no fast path: the slim-APK trick under
"Starting the VM on a device" strips the payloads, which is precisely what a guest change is.

So the device is the *first* place guest code runs. Ship one or two guest changes per image rather
than a batch, and get a hardware run between them. Five went out in 0.1.3 at once, sessions stopped
starting, and there was nothing to bisect against — the first hardware run had also been the first
integration test. Batching saves a twenty-minute build and can cost an evening.

### When the guest misbehaves

On a release build nothing on the phone can reach the guest from outside: `run-as` refuses,
`allowBackup` is false, and the app's storage is private. Box's own **Terminal panel** (Computer →
Terminal) is the shell, running as `agent` with agentd's environment. Driving it from a laptop:

```bash
adb shell input tap <composer x> <y>
adb shell input text "pgrep%s-af%ssdk-linux-arm64"   # %s is a space
adb shell input keyevent 66
adb shell uiautomator dump /sdcard/t.xml             # read the output back
```

`|` does not survive `input text`, so a piped command silently runs as only its first half — reach
for `pgrep -af`, `ss state established` or `grep -r` instead. `input text` also capitalises some
words, so check the echoed command before believing an error about a path.

The question worth asking first, when an agent session will not start:

```bash
claude -p hi --model claude-opus-5
```

Run by hand in the guest, that separates a broken environment from a harness driving the CLI
wrongly, in one command. It is what ruled out the credential, the model, the network and the binary
in #71 — leaving the SDK streaming path as the only remaining suspect.

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

QEMU runs under TCG, not KVM, and on this hardware there is no alternative to go looking
for. The reason is not that Android withholds a permission: **`/dev/kvm` does not exist on
the Fold 7 at all**, because the SoC's hypervisor is Qualcomm's Gunyah rather than pKVM.
The probe logged at every start says so directly — `open=failed errno=2` — and `/dev/gunyah`
is what is there instead, which no QEMU release has a backend for. AVF is no way round it
either: this device reports `ro.boot.hypervisor.vm.supported` empty, so it runs protected
VMs only and cannot boot our kernel, and it ships Google's Terminal app disabled. The
bootloader is locked with no OEM unlock, so root cannot change any of this. An arm64 guest
on an arm64 host is fully emulated here, permanently.

`systemd-udev-trigger` needs ~92 s to coldplug the virtual hardware at two processors, just
past systemd's 90 s `DefaultDeviceTimeoutSec`; losing that race fails `dev-vdb.device`, then
`workspace.mount`, then `local-agentd.service`, while the console still looks like a clean
boot. `guest/build-image.sh` raises the device timeout to 300 s and masks boot-time
maintenance units for exactly this reason.

Boot cost is dominated by processor *count*, not by translation throughput — see
[GuestSizing.MAX_PROCESSORS] for the measured ladder. Much of the tail is systemd waiting on
device jobs rather than computing (`Job dev-ttyAMA0.device/start running (1min 4s / 5min)`),
which is why more vCPUs cannot help it and each extra one makes it worse.

Measured tap-to-Ready on a Galaxy Z Fold 7 (SM-F966U1, Android 16): **~170 s** on the
agentd-v2 image — 171 s cooled, 168 s on a first cold provision, 252 s with the SoC hot
from back-to-back runs. The larger image that carries the desktop measured ~110 s warm and
~4.5 min on a first cold provision. Heat dominates, so record the AP temperature
(`adb shell dumpsys thermalservice`) beside any timing or the comparison means nothing.

## Naming

The Gradle project is still `LocalAgentWorkstation` and the package is still
`dev.localagent.workstation`. Box is the product name; the rename hasn't happened.

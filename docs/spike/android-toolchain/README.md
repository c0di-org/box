# Spike artifacts: Android build toolchain on aarch64 Linux

Supporting code for **[docs/spike-android-toolchain.md](../../spike-android-toolchain.md)** —
read the findings doc first; it has the verdict, the measurements, and the reasoning.
This directory only holds the things you need to re-run the experiment.

These are **research artifacts, not product code.** Nothing here is wired into the Box
Gradle build (the root `settings.gradle.kts` includes only `:app`, `:runtime-api`,
`:runtime-qemu`), and `hello/` and `hello-kt/` are standalone Gradle builds with their own
`settings.gradle.kts`. Don't import them into the main build.

## Contents

| Path | What it is |
|---|---|
| `repro.sh` | Reproduces the whole spike in an arm64 Debian container |
| `hello/` | Java hello-world app — the Q1 gate. Minimal, no AndroidX |
| `hello-kt/` | Kotlin + AndroidX + Material, `isMinifyEnabled` (R8) — the realistic case |

## Running it

Needs Docker with arm64 support. On Apple Silicon the container is native arm64, which is
the point: it isolates *"does the toolchain exist"* from *"how slow is emulation"*.

```bash
./repro.sh all
```

Or one stage at a time:

```bash
./repro.sh provision   # container + JDK 17 + SDK + Gradle + arm64 aapt2
./repro.sh arch        # shows Google ships x86-64 only            (Q1 evidence)
./repro.sh build       # builds the Java APK                       (the Q1 gate)
./repro.sh negative    # same build minus the aapt2 override -> must fail
./repro.sh kotlin      # Kotlin + AndroidX + R8
./repro.sh tcg         # measures QEMU TCG slowdown                (Q2 evidence)
./repro.sh memory      # memory sweep -> the build's RAM floor     (Q2)
./repro.sh clean
```

`memory` needs `provision` to have run first (it snapshots the provisioned container).

## The one thing that makes this work

Google publishes no arm64 `aapt2`, and Debian has no `aapt2` package at any suite. The
build only succeeds because AGP is pointed at a community arm64 cross-build:

```properties
# hello/gradle.properties
android.aapt2FromMavenOverride=/opt/android-sdk/arm64/aapt2
```

`./repro.sh negative` deletes that line and demonstrates the build failing, which is what
makes the positive result meaningful rather than incidental.

## Two traps for whoever picks this up

**Rosetta can fake a pass.** Docker Desktop on Apple Silicon registers Rosetta as a binfmt
handler, so an x86-64 binary inside a `linux/arm64` container may silently execute — which
would make a "successful" build prove nothing. It doesn't happen here because the arm64
rootfs has no x86-64 dynamic loader, and `./repro.sh arch` asserts that explicitly. If you
reproduce this on an x86-64 host, or with an amd64 rootfs present, re-check this first or
the result is invalid.

**The measurements in the findings doc are not from the guest.** Everything here runs on
native arm64. The on-phone numbers in the doc are extrapolations from measured TCG
multipliers, with roughly a 3× spread. The highest-value follow-up is running `hello/`
inside the real guest on a real device and replacing those estimates with one real number.

**`./repro.sh memory` reports a misleading peak.** It samples cgroup `memory.current`,
which includes reclaimable page cache — so after the SDK download the number tracks the
container's limit rather than the build's demand. This already produced one wrong
conclusion in the first draft of the findings doc (see §4.4, which documents the mistake).
If you re-run the sweep, measure anon from `memory.stat` instead, and warm the dependency
cache first. The Kotlin memory floor is still unmeasured and is the main open question.

## Pinned versions

`repro.sh` pins everything; the same table with sha256s is in the findings doc.

- Debian bookworm arm64, `openjdk-17-jdk-headless` 17.0.20+8-1~deb12u1
- cmdline-tools `15859902`, `platforms;android-36`, `build-tools;36.0.0`
- Gradle 8.14.3 (Debian's `gradle` is 4.4.1 — unusable with AGP 8.x)
- AGP 8.13.2, Kotlin 2.1.0
- arm64 `aapt2` from [Commit451/android-arm-build-tools](https://github.com/Commit451/android-arm-build-tools) `platform-tools-36.0.0`,
  sha256 `7512ff7e…eb9d7f98`, verified at provision time

That last dependency is a single community-maintained repo, and it is the most
load-bearing piece of the whole feature. The findings doc recommends building `aapt2` from
AOSP source in Box's own CI before shipping anything that depends on it.

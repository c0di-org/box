# Spike: Android build toolchain on aarch64 Linux

**Date:** 2026-08-10
**Question:** Can Box build a real APK inside its ARM64 Debian guest, and install it on the host phone?
**Status:** Research spike. No app features were built.

---

## Verdict up front

**Q1 — Does the toolchain exist for aarch64 Linux? YES.** Confirmed by building a real,
signed, installable APK end to end in an arm64 Debian bookworm container. The toolchain
works, but not out of the box: `aapt2` is the single blocker and it must be supplied from
a non-Google source. Everything else is either Java (arch-agnostic) or already in Debian.

**Q2 — How slow is it under emulation? Bad enough to be the real problem.** The toolchain
question turned out to be the easy one. Two findings outrank it:

1. **Memory is tight and the first build is the dangerous one.** A Java hello-world
   builds fine in 1 GB — even in 768 MB — once the Gradle dependency cache is warm,
   peaking around 400 MiB. But the *cold* first build, doing dependency resolution and
   download, OOM-kills the Gradle daemon at 1 GB. A realistic Kotlin/AndroidX app needs
   substantially more. See [§4.4](#44-ram) for what is solid here and what is not — I
   initially reported this as a hard "1 GB can't build anything" wall and that was wrong.
2. **pKVM is not a fallback Box can take.** AVF's APIs are `@SystemApi` behind
   `MANAGE_VIRTUAL_MACHINE`, which AOSP documents as *"not available to third party apps"*
   — privileged-only on Android 14, preinstalled-only on Android 15. A Play-distributed Box
   cannot get hardware virtualization. The plan of "TCG now, AVF later" has no later.

So the honest answer to "is the APK endgame buildable": **yes for a toy app, at roughly
10–15 minutes per clean build; no for a realistic Kotlin app at 20–40 minutes per clean
build and 1.5–3 hours for a release build with R8.** Details and error bars below.

**Install path: your instinct is right.** `PackageInstaller` + `REQUEST_INSTALL_PACKAGES`
is clearly correct over `adb connect`. Reasoning in [§6](#6-install-path).

---

## 1. Method, and one caveat worth stating

Tested in an arm64 Debian container on Apple Silicon, per the brief — native arm64, so it
isolates the toolchain question from emulation performance:

```bash
docker run --rm -it --platform linux/arm64 debian:bookworm bash
```

**Caveat that could have produced a false positive:** Docker Desktop on Apple Silicon
registers Rosetta as a binfmt handler, so an x86-64 binary inside a linux/arm64 container
can silently execute. That would have made a "successful" build meaningless. It did not
happen here, and I verified why: the arm64 rootfs has no x86-64 dynamic loader.

```
$ ls -l /lib64/ld-linux-x86-64.so.2
ls: cannot access '/lib64/ld-linux-x86-64.so.2': No such file or directory

$ $ANDROID_HOME/build-tools/36.0.0/aapt2 version
rosetta error: failed to open elf at /lib64/ld-linux-x86-64.so.2
exit=133
```

x86-64 binaries genuinely cannot run in this container, so every result below is native
aarch64 execution. Anyone reproducing this on an x86-64 host, or with an amd64 rootfs
present, must re-check this or the whole spike is invalid.

---

## 2. Q1 — Toolchain availability

### 2.1 `aapt2` — the crux

**Google ships x86-64 only. Confirmed, current as of today.**

ELF `e_machine` of the binaries in `build-tools;36.0.0` (Linux distribution):

| Binary | Architecture |
|---|---|
| `aapt` | x86-64 |
| `aapt2` | x86-64 |
| `aidl` | x86-64 |
| `zipalign` | x86-64 |
| `split-select` | x86-64 |

AGP does not use the SDK copy anyway — it downloads `aapt2` from Google's Maven repo.
I probed that repo directly for an arm64 classifier at the newest published version
(`9.4.0-alpha08-15978811`, i.e. the AGP 9.4 alpha line):

```
classifier linux          HTTP 200
classifier osx            HTTP 200
classifier windows        HTTP 200
classifier linux-arm64    HTTP 404
classifier linux-aarch64  HTTP 404
classifier osx-arm64      HTTP 404
```

There is no arm64 aapt2 from Google, including in unreleased alphas. This is not
changing imminently.

**Debian does not have aapt2 either — at any suite.** This surprised me and is worth
recording, because `android-sdk-build-tools` exists in the archive and looks like the
answer. It is not:

```
$ apt-cache policy android-sdk-build-tools     # bookworm AND trixie
  Candidate: 29.0.3+9

$ dpkg -I android-sdk-build-tools_*.deb | grep Depends
  Depends: android-sdk-build-tools-common, aapt (>= 1:10.0.0+r36~), aidl,
           apksigner, split-select, zipalign
```

That depends on **`aapt`** — the legacy v1 tool, from Android 10 era. AGP 8.x cannot use
it. `apt-cache search aapt` returns only `aapt` and `android-libaapt` in both bookworm and
trixie. There is no `aapt2` package in Debian.

**What does work: Commit451/android-arm-build-tools.** Community cross-builds of
`aapt2`/`aidl`/`zipalign`/`split-select` for linux-arm64, built against glibc 2.36 on
Debian 12 — exactly matching the guest's target. Versions track upstream through
build-tools 37.0.0.

```
$ sha256sum -c SHA256SUMS
aapt2: OK
aidl: OK
zipalign: OK
split-select: OK

$ ./aapt2 version
Android Asset Packaging Tool (aapt) 2.20-SOONG BUILD NUMBER PLACEHOLDER
```

Runs natively. `e_machine` = AArch64.

**Wiring it into AGP** is a one-line Gradle property — this is the whole trick:

```properties
android.aapt2FromMavenOverride=/opt/android-arm64-tools/aapt2
```

**Negative control (important):** with that line removed, the build fails exactly as
predicted, proving the override is what makes it work rather than something incidental:

```
* What went wrong:
Execution failed for task ':app:processDebugResources'.
> AAPT2 aapt2-8.13.2-14304508-linux Daemon #0: Daemon startup failed
```

### 2.2 Only `aapt2` needs replacing

A useful empirical result: the successful build ran with the **x86-64 `zipalign`, `aidl`,
and `split-select` still in place and unusable**. AGP 8.13 never invoked them — it does
zip alignment internally via zipflinger, and `aidl`/`split-select` are only needed by
projects that use AIDL or legacy splits. So the guest image needs exactly one substituted
native binary, not four. (Ship `aidl` too if Box should support AIDL projects; it's 11 MB
for all four, so just ship all four.)

### 2.3 `d8`/`r8` — confirmed arch-agnostic

As expected, these are Java shell wrappers over JARs and run fine:

```
$ $ANDROID_HOME/build-tools/36.0.0/d8 --version
D8 8.10.9-dev (build a7ad18a70460b799d0482e497c109a75bf7f91de ...)   exit=0

$ $ANDROID_HOME/build-tools/36.0.0/apksigner --version
0.9   exit=0
```

R8 ran successfully in the release build (§3.2), producing a minified, resource-shrunk
APK. No arch issues anywhere in the Java-based tooling — `sdkmanager`, `d8`, `r8`,
`apksigner`, and AGP itself all just work.

### 2.4 Gradle + AGP on aarch64 — no arch issues, but two packaging traps

Gradle 8.14.3 + AGP 8.13.2 + Kotlin 2.1.0 all worked with **no** aarch64-specific problems
beyond aapt2. The traps are about *provenance*, not architecture:

- **Do not use Debian's `gradle`.** bookworm ships **4.4.1**; AGP 8.x requires Gradle 8.5+.
  Unusable. Must come from `services.gradle.org` (or the project's own wrapper).
- **Do not use Debian's `kotlin`.** bookworm ships **1.3.31** (2019). Unusable.
- One cosmetic warning, harmless: `This version only understands SDK XML versions up to 3
  but an SDK XML file of version 4 was encountered` — cmdline-tools vs. repo metadata skew.

### 2.5 JDK 17 — trivial, as expected

```
$ java -version
openjdk version "17.0.20" 2026-07-21
OpenJDK Runtime Environment (build 17.0.20+8-1-deb12u1-Debian)
OpenJDK 64-Bit Server VM (build 17.0.20+8-1-deb12u1-Debian, mixed mode, sharing)
```

`openjdk-17-jdk-headless` 17.0.20+8-1~deb12u1, straight from bookworm arm64. Nothing to do.

### 2.6 Exact manifest that worked

**From Debian bookworm arm64 (`apt`):**

| Package | Version |
|---|---|
| `openjdk-17-jdk-headless` | 17.0.20+8-1~deb12u1 |
| `curl` | 7.88.1-10+deb12u15 |
| `unzip` | 6.0-28 |
| `zip` | 3.0-13 |
| `xz-utils` | 5.4.1-1+deb12u1 |
| `ca-certificates` | 20250419~deb12u1 |
| `git` | 1:2.39.5-0+deb12u3 |

**Not from apt — must be fetched and pinned:**

| Component | Version / source |
|---|---|
| Android cmdline-tools | `commandlinetools-linux-15859902_latest.zip` (dl.google.com) |
| SDK platform | `platforms;android-36` (via `sdkmanager`) |
| SDK build-tools | `build-tools;36.0.0` (via `sdkmanager`) |
| Gradle | 8.14.3, `services.gradle.org/distributions/gradle-8.14.3-bin.zip` |
| **aapt2 (arm64)** | **Commit451 `platform-tools-36.0.0`**, sha256 `7512ff7e381bea6fd310b6f6e347422c8fda21e07c6e3f0162742e95eb9d7f98` |
| aidl (arm64) | sha256 `8c97356b8bba8f7aad44cfd408e3ee24c66a1258244b5d08af1c9249f50dd659` |
| zipalign (arm64) | sha256 `e8856fb24b10095eb6e940c577ce96d89ddbfc45aa0f7eeaef1597ef68f11a12` |
| split-select (arm64) | sha256 `fb7f0c3c87dbd4243d7e1277ba64ded2399389244058b6a4c969cc615360766c` |

**Supply-chain note:** the single most load-bearing binary in this whole feature is a
community build from one GitHub user. That is a real risk for a shipped product — if that
repo goes stale or away, the feature dies. Mitigation: build aapt2 from AOSP source in
Box's own CI and vendor the result, using Commit451's build scripts as the reference. The
sha256 pins above are the minimum interim measure; do not fetch this at guest runtime.

---

## 3. Evidence — the hello-world APK

### 3.1 Java app (`minSdk 26`, `compileSdk 36`, AGP 8.13.2)

```
### uname / arch
aarch64
### aapt2 in use
Android Asset Packaging Tool (aapt) 2.20-SOONG BUILD NUMBER PLACEHOLDER
### gradle assembleDebug
> Task :app:mergeDebugGlobalSynthetics
> Task :app:mergeLibDexDebug
> Task :app:mergeProjectDexDebug
> Task :app:validateSigningDebug
> Task :app:packageDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 51s
34 actionable tasks: 34 executed
### APK
-rw-r--r-- 1 root root 8555 app/build/outputs/apk/debug/app-debug.apk
package: name='com.example.hello' versionCode='1' versionName='1.0'
  platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'
minSdkVersion:'26'
targetSdkVersion:'36'
application-label:'Box Hello'
```

The APK is real, not a stub — resources were genuinely compiled and linked, and it is
signed and verifies:

```
$ unzip -l app-debug.apk
       57  META-INF/com/android/build/gradle/app-metadata.properties
     1564  classes.dex
     1356  classes2.dex
     1976  AndroidManifest.xml
      588  res/layout/activity_main.xml
      980  resources.arsc

$ aapt2 dump resources app-debug.apk
    resource 0x7f010000 id/message
    resource 0x7f020000 layout/activity_main
      () (file) res/layout/activity_main.xml type=XML
    resource 0x7f030000 string/app_name
    resource 0x7f030001 string/greeting

$ apksigner verify --verbose app-debug.apk
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
```

### 3.2 Kotlin + AndroidX + R8 app

Also built successfully — `androidx.core:core-ktx`, `appcompat`, `material`,
`isMinifyEnabled = true`, `isShrinkResources = true`:

```
> Task :app:minifyReleaseWithR8
BUILD SUCCESSFUL in 3m 58s
-rw-r--r-- 1 root root 1.4M app-release-unsigned.apk
```

Note `app-release-unsigned.apk` — release builds have no signing config by default. For
Box's flow this is fine: debug builds are auto-signed with the debug keystore and are
directly installable. If Box ever offers release builds, it must manage a keystore.

---

## 4. Q2 — Performance

### 4.1 Native arm64 baselines (2 vCPU, Apple Silicon)

Container pinned to `--cpuset-cpus="0,1"` to match the guest's `-smp 2`. Medians of
repeated runs; Gradle daemon disabled for clean builds, enabled for incrementals.

| Scenario | Time |
|---|---|
| Java hello — cold, incl. dependency downloads | 86 s |
| Java hello — clean, deps cached | 18.5 s |
| Java hello — no-op (all up-to-date) | 0.7 s |
| Java hello — incremental, resource edit | 0.9 s |
| Java hello — incremental, Java edit | 1.7 s |
| Kotlin+AndroidX — debug clean, deps cached | 43 s |
| Kotlin+AndroidX — incremental, Kotlin edit | 14–33 s |
| Kotlin+AndroidX — release + R8 + shrink | 239 s |

### 4.2 Measured TCG penalty

Rather than assume a slowdown factor, I measured it: same binaries run natively vs. under
`qemu-aarch64-static` (aarch64-on-aarch64 still goes through full TCG translation).

| Workload | Native | TCG | Slowdown |
|---|---|---|---|
| Tight integer loop | 532 ms | 926 ms | **1.7×** |
| JVM startup (`java -version`) | 45 ms | 446 ms | **9.9×** |
| `javac` small class | 253 ms | 3015 ms | **11.9×** |
| `aapt2 compile` resources | 4 ms | 62 ms | **15.5×** |

The tight loop at 1.7× is TCG's best case and is misleading — a small hot loop is
translated once and then runs efficiently. The realistic figures are the JVM and aapt2
ones: **10–15×**. Build tooling is the bad case for TCG (huge code footprint, constant
JIT churn generating fresh code that must be re-translated, heavy syscall and GC traffic).

`qemu-system` will be worse than these `qemu-user` numbers — it adds MMU translation,
device emulation, and interrupt handling. Call it **12–30×** in the guest.

### 4.3 Extrapolation to the phone

**These are estimates, not measurements — treat the range as real uncertainty.** Two
multipliers stack:

- TCG in system mode: **12–30×**
- Phone big core vs. Apple Silicon P-core: **1.5–2.5×** slower

Combined: **~20–75×**, central band **~25–50×**.

| Scenario | Native | Estimated on-phone under TCG |
|---|---|---|
| Java hello — clean | 18.5 s | **8–15 min** |
| Java hello — incremental | 1.7 s | **1–2.5 min** |
| Kotlin+AndroidX — debug clean | 43 s | **18–36 min** |
| Kotlin+AndroidX — incremental | 15 s | **6–12 min** |
| Kotlin+AndroidX — release + R8 | 239 s | **1.7–3.3 hr** |
| First-ever build (incl. downloads) | 86 s | **30–70 min** |

Thermal throttling on a phone sustaining 2 cores at 100% for tens of minutes will push
these toward the upper end, and will drain the battery hard. Neither is modelled above.

### 4.4 RAM

**Correction.** My first pass at this reported a hard wall — "1 GB cannot build anything."
That was wrong, and the error is worth recording because it is easy to repeat. I measured
peak memory with cgroup `memory.current`, **which includes reclaimable page cache**. In a
container that had just downloaded and unpacked a 635 MB SDK, that number is dominated by
file cache, not by the build. The tell was in my own data: "peak" tracked the limit rather
than the workload — 864 MiB at 1.5 GB, 1069 MiB at 2 GB, 993 MiB at 3 GB. That is page
cache expanding to fill available memory, not demand.

**What is solid**, re-measured against a warm dependency cache, Java hello-world,
2 vCPU, repeated runs:

| Guest RAM | `-Xmx` | Result | Peak |
|---|---|---|---|
| 1024 MB | 1024m | SUCCESS ×3 | ~400 MiB |
| 1024 MB | 512m | SUCCESS ×2 | ~400 MiB |
| 768 MB | 512m | SUCCESS | 397 MiB |

So the trivial Java build genuinely fits in the guest's current `-m 1024`, with room to
spare. The real steady-state demand is ~400 MiB.

**What is also real:** the *cold* build — the one doing dependency resolution and download
— did reproducibly OOM-kill the Gradle daemon at 1 GB:

```
* What went wrong:
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
> Task :app:dexBuilderDebug
```

That reframes the fix rather than removing it. The memory spike is **first-build
dependency resolution**, not compilation. This makes pre-seeding the Gradle cache at image
build time (§5) a correctness requirement, not just a speed optimisation — it removes the
one phase that doesn't fit.

**What is NOT resolved:** the Kotlin/AndroidX floor. It failed at 2 GB and succeeded at
3/4/6 GB, but those runs used cold dependency caches and the page-cache-contaminated
measurement, so I do not trust the 3 GB figure. Kotlin genuinely needs more than Java — it
forks a separate Kotlin compile daemon — but **how much more is an open question this
spike did not settle.** Do not size the guest off that number. Re-running
`./repro.sh memory` against a warm cache, measuring `memory.stat` anon rather than
`memory.current`, would close this in about an hour.

**Working recommendation: raise the guest to 2–3 GB and `-smp 4` if the device allows**,
pending that measurement. That is a judgement call for headroom on real projects, not a
measured floor — 1 GB demonstrably builds the toy app.

### 4.5 Disk

| Component | Size |
|---|---|
| Android SDK (platform 36 + build-tools 36) | 635 MB |
| Gradle 8.14.3 | 146 MB |
| JDK 17 | 261 MB |
| arm64 native tools | 11 MB |
| Gradle dependency cache (after Java + Kotlin/AndroidX) | 978 MB |
| **Total toolchain** | **~2.0 GB** |

`IMAGE_SIZE_MB` is currently 4096, against a base Debian rootfs. Adding 2 GB of toolchain
does not fit with any headroom.

---

## 5. Recommended changes to the guest image

### `guest/packages.txt`

Add:

```
openjdk-17-jdk-headless
unzip
zip
xz-utils
```

`curl`, `ca-certificates`, `git`, `python3` are already present. **Do not** add `gradle`,
`kotlin`, `aapt`, or `android-sdk-build-tools` — all are too old or wrong (§2.4, §2.1).

Consider dropping `build-essential` if nothing else needs a C toolchain; it is large and
the Android build path doesn't use it (NDK projects would, but that's out of scope here).

### `guest/build-image.sh`

Four changes:

1. **Raise `IMAGE_SIZE_MB` from 4096 to at least 8192**, or install the toolchain onto
   the workspace disk instead and grow `WORKSPACE_SIZE_MB`. The former is simpler; the
   latter keeps the system image reproducible and lets the SDK be updated without
   reflashing. I'd lean to keeping the SDK in the system image and raising to 8192.

2. **Add a toolchain stage** after `mmdebstrap`, fetching into the rootfs with pinned
   checksums: cmdline-tools → `sdkmanager --install "platforms;android-36"
   "build-tools;36.0.0"`, the Gradle 8.14.3 distribution, and the vendored arm64 aapt2.
   All four downloads must be checksum-verified at build time, never at guest runtime.

3. **Set the aapt2 override globally**, so every project the user creates picks it up with
   no per-project configuration:

   ```
   # /home/agent/.gradle/gradle.properties
   android.aapt2FromMavenOverride=/opt/android-sdk/arm64/aapt2
   org.gradle.jvmargs=-Xmx1024m
   org.gradle.workers.max=2
   ```

   Note `-Xmx1024m` rather than AGP's default 1536 MB, and capped workers — both matter in
   a memory-constrained guest.

4. **Pre-seed the Gradle dependency cache** at image build time by running one throwaway
   build of a template project. This is the highest-leverage change in the list, for two
   reasons: it removes the 30–70 minute cold-download penalty from §4.3, *and* cold
   dependency resolution is the one phase that OOMs at 1 GB (§4.4). It buys both speed and
   headroom.

### `QemuCommand.kt`

`-m 1024` → `-m 3072` and `-smp 2` → `-smp 4` where the device permits. Treat this as
headroom for real projects rather than a measured requirement — per §4.4 the toy app
builds fine at 1 GB, and the Kotlin floor is unmeasured. If you want the number to be
evidence-based before changing the launch contract, do the §4.4 re-measurement first;
it's cheap.

Independently: Box should detect available device RAM and decline to offer the build
feature below a threshold, rather than letting the user discover the limit via an OOM kill
20 minutes into a build.

---

## 6. Install path

**Recommendation: `PackageInstaller` + `REQUEST_INSTALL_PACKAGES`. You are not wrong —
this isn't close.**

`adb connect` to the host phone via SLIRP's 10.0.2.2 would technically work, but it fails
on every axis that matters:

- **Requires the user to enable Developer Options and Wireless debugging**, then complete
  a pairing-code flow. That is a far worse first-run experience than one install tap, and
  it is exactly the "no adb, no developer mode" property you designed for.
- **Wireless debugging's port is not stable** across reboots, so Box would need to
  discover it (mDNS) and re-pair periodically. Fragile, and it breaks silently.
- **It's a security downgrade.** `adb install` carries `INSTALL_PACKAGES`, which installs
  without user consent. Routing that capability through a VM the user runs untrusted code
  in is a genuinely bad idea. `PackageInstaller` keeps the system-rendered confirmation
  dialog as a mandatory, unforgeable checkpoint.
- **It inverts the trust boundary.** Today the guest is sandboxed and talks to Box over a
  private virtio channel. Giving the guest adb access to the host phone hands it control
  over the device that contains it.

The `PackageInstaller` path — guest writes APK → Box pulls it over the existing control
channel → `PackageInstaller.Session` → one system-rendered tap — preserves the trust
boundary and needs no device configuration.

Two things to plan for:

- **`REQUEST_INSTALL_PACKAGES` is a Play-restricted permission.** Box needs to declare it
  via the Permissions Declaration Form and justify it. Box's core function is
  user-initiated installation of packages the user built, which is squarely within the
  documented acceptable use, but budget review time and expect scrutiny.
- The user grants "install unknown apps" for Box once, via a system settings screen
  (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`); handle the not-yet-granted state gracefully.

Also worth noting: the control channel already carries the APK, so **the install path is
the cheap part of this feature.** Nothing here is a blocker.

---

## 7. Verdict — is the APK endgame buildable, and what does it cost?

**Buildable: yes, technically, and proven.** A real signed APK built on aarch64 Linux with
a one-line AGP override. The toolchain gate is passed and the install path is sound.

**Worth building: not on the current runtime.** The blockers are resources, not tooling:

1. Memory needs attention but is not the blocker I first claimed. The toy app builds in
   1 GB once caches are warm; cold dependency resolution is what OOMs, and pre-seeding the
   image fixes that. Raising the guest to ~3 GB is prudent headroom. The Kotlin floor is
   genuinely unmeasured (§4.4) and should be nailed down before sizing decisions are
   locked in.
2. Under TCG, a realistic Kotlin app costs an estimated 18–36 minutes per clean build and
   6–12 minutes per incremental. That is not a coding loop; it is a batch job. A toy Java
   app at 8–15 min clean / 1–2.5 min incremental is on the edge of tolerable, which
   suggests a viable narrower V1 (see below).
3. **AVF/pKVM cannot rescue this for a third-party app.** This is the finding that most
   changes the roadmap. AOSP is explicit that the virtualization APIs are `@SystemApi`
   requiring `MANAGE_VIRTUAL_MACHINE`, *"not available to third party apps"* — privileged
   apps only on Android 14, preinstalled apps only on Android 15. It can be granted via
   `adb shell pm grant` for development, which makes it fine for a demo and useless for a
   shipped product. Google's own Terminal app gets the Debian-VM experience precisely
   because it is preinstalled.

So the pKVM line in the roadmap should be rewritten. It is not "optional now, required
later" — **it is currently unavailable to Box at any point**, unless Box ships preinstalled
on a device via an OEM deal, or Google opens the API to third parties. Both are outside
Box's control, and neither should be planned around.

### What I'd recommend

- **Do the cheap, unambiguous work now:** raise guest RAM, add the toolchain to the image,
  pre-seed the Gradle cache, vendor aapt2 from Box's own CI. That is maybe a week and it
  is all reusable regardless of what happens to the build feature.
- **Scope V1 to what TCG can actually carry:** a Java or minimal-Kotlin, no-AndroidX,
  no-R8 template app. ~10 min clean, ~1–2 min incremental, debug-signed, one-tap install.
  Frame it as "build and install a real app from your phone", not as a general Android IDE.
- **Measure before committing further.** My Q2 numbers are extrapolations with a 3× spread.
  The next step, if you want to proceed, is to run the §3.1 hello-world build inside the
  actual guest on a real device and replace the estimates with one real number. That is
  roughly a day of work now that the toolchain question is settled, and it converts the
  biggest remaining unknown into a fact.
- **Do not build the feature assuming AVF arrives.** If the 20–40 minute Kotlin reality is
  unacceptable, the honest conclusion is that the endgame needs a different execution model
  — remote/cloud build, or a host-side native Android build — not a faster hypervisor Box
  is not permitted to use.

---

## Appendix — reproduction

Full spike artifacts (both projects, the arm64 tools, the Gradle home) were built in a
throwaway container. To reproduce §3.1 from scratch:

```bash
docker run --rm -it --platform linux/arm64 debian:bookworm bash
# then, inside:
apt-get update && apt-get install -y openjdk-17-jdk-headless curl unzip zip xz-utils ca-certificates git
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ANDROID_HOME=/opt/android-sdk
# cmdline-tools -> sdkmanager -> platforms;android-36 build-tools;36.0.0
# gradle 8.14.3 from services.gradle.org
# aapt2 from Commit451/android-arm-build-tools platform-tools-36.0.0 (verify sha256)
# set android.aapt2FromMavenOverride in gradle.properties, then:
gradle assembleDebug
```

**Sources**

- [Commit451/android-arm-build-tools](https://github.com/Commit451/android-arm-build-tools) — arm64 aapt2/aidl/zipalign/split-select
- [AVF framework-virtualization README (AOSP)](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/aml_net_351410000/libs/framework-virtualization/README.md) — `MANAGE_VIRTUAL_MACHINE` restrictions
- [Android Virtualization Framework overview (AOSP)](https://source.android.com/docs/core/virtualization)
- [Use of the REQUEST_INSTALL_PACKAGES permission — Play Console Help](https://support.google.com/googleplay/android-developer/answer/12085295?hl=en)
- [AAPT2 — Android Developers](https://developer.android.com/tools/aapt2)

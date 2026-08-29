# Addendum: Android builds on-device, without Gradle

**Date:** 2026-08-29
**Image:** guest built from `a9f2e9e`
**Relationship to prior work:** extends [`spike-android-toolchain.md`](spike-android-toolchain.md)
(2026-08-10). That spike's conclusions are not contradicted here. Its numbers were measured
in a native arm64 container and extrapolated to the phone; this addendum reports what
happened when the same question was run **on an actual device, inside Box's own guest**.

**Status:** working code, reproducible. Artifacts in
[`spike/android-toolchain/gradle-free/`](spike/android-toolchain/gradle-free/).

---

## Verdict up front

**A signed, installable APK was built inside Box's guest on a phone.** Not in a container,
not extrapolated — on the device, by the agent, in the session that produced this document.

The prior spike's central estimate for this was **8–15 minutes** for a clean Java
hello-world ([§4.3](spike-android-toolchain.md)). Measured here: **2 min 47 s**.

The difference is not hardware and not luck. **It is Gradle.** Removing Gradle from the
build removes the phase that dominates the time, and removes the phase that the prior spike
identified as the one that OOM-kills at 1 GB ([§4.4](spike-android-toolchain.md)). Both
problems have the same cause, and dropping one dependency addresses both.

This does **not** replace the Gradle path. See [Limits](#limits) — it cannot resolve Maven
dependencies, so no AndroidX, no Compose. It is a second, much cheaper path that covers the
case Box most plausibly cares about first: an agent generating a small app from source it
wrote itself.

---

## 1. What was measured

All figures from the guest described above: QEMU TCG, `aarch64`, **2 cores, 2 GB RAM**, on
a phone. No hardware virtualisation, consistent with the prior spike's
[§2](spike-android-toolchain.md) finding that AVF is unavailable to third-party apps.

| Step | Time |
|---|---|
| `provision.sh` from nothing (download + verify) | **6 min 30 s** |
| Clean APK build, Gradle-free | **2 min 47 s** |
| — of which `ecj` compile | 59 s |
| JDK 17 unpack (for comparison, later removed) | 2 min 34 s |
| `jlink` a minimal runtime (for comparison, later removed) | 9 min 11 s |

The resulting APK: 12,821 bytes, `minSdk 24`, signed and verified under **APK Signature
Scheme v2 and v3**, `aapt2 dump badging` reporting a resolvable launchable activity and
icon.

### Cross-check against the prior spike

| | Prior spike (est.) | Measured here |
|---|---|---|
| Java hello, clean build | 8–15 min | **2 min 47 s** |
| First-ever build incl. downloads | 30–70 min | **9 min 17 s** |
| Toolchain on disk | ~2.0 GB | **217 MB** |
| Guest RAM required | 2–3 GB recommended | **built fine in 2 GB** |

The prior extrapolation was sound for the build it modelled. The gap is method, not error:
that model included Gradle daemon startup, dependency resolution and AGP task graph
construction. This build has none of them.

---

## 2. Why it is smaller: three substitutions

The prior spike's disk table ([§4.5](spike-android-toolchain.md)) totals ~2.0 GB and
concludes it "does not fit with any headroom" in `IMAGE_SIZE_MB=4096`. Three changes bring
that to 217 MB, which fits the **existing** 974 MB workspace disk with room to spare — so
no image resize is required for this path.

### 2.1 A JRE and `ecj`, not a JDK

The prior spike recommends adding `openjdk-17-jdk-headless` to `guest/packages.txt`. But
the JDK is needed only for `javac`, and the Eclipse batch compiler is a single 3 MB jar
that compiles Java on a plain JRE.

| | Download | Installed |
|---|---|---|
| Temurin JDK 17 | 192 MB | 271 MB |
| Temurin JRE 17 + `ecj` | **49 MB** | **140 MB** |

Verified: `ecj 3.33.0` compiled the sample app on the JRE, `d8` dexed the result, and
`apksigner` signed it — all on a runtime with no `javac` present. `keytool` ships in the
JRE, so key generation still works.

A `jlink`ed runtime is smaller still (**64 MB**, and `javac` survives), but getting there
costs the 192 MB JDK download plus 9 min of `jlink`. Not worth it at runtime. It would be
worth it *at image build time* if the toolchain were ever baked in.

### 2.2 Ranged reads instead of whole archives

Two of the needed files sit inside large zips that are mostly x86-64 binaries we discard.
Zip's central directory is at the end of the file, and `dl.google.com` sends
`Accept-Ranges: bytes` — so the members can be fetched individually.

| Archive | Full size | Fetched | Wanted |
|---|---|---|---|
| `platform-35_r02.zip` | 64.3 MB | **25.0 MB** | `android.jar` |
| `build-tools_r35.0.1_linux.zip` | 62.0 MB | **13.2 MB** | `d8.jar`, `apksigner.jar` |

Both verified `sha256`-identical to the same files extracted from full downloads.
Implementation: [`zipget.py`](spike/android-toolchain/gradle-free/zipget.py), ~90 lines,
stdlib only.

### 2.3 No Gradle at all

146 MB of Gradle plus a 978 MB dependency cache in the prior spike's table — both zero
here. The pipeline is seven steps invoked directly:

```
aapt2 compile → aapt2 link → ecj → d8 → zip in classes.dex → zipalign → apksigner
```

This is what AGP orchestrates. For a project with no external dependencies, orchestrating
it directly costs about 40 lines of shell.

---

## 3. `aapt2`: an independent second source

The prior spike identifies `aapt2` as the sole blocker and recommends
Commit451/android-arm-build-tools. That still stands. This run independently used
**lzhiyong/android-sdk-tools** (`35.0.2`), which publishes static `aarch64` builds of
`aapt2`, `aapt`, `aidl`, `zipalign`, `split-select` and platform-tools.

```
$ ./build-tools/aapt2 version
Android Asset Packaging Tool (aapt) 2.19
```

Worth recording because the whole path depends on one community binary. Two independent
sources is materially better than one, and they can be checksum-pinned against each other.

---

## 4. Limits

Stated plainly, because this path is easy to oversell.

- **No Maven dependency resolution.** No AndroidX, no Compose, no `material`. Every library
  would be a manual AAR fetch and merge. The prior spike's Gradle path remains the only
  answer for real projects, and its §5 recommendations stand for that path.
- **Not tested beyond a single-module Java app** with plain Views. Multi-module, AIDL,
  NDK, and product flavours are all unexercised.
- **Kotlin untested here.** `kotlinc` is a JVM tool and should work on the same JRE, at
  maybe +100 MB — but that is an inference, not a measurement.
- **The signing key is generated in the guest** and stored unencrypted. Fine for
  sideloading; it is not a release-signing story.
- **`ecj` is not `javac`.** For plain Java the difference is immaterial, but it is a
  different compiler with different diagnostics and its own bugs.

---

## 5. Suggested next steps

Ordered by cost. None require the image resize or RAM increase in the prior spike's §5,
because this path does not need them.

1. **Adopt the gradle-free path as the fast lane**, keeping Gradle for real projects. An
   agent writing a small app from scratch — the likely first use case — never needs Maven.
2. **Pin checksums.** `provision.sh` verifies each tool by *executing* it, but does not yet
   pin hashes. It should, especially for the community `aapt2`.
3. **Re-run the prior spike's §4.4 RAM measurement against this path.** It should be
   undemanding — `d8` ran under `-Xmx512m` and there is no daemon — which would settle
   whether the guest needs more than its current 1 GB at all.
4. **Consider host-side building.** Everything above still pays a 10–15× TCG tax measured
   in the prior spike's §4.2. `aapt2` is a native ARM64 binary that could ship as a JNI
   library exactly as QEMU's `.so`s already do; `d8`, `ecj` and `apksigner` are pure Java
   and could be dexed to run on ART. That would move a 2m47s build to seconds of native
   execution. **Unverified** — there is no Android runtime inside the guest to test it
   from, and it needs a prototype before anyone commits to it. Fetching the pieces on
   demand into host app storage would keep them out of the APK download.

---

## 6. An unrelated bug found on the way

The natural way to hand a freshly built APK to the user today is to serve it and let them
tap a link in the preview panel. **That silently does nothing.**

`PreviewSheet.kt` constructs its `WebView` with a `WebViewClient` overriding only
`onPageStarted`/`onPageFinished`. There is no `setDownloadListener` anywhere in the
codebase, and a `WebView` with no download listener discards any response it cannot render
— with no error shown to the user and nothing surfaced to the agent.

The forward itself is fine: `QemuTcgRuntime.hostfwd` binds `127.0.0.1` on the host, so the
same URL opened in Chrome downloads correctly. The workaround is to tell the user to copy
the address into a real browser, which is a poor experience for something the agent just
built for them.

Fixing this is small and independent of everything above: a `setDownloadListener` handing
off to `DownloadManager`. It is worth doing regardless of which build path Box adopts,
since "agent produces a file the user should keep" is not specific to APKs.

Filed here rather than separately because it was found while trying to deliver the APK this
document describes.

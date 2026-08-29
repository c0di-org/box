# Gradle-free Android build for the Box guest

Builds a signed, installable APK inside Box's ARM64 Debian guest, with no Gradle, no
Android Studio, no root and no `apt`. Written and run on a phone; see
[`../../../spike-android-toolchain-ondevice.md`](../../../spike-android-toolchain-ondevice.md)
for measurements and rationale, and
[`../../../spike-android-toolchain.md`](../../../spike-android-toolchain.md) for the
earlier Gradle-based spike this extends.

## Use

```bash
./provision.sh            # ~6 min, ~106 MB, installs to /workspace/android
./build.sh                # ~3 min, emits build/BuiltInTheBox.apk
```

`provision.sh` is idempotent — rerunning it re-verifies without re-downloading. Pass a
prefix to install elsewhere: `./provision.sh /somewhere/else`.

## What it assembles, and why each piece

| Piece | Source | Why not the obvious thing |
|---|---|---|
| `aapt2`, `zipalign` | lzhiyong/android-sdk-tools (ARM64) | Google ships x86-64 only; Debian has no `aapt2` at any suite |
| `d8`, `apksigner` | Google's x86-64 zip, ranged read | These members are pure Java and run anywhere — the native binaries beside them are discarded |
| `ecj` | Maven Central, 3 MB | Compiles Java on a plain JRE, so no 192 MB JDK is needed |
| JRE 17 | Temurin aarch64 | 46 MB vs the JDK's 192 MB; `keytool` is included |
| `android.jar` | Google's platform zip, ranged read | Only 26 MB of a 64 MB archive is wanted |

Total: **~106 MB downloaded, 217 MB installed.**

## The pipeline

`build.sh` runs directly what AGP would otherwise orchestrate:

```
aapt2 compile   res/ → flat resources
aapt2 link      → base.apk + generated R.java
ecj             → .class      (javac if a full JDK is present)
d8              → classes.dex
zip             classes.dex into the apk
zipalign -p 4
apksigner sign  → signed, verifiable APK
```

Provenance for the sample app is generated into `BuildInfo.java` at build time — kernel,
arch, core count, compiler versions — so the built app can state where it came from.

## Limits

No Maven resolution, so no AndroidX and no Compose; every library would be a manual AAR
merge. Single-module Java with plain Views is what has been exercised. The signing key is
self-signed and stored unencrypted — fine for sideloading, not a release story.

## Files

- `provision.sh` — assembles and verifies the toolchain by executing each tool
- `build.sh` — the seven-step pipeline; honours `$JDK_HOME`, falls back to the JRE
- `zipget.py` — extracts named members from a remote zip over HTTP range requests
- `sample/app` — the app used for the measurements

# AndroidX without Gradle: Maven resolution, AAR merging, and a dex cache

**Date:** 2026-08-30
**Guest:** built from `a9f2e9e` — QEMU TCG, aarch64, 2 cores, 2 GB RAM, on a phone
**Follows:** [`spike-android-toolchain-ondevice.md`](../../../spike-android-toolchain-ondevice.md),
which built a dependency-free APK on-device in 2m47s and named Maven resolution
as the missing piece.

**Status:** working. A signed APK using AppCompat, Material, ConstraintLayout and
RecyclerView was built in the guest, installed, and run on the phone.

---

## What was missing, and now is not

The previous round could build any app that used only the Android platform. That
excludes almost every real app, because AndroidX ships as AARs on Maven, and
using one means three jobs the Android Gradle Plugin normally does:

1. resolve a transitive dependency graph
2. unpack AARs and merge their resources
3. merge their manifest fragments into the app's

All three now work, in ~450 lines of Python, with no Gradle.

| From `app/deps.txt` | Result |
|---|---|
| 4 coordinates | **57 modules**, 10.9 MB, no unresolved warnings |
| AAR unpacking | 58 jars, 25 resource sets |
| Manifest merge | 4 nodes merged, `${applicationId}` substituted |
| `aapt2 link` | 41 library `R` packages, **86 locales**, 766 resources |
| `ecj` | 679 classes, zero errors |
| `d8` | **5 dex files** — native multidex, no support library |
| Signed APK | 5.5 MB, v2 + v3, installs and runs |

---

## Three things that are easy to get wrong

Recording these because each produced a plausible-looking result rather than an
error, which is the expensive kind of bug.

### 1. AndroidX pins versions with range syntax

`appcompat` declares its sibling as `[1.6.1]` — a Maven range meaning *exactly
this version*, not the literal string. Treated literally the lookup 404s, and the
resolver silently skipped `appcompat-resources`. AppCompat without its resources
module then fails in a way that points nowhere near the cause.

`maven.py` unwraps single-version brackets and **refuses open ranges** rather
than picking a version and building against something the author never asked for.

### 2. Library resource collisions are normal, and are not errors

AppCompat and Material both define `styleable/SearchView`. Passing every
library's compiled resources to `aapt2 link` as plain inputs makes that fatal:

```
error: resource 'styleable/SearchView' has a conflicting value for configuration ().
error: failed to merge resource table.
```

`-R` applies overlay semantics instead, where the last conflicting definition
wins. That makes **ordering** load-bearing, so the resolver records each module's
distance from a root coordinate, and resources are layered deepest-transitive
first, app last. A direct dependency outranks one that was merely dragged in, and
the app can always override a library.

### 3. Manifest merging has real semantics, not just concatenation

Several AndroidX libraries register meta-data on **the same** provider,
`androidx.startup.InitializationProvider`. Appending each library's copy, or
letting the last one win, loses initializers — and the app still builds, then
misbehaves at runtime.

`aar.py` unions provider children by name, and substitutes `${applicationId}`
into `android:authorities`. Left unsubstituted the manifest is invalid; worse,
two apps built this way would collide on a single provider authority. It also
strips `tools:` attributes, which are merger directives AGP consumes and `aapt2`
does not understand.

---

## Performance: where the time actually goes

Measured in the guest, cold, nothing cached:

| Stage | Time |
|---|---|
| `d8` — dexing 58 library jars + app classes | **~59 min** |
| `ecj` — compiling against 58 jars | ~16 min |
| resolve, unpack, `aapt2`, package, sign | ~5 min |
| **total** | **~95 min** |

That is too slow to enjoy, and it corrects an earlier estimate of ~20 min that
was simply wrong.

**But nearly all of it is work that does not depend on the app.** Those 58 jars
are byte-identical in every project. `predex.sh` therefore:

- dexes each library jar once with `d8 --intermediate`, cached by sha256, so a
  version bump re-dexes only the jar that changed
- merges all library jars into **one** classpath jar, because much of `ecj`'s
  time goes on opening and indexing 58 separate zips

The build then merges pre-built dex files instead of dexing 11 MB of bytecode,
and compiles against one jar instead of 58. This is what AGP achieves with
per-library dexing plus a dex merger. `build.sh` uses the cache when it is
present and falls back cleanly when it is not.

### Batch the dexing, do not do it per jar

The first implementation cached **per jar**, keyed by sha256 — finer-grained,
and what AGP does. Under emulation it was catastrophically worse: **6.5 minutes
for a 40 KB jar**, and 2 of 58 jars done in 13 minutes, extrapolating to roughly
**six hours** against ~59 min to dex the whole set at once.

The reason is that a `d8` invocation costs minutes here almost regardless of
input size. JVM startup and d8's initialisation dominate, and the toolchain spike
measured JVM startup at ~10× under TCG. Paying that 58 times swamps any benefit
from doing less work per call.

So the cache key covers the **whole dependency set**: change one coordinate and
all of them are re-dexed. That is the wrong trade on a normal machine and the
right one here, because the per-invocation cost *is* the cost. Worth remembering
for anything else that shells out to a JVM tool in a loop inside the guest.

### Measured, warm

| | Time |
|---|---|
| Cold, nothing cached | ~95 min |
| Populating the cache (one batched `d8` pass) | 26m 05s |
| **Warm rebuild** | **13m 11s** |

**7.2× faster.** The cache is 18 MB: a 7.9 MB pre-dexed library set and a 9.6 MB
merged classpath jar holding 6,011 classes.

It is also not the ~5 min predicted before measuring — the second optimistic
estimate this document has had to correct. The cause is visible in the build log:
`aapt2` still recompiles **25 library resource sets on every build**, and those
are exactly as app-independent as the jars were. Caching them the same way is the
obvious next step, and is deliberately left unmeasured rather than estimated
again.

---

## Two lanes, and the honest trade

| | No dependencies | AndroidX / Material |
|---|---|---|
| Cold build | **2m 47s** | ~95 min (cache targets ~5 min) |
| Platform API | complete | complete |
| Modern UI kit | no | yes |

The dependency-free lane is not a toy. `android.jar` is the whole platform:
SQLite, Camera2, sensors, networking, notifications, widgets, `Canvas`, and
**WebView** — which makes a native shell around local HTML/CSS/JS a genuine
architecture rather than a workaround.

What AndroidX adds is mostly *convenience and visual polish*. That matters less
here than it normally would, because the boilerplate a library saves is precisely
what an agent can write. The libraries that stay genuinely irreplaceable are the
ones wrapping services — Maps, Firebase, payments — not the ones wrapping SQLite.

So the recommendation is not "always resolve dependencies". It is: **default to
the fast lane, and reach for AndroidX when the app actually needs it** — which,
with the cache warm, should cost minutes rather than an hour and a half.

---

## Still missing

- **Kotlin.** Not blocked by anything here; `kotlinc` is a JVM tool at ~91 MB and
  should run on the same JRE. Untested.
- **Compose.** Needs Kotlin plus the compiler plugin. Hard, and likely not worth
  it under emulation.
- **Instrumented tests.** No Android runtime in the guest. JVM unit tests would
  work.
- **`<scope>import</scope>` BOMs, classifiers, snapshot versions.** Refused
  explicitly rather than guessed at. None appear in the AndroidX graphs targeted
  here.
- **Checksum pinning.** Artifacts are fetched over TLS but not pinned.

---

## Files

- `maven.py` — POM parsing with parent inheritance, `dependencyManagement`,
  `${property}` expansion, highest-version-wins, dependency-depth tracking
- `aar.py` — AAR unpacking and manifest merging
- `predex.sh` — the dex cache and merged classpath jar
- `build.sh` — the pipeline; uses the cache when present
- `sample/app` — the app used for these measurements

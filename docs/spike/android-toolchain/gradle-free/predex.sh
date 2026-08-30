#!/bin/bash
# Pre-dex the library set once and cache it, plus a merged classpath jar.
#
# Why this exists: a cold AndroidX build measured ~95 min in Box's guest, of
# which ~59 min was d8 dexing 58 library jars and ~16 min was ecj opening those
# same jars to index them. Neither depends on the app - those jars are
# byte-identical in every project - so both are done once and reused.
#
# Why it dexes the whole set in ONE d8 invocation rather than per jar: under
# TCG, a d8 run costs minutes almost regardless of input size, because JVM
# startup and d8's own initialisation dominate (the toolchain spike measured JVM
# startup at ~10x emulated). Per-jar caching was tried first and measured ~6.5
# min for a 40 KB jar - about six hours for the set, against ~59 min to dex all
# of them together. Finer-grained caching is the better idea everywhere except
# here; batching wins because the per-invocation cost is the whole cost.
#
# The trade: the cache key covers the whole set, so changing one dependency
# re-dexes all of them. At one invocation per set that is still far cheaper.
#
#   DEXDIR=$(./predex.sh <libinfo.json>)      # progress on stderr, path on stdout
set -eu

SDK=/workspace/android
CACHE=$SDK/.dexcache
JAVA=$SDK/jre/bin/java
ANDROID_JAR=$SDK/platforms/android-35/android.jar
LIBINFO=${1:-$SDK/build/libinfo.json}

mkdir -p "$CACHE"
say() { echo "[$(date +%H:%M:%S)] $*" >&2; }

python3 -c "
import json;print('\n'.join(json.load(open('$LIBINFO'))['jars']))" > "$CACHE/.jars.txt"
total=$(wc -l < "$CACHE/.jars.txt")

# Key on the content of every jar, so a version bump misses the cache and
# anything else hits it.
KEY=$(sha256sum $(cat "$CACHE/.jars.txt") | sha256sum | cut -c1-32)
DEXDIR=$CACHE/set-$KEY

if [ -f "$DEXDIR/.complete" ]; then
    say "dex cache HIT ($total jars, $(ls "$DEXDIR"/*.dex | wc -l) dex, $(du -sh "$DEXDIR" | cut -f1))"
else
    say "dex cache MISS - dexing $total jars in one pass (slow, once)"
    rm -rf "$DEXDIR"; mkdir -p "$DEXDIR"
    # --intermediate emits mergeable dex rather than a final linked one, so the
    # app's own classes can be merged in later without redoing this work.
    "$JAVA" -Xmx1200m -XX:+UseSerialGC -cp "$SDK/lib/d8.jar" \
        com.android.tools.r8.D8 \
        --intermediate --min-api 24 --release \
        --lib "$ANDROID_JAR" \
        --output "$DEXDIR" \
        @"$CACHE/.jars.txt"
    touch "$DEXDIR/.complete"
    say "dexed: $(ls "$DEXDIR"/*.dex | wc -l) dex files, $(du -sh "$DEXDIR" | cut -f1)"
fi

# One jar for ecj to index instead of 58 separate zips.
CPJAR=$CACHE/cp-$KEY.jar
if [ ! -s "$CPJAR" ]; then
    say "merging classpath jar"
    python3 - "$CACHE/.jars.txt" "$CPJAR" <<'PY' >&2
import sys, zipfile
jars = [l.strip() for l in open(sys.argv[1]) if l.strip()]
seen, dupes = set(), 0
with zipfile.ZipFile(sys.argv[2], 'w', zipfile.ZIP_DEFLATED) as dst:
    for j in jars:
        try:
            src = zipfile.ZipFile(j)
        except zipfile.BadZipFile:
            continue
        with src:
            for info in src.infolist():
                n = info.filename
                # jar signatures describe the archive being dismantled and are
                # invalid here; on duplicate classes the first jar wins, which
                # is the order ecj would have used anyway.
                if n.startswith('META-INF/') or n.endswith('/'):
                    continue
                if n in seen:
                    dupes += 1
                    continue
                seen.add(n)
                dst.writestr(info, src.read(n))
print(f"  {len(seen)} entries, {dupes} duplicates dropped")
PY
fi
say "classpath jar $(du -h "$CPJAR" | cut -f1)"

echo "$DEXDIR"

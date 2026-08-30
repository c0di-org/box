#!/bin/bash
# Build a signed APK without Gradle.
#   aapt2 compile -> aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner
set -eu

SDK=/workspace/android
APP=$SDK/app
OUT=$SDK/build
PKG=com.c0di.builtinbox
NAME=BuiltInTheBox

# A full JDK if one is present, else the JRE that provision.sh installs.
if [ -z "${JDK_HOME:-}" ]; then
    if   [ -x "$SDK/jdk/bin/java" ]; then JDK_HOME=$SDK/jdk
    elif [ -x "$SDK/jre/bin/java" ]; then JDK_HOME=$SDK/jre
    else echo "no java runtime found - run ./provision.sh first" >&2; exit 1
    fi
fi
JAVA=$JDK_HOME/bin/java
JAVAC=$JDK_HOME/bin/javac
[ -x "$JAVA" ] || { echo "no java at $JAVA" >&2; exit 1; }
AAPT2=$SDK/build-tools/aapt2
ZIPALIGN=$SDK/build-tools/zipalign
ANDROID_JAR=$SDK/platforms/android-35/android.jar

MIN_SDK=24
TARGET_SDK=34

step() { echo ""; echo "==> $*"; }

rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes,dex,libs}

# --- dependencies --------------------------------------------------------
# app/deps.txt holds Maven coordinates, one per line. Resolving them, unpacking
# the AARs and merging their manifests is what AGP would do; maven.py and aar.py
# do it instead. With no deps.txt this whole section is skipped.
DEP_JARS=""; DEP_RES=""; DEP_PKGS=""; DEXDIR=""; CPJAR=""
APP_MANIFEST="$APP/AndroidManifest.xml"
if [ -s "$APP/deps.txt" ]; then
    step "resolving dependencies"
    COORDS=$(grep -vE '^\s*(#|$)' "$APP/deps.txt" | tr '\n' ' ')
    echo "   $COORDS"
    python3 "$SDK/maven.py" $COORDS --out "$OUT/libs" > "$OUT/artifacts.json"

    step "unpacking AARs + merging manifests"
    python3 "$SDK/aar.py" "$OUT/artifacts.json" "$OUT/aar" \
        "$APP/AndroidManifest.xml" --out "$OUT/AndroidManifest.xml" \
        > "$OUT/libinfo.json"
    APP_MANIFEST="$OUT/AndroidManifest.xml"

    DEP_JARS=$(python3 -c "
import json;print(' '.join(json.load(open('$OUT/libinfo.json'))['jars']))")
    DEP_RES=$(python3 -c "
import json;print(' '.join(json.load(open('$OUT/libinfo.json'))['resdirs']))")
    DEP_PKGS=$(python3 -c "
import json;print(' '.join(json.load(open('$OUT/libinfo.json'))['packages']))")
    python3 -c "
import json; d=json.load(open('$OUT/libinfo.json'))
print(f\"   {len(d['jars'])} jars, {len(d['resdirs'])} resource dirs, \"
      f\"{len(d['packages'])} R packages, {d['merged_nodes']} manifest nodes merged\")"

    # Libraries are identical in every project, so dex them once and keep both
    # the dex and a single merged classpath jar. This is where the time goes:
    # ~59 min of a cold build was d8 on these jars, ~16 min was ecj indexing
    # them. Cached, both become close to free.
    step "pre-dexing libraries (cached)"
    DEXDIR=$(bash "$SDK/predex.sh" "$OUT/libinfo.json")
    CPJAR=$(ls "$SDK"/.dexcache/cp-*.jar 2>/dev/null | head -1)
fi

# --- 0. bake build provenance into the source --------------------------
step "generating BuildInfo.java"
mkdir -p "$OUT/gen/com/c0di/builtinbox"
KERNEL=$(uname -r)
ARCH=$(uname -m)
CORES=$(nproc)
STAMP=$(date -u '+%Y-%m-%d %H:%M UTC')
AAPTV=$("$AAPT2" version 2>&1 | head -1 | sed 's/.*(aapt) //;s/-*$//')
JAVAV=$("$JAVAC" -version 2>&1 | awk '{print $2}')
COMMIT=$(grep '^commit=' /usr/src/box/BUILD-INFO 2>/dev/null | cut -d= -f2 | cut -c1-7)

cat > "$OUT/gen/com/c0di/builtinbox/BuildInfo.java" <<EOF
package com.c0di.builtinbox;
/** Generated at build time by build.sh. Do not edit. */
public final class BuildInfo {
    public static final String TEXT =
        "built     $STAMP\n" +
        "host      Debian 12, kernel $KERNEL\n" +
        "arch      $ARCH ($CORES cores, QEMU)\n" +
        "compiler  javac $JAVAV + d8\n" +
        "resources aapt2 $AAPTV (ARM64)\n" +
        "box       $COMMIT";
    private BuildInfo() {}
}
EOF

# --- 1. compile resources ----------------------------------------------
# Every library's res/ is compiled too, or its resources do not exist and any
# R field referring to them fails to resolve.
step "aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"
LIB_ZIPS=""
if [ -n "$DEP_RES" ]; then
    n=0
    for d in $DEP_RES; do
        n=$((n + 1))
        z="$OUT/res/lib-$n.zip"
        "$AAPT2" compile --dir "$d" -o "$z"
        LIB_ZIPS="$LIB_ZIPS $z"
    done
    echo "   app + $n library resource sets"
fi

# --- 2. link -> base apk + R.java --------------------------------------
# Libraries legitimately collide - AppCompat and Material both define
# styleable/SearchView, for instance. Passing them as plain inputs makes that a
# fatal "failed to merge resource table"; -R applies overlay semantics instead,
# where the last conflicting definition wins. So: deepest transitive dependency
# as the base, shallower ones layered over it, and the app last so it can always
# override a library.
step "aapt2 link"
EXTRA=""
for p in $DEP_PKGS; do EXTRA="$EXTRA --extra-packages $p"; done

OVERLAYS=""; BASE=""
for z in $LIB_ZIPS; do
    if [ -z "$BASE" ]; then BASE="$z"; else OVERLAYS="$OVERLAYS -R $z"; fi
done
if [ -n "$BASE" ]; then
    OVERLAYS="$OVERLAYS -R $OUT/res.zip"     # app overlays every library
else
    BASE="$OUT/res.zip"                      # no deps: app is the only input
fi

"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP_MANIFEST" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --auto-add-overlay \
    $EXTRA \
    $OVERLAYS \
    "$BASE"

# --- 3. compile ----------------------------------------------------------
# javac if a full JDK is present; otherwise ecj, which runs on a plain JRE.
# One merged jar beats 58 separate zips for ecj to open and index.
CP="$ANDROID_JAR"
if [ -n "$CPJAR" ] && [ -s "$CPJAR" ]; then
    CP="$CP:$CPJAR"
    echo "   classpath: android.jar + 1 merged library jar"
else
    for j in $DEP_JARS; do CP="$CP:$j"; done
fi

find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if [ -x "$JAVAC" ]; then
    step "javac"
    wc -l < "$OUT/sources.txt" | xargs echo "   sources:"
    "$JAVAC" \
        -source 8 -target 8 -nowarn \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$CP" \
        -d "$OUT/classes" \
        @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path' || true
else
    step "ecj (no JDK — compiling on the JRE)"
    wc -l < "$OUT/sources.txt" | xargs echo "   sources:"
    "$JAVA" -jar "$SDK/lib/ecj.jar" \
        -source 8 -target 8 -nowarn -proc:none \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$CP" \
        -d "$OUT/classes" \
        @"$OUT/sources.txt"
fi

# --- 4. dex --------------------------------------------------------------
# Library jars are dexed alongside the app's classes. With AndroidX in play the
# method count runs past the 64k single-dex limit, so d8 emits classes2.dex and
# on - min-api 24 means native multidex, no support library needed.
step "d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# Pre-dexed libraries merge in as dex; without a cache they are dexed here.
if [ -n "$DEXDIR" ] && [ -f "$DEXDIR/.complete" ]; then
    ls "$DEXDIR"/*.dex >> "$OUT/classes.txt"
    echo "   inputs: app classes + $(ls "$DEXDIR"/*.dex | wc -l) pre-dexed library dex"
else
    for j in $DEP_JARS; do echo "$j" >> "$OUT/classes.txt"; done
    echo "   inputs: $(wc -l < "$OUT/classes.txt")  (no dex cache - dexing libraries now)"
fi
"$JAVA" -Xmx1200m -XX:+UseSerialGC -cp "$SDK/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    --release \
    --output "$OUT/dex" \
    @"$OUT/classes.txt"
ls "$OUT/dex"

# --- 5. put the dex files into the apk ----------------------------------
step "packaging"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT/unsigned.apk" "$OUT/dex" <<'PY'
import os, sys, zipfile
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for name in sorted(os.listdir(dexdir)):
        if name.endswith('.dex'):
            z.write(os.path.join(dexdir, name), name)
    print('   entries:', len(z.namelist()))
PY

# --- 6. align ------------------------------------------------------------
step "zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$ZIPALIGN" -c -v 4 "$OUT/aligned.apk" > /dev/null && echo "   alignment OK"

# --- 7. sign -------------------------------------------------------------
step "apksigner"
"$JAVA" -Xmx256m -jar "$SDK/lib/apksigner.jar" sign \
    --ks "$SDK/debug.keystore" \
    --ks-pass pass:boxbox --key-pass pass:boxbox \
    --ks-key-alias boxkey \
    --min-sdk-version "$MIN_SDK" \
    --out "$OUT/$NAME.apk" \
    "$OUT/aligned.apk"

"$JAVA" -jar "$SDK/lib/apksigner.jar" verify -v "$OUT/$NAME.apk" | head -6

# --- 8. report -----------------------------------------------------------
step "result"
"$AAPT2" dump badging "$OUT/$NAME.apk" | head -4
echo ""
ls -la "$OUT/$NAME.apk"
echo ""
echo "APK: $OUT/$NAME.apk"

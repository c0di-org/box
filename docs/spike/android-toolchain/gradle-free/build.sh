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

rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes,dex}

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
step "aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

# --- 2. link -> base apk + R.java --------------------------------------
step "aapt2 link"
"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --auto-add-overlay \
    "$OUT/res.zip"

# --- 3. compile ----------------------------------------------------------
# javac if a full JDK is present; otherwise ecj, which runs on a plain JRE.
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if [ -x "$JAVAC" ]; then
    step "javac"
    wc -l < "$OUT/sources.txt" | xargs echo "   sources:"
    "$JAVAC" \
        -source 8 -target 8 -nowarn \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$ANDROID_JAR" \
        -d "$OUT/classes" \
        @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path' || true
else
    step "ecj (no JDK — compiling on the JRE)"
    wc -l < "$OUT/sources.txt" | xargs echo "   sources:"
    "$JAVA" -jar "$SDK/lib/ecj.jar" \
        -source 8 -target 8 -nowarn -proc:none \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$ANDROID_JAR" \
        -d "$OUT/classes" \
        @"$OUT/sources.txt"
fi

# --- 4. dex --------------------------------------------------------------
step "d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$JAVA" -Xmx512m -cp "$SDK/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    --release \
    --output "$OUT/dex" \
    @"$OUT/classes.txt"
ls -la "$OUT/dex"

# --- 5. put classes.dex into the apk ------------------------------------
step "packaging"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
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

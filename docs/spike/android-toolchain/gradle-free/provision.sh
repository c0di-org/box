#!/bin/bash
# Provision an Android build toolchain that runs on ARM64 Linux, from nothing.
#
# Why this script exists: Google ships Android's build-tools as x86_64 Linux
# binaries only, so the usual `sdkmanager` route produces a toolchain that
# cannot execute here. This assembles a working one from three sources and
# downloads about a third of what the obvious approach would.
#
#   aapt2 / zipalign   native ARM64 builds (Google ships no ARM64 equivalent)
#   d8 / apksigner     pure Java, lifted out of Google's x86_64 zip unchanged
#   ecj                compiles Java, so a JRE suffices and no JDK is needed
#   android.jar        API stubs to compile against
#
# Needs: curl, python3, tar. No root, no apt, no Gradle, no Android Studio.
#
#   ./provision.sh [prefix]     default prefix: /workspace/android
set -eu

PREFIX=${1:-/workspace/android}
CACHE=${CACHE:-/tmp/andl}
HERE=$(cd "$(dirname "$0")" && pwd)

JRE_URL=https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jre_aarch64_linux_hotspot_17.0.20.1_1.tar.gz
ECJ_URL=https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar
TOOLS_URL=https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
BUILDTOOLS_ZIP=https://dl.google.com/android/repository/build-tools_r35.0.1_linux.zip
PLATFORM_ZIP=https://dl.google.com/android/repository/platform-35_r02.zip

mkdir -p "$PREFIX" "$CACHE" "$PREFIX/lib"
cd "$PREFIX"

say() { echo "[$(date +%H:%M:%S)] $*"; }

get() { # url dest
  [ -s "$2" ] && return 0
  curl -sL --retry 3 --retry-delay 2 -o "$2.part" "$1" && mv "$2.part" "$2"
}

# 1. a JRE. Not a JDK: ecj below does the compiling, and the JRE is 46 MB
#    against the JDK's 192 MB. keytool ships in it, which is all we need
#    beyond `java` itself.
if [ ! -x "$PREFIX/jre/bin/java" ]; then
  say "fetching JRE (46 MB)"
  get "$JRE_URL" "$CACHE/jre.tar.gz"
  rm -rf .jretmp && mkdir -p .jretmp
  tar xzf "$CACHE/jre.tar.gz" -C .jretmp
  mv .jretmp/* jre && rmdir .jretmp
fi
say "java: $("$PREFIX/jre/bin/java" -version 2>&1 | head -1)"

# 2. the Eclipse batch compiler - a single jar that runs on a plain JRE.
if [ ! -s "$PREFIX/lib/ecj.jar" ]; then
  say "fetching ecj (3 MB)"
  get "$ECJ_URL" "$PREFIX/lib/ecj.jar"
fi

# 3. android.jar, read out of a 64 MB zip without downloading the other 39 MB.
if [ ! -s "$PREFIX/platforms/android-35/android.jar" ]; then
  say "fetching android.jar by ranged read"
  python3 "$HERE/zipget.py" "$PLATFORM_ZIP" "$PREFIX/platforms/android-35" /android.jar
fi

# 4. d8 + apksigner. Google's zip is x86_64, but these two members are pure
#    Java and run anywhere - so take only them and drop the native binaries.
if [ ! -s "$PREFIX/lib/d8.jar" ]; then
  say "fetching d8 + apksigner by ranged read"
  python3 "$HERE/zipget.py" "$BUILDTOOLS_ZIP" "$PREFIX/lib" lib/d8.jar lib/apksigner.jar
fi

# 5. the native ARM64 tools that have no official equivalent.
if [ ! -x "$PREFIX/build-tools/aapt2" ]; then
  say "fetching ARM64 aapt2 / zipalign (14 MB)"
  get "$TOOLS_URL" "$CACHE/arm64tools.zip"
  python3 -c "
import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" \
    "$CACHE/arm64tools.zip" "$PREFIX"
  chmod +x build-tools/* platform-tools/* 2>/dev/null || true
  rm -rf "$PREFIX/others"
fi

# 6. a signing key. Self-signed is what sideloading wants.
if [ ! -f "$PREFIX/debug.keystore" ]; then
  say "generating signing key"
  "$PREFIX/jre/bin/keytool" -genkeypair \
    -keystore "$PREFIX/debug.keystore" -storepass boxbox -keypass boxbox \
    -alias boxkey -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Built in the Box, O=Box, C=GB" >/dev/null 2>&1
fi

# --- prove each piece actually executes, rather than merely existing -------
say "verifying"
fail=0
check() { # label command...
  if out=$("${@:2}" 2>&1 | head -1); then
    printf '  %-12s %s\n' "$1" "${out:-ok}"
  else
    printf '  %-12s FAILED\n' "$1"; fail=1
  fi
}
check aapt2    "$PREFIX/build-tools/aapt2" version
check zipalign "$PREFIX/build-tools/zipalign"
check java     "$PREFIX/jre/bin/java" -version
check ecj      "$PREFIX/jre/bin/java" -jar "$PREFIX/lib/ecj.jar" -version
check d8       "$PREFIX/jre/bin/java" -cp "$PREFIX/lib/d8.jar" com.android.tools.r8.D8 --version
check apksigner "$PREFIX/jre/bin/java" -jar "$PREFIX/lib/apksigner.jar" version
[ -s "$PREFIX/platforms/android-35/android.jar" ] \
  && printf '  %-12s %s\n' android.jar "$(du -h "$PREFIX/platforms/android-35/android.jar" | cut -f1)" \
  || { echo "  android.jar  MISSING"; fail=1; }

[ "$fail" = 0 ] || { echo "provisioning incomplete"; exit 1; }
say "ready - $(du -sh "$PREFIX" | cut -f1) at $PREFIX"

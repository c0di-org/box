#!/usr/bin/env bash
# Reproduces the aarch64 Android-toolchain spike documented in
# docs/spike-android-toolchain.md.
#
# Runs in an arm64 Debian bookworm container, NOT in the emulated guest — this
# deliberately isolates the toolchain question from emulation performance.
# On Apple Silicon the container is native arm64 and therefore fast.
#
#   ./repro.sh provision    # container + JDK + SDK + Gradle + arm64 aapt2
#   ./repro.sh arch         # show that Google ships x86-64 only  (evidence for Q1)
#   ./repro.sh build        # build the Java hello-world APK      (the Q1 gate)
#   ./repro.sh negative     # same build without the aapt2 override -> must fail
#   ./repro.sh kotlin       # Kotlin + AndroidX + R8 build
#   ./repro.sh tcg          # measure QEMU TCG slowdown           (evidence for Q2)
#   ./repro.sh memory       # memory sweep: find the build's RAM floor (Q2)
#   ./repro.sh all
#   ./repro.sh clean
set -euo pipefail

CONTAINER=box-toolchain-spike
IMAGE=debian:bookworm
PLATFORM=linux/arm64
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Pinned toolchain versions. These are the exact versions the spike validated;
# see the manifest table in docs/spike-android-toolchain.md.
CMDLINE_TOOLS=commandlinetools-linux-15859902_latest.zip
GRADLE_VERSION=8.14.3
BUILD_TOOLS=36.0.0
PLATFORM_API=android-36

# The crux: Google publishes no arm64 aapt2, so it comes from a community
# cross-build. Pinned by sha256 -- never fetch this unverified.
ARM64_TOOLS_TAG=platform-tools-36.0.0
ARM64_TOOLS_BASE=https://github.com/Commit451/android-arm-build-tools/releases/download/$ARM64_TOOLS_TAG
AAPT2_SHA256=7512ff7e381bea6fd310b6f6e347422c8fda21e07c6e3f0162742e95eb9d7f98

JAVA_HOME_GUEST=/usr/lib/jvm/java-17-openjdk-arm64
SDK=/opt/android-sdk
ARM64_TOOLS=$SDK/arm64

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# Run a command inside the container with the toolchain env set up.
inc() {
  docker exec "$CONTAINER" bash -c "
    export JAVA_HOME=$JAVA_HOME_GUEST
    export ANDROID_HOME=$SDK
    export PATH=\$JAVA_HOME/bin:/opt/gradle/bin:$SDK/cmdline-tools/latest/bin:\$PATH
    set -euo pipefail
    $1"
}

running() { [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = "true" ]; }

provision() {
  say "Starting $PLATFORM container"
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$CONTAINER" --platform "$PLATFORM" \
    -v "$HERE:/spike:ro" -v "$CONTAINER-work:/work" "$IMAGE" sleep infinity >/dev/null
  docker exec "$CONTAINER" bash -c 'uname -m; cat /etc/debian_version; ldd --version | head -1'

  say "Installing Debian packages"
  docker exec "$CONTAINER" bash -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq openjdk-17-jdk-headless curl unzip zip xz-utils ca-certificates git >/dev/null
    dpkg-query -W -f='"'"'${Package} ${Version}\n'"'"' openjdk-17-jdk-headless curl unzip zip xz-utils ca-certificates git'

  say "Installing Android cmdline-tools + platform $PLATFORM_API + build-tools $BUILD_TOOLS"
  inc "
    mkdir -p $SDK/cmdline-tools
    curl -sSL -o /tmp/cmdline.zip https://dl.google.com/android/repository/$CMDLINE_TOOLS
    unzip -q -o /tmp/cmdline.zip -d $SDK/cmdline-tools
    mv $SDK/cmdline-tools/cmdline-tools $SDK/cmdline-tools/latest 2>/dev/null || true
    yes | sdkmanager --licenses >/dev/null 2>&1 || true
    sdkmanager --install 'platforms;$PLATFORM_API' 'build-tools;$BUILD_TOOLS' >/dev/null 2>&1
    echo 'installed:'; ls $SDK/build-tools/"

  say "Installing Gradle $GRADLE_VERSION (Debian ships 4.4.1 -- far too old for AGP 8.x)"
  inc "
    curl -sSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip
    unzip -q -o /tmp/gradle.zip -d /opt
    ln -sfn /opt/gradle-$GRADLE_VERSION /opt/gradle
    /opt/gradle/bin/gradle --version | grep -E '^Gradle'"

  say "Installing arm64 aapt2 (the one thing Google does not ship)"
  inc "
    mkdir -p $ARM64_TOOLS && cd $ARM64_TOOLS
    for f in aapt2 aidl zipalign split-select; do curl -sSL -o \$f $ARM64_TOOLS_BASE/\$f; done
    chmod +x aapt2 aidl zipalign split-select
    echo '$AAPT2_SHA256  aapt2' | sha256sum -c -
    ./aapt2 version"

  say "Provisioned"
}

arch_evidence() {
  say "Q1 evidence: architecture of Google-shipped build-tools binaries"
  inc "
    for b in aapt aapt2 aidl zipalign split-select; do
      m=\$(od -An -tx1 -j18 -N2 $SDK/build-tools/$BUILD_TOOLS/\$b | tr -d ' ')
      case \"\$m\" in 3e00) a='x86-64';; b700) a='AArch64';; *) a=\"unknown(\$m)\";; esac
      printf '  %-22s %s\n' \"\$b (Google)\" \"\$a\"
    done
    for b in aapt2 zipalign; do
      m=\$(od -An -tx1 -j18 -N2 $ARM64_TOOLS/\$b | tr -d ' ')
      case \"\$m\" in 3e00) a='x86-64';; b700) a='AArch64';; *) a=\"unknown(\$m)\";; esac
      printf '  %-22s %s\n' \"\$b (Commit451)\" \"\$a\"
    done"

  say "Confirming x86-64 binaries genuinely cannot run here"
  # This guards against a false positive: Docker Desktop on Apple Silicon can
  # route x86-64 ELFs through Rosetta. The arm64 rootfs has no x86-64 loader,
  # so they cannot execute -- meaning a successful build is truly native aarch64.
  inc "ls -l /lib64/ld-linux-x86-64.so.2 2>&1 || echo '  no x86-64 loader present -> x86-64 cannot execute'"
  inc "$SDK/build-tools/$BUILD_TOOLS/aapt2 version 2>&1 | head -2 || true"

  say "Java-based tools are arch-agnostic (expected to pass)"
  inc "$SDK/build-tools/$BUILD_TOOLS/d8 --version; $SDK/build-tools/$BUILD_TOOLS/apksigner --version"
}

prep_project() {
  # /spike is mounted read-only; Gradle needs a writable copy.
  inc "rm -rf /work/$1 && mkdir -p /work && cp -r /spike/$1 /work/$1 && echo 'sdk.dir=$SDK' > /work/$1/local.properties"
}

build_java() {
  say "Q1 gate: building the Java hello-world APK"
  prep_project hello
  inc "cd /work/hello && gradle --no-daemon --gradle-user-home=/work/gradle-home assembleDebug 2>&1 | tail -6"
  say "Verifying the APK is real"
  inc "
    cd /work/hello
    APK=app/build/outputs/apk/debug/app-debug.apk
    ls -l \$APK
    unzip -l \$APK | sed -n '3,12p'
    $ARM64_TOOLS/aapt2 dump badging \$APK | head -4
    $ARM64_TOOLS/aapt2 dump resources \$APK | grep -E 'string/|layout/|id/' | head
    $SDK/build-tools/$BUILD_TOOLS/apksigner verify --verbose \$APK | head -4"
}

negative_control() {
  say "Negative control: same build WITHOUT the aapt2 override (must fail)"
  prep_project hello
  # A failure here is the expected, desired outcome, so the exit code is
  # inspected explicitly rather than letting pipefail conflate the two.
  inc "
    cd /work/hello
    grep -v aapt2FromMavenOverride gradle.properties > gp && mv gp gradle.properties
    rc=0
    gradle --no-daemon --gradle-user-home=/work/gradle-home assembleDebug >/tmp/neg.log 2>&1 || rc=\$?
    grep -A5 'What went wrong' /tmp/neg.log || true
    echo
    if [ \$rc -ne 0 ]; then
      echo '  EXPECTED: build failed without the aapt2 override -> the override is load-bearing'
    else
      echo '  UNEXPECTED: build succeeded without the override. Check that x86-64 binaries'
      echo '  are not being emulated (Rosetta/binfmt) -- see ./repro.sh arch'
    fi"
}

build_kotlin() {
  say "Kotlin + AndroidX + R8 (needs more RAM than the Java app; floor unmeasured -- see findings 4.4)"
  prep_project hello-kt
  inc "cd /work/hello-kt && gradle --no-daemon --gradle-user-home=/work/gradle-home assembleRelease 2>&1 | tail -6"
  inc "ls -l /work/hello-kt/app/build/outputs/apk/release/ 2>/dev/null || true"
}

tcg_measurement() {
  say "Q2 evidence: QEMU TCG slowdown (qemu-user, aarch64-on-aarch64 still translates)"
  inc "export DEBIAN_FRONTEND=noninteractive; apt-get install -y -qq qemu-user-static gcc >/dev/null 2>&1 || true"
  inc "
    cd /tmp
    printf '#include <stdio.h>\n#include <stdint.h>\nint main(){volatile uint64_t a=0;for(uint64_t i=0;i<300000000ULL;i++){a=a*6364136223846793005ULL+i;}printf(\"%%llu\\\\n\",(unsigned long long)a);return 0;}\n' > bench.c
    gcc -O2 -o bench bench.c
    printf 'public class T{public static void main(String[] a){System.out.println(1);}}\n' > T.java

    bench_one() {
      local label=\"\$1\"; shift
      local s e n t
      s=\$(date +%s%N); \"\$@\" >/dev/null 2>&1; e=\$(date +%s%N); n=\$(( (e-s)/1000000 ))
      s=\$(date +%s%N); qemu-aarch64-static \"\$@\" >/dev/null 2>&1; e=\$(date +%s%N); t=\$(( (e-s)/1000000 ))
      awk -v l=\"\$label\" -v n=\$n -v t=\$t 'BEGIN{printf \"  %-26s native %6dms   TCG %6dms   %.1fx\n\", l, n, t, t/n}'
    }
    bench_one 'tight integer loop' /tmp/bench
    bench_one 'JVM startup'        $JAVA_HOME_GUEST/bin/java -version
    bench_one 'javac small class'  $JAVA_HOME_GUEST/bin/javac -d /tmp/o /tmp/T.java
    bench_one 'aapt2 compile res'  $ARM64_TOOLS/aapt2 compile --dir /spike/hello/app/src/main/res -o /tmp/r.zip"
  echo
  echo "  The tight loop is TCG's best case and is misleading; the JVM/aapt2"
  echo "  figures (~10-15x) are the ones that describe a real build."
}

memory_sweep() {
  say "Q2: memory floor. Measures cgroup memory.current, which INCLUDES page cache --\n     see findings 4.4 before trusting the peak column."
  local vol=$CONTAINER-sweep
  for mem in 1g 1536m 2g 3g 4g; do
    for proj in hello hello-kt; do
      docker rm -f "$CONTAINER-mem" >/dev/null 2>&1 || true
      docker run -d --name "$CONTAINER-mem" --platform "$PLATFORM" \
        --cpuset-cpus="0,1" --memory=$mem --memory-swap=$mem \
        -v "$HERE:/spike:ro" -v "$vol:/work" \
        "$CONTAINER-img" sleep infinity >/dev/null
      res=$(docker exec "$CONTAINER-mem" bash -c "
        export JAVA_HOME=$JAVA_HOME_GUEST ANDROID_HOME=$SDK
        export PATH=\$JAVA_HOME/bin:/opt/gradle/bin:\$PATH
        rm -rf /work/$proj && cp -r /spike/$proj /work/$proj
        echo 'sdk.dir=$SDK' > /work/$proj/local.properties
        cd /work/$proj
        ( p=0; while true; do c=\$(cat /sys/fs/cgroup/memory.current 2>/dev/null||echo 0); [ \"\$c\" -gt \"\$p\" ] && p=\$c && echo \$p>/tmp/peak; sleep 0.3; done ) & s=\$!
        gradle --no-daemon --gradle-user-home=/work/gradle-home assembleDebug >/tmp/b.log 2>&1; rc=\$?
        kill \$s 2>/dev/null
        [ \$rc -eq 0 ] && m=SUCCESS || m='FAILED (daemon OOM-killed)'
        echo \"\$m  peak=\$(( \$(cat /tmp/peak)/1024/1024 ))MiB\"" 2>/dev/null | tail -1)
      printf '  %-8s %-9s %s\n' "$mem" "$proj" "$res"
    done
  done
  docker rm -f "$CONTAINER-mem" >/dev/null 2>&1 || true
}

case "${1:-all}" in
  provision) provision ;;
  arch)      arch_evidence ;;
  build)     build_java ;;
  negative)  negative_control ;;
  kotlin)    build_kotlin ;;
  tcg)       tcg_measurement ;;
  memory)
    running || { echo "run './repro.sh provision' first" >&2; exit 1; }
    say "Snapshotting provisioned container for the sweep"
    docker commit "$CONTAINER" "$CONTAINER-img" >/dev/null
    memory_sweep ;;
  all)
    provision; arch_evidence; build_java; negative_control; build_kotlin; tcg_measurement
    say "Snapshotting provisioned container for the sweep"
    docker commit "$CONTAINER" "$CONTAINER-img" >/dev/null
    memory_sweep ;;
  clean)
    docker rm -f "$CONTAINER" "$CONTAINER-mem" >/dev/null 2>&1 || true
    docker rmi "$CONTAINER-img" >/dev/null 2>&1 || true
    docker volume rm "$CONTAINER-work" "$CONTAINER-sweep" >/dev/null 2>&1 || true
    echo "cleaned" ;;
  *) sed -n '2,20p' "$0"; exit 1 ;;
esac

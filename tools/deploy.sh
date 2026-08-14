#!/usr/bin/env bash
set -euo pipefail

# Build Box and put it on the phone.
#
#   tools/deploy.sh                 build the APK, install it, launch it
#   tools/deploy.sh --image         rebuild the guest image first
#   tools/deploy.sh --wipe          drop the installed guest, workspace included, and start clean
#   tools/deploy.sh --no-launch     install only
#   tools/deploy.sh --no-fetch      skip the up-to-date check and deploy this checkout as it stands
#
# Three things about this cycle are easy to get wrong, and all three are handled here rather than
# remembered:
#
#   1. A git worktree has no local.properties, and Gradle fails on a missing SDK rather than
#      finding the one two directories up.
#   2. A phone attached over both USB and Wi-Fi is two adb devices, and every adb command without
#      -s fails with "more than one device".
#   3. A checkout that is behind its remote builds and installs perfectly happily. Nothing fails,
#      and the app on the phone looks right -- it is simply not the code you meant to test, and
#      you find that out by testing behaviour that was fixed a week ago. The check costs a fetch;
#      being wrong about it costs the whole cycle, image and 950 MB install included.
#
# `--image` used to imply `--wipe`, and no longer does. A guest image now carries an id and a
# version derived from its own contents, so a rebuilt one is a different image and the app installs
# it on the next start -- replacing the kernel, initrd and system disk, and keeping /workspace. The
# old coupling existed because none of that was true: the disks were preserved by filename alone,
# so a new image was silently ignored and uninstalling was the only way to test one. `--wipe` is
# still here for when you want the workspace gone too, but it is now a choice rather than the toll
# for touching guest/.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PACKAGE="dev.localagent.workstation.stock"
ACTIVITY="$PACKAGE/dev.localagent.workstation.MainActivity"
APK="app/build/outputs/apk/stock/debug/app-stock-debug.apk"

build_image=0
wipe=0
launch=1
fetch=1
for argument in "$@"; do
  case "$argument" in
    --image) build_image=1 ;;
    --wipe) wipe=1 ;;
    --no-launch) launch=0 ;;
    --no-fetch) fetch=0 ;;
    -h|--help) sed -n '3,30p' "$0"; exit 0 ;;
    *) echo "unknown option: $argument" >&2; exit 2 ;;
  esac
done

say() { printf '\n\033[1;32m==>\033[0m %s\n' "$1"; }
warn() { printf '\n\033[1;31m!!\033[0m %s\n' "$1"; }

# --- the branch -------------------------------------------------------------------------------
# Ask the remote before spending ten minutes building. A checkout that is behind produces a
# perfectly good APK of the wrong code, and the phone gives you no hint: the app launches, the
# screens render, and only the behaviour you came to check is missing. Offline is not an error
# here -- a deploy on a train should still work -- so a failed fetch says so and carries on.
if (( fetch )) && git rev-parse --git-dir >/dev/null 2>&1; then
  upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)"
  if [[ -z "$upstream" ]]; then
    say "No upstream for this branch; building $(git rev-parse --short HEAD) as it stands."
  elif ! git fetch --quiet 2>/dev/null; then
    say "Could not reach the remote; building $(git rev-parse --short HEAD) as it stands."
  else
    behind="$(git rev-list --count "HEAD..$upstream" 2>/dev/null || echo 0)"
    if (( behind > 0 )); then
      warn "This checkout is $behind commit(s) behind $upstream:"
      git log --oneline "HEAD..$upstream" | sed 's/^/    /'
      cat <<EOF

Deploying now installs an APK missing all of that. If any of it touches guest/, the image
would be behind as well, and a new host against an old guest fails at the handshake rather
than at the build.

  git merge --ff-only $upstream
  tools/deploy.sh $* --no-fetch      to deploy this checkout anyway

EOF
      exit 1
    fi
    say "Up to date with $upstream ($(git rev-parse --short HEAD))"
  fi
fi

# --- the device -------------------------------------------------------------------------------
# One phone on both transports answers twice. Prefer USB: it is faster for an 800 MB install and
# does not drop when the Wi-Fi roams. Set BOX_DEVICE to override.
if [[ -z "${BOX_DEVICE:-}" ]]; then
  # Kept to POSIX-ish shell rather than mapfile: macOS still ships bash 3.2.
  attached="$(adb devices | awk '/\tdevice$/ {print $1}')"
  count="$(printf '%s\n' "$attached" | grep -c . || true)"
  if [[ "$count" -eq 0 ]]; then
    echo 'No device. Plug the phone in, or check wireless debugging is paired.' >&2
    exit 1
  elif [[ "$count" -eq 1 ]]; then
    BOX_DEVICE="$attached"
  else
    BOX_DEVICE="$(printf '%s\n' "$attached" | grep -v '_adb-tls-connect' | head -1 || true)"
    [[ -n "$BOX_DEVICE" ]] || BOX_DEVICE="$(printf '%s\n' "$attached" | head -1)"
    echo "Several transports for this phone; using $BOX_DEVICE (set BOX_DEVICE to change)."
  fi
fi
adb() { command adb -s "$BOX_DEVICE" "$@"; }

# --- the Android SDK --------------------------------------------------------------------------
if [[ ! -f local.properties ]]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  [[ -d "$sdk" ]] || { echo "No Android SDK at $sdk. Set ANDROID_HOME." >&2; exit 1; }
  say "Writing local.properties (this checkout had none)"
  printf 'sdk.dir=%s\n' "$sdk" > local.properties
fi

# --- the guest image --------------------------------------------------------------------------
if (( build_image )); then
  say 'Rebuilding the guest image (several minutes; needs Docker running)'
  ./guest/build-container.sh
fi
if [[ ! -f guest/image/out/image.json ]]; then
  echo 'No guest image in guest/image/out. Run with --image to build one.' >&2
  exit 1
fi
say "Guest image $(sed -n 's/.*"id": "\([^"]*\)".*/\1/p' guest/image/out/image.json | head -1)@$(sed -n 's/.*"version": "\([^"]*\)".*/\1/p' guest/image/out/image.json | head -1)"

# --- build and install ------------------------------------------------------------------------
say 'Building the APK'
./gradlew :app:assembleStockDebug -q

if (( wipe )); then
  say 'Removing the installed app, its guest image and the workspace'
  adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
fi

# Pushed as a file and installed on the device, rather than streamed by `adb install`.
#
# The APK is about a gigabyte, almost all of it the guest image, and streaming one that size to
# this phone is the slow and fragile path: an install left running overnight was still going
# eight hours later and then reported failure, and the incremental installer separately refused
# with INSTALL_FAILED_MEDIA_UNAVAILABLE. The same bytes cross as an ordinary file in about
# twenty seconds, after which `pm install` takes three.
say "Installing $(du -h "$APK" | cut -f1) to $BOX_DEVICE"
staged="/data/local/tmp/box-install-$$.apk"
adb push "$APK" "$staged" >/dev/null
# `pm` reports a refused install by printing Failure and still exiting 0 on some builds, so the
# output is the result rather than the status. Cleared first either way: a gigabyte left behind
# in /data/local/tmp is the sort of thing nobody finds until the phone is full.
install_output="$(adb shell pm install -r -t "$staged" 2>&1 | tr -d '\r')"
adb shell rm -f "$staged" >/dev/null 2>&1 || true
printf '%s\n' "$install_output"
grep -q '^Success' <<<"$install_output" || { echo 'The install did not succeed.' >&2; exit 1; }

if (( launch )); then
  say 'Launching'
  adb shell am start -n "$ACTIVITY" >/dev/null
fi

if (( wipe )); then
  cat <<'EOF'

The guest disk was dropped, so the app will provision a fresh one on first start and
the first boot takes a couple of minutes. Tap "Set up", then "Start".
EOF
elif (( build_image )); then
  cat <<'EOF'

The rebuilt image has a new version, so the app installs it the next time the box is
started -- the system disk is replaced and /workspace is kept. Watch for the
"Provisioning guest image" line in logcat.

To reinstall the same version over itself, still keeping /workspace:

  adb shell am start -n dev.localagent.workstation.stock/dev.localagent.workstation.VmProbeActivity \
    --es runtime_action dev.localagent.runtime.qemu.REPROVISION_IMAGE
EOF
fi

say 'Done. Follow the runtime with:'
echo "  adb -s $BOX_DEVICE logcat -s LocalAgentRuntime:I LocalAgentQemu:I BoxGuestSerial:D"

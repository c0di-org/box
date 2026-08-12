#!/usr/bin/env bash
set -euo pipefail

# Build Box and put it on the phone.
#
#   tools/deploy.sh                 build the APK, install it, launch it
#   tools/deploy.sh --image         rebuild the guest image first
#   tools/deploy.sh --wipe          drop the installed guest, workspace included, and start clean
#   tools/deploy.sh --no-launch     install only
#
# Two things about this cycle are easy to get wrong, and both are handled here rather than
# remembered:
#
#   1. A git worktree has no local.properties, and Gradle fails on a missing SDK rather than
#      finding the one two directories up.
#   2. A phone attached over both USB and Wi-Fi is two adb devices, and every adb command without
#      -s fails with "more than one device".
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
for argument in "$@"; do
  case "$argument" in
    --image) build_image=1 ;;
    --wipe) wipe=1 ;;
    --no-launch) launch=0 ;;
    -h|--help) sed -n '3,25p' "$0"; exit 0 ;;
    *) echo "unknown option: $argument" >&2; exit 2 ;;
  esac
done

say() { printf '\n\033[1;32m==>\033[0m %s\n' "$1"; }

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

say "Installing $(du -h "$APK" | cut -f1) to $BOX_DEVICE"
adb install -r "$APK"

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

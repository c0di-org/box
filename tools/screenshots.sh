#!/usr/bin/env bash
set -euo pipefail

# Take the pictures in the README.
#
#   tools/screenshots.sh                    every shot, phone and tablet, dark and light
#   tools/screenshots.sh --device phone     one emulator only
#   tools/screenshots.sh --scene chat       one scene, everywhere it appears
#   tools/screenshots.sh --keep             leave the emulators running afterwards
#
# What this is actually doing, because none of it is obvious:
#
#   1. It photographs `UiGalleryActivity`, not the app's normal entry point. Box's screens are
#      about a Linux machine, an emulator has no Linux machine, and the states worth showing --
#      a box opening, an agent asking permission, a finished piece of work -- are exactly the
#      ones nobody can reach on a desk. The gallery plays the app's own scripted backend at zero
#      pace and folds it through the real transcript builder, so these are photographs of the
#      shipping UI with the demo conversation in it, not a mockup and not a hand-drawn state.
#   2. It builds the `avf` flavor, whose only purpose here is that it carries no guest image.
#      `stock` refuses to build without a 500 MB qcow2 that is neither in git nor of any use to
#      an emulator that cannot run it.
#   3. The tablet is a real second emulator rather than a resized phone, because the layout that
#      needs proving -- list beside transcript -- is chosen from the window, and a window is the
#      one thing `wm size` does not honestly change.
#   4. SystemUI on a software-rendered emulator ANRs roughly whenever it feels like it, and that
#      dialog lands in the middle of the screenshot. Every shot closes system dialogs first.
#
# Adding a scene: add it to SCENES in UiGalleryActivity.kt, implement it in GalleryModel.enter,
# then add a line to SHOTS below.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# --- what to take -----------------------------------------------------------------------------
# "<device> <scene> <scroll-ups> <name>", one picture per line, taken in both themes unless
# --theme narrows it. A conversation opens at its newest message, so a shot that wants the middle
# of one asks for a few scrolls back up; that is what the third field is, and the fourth is the
# name it lands under, since two shots can share a scene.
SHOTS=(
  "phone opening 0 opening"      # the honest wait, with work already queued behind it
  "phone tasks 0 tasks"          # one box, several agents, one list
  "phone chat 3 progress"        # the plain-language checklist, mid-run
  "phone chat 0 chat"            # and how it ended
  "phone permission 0 permission" # the moment before it edits a file
  "phone subagent 0 subagent"    # a sub-agent working, and the one control that stops just it
  "phone github-ask 0 github-ask"     # an agent mid-clone, asking for the one thing it cannot get
  "phone github-code 0 github-code"   # eight characters, already on the clipboard
  "phone github-repos 0 github-repos" # and the step that is actually about trust
  "phone terminal 0 terminal"    # the machine, driven directly
  "tablet chat 3 progress"       # unfolded: the list keeps its place beside the conversation
  "tablet chat 0 chat"
)

PACKAGE="dev.localagent.workstation.avf"
ACTIVITY="$PACKAGE/dev.localagent.workstation.UiGalleryActivity"
APK="app/build/outputs/apk/avf/debug/app-avf-debug.apk"
OUT_DIR="docs/assets/screenshots"

PHONE_AVD="${BOX_PHONE_AVD:-Medium_Phone_API_36.1}"
TABLET_AVD="${BOX_TABLET_AVD:-Box_Tablet_API36}"
SYSTEM_IMAGE="${BOX_SYSTEM_IMAGE:-system-images;android-36.1;google_apis_playstore;arm64-v8a}"
PHONE_PORT=5584
TABLET_PORT=5586

devices="both"
themes="both"
only_scene=""
keep=0
build=1
while (( $# )); do
  case "$1" in
    --device) shift; devices="${1:-both}" ;;
    --device=*) devices="${1#*=}" ;;
    --phone) devices="phone" ;;
    --tablet) devices="tablet" ;;
    --theme) shift; themes="${1:-both}" ;;
    --theme=*) themes="${1#*=}" ;;
    --dark) themes="dark" ;;
    --light) themes="light" ;;
    --scene) shift; only_scene="${1:-}" ;;
    --scene=*) only_scene="${1#*=}" ;;
    --keep) keep=1 ;;
    --no-build) build=0 ;;
    -h|--help) sed -n '3,30p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
[[ "$themes" == "both" ]] && themes="dark light"
[[ "$devices" == "both" ]] && devices="phone tablet"

say() { printf '\n\033[1;32m==>\033[0m %s\n' "$1"; }
note() { printf '    %s\n' "$1"; }
fail() { printf '\033[1;31m!!\033[0m %s\n' "$1" >&2; exit 1; }

# --- the SDK ----------------------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[[ -d "$SDK" ]] || fail "No Android SDK at $SDK. Set ANDROID_HOME."
EMULATOR="$SDK/emulator/emulator"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
[[ -x "$EMULATOR" ]] || fail "No emulator at $EMULATOR."

# A worktree has no local.properties, and Gradle looks for the SDK there before ANDROID_HOME.
if [[ ! -f local.properties ]]; then
  printf 'sdk.dir=%s\n' "$SDK" > local.properties
fi

# --- the APK ----------------------------------------------------------------------------------
if (( build )); then
  say 'Building the gallery APK'
  ./gradlew :app:assembleAvfDebug -q
fi
[[ -f "$APK" ]] || fail "No APK at $APK. Drop --no-build."

# --- emulators --------------------------------------------------------------------------------

ensure_avd() {
  local name="$1" profile="$2"
  if "$EMULATOR" -list-avds | grep -qx "$name"; then return; fi
  [[ -x "$AVDMANAGER" ]] || fail "No AVD named $name, and no avdmanager to create one."
  say "Creating the $profile AVD ($name)"
  if ! echo no | "$AVDMANAGER" create avd -n "$name" -k "$SYSTEM_IMAGE" -d "$profile" >/dev/null 2>&1; then
    fail "Could not create $name. Install the image first:
    $SDK/cmdline-tools/latest/bin/sdkmanager \"$SYSTEM_IMAGE\""
  fi
}

# Sets SERIAL.
boot() {
  local avd="$1" port="$2"
  SERIAL="emulator-$port"
  if "$ADB" devices | grep -q "^$SERIAL[[:space:]]*device$"; then
    note "reusing $SERIAL"
    return
  fi
  # No snapshots: a restored snapshot brings back the last run's theme and demo-mode state, and
  # a screenshot tool that quietly photographs yesterday is worse than a slow one.
  "$EMULATOR" -avd "$avd" -port "$port" \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    >"${TMPDIR:-/tmp}/box-emulator-$port.log" 2>&1 &

  local waited=0
  until [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
    waited=$(( waited + 2 ))
    (( waited < 420 )) || fail "$avd did not boot in 7 minutes. See ${TMPDIR:-/tmp}/box-emulator-$port.log"
  done
  "$ADB" -s "$SERIAL" shell pm list packages >/dev/null 2>&1 || true
}

shutdown() {
  local port="$1"
  "$ADB" -s "emulator-$port" emu kill >/dev/null 2>&1 || true
}

prepare() {
  local serial="$1"
  local sh=("$ADB" -s "$serial" shell)
  "${sh[@]}" settings put global window_animation_scale 0 >/dev/null
  "${sh[@]}" settings put global transition_animation_scale 0 >/dev/null
  "${sh[@]}" settings put global animator_duration_scale 0 >/dev/null
  "${sh[@]}" settings put global sysui_demo_allowed 1 >/dev/null
  "$ADB" -s "$serial" install -r -t "$APK" >/dev/null
}

# A status bar that says something different in every picture is noise, and a clock that says when
# the screenshot was taken dates the README. Demo mode pins both.
#
# Re-sent before every shot rather than once per boot: SystemUI drops demo mode when it restarts,
# and it restarts more or less whenever it likes on a software-rendered emulator.
pin_status_bar() {
  local serial="$1"
  demo "$serial" command enter
  demo "$serial" command clock -e hhmm 0941
  demo "$serial" command battery -e level 100 -e plugged false
  demo "$serial" command network -e wifi show -e level 4
  demo "$serial" command network -e mobile hide
  demo "$serial" command notifications -e visible false
}

demo() {
  local serial="$1"; shift
  "$ADB" -s "$serial" shell am broadcast -a com.android.systemui.demo -e "$@" >/dev/null 2>&1 || true
}

capture() {
  local serial="$1" scene="$2" theme="$3" device="$4" scrolls="$5" name="$6"
  local target="$OUT_DIR/$device-$name-$theme.png"

  "$ADB" -s "$serial" logcat -c >/dev/null 2>&1 || true
  "$ADB" -s "$serial" shell am start -S -n "$ACTIVITY" --es scene "$scene" >/dev/null 2>&1

  # The gallery says when its scene has settled, which beats sleeping and hoping.
  local waited=0
  until "$ADB" -s "$serial" logcat -d 2>/dev/null | grep -q "BoxUiGallery: scene \"$scene\" ready"; do
    sleep 1
    waited=$(( waited + 1 ))
    if (( waited > 60 )); then
      printf '\033[1;33m??\033[0m %s never reported ready; taking it anyway\n' "$scene" >&2
      break
    fi
  done

  # SystemUI's ANR dialog, if it turned up while the app was starting.
  "$ADB" -s "$serial" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  sleep 2
  "$ADB" -s "$serial" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  sleep 1

  if (( scrolls > 0 )); then
    local size width height
    size="$("$ADB" -s "$serial" shell wm size | tr -d '\r' | awk '{print $NF}')"
    width="${size%x*}"
    height="${size#*x}"
    for (( i = 0; i < scrolls; i++ )); do
      # Slow enough not to fling: a fling settles somewhere different every run.
      "$ADB" -s "$serial" shell input swipe \
        $(( width / 2 )) $(( height / 4 )) $(( width / 2 )) $(( height * 3 / 4 )) 600 >/dev/null
      sleep 1
    done
    sleep 2
  fi

  pin_status_bar "$serial"
  sleep 1
  "$ADB" -s "$serial" exec-out screencap -p > "$target"
  # A screencap that lost the race with something writes a plausible-looking short file.
  local bytes
  bytes="$(wc -c < "$target" | tr -d ' ')"
  (( bytes > 20000 )) || fail "$target came back at $bytes bytes. Is the emulator still up?"
  note "$target ($(du -h "$target" | cut -f1))"
}

mkdir -p "$OUT_DIR"

for device in $devices; do
  case "$device" in
    phone) avd="$PHONE_AVD"; port="$PHONE_PORT"; profile="medium_phone" ;;
    tablet) avd="$TABLET_AVD"; port="$TABLET_PORT"; profile="medium_tablet" ;;
    *) fail "Unknown device \"$device\". Use phone or tablet." ;;
  esac

  # Anything in SHOTS for this device, minus whatever --scene filtered out. `set --` splits a shot
  # into its four fields; the script's own arguments have been parsed by this point.
  wanted=()
  for shot in "${SHOTS[@]}"; do
    set -- $shot
    [[ "$1" == "$device" ]] || continue
    [[ -z "$only_scene" || "$2" == "$only_scene" || "$4" == "$only_scene" ]] || continue
    wanted+=("$shot")
  done
  [[ ${#wanted[@]} -gt 0 ]] || continue

  ensure_avd "$avd" "$profile"
  say "Booting $avd"
  boot "$avd" "$port"
  serial="$SERIAL"
  note "$serial · $("$ADB" -s "$serial" shell wm size | tr -d '\r')"
  prepare "$serial"

  for theme in $themes; do
    case "$theme" in
      dark) "$ADB" -s "$serial" shell cmd uimode night yes >/dev/null ;;
      light) "$ADB" -s "$serial" shell cmd uimode night no >/dev/null ;;
      *) fail "Unknown theme \"$theme\". Use dark or light." ;;
    esac
    say "$device · $theme"
    for shot in "${wanted[@]}"; do
      set -- $shot
      capture "$serial" "$2" "$theme" "$1" "$3" "$4"
    done
  done

  "$ADB" -s "$serial" shell cmd uimode night yes >/dev/null 2>&1 || true
  demo "$serial" command exit
  if (( keep )); then
    note "leaving $serial running (--keep)"
  else
    shutdown "$port"
  fi
done

say "Done. $(ls "$OUT_DIR"/*.png 2>/dev/null | wc -l | tr -d ' ') files in $OUT_DIR"

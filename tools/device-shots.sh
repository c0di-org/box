#!/usr/bin/env bash
set -euo pipefail

# Drive and photograph Box on a real device, in each of the shapes it actually takes.
#
#   tools/device-shots.sh list                    what displays exist, and where Box is right now
#   tools/device-shots.sh shot opening            photograph Box, cropped to its own window
#   tools/device-shots.sh shot desk --full        the whole display instead of just Box
#   tools/device-shots.sh tap 120 300             tap inside Box's window, wherever that window is
#   tools/device-shots.sh swipe 200 900 200 300   drag inside Box's window
#   tools/device-shots.sh text 'clone my project'
#   tools/device-shots.sh key BACK
#   tools/device-shots.sh launch --form desktop   put Box on a particular display
#
# Why this sits next to tools/screenshots.sh instead of inside it: that script photographs a
# scripted gallery on emulators, which is the honest way to get a repeatable picture of a state
# nobody can reach on a desk -- a box opening, an agent asking permission. This one points at the
# phone that is actually running the Linux VM, which is the only way to see the app in the shape a
# user meets it in: shut, open, and in DeX on a desktop monitor.
#
# Four things here are not obvious, and each one costs an afternoon if you rediscover it:
#
#   1. screencap and input do NOT take the same display id. `screencap -d` wants the *physical*
#      id -- the 19-digit number SurfaceFlinger prints -- while `input -d` and `am start --display`
#      want the *logical* id (0, 1, 10). Passing a logical id to screencap does not fail: it
#      cheerfully writes a PNG of a warning banner, which looks like a screenshot until you open
#      it. This script keeps both ids for every display so you never have to remember which is
#      which.
#   2. A fold has two internal displays and only one is ever on. The dark one is the one you are
#      not looking at, and no adb command will wake it -- that is the hinge's job. So the
#      phone-shaped shot has to be taken with the phone shut, by a human. `list` says which
#      shapes are reachable right now rather than letting you photograph a black rectangle.
#   3. A DeX screenshot photographs the user's entire desktop -- their browser tabs, their mail,
#      their notifications. Cropping to Box's own window is the default here for that reason, not
#      for tidiness. Use --full only when you have looked at what else is on the screen.
#   4. Coordinates are window-relative. Box's DeX window does not start at the origin, and a tap
#      aimed with coordinates read off a full-display screenshot lands in whatever app is to the
#      left of it.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PACKAGE="${BOX_PACKAGE:-dev.localagent.workstation.stock}"
ACTIVITY="$PACKAGE/dev.localagent.workstation.MainActivity"
OUT_DIR="${BOX_SHOT_DIR:-docs/assets/screenshots/device}"

say()  { printf '\n\033[1;32m==>\033[0m %s\n' "$1"; }
note() { printf '    %s\n' "$1"; }
fail() { printf '\033[1;31m!!\033[0m %s\n' "$1" >&2; exit 1; }

# --- the device -------------------------------------------------------------------------------
# Same rule as tools/deploy.sh: one phone on two transports answers twice, and every adb command
# without -s then fails with "more than one device".
if [[ -z "${BOX_DEVICE:-}" ]]; then
  attached="$(command adb devices | awk '/\tdevice$/ {print $1}')"
  count="$(printf '%s\n' "$attached" | grep -c . || true)"
  (( count > 0 )) || fail 'No device. Plug the phone in, or check wireless debugging is paired.'
  if (( count == 1 )); then
    BOX_DEVICE="$attached"
  else
    BOX_DEVICE="$(printf '%s\n' "$attached" | grep -v '_adb-tls-connect' | head -1 || true)"
    [[ -n "$BOX_DEVICE" ]] || BOX_DEVICE="$(printf '%s\n' "$attached" | head -1)"
  fi
fi
# stdin comes from /dev/null on every call. `adb shell` reads its own stdin, so an adb command
# inside a `while read ... done < <(...)` loop eats the loop's input and the loop runs once with
# half a line. Nothing here ever needs to type into the device, so closing it is free.
adb() { command adb -s "$BOX_DEVICE" "$@" </dev/null; }

# --- displays ---------------------------------------------------------------------------------
# One dumpsys, parsed once, because `dumpsys display` on a fold is a quarter of a megabyte and
# grepping it repeatedly is most of this script's runtime.
#
# Emits: form logicalId physicalId width height active
displays() {
  adb shell dumpsys display 2>/dev/null | tr ',' '\n' | python3 -c '
import re, sys

text = sys.stdin.read()
# The viewport list is the only place carrying logical id, physical id, size and liveness
# together. Fields arrive one per line because the caller split the dump on commas.
fields = {}
entries = []
for line in text.splitlines():
    line = line.strip()
    if line.startswith("DisplayViewport{") or "DisplayViewport{" in line:
        if fields.get("displayId") is not None:
            entries.append(fields)
        fields = {}
        m = re.search(r"type=(\w+)", line)
        if m:
            fields["type"] = m.group(1)
    for key in ("displayId", "deviceWidth", "deviceHeight"):
        m = re.search(rf"\b{key}=(\d+)", line)
        if m and key not in fields:
            fields[key] = int(m.group(1))
    m = re.search(r"uniqueId=.local:(\d+)", line)
    if m and "physical" not in fields:
        fields["physical"] = m.group(1)
    m = re.search(r"isActive=(\w+)", line)
    if m and "active" not in fields:
        fields["active"] = m.group(1) == "true"
    m = re.search(r"\btype=(\w+)", line)
    if m and "type" not in fields:
        fields["type"] = m.group(1)
if fields.get("displayId") is not None:
    entries.append(fields)

good = [e for e in entries if {"displayId", "physical", "deviceWidth", "deviceHeight"} <= set(e)]
internal = [e for e in good if e.get("type") == "INTERNAL"]
# Of the two internal panels, the inner one is simply the one with more of a short edge: the
# cover screen is tall and narrow, the unfolded screen is nearly square.
internal.sort(key=lambda e: min(e["deviceWidth"], e["deviceHeight"]))
form = {}
if len(internal) >= 2:
    form[internal[0]["displayId"]] = "phone"
    form[internal[-1]["displayId"]] = "tablet"
elif internal:
    form[internal[0]["displayId"]] = "phone"
for e in good:
    if e.get("type") == "EXTERNAL":
        form[e["displayId"]] = "desktop"

for e in good:
    name = form.get(e["displayId"], "display%d" % e["displayId"])
    print(name, e["displayId"], e["physical"], e["deviceWidth"], e["deviceHeight"],
          "on" if e.get("active") else "off")
'
}

# Which shape to use when nobody passed one.
#
# This used to default to "desktop" unconditionally, so every command on a phone that was not in
# DeX failed with `No display for form "desktop"` -- a confusing way to be told that you forgot a
# flag, on the one tool you reach for when you are already unsure what the device is doing.
# Prefer an attached monitor when there is one, since that was the old intent, and otherwise use
# whichever internal panel is actually awake.
default_form() {
  local lines pick
  lines="$(displays)"
  pick="$(echo "$lines" | awk '$6 == "on" && $1 == "desktop" {print $1; exit}')"
  [[ -n "$pick" ]] || pick="$(echo "$lines" | awk '$6 == "on" {print $1; exit}')"
  echo "${pick:-desktop}"
}

# Resolve a form name to "logicalId physicalId width height active", honouring --form.
resolve_form() {
  local want="$1" line
  line="$(displays | awk -v w="$want" '$1 == w {print; exit}')"
  [[ -n "$line" ]] || fail "No display for form \"$want\". Try: tools/device-shots.sh list"
  echo "$line"
}

# Where Box's window is on a given logical display: "left top right bottom", or empty if Box is
# not on that display at all.
box_frame() {
  local logical="$1"
  adb shell dumpsys window windows 2>/dev/null | tr -d '\r' | python3 -c '
import re, sys
want, package = int(sys.argv[1]), sys.argv[2]

# Read the whole dump rather than breaking out on the first match: closing this pipe early kills
# the adb side with SIGPIPE, and the exit code propagates out through `set -o pipefail`.
inside, display, answer = False, None, None
for line in sys.stdin.read().splitlines():
    if "Window{" in line:
        inside, display = package in line, None
        continue
    if not inside or answer:
        continue
    m = re.search(r"mDisplayId=(\d+)", line)
    if m:
        display = int(m.group(1))
    m = re.search(r"\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", line)
    if m and display == want:
        answer = tuple(int(g) for g in m.groups())
if answer:
    print(*answer)
' "$logical" "$PACKAGE"
}

# --- commands ---------------------------------------------------------------------------------

cmd_list() {
  say "Displays on $BOX_DEVICE"
  printf '    %-9s %-8s %-21s %-11s %s\n' FORM LOGICAL PHYSICAL SIZE STATE
  while read -r form logical physical width height state; do
    [[ -n "$form" ]] || continue
    printf '    %-9s %-8s %-21s %-11s %s\n' \
      "$form" "$logical" "$physical" "${width}x${height}" "$state"
  done < <(displays)

  say 'Box'
  local found=0
  while read -r form logical physical width height state; do
    [[ -n "$form" ]] || continue
    local frame
    frame="$(box_frame "$logical")"
    if [[ -n "$frame" ]]; then
      set -- $frame
      note "on $form (display $logical) at [$1,$2]-[$3,$4]  $(( $3 - $1 ))x$(( $4 - $2 ))"
      found=1
    fi
  done < <(displays)
  (( found )) || note 'not on any display. tools/device-shots.sh launch --form <form>'
}

cmd_shot() {
  local name="${1:-shot}" full="${2:-0}"
  local line; line="$(resolve_form "$FORM")"
  set -- $line
  local logical="$2" physical="$3" state="$6"
  [[ "$state" == "on" ]] || fail "The $FORM display is off. $(off_hint "$FORM")"

  mkdir -p "$OUT_DIR"
  local target="$OUT_DIR/$FORM-$name.png"
  adb exec-out screencap -p -d "$physical" > "$target"

  local bytes; bytes="$(wc -c < "$target" | tr -d ' ')"
  (( bytes > 20000 )) || fail "$target came back at $bytes bytes -- is that display really on?"

  if (( ! full )); then
    local frame; frame="$(box_frame "$logical")"
    if [[ -n "$frame" ]]; then
      set -- $frame
      python3 - "$target" "$1" "$2" "$3" "$4" <<'PY'
import sys
from PIL import Image
path, left, top, right, bottom = sys.argv[1], *map(int, sys.argv[2:6])
image = Image.open(path)
# A window frame can hang off the edge of a rotated display; clamp rather than let PIL pad.
box = (max(0, left), max(0, top), min(image.width, right), min(image.height, bottom))
if box[2] > box[0] and box[3] > box[1]:
    image.crop(box).save(path)
PY
      note "cropped to Box's window"
    else
      note "Box is not on the $FORM display; kept the whole screen"
    fi
  fi
  note "$target ($(du -h "$target" | cut -f1), $(python3 -c '
import sys; from PIL import Image; i=Image.open(sys.argv[1]); print(f"{i.width}x{i.height}")' "$target"))"
}

off_hint() {
  case "$1" in
    phone)  echo 'Shut the fold to bring the cover screen up, then run this again.' ;;
    tablet) echo 'Open the fold, then run this again.' ;;
    *)      echo 'Wake it and run this again.' ;;
  esac
}

# Taps and swipes are given in Box-window coordinates and translated here, so a script written
# against the phone still lands correctly when Box is a window in the corner of a 34" monitor.
window_origin() {
  local logical="$1" frame
  frame="$(box_frame "$logical")"
  if [[ -n "$frame" ]]; then set -- $frame; echo "$1 $2"; else echo "0 0"; fi
}

cmd_tap() {
  local x="$1" y="$2"
  local line; line="$(resolve_form "$FORM")"; set -- $line
  local logical="$2"
  set -- $(window_origin "$logical")
  adb shell input -d "$logical" tap $(( x + $1 )) $(( y + $2 ))
  note "tap ($x,$y) in Box -> ($(( x + $1 )),$(( y + $2 ))) on display $logical"
}

cmd_swipe() {
  local x1="$1" y1="$2" x2="$3" y2="$4" ms="${5:-500}"
  local line; line="$(resolve_form "$FORM")"; set -- $line
  local logical="$2"
  set -- $(window_origin "$logical")
  adb shell input -d "$logical" swipe \
    $(( x1 + $1 )) $(( y1 + $2 )) $(( x2 + $1 )) $(( y2 + $2 )) "$ms"
}

# Both of these read their argument *before* resolving the form: `set -- $line` overwrites the
# positional parameters, so reading $1 afterwards yields the form name. That bug types the word
# "desktop" into the guest and looks convincingly like a broken keyboard.
cmd_text() {
  local text="$1"
  local line; line="$(resolve_form "$FORM")"; set -- $line
  # Two levels of quoting, both required. `input text` takes no spaces, and %s is its documented
  # escape; separately, adb joins its arguments and hands them to the *device's* shell, which will
  # otherwise act on ; && | > itself -- running half the intended string on the phone instead of
  # typing it into the VM.
  local escaped; escaped="$(printf '%s' "$text" | sed 's/ /%s/g')"
  adb shell "input -d $2 text '$escaped'"
}

cmd_key() {
  local key="$1"
  local line; line="$(resolve_form "$FORM")"; set -- $line
  adb shell input -d "$2" keyevent "$key"
}

cmd_launch() {
  local line; line="$(resolve_form "$FORM")"; set -- $line
  adb shell am start --display "$2" -n "$ACTIVITY" >/dev/null
  note "launched Box on $FORM (display $2)"
}

# --- arguments --------------------------------------------------------------------------------
FORM="${BOX_FORM:-}"
full=0
args=()
while (( $# )); do
  case "$1" in
    --form) shift; FORM="${1:-desktop}" ;;
    --form=*) FORM="${1#*=}" ;;
    --phone) FORM=phone ;;
    --tablet) FORM=tablet ;;
    --desktop|--dex) FORM=desktop ;;
    --full) full=1 ;;
    -h|--help) sed -n '3,40p' "$0"; exit 0 ;;
    *) args+=("$1") ;;
  esac
  shift
done
set -- "${args[@]:-}"

if [[ -z "$FORM" ]]; then
  FORM="$(default_form)"
  [[ "${1:-list}" == "list" ]] || note "no form given; using $FORM"
fi

case "${1:-list}" in
  list)   cmd_list ;;
  shot)   cmd_shot "${2:-shot}" "$full" ;;
  tap)    cmd_tap "$2" "$3" ;;
  swipe)  cmd_swipe "$2" "$3" "$4" "${5:-}" "${6:-500}" ;;
  text)   cmd_text "$2" ;;
  key)    cmd_key "$2" ;;
  launch) cmd_launch ;;
  *) fail "unknown command \"$1\". Try: list, shot, tap, swipe, text, key, launch" ;;
esac

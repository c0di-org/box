#!/bin/bash
# Where a build reads its tools from, and where it writes.
#
# Worth a check rather than a reading, because both mistakes are quiet and expensive: a build that
# writes into /opt/android loses everything on the next Box update, and `rm -rf "$OUT"` a few lines
# into build.sh means an OUT that resolves to the wrong place is not a cosmetic bug.
#
#   tools/checks/android-toolchain-paths.sh <path-to-build.sh>
set -u
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SRC=$1
BLOCK=$(awk '/^if \[ -n "\$\{BOX_ANDROID_SDK/,/^KEYSTORE=/' "$SRC")
[ -n "$BLOCK" ] || { echo "could not extract the resolution block"; exit 1; }

pass=0; fail=0
check() { # label actual expected
  if [ "$2" = "$3" ]; then echo "ok   $1"; pass=$((pass+1));
  else echo "FAIL $1 (got '$2', want '$3')"; fail=$((fail+1)); fi
}
run() { # returns "SDK|WORK|APP|OUT|KEYSTORE"
  ( eval "$BLOCK" >/dev/null 2>&1; echo "$SDK|$WORK|$APP|$OUT|$KEYSTORE" )
}

ROOT=$(mktemp -d)
mkdir -p "$ROOT/baked/jre/bin" "$ROOT/hand/jre/bin"
touch "$ROOT/baked/jre/bin/java" "$ROOT/hand/jre/bin/java"
chmod +x "$ROOT/baked/jre/bin/java" "$ROOT/hand/jre/bin/java"

# An explicit prefix always wins.
out=$(BOX_ANDROID_SDK="$ROOT/baked" BOX_ANDROID_WORK="$ROOT/w" run)
check "explicit SDK wins"        "$(echo "$out" | cut -d'|' -f1)" "$ROOT/baked"
check "work stays separate"      "$(echo "$out" | cut -d'|' -f2)" "$ROOT/w"
check "the key lives with work"  "$(echo "$out" | cut -d'|' -f5)" "$ROOT/w/debug.keystore"

# The build must never write into the tools, baked or not: that disk is replaced by an update.
check "output is never inside the SDK" \
  "$(echo "$out" | cut -d'|' -f4 | grep -c "^$ROOT/baked")" "0"
check "the project is never inside the SDK" \
  "$(echo "$out" | cut -d'|' -f3 | grep -c "^$ROOT/baked")" "0"

# With nothing installed anywhere, it still names a prefix rather than an empty string --
# `rm -rf "$OUT"` runs a few lines later and OUT must never resolve to "/".
out=$(unset BOX_ANDROID_SDK BOX_ANDROID_WORK; run)
check "a bare box still resolves an SDK"  "$(echo "$out" | cut -d'|' -f1)" "/workspace/android"
check "and a work root"                   "$(echo "$out" | cut -d'|' -f2)" "/workspace/android"
check "OUT is never bare"                 "$(echo "$out" | cut -d'|' -f4)" "/workspace/android/build"

rm -rf "$ROOT"
echo ""
echo "$pass passed, $fail failed"
[ "$fail" = 0 ]

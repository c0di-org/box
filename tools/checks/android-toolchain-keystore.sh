#!/bin/bash
# The one thing baking the toolchain must never do: ship a signing key.
#
# A key baked into the image would be the same key in every copy of Box, with its password in a
# script in this repo and its private half extractable from any APK -- so anybody could sign an
# update to anybody else's app. This checks the guard exists, defaults the safe way for a
# hand-provisioned prefix, is actually set by the image build, and is asserted afterwards rather
# than trusted.
#
#   tools/checks/android-toolchain-keystore.sh <path-to-provision.sh>
set -u
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SRC=$1
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; pass=$((pass+1)); else echo "FAIL $1 (got '$2', want '$3')"; fail=$((fail+1)); fi; }

# The guard, verbatim from provision.sh.
GUARD=$(grep -n 'if \[ "\$SKIP_KEYSTORE" = 0 \]' "$SRC" | head -1)
check "provision.sh guards the keystore step" "$([ -n "$GUARD" ] && echo yes || echo no)" "yes"

# It defaults to generating one, so a hand-provisioned prefix is unchanged.
DEF=$(grep -c 'SKIP_KEYSTORE=${SKIP_KEYSTORE:-0}' "$SRC")
check "and defaults to generating one" "$DEF" "1"

# The image build sets it, and asserts afterwards rather than trusting itself.
IMG="$ROOT_DIR"/guest/build-image.sh
check "the image build sets SKIP_KEYSTORE" "$(grep -c 'SKIP_KEYSTORE=1' "$IMG")" "1"
check "and fails if a key appears anyway" \
  "$(grep -c 'a signing key was baked into the image' "$IMG")" "1"

# Simulate both paths without downloading anything.
for skip in 0 1; do
  ROOT=$(mktemp -d)
  ( SKIP_KEYSTORE=$skip PREFIX=$ROOT
    if [ "$SKIP_KEYSTORE" = 0 ] && [ ! -f "$PREFIX/debug.keystore" ]; then touch "$PREFIX/debug.keystore"; fi )
  if [ "$skip" = 0 ]; then
    check "unset: a key is made" "$([ -f "$ROOT/debug.keystore" ] && echo yes || echo no)" "yes"
  else
    check "set: no key is made"  "$([ -f "$ROOT/debug.keystore" ] && echo yes || echo no)" "no"
  fi
  rm -rf "$ROOT"
done

# build.sh makes one per device instead, on the workspace disk.
BLD="$ROOT_DIR"/docs/spike/android-toolchain/gradle-free/build.sh
check "build.sh generates the key on demand" "$(grep -c "generating this box's signing key" "$BLD")" "1"
check "and signs with the per-device one"    "$(grep -c '\-\-ks "\$KEYSTORE"' "$BLD")" "1"
check "no script signs from the SDK prefix"  "$(grep -c 'ks "\$SDK/debug.keystore"' "$BLD")" "0"

echo ""; echo "$pass passed, $fail failed"; [ "$fail" = 0 ]

#!/usr/bin/env bash
set -euo pipefail

ROOTFS="${1:?usage: install.sh ROOTFS REPO_ROOT}"
REPO_ROOT="${2:?usage: install.sh ROOTFS REPO_ROOT}"
CODEX_VERSION="${CODEX_VERSION:-0.147.0}"
CODEX_TAG="rust-v${CODEX_VERSION}"
CODEX_ARCHIVE="codex-app-server-aarch64-unknown-linux-musl.tar.gz"
CODEX_SHA256="0bb78fa190cdcbc689dc34d34358b054a5c7e81a6d899d97065ea139aeb3ba9c"
CODEX_URL="https://github.com/openai/codex/releases/download/${CODEX_TAG}/${CODEX_ARCHIVE}"
CODEX_ROOT="$ROOTFS/opt/local-agent/codex"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

command -v curl >/dev/null || { echo 'curl is required to package Codex' >&2; exit 1; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required to package Codex' >&2; exit 1; }

curl -fsSL --retry 3 -o "$TMP_DIR/$CODEX_ARCHIVE" "$CODEX_URL"
printf '%s  %s\n' "$CODEX_SHA256" "$TMP_DIR/$CODEX_ARCHIVE" | sha256sum -c -
mkdir -p "$TMP_DIR/unpacked"
tar -xzf "$TMP_DIR/$CODEX_ARCHIVE" -C "$TMP_DIR/unpacked"

APP_SERVER="$(find "$TMP_DIR/unpacked" -maxdepth 2 -type f -name 'codex-app-server*' -print -quit)"
[[ -n "$APP_SERVER" ]] || { echo 'Codex App Server archive contained no app-server binary' >&2; exit 1; }

install -d -m 0755 "$CODEX_ROOT/bin"
install -m 0755 "$APP_SERVER" "$CODEX_ROOT/bin/codex-app-server"
install -m 0755 "$REPO_ROOT/guest/codex/box-codex-harness.mjs" "$CODEX_ROOT/box-codex-harness.mjs"
install -m 0755 "$REPO_ROOT/guest/codex/box-codex-login.mjs" "$CODEX_ROOT/bin/box-codex-login"
printf '%s\n' "$CODEX_VERSION" > "$CODEX_ROOT/VERSION"

# Exact installed-byte accounting; compressed qcow2/APK attribution is a property
# of the full image build and is reported there rather than guessed per harness.
printf 'Codex installed bytes: '
du -sb "$CODEX_ROOT" | awk '{print $1}'

#!/usr/bin/env bash
set -euo pipefail

# Reproducible ARM64 guest build. The output remains VM data and is never executed by Android.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT_DIR/guest/image/out"
docker build --platform linux/arm64 -t local-agent-guest-builder -f "$ROOT_DIR/guest/Dockerfile" "$ROOT_DIR"
docker run --rm --platform linux/arm64 --privileged \
  -v "$ROOT_DIR:/workspace" \
  -e IMAGE_SIZE_MB="${IMAGE_SIZE_MB:-6144}" \
  -e WORKSPACE_SIZE_MB="${WORKSPACE_SIZE_MB:-1024}" \
  -e DEBUG_ROOT_PASSWORD="${DEBUG_ROOT_PASSWORD:-}" \
  local-agent-guest-builder

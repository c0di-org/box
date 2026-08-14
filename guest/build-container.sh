#!/usr/bin/env bash
set -euo pipefail

# Reproducible ARM64 guest build. The output remains VM data and is never executed by Android.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT_DIR/guest/image/out"

# The image bakes its own source in with `git archive HEAD`, so the builder needs a working
# repository and not merely the files. In a worktree it does not get one from the bind mount alone:
# `.git` there is a *file* holding an absolute path to the real git directory, which lives outside
# the checkout and so outside the container. git resolves that pointer, finds nothing, and the
# build dies two hundred seconds in with "not a git repository" — after mmdebstrap has run.
#
# Mounting the common git directory at the same absolute path it has on the host is what makes the
# pointer resolve. Read-only because everything this build asks of git is a read, and the thing
# being mounted is the user's actual history.
GIT_MOUNT=()
if [[ -f "$ROOT_DIR/.git" ]]; then
  git_common="$(cd "$ROOT_DIR" && git rev-parse --path-format=absolute --git-common-dir)"
  GIT_MOUNT=(-v "$git_common:$git_common:ro")
fi

docker build --platform linux/arm64 -t local-agent-guest-builder -f "$ROOT_DIR/guest/Dockerfile" "$ROOT_DIR"
docker run --rm --platform linux/arm64 --privileged \
  -v "$ROOT_DIR:/workspace" \
  "${GIT_MOUNT[@]+"${GIT_MOUNT[@]}"}" \
  -e IMAGE_SIZE_MB="${IMAGE_SIZE_MB:-6144}" \
  -e WORKSPACE_SIZE_MB="${WORKSPACE_SIZE_MB:-1024}" \
  -e DEBUG_ROOT_PASSWORD="${DEBUG_ROOT_PASSWORD:-}" \
  local-agent-guest-builder

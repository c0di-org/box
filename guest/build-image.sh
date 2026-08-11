#!/usr/bin/env bash
set -euo pipefail

# Run in an ARM64 Debian/Ubuntu CI runner with mmdebstrap, e2fsprogs and qemu-utils.
# The image is VM data only; it must never be unpacked and executed by Android.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/guest/image/out}"
SUITE="${DEBIAN_SUITE:-bookworm}"
# Raised from 4096 to fit the baked harness (~315 MB). Unused space costs almost nothing in the
# APK: the image ships as a compressed qcow2, and empty blocks compress to nearly zero.
IMAGE_SIZE_MB="${IMAGE_SIZE_MB:-6144}"
mkdir -p "$OUT_DIR"
command -v mmdebstrap >/dev/null || { echo 'mmdebstrap is required (use the CI image)' >&2; exit 1; }
command -v qemu-img >/dev/null || { echo 'qemu-img is required' >&2; exit 1; }

ROOTFS="$(mktemp -d)"
cleanup() { rm -rf "$ROOTFS"; }
trap cleanup EXIT

PACKAGES="$(paste -sd, "$ROOT_DIR/guest/packages.txt")"
mmdebstrap --architectures=arm64 --variant=minbase --include="$PACKAGES" "$SUITE" "$ROOTFS" http://deb.debian.org/debian

install -d -m 0755 "$ROOTFS/workspace" "$ROOTFS/opt/local-agent"
chroot "$ROOTFS" useradd --create-home --shell /bin/bash agent
chroot "$ROOTFS" usermod --append --groups tty agent
chroot "$ROOTFS" chown agent:agent /workspace
if [[ -n "${DEBUG_ROOT_PASSWORD:-}" ]]; then
  printf 'root:%s\n' "$DEBUG_ROOT_PASSWORD" | chroot "$ROOTFS" chpasswd
fi
install -m 0755 "$ROOT_DIR/guest/agentd/agentd.py" "$ROOTFS/opt/local-agent/agentd.py"

# The Claude Code harness is baked in rather than installed on first run.
#
# Not the obvious choice — users pick their own harness, so installing on demand keeps the image
# small and the harness current. What decides it is the size: the Agent SDK pulls a
# platform-specific native binary that unpacks to ~295 MB. Fetching that through the guest's
# emulated network and unpacking it under TCG is minutes of dead time on a first run that already
# waits ~170s for boot. Installed here, it is present the moment the VM is ready, and Box works
# with no network at all.
#
# This runs on the build host (linux/arm64, per the Dockerfile), so npm resolves the same
# linux-arm64 optional dependency the guest would have chosen, at native speed. The version is
# pinned in guest/harness/package.json and the lockfile beside it.
install -d -m 0755 "$ROOTFS/opt/local-agent/harness"
install -m 0644 "$ROOT_DIR/guest/harness/package.json" "$ROOTFS/opt/local-agent/harness/package.json"
install -m 0644 "$ROOT_DIR/guest/harness/package-lock.json" "$ROOTFS/opt/local-agent/harness/package-lock.json"
install -m 0755 "$ROOT_DIR/guest/harness/box-claude-harness.mjs" "$ROOTFS/opt/local-agent/harness/box-claude-harness.mjs"
npm --prefix "$ROOTFS/opt/local-agent/harness" ci --omit=dev --no-audit --no-fund
test -d "$ROOTFS/opt/local-agent/harness/node_modules/@anthropic-ai/claude-agent-sdk" \
  || { echo 'the Claude Code harness did not install' >&2; exit 1; }
# A harness that installed for the wrong architecture would fail only once it reached the phone.
test -d "$ROOTFS/opt/local-agent/harness/node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64" \
  || { echo 'the harness installed without its linux-arm64 runtime' >&2; exit 1; }
install -m 0644 "$ROOT_DIR/guest/systemd/local-agentd.service" "$ROOTFS/etc/systemd/system/local-agentd.service"
install -m 0644 "$ROOT_DIR/guest/systemd/local-agent-workspace-prepare.service" "$ROOTFS/etc/systemd/system/local-agent-workspace-prepare.service"
install -m 0644 "$ROOT_DIR/guest/systemd/local-agent-desktop.service" "$ROOTFS/etc/systemd/system/local-agent-desktop.service"
install -d -m 0755 "$ROOTFS/etc/modules-load.d"
printf 'virtio_console\nvirtio_gpu\n' > "$ROOTFS/etc/modules-load.d/local-agent.conf"
ln -sf /etc/systemd/system/local-agentd.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/local-agentd.service"
ln -sf /etc/systemd/system/local-agent-desktop.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/local-agent-desktop.service"

# The desktop runs as `agent`, not as root, so that anything started from it writes files the agent
# also owns — a session that produced root-owned files in /workspace would break the next agent run.
# Debian ships X as a setuid wrapper for exactly this case; without allowed_users the wrapper
# refuses a non-console user, and without the two device groups X cannot open the GPU or any input.
chroot "$ROOTFS" usermod --append --groups video,input,render agent
install -d -m 0755 "$ROOTFS/etc/X11"
cat > "$ROOTFS/etc/X11/Xwrapper.config" <<'EOF'
allowed_users=anybody
needs_root_rights=yes
EOF

# openbox on its own is a grey screen and a right-click menu, which reads as a broken desktop. One
# terminal at startup makes it obviously a working computer, and it opens on /workspace so the
# files the agent has been working on are already there.
install -d -m 0755 "$ROOTFS/etc/xdg/openbox"
cat > "$ROOTFS/etc/xdg/openbox/autostart" <<'EOF'
xsetroot -solid "#101418" &
(cd /workspace && xterm -geometry 100x30+40+40 -fa Monospace -fs 11) &
EOF
cat > "$ROOTFS/etc/fstab" <<'EOF'
/dev/vda / ext4 defaults 0 1
/dev/vdb /workspace ext4 defaults,nofail,x-systemd.device-timeout=300s 0 2
EOF

# QEMU runs under TCG on the phone, so udev coldplug takes ~90s -- right at systemd's default
# device timeout. When it loses that race, dev-vdb.device fails, workspace.mount fails with it,
# and local-agentd never starts even though the kernel enumerated the disk in 15 seconds.
install -d -m 0755 "$ROOTFS/etc/systemd/system.conf.d"
cat > "$ROOTFS/etc/systemd/system.conf.d/local-agent.conf" <<'EOF'
[Manager]
DefaultDeviceTimeoutSec=300s
EOF

# Background maintenance that only competes for emulated CPU during boot. e2scrub_reap alone
# occupied 80 seconds of the first boot.
for unit in e2scrub_reap.service e2scrub_all.timer apt-daily.timer apt-daily-upgrade.timer \
    dpkg-db-backup.timer fstrim.timer; do
  ln -sf /dev/null "$ROOTFS/etc/systemd/system/$unit"
done

KERNEL="$(find "$ROOTFS/boot" -maxdepth 1 -type f -name 'vmlinuz-*' | sort | tail -n 1)"
INITRD="$(find "$ROOTFS/boot" -maxdepth 1 -type f -name 'initrd.img-*' | sort | tail -n 1)"
test -n "$KERNEL" && test -n "$INITRD" || { echo 'Debian kernel/initrd was not produced' >&2; exit 1; }
cp "$KERNEL" "$OUT_DIR/kernel"
cp "$INITRD" "$OUT_DIR/initrd.img"

RAW="$OUT_DIR/base-system.raw"
QCOW2="$OUT_DIR/base-system.qcow2"
truncate -s "${IMAGE_SIZE_MB}M" "$RAW"
mkfs.ext4 -q -d "$ROOTFS" -L local-agent-system "$RAW"
qemu-img convert -f raw -O qcow2 -c "$RAW" "$QCOW2"
(cd "$OUT_DIR" && sha256sum "$(basename "$QCOW2")" > "$(basename "$QCOW2").sha256")
(cd "$OUT_DIR" && sha256sum kernel > kernel.sha256)
(cd "$OUT_DIR" && sha256sum initrd.img > initrd.img.sha256)
rm "$RAW"

WORKSPACE_RAW="$OUT_DIR/workspace.raw"
WORKSPACE_QCOW2="$OUT_DIR/workspace.qcow2"
truncate -s "${WORKSPACE_SIZE_MB:-1024}M" "$WORKSPACE_RAW"
mkfs.ext4 -q -L local-agent-workspace "$WORKSPACE_RAW"
qemu-img convert -f raw -O qcow2 -c "$WORKSPACE_RAW" "$WORKSPACE_QCOW2"
(cd "$OUT_DIR" && sha256sum "$(basename "$WORKSPACE_QCOW2")" > "$(basename "$WORKSPACE_QCOW2").sha256")
rm "$WORKSPACE_RAW"
echo "Created $QCOW2"

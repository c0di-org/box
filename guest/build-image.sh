#!/usr/bin/env bash
set -euo pipefail

# Run in an ARM64 Debian/Ubuntu CI runner with mmdebstrap, e2fsprogs and qemu-utils.
# The image is VM data only; it must never be unpacked and executed by Android.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/guest/image/out}"
SUITE="${DEBIAN_SUITE:-bookworm}"
IMAGE_SIZE_MB="${IMAGE_SIZE_MB:-4096}"
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
install -m 0644 "$ROOT_DIR/guest/systemd/local-agentd.service" "$ROOTFS/etc/systemd/system/local-agentd.service"
install -m 0644 "$ROOT_DIR/guest/systemd/local-agent-workspace-prepare.service" "$ROOTFS/etc/systemd/system/local-agent-workspace-prepare.service"
install -d -m 0755 "$ROOTFS/etc/modules-load.d"
printf 'virtio_console\n' > "$ROOTFS/etc/modules-load.d/local-agent.conf"
ln -sf /etc/systemd/system/local-agentd.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/local-agentd.service"
cat > "$ROOTFS/etc/fstab" <<'EOF'
/dev/vda / ext4 defaults 0 1
/dev/vdb /workspace ext4 defaults,nofail 0 2
EOF

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

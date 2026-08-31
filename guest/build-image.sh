#!/usr/bin/env bash
set -euo pipefail

# Run in an ARM64 Debian/Ubuntu CI runner with mmdebstrap, e2fsprogs and qemu-utils.
# The image is VM data only; it must never be unpacked and executed by Android.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/guest/image/out}"
SUITE="${DEBIAN_SUITE:-bookworm}"

# What this image is, as opposed to what files it happens to produce.
#
# The app used to know its guest as four filenames, which meant it had no way to answer the one
# question that matters on a device that already has a box: "is this the same image?" These three
# values plus the content hash derived at the end are that answer, and they are what lets a second
# image -- a bare Ubuntu, a toolchain-specific one -- exist without colliding with this one.
IMAGE_ID="${IMAGE_ID:-box-minimal-claude}"
IMAGE_NAME="${IMAGE_NAME:-Box Minimal}"
IMAGE_DESCRIPTION="${IMAGE_DESCRIPTION:-Debian ${SUITE} arm64 with Claude Code, DeepSeek Harness, agentd and a minimal desktop.}"
# Raised from 4096 to fit the baked harness (~315 MB), and again for the Android toolchain
# (~220 MB). Unused space costs almost nothing in the APK: the image ships as a compressed qcow2,
# and empty blocks compress to nearly zero. Occupied space is the part that is paid for, which is
# why the toolchain was a decision and this number was not.
IMAGE_SIZE_MB="${IMAGE_SIZE_MB:-8192}"
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

# DeepSeek Harness is baked beside Claude, but gets its own Node runtime.
#
# DSH 0.1.0-rc.5 requires Node >=22.19 while Debian Bookworm's nodejs is older. Replacing /usr/bin
# node would unnecessarily move the known-good Claude path, so a pinned official ARM64 Node lives
# under /opt/local-agent/deepseek/node and is used only by this harness. The archive is verified
# against Node's published SHA256 list before anything from it is executed.
DSH_NODE_VERSION="${DSH_NODE_VERSION:-22.23.2}"
DSH_NODE_ARCHIVE="node-v${DSH_NODE_VERSION}-linux-arm64.tar.gz"
DSH_NODE_RELEASE="https://nodejs.org/download/release/v${DSH_NODE_VERSION}"
DSH_ROOT="$ROOTFS/opt/local-agent/deepseek"
DSH_NODE="$DSH_ROOT/node"
DSH_APP="$DSH_ROOT/app"
command -v curl >/dev/null || { echo 'curl is required (rebuild the builder image)' >&2; exit 1; }
install -d -m 0755 "$DSH_NODE" "$DSH_APP" "$ROOTFS/tmp"
curl -fsSL -o "$ROOTFS/tmp/$DSH_NODE_ARCHIVE" "$DSH_NODE_RELEASE/$DSH_NODE_ARCHIVE"
curl -fsSL -o "$ROOTFS/tmp/node-shasums.txt" "$DSH_NODE_RELEASE/SHASUMS256.txt"
( cd "$ROOTFS/tmp" && grep " $DSH_NODE_ARCHIVE\$" node-shasums.txt | sha256sum -c - ) \
  || { echo 'the DeepSeek Node runtime did not match Node.js published checksums' >&2; exit 1; }
tar -xzf "$ROOTFS/tmp/$DSH_NODE_ARCHIVE" -C "$DSH_NODE" --strip-components=1
rm -f "$ROOTFS/tmp/$DSH_NODE_ARCHIVE" "$ROOTFS/tmp/node-shasums.txt"
"$DSH_NODE/bin/node" --version | grep -qx "v${DSH_NODE_VERSION}" \
  || { echo 'the DeepSeek Node runtime did not install' >&2; exit 1; }

install -m 0644 "$ROOT_DIR/guest/deepseek/package.json" "$DSH_APP/package.json"
install -m 0644 "$ROOT_DIR/guest/deepseek/box.cordis.yml" "$DSH_APP/box.cordis.yml"
install -m 0755 "$ROOT_DIR/guest/deepseek/box-deepseek-harness.mjs" "$DSH_APP/box-deepseek-harness.mjs"
# npm's executable uses /usr/bin/env node, so put the private runtime first while resolving DSH.
# All top-level packages are exact versions in package.json; this is intentionally separate from
# Claude's lockfile so adding DSH cannot perturb Claude's dependency tree.
PATH="$DSH_NODE/bin:$PATH" "$DSH_NODE/bin/npm" --prefix "$DSH_APP" install --omit=dev --no-audit --no-fund
test -f "$DSH_APP/node_modules/@deepseek-ai/dsh-acp-demo/lib/bin.js" \
  || { echo 'DeepSeek Harness ACP runtime did not install' >&2; exit 1; }
test -d "$DSH_APP/node_modules/@agentclientprotocol/sdk" \
  || { echo 'DeepSeek Harness ACP client SDK did not install' >&2; exit 1; }

# Connecting this box to GitHub, and the one thing that reads the credential afterwards.
#
# Both go in a directory of their own rather than beside the harness: they have nothing to do with
# which agent is installed, and a box running some future harness still has to be able to clone.
install -d -m 0755 "$ROOTFS/opt/local-agent/bin"
install -m 0755 "$ROOT_DIR/guest/github/box-github-connect.mjs" "$ROOTFS/opt/local-agent/bin/box-github-connect.mjs"
install -m 0755 "$ROOT_DIR/guest/github/box-git-credential" "$ROOTFS/opt/local-agent/bin/box-git-credential"

# The GitHub CLI.
#
# Not in Debian, so it is fetched from the project's own releases and checked against the checksum
# file published beside it. Worth the ~40 MB and the extra fetch for one reason: without it the way
# to open a pull request from here is curl with a hand-built Authorization header, which means the
# agent reading the token — and a token an agent has read is one turn away from being echoed into a
# session log that is kept on disk. `gh` is also simply the tool it already knows how to drive.
GH_VERSION="${GH_VERSION:-2.63.2}"
GH_DEB="gh_${GH_VERSION}_linux_arm64.deb"
GH_RELEASE="https://github.com/cli/cli/releases/download/v${GH_VERSION}"
command -v curl >/dev/null || { echo 'curl is required (rebuild the builder image)' >&2; exit 1; }
install -d -m 1777 "$ROOTFS/tmp"
curl -fsSL -o "$ROOTFS/tmp/$GH_DEB" "$GH_RELEASE/$GH_DEB"
curl -fsSL -o "$ROOTFS/tmp/gh_checksums.txt" "$GH_RELEASE/gh_${GH_VERSION}_checksums.txt"
( cd "$ROOTFS/tmp" && grep " $GH_DEB\$" gh_checksums.txt | sha256sum -c - ) \
  || { echo "the gh download did not match its published checksum" >&2; exit 1; }
chroot "$ROOTFS" dpkg --install "/tmp/$GH_DEB"
rm -f "$ROOTFS/tmp/$GH_DEB" "$ROOTFS/tmp/gh_checksums.txt"
chroot "$ROOTFS" gh --version >/dev/null || { echo 'gh did not install' >&2; exit 1; }
# Box's own source, so the agent in the box can read the app the user is running.
#
# The point is not convenience -- it is that this copy and the running app are the same
# commit, by construction. Cloning the remote at first boot could not promise that: it
# would fetch whatever HEAD happens to be, on a device that may be several releases behind,
# and it would need network on a first run that is otherwise designed to work without any.
# Baking it means a user can open Box, ask why a button behaves the way it does, and the
# agent is reading the code that drew it.
#
# The QEMU shared objects are excluded. They are 25 MB of the 26 MB worktree, they are
# already in the APK as the app's native libraries, and nothing the agent does with source
# needs them -- so shipping them here would double them on the device to no benefit. What
# remains is 1.5 MB of text, which is roughly 0.3 MB once the qcow2 is compressed.
#
# History is deliberately not included: a shallow clone would drag those same binaries in
# through the object store. The commit is recorded instead, which is what provenance
# actually needs, and anything wanting real history can clone the remote once there is
# network -- which any push would require regardless.
command -v git >/dev/null || { echo 'git is required (rebuild the builder image)' >&2; exit 1; }
# The repository is bind-mounted from the host, so it is owned by a different uid than the
# builder runs as; without this git refuses to read it at all.
git config --global --add safe.directory "$ROOT_DIR"
# -e rather than -d: in a git worktree this is a file pointing at the real git directory, which
# build-container.sh mounts alongside the checkout.
test -e "$ROOT_DIR/.git" || { echo 'guest image must be built from a git checkout' >&2; exit 1; }
BOX_COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD)"
BOX_REMOTE="$(git -C "$ROOT_DIR" remote get-url origin 2>/dev/null || echo unknown)"
install -d -m 0755 "$ROOTFS/usr/src/box"
git -C "$ROOT_DIR" archive HEAD \
  | tar -x -C "$ROOTFS/usr/src/box" --exclude='runtime-qemu/src/main/jniLibs/*'
# No build timestamp: this file is read by the agent for provenance, and a clock reading
# would make two builds of the same commit produce different images.
cat > "$ROOTFS/usr/src/box/BUILD-INFO" <<EOF
commit=$BOX_COMMIT
remote=$BOX_REMOTE
note=Source of the Box app running on this device, at the commit it was built from.
      Excludes runtime-qemu/src/main/jniLibs (shipped in the APK as native libraries).
EOF
chroot "$ROOTFS" chown -R agent:agent /usr/src/box

# What the agent knows about the machine before it is told anything.
#
# The harness starts Claude Code without setting `settingSources`, and the Agent SDK's
# default for that is to load user, project and local settings — so a CLAUDE.md in the
# agent's home is read at the start of every session, with no harness change needed.
#
# It goes on the system disk rather than in /workspace, which is the opposite of where
# durable things usually belong here, and the reason is the update story. This disk is
# replaced by every image build, so the conventions a Box ships with always match the tree
# they were built from — fix a wrong statement here and every device gets the fix on its
# next update. /workspace survives updates precisely so the user's box is never wiped,
# which also means a machine fact written there would strand a stale copy on every device
# already in the field, with nothing able to correct it.
#
# So this file is what is true of every Box; /workspace/CLAUDE.md is what is true of one.
# The source is not itself named CLAUDE.md, because a file by that name inside the
# repository would be picked up as project memory by anyone working on Box and silently
# describe the phone to someone sitting at a laptop.
# The master copy, which local-agent-workspace-prepare installs into the agent's config
# directory on every boot. It cannot simply be baked into that directory: the config
# directory is on the workspace disk, which the image build creates empty and then never
# touches again, so anything baked would only ever reach a device that installed Box after
# the change. Copying at boot means a Box update corrects the conventions everywhere.
install -d -m 0755 "$ROOTFS/usr/share/box"
install -m 0644 "$ROOT_DIR/guest/agent-conventions.md" "$ROOTFS/usr/share/box/agent-conventions.md"
# Also at the default location, which is what gets read if anything ever runs Claude Code
# in here without CLAUDE_CONFIG_DIR set. Same file, so the two cannot disagree.
install -d -m 0755 "$ROOTFS/home/agent/.claude"
install -m 0644 "$ROOT_DIR/guest/agent-conventions.md" "$ROOTFS/home/agent/.claude/CLAUDE.md"
chroot "$ROOTFS" chown -R agent:agent /home/agent/.claude

# The Android build toolchain, baked for the same reason as the harness and one more.
#
# Google ships Android's build-tools as x86_64 Linux binaries only, so the usual route produces a
# toolchain that cannot run in here at all. The way through is a hybrid -- community ARM64 aapt2
# and zipalign, with d8 and apksigner lifted out of Google's x86_64 zip because those two are pure
# Java -- and it was measured end to end on a phone before it was believed. See
# docs/spike/android-toolchain/gradle-free/.
#
# Three reasons it is baked rather than fetched on demand:
#
#   1. The ARM64 aapt2 is one community build with no official equivalent. If that release ever
#      disappears, an image that fetches it loses the ability to build Android apps and no amount
#      of retrying brings it back. 14 MB of the ~220 is insurance against a single point of
#      failure outside anyone's control.
#   2. Provisioning it inside the guest took 6m30s of emulated CPU, once per box. Here it is a
#      native download on the build host and costs a user nothing.
#   3. It makes the toolchain a property of the image, so an agent can rely on it being present
#      and matching, instead of discovering the machine and negotiating with the network.
#
# It goes on the system disk, which every update replaces -- so a Box update ships a coherent
# toolchain everywhere, exactly like agent-conventions.md above. Nothing a build *writes* goes
# here; build.sh keeps projects, caches and this box's signing key on the workspace disk, which
# survives. SKIP_KEYSTORE is the load-bearing half of that split: a key generated here would be
# one key inside every copy of Box, with its password in a script in this repo and its private
# half extractable from any APK.
#
# Runs on the build host, which is linux/arm64, so provision.sh's verification step executes the
# same binaries the guest will and a broken artifact fails the build rather than the phone.
install -d -m 0755 "$ROOTFS/opt/android"
SKIP_KEYSTORE=1 CACHE="$(mktemp -d)" \
  "$ROOT_DIR/docs/spike/android-toolchain/gradle-free/provision.sh" "$ROOTFS/opt/android"
test -x "$ROOTFS/opt/android/build-tools/aapt2" \
  || { echo 'the Android toolchain did not install' >&2; exit 1; }
test ! -f "$ROOTFS/opt/android/debug.keystore" \
  || { echo 'a signing key was baked into the image; it must be per device' >&2; exit 1; }
install -d -m 0755 "$ROOTFS/opt/android/bin"
for script in build.sh predex.sh provision.sh; do
  install -m 0755 "$ROOT_DIR/docs/spike/android-toolchain/gradle-free/$script" "$ROOTFS/opt/android/bin/$script"
done
for module in maven.py aar.py zipget.py; do
  install -m 0644 "$ROOT_DIR/docs/spike/android-toolchain/gradle-free/$module" "$ROOTFS/opt/android/bin/$module"
done
# agent-conventions.md sends the agent here to read before starting, so it has to be here.
install -m 0644 "$ROOT_DIR/docs/spike/android-toolchain/gradle-free/README.md" "$ROOTFS/opt/android/bin/README"
install -m 0644 "$ROOT_DIR/docs/spike/android-toolchain/gradle-free/DEPENDENCIES.md" "$ROOTFS/opt/android/bin/DEPENDENCIES.md"
chroot "$ROOTFS" chown -R agent:agent /opt/android

install -m 0644 "$ROOT_DIR/guest/systemd/local-agentd.service" "$ROOTFS/etc/systemd/system/local-agentd.service"
install -m 0644 "$ROOT_DIR/guest/systemd/local-agent-workspace-prepare.service" "$ROOTFS/etc/systemd/system/local-agent-workspace-prepare.service"
install -m 0644 "$ROOT_DIR/guest/systemd/local-agent-desktop.service" "$ROOTFS/etc/systemd/system/local-agent-desktop.service"
install -m 0644 "$ROOT_DIR/guest/systemd/local-agent-online-cpus.service" "$ROOTFS/etc/systemd/system/local-agent-online-cpus.service"
install -d -m 0755 "$ROOTFS/etc/modules-load.d"
printf 'virtio_console\nvirtio_gpu\n' > "$ROOTFS/etc/modules-load.d/local-agent.conf"

# Two things X gets wrong on a machine like this, both explained at length in the files: the cursor
# has to be drawn into the framebuffer or it is never transmitted at all, and the display must
# never be powered down, because nothing in a Box can turn it back on.
install -d -m 0755 "$ROOTFS/etc/X11/xorg.conf.d"
for conf in "$ROOT_DIR"/guest/xorg.conf.d/*.conf; do
  install -m 0644 "$conf" "$ROOTFS/etc/X11/xorg.conf.d/$(basename "$conf")"
done

# Networking. The guest had none: the interface was never brought up, and the only resolver it had
# was the build container's, baked in by accident.
#
# systemd-networkd ships inside Debian's systemd package and speaks DHCP itself, so this costs no
# new package -- it only has to be turned on, which Debian does not do by default.
install -d -m 0755 "$ROOTFS/etc/systemd/network"
install -m 0644 "$ROOT_DIR/guest/systemd/local-agent-network.network" \
  "$ROOTFS/etc/systemd/network/10-local-agent.network"
# Enabled by symlink rather than `systemctl enable` in a chroot, matching how local-agentd is
# turned on above: it is the same two links the [Install] section would make, and it cannot fail
# for want of a running systemd to talk to.
install -d -m 0755 "$ROOTFS/etc/systemd/system/multi-user.target.wants" \
  "$ROOTFS/etc/systemd/system/sockets.target.wants"
ln -sf /lib/systemd/system/systemd-networkd.service \
  "$ROOTFS/etc/systemd/system/multi-user.target.wants/systemd-networkd.service"
ln -sf /lib/systemd/system/systemd-networkd.socket \
  "$ROOTFS/etc/systemd/system/sockets.target.wants/systemd-networkd.socket"
test -f "$ROOTFS/lib/systemd/system/systemd-networkd.service" \
  || { echo 'systemd-networkd is missing from the image' >&2; exit 1; }

# mmdebstrap copies the build container's /etc/resolv.conf in so apt can resolve, and leaves it
# behind. On the phone that file pointed at a Docker Engine nameserver on the build machine, so
# every lookup in the guest went to an address that does not exist there. Overwriting it is the
# fix, but *what* to write is a real choice:
#
# Slirp offers a nameserver at 10.0.2.3 and forwards it to whatever the host resolves with. It
# finds those by reading the host's /etc/resolv.conf -- which Android does not have, so that relay
# cannot work here. Reaching a resolver by address instead makes DNS an ordinary UDP flow that
# slirp NATs like any other, which does work. The cost is that lookups leave the device to a third
# party rather than following the phone's own DNS settings; 10.0.2.3 is kept last so that a
# platform where the relay does work is still preferred over nothing.
cat > "$ROOTFS/etc/resolv.conf" <<'EOF'
# Written by Box's image build. See build-image.sh for why this is not slirp's own resolver.
nameserver 1.1.1.1
nameserver 8.8.8.8
nameserver 10.0.2.3
options timeout:2 attempts:1
EOF
chmod 0644 "$ROOTFS/etc/resolv.conf"
ln -sf /etc/systemd/system/local-agentd.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/local-agentd.service"
# See the unit: the kernel boots with maxcpus=1 and this is what undoes it once boot is over.
ln -sf /etc/systemd/system/local-agent-online-cpus.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/local-agent-online-cpus.service"
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

# --- the manifest -----------------------------------------------------------------------------
#
# The four files above are the image; this is the description of it, and it is what the app reads
# instead of knowing their names. Two things follow from having it.
#
# The first is that a payload has a *role* rather than a position. `base-system.qcow2` is the
# system disk because it says so here, not because it is third in a list that the Gradle script
# and RuntimeStorage had to agree on by hand.
#
# The second is the version, and it is the reason this file exists at all. A device that already
# has a box preserves its disks across an app update, deliberately -- an update must never wipe
# the user's Linux machine. Without an identity to compare, that rule also silently swallowed
# every rebuilt image: the file was already there, so it was kept, and a changed agentd.py
# surfaced as a protocol error at handshake rather than as anything a build could report. Making
# the version a hash of the payloads means a rebuild always produces a new identity, so "already
# installed" and "a different image" stop being the same answer.
#
# It is a content hash rather than a number somebody bumps precisely because the failure being
# fixed is a *forgotten* step. A version that has to be remembered would be forgotten in exactly
# the dev loop this is meant to repair. It also keeps the build reproducible: no timestamp, so two
# builds of the same tree agree, which is the same rule BUILD-INFO follows.
digest_of() { cut -d' ' -f1 < "$OUT_DIR/$1.sha256"; }
bytes_of() { wc -c < "$OUT_DIR/$1" | tr -d ' '; }

KERNEL_SHA="$(digest_of kernel)"
INITRD_SHA="$(digest_of initrd.img)"
SYSTEM_SHA="$(digest_of base-system.qcow2)"
WORKSPACE_SHA="$(digest_of workspace.qcow2)"

IMAGE_VERSION="$(printf 'kernel %s\ninitrd %s\nsystem %s\nworkspace %s\n' \
  "$KERNEL_SHA" "$INITRD_SHA" "$SYSTEM_SHA" "$WORKSPACE_SHA" | sha256sum | cut -c1-16)"

# These values are ours rather than a user's, but they still land inside a quoted JSON string, and
# a stray quote would produce a manifest the app rejects at parse time on the device rather than
# here. Escaping is cheaper than finding that out later.
json_string() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

cat > "$OUT_DIR/image.json" <<EOF
{
  "schema": 1,
  "id": "$(json_string "$IMAGE_ID")",
  "version": "$IMAGE_VERSION",
  "name": "$(json_string "$IMAGE_NAME")",
  "description": "$(json_string "$IMAGE_DESCRIPTION")",
  "payloads": [
    { "role": "kernel", "file": "kernel", "sha256": "$KERNEL_SHA", "bytes": $(bytes_of kernel) },
    { "role": "initrd", "file": "initrd.img", "sha256": "$INITRD_SHA", "bytes": $(bytes_of initrd.img) },
    { "role": "system", "file": "base-system.qcow2", "sha256": "$SYSTEM_SHA", "bytes": $(bytes_of base-system.qcow2) },
    { "role": "workspace", "file": "workspace.qcow2", "sha256": "$WORKSPACE_SHA", "bytes": $(bytes_of workspace.qcow2) }
  ],
  "contains": {
    "desktop": true,
    "harnesses": [
      {
        "id": "claude-code",
        "name": "Claude Code",
        "entry": "/opt/local-agent/harness/box-claude-harness.mjs"
      },
      {
        "id": "deepseek-harness",
        "name": "DeepSeek Harness",
        "entry": "/opt/local-agent/deepseek/app/box-deepseek-harness.mjs"
      }
    ],
    "source": { "commit": "$BOX_COMMIT", "path": "/usr/src/box" }
  }
}
EOF

echo "Created $QCOW2"
echo "Image $IMAGE_ID@$IMAGE_VERSION described in $OUT_DIR/image.json"

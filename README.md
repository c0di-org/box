<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero-dark.svg">
    <img alt="Box — a real Linux box, inside your phone." src="docs/assets/hero-light.svg" width="820">
  </picture>
</p>

<p align="center">
  <strong>Box is an AI chat app whose agents have a real computer.</strong><br>
  Not a sandbox rented in the cloud — a Debian VM that boots on the phone in your hand.
</p>

## The whole idea

You say:

> Clone my project and get it running.

Box goes quiet for a moment and then starts reporting, in plain language:

```
✓  Cloned the project
✓  Installed dependencies
✓  Fixed an error
⟳  Starting the app
```

Every one of those lines is something that actually happened on a machine. The agent
didn't tell you how to clone the repo — it cloned it. It ran the tests and read the
failure. The files are still there tomorrow.

You never see a terminal, and you never need to know it's Linux.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/phone-progress-dark.png">
    <img alt="A Box conversation: a checklist ticking off cloned repo, installed dependencies, starting dev server, with the commands it ran folded up underneath." src="docs/assets/screenshots/phone-progress-light.png" width="300">
  </picture>
  &nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/phone-permission-dark.png">
    <img alt="Box asking permission to edit vite.config.js, showing the exact diff above Deny and Allow buttons." src="docs/assets/screenshots/phone-permission-light.png" width="300">
  </picture>
</p>

The commands are there if you want them — every card opens. And nothing is edited
without the diff being put in front of you first.

## Until you want to

Tap **Open computer** and there it is: the desktop the agent is working on, live. Watch
it type. Read its files. Tap **Take over** and the keyboard and mouse are yours — the
agent's input is suspended until you hand it back. Then straight back to the conversation.

## One box, many agents

The computer belongs to Box, not to any one agent. So one of them builds it, another
reviews it, a third fixes the one thing that's broken — same machine, same files, same
`/workspace` that survives every conversation.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/phone-tasks-dark.png">
    <img alt="Box's home screen: one Computer row above a flat list of tasks, each labelled with the agent running it." src="docs/assets/screenshots/phone-tasks-light.png" width="300">
  </picture>
  &nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/phone-opening-dark.png">
    <img alt="Box opening its box: a progress ring reading Opening your box, about a minute left, with tasks already waiting underneath." src="docs/assets/screenshots/phone-opening-light.png" width="300">
  </picture>
</p>

One box at the top, whoever is using it underneath. Opening it takes a couple of minutes
the first time, and Box says so rather than spinning.

## The screen you happen to have

On a phone Box is a chat app, and the computer stays out of the way. On a Fold, a tablet
or Samsung DeX it opens out: conversation on one side, the agent's live desktop on the
other. Plug in a keyboard and you're using a small Linux PC that was in your pocket.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/screenshots/tablet-progress-dark.png">
    <img alt="Box on a tablet: the task list keeps a permanent column on the left while the conversation fills the rest of the window." src="docs/assets/screenshots/tablet-progress-light.png" width="820">
  </picture>
</p>

And this one is not an emulator at all — it is the desktop, on a desk:

<p align="center">
  <img alt="Box in Samsung DeX on a 34-inch monitor: the agent's own Debian desktop fills the window, a terminal open on it at agent@355cb8862545:/workspace." src="docs/assets/screenshots/device/dex-computer.png" width="820">
</p>

A Galaxy Z Fold 7 in DeX, driving a 3440×1440 monitor, showing the Debian desktop that is
running inside the phone. Taken with [`tools/device-shots.sh`](tools/device-shots.sh),
which drives and photographs the app on real hardware in each of its three shapes.

It is the same machine folded shut, and unfolded — the guest's screen is resized to whatever
window it is being shown in, so the desktop fits the shape you are holding rather than being
letterboxed into it:

<p align="center">
  <img alt="Box on the cover screen of a folded Galaxy Z Fold 7: the Debian desktop fills the phone's portrait screen, a terminal on it showing PRETTY_NAME=Debian GNU/Linux 12 (bookworm), Linux 6.1.0-50-arm64 aarch64, and nproc reporting 2." src="docs/assets/screenshots/device/phone-computer-debian.png" width="270">
  &nbsp;&nbsp;
  <img alt="The same Debian session on the unfolded inner screen, the desktop filling the taller panel, with the header reading Computer, Debian, ARM64 and a You're driving pill." src="docs/assets/screenshots/device/tablet-computer-debian.png" width="470">
</p>

Debian 12 on an ARM64 kernel with two cores, in a phone, with nothing else running it. Both
shots are the same box a few seconds apart, shut and open.

## Nothing to set up

Real Linux on Android today means several apps, a tour of the developer settings, a
command-line walkthrough, and looking all of it up again next week. Box absorbs that
whole thing.

Install Box. Open it. Start chatting. No Termux, no separate X11 app, no terminal.

---

## Where this actually is

Box is being built in the open, so this part is here for the same reason the rest of it
is: nobody should have to guess which of the above already works. Checked against the
code, not the roadmap.

| Promise | Where it stands |
| --- | --- |
| **The computer** | Real. An ARM64 Debian Bookworm VM boots under QEMU in its own process, with a private control channel to `agentd` inside it. Commands run, files persist. |
| **A real agent in it** | Real. Claude Code runs in the guest and speaks Box's event vocabulary; you sign in through the phone's browser, no API key to paste. The full OAuth round trip is not yet proven on hardware. |
| **Open computer / Take over** | Real, confirmed on hardware 12 Aug 2026. The guest runs X and openbox, the screen reaches the app over RFB on a private socket, and taking over moves the guest's own cursor and types into its shell. Until that day the machine had a screen and no input devices at all, so "Take over" switched on and then silently dropped every click — see [docs/ux-field-notes.md](docs/ux-field-notes.md). The guest's resolution is still fixed at 1280×800 rather than following the window. |
| **Other agents** | ChatGPT and Cursor exist as a scripted demo only. One harness is wired for real. |
| **Preview a running server** | Not built. Port forwarding throws, and the button says so instead of opening nothing. |

The honest cost: it's a fully emulated ARM64 VM, so first light takes a couple of minutes
on a Galaxy Z Fold 7 and most of that is the guest waiting on emulated udev. See
[docs/development.md](docs/development.md) for the measurements.

And the pictures above, on the same terms. The phone and tablet ones are screenshots taken
by [`tools/screenshots.sh`](tools/screenshots.sh) on emulators, of the app in this
repository; what is in them is Box's built-in demo conversation, because an emulator has no
VM and there is no agent in there to have really run `npm install`. Every pixel around it is
the shipping UI.

The three desktop pictures are the other kind: [`tools/device-shots.sh`](tools/device-shots.sh)
on a real Fold 7 with a real VM behind it, so the Debian in them is actually running, the
prompt is a shell you could type into, and the `uname` output is that machine answering. That
screen used to be the one thing Box could not honestly photograph — and until recently it could
not be photographed *well* on a phone either, because the guest was a fixed 1280×800 letterboxed
into whatever it was shown in, which on the cover screen left about 70% of the panel black.

## Build it

Onto a plugged-in arm64 phone, in one command. The first run needs `--image`, which builds
the guest image in a container before building the app — a few minutes, and Docker has to
be running:

```bash
./tools/deploy.sh --image
```

After that, build, install and launch:

```bash
./tools/deploy.sh
```

Reach for `--image` again whenever anything under `guest/` changed. It also reprovisions,
which is the only way a new image reaches a device — an installed Box keeps its existing
guest disk on purpose, so that an app update never wipes the user's Linux box.

The tests, which need no device:

```bash
./gradlew :app:testStockDebugUnitTest :runtime-qemu:testDebugUnitTest && node --test guest/tests/*.mjs && python3 -m unittest discover -s guest/tests
```

Everything else — building by hand, watching a boot from `adb logcat`, and why a rebuilt
guest image can appear to do nothing — is in [docs/development.md](docs/development.md).

## Layout

| Module | What it holds |
| --- | --- |
| `app/` | Compose UI, the semantic event model, the adaptive phone/tablet/DeX layouts |
| `runtime-api/` | The `ComputerRuntime` boundary — exec, streaming exec, PTY, files, sessions |
| `runtime-qemu/` | QEMU AArch64, the out-of-process runtime service, the `agentd` client |
| `guest/` | Image builder, `agentd`, and the agent harness that runs inside the VM |
| `protocol/` | The `agentd` wire protocol — [v2](protocol/agentd-v2.md) is current |
| `docs/` | [Runtime design](docs/runtime.md), [UI contract](docs/ui-contract.md), [development](docs/development.md) |

Two build flavors: `stock` (QEMU, runs everywhere) and `avf` (experimental, Android
Virtualization Framework).

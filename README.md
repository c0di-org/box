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

## Until you want to

Tap **Open computer** and there it is: the desktop the agent is working on, live. Watch
it type. Read its files. Tap **Take over** and the keyboard and mouse are yours — the
agent's input is suspended until you hand it back. Then straight back to the conversation.

## One box, many agents

The computer belongs to Box, not to any one agent. So one of them builds it, another
reviews it, a third fixes the one thing that's broken — same machine, same files, same
`/workspace` that survives every conversation.

## The screen you happen to have

On a phone Box is a chat app, and the computer stays out of the way. On a Fold, a tablet
or Samsung DeX it opens out: conversation on one side, the agent's live desktop on the
other. Plug in a keyboard and you're using a small Linux PC that was in your pocket.

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
| **Open computer / Take over** | Built, not confirmed. The guest runs X and openbox, and the screen comes to the app over RFB on a private socket. Every hop is verified except the last one — pixels landing on the phone's surface. |
| **Other agents** | ChatGPT and Cursor exist as a scripted demo only. One harness is wired for real. |
| **Preview a running server** | Not built. Port forwarding throws, and the button says so instead of opening nothing. |

The honest cost: it's a fully emulated ARM64 VM, so first light takes a couple of minutes
on a Galaxy Z Fold 7 and most of that is the guest waiting on emulated udev. See
[docs/development.md](docs/development.md) for the measurements.

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

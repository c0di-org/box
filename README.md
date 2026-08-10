# Box

**Box is an AI chat app that has its own real computer inside your phone.**

Most of the time it feels like a normal chat app. You talk to ChatGPT, Claude, Cursor
or another agent, keep several conversations going at once, and come back to them
later.

The difference is what happens when an agent needs to actually *do* something. Box
gives it a private Linux computer to work on. So instead of only telling you how to
clone a project, install a dependency, run the tests or start a server, the agent
does those things.

## What using it feels like

You say:

> Clone my project and get it running.

The agent starts working in the background, and Box shows plain progress:

- Cloned the project
- Installed dependencies
- Fixed an error
- Starting the app

You never need to see a terminal or understand Linux. But if you're curious, tap
**Open Computer** and you get the exact desktop the agent is using — watch it work,
open its terminal, look at its files. Press **Take Over** and the machine is yours.
When you're done you go straight back to the conversation.

**On your phone** Box is a chat app. Conversations with different agents, a clear
view of which are still working, notifications when something finishes. The computer
stays out of the way unless you want it.

**On a tablet, Fold, or Samsung DeX** Box becomes a desktop workspace: conversation on
one side, the agent's computer on the other. You can literally watch Claude work on
the machine while still talking to it — and because the computer is running locally on
your device, you can drive it with a keyboard and mouse like a small Linux PC.

## Why this is different

Getting a real Linux environment on Android today means installing several apps,
changing settings, learning command-line setup, and remembering how to start it all
again next week. Box absorbs all of that. Install Box. Open it. Start chatting.

No Termux. No separate X11 app. No setup guide. No terminal knowledge required.

## Why give an AI a computer?

An agent that can work in a real environment is far more useful than one that can only
describe the work. It can run code, install tools, use git, inspect files, run tests,
start local sites and apps, automate longer jobs, and keep a workspace that persists
between conversations.

And because the computer belongs to Box rather than to any one agent, they share it.
Have ChatGPT build something, ask Claude to review it, then have Cursor fix one issue —
same machine, same files.

---

## Where the code actually is

The product above is the target. This is what the repository does today, stated plainly
so nobody has to infer it from the source.

**The computer is real.** On an arm64 device the app boots an actual ARM64 Debian
Bookworm VM under QEMU, in its own process, and talks to a guest service over a private
virtio-serial port. Commands really execute, files really persist. The Workspace tools
in the UI — the terminal and the file browser — are wired to that VM through
`RuntimeService`, not to a mock.

**The conversation is not real yet.** This is the gap that matters. Everything the
product is *about* — sessions with ChatGPT, Claude and Cursor, streamed replies, tool
calls, diffs, permission prompts — is currently served by
[`FakeAgentBackend`](app/src/main/kotlin/dev/localagent/workstation/agent/FakeAgentBackend.kt),
an in-process script. The semantic event model
([`AgentEvent`](app/src/main/kotlin/dev/localagent/workstation/agent/AgentEvent.kt)) and
the [`AgentBackend`](app/src/main/kotlin/dev/localagent/workstation/agent/AgentBackend.kt)
boundary are designed and tested, so the UI is built against the shape the real thing
will have — but no agent harness runs in the guest.

Also still to come, each with a written-down interface and no implementation:

| Product promise | Status |
| --- | --- |
| Chat with real agent harnesses | `AgentBackend` defined; only `FakeAgentBackend` implements it |
| **Open Computer** — the live desktop | `DesktopTransport` defined; no display transport exists |
| **Take Over** — user takes the keyboard | Part of `DesktopTransport`; unimplemented |
| Preview a server the agent started | `PreviewTransport` defined; port forwarding throws |
| Background work + notifications | Foreground runtime service exists; no agent to notify about |

The project is still named `LocalAgentWorkstation` in Gradle and `dev.localagent.workstation`
in code. Box is the product name; the rename hasn't happened.

## Layout

| Module | What it holds |
| --- | --- |
| `app/` | Compose UI, the semantic event model, adaptive phone/tablet/DeX layouts |
| `runtime-api/` | The `ComputerRuntime` boundary — exec, streaming exec, PTY, files |
| `runtime-qemu/` | QEMU AArch64, the out-of-process runtime service, the agentd client |
| `guest/` | Image builder and `agentd`, the guest's only host-facing service |
| `protocol/` | The agentd wire protocol — [v2](protocol/agentd-v2.md) is current |
| `docs/` | Runtime design, UI contract, the aarch64 toolchain spike |

Two build flavors: `stock` (QEMU, ships everywhere) and `avf` (experimental, Android
Virtualization Framework).

## Build

The guest image is an input to the APK, so build it first — see
[guest/README.md](guest/README.md) for detail:

```bash
./guest/build-container.sh
```

Then:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleStockDebug
```

```bash
adb install -r app/build/outputs/apk/stock/debug/app-stock-debug.apk
```

Tests — 43 JVM tests plus the guest protocol suite:

```bash
./gradlew :app:testStockDebugUnitTest :runtime-qemu:testDebugUnitTest
```

```bash
python3 -m unittest discover -s guest/tests
```

## Verifying the VM on a device

```bash
adb logcat "BoxRuntime:*" "LocalAgentRuntime:*" "LocalAgentQemu:*" "BoxGuestSerial:*" "*:S"
```

A healthy boot logs `QMP confirmed running guest`, then `Guest agent confirmed ready`,
then `QEMU runtime launch accepted`. Start the runtime with:

```bash
adb shell am start -n dev.localagent.workstation.stock/dev.localagent.workstation.VmProbeActivity --es runtime_action dev.localagent.runtime.qemu.START
```

Then exercise the guest control channel end to end:

```bash
adb shell am start -n dev.localagent.workstation.stock/dev.localagent.workstation.VmProbeActivity --es runtime_action dev.localagent.runtime.qemu.EXEC_PROBE
```

Expect `Guest command probe: exit=0 stdout=device-agentd-ok` in logcat. Run `START`
first on a fresh install: only `START` provisions the guest image, so `EXEC_PROBE` on
an unprovisioned device fails with `No complete verified guest image is installed yet`.

Tap-to-Ready on a Galaxy Z Fold 7 measured ~170 seconds against the protocol-v2 image
(171 s cooled, 168 s on a first cold provision, 252 s with the SoC already hot from
back-to-back runs), versus a ~90 second figure quoted for earlier builds. Nearly all of
it is the guest waiting on emulated udev; see [guest/README.md](guest/README.md).

Reinstalling the APK does **not** replace an already-provisioned guest disk —
`base-system.qcow2` is installed only when absent, since it is mutable once booted. To
pick up a rebuilt image, drop the provisioned copy first (this keeps the workspace):

```bash
adb shell run-as dev.localagent.workstation.stock rm -f files/computer/disks/system.qcow2
```

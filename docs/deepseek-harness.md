# DeepSeek Harness in Box

This branch bakes [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) beside Claude Code and makes the harness a property of each task. Claude remains the default; choosing **DeepSeek Harness** from the New task picker starts a different guest process for that task.

## Build

Build Box normally. `guest/build-image.sh` now installs two independent runtimes:

- Claude Code at `/opt/local-agent/harness`, unchanged from the existing image.
- DeepSeek Harness at `/opt/local-agent/deepseek` with its own pinned Node 22 runtime.

The image keeps the existing `box-minimal-claude` image id so this is an update of the same Box image family; the content-derived image version still changes when the payload changes.

## Configure DeepSeek

The first integration deliberately does not add another Android account/key screen. Put the DeepSeek API key on the persistent workspace disk from Box's terminal:

```sh
install -d -m 700 /workspace/.config/box
umask 077
read -rsp 'DeepSeek API key: ' key; echo
printf '%s' "$key" > /workspace/.config/box/deepseek-api-key
unset key
chmod 600 /workspace/.config/box/deepseek-api-key
```

The key is read inside the guest by `box-deepseek-harness.mjs`, passed only to the DSH child process as `DEEPSEEK_API_KEY`, and is never sent through Box's Android/virtio session-open payload.

Then create a task and use the harness picker beside **New task** to choose **DeepSeek Harness**.

## Architecture

```text
Box task (harnessId = deepseek-harness)
        |
        v
GuestAgentBackend
        |
        v
/opt/local-agent/deepseek/app/box-deepseek-harness.mjs
        |
        | ACP JSON-RPC over private child stdio
        v
@deepseek-ai/dsh-acp-demo
        |
        v
DeepSeek Harness / deepseek-v4-flash
        |
        v
/workspace
```

The wrapper owns one ACP session for the lifetime of one Box harness process. Box's stdout vocabulary remains unchanged: the Android app still only sees `session_started`, `user_message`, `message`, `activity`, `permission_*`, `error`, and the other existing Box events.

The DSH Cordis composition uses `danger-full-access` **inside the Box VM**. The QEMU VM remains the security boundary, matching the way the current Claude Agent SDK runs in Box and avoiding a second bwrap/Landlock layer inside an already isolated guest.

## What works in this first pass

- Harness selection per task.
- Lazy process launch: installing two harnesses does not start two harnesses at boot.
- Multi-turn prompts while the DeepSeek harness process remains alive.
- Cancellation through ACP `session/cancel`.
- Text attachments as paths to files already copied into `/workspace`.
- DeepSeek's own JSONL persistence under `/workspace/.config/dsh`.
- ACP one-shot permission requests translated into Box's existing permission event vocabulary when DSH emits one.
- Claude's existing runtime, credential flow, model switching, and wrapper are left on their existing paths.

## Known limitations

This is intentionally an experiment built on DSH's **published ACP automation transport**, not on private Cordis internals.

1. **ACP only publishes committed assistant text.** DSH reasoning, tool calls, plans, and rich live progress stay in DSH's session log, so DeepSeek tasks will look much quieter than Claude tasks in Box. A later native Box Cordis plugin could translate `session/event` directly and restore those cards.
2. **ACP currently creates fresh sessions only.** If the DeepSeek wrapper process/VM is destroyed, Box's transcript survives but a later DeepSeek process cannot resume that exact DSH conversation yet. Keep the box/harness alive for meaningful multi-turn testing.
3. **The Android model picker is still Claude-specific.** DeepSeek currently uses `deepseek-v4-flash` from `box.cordis.yml`; `model` commands sent by Box are ignored by the DeepSeek wrapper.
4. **DeepSeek authentication is manual.** There is no Android key-entry/disconnect UI in this PR.
5. **Box `show`, GitHub `connect`, targeted subagent interruption, and DSH's human-question UI are not bridged yet.**
6. **Permission modes are not feature-equivalent yet.** The wrapper understands Box's permission mode and can answer ACP permission callbacks, but the initial DSH profile runs unrestricted inside the VM, so normal file/shell actions usually do not generate those callbacks.
7. **Box's existing first-run sign-in state is still Claude-oriented.** On a completely fresh install, the product may still encourage/require the Claude sign-in path before the normal conversation flow. This PR is aimed first at validating DSH execution on the real guest; separating provider authentication in the product model is follow-up work.

These limitations are why this should remain a draft until it has been built and exercised on a real Box image.

# Codex App Server harness

This document describes Box's first Codex integration. It is intentionally a harness adapter, not a second provider protocol in the Android app.

## Branch relationship

This work is stacked on PR #42 (`deepseek-harness-option`). PR #42 owns the generic per-task harness ID, picker, registry, lazy process dispatch, transcript wire vocabulary, and DeepSeek integration. The Codex branch adds only the Codex runtime plus build/test/docs changes that use those abstractions.

After #42 merges, this branch should be rebased or its PR base retargeted to `main`. The Codex-specific commits should then remain as the PR delta.

## Architecture

```text
Box task (harnessId=codex)
  -> GuestAgentBackend
  -> agentd / one active Box session process
  -> /usr/bin/node /opt/local-agent/codex/box-codex-harness.mjs
  -> JSON-RPC over stdio
  -> /opt/local-agent/codex/bin/codex-app-server
  -> Codex thread/turn in /workspace
```

Android continues to consume `HarnessWire` JSON-lines events. Codex JSON-RPC never crosses into Android.

The first implementation uses one adapter + one App Server process per active Box Codex task. App Server can multiplex threads, but a global daemon would couple failure/lifetime/ownership for unrelated tasks and would require a second routing layer. Under TCG, a shared daemon might eventually win on aggregate startup/RAM; that should be decided with on-device measurements rather than assumed here.

## Packaging

Pinned Codex release: `0.147.0` (`rust-v0.147.0`).

Build-time artifact:

- `codex-app-server-aarch64-unknown-linux-musl.tar.gz`
- official OpenAI GitHub release asset
- SHA-256: `0bb78fa190cdcbc689dc34d34358b054a5c7e81a6d899d97065ea139aeb3ba9c`
- compressed release asset size: 76,475,295 bytes

`guest/codex/install.sh` downloads and verifies the archive on the native image-build host. It installs the native binary and Box adapter under `/opt/local-agent/codex`. Nothing is downloaded, resolved, installed, or unpacked when a user chooses Codex inside the QEMU guest.

Filesystem layout:

```text
/opt/local-agent/codex/
  VERSION
  box-codex-harness.mjs
  bin/
    codex-app-server
    box-codex-login

/workspace/.config/codex/
  ... Codex credentials/config ...

/workspace/.config/box/codex/sessions/
  <box-session-id>.json
  <box-session-id>.lock
```

Claude's known-good dependency tree and `/usr/bin/node` usage are unchanged. DeepSeek keeps its private Node 22 runtime. Codex itself is a native App Server binary; Node is used only for Box's small protocol adapter and login helper.

### Footprint accounting

Every image build prints exact uncompressed directory sizes for Claude, DeepSeek, and Codex and the final compressed system qcow2 size. The Codex installer also prints its exact installed directory size.

This development environment did not run the full ARM64 image builder, so no installed-byte or final qcow2 delta is recorded here. Do not substitute the 76,475,295-byte compressed release archive size for installed footprint: they are different measurements.

## Installed, warmed, running

These remain separate states.

**Installed:** Claude, DeepSeek, and Codex are present in the guest image.

**Seed-warmed:** unchanged from the existing Box policy. Codex is not added to RuntimeService's warm command and is not deliberately read into page cache for the seeded snapshot.

**Running:** no Codex, Claude, or DeepSeek service is installed or enabled. `agentd` and Box's required guest services start at boot; a harness process exists only when Box opens an agent session for a task.

A newly-created Codex task follows Box's existing behavior and may pre-start its selected harness before the first prompt. The Codex adapter then pre-starts App Server but does **not** create a Codex thread until the first prompt. This can fault the native binary while the user reads/types without creating empty durable conversations.

On task close, Box asks `agentd` to close the session. The adapter cancels pending approvals, interrupts an active turn, closes App Server stdin, and terminates the child if needed. There is no idle timeout in this PR. Durable thread IDs make later process eviction possible without equating process lifetime with conversation lifetime.

### Historical task caveat inherited from #42

PR #42 correctly avoids launching every restored session when the VM becomes Ready, and standing settings do not create outbox work that fans out into launches.

However, `GuestAgentBackend.events()` still calls the generic attach path. With a Ready VM, a restored task that has not been opened in the current guest is currently planned as `Open`, so merely opening an old transcript can start its harness. Fixing that correctly needs the generic attach API to distinguish **read/attach history** from **activate for work**; otherwise a naive `hasHistory -> ReadHistory` change makes `send()` queue work without starting the process. That lifecycle refactor belongs in the multi-harness layer, not as a Codex conditional here.

Consequences for this PR:

- merely restoring the task list does not launch Codex;
- VM Ready does not fan out over historical Codex tasks;
- standing model/viewport/permission broadcasts do not launch dormant Codex tasks;
- opening a historical task while the VM is Ready can still launch it because of the inherited #42 attach semantics;
- sending work launches/reuses the task harness as Box already does.

## Cold-start coordination

This PR does not add a `HarnessLaunchCoordinator`. The existing backend prevents accidental fan-out on VM readiness, which addresses the demonstrated pathological case, but two genuinely activated cold tasks can still start expensive harnesses concurrently.

A generic coordinator remains worth measuring under TCG. The safe shape would serialize only the startup phase until each adapter reaches `session_started`/ready, then allow requested work to run concurrently. Implementing it here would touch all harnesses and lifecycle ownership without an on-device measurement demonstrating the trade-off.

## Durable Codex thread resume

Box transcript persistence and Codex model state are independent. For Codex, the adapter stores the App Server thread ID in `/workspace/.config/box/codex/sessions/<box-session-id>.json`.

For a new conversation:

1. App Server starts and authentication is checked.
2. The first prompt calls `thread/start`.
3. The returned thread ID is written to a same-directory temporary file with mode `0600` and atomically renamed into place.
4. Only then does Box call `turn/start`.

For a recreated adapter:

1. It reads the saved thread ID.
2. The first prompt calls `thread/resume` with that exact ID.
3. If resume succeeds, the new turn continues that model thread.
4. If resume fails, Box receives an explicit recoverable error and **no replacement `thread/start` is attempted**. The transcript remains readable, but Box does not pretend model context survived.

The small unavoidable crash window is between successful `thread/start` and the atomic rename: a process death there can orphan one empty Codex thread. It cannot silently attach an old Box transcript to a new thread because a turn is not started before persistence completes.

A per-session lock file plus PID liveness check prevents two adapter processes from concurrently owning the same Box task and racing into duplicate thread creation. Stale locks are removed after the owner dies.

## App Server protocol mapping

The adapter targets the versioned protocol shipped with Codex 0.147.0 and initializes App Server over stdio JSON-RPC.

Mappings currently implemented:

| Codex | Box wire |
| --- | --- |
| `item/agentMessage/delta`, completed agent message | `message` |
| reasoning summary/text deltas | `thinking` |
| command/file/MCP/dynamic/collab/web-search item start | `tool_started` |
| command/file/MCP output/progress | `tool_progress` |
| item completion | `tool_finished` |
| structured file change diffs | `file_changed` |
| `item/commandExecution/requestApproval` | `permission_requested` |
| `item/fileChange/requestApproval` | `permission_requested` |
| Box allow/always/deny | Codex `accept` / `acceptForSession` / `decline` |
| `turn/plan/updated` | `task_progress` |
| `turn/interrupt` | Box Stop |
| turn/protocol errors | `error` |

Unknown notifications are intentionally ignored. The adapter does not synthesize fake busy/tool events when Box has no honest representation.

## Permissions and sandbox

Box's VM remains the security/isolation boundary. Box's permission UI remains the human/product policy. Codex is configured with `danger-full-access` **inside the VM** so a second filesystem sandbox does not make normal Box workspace behavior brittle, while approval policy is kept independently:

- Box **Ask** -> Codex `untrusted`
- Box **Accept safe edits** -> Codex `on-request`
- Box **Everything** -> Codex `never`

When Codex does ask, command and file-change approval callbacks are translated into Box's permission UI. `allow_always` maps to Codex's session-scoped `acceptForSession`; it is not represented as a permanent global Codex rule.

## Authentication

Credentials live entirely in the durable workspace. App Server is launched with `CODEX_HOME=/workspace/.config/codex` and `cli_auth_credentials_store="file"`, so credentials are not dependent on a desktop keyring or the replaceable system disk.

Initial sign-in is a secure manual guest path:

```bash
/opt/local-agent/codex/bin/box-codex-login
```

The helper uses App Server's `account/login/start` with `chatgptDeviceCode`, prints the official verification URL and one-time code, waits for `account/login/completed`, and supports cancellation. No API key or ChatGPT credential is sent through Android session-open payloads or transcript events.

A polished Android sign-in surface is a follow-up; the machine-facing auth path behind it is already App Server rather than CLI scraping.

## Models

This PR does not map Box's Claude-specific `AgentModel` enum onto Codex. Codex uses its configured/default model. The wrapper ignores Box's current generic `model` command until Box has a per-harness model descriptor/catalog abstraction.

This avoids lying about model identity at the cost of not exposing Codex model selection in the first PR.

## Attachments

Box already materializes attachments in `/workspace` before sending the turn metadata.

- Images are sent as App Server `localImage` inputs.
- Audio is sent as `localAudio` inputs.
- Other files remain workspace files; the adapter appends their exact paths and MIME labels to the user text so Codex can inspect them with ordinary tools.

The adapter waits for each path to become readable before starting the turn. It never claims a PDF/binary was uploaded as structured content when only a workspace path was supplied.

## Box-specific tools

This PR does not yet expose `show/artifact` or GitHub connect through Codex MCP. App Server exposes MCP/tool mechanisms and the adapter maps MCP lifecycle events, so MCP is the preferred extension point rather than adding Codex-specific Android commands. Wiring Box's existing local tools into a provider-neutral MCP surface is a follow-up.

## Interrupt and failure behavior

Box Stop uses `turn/interrupt`; it does not merely hide output. An interrupt received before `turn/start` returns is remembered and sent immediately once the turn ID exists.

On adapter shutdown, active turns are interrupted and pending approvals are answered with Codex `cancel` before the App Server child is closed. An unexpected App Server exit emits a recoverable Box error.

## Local tests

Protocol adapter tests (host Node, no Codex binary required):

```bash
node --check guest/codex/box-codex-harness.mjs
node --check guest/codex/box-codex-login.mjs
node --test guest/codex/test/box-codex-harness.test.mjs
bash -n guest/codex/install.sh
bash -n guest/build-image.sh
```

Android unit/build checks from the repository root:

```bash
./gradlew test
./gradlew assembleDebug
```

Full image build requires the repository's ARM64 image-builder environment described in the existing guest/development docs. The build itself verifies the Codex release checksum and prints harness footprint numbers.

## On-device test procedure

1. Build/install an APK containing a freshly-built guest image from this branch.
2. Enable **Open faster**, create a seed snapshot, reboot/reopen Box, and confirm no `codex-app-server`, Claude, or DeepSeek harness process exists before a task is activated.
3. In the Box terminal run `/opt/local-agent/codex/bin/box-codex-login` and complete the device-code flow.
4. Create a new task and choose **Codex**. Confirm only that task's adapter/App Server starts and Claude remains usable in a separate Claude task.
5. Send a simple text turn, a command-producing turn, a file-editing turn, an image attachment, and a non-image file attachment. Verify streaming, tool cards, diffs, and approvals.
6. Exercise Ask / Accept safe edits / Everything and verify the Box sheet drives the Codex decision.
7. Press Stop (a) immediately after send, (b) during a command, and (c) while an approval is pending; then send another prompt.
8. Record the saved thread ID, kill the Android UI process, kill/recreate the harness process or restart the guest, reopen the task, send another turn, and verify `thread/resume` continues the same context.
9. Temporarily move/corrupt the persisted Codex thread state or its Codex-side thread, retry, and verify Box surfaces resume failure instead of creating a blank continuation.
10. Restore a task list containing Claude, DeepSeek, and Codex history and verify VM readiness alone does not fan out into all harnesses. Also measure the inherited historical-task **open** behavior described above.
11. Measure cold App Server startup, peak/idle RSS, two simultaneously activated cold harnesses, seed snapshot size, and image qcow2/APK delta. Those TCG measurements cannot be reproduced faithfully on a normal development host.

## Known limitations / follow-ups

- Historical transcript **open** can still start a dormant harness while the VM is Ready because of #42's generic attach semantics; restored-list fan-out is fixed.
- Cold harness startups are not serialized yet.
- No idle process eviction timeout yet; durable resume is designed to permit it later.
- No Android Codex sign-in UI yet; use the App Server device-code helper in the guest.
- No per-harness Codex model catalog/selection yet.
- Box show/artifact and GitHub connect are not exposed to Codex yet; MCP is the intended path.
- Context/token usage notifications are not surfaced because Box has no current harness-neutral UI/event for them.
- App Server protocol is version-sensitive; upgrade the pinned binary and adapter/schema assumptions together.
- ARM64/QEMU TCG behavior, installed footprint, final qcow2/APK delta, snapshot-size impact, and real ChatGPT authentication still require an actual image/on-device run.

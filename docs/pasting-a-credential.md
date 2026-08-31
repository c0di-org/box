# Somewhere to paste a key

A proposal, not an implementation. Written from a phone on the image built from `d0af4db`,
31 Aug 2026, by a user who had a DeepSeek API key in their clipboard and nowhere in Box to put it.

## What happens today

Start a task on the DeepSeek harness without a key and the turn fails with a red card:

> **DeepSeek Harness could not complete this turn.**
> DeepSeek is not configured. Put a DeepSeek API key in `/workspace/.config/box/deepseek-api-key`
> from the Box terminal, then send the task again.

The message is accurate. `guest/deepseek/box-deepseek-harness.mjs:38` reads exactly that path, and
the app passes it as `BOX_DEEPSEEK_API_KEY_FILE` from `GuestAgentBackend.kt:873`. As an instruction
to somebody holding a phone, though, it asks for something close to impossible.

To follow it, the user must: open the computer pane, find a terminal on the openbox desktop, raise
the guest on-screen keyboard, and type a thirty-odd character secret by hand — because the phone's
clipboard does not reach the guest's X session. The one thing they already have is the key, in the
clipboard, one tap from being pasted. Box offers nowhere to paste it.

Meanwhile the failure card has a **Reconnect** button, which is the one action that cannot help:
there is nothing wrong with the connection.

## Box already has this mechanism

This does not need a new idea. It needs an existing one pointed at a second credential.

**A stdin command whose payload is a secret, deliberately never logged.** From
`guest/harness/box-claude-harness.mjs:352`:

```js
case 'auth_code': {
  // Never echoed. This is the one command whose payload is credential material, so unlike
  // `prompt` it does not get mirrored into the event log.
  pushAuthCode(String(command.code ?? ''));
  break;
}
```

That comment is the whole design. A credential travels down the same pipe as everything else and is
the one thing that does not become a transcript line.

**A sheet for typing one in.** `ui/SignInSheet.kt` is a `ModalBottomSheet` with a `BasicTextField`,
and `agent/GuestAuth.kt:142` is the other half:

```kotlin
fun submitCode(code: String) {
    val command = JSONObject().put("type", "auth_code").put("code", code.trim()).toString()
    runCatching { session.write((command + "\n").toByteArray()) }
}
```

**And a second one already exists for a second credential** — `ui/ConnectGitHubSheet.kt`.

So Box has solved this twice. Claude sign-in gets a device-code flow and a sheet; GitHub gets a
connect flow and a sheet; DeepSeek gets a sentence about a terminal.

## What it must not be

The obvious shortcut — let the user paste the key into the ordinary composer and have the agent
write it to the file — is the one option that must be refused, and it is worth saying why in the
same place as the proposal, because it will otherwise get built.

A message typed into the composer goes `sendMessage` → `promptCommand` → the guest, where the
harness's first act is `emit({ type: 'user_message', text })` — a line appended to the session log
on disk. That log is permanent, is replayed in full every time the conversation is opened, and is
rendered back as a chat bubble. A key pasted into the composer is a key written to storage in the
clear and drawn on screen every time anybody looks at that task.

That is exactly the distinction the `auth_code` comment is drawing, and it is the reason the answer
is a sheet rather than a message.

## The proposal

1. **Make the failure card's action fit the failure.** A missing credential is not a connection
   problem. Where the card currently offers only **Reconnect**, offer **Paste API key** — and keep
   Reconnect for the case that really is one.

2. **Reuse the sign-in sheet.** A `ModalBottomSheet`, one obscured field, a paste affordance, a
   Save. `SignInSheet` is the template; this is a smaller version of it.

3. **Send it down the non-echoed channel.** A new stdin command, shaped like the one that already
   works:

   ```json
   {"type": "api_key", "value": "sk-…"}
   ```

   Handled by the DeepSeek harness the way `auth_code` is handled by the Claude one: consumed,
   never emitted, never mirrored into the log.

4. **Let the harness write the file.** It is already inside the guest and already owns that path
   (`box-deepseek-harness.mjs:38`). The app writing across the VM boundary would be a second
   mechanism for no gain. Write it `0600`, matching what is already in `/workspace/.config/box`.

5. **Retry the turn that failed.** "Then send the task again" is a step the machine can take
   itself. The prompt is the thing that failed; the user has just supplied the only thing it was
   missing.

### One detail that makes this safe to build

`box-deepseek-harness.mjs:65` reads the key as `(await readFile(...)).trim()` and returns `null`
for an empty result, with `ENOENT` treated identically. **An empty file and a missing file are the
same state.** So a key file can be created ahead of time, a half-finished paste cannot produce a
broken intermediate state, and a cleared field means "not configured" rather than "configured with
nothing". The failure mode is already benign; only the way in is missing.

## The general version

DeepSeek is the second harness to need a credential and the third route into `/workspace/.config`:

| Credential | How it gets there | Usable from a phone |
| --- | --- | --- |
| Claude | Device-code flow, `SignInSheet`, `auth_code` over stdin | Yes |
| GitHub | `ConnectGitHubSheet`, connect flow, credential helper | Yes |
| DeepSeek | "Put a key in `/workspace/.config/box/deepseek-api-key` from the Box terminal" | No |

A fourth harness will invent a fourth. The small shared contract worth having: a harness declares
that it needs a named secret — `AgentBackend.harnesses` already exposes `HarnessDescriptor` as the
place a harness describes itself to the UI — Box raises one sheet for any harness that says so, and
the value goes down the non-echoed channel to be written by whoever owns the path. One sheet, one
command, one rule about what never reaches the log.

That is less code than three bespoke paths, and it means the next harness gets this for free
instead of getting a sentence about a terminal.

## Smaller things worth fixing alongside

- **The error text names a path the reader cannot reach.** Even once a sheet exists, telling a
  phone user about `/workspace/.config/box/deepseek-api-key` and "the Box terminal" describes a
  machine they are not sitting at. It should say what to do, not where the file lives.
- **`Reconnect` on a not-configured card is a wrong affordance**, not just a missing one: it
  invites a retry that is guaranteed to fail the same way, and reads as though the problem might be
  transient.

## What has *not* been done

- **No implementation.** No sheet, no command, no harness handler.
- **Not designed against the real DeepSeek flow beyond the key.** This assumes the key is the only
  thing missing, which is what the harness currently reports; whether DeepSeek needs anything else
  configured has not been checked.
- **The generalisation in the table is a suggestion, not a requirement.** Fixing DeepSeek alone is
  worth doing on its own, and is much the smaller change.

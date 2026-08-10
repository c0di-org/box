# agentd protocol v2

`agentd` is the only host-facing service inside the guest. It speaks over one
private QEMU virtio-serial port (`dev.localagent.agentd`) backed by an app-private
Unix socket. There is no TCP listener, nothing on the LAN, and `agentd` runs as the
unprivileged `agent` user. v2 changes the wire format; it does not change that.

v1 ([agentd-v1.md](agentd-v1.md)) was newline-delimited JSON with exactly one
request in flight, buffered whole. That carried a one-shot `exec` and nothing else.
v2 is a byte-oriented multiplexer: many logical streams over the same port, each
independently readable, cancellable and flow-controlled.

## 1. Frame

Every byte on the port belongs to a frame. A frame is a fixed 12-byte header
followed by exactly `payloadLength` bytes.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-------+-------+-------+-------+-------+-------+-------+-------+
|    version    |     type      |    channel    |   reserved    |
+---------------+---------------+---------------+---------------+
|                       streamId  (u32, big endian)             |
+---------------------------------------------------------------+
|                    payloadLength (u32, big endian)            |
+---------------------------------------------------------------+
|                     payload (payloadLength bytes)             |
+---------------------------------------------------------------+
```

| field | value |
| --- | --- |
| `version` | always `2`. Any other value is a fatal framing error. |
| `type` | see [§2](#2-frame-types). Unknown types are fatal. |
| `channel` | `0` control, `1` stdin, `2` stdout, `3` stderr. |
| `reserved` | must be `0`. |
| `streamId` | `0` for connection-level frames, otherwise the logical stream. |
| `payloadLength` | `0..65536`. Larger is a fatal framing error. |

All integers are big endian. Multi-byte fields are naturally aligned within the
header, which makes a hexdump of the channel readable by eye during bring-up.

### Why binary length-prefixed, and not newline JSON

* **Exact byte counts.** Flow control is credit in bytes. A length prefix gives the
  receiver the size of a frame before it allocates anything, so a hostile or buggy
  peer can never make the app allocate more than one 64 KiB buffer.
* **Raw bytes on the hot path.** PTY output and file contents are arbitrary bytes.
  Newline framing forces base64 (+33% and a copy) on exactly the traffic that
  matters most. v2 `read_file`/`write_file` stream raw bytes; v1's base64 body and
  its 12 MiB whole-response cap are both gone.
* **Bounded parsing.** Scanning for a delimiter is unbounded work over untrusted
  input. `read 12; validate; read N` is not.
* **No new dependencies.** Control payloads stay compact UTF-8 JSON, so the guest
  keeps using stdlib `json` and the app keeps using `org.json`. A binary object
  encoding (CBOR, protobuf, msgpack) would buy little here and would have to be
  vendored into the guest image.

`payloadLength` is capped at 64 KiB rather than left open because the port is a
single ordered byte stream: while one frame is on the wire, every other stream
waits. 64 KiB bounds that head-of-line delay. A sender with more to say splits it
across frames; nothing in the protocol reassembles frames, so there is no
reassembly buffer to overflow.

## 2. Frame types

| code | name | streams | payload |
| --- | --- | --- | --- |
| `0x01` | `HELLO` | `0` | JSON capabilities |
| `0x02` | `OPEN` | `>0` | JSON `{"kind":…}` — host→guest only |
| `0x03` | `DATA` | `>0` | raw bytes on `channel` |
| `0x04` | `END` | `>0` | empty — no more `DATA` on `channel` from this sender |
| `0x05` | `CLOSE` | `>0` | JSON terminal status — guest→host only |
| `0x06` | `WINDOW` | `>0` | `u32` big-endian credit increment in bytes |
| `0x07` | `CANCEL` | `>0` | JSON `{"signal":…}` (optional) — host→guest only |
| `0x08` | `CTRL` | `>0` | JSON `{"op":…}` — either direction |
| `0x09` | `PING` | `0` | 8 opaque bytes |
| `0x0A` | `PONG` | `0` | the 8 bytes from the `PING` |
| `0x0B` | `GOAWAY` | `0` | JSON `{"code":…,"message":…}` |

`WINDOW` carries a bare `u32` rather than JSON because it is the one control frame
on the hot path — one per ~64 KiB of output — and 4 bytes beats ~30.

## 3. Streams

A stream is one logical operation: a method call, a command, a PTY.

* **Ids.** Host-initiated streams use odd ids, guest-initiated streams use even
  non-zero ids. Nothing in v2 is guest-initiated; the split exists so that adding
  guest-pushed streams later (agent events, notifications) cannot collide with an
  id the host is already using. Ids increase monotonically and are never reused
  within a connection. On exhaustion the peer sends `GOAWAY` and the host
  reconnects.
* **Opening.** The host sends `OPEN` and may immediately send `DATA`. There is no
  open acknowledgement: if the guest rejects the request it answers `CLOSE` with an
  error, which is the same code path as any other failure.
* **Ending.** `END` is a half-close of one direction of one channel — the host
  sends `END` on `stdin` to give a child process EOF while still reading its
  output. `CLOSE` terminates the whole stream and only the guest sends it.
* **Retirement.** A stream id is retired when the host has received its `CLOSE`.
  The host cancels by sending `CANCEL`; the guest still answers `CLOSE`
  (`"status":"cancelled"`). One rule — *guest `CLOSE` ends a stream* — means there
  is never a window where both sides disagree about whether an id is live, so an id
  cannot be recycled onto a straggling frame.

`CLOSE` payload:

```json
{"status":"ok","exitCode":0}
{"status":"cancelled","exitCode":143}
{"status":"error","error":{"code":"timeout","message":"command exceeded its time limit"}}
```

`status` is one of `ok`, `error`, `cancelled`. `exitCode` is present when a process
was involved and was reaped.

## 4. Flow control

Credit-based, per stream, per direction, counted in `DATA` payload bytes only.
Control frames are never charged: they are small, bounded in number by the traffic
they describe, and charging them could deadlock a stream that needs to send a
`WINDOW` to make progress.

1. Each side starts every stream with `initialWindowBytes` of credit to send.
2. A sender may have at most that many unacknowledged `DATA` bytes outstanding.
   At zero credit it stops writing.
3. A receiver sends `WINDOW` **only after its consumer has taken the bytes** — not
   on arrival. This is the whole point: the in-memory queue for a stream can never
   exceed one window, because more can only arrive once something left.
4. Violating the window is a fatal protocol error (`GOAWAY`, connection reset).

Because credit is only replenished on consumption, backpressure reaches all the way
to the producing process: the guest stops reading a child's pipe or PTY master, the
pipe fills, and the child blocks in `write()`. A process that prints in a tight
loop throttles itself against the app's ability to consume, using the kernel's pipe
buffer as the queue. No component has to buffer without limit for that to work.

**Total bound.** `maxConcurrentStreams × initialWindowBytes` per direction — with
the defaults, 32 × 128 KiB = 4 MiB. There is deliberately no second,
connection-level window: it would not tighten a bound we already have, and a shared
window is the classic way for one stalled stream to starve every other one.

**Cancellation must not deadlock.** After sending `CANCEL`, the host keeps granting
credit for whatever still arrives on that stream and discards it. Otherwise a guest
blocked on a window it will never receive could not run its own teardown and would
never send the `CLOSE` that retires the id.

## 5. Ordering and errors

The port is a single byte stream, so frames are totally ordered and per-stream
ordering falls out for free — v2 carries no sequence numbers. The only requirement
is that each side serialise whole frames: both implementations funnel every write
through one writer, so two frames can never interleave.

There are exactly three ways out of a stream, and the difference is deliberate:

1. **Graceful** — guest `CLOSE` with `"status":"ok"`.
2. **Failed operation** — guest `CLOSE` with `"status":"error"`. The stream failed;
   the connection is untouched and every other stream keeps running. v1's rule that
   failures are data, never silently converted to a successful result, is kept.
3. **Fatal framing error** — bad version, unknown type, non-zero `reserved`,
   oversized payload, window violation, unknown stream. `GOAWAY` is attempted, the
   socket is closed, and every open stream fails. A length-prefixed stream cannot
   be resynchronised after a desync, so guessing is worse than reconnecting.

## 6. Handshake

The host sends `HELLO` on stream `0` immediately after connecting and the guest
replies with its own. Effective limits are the minimum of the two sides.

```json
→ {"version":2,"client":"box-android","maxFramePayloadBytes":65536,
   "initialWindowBytes":131072,"maxConcurrentStreams":32}
← {"version":2,"agent":"agentd/2","maxFramePayloadBytes":65536,
   "initialWindowBytes":131072,"maxConcurrentStreams":32,
   "capabilities":["call","exec","pty"],"workspace":"/workspace"}
```

The app and the guest image ship together, so there is no v1 fallback: a v1
`agentd` answers a `HELLO` frame with a JSON error line whose first byte is `{`
(`0x7b`) in the version field. The host detects exactly that byte and fails fast
with "the guest agent is older than this app" instead of retrying for three
minutes.

`PING`/`PONG` on stream `0` detect a wedged agent that has not died (QEMU exiting is
already caught elsewhere). The host pings when the connection has been idle and
gives up after three unanswered pings.

## 7. Stream kinds

### `call` — the v1 request/response methods

```json
{"kind":"call","method":"read_file","params":{"path":"/workspace/notes.txt"}}
```

The result body streams back as `DATA` on `stdout`; `CLOSE` carries the status.
There is no `id` field any more — the stream id is the correlation. Because the
body streams, no method has a whole-response size cap in the transport.

| method | params | result body |
| --- | --- | --- |
| `health` | — | JSON `{"ready":true,"protocol":2,"workspace":…}` |
| `list_files` | `path` | JSON `{"items":[{name,path,directory,size}]}` |
| `read_file` | `path` | raw file bytes |
| `write_file` | `path` | JSON `{"bytesWritten":N}` — the content is streamed by the host on `stdin`, terminated by `END` |

### `exec` — a command, streamed

```json
{"kind":"exec","command":["cargo","build"],"cwd":"/workspace",
 "env":{"RUSTFLAGS":"-C debuginfo=0"},"timeoutSeconds":900,"stdin":false}
```

`DATA` on `stdout` and `stderr` as the process produces it. `CLOSE` carries
`exitCode`. With `"stdin":true` the host may write `stdin` and `END` it.

### `pty` — an interactive terminal

```json
{"kind":"pty","command":["/bin/bash","-l"],"cwd":"/workspace",
 "columns":80,"rows":24,"term":"xterm-256color"}
```

The child gets a real controlling terminal (`openpty` + `setsid` + `TIOCSCTTY`), so
line editing, job control and full-screen programs work. Output is merged onto
`stdout` exactly as a terminal merges it. The host writes keystrokes as `stdin`
`DATA` and resizes with `CTRL`:

```json
{"op":"resize","columns":120,"rows":40}
```

A PTY is not a new privilege. `exec` already ran arbitrary commands as `agent`; the
difference is a terminal device, not what may be run.

### Cancellation

`CANCEL` with an optional `{"signal":"TERM"|"KILL"}`. The guest signals the child's
whole **process group** — a build's subprocesses die with it — escalating to
`SIGKILL` after a grace period, then answers `CLOSE` with `"status":"cancelled"`.

## 8. Limits

| limit | value | enforced by |
| --- | --- | --- |
| `maxFramePayloadBytes` | 64 KiB | both, fatally |
| `initialWindowBytes` | 128 KiB | both |
| `maxConcurrentStreams` | 32 | both |
| `MAX_FILE_BYTES` | 64 MiB | guest, per `read_file`/`write_file` |
| exec/pty timeout | 1..900 s (`exec` default 120 s, `pty` none) | guest |
| `CANCEL` grace before `SIGKILL` | 3 s | guest |

## 9. Security

The threat model is unchanged from v1, and v2 does not widen it:

* The channel is an app-private Unix socket created by QEMU under `filesDir`. No
  TCP, no LAN listener, no Android API exposed to the guest.
* `agentd` runs as `agent` via systemd `User=agent`. Nothing in v2 needs root.
* What v2 adds is a parser reachable from the guest, so the parser is written to be
  boring: a fixed-size header, a hard payload cap checked *before* allocating, a
  hard stream cap, a hard window, and immediate connection teardown on anything
  unexpected. No length taken from the wire is ever trusted as an allocation size
  without being range-checked first.

### The `resolve_path` guardrail (documented wart, deliberately kept)

`agentd` restricts `read_file`/`write_file`/`remove_file` to `/workspace`, and
working directories to `/workspace` or `/home/agent`. **This is not a security
boundary.** `exec` and `pty` run arbitrary commands as `agent`, so anything the
file methods refuse is one `cat` away. The check exists so that an agent harness
with a wrong path scribbles inside the workspace instead of over the guest's
`/etc`, and so that a path bug is a clear error rather than silent damage.

It is also not TOCTOU-safe: the path is resolved and then opened, so a symlink
swapped in between could escape. Closing that would need `O_NOFOLLOW` on every path
component, which is not worth it for a guardrail.

The real isolation boundary is the VM itself. Everything inside the guest is
already compromised together, which is exactly why the guest never gets a network
listener or an Android capability.

## 10. Implementation notes

* **Guest** (`guest/agentd/agentd.py`, one file so the entire guest attack surface
  stays auditable in one place): three threads regardless of stream count — a
  reader that only ever blocks on the port, a writer draining a bounded queue, and
  one `selectors` event loop pumping every child fd. Credit-aware registration is
  how backpressure is applied: at zero credit the fd is deregistered and the child
  blocks on its own pipe.
* **Host** (`runtime-qemu/.../Agentd*.kt`): one reader coroutine, one writer
  coroutine draining a `Channel`, and a stream registry. Framing is pure Kotlin
  with no Android or JSON dependency so it is unit-testable on the JVM.
* Two Android landmines that this code must keep respecting:
  `LocalSocket.connect(address, timeout)` throws `UnsupportedOperationException` —
  the retry loop owns the deadline; and a `setSoTimeout` expiry surfaces as a bare
  `IOException` carrying EAGAIN text, not `SocketTimeoutException` — see
  `SocketTimeouts.kt`.

### Not yet wired

The `:computer` → UI Binder surface (`IRuntimeControl`) still exposes only the
buffered `exec`, `listFiles` and `readFile` calls. Streaming exec output and PTY
sessions across that process boundary is the next step and needs its own session
handle; the runtime API (`ComputerRuntime.execStream`, `createPty`) is complete and
is what that work will build on.

# agentd v2 — implementation notes and handoff

Companion to [`protocol/agentd-v2.md`](../protocol/agentd-v2.md). The spec is the
contract: framing, flow control, stream lifecycle, and *why* each choice was made.
This document is for whoever touches the code next — where things live, what is
proven and what is not, the traps that cost time, and the invariants that will
break the channel quietly if violated.

## 1. Where the code is

| File | Role |
| --- | --- |
| `protocol/agentd-v2.md` | The wire contract. Change this **first** when changing behaviour. |
| `guest/agentd/agentd.py` | The entire guest service: framing, pump, exec, PTY, file methods. |
| `guest/tests/test_agentd.py` | Drives the real service over a socketpair. |
| `runtime-qemu/.../AgentdFrame.kt` | Codec and constants. No Android, no JSON. |
| `runtime-qemu/.../AgentdConnection.kt` | Multiplexer: reader, writer, streams, credit. No JSON. |
| `runtime-qemu/.../AgentdClient.kt` | Protocol vocabulary: `call` / `exec` / `pty`, JSON, connect. |
| `runtime-qemu/.../QemuTcgRuntime.kt` | `ComputerRuntime` implementation on top of the client. |
| `runtime-qemu/src/test/.../FakeGuest.kt` | Hand-written guest half + in-memory pipe, shared by both host test classes. |

The layering is deliberate and worth preserving: **`AgentdConnection` never parses
JSON.** Control payloads are handed up as bytes. That is what lets the multiplexer
be unit-tested on the JVM, where `org.json` from the mockable `android.jar` throws
from every method.

`agentd.py` is one file on purpose. It is the whole host-facing attack surface, and
one auditable file with one install step beats a package plus `PYTHONPATH` games in
a systemd unit running as an unprivileged user. Resist splitting it.

## 2. Running the tests

```bash
python3 -m unittest discover -s guest/tests
```

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :runtime-qemu:testDebugUnitTest
```

Run the guest suite with `python3 -W error::ResourceWarning -m unittest discover -s
guest/tests` when touching process handling — a leaked child or an unreaped zombie
shows up as a `ResourceWarning` rather than a failure.

## 3. Verification status — read this before trusting anything

**Proven by the test suites.** The guest suite drives the shipping
`Connection` over a real `socketpair` with real subprocesses and real PTYs:
streaming-while-running, stdout/stderr separation, exit codes, timeouts, cancel
killing the whole process group (asserted via a grandchild that must not survive),
concurrency, the concurrent-stream cap, both flow-control directions, PTY echo,
`TIOCSWINSZ` resize reaching the child as a real `stty size`, and controlling-tty
acquisition. The host suite drives the shipping multiplexer and client against a
hand-written guest: codec validation, credit accounting, cancellation, limit
negotiation, framing-violation teardown, and every stream kind.

The channel has since carried real work on hardware — a booted guest, agent sessions,
file transfer and PTYs. What the test suites do not reach, and a device run should still
be watched for, is the v1-guest detection path: it is reasoned from what a v1 `agentd`
would emit, not observed.

## 4. Traps that cost time

### Android

1. `LocalSocket.connect(address, timeout)` is **not implemented** —
   `LocalSocketImpl` unconditionally throws `UnsupportedOperationException`. Use the
   single-arg overload; the caller's retry loop owns the deadline.
2. A `LocalSocket` read that hits its `setSoTimeout` deadline throws a **bare
   `IOException` carrying EAGAIN strerror text**, not `SocketTimeoutException`.
   `AgentdConnection.readFully` relies on `isSocketReadTimeout()` in
   `SocketTimeouts.kt`, which handles both forms. Polling with a 1 s socket timeout
   is also what lets coroutine cancellation win over a blocking read.

### Kotlin coroutines

3. **`flow { withTimeout { emitAll(...) } }` throws "Flow invariant is violated".**
   `withTimeout` creates a child coroutine with a different `Job`, and `SafeCollector`
   compares the full context at every `emit`. The first draft of
   `QemuTcgRuntime.execStream` did exactly this. The fix is to wrap the *collection*
   instead — see `exec()`, which applies the transport backstop around
   `execStream(request).collect { }`. A cold flow that emits must stay in one
   coroutine; `withContext(NonCancellable)` in a `finally` is fine only because
   nothing is emitted inside it.
4. `runBlocking` uses a single-threaded event loop on the calling thread, so a
   `launch { }` inside it never runs while that thread is blocked. Test senders must
   be `launch(Dispatchers.IO)`, and any coroutine left suspended at the end of the
   body will hang `runBlocking` until it completes — cancel stalled senders
   explicitly.
5. `withTimeoutOrNull` **cannot** interrupt a blocking stream read. It will not
   report silence; it will return whatever frame eventually arrives. `FakeGuest`
   therefore reads on a dedicated thread into a `LinkedBlockingQueue` and polls that.
   An earlier harness used `withTimeoutOrNull` and "failed" by picking up the 20 s
   keepalive PING.
6. Unit tests need a real `org.json` (`testImplementation(libs.json)`); the stub in
   the mockable `android.jar` throws.

### Python guest

7. `del bytearray[:n]` raises `BufferError` if a `memoryview` of it is still alive.
   `_drain_sink` takes a bounded copy instead.
8. **Descriptor ownership must be singular.** `Popen` owns the fds behind
   `process.stdout` / `stderr` / `stdin`; closing them independently is a double
   close once `Popen` is collected, onto an fd number that may have been recycled.
   `FdSource`/`FdSink` take a `closer` callable so the `Popen` file object stays the
   owner. The PTY sink is an explicit `os.dup(master)` for the same reason.
9. The parent **must** `os.close(slave)` after spawning a PTY child, or the master
   never reports EOF and the stream never finishes.
10. `epoll` rejects regular files, so `selectors.DefaultSelector` cannot carry a
    `read_file` source. The pump uses `select.select` and treats non-fd sources
    (`BytesSource`, `FileSource`) as always-runnable while they have credit.
11. A controlling terminal needs `start_new_session=True` **plus**
    `preexec_fn=_attach_controlling_terminal` doing `TIOCSCTTY`; `setsid` alone does
    not attach one.
12. On host disconnect, `_abort_all` SIGKILLs and then `wait()`s every child.
    Without the reap, a reconnect cycle accumulates zombies in the guest.

## 5. Invariants — breaking these fails quietly

- **A stream id retires only when the host receives `CLOSE`.** Cancel does not
  retire it; the guest still answers `CLOSE`. This is what makes id reuse safe.
- **Credit is returned on consumption, never on arrival.** Return it earlier and the
  in-memory queue for a stream is no longer bounded by one window, which is the
  entire OOM defence. On the guest, that means granting in `_drain_sink` after the
  write, not in `_accept_data` on receipt.
- **After `CANCEL`, keep granting credit and discard the data.** A guest blocked on a
  window it will never receive cannot run teardown, so it never sends the `CLOSE`
  that retires the id. Deadlock. Both sides implement this; both sides test it.
- **Every write goes through one writer** (the guest's writer thread, the host's
  writer coroutine) so frames are never interleaved. There are no sequence numbers;
  ordering rests entirely on this.
- **Never allocate from a length off the wire before range-checking it.**
  `decodeHeader` validates version, reserved byte, type, channel, stream-0 rules and
  length before the payload buffer exists.
- **A framing fault is fatal to the connection, not to a stream.** Do not try to
  resynchronise a length-prefixed byte stream.
- **The guest's pump thread is the sole owner of stream state.** The reader thread
  posts commands onto a deque and wakes the pump via a self-pipe. There are no locks
  because there is no sharing; keep it that way.

## 6. Security notes for the next change

The threat model is unchanged from v1 and v2 does not widen it: app-private Unix
socket via a QEMU chardev, no TCP, no LAN listener, no Android capability exposed to
the guest, `agentd` running as `agent` via systemd `User=agent`. What v2 *adds* is a
parser reachable from the guest, which is why the codec is deliberately boring and
why the limits in spec §8 are enforced fatally on both sides.

`resolve_path()` restricts file methods to `/workspace`, and working directories to
`/workspace` or `/home/agent` (widened in v2 so a PTY shell can start in the agent's
home). **It is a guardrail, not a security boundary** — `exec` and `pty` run
arbitrary commands as `agent`, so anything it refuses is one `cat` away, and it is
not TOCTOU-safe. It exists so a harness with a wrong path scribbles inside the
workspace instead of over `/etc`. The real boundary is the VM. Do not let a future
feature come to depend on it as though it were isolation.

A PTY is not a new privilege: `exec` already ran arbitrary commands as `agent`. The
difference is a terminal device, not what may be run.

## 7. What is not done

- **No PTY across Binder.** `IRuntimeControl` carries buffered `exec` and file methods,
  and streams agent sessions, but nothing exposes `ComputerRuntime.createPty` to the UI.
  That work needs a session handle surviving `oneway` callbacks, a cancellation token,
  and chunking well under the ~1 MB Binder transaction cap.
- **No v1 fallback, by decision.** The app and guest image ship together. A stale v1
  guest is detected (its JSON error line puts `{` in the version byte) and fails fast
  with "older than this app" instead of retrying for three minutes. If images ever
  ship independently of the APK, this becomes a real compatibility problem and the
  handshake needs a negotiated downgrade.
- **No connection-level flow-control window**, deliberately: the bound
  `maxConcurrentStreams × initialWindowBytes` already exists, and a shared window is
  the classic way for one stalled stream to starve the others. Revisit only with a
  measurement showing 4 MiB worst-case is too much.
- **`WINDOW` frames queue behind `DATA` in the host outbox** (32 frames). Bounded, so
  not a deadlock, but it adds latency under load. If interactive PTY latency
  disappoints on a real device, a priority lane for control frames is the first thing
  to try.
- Guest `list_files` builds the whole listing in memory before streaming it; the host
  caps a call result at 1 MiB. Fine for a workspace, wrong for a huge directory.

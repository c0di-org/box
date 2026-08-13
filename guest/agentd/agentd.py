#!/usr/bin/env python3
"""Private virtio-serial control service for Local Agent Workstation (protocol v2).

This deliberately exposes no Android API and starts as the unprivileged `agent`
user. Privileged package installation will be a separately audited method.

The wire format is specified in `protocol/agentd-v2.md`: a 12-byte header, a raw
payload, and many logical streams over the single virtio port.

Three threads, whatever the stream count:

  reader  parses frames off the port and posts commands. It never blocks on
          anything but the port, so one wedged child cannot stall the channel.
  pump    the only owner of stream state. Applies commands, moves bytes between
          child descriptors and the wire, and enforces send credit.
  writer  drains a bounded queue onto the port, so frames are always whole.
"""
from __future__ import annotations

import argparse
import collections
import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import queue
import select
import signal
import struct
import subprocess
import sys
import termios
import threading
import time
from typing import Any, Callable

PROTOCOL_VERSION = 2
AGENT_NAME = "agentd/2"

WORKSPACE = Path("/workspace")
HOME = Path("/home/agent")

# Claude Code keeps its credential under its config directory, which defaults to
# ~/.claude -- and the agent's home is on the system disk, which a guest image update
# replaces wholesale. Left there, every Box update silently signs the user out and makes
# them redo the browser handshake, which is exactly what keeping the workspace disk across
# updates was meant to avoid. Pointing the config directory at the workspace puts the
# credential on the disk that survives.
CLAUDE_CONFIG_DIR = WORKSPACE / ".config" / "claude"

# git's own state, on the workspace disk for exactly the reason above: the box's commit identity
# and its GitHub credential helper are configured once, when the user connects GitHub, and a
# ~/.gitconfig would be thrown away by the next image update -- signing the user out of git
# silently and leaving `git commit` failing with "please tell me who you are".
GIT_CONFIG = WORKSPACE / ".config" / "git" / "config"
GH_CONFIG_DIR = WORKSPACE / ".config" / "gh"

MAX_FRAME_PAYLOAD = 64 * 1024
INITIAL_WINDOW_BYTES = 128 * 1024
MAX_CONCURRENT_STREAMS = 32
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_EXEC_TIMEOUT_SECONDS = 900
DEFAULT_EXEC_TIMEOUT_SECONDS = 120
CANCEL_GRACE_SECONDS = 3.0
WRITER_QUEUE_FRAMES = 64
PUMP_TICK_SECONDS = 0.5

HEADER = struct.Struct(">BBBBII")
HEADER_BYTES = HEADER.size

FRAME_HELLO = 0x01
FRAME_OPEN = 0x02
FRAME_DATA = 0x03
FRAME_END = 0x04
FRAME_CLOSE = 0x05
FRAME_WINDOW = 0x06
FRAME_CANCEL = 0x07
FRAME_CTRL = 0x08
FRAME_PING = 0x09
FRAME_PONG = 0x0A
FRAME_GOAWAY = 0x0B

FRAME_TYPES = frozenset(range(FRAME_HELLO, FRAME_GOAWAY + 1))
CONNECTION_FRAMES = frozenset((FRAME_HELLO, FRAME_PING, FRAME_PONG, FRAME_GOAWAY))

CHANNEL_CONTROL = 0
CHANNEL_STDIN = 1
CHANNEL_STDOUT = 2
CHANNEL_STDERR = 3
CHANNELS = frozenset((CHANNEL_CONTROL, CHANNEL_STDIN, CHANNEL_STDOUT, CHANNEL_STDERR))


class FramingError(Exception):
    """Unrecoverable: a length-prefixed stream cannot be resynchronised."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class StreamError(Exception):
    """One operation failed. The connection and every other stream survive."""

    def __init__(self, message: str, code: str = "invalid_request") -> None:
        super().__init__(message)
        self.code = code


class Disconnected(Exception):
    """The host went away; reopen the port and wait for the next connection."""


def encode_frame(frame_type: int, stream_id: int, channel: int, payload: bytes) -> bytes:
    if len(payload) > MAX_FRAME_PAYLOAD:
        raise FramingError("frame_too_large", "outgoing frame exceeds the payload limit")
    return HEADER.pack(PROTOCOL_VERSION, frame_type, channel, 0, stream_id, len(payload)) + payload


def decode_header(header: bytes) -> tuple[int, int, int, int]:
    """Returns (type, channel, streamId, payloadLength). Anything unexpected is fatal."""
    version, frame_type, channel, reserved, stream_id, length = HEADER.unpack(header)
    if version != PROTOCOL_VERSION:
        raise FramingError("bad_version", f"frame version {version} is not {PROTOCOL_VERSION}")
    if reserved != 0:
        raise FramingError("bad_frame", "reserved header byte must be zero")
    if frame_type not in FRAME_TYPES:
        raise FramingError("bad_frame", f"unknown frame type 0x{frame_type:02x}")
    if channel not in CHANNELS:
        raise FramingError("bad_frame", f"unknown channel {channel}")
    if length > MAX_FRAME_PAYLOAD:
        raise FramingError("frame_too_large", f"payload of {length} bytes exceeds the limit")
    if (stream_id == 0) != (frame_type in CONNECTION_FRAMES):
        raise FramingError("bad_frame", f"frame type 0x{frame_type:02x} on stream {stream_id}")
    return frame_type, channel, stream_id, length


# --- path handling ---------------------------------------------------------
#
# This is a guardrail against a mistaken path, NOT a security boundary: `exec`
# and `pty` run arbitrary commands as `agent`, so anything refused here is one
# `cat` away. It keeps a harness with a wrong path inside the workspace instead
# of over the guest's /etc, and turns a path bug into a clear error rather than
# silent damage. It is not TOCTOU-safe either: the path is resolved and then
# opened. The real isolation boundary is the VM. See protocol/agentd-v2.md §9.


def _guarded_path(value: str, roots: tuple[Path, ...]) -> Path:
    path = Path(value).resolve()
    for root in roots:
        resolved = root.resolve()
        if path == resolved or resolved in path.parents:
            return path
    raise StreamError(f"paths must be inside {' or '.join(str(root) for root in roots)}")


def resolve_path(value: str) -> Path:
    """File methods stay inside the workspace."""
    return _guarded_path(value, (WORKSPACE,))


def resolve_working_directory(value: str) -> Path:
    """Commands may also start in the agent's home, where tooling keeps its state."""
    return _guarded_path(value, (WORKSPACE, HOME))


def child_environment(extra: Any) -> dict[str, str]:
    # Set here rather than at either call site in the app: every guest process is built
    # from this one place, so the sign-in session and the agent session that later reads
    # the credential cannot drift apart. A caller that genuinely needs its own config
    # directory can still override it through `extra`.
    environment = {
        **os.environ,
        "HOME": str(HOME),
        "CLAUDE_CONFIG_DIR": str(CLAUDE_CONFIG_DIR),
        "GIT_CONFIG_GLOBAL": str(GIT_CONFIG),
        "GH_CONFIG_DIR": str(GH_CONFIG_DIR),
        # git asks for a username on the terminal when it has no credential, which on a pty
        # session is a prompt nobody is looking at and a session that hangs until it is killed.
        # Off, so an unauthenticated clone fails in a second with a message the agent can read
        # and act on -- which is what lets it ask the user to connect GitHub instead of stalling.
        "GIT_TERMINAL_PROMPT": "0",
    }
    if extra is None:
        return environment
    if not isinstance(extra, dict) or not all(
        isinstance(key, str) and isinstance(value, str) for key, value in extra.items()
    ):
        raise StreamError("env must be a string-to-string object")
    environment.update(extra)
    return environment


def require_command(params: dict[str, Any]) -> list[str]:
    command = params.get("command")
    if not isinstance(command, list) or not command or not all(isinstance(x, str) for x in command):
        raise StreamError("command must be a non-empty string array")
    return command


# --- byte producers and consumers -----------------------------------------


class Source:
    """Produces bytes for one channel of one stream."""

    fd: int | None = None
    finished = False

    def read(self, limit: int) -> bytes | None:
        """Bytes, b"" at end of stream, or None when a descriptor is not ready."""
        raise NotImplementedError

    def close(self) -> None:
        pass


class BytesSource(Source):
    """An already-computed result body, streamed out under the same flow control."""

    def __init__(self, data: bytes) -> None:
        self._data = data
        self._offset = 0

    def read(self, limit: int) -> bytes:
        chunk = self._data[self._offset : self._offset + limit]
        self._offset += len(chunk)
        return chunk


class FileSource(Source):
    """`read_file`, streamed. Regular files are never handed to select()."""

    def __init__(self, path: Path) -> None:
        self._handle = path.open("rb")

    def read(self, limit: int) -> bytes:
        return self._handle.read(limit)

    def close(self) -> None:
        self._handle.close()


class FdSource(Source):
    """A child's pipe or PTY master, kept non-blocking.

    `closer` exists so a descriptor has exactly one owner: for a subprocess pipe
    the owner is the Popen file object, and closing it here would otherwise be a
    double close once Popen is collected.
    """

    def __init__(self, fd: int, closer: Callable[[], None] | None = None) -> None:
        self.fd = fd
        self._closer = closer if closer is not None else (lambda: os.close(fd))
        os.set_blocking(fd, False)

    def read(self, limit: int) -> bytes | None:
        try:
            return os.read(self.fd, limit)
        except BlockingIOError:
            return None
        except OSError as error:
            # A PTY master reports EIO once the last slave descriptor is gone.
            if error.errno in (errno.EIO, errno.EBADF):
                return b""
            raise

    def close(self) -> None:
        if self.fd is None:
            return
        self.fd = None
        try:
            self._closer()
        except OSError:
            pass


class Sink:
    """Consumes host bytes for one stream."""

    fd: int | None = None

    def write(self, data: bytes) -> int:
        raise NotImplementedError

    def finish(self) -> None:
        pass

    def close(self) -> None:
        pass


class FileSink(Sink):
    """`write_file`, streamed. Bounded so a runaway host cannot fill the disk."""

    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self._handle = path.open("wb")
        self.written = 0

    def write(self, data: bytes) -> int:
        if self.written + len(data) > MAX_FILE_BYTES:
            raise StreamError("file exceeds the transfer limit", code="too_large")
        self._handle.write(data)
        self.written += len(data)
        return len(data)

    def finish(self) -> None:
        self._handle.flush()

    def close(self) -> None:
        self._handle.close()


class FdSink(Sink):
    """A child's stdin pipe or a PTY master, kept non-blocking."""

    def __init__(self, fd: int, closer: Callable[[], None] | None = None, close_on_finish: bool = False) -> None:
        self.fd = fd
        self._closer = closer if closer is not None else (lambda: os.close(fd))
        self._close_on_finish = close_on_finish
        os.set_blocking(fd, False)

    def write(self, data: bytes) -> int:
        if self.fd is None:
            return len(data)
        try:
            return os.write(self.fd, data)
        except BlockingIOError:
            return 0
        except OSError as error:
            if error.errno in (errno.EPIPE, errno.EIO, errno.EBADF):
                return len(data)  # The child stopped reading; drop the rest.
            raise

    def finish(self) -> None:
        if self._close_on_finish:
            self.close()

    def close(self) -> None:
        if self.fd is None:
            return
        self.fd = None
        try:
            self._closer()
        except OSError:
            pass


# --- streams ---------------------------------------------------------------


class Stream:
    """Pump-owned state for one logical operation."""

    def __init__(self, stream_id: int, send_credit: int = INITIAL_WINDOW_BYTES) -> None:
        self.id = stream_id
        self.sources: list[tuple[int, Source]] = []
        self.sink: Sink | None = None
        self.sink_pending = bytearray()
        self.sink_eof = False
        self.sink_finished = False
        self.sink_gates_completion = False
        self.on_sink_finished: Callable[[Stream], None] | None = None
        self.process: subprocess.Popen[bytes] | None = None
        self.send_credit = send_credit
        self.ungranted_from_host = 0
        self.deadline: float | None = None
        self.kill_deadline: float | None = None
        self.abandon_deadline: float | None = None
        self.status = "ok"
        self.error: dict[str, str] | None = None
        self.exit_code: int | None = None

    @property
    def sources_drained(self) -> bool:
        return all(source.finished for _, source in self.sources)

    @property
    def sink_settled(self) -> bool:
        return self.sink_finished or not self.sink_gates_completion

    def discard_output(self) -> None:
        for _, source in self.sources:
            if not source.finished:
                source.finished = True
                source.close()

    def release(self) -> None:
        for _, source in self.sources:
            source.close()
        self.sources.clear()
        if self.sink is not None:
            self.sink.close()
            self.sink = None

    def signal_process(self, sig: int) -> None:
        """Signal the whole process group, so a build's children die with it."""
        if self.process is None or self.process.poll() is not None:
            return
        try:
            os.killpg(os.getpgid(self.process.pid), sig)
        except (ProcessLookupError, PermissionError, OSError):
            try:
                self.process.send_signal(sig)
            except (ProcessLookupError, OSError):
                pass


# --- the connection --------------------------------------------------------


class Connection:
    """One host attachment to the virtio port."""

    def __init__(self, read_fd: int, write_fd: int) -> None:
        self._read_fd = read_fd
        self._write_fd = write_fd
        self._outbox: queue.Queue[bytes | None] = queue.Queue(maxsize=WRITER_QUEUE_FRAMES)
        self._commands: collections.deque[tuple[int, int, int, bytes]] = collections.deque()
        self._wake_read, self._wake_write = os.pipe()
        os.set_blocking(self._wake_read, False)
        os.set_blocking(self._wake_write, False)
        self._streams: dict[int, Stream] = {}
        self._abandoned: list[subprocess.Popen[bytes]] = []
        # Effective limits are the lower of the two sides', settled by HELLO before any OPEN.
        self._peer_window = INITIAL_WINDOW_BYTES
        self._peer_frame_payload = MAX_FRAME_PAYLOAD
        self._stopping = threading.Event()
        self._writer = threading.Thread(target=self._write_loop, name="agentd-writer", daemon=True)
        self._pump = threading.Thread(target=self._pump_loop, name="agentd-pump", daemon=True)

    # -- lifecycle

    def serve(self) -> None:
        self._writer.start()
        self._pump.start()
        try:
            self._read_loop()
        except (Disconnected, OSError):
            pass
        except FramingError as error:
            self._send(FRAME_GOAWAY, 0, CHANNEL_CONTROL, _json(
                {"code": error.code, "message": str(error)},
            ))
        finally:
            self._shutdown()

    def _shutdown(self) -> None:
        self._stopping.set()
        self._wake()
        self._pump.join(timeout=5)
        self._outbox.put(None)
        self._writer.join(timeout=5)
        for fd in (self._wake_read, self._wake_write):
            try:
                os.close(fd)
            except OSError:
                pass

    def _wake(self) -> None:
        try:
            os.write(self._wake_write, b"\x01")
        except (BlockingIOError, OSError):
            pass

    # -- writing

    def _send(self, frame_type: int, stream_id: int, channel: int, payload: bytes) -> None:
        if self._stopping.is_set():
            return
        try:
            self._outbox.put(encode_frame(frame_type, stream_id, channel, payload))
        except FramingError:
            pass

    def _write_loop(self) -> None:
        while True:
            frame = self._outbox.get()
            if frame is None:
                return
            view = memoryview(frame)
            try:
                while view:
                    view = view[os.write(self._write_fd, view) :]
            except OSError:
                self._stopping.set()
                self._wake()
                return

    # -- reading

    def _read_exactly(self, count: int) -> bytes:
        chunks = bytearray()
        while len(chunks) < count:
            chunk = os.read(self._read_fd, count - len(chunks))
            if not chunk:
                raise Disconnected()
            chunks += chunk
        return bytes(chunks)

    def _read_loop(self) -> None:
        while not self._stopping.is_set():
            frame_type, channel, stream_id, length = decode_header(self._read_exactly(HEADER_BYTES))
            payload = self._read_exactly(length) if length else b""
            self._dispatch(frame_type, channel, stream_id, payload)

    def _dispatch(self, frame_type: int, channel: int, stream_id: int, payload: bytes) -> None:
        # Stream-0 frames are answered inline; everything else becomes pump work.
        if frame_type == FRAME_HELLO:
            self._adopt_peer_limits(payload)
            self._send(FRAME_HELLO, 0, CHANNEL_CONTROL, _json({
                "version": PROTOCOL_VERSION,
                "agent": AGENT_NAME,
                "maxFramePayloadBytes": MAX_FRAME_PAYLOAD,
                "initialWindowBytes": INITIAL_WINDOW_BYTES,
                "maxConcurrentStreams": MAX_CONCURRENT_STREAMS,
                "capabilities": ["call", "exec", "pty"],
                "workspace": str(WORKSPACE),
            }))
            return
        if frame_type == FRAME_PING:
            self._send(FRAME_PONG, 0, CHANNEL_CONTROL, payload)
            return
        if frame_type == FRAME_PONG:
            return
        if frame_type == FRAME_GOAWAY:
            raise Disconnected()
        if frame_type == FRAME_CLOSE:
            raise FramingError("bad_frame", "CLOSE is sent by the guest only")
        if frame_type == FRAME_WINDOW and len(payload) != 4:
            raise FramingError("bad_frame", "WINDOW payload must be four bytes")

        self._commands.append((frame_type, channel, stream_id, payload))
        self._wake()

    def _adopt_peer_limits(self, payload: bytes) -> None:
        try:
            hello = _parse_json(payload)
        except StreamError:
            return
        self._peer_window = _limit(hello.get("initialWindowBytes"), INITIAL_WINDOW_BYTES)
        self._peer_frame_payload = _limit(hello.get("maxFramePayloadBytes"), MAX_FRAME_PAYLOAD)

    # -- the pump

    def _pump_loop(self) -> None:
        while not self._stopping.is_set():
            self._apply_commands()
            worked = self._move_bytes()
            self._reap()
            timeout = 0.0 if worked else self._next_timeout()
            read_fds = [self._wake_read]
            write_fds: list[int] = []
            for stream in self._streams.values():
                if stream.send_credit > 0:
                    read_fds.extend(
                        source.fd
                        for _, source in stream.sources
                        if source.fd is not None and not source.finished
                    )
                if stream.sink is not None and stream.sink.fd is not None and stream.sink_pending:
                    write_fds.append(stream.sink.fd)
            try:
                select.select(read_fds, write_fds, [], timeout)
            except (OSError, ValueError):
                pass
            self._drain_wakeups()
        self._abort_all()

    def _drain_wakeups(self) -> None:
        try:
            while os.read(self._wake_read, 4096):
                pass
        except (BlockingIOError, OSError):
            pass

    def _next_timeout(self) -> float:
        deadlines = [
            deadline
            for stream in self._streams.values()
            for deadline in (stream.deadline, stream.kill_deadline, stream.abandon_deadline)
            if deadline is not None
        ]
        if self._abandoned:
            deadlines.append(time.monotonic() + PUMP_TICK_SECONDS)
        if not deadlines:
            return PUMP_TICK_SECONDS
        return max(0.0, min(PUMP_TICK_SECONDS, min(deadlines) - time.monotonic()))

    def _apply_commands(self) -> None:
        while self._commands:
            frame_type, channel, stream_id, payload = self._commands.popleft()
            if frame_type == FRAME_OPEN:
                self._open(stream_id, payload)
                continue
            stream = self._streams.get(stream_id)
            if stream is None:
                # A frame for a retired id races legitimately with our own CLOSE,
                # so an unknown stream is ignored rather than treated as fatal.
                continue
            if frame_type == FRAME_DATA:
                self._accept_data(stream, channel, payload)
            elif frame_type == FRAME_END:
                if channel == CHANNEL_STDIN:
                    stream.sink_eof = True
            elif frame_type == FRAME_WINDOW:
                stream.send_credit += struct.unpack(">I", payload)[0]
            elif frame_type == FRAME_CANCEL:
                self._cancel(stream, payload)
            elif frame_type == FRAME_CTRL:
                self._control(stream, payload)

    def _accept_data(self, stream: Stream, channel: int, payload: bytes) -> None:
        if channel != CHANNEL_STDIN or stream.sink is None or stream.sink_finished:
            # Nowhere to put it, but the host's credit must not be stranded.
            self._grant_window(stream, len(payload))
            return
        stream.sink_pending += payload

    def _grant_window(self, stream: Stream, consumed: int) -> None:
        """Credit is returned only once bytes have left our hands, so the queue for
        a stream can never exceed one window."""
        stream.ungranted_from_host += consumed
        if stream.ungranted_from_host >= INITIAL_WINDOW_BYTES // 2:
            self._send(FRAME_WINDOW, stream.id, CHANNEL_CONTROL,
                       struct.pack(">I", stream.ungranted_from_host))
            stream.ungranted_from_host = 0

    def _cancel(self, stream: Stream, payload: bytes) -> None:
        requested = _parse_json(payload).get("signal") if payload else None
        stream.status = "cancelled"
        stream.discard_output()
        if stream.process is None:
            return
        now = time.monotonic()
        stream.signal_process(signal.SIGKILL if requested == "KILL" else signal.SIGTERM)
        stream.deadline = None
        stream.kill_deadline = now + CANCEL_GRACE_SECONDS
        stream.abandon_deadline = now + CANCEL_GRACE_SECONDS * 2

    def _control(self, stream: Stream, payload: bytes) -> None:
        try:
            request = _parse_json(payload)
        except StreamError:
            return
        if request.get("op") != "resize" or stream.sink is None or stream.sink.fd is None:
            return
        columns = _bounded_int(request.get("columns"), 80)
        rows = _bounded_int(request.get("rows"), 24)
        try:
            fcntl.ioctl(stream.sink.fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))
        except OSError:
            pass

    def _move_bytes(self) -> bool:
        worked = False
        for stream in list(self._streams.values()):
            if self._drain_sink(stream):
                worked = True
            for channel, source in list(stream.sources):
                while stream.send_credit > 0 and not source.finished:
                    limit = min(self._peer_frame_payload, stream.send_credit)
                    try:
                        chunk = source.read(limit)
                    except OSError:
                        chunk = b""
                    if chunk is None:
                        break
                    if not chunk:
                        source.finished = True
                        break
                    stream.send_credit -= len(chunk)
                    self._send(FRAME_DATA, stream.id, channel, chunk)
                    worked = True
        return worked

    def _drain_sink(self, stream: Stream) -> bool:
        sink = stream.sink
        if sink is None or stream.sink_finished:
            return False
        worked = False
        while stream.sink_pending:
            # A copy, not a memoryview: a live view would block the del below.
            chunk = bytes(stream.sink_pending[:MAX_FRAME_PAYLOAD])
            try:
                written = sink.write(chunk)
            except StreamError as error:
                self._finish(stream, "error", error=_error(error))
                return True
            except OSError as error:
                self._finish(stream, "error", error=_error(StreamError(str(error), "io_error")))
                return True
            if written <= 0:
                break
            del stream.sink_pending[:written]
            self._grant_window(stream, written)
            worked = True
        if stream.sink_eof and not stream.sink_pending:
            stream.sink_finished = True
            try:
                sink.finish()
            except OSError:
                pass
            if stream.on_sink_finished is not None:
                stream.on_sink_finished(stream)
            worked = True
        return worked

    def _reap(self) -> None:
        self._abandoned = [process for process in self._abandoned if process.poll() is None]
        now = time.monotonic()
        for stream in list(self._streams.values()):
            if stream.deadline is not None and now >= stream.deadline:
                stream.deadline = None
                stream.status = "error"
                stream.error = {"code": "timeout", "message": "command exceeded its time limit"}
                stream.discard_output()
                stream.signal_process(signal.SIGKILL)
                stream.kill_deadline = None
                stream.abandon_deadline = now + CANCEL_GRACE_SECONDS
            if stream.kill_deadline is not None and now >= stream.kill_deadline:
                stream.kill_deadline = None
                stream.signal_process(signal.SIGKILL)
            if stream.abandon_deadline is not None and now >= stream.abandon_deadline:
                # An unkillable child must not pin a stream id forever.
                if stream.process is not None and stream.process.poll() is None:
                    self._abandoned.append(stream.process)
                    stream.process = None
                self._finish(stream, stream.status, error=stream.error)
                continue

            if stream.process is not None:
                stream.exit_code = stream.process.poll()
                if stream.exit_code is None:
                    continue
            if stream.sources_drained and stream.sink_settled:
                self._finish(stream, stream.status, error=stream.error)

    def _finish(self, stream: Stream, status: str, error: dict[str, str] | None = None) -> None:
        if self._streams.pop(stream.id, None) is None:
            return
        payload: dict[str, Any] = {"status": status}
        if stream.exit_code is not None:
            payload["exitCode"] = stream.exit_code
        if error is not None:
            payload["status"] = "error"
            payload["error"] = error
        stream.release()
        self._send(FRAME_CLOSE, stream.id, CHANNEL_CONTROL, _json(payload))

    def _abort_all(self) -> None:
        """The host is gone. Kill every child and reap it, so a disconnect cannot
        leave the guest accumulating zombies across reconnections."""
        orphans = [stream.process for stream in self._streams.values() if stream.process]
        for stream in list(self._streams.values()):
            stream.signal_process(signal.SIGKILL)
            stream.release()
            self._streams.pop(stream.id, None)
        for process in orphans + self._abandoned:
            try:
                process.wait(timeout=1)
            except (subprocess.TimeoutExpired, OSError):
                pass
        self._abandoned.clear()

    # -- opening streams

    def _open(self, stream_id: int, payload: bytes) -> None:
        if stream_id % 2 == 0:
            self._reject(stream_id, StreamError("host streams use odd ids", "bad_stream"))
            return
        if stream_id in self._streams:
            self._reject(stream_id, StreamError("stream id is already open", "bad_stream"))
            return
        if len(self._streams) >= MAX_CONCURRENT_STREAMS:
            self._reject(stream_id, StreamError("too many concurrent streams", "too_many_streams"))
            return
        stream = Stream(stream_id, send_credit=self._peer_window)
        self._streams[stream_id] = stream
        try:
            request = _parse_json(payload)
            kind = request.get("kind")
            if kind == "call":
                self._start_call(stream, request)
            elif kind == "exec":
                self._start_exec(stream, request)
            elif kind == "pty":
                self._start_pty(stream, request)
            else:
                raise StreamError(f"unsupported stream kind {kind!r}")
        except StreamError as error:
            self._finish(stream, "error", error=_error(error))
        except (KeyError, TypeError, ValueError, OSError) as error:
            self._finish(stream, "error", error=_error(StreamError(str(error))))

    def _reject(self, stream_id: int, error: StreamError) -> None:
        self._send(FRAME_CLOSE, stream_id, CHANNEL_CONTROL,
                   _json({"status": "error", "error": _error(error)}))

    def _start_call(self, stream: Stream, request: dict[str, Any]) -> None:
        method = request.get("method")
        params = request.get("params") or {}
        if not isinstance(params, dict):
            raise StreamError("params must be an object")

        if method == "health":
            stream.sources.append((CHANNEL_STDOUT, BytesSource(_json({
                "ready": True,
                "protocol": PROTOCOL_VERSION,
                "agent": AGENT_NAME,
                "workspace": str(WORKSPACE),
            }))))
        elif method == "list_files":
            path = resolve_path(params.get("path", str(WORKSPACE)))
            items = [
                {
                    "name": entry.name,
                    "path": str(entry),
                    "directory": entry.is_dir(),
                    "size": entry.stat().st_size,
                }
                for entry in sorted(path.iterdir())
            ]
            stream.sources.append((CHANNEL_STDOUT, BytesSource(_json({"items": items}))))
        elif method == "read_file":
            path = resolve_path(params["path"])
            if path.stat().st_size > MAX_FILE_BYTES:
                raise StreamError("file exceeds the transfer limit", code="too_large")
            stream.sources.append((CHANNEL_STDOUT, FileSource(path)))
        elif method == "write_file":
            sink = FileSink(resolve_path(params["path"]))
            stream.sink = sink
            stream.sink_gates_completion = True
            stream.on_sink_finished = lambda target: target.sources.append(
                (CHANNEL_STDOUT, BytesSource(_json({"bytesWritten": sink.written}))),
            )
        else:
            raise StreamError(f"unsupported method {method!r}")

    def _start_exec(self, stream: Stream, request: dict[str, Any]) -> None:
        command = require_command(request)
        cwd = resolve_working_directory(request.get("cwd", str(WORKSPACE)))
        # 0 means no deadline. An agent harness works for as long as the task takes, and a
        # wall-clock limit that kills it mid-edit is worse than none: the stream is still bounded
        # by CANCEL, and _abort_all kills every child when the host disconnects. A pty has never
        # had a deadline for the same reason.
        requested = int(request.get("timeoutSeconds", DEFAULT_EXEC_TIMEOUT_SECONDS))
        timeout = (
            None if requested == 0
            else min(max(requested, 1), MAX_EXEC_TIMEOUT_SECONDS)
        )
        wants_stdin = bool(request.get("stdin", False))
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=child_environment(request.get("env")),
            stdin=subprocess.PIPE if wants_stdin else subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
        stream.process = process
        if timeout is not None:
            stream.deadline = time.monotonic() + timeout
        # Popen owns these descriptors; the sources borrow them and close through it.
        stream.sources.append((CHANNEL_STDOUT, FdSource(process.stdout.fileno(), process.stdout.close)))
        stream.sources.append((CHANNEL_STDERR, FdSource(process.stderr.fileno(), process.stderr.close)))
        if wants_stdin and process.stdin is not None:
            stream.sink = FdSink(
                process.stdin.fileno(), process.stdin.close, close_on_finish=True,
            )

    def _start_pty(self, stream: Stream, request: dict[str, Any]) -> None:
        command = require_command(request)
        cwd = resolve_working_directory(request.get("cwd", str(WORKSPACE)))
        environment = child_environment(request.get("env"))
        environment.setdefault("TERM", str(request.get("term", "xterm-256color")))
        columns = _bounded_int(request.get("columns"), 80)
        rows = _bounded_int(request.get("rows"), 24)

        master, slave = pty.openpty()
        fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))
        try:
            process = subprocess.Popen(
                command,
                cwd=cwd,
                env=environment,
                stdin=slave,
                stdout=slave,
                stderr=slave,
                start_new_session=True,
                preexec_fn=_attach_controlling_terminal,
            )
        except Exception:
            os.close(master)
            os.close(slave)
            raise
        # The parent must drop the slave, or the master never reports end of file.
        os.close(slave)
        stream.process = process
        stream.sources.append((CHANNEL_STDOUT, FdSource(master)))
        # A separate descriptor, so closing the readable side cannot leave the
        # writable side pointing at a recycled fd number.
        stream.sink = FdSink(os.dup(master))


def _attach_controlling_terminal() -> None:
    """Runs in the child after setsid, so the shell gets a real controlling tty."""
    fcntl.ioctl(0, termios.TIOCSCTTY, 0)


def _json(value: Any) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


def _parse_json(payload: bytes) -> dict[str, Any]:
    if not payload:
        return {}
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise StreamError(f"payload is not valid JSON: {error}") from error
    if not isinstance(value, dict):
        raise StreamError("payload must be a JSON object")
    return value


def _error(error: StreamError) -> dict[str, str]:
    return {"code": error.code, "message": str(error)}


def _limit(value: Any, ceiling: int) -> int:
    """A peer may lower one of our limits but never raise it."""
    try:
        return max(1, min(int(value), ceiling))
    except (TypeError, ValueError):
        return ceiling


def _bounded_int(value: Any, fallback: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return fallback
    return number if 1 <= number <= 10_000 else fallback


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", help="Dedicated virtio-serial device to use for protocol I/O")
    args = parser.parse_args()

    WORKSPACE.mkdir(parents=True, exist_ok=True)
    if not args.device:
        Connection(sys.stdin.fileno(), sys.stdout.fileno()).serve()
        return 0

    # QEMU reports EOF from a virtio port until a host endpoint connects, and
    # again after a client disconnects. Keep the service alive and reopen the
    # *single* bidirectional descriptor for the next LocalSocket connection.
    while True:
        try:
            fd = os.open(args.device, os.O_RDWR)
        except OSError:
            time.sleep(0.2)
            continue
        try:
            Connection(fd, fd).serve()
        finally:
            try:
                os.close(fd)
            except OSError:
                pass
        time.sleep(0.2)


if __name__ == "__main__":
    raise SystemExit(main())

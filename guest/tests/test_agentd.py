"""End-to-end tests for agentd protocol v2.

Every test drives the real `Connection` over a socketpair, so framing, streaming,
cancellation and flow control are exercised against the code that ships, not
against a stub of it.
"""
from __future__ import annotations

import contextlib
import importlib.util
import json
from pathlib import Path
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest


AGENTD_PATH = Path(__file__).parents[1] / "agentd" / "agentd.py"
SPEC = importlib.util.spec_from_file_location("box_agentd", AGENTD_PATH)
assert SPEC is not None and SPEC.loader is not None
agentd = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = agentd
SPEC.loader.exec_module(agentd)

RECEIVE_TIMEOUT_SECONDS = 10.0


class Client:
    """The host half of the protocol, deliberately hand-rolled."""

    def __init__(self, sock: socket.socket) -> None:
        self._socket = sock
        self._socket.settimeout(RECEIVE_TIMEOUT_SECONDS)
        self._next_stream = 1

    def open_stream(self) -> int:
        stream_id = self._next_stream
        self._next_stream += 2
        return stream_id

    def send(self, frame_type: int, stream_id: int, channel: int, payload: bytes = b"") -> None:
        self._socket.sendall(agentd.encode_frame(frame_type, stream_id, channel, payload))

    def send_json(self, frame_type: int, stream_id: int, value: dict) -> None:
        self.send(frame_type, stream_id, agentd.CHANNEL_CONTROL,
                  json.dumps(value).encode("utf-8"))

    def open(self, request: dict) -> int:
        stream_id = self.open_stream()
        self.send_json(agentd.FRAME_OPEN, stream_id, request)
        return stream_id

    def grant(self, stream_id: int, credit: int) -> None:
        self.send(agentd.FRAME_WINDOW, stream_id, agentd.CHANNEL_CONTROL,
                  struct.pack(">I", credit))

    def receive(self, timeout: float | None = None) -> tuple[int, int, int, bytes]:
        if timeout is not None:
            self._socket.settimeout(timeout)
        try:
            frame_type, channel, stream_id, length = agentd.decode_header(self._read(agentd.HEADER_BYTES))
            return frame_type, channel, stream_id, self._read(length) if length else b""
        finally:
            self._socket.settimeout(RECEIVE_TIMEOUT_SECONDS)

    def _read(self, count: int) -> bytes:
        chunks = bytearray()
        while len(chunks) < count:
            chunk = self._socket.recv(count - len(chunks))
            if not chunk:
                raise AssertionError("agentd closed the channel")
            chunks += chunk
        return bytes(chunks)

    def collect(self, stream_id: int, auto_grant: bool = True) -> tuple[dict, bytes, bytes]:
        """Runs one stream to completion, returning (close, stdout, stderr)."""
        stdout, stderr = bytearray(), bytearray()
        while True:
            frame_type, channel, frame_stream, payload = self.receive()
            self.assert_stream(frame_stream, stream_id)
            if frame_type == agentd.FRAME_DATA:
                (stdout if channel == agentd.CHANNEL_STDOUT else stderr).extend(payload)
                if auto_grant:
                    self.grant(stream_id, len(payload))
            elif frame_type == agentd.FRAME_CLOSE:
                return json.loads(payload), bytes(stdout), bytes(stderr)

    def assert_stream(self, actual: int, expected: int) -> None:
        if actual != expected:
            raise AssertionError(f"frame for stream {actual}, expected {expected}")

    def call(self, method: str, params: dict | None = None) -> tuple[dict, bytes]:
        stream_id = self.open({"kind": "call", "method": method, "params": params or {}})
        close, stdout, _ = self.collect(stream_id)
        return close, stdout


@contextlib.contextmanager
def connected():
    host, guest = socket.socketpair()
    connection = agentd.Connection(guest.fileno(), guest.fileno())
    server = threading.Thread(target=connection.serve, name="agentd-test", daemon=True)
    server.start()
    try:
        yield Client(host)
    finally:
        host.close()
        server.join(timeout=RECEIVE_TIMEOUT_SECONDS)
        guest.close()


class FramingTests(unittest.TestCase):
    def test_header_round_trip(self) -> None:
        frame = agentd.encode_frame(agentd.FRAME_DATA, 7, agentd.CHANNEL_STDERR, b"boom")

        frame_type, channel, stream_id, length = agentd.decode_header(frame[: agentd.HEADER_BYTES])

        self.assertEqual(agentd.HEADER_BYTES, 12)
        self.assertEqual(agentd.FRAME_DATA, frame_type)
        self.assertEqual(agentd.CHANNEL_STDERR, channel)
        self.assertEqual(7, stream_id)
        self.assertEqual(4, length)
        self.assertEqual(b"boom", frame[agentd.HEADER_BYTES :])

    def test_a_wrong_version_is_fatal(self) -> None:
        header = struct.pack(">BBBBII", 1, agentd.FRAME_DATA, 0, 0, 1, 0)

        with self.assertRaisesRegex(agentd.FramingError, "version 1"):
            agentd.decode_header(header)

    def test_an_oversized_length_is_rejected_before_it_is_allocated(self) -> None:
        header = struct.pack(">BBBBII", 2, agentd.FRAME_DATA, 0, 0, 1, 1 << 30)

        with self.assertRaises(agentd.FramingError) as raised:
            agentd.decode_header(header)

        self.assertEqual("frame_too_large", raised.exception.code)

    def test_reserved_byte_and_stream_zero_are_enforced(self) -> None:
        with self.assertRaisesRegex(agentd.FramingError, "reserved"):
            agentd.decode_header(struct.pack(">BBBBII", 2, agentd.FRAME_DATA, 0, 1, 1, 0))
        with self.assertRaisesRegex(agentd.FramingError, "on stream 0"):
            agentd.decode_header(struct.pack(">BBBBII", 2, agentd.FRAME_DATA, 0, 0, 0, 0))
        with self.assertRaisesRegex(agentd.FramingError, "on stream 3"):
            agentd.decode_header(struct.pack(">BBBBII", 2, agentd.FRAME_PING, 0, 0, 3, 0))


class WorkspaceTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._previous_workspace = agentd.WORKSPACE
        self._previous_home = agentd.HOME
        self._directory = tempfile.TemporaryDirectory()
        agentd.WORKSPACE = Path(self._directory.name).resolve()
        agentd.HOME = agentd.WORKSPACE / "home"
        agentd.HOME.mkdir()

    def tearDown(self) -> None:
        agentd.WORKSPACE = self._previous_workspace
        agentd.HOME = self._previous_home
        self._directory.cleanup()


class HandshakeTests(WorkspaceTestCase):
    def test_hello_reports_limits_and_capabilities(self) -> None:
        with connected() as client:
            client.send_json(agentd.FRAME_HELLO, 0, {"version": 2, "client": "test"})

            frame_type, _, stream_id, payload = client.receive()
            hello = json.loads(payload)

            self.assertEqual(agentd.FRAME_HELLO, frame_type)
            self.assertEqual(0, stream_id)
            self.assertEqual(2, hello["version"])
            self.assertEqual(agentd.MAX_FRAME_PAYLOAD, hello["maxFramePayloadBytes"])
            self.assertEqual(agentd.INITIAL_WINDOW_BYTES, hello["initialWindowBytes"])
            self.assertEqual(["call", "exec", "pty"], hello["capabilities"])

    def test_ping_is_echoed(self) -> None:
        with connected() as client:
            client.send(agentd.FRAME_PING, 0, agentd.CHANNEL_CONTROL, b"12345678")

            frame_type, _, _, payload = client.receive()

            self.assertEqual(agentd.FRAME_PONG, frame_type)
            self.assertEqual(b"12345678", payload)

    def test_a_framing_violation_ends_the_connection_with_goaway(self) -> None:
        with connected() as client:
            client._socket.sendall(struct.pack(">BBBBII", 9, agentd.FRAME_DATA, 0, 0, 1, 0))

            frame_type, _, _, payload = client.receive()

            self.assertEqual(agentd.FRAME_GOAWAY, frame_type)
            self.assertEqual("bad_version", json.loads(payload)["code"])


class CallTests(WorkspaceTestCase):
    def test_health_reports_protocol_and_workspace(self) -> None:
        with connected() as client:
            close, body = client.call("health")

            self.assertEqual("ok", close["status"])
            health = json.loads(body)
            self.assertTrue(health["ready"])
            self.assertEqual(agentd.PROTOCOL_VERSION, health["protocol"])
            self.assertEqual(str(agentd.WORKSPACE), health["workspace"])

    def test_file_round_trip_streams_raw_bytes_and_lists_sorted(self) -> None:
        content = b"hello from Box\n\x00\xff binary is fine now"
        (agentd.WORKSPACE / "a-directory").mkdir()
        target = agentd.WORKSPACE / "notes.txt"

        with connected() as client:
            stream_id = client.open({
                "kind": "call", "method": "write_file", "params": {"path": str(target)},
            })
            client.send(agentd.FRAME_DATA, stream_id, agentd.CHANNEL_STDIN, content)
            client.send(agentd.FRAME_END, stream_id, agentd.CHANNEL_STDIN)
            write_close, write_body, _ = client.collect(stream_id)

            read_close, read_body = client.call("read_file", {"path": str(target)})
            list_close, list_body = client.call("list_files", {"path": str(agentd.WORKSPACE)})

        self.assertEqual("ok", write_close["status"])
        self.assertEqual(len(content), json.loads(write_body)["bytesWritten"])
        self.assertEqual(content, target.read_bytes())

        self.assertEqual("ok", read_close["status"])
        self.assertEqual(content, read_body)

        self.assertEqual("ok", list_close["status"])
        items = json.loads(list_body)["items"]
        self.assertEqual(["a-directory", "home", "notes.txt"], [item["name"] for item in items])
        self.assertTrue(items[0]["directory"])
        self.assertFalse(items[2]["directory"])

    def test_a_file_larger_than_one_frame_is_streamed_in_order(self) -> None:
        payload = bytes(range(256)) * 1024  # 256 KiB, four frames plus a window refill
        target = agentd.WORKSPACE / "large.bin"
        target.write_bytes(payload)

        with connected() as client:
            close, body = client.call("read_file", {"path": str(target)})

        self.assertEqual("ok", close["status"])
        self.assertEqual(payload, body)

    def test_paths_cannot_escape_through_a_symlink(self) -> None:
        outside = Path(self._directory.name).parent / "box-agentd-outside"
        (agentd.WORKSPACE / "outside").symlink_to(outside)

        with connected() as client:
            close, _ = client.call("read_file", {"path": str(agentd.WORKSPACE / "outside" / "secret")})

        self.assertEqual("error", close["status"])
        self.assertIn("must be inside", close["error"]["message"])

    def test_an_unsupported_method_fails_the_stream_not_the_connection(self) -> None:
        with connected() as client:
            close, _ = client.call("not_a_method")
            self.assertEqual("error", close["status"])
            self.assertEqual("invalid_request", close["error"]["code"])

            # The connection is still usable, which is the whole point of the split.
            healthy, _ = client.call("health")
            self.assertEqual("ok", healthy["status"])


class ExecTests(WorkspaceTestCase):
    def test_streams_are_separated_and_carry_the_exit_code(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "exec",
                "command": [sys.executable, "-c",
                            "import sys; sys.stdout.write('out'); sys.stderr.write('err'); sys.exit(3)"],
                "cwd": str(agentd.WORKSPACE),
            })
            close, stdout, stderr = client.collect(stream_id)

        self.assertEqual("ok", close["status"])
        self.assertEqual(3, close["exitCode"])
        self.assertEqual(b"out", stdout)
        self.assertEqual(b"err", stderr)

    def test_output_arrives_while_the_command_is_still_running(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "exec",
                "command": [sys.executable, "-c",
                            "import sys,time; sys.stdout.write('early'); sys.stdout.flush();"
                            " time.sleep(1.5)"],
                "cwd": str(agentd.WORKSPACE),
            })
            started = time.monotonic()
            frame_type, channel, _, payload = client.receive()
            first_byte_delay = time.monotonic() - started

            self.assertEqual(agentd.FRAME_DATA, frame_type)
            self.assertEqual(agentd.CHANNEL_STDOUT, channel)
            self.assertEqual(b"early", payload)
            # v1 could not have produced this: it buffered the whole run first.
            self.assertLess(first_byte_delay, 1.0)

            client.send_json(agentd.FRAME_CANCEL, stream_id, {})
            client.collect(stream_id)

    def test_the_host_can_write_stdin_and_signal_end_of_file(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "exec",
                "command": [sys.executable, "-c",
                            "import sys; sys.stdout.write(sys.stdin.read().upper())"],
                "cwd": str(agentd.WORKSPACE),
                "stdin": True,
            })
            client.send(agentd.FRAME_DATA, stream_id, agentd.CHANNEL_STDIN, b"quiet")
            client.send(agentd.FRAME_END, stream_id, agentd.CHANNEL_STDIN)
            close, stdout, _ = client.collect(stream_id)

        self.assertEqual(0, close["exitCode"])
        self.assertEqual(b"QUIET", stdout)

    def test_a_working_directory_outside_the_guardrail_is_refused(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "exec", "command": ["true"], "cwd": "/etc",
            })
            close, _, _ = client.collect(stream_id)

        self.assertEqual("error", close["status"])
        self.assertIn("must be inside", close["error"]["message"])

    def test_the_agent_home_is_a_valid_working_directory(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "exec",
                "command": [sys.executable, "-c", "import os; print(os.getcwd())"],
                "cwd": str(agentd.HOME),
            })
            close, stdout, _ = client.collect(stream_id)

        self.assertEqual(0, close["exitCode"])
        self.assertEqual(str(agentd.HOME), stdout.decode().strip())

    def test_a_timeout_reports_an_error_rather_than_a_success(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "exec",
                "command": [sys.executable, "-c", "import time; time.sleep(30)"],
                "cwd": str(agentd.WORKSPACE),
                "timeoutSeconds": 1,
            })
            close, _, _ = client.collect(stream_id)

        self.assertEqual("error", close["status"])
        self.assertEqual("timeout", close["error"]["code"])

    def test_cancel_kills_the_whole_process_group(self) -> None:
        marker = agentd.WORKSPACE / "child-was-here"
        # The grandchild inherits the process group, which is what cancel must reach.
        grandchild = f"import time; time.sleep(30); open({str(marker)!r}, 'w').close()"
        script = (
            "import subprocess, sys, time\n"
            f"subprocess.Popen([sys.executable, '-c', {grandchild!r}])\n"
            "sys.stdout.write('spawned'); sys.stdout.flush()\n"
            "time.sleep(30)\n"
        )
        with connected() as client:
            stream_id = client.open({
                "kind": "exec", "command": [sys.executable, "-c", script],
                "cwd": str(agentd.WORKSPACE),
            })
            self.assertEqual(b"spawned", client.receive()[3])

            started = time.monotonic()
            client.send_json(agentd.FRAME_CANCEL, stream_id, {})
            close, _, _ = client.collect(stream_id)
            elapsed = time.monotonic() - started

        self.assertEqual("cancelled", close["status"])
        self.assertLess(elapsed, 5.0)
        time.sleep(0.2)
        self.assertFalse(marker.exists(), "the grandchild outlived its process group")


class MultiplexingTests(WorkspaceTestCase):
    def test_a_short_call_completes_while_a_long_command_is_running(self) -> None:
        with connected() as client:
            slow = client.open({
                "kind": "exec",
                "command": [sys.executable, "-c", "import time; time.sleep(2)"],
                "cwd": str(agentd.WORKSPACE),
            })
            quick = client.open({"kind": "call", "method": "health", "params": {}})

            body = bytearray()
            while True:
                frame_type, _, stream_id, payload = client.receive()
                self.assertNotEqual(slow, stream_id, "the slow command must not have finished first")
                if frame_type == agentd.FRAME_DATA:
                    body.extend(payload)
                elif frame_type == agentd.FRAME_CLOSE:
                    self.assertEqual(quick, stream_id)
                    self.assertEqual("ok", json.loads(payload)["status"])
                    break

            self.assertTrue(json.loads(body)["ready"])
            client.send_json(agentd.FRAME_CANCEL, slow, {})
            client.collect(slow)

    def test_concurrent_streams_are_capped(self) -> None:
        with connected() as client:
            opened = [
                client.open({
                    "kind": "exec",
                    "command": [sys.executable, "-c", "import time; time.sleep(5)"],
                    "cwd": str(agentd.WORKSPACE),
                })
                for _ in range(agentd.MAX_CONCURRENT_STREAMS)
            ]
            overflow = client.open({"kind": "call", "method": "health", "params": {}})

            close = None
            while close is None:
                frame_type, _, stream_id, payload = client.receive()
                if frame_type == agentd.FRAME_CLOSE and stream_id == overflow:
                    close = json.loads(payload)

            self.assertEqual("error", close["status"])
            self.assertEqual("too_many_streams", close["error"]["code"])

            for stream_id in opened:
                client.send_json(agentd.FRAME_CANCEL, stream_id, {})


class FlowControlTests(WorkspaceTestCase):
    def test_a_chatty_command_stalls_at_exactly_one_window(self) -> None:
        total = 400 * 1024
        with connected() as client:
            stream_id = client.open({
                "kind": "exec",
                "command": [sys.executable, "-c",
                            f"import sys; sys.stdout.write('x' * {total})"],
                "cwd": str(agentd.WORKSPACE),
            })

            received = 0
            while received < agentd.INITIAL_WINDOW_BYTES:
                frame_type, _, _, payload = client.receive()
                self.assertEqual(agentd.FRAME_DATA, frame_type)
                received += len(payload)

            self.assertEqual(agentd.INITIAL_WINDOW_BYTES, received)
            # Without credit nothing more may arrive, however much the child has
            # written: the guest stops reading its pipe and the child blocks.
            with self.assertRaises(socket.timeout):
                client.receive(timeout=0.75)

            client.grant(stream_id, total)
            close, stdout, _ = client.collect(stream_id, auto_grant=False)

        self.assertEqual("ok", close["status"])
        self.assertEqual(0, close["exitCode"])
        self.assertEqual(total, received + len(stdout))

    def test_the_guest_returns_stdin_credit_as_it_consumes(self) -> None:
        payload = b"z" * (agentd.INITIAL_WINDOW_BYTES // 2 + 1024)
        target = agentd.WORKSPACE / "streamed.bin"

        with connected() as client:
            stream_id = client.open({
                "kind": "call", "method": "write_file", "params": {"path": str(target)},
            })
            for offset in range(0, len(payload), agentd.MAX_FRAME_PAYLOAD):
                client.send(agentd.FRAME_DATA, stream_id, agentd.CHANNEL_STDIN,
                            payload[offset : offset + agentd.MAX_FRAME_PAYLOAD])

            credit = 0
            while credit == 0:
                frame_type, _, frame_stream, frame_payload = client.receive()
                if frame_type == agentd.FRAME_WINDOW:
                    self.assertEqual(stream_id, frame_stream)
                    credit = struct.unpack(">I", frame_payload)[0]

            client.send(agentd.FRAME_END, stream_id, agentd.CHANNEL_STDIN)
            close, body, _ = client.collect(stream_id)

        self.assertGreaterEqual(credit, agentd.INITIAL_WINDOW_BYTES // 2)
        self.assertEqual("ok", close["status"])
        self.assertEqual(len(payload), json.loads(body)["bytesWritten"])
        self.assertEqual(payload, target.read_bytes())


class PtyTests(WorkspaceTestCase):
    def test_a_pty_echoes_input_the_way_a_terminal_does(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "pty", "command": ["/bin/cat"], "cwd": str(agentd.WORKSPACE),
            })
            client.send(agentd.FRAME_DATA, stream_id, agentd.CHANNEL_STDIN, b"hello\n")

            seen = bytearray()
            while b"hello" not in seen:
                frame_type, channel, _, payload = client.receive()
                self.assertEqual(agentd.FRAME_DATA, frame_type)
                # A terminal merges everything onto one channel, and so do we.
                self.assertEqual(agentd.CHANNEL_STDOUT, channel)
                seen.extend(payload)
                client.grant(stream_id, len(payload))

            client.send_json(agentd.FRAME_CANCEL, stream_id, {})
            close, _, _ = client.collect(stream_id)

        self.assertEqual("cancelled", close["status"])

    def test_resize_reaches_the_child_as_a_real_window_size(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "pty",
                "command": ["/bin/sh", "-c", "sleep 0.4; stty size"],
                "cwd": str(agentd.WORKSPACE),
                "columns": 80,
                "rows": 24,
            })
            client.send_json(agentd.FRAME_CTRL, stream_id, {
                "op": "resize", "columns": 120, "rows": 40,
            })
            close, stdout, _ = client.collect(stream_id)

        self.assertEqual("ok", close["status"])
        self.assertIn("40 120", stdout.decode(errors="replace"))

    def test_the_child_gets_a_controlling_terminal(self) -> None:
        with connected() as client:
            stream_id = client.open({
                "kind": "pty",
                "command": [sys.executable, "-c",
                            "import os,sys; print(os.isatty(0), os.getsid(0) == os.getpid())"],
                "cwd": str(agentd.WORKSPACE),
            })
            close, stdout, _ = client.collect(stream_id)

        self.assertEqual("ok", close["status"])
        self.assertEqual(0, close["exitCode"])
        self.assertIn("True True", stdout.decode(errors="replace"))


if __name__ == "__main__":
    unittest.main()

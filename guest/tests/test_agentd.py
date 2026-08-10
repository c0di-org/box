from __future__ import annotations

import base64
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest


AGENTD_PATH = Path(__file__).parents[1] / "agentd" / "agentd.py"
SPEC = importlib.util.spec_from_file_location("box_agentd", AGENTD_PATH)
assert SPEC is not None and SPEC.loader is not None
agentd = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = agentd
SPEC.loader.exec_module(agentd)


class Duplex:
    """Small text stream that feeds requests and records protocol responses."""

    def __init__(self, *lines: str) -> None:
        self._lines = iter(lines)
        self.output = io.StringIO()

    def __iter__(self):
        return self

    def __next__(self) -> str:
        return next(self._lines)

    def write(self, value: str) -> int:
        return self.output.write(value)

    def flush(self) -> None:
        return None


class AgentdTests(unittest.TestCase):
    def setUp(self) -> None:
        self._old_workspace = agentd.WORKSPACE
        self._temporary_directory = tempfile.TemporaryDirectory()
        agentd.WORKSPACE = Path(self._temporary_directory.name).resolve()

    def tearDown(self) -> None:
        agentd.WORKSPACE = self._old_workspace
        self._temporary_directory.cleanup()

    def test_health_reports_protocol_and_workspace(self) -> None:
        result = agentd.handle("health", {})

        self.assertTrue(result["ready"])
        self.assertEqual(agentd.PROTOCOL_VERSION, result["protocol"])
        self.assertEqual(str(agentd.WORKSPACE), result["workspace"])

    def test_exec_uses_requested_workspace_directory(self) -> None:
        project = agentd.WORKSPACE / "project"
        project.mkdir()

        result = agentd.handle(
            "exec",
            {
                "command": [sys.executable, "-c", "from pathlib import Path; print(Path.cwd().name)"],
                "cwd": str(project),
            },
        )

        self.assertEqual(0, result["exitCode"])
        self.assertEqual("project\n", result["stdout"])
        self.assertEqual("", result["stderr"])

    def test_exec_rejects_a_directory_outside_workspace(self) -> None:
        with self.assertRaisesRegex(ValueError, "inside /workspace"):
            agentd.handle("exec", {"command": ["true"], "cwd": "/tmp"})

    def test_file_round_trip_and_sorted_listing(self) -> None:
        content = b"hello from Box\n"
        second = agentd.WORKSPACE / "a-directory"
        second.mkdir()

        write_result = agentd.handle(
            "write_file",
            {
                "path": str(agentd.WORKSPACE / "notes.txt"),
                "dataBase64": base64.b64encode(content).decode("ascii"),
            },
        )
        read_result = agentd.handle(
            "read_file",
            {"path": str(agentd.WORKSPACE / "notes.txt")},
        )
        listing = agentd.handle("list_files", {"path": str(agentd.WORKSPACE)})

        self.assertEqual(len(content), write_result["bytesWritten"])
        self.assertEqual(content, base64.b64decode(read_result["dataBase64"]))
        self.assertEqual(["a-directory", "notes.txt"], [item["name"] for item in listing["items"]])
        self.assertTrue(listing["items"][0]["directory"])
        self.assertFalse(listing["items"][1]["directory"])

    def test_paths_cannot_escape_through_a_symlink(self) -> None:
        outside = Path(self._temporary_directory.name).parent / "box-agentd-outside"
        link = agentd.WORKSPACE / "outside"
        link.symlink_to(outside)

        with self.assertRaisesRegex(ValueError, "inside /workspace"):
            agentd.handle("read_file", {"path": str(link / "secret")})

    def test_protocol_errors_are_structured_and_keep_the_request_id(self) -> None:
        stream = Duplex(
            json.dumps(
                {
                    "version": agentd.PROTOCOL_VERSION,
                    "id": "request-7",
                    "method": "not_a_method",
                    "params": {},
                },
            )
            + "\n",
        )

        agentd.serve(stream)
        response = json.loads(stream.output.getvalue())

        self.assertEqual("request-7", response["id"])
        self.assertEqual(agentd.PROTOCOL_VERSION, response["version"])
        self.assertEqual("invalid_request", response["error"]["code"])
        self.assertIn("unsupported", response["error"]["message"])


if __name__ == "__main__":
    unittest.main()

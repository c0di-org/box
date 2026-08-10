#!/usr/bin/env python3
"""Private virtio-serial control service for Local Agent Workstation.

This deliberately exposes no Android API and starts as the unprivileged `agent`
user. Privileged package installation will be a separately audited method.
"""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any

PROTOCOL_VERSION = 1
WORKSPACE = Path("/workspace").resolve()
MAX_FILE_BYTES = 8 * 1024 * 1024


def response(request_id: str, result: Any = None, error: dict[str, str] | None = None) -> None:
    payload: dict[str, Any] = {"version": PROTOCOL_VERSION, "id": request_id}
    if error is not None:
        payload["error"] = error
    else:
        payload["result"] = result
    print(json.dumps(payload, separators=(",", ":")), flush=True)


def resolve_path(value: str) -> Path:
    path = Path(value).resolve()
    if path != WORKSPACE and WORKSPACE not in path.parents:
        raise ValueError("paths must be inside /workspace")
    return path


def handle(method: str, params: dict[str, Any]) -> Any:
    if method == "health":
        return {"ready": True, "workspace": str(WORKSPACE), "protocol": PROTOCOL_VERSION}

    if method == "exec":
        command = params.get("command")
        if not isinstance(command, list) or not command or not all(isinstance(x, str) for x in command):
            raise ValueError("command must be a non-empty string array")
        cwd = resolve_path(params.get("cwd", "/workspace"))
        timeout = min(max(int(params.get("timeoutSeconds", 120)), 1), 900)
        completed = subprocess.run(
            command, cwd=cwd, env={**os.environ, "HOME": "/home/agent"},
            capture_output=True, text=True, timeout=timeout, check=False,
        )
        return {"exitCode": completed.returncode, "stdout": completed.stdout, "stderr": completed.stderr}

    if method == "read_file":
        data = resolve_path(params["path"]).read_bytes()
        if len(data) > MAX_FILE_BYTES:
            raise ValueError("file exceeds response limit")
        return {"dataBase64": base64.b64encode(data).decode("ascii")}

    if method == "write_file":
        path = resolve_path(params["path"])
        data = base64.b64decode(params["dataBase64"], validate=True)
        if len(data) > MAX_FILE_BYTES:
            raise ValueError("file exceeds request limit")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return {"bytesWritten": len(data)}

    if method == "list_files":
        path = resolve_path(params.get("path", "/workspace"))
        return [{"name": entry.name, "path": str(entry), "directory": entry.is_dir(), "size": entry.stat().st_size} for entry in sorted(path.iterdir())]

    raise ValueError("unsupported method")


def main() -> int:
    WORKSPACE.mkdir(parents=True, exist_ok=True)
    for line in sys.stdin:
        request_id = ""
        try:
            request = json.loads(line)
            request_id = str(request.get("id", ""))
            if request.get("version") != PROTOCOL_VERSION or not request_id:
                raise ValueError("version and id are required")
            result = handle(request["method"], request.get("params", {}))
            response(request_id, result=result)
        except subprocess.TimeoutExpired:
            response(request_id, error={"code": "timeout", "message": "command exceeded its time limit"})
        except (KeyError, TypeError, ValueError, OSError, json.JSONDecodeError) as exc:
            response(request_id, error={"code": "invalid_request", "message": str(exc)})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Persistent JSONL connection to the Datapack Sandbox JVM."""

from __future__ import annotations

import json
import os
import queue
import re
import subprocess
import sys
import threading
from collections import deque
from dataclasses import dataclass
from importlib import resources
from pathlib import Path
from typing import Any


class DpsSessionError(RuntimeError):
    """Structured failure returned by the sandbox or its process wrapper."""

    def __init__(self, code: str, message: str, details: dict[str, Any] | None = None):
        super().__init__(message)
        self.code = code
        self.details = details or {}


@dataclass(frozen=True)
class SessionHello:
    protocol: str
    default_version: str
    versions: tuple[str, ...]
    capabilities: dict[str, Any]


class DpsSession:
    """Own one standalone CLI serve process for one notebook kernel."""

    def __init__(self, jar: Path | None = None, java: str | None = None, cwd: Path | None = None):
        self.jar = (jar or discover_cli_jar()).resolve()
        self.java = java or os.environ.get("DPS_JAVA") or java_from_home() or "java"
        self.cwd = (cwd or Path.cwd()).resolve()
        self._process: subprocess.Popen[str] | None = None
        self._start_lock = threading.Lock()
        self._write_lock = threading.Lock()
        self._pending_lock = threading.Lock()
        self._pending: dict[str, queue.Queue[dict[str, Any] | DpsSessionError]] = {}
        self._counter = 0
        self._stderr: deque[str] = deque(maxlen=200)
        self._interrupt_requested = threading.Event()
        self.hello: SessionHello | None = None
        self._java_checked = False

    @property
    def running(self) -> bool:
        return self._process is not None and self._process.poll() is None

    def start(self) -> SessionHello:
        with self._start_lock:
            if self.running:
                assert self.hello is not None
                return self.hello
            if not self.jar.is_file():
                raise DpsSessionError("CLI_JAR_MISSING", f"Datapack Sandbox CLI JAR does not exist: {self.jar}")
            self._check_java()
            try:
                creation_flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
                self._process = subprocess.Popen(
                    [self.java, "-jar", str(self.jar), "serve"],
                    cwd=self.cwd,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    bufsize=1,
                    creationflags=creation_flags,
                )
            except OSError as error:
                raise DpsSessionError("JAVA_START_FAILED", f"Could not start Java executable '{self.java}': {error}") from error
            assert self._process.stderr is not None
            threading.Thread(target=self._drain_stderr, name="dps-kernel-stderr", daemon=True).start()
            message = self._read_message()
            if not message.get("ok"):
                self.close()
                raise DpsSessionError("PROTOCOL_ERROR", f"Serve process did not return a successful hello: {message}")
            result = message.get("result") or {}
            protocol = result.get("protocol")
            if protocol != "dps-jsonl":
                self.close()
                raise DpsSessionError("PROTOCOL_ERROR", f"Unsupported serve protocol: {protocol!r}")
            self.hello = SessionHello(
                protocol=protocol,
                default_version=str(result.get("defaultVersion", "")),
                versions=tuple(str(value) for value in result.get("versions", [])),
                capabilities=dict(result.get("capabilities") or {}),
            )
            threading.Thread(target=self._read_responses, name="dps-kernel-responses", daemon=True).start()
            return self.hello

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        if method == "interrupt":
            self._interrupt_requested.set()
        self.start()
        process = self._require_process()
        response_queue: queue.Queue[dict[str, Any] | DpsSessionError] = queue.Queue(maxsize=1)
        with self._pending_lock:
            self._counter += 1
            request_id = f"jupyter-{self._counter}"
            self._pending[request_id] = response_queue
        payload = {"id": request_id, "method": method, "params": params or {}}
        with self._write_lock:
            assert process.stdin is not None
            try:
                process.stdin.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n")
                process.stdin.flush()
            except (BrokenPipeError, OSError) as error:
                with self._pending_lock:
                    self._pending.pop(request_id, None)
                raise self._process_failure("SERVE_WRITE_FAILED", "Could not write to Datapack Sandbox serve process", error)
        try:
            response = response_queue.get(timeout=300)
        except queue.Empty as error:
            with self._pending_lock:
                self._pending.pop(request_id, None)
            raise DpsSessionError("SERVE_TIMEOUT", f"Timed out waiting for serve method {method!r}") from error
        if isinstance(response, DpsSessionError):
            raise response
        if not response.get("ok"):
            failure = dict(response.get("error") or {})
            raise DpsSessionError(
                str(failure.get("code", "SANDBOX_ERROR")),
                str(failure.get("message", "Sandbox request failed")),
                failure,
            )
        result = response.get("result")
        return result if isinstance(result, dict) else {"value": result}

    def close(self) -> None:
        process = self._process
        self._process = None
        self.hello = None
        self._interrupt_requested.clear()
        if process is None:
            return
        if process.stdin is not None:
            try:
                process.stdin.close()
            except OSError:
                pass
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)
        if process.stdout is not None:
            process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()

    def stderr_tail(self) -> list[str]:
        return list(self._stderr)

    def consume_interrupt(self) -> bool:
        requested = self._interrupt_requested.is_set()
        if requested:
            self._interrupt_requested.clear()
        return requested

    def clear_interrupt(self) -> None:
        self._interrupt_requested.clear()

    def _read_message(self) -> dict[str, Any]:
        process = self._require_process()
        assert process.stdout is not None
        line = process.stdout.readline()
        if not line:
            raise self._process_failure("SERVE_EXITED", "Datapack Sandbox serve process exited before responding")
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise DpsSessionError("PROTOCOL_ERROR", f"Serve emitted invalid JSON: {line.rstrip()}") from error
        if not isinstance(value, dict):
            raise DpsSessionError("PROTOCOL_ERROR", "Serve response must be a JSON object")
        return value

    def _require_process(self) -> subprocess.Popen[str]:
        process = self._process
        if process is None:
            raise DpsSessionError("SERVE_NOT_RUNNING", "Datapack Sandbox serve process is not running")
        return process

    def _drain_stderr(self) -> None:
        process = self._process
        if process is None or process.stderr is None:
            return
        for line in process.stderr:
            self._stderr.append(line.rstrip())

    def _read_responses(self) -> None:
        process = self._process
        if process is None or process.stdout is None:
            return
        try:
            for line in process.stdout:
                try:
                    message = json.loads(line)
                except json.JSONDecodeError as error:
                    self._fail_pending(DpsSessionError("PROTOCOL_ERROR", f"Serve emitted invalid JSON: {line.rstrip()}"))
                    return
                request_id = message.get("id") if isinstance(message, dict) else None
                with self._pending_lock:
                    target = self._pending.pop(str(request_id), None)
                if target is not None:
                    target.put(message)
        finally:
            if self._pending:
                self._fail_pending(self._process_failure("SERVE_EXITED", "Datapack Sandbox serve process exited"))

    def _fail_pending(self, error: DpsSessionError) -> None:
        with self._pending_lock:
            queues = list(self._pending.values())
            self._pending.clear()
        for target in queues:
            target.put(error)

    def _process_failure(self, code: str, message: str, cause: Exception | None = None) -> DpsSessionError:
        process = self._process
        exit_code = process.poll() if process is not None else None
        suffix = f"; exitCode={exit_code}; stderr={' | '.join(self.stderr_tail()[-10:]) or '<empty>'}"
        error = DpsSessionError(code, message + suffix, {"exitCode": exit_code, "stderr": self.stderr_tail()})
        if cause is not None:
            error.__cause__ = cause
        return error

    def _check_java(self) -> None:
        if self._java_checked:
            return
        try:
            result = subprocess.run(
                [self.java, "-version"],
                cwd=self.cwd,
                capture_output=True,
                text=True,
                encoding="utf-8",
                timeout=10,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise DpsSessionError("JAVA_START_FAILED", f"Could not inspect Java executable '{self.java}': {error}") from error
        output = (result.stderr or result.stdout).strip()
        match = re.search(r'version\s+"([^"]+)"', output)
        raw = match.group(1) if match else ""
        major_text = raw.split(".", 2)[1] if raw.startswith("1.") else raw.split(".", 1)[0]
        major = int(major_text) if major_text.isdigit() else 0
        if result.returncode != 0 or major < 25:
            raise DpsSessionError(
                "JAVA_VERSION_MISMATCH",
                f"Datapack Sandbox requires Java 25 or newer; '{self.java}' reported {raw or output or '<unknown>'}",
                {"java": self.java, "version": raw, "output": output},
            )
        self._java_checked = True


def discover_cli_jar() -> Path:
    configured = os.environ.get("DPS_CLI_JAR")
    if configured:
        return Path(configured).expanduser()

    try:
        packaged = resources.files("datapack_sandbox_kernel").joinpath("resources/datapack-sandbox-cli.jar")
        if packaged.is_file():
            return Path(str(packaged))
    except (ModuleNotFoundError, TypeError):
        pass

    source_checkout = Path(__file__).resolve().parents[3]
    candidate = source_checkout / "cli" / "build" / "libs" / "datapack-sandbox-cli.jar"
    if candidate.is_file():
        return candidate
    raise DpsSessionError(
        "CLI_JAR_MISSING",
        "Could not locate datapack-sandbox-cli.jar. Set DPS_CLI_JAR or build ./gradlew :cli:fatJar.",
    )


def java_from_home() -> str | None:
    home = os.environ.get("JAVA_HOME")
    if not home:
        return None
    executable = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    return str(executable) if executable.is_file() else None

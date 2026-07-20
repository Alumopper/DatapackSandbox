"""Jupyter Kernel implementation for native mcfunction cells."""

from __future__ import annotations

import asyncio
import html
import json
import traceback
from typing import Any

from ipykernel.kernelbase import Kernel

from .runtime import CellOutcome, NotebookRuntime
from .session import DpsSessionError


class DatapackSandboxKernel(Kernel):
    implementation = "datapack-sandbox"
    implementation_version = "1.0.1"
    language = "mcfunction"
    language_version = "26.2"
    language_info = {
        "name": "mcfunction",
        "mimetype": "text/x-mcfunction",
        "file_extension": ".mcfunction",
        "codemirror_mode": "text/plain",
        "pygments_lexer": "text",
    }
    banner = "Datapack Sandbox — persistent Minecraft mcfunction runtime"
    help_links = [
        {"text": "Datapack Sandbox", "url": "https://github.com/Alumopper/DatapackSandbox"},
    ]

    def __init__(self, **kwargs: Any):
        super().__init__(**kwargs)
        self.runtime = NotebookRuntime()

    async def do_execute(
        self,
        code: str,
        silent: bool,
        store_history: bool = True,
        user_expressions: dict[str, Any] | None = None,
        allow_stdin: bool = False,
        *,
        cell_meta: dict[str, Any] | None = None,
        cell_id: str | None = None,
    ) -> dict[str, Any]:
        del store_history, user_expressions, allow_stdin, cell_meta
        try:
            outcome = await asyncio.to_thread(self.runtime.execute_cell, code, self.execution_count, cell_id)
            if not silent:
                self._publish(outcome)
            return {
                "status": "ok",
                "execution_count": self.execution_count,
                "payload": [],
                "user_expressions": {},
            }
        except (KeyboardInterrupt, asyncio.CancelledError):
            # Some Linux clients still deliver SIGINT even when the kernelspec
            # requests message-mode interrupts. Convert that signal into the
            # same cooperative JVM cancellation used on Windows.
            try:
                self.runtime.session.request("interrupt", {})
            except DpsSessionError:
                pass
            return self._publish_error(
                "EXECUTION_INTERRUPTED",
                "Sandbox execution interrupted at a command boundary",
                {},
                silent,
            )
        except DpsSessionError as error:
            return self._publish_error(error.code, str(error), error.details, silent)
        except Exception as error:  # Defensive protocol boundary.
            return self._publish_error(type(error).__name__, str(error), {"traceback": traceback.format_exc()}, silent)

    async def do_complete(self, code: str, cursor_pos: int) -> dict[str, Any]:
        try:
            result = await asyncio.to_thread(self.runtime.complete, code, cursor_pos)
            return {
                "status": "ok",
                "matches": result.get("matches", []),
                "cursor_start": result.get("cursor_start", cursor_pos),
                "cursor_end": result.get("cursor_end", cursor_pos),
                "metadata": result.get("metadata", {}),
            }
        except DpsSessionError:
            return {"status": "ok", "matches": [], "cursor_start": cursor_pos, "cursor_end": cursor_pos, "metadata": {}}

    async def do_inspect(
        self,
        code: str,
        cursor_pos: int,
        detail_level: int = 0,
        omit_sections: tuple[str, ...] = (),
    ) -> dict[str, Any]:
        del detail_level, omit_sections
        try:
            inspected = await asyncio.to_thread(self.runtime.inspect, code, cursor_pos)
            return {
                "status": "ok",
                "found": inspected["found"],
                "data": {"text/plain": inspected["text"], "application/json": inspected["data"]},
                "metadata": {},
            }
        except DpsSessionError as error:
            return {
                "status": "ok",
                "found": True,
                "data": {"text/plain": f"{error.code}: {error}"},
                "metadata": {},
            }

    def do_is_complete(self, code: str) -> dict[str, str]:
        if has_unclosed_structure(code):
            return {"status": "incomplete", "indent": ""}
        return {"status": "complete"}

    def do_shutdown(self, restart: bool) -> dict[str, Any]:
        self.runtime.close()
        return {"status": "ok", "restart": restart}

    async def do_interrupt(self) -> dict[str, Any]:
        if not self.runtime.session.running:
            return {"status": "ok"}
        try:
            await asyncio.to_thread(self.runtime.session.request, "interrupt", {})
            return {"status": "ok"}
        except DpsSessionError as error:
            return {"status": "error", "ename": error.code, "evalue": str(error), "traceback": []}

    async def interrupt_request(self, stream: Any, ident: Any, parent: dict[str, Any]) -> None:
        """Route message-mode interrupts to cooperative JVM cancellation on every OS."""
        content = await self.do_interrupt()
        if self.session is not None:
            self.session.send(stream, "interrupt_reply", content, parent, ident=ident)

    def _publish(self, outcome: CellOutcome) -> None:
        for stream in outcome.streams:
            self.send_response(self.iopub_socket, "stream", {"name": "stdout", "text": stream + "\n"})
        bundle: dict[str, Any] = {
            "text/plain": outcome.summary,
            "text/markdown": summary_markdown(outcome),
            "text/html": outcome.html or summary_html(outcome),
        }
        if outcome.image_png:
            bundle["image/png"] = outcome.image_png
        self.send_response(
            self.iopub_socket,
            "display_data",
            {"data": bundle, "metadata": {"datapack-sandbox": outcome.data}},
        )
        self.send_response(
            self.iopub_socket,
            "execute_result",
            {
                "execution_count": self.execution_count,
                "data": {"text/plain": outcome.summary, "text/markdown": summary_markdown(outcome)},
                "metadata": {"datapack-sandbox": outcome.data},
            },
        )

    def _publish_error(
        self,
        name: str,
        value: str,
        details: dict[str, Any],
        silent: bool,
    ) -> dict[str, Any]:
        location = details.get("location") if isinstance(details, dict) else None
        lines = [f"{name}: {value}"]
        if isinstance(location, dict):
            lines.append(
                f"at {location.get('file', '<notebook>')}:{location.get('line', '?')} "
                f"{location.get('command', '')}".rstrip()
            )
        partial = details.get("partial") if isinstance(details, dict) else None
        if isinstance(partial, dict):
            lines.append(
                f"partial execution: commands={partial.get('commandsCompleted', 0)} "
                f"snapshotDiffs={len(partial.get('snapshotDiffs', []))}"
            )
        if details.get("traceback"):
            lines.extend(str(details["traceback"]).splitlines())
        content = {"ename": name, "evalue": value, "traceback": lines}
        if not silent:
            self.send_response(self.iopub_socket, "error", content)
        return {"status": "error", "execution_count": self.execution_count, **content}


def summary_html(outcome: CellOutcome) -> str:
    state = outcome.data.get("state") if isinstance(outcome.data, dict) else None
    details = ""
    if isinstance(state, dict):
        details = (
            f"<dl><dt>Game time</dt><dd>{state.get('gameTime', 0)}</dd>"
            f"<dt>Entities</dt><dd>{state.get('entities', 0)}</dd>"
            f"<dt>Players</dt><dd>{state.get('players', 0)}</dd></dl>"
        )
    raw = html.escape(json.dumps(outcome.data, ensure_ascii=False, indent=2, default=str))
    return (
        '<section class="dps-result">'
        f'<strong>{html.escape(outcome.summary)}</strong>{details}'
        f'<details><summary>Raw result</summary><pre>{raw}</pre></details>'
        '</section>'
    )


def summary_markdown(outcome: CellOutcome) -> str:
    state = outcome.data.get("state") if isinstance(outcome.data, dict) else None
    lines = [f"**{outcome.summary}**"]
    if isinstance(state, dict):
        lines.extend(
            [
                "",
                f"- Game time: `{state.get('gameTime', 0)}`",
                f"- Entities: `{state.get('entities', 0)}`",
                f"- Players: `{state.get('players', 0)}`",
            ]
        )
    render = outcome.data.get("render") if isinstance(outcome.data, dict) else None
    if isinstance(render, dict):
        lines.extend(["", f"- Render: `{render.get('width', '?')}×{render.get('height', '?')}`"])
    return "\n".join(lines)


def has_unclosed_structure(code: str) -> bool:
    braces = 0
    brackets = 0
    quoted = False
    escaped = False
    for character in code:
        if escaped:
            escaped = False
            continue
        if character == "\\" and quoted:
            escaped = True
            continue
        if character == '"':
            quoted = not quoted
            continue
        if quoted:
            continue
        if character == "{":
            braces += 1
        elif character == "}":
            braces -= 1
        elif character == "[":
            brackets += 1
        elif character == "]":
            brackets -= 1
    return quoted or braces > 0 or brackets > 0 or code.rstrip().endswith("\\")

"""Notebook-oriented state and `%dps` command handling."""

from __future__ import annotations

import base64
import json
import os
import shlex
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .session import DpsSession, DpsSessionError


@dataclass
class KernelConfig:
    version: str = "26.2"
    packs: list[Path] = field(default_factory=list)
    minecraft_assets: Path | None = None
    resource_packs: list[Path] = field(default_factory=list)
    default_player: str | None = "Steve"
    camera_player: str | None = "Steve"
    auto_render: bool = True
    strict: bool = False
    render_width: int = 960
    render_height: int = 540
    field_of_view: float = 70.0
    render_distance: float = 64.0


@dataclass
class CellOutcome:
    summary: str
    data: dict[str, Any]
    streams: list[str] = field(default_factory=list)
    image_png: str | None = None
    html: str | None = None


class NotebookRuntime:
    """Translate notebook cells into persistent serve requests."""

    def __init__(self, session: DpsSession | None = None, cwd: Path | None = None):
        self.cwd = (cwd or Path.cwd()).resolve()
        self.session = session or DpsSession(cwd=self.cwd)
        self.config = load_kernel_config(self.cwd)
        self._opened = False
        self._pending_rebuild = False
        self._last_state: dict[str, Any] = {}
        self._world_revision = 0
        self._render_cache: tuple[tuple[Any, ...], str, dict[str, Any]] | None = None

    def close(self) -> None:
        self.session.close()
        self._opened = False
        self._render_cache = None

    def execute_cell(self, code: str, execution_count: int, cell_id: str | None = None) -> CellOutcome:
        directives: list[str] = []
        mcf_lines: list[str] = []
        for line in code.splitlines():
            if line.lstrip().startswith("%dps ") or line.strip() == "%dps":
                directives.append(line.strip()[4:].strip())
                mcf_lines.append("")
            else:
                mcf_lines.append(line)

        outcomes = [self.execute_directive(command) for command in directives]
        mcf = "\n".join(mcf_lines)
        if mcf.strip():
            outcomes.append(self.execute_mcf(mcf, execution_count, cell_id))
        if not outcomes:
            return CellOutcome("No commands", {"commands": 0, "snapshotDiffs": []})
        return merge_outcomes(outcomes)

    def execute_mcf(self, code: str, execution_count: int, cell_id: str | None = None) -> CellOutcome:
        if self._pending_rebuild:
            raise DpsSessionError(
                "RESET_REQUIRED",
                "Version or datapack configuration changed. Run `%dps reset --apply` before executing MCF.",
            )
        self._ensure_open()
        function_id = f"notebook:cell_{execution_count}"
        try:
            self.session.request(
                "upsertFunctionSource",
                {
                    "id": function_id,
                    "text": code,
                    "sourceName": f"<notebook:{cell_id or execution_count}>",
                },
            )
            if self.session.consume_interrupt():
                raise DpsSessionError(
                    "EXECUTION_INTERRUPTED",
                    "Sandbox execution interrupted before the function request began",
                )
            result = self.session.request("runFunction", {"id": function_id})
            self.session.clear_interrupt()
        except DpsSessionError as error:
            self.session.clear_interrupt()
            # An interrupted or failed request may have completed earlier command
            # boundaries, so a cached frame can no longer be trusted.
            self._world_revision += 1
            self._render_cache = None
            if error.code in SESSION_LOSS_CODES:
                self._opened = False
                self._pending_rebuild = True
                raise DpsSessionError(
                    "SESSION_LOST",
                    "The JVM serve session was lost; the previous world cannot be recovered. "
                    "Run `%dps reset --apply` to create a new world.",
                    error.details,
                ) from error
            raise
        self._last_state = dict(result.get("state") or {})
        self._track_world_change(result)
        streams = [str(output.get("text", "")) for output in result.get("outputs", []) if output.get("text")]
        image, render_metadata = self._render() if self.config.auto_render else (None, None)
        data = {
            "commands": int(result.get("commands", 0)),
            "state": self._last_state,
            "snapshotDiffs": result.get("snapshotDiffs", []),
            "outputs": result.get("outputs", []),
        }
        if render_metadata is not None:
            data["render"] = render_metadata
        state = self._last_state
        summary = (
            f"OK commands={data['commands']} gameTime={state.get('gameTime', 0)} "
            f"entities={state.get('entities', 0)}"
        )
        return CellOutcome(summary, data, streams=streams, image_png=image)

    def execute_directive(self, command: str) -> CellOutcome:
        args = shlex.split(command, posix=True)
        if not args or args[0] == "help":
            return CellOutcome(DPS_HELP, {"help": DPS_HELP})
        name = args[0].lower()
        values = args[1:]
        if name == "version":
            require_arity(name, values, 1)
            self.config.version = values[0]
            return self._configuration_changed(f"Version configured: {values[0]}")
        if name == "pack":
            require_arity(name, values, 1)
            path = self._existing_path(values[0], "Datapack")
            if path not in self.config.packs:
                self.config.packs.append(path)
            return self._configuration_changed(f"Datapack configured: {path}")
        if name == "packs":
            packs = [str(path) for path in self.config.packs]
            return CellOutcome("\n".join(packs) if packs else "No datapacks configured", {"packs": packs})
        if name == "assets":
            require_arity(name, values, 1)
            self.config.minecraft_assets = self._existing_path(values[0], "Minecraft assets")
            return CellOutcome(f"Minecraft assets configured: {self.config.minecraft_assets}", self.status_data())
        if name == "resource-pack":
            require_arity(name, values, 1)
            path = self._existing_path(values[0], "Resource pack")
            if path not in self.config.resource_packs:
                self.config.resource_packs.append(path)
            return CellOutcome(f"Resource pack configured: {path}", self.status_data())
        if name == "camera":
            require_arity(name, values, 1)
            self.config.camera_player = values[0]
            return CellOutcome(f"Render camera configured: player {values[0]}", self.status_data())
        if name == "world":
            require_arity(name, values, 1)
            self._ensure_ready()
            path = self._existing_path(values[0], "World fixture")
            result = self.session.request("applyWorldFixture", {"path": str(path)})
            return self._tracked_outcome("World fixture applied", result)
        if name == "tick":
            require_arity(name, values, 1)
            self._ensure_ready()
            result = self.session.request("tick", {"count": int(values[0])})
            return self._tracked_outcome(f"Advanced {values[0]} ticks", result)
        if name == "function":
            require_arity(name, values, 1)
            self._ensure_ready()
            result = self.session.request("runFunction", {"id": values[0]})
            return self._tracked_outcome(f"Function completed: {values[0]}", result)
        if name == "render":
            if len(values) > 1:
                raise DpsSessionError("INPUT_FORMAT", "%dps render accepts at most one output path")
            self._ensure_ready()
            image, metadata = self._render()
            if image is None:
                raise DpsSessionError("RENDER_ERROR", "Renderer returned no PNG")
            if values:
                output = self._resolve(values[0])
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(base64.b64decode(image))
                summary = f"Screenshot written: {output}"
            else:
                summary = "Screenshot rendered"
            return CellOutcome(summary, {"render": metadata}, image_png=image)
        if name == "snapshot":
            self._ensure_ready()
            snapshot = self.session.request("snapshot")
            return CellOutcome(json.dumps(snapshot, indent=2, ensure_ascii=False), {"snapshot": snapshot})
        if name == "outputs":
            self._ensure_ready()
            result = self.session.request("outputs")
            streams = [str(output.get("text", "")) for output in result.get("outputs", []) if output.get("text")]
            return CellOutcome(f"Outputs: {len(result.get('outputs', []))}", result, streams=streams)
        if name == "reset":
            if values not in ([], ["--apply"]):
                raise DpsSessionError("INPUT_FORMAT", "%dps reset only accepts --apply")
            self._open(force=True)
            self._pending_rebuild = False
            return CellOutcome("Sandbox reset", self.status_data())
        if name == "status":
            if self._opened:
                self._last_state = self.session.request("state")
            return CellOutcome(self.status_text(), self.status_data())
        if name == "config":
            if len(values) != 2 or values[0] != "autoRender":
                raise DpsSessionError("INPUT_FORMAT", "%dps config usage: %dps config autoRender <true|false>")
            self.config.auto_render = parse_boolean(values[1])
            return CellOutcome(f"autoRender={str(self.config.auto_render).lower()}", self.status_data())
        raise DpsSessionError("INPUT_FORMAT", f"Unknown %dps command '{name}'. Run `%dps help`.")

    def complete(self, code: str, cursor_pos: int) -> dict[str, Any]:
        line_start = code.rfind("\n", 0, cursor_pos) + 1
        line = code[line_start:cursor_pos]
        if line.lstrip().startswith("%dps"):
            prefix = line.strip().split()[-1] if line.strip().split() else ""
            matches = [command for command in DPS_COMMANDS if command.startswith(prefix)]
            return {"matches": matches, "cursor_start": cursor_pos - len(prefix), "cursor_end": cursor_pos}
        self._ensure_ready()
        result = self.session.request("completions", {"buffer": line, "cursor": len(line)})
        suggestions = result.get("suggestions", [])
        start = min((int(item.get("start", len(line))) for item in suggestions), default=len(line))
        return {
            "matches": [str(item.get("value", "")) for item in suggestions],
            "cursor_start": line_start + start,
            "cursor_end": cursor_pos,
            "metadata": {"suggestions": suggestions},
        }

    def inspect(self, code: str, cursor_pos: int) -> dict[str, Any]:
        line_start = code.rfind("\n", 0, cursor_pos) + 1
        line_end = code.find("\n", cursor_pos)
        line = code[line_start : len(code) if line_end < 0 else line_end].strip()
        if line.startswith("%dps"):
            return {"found": True, "text": DPS_HELP, "data": {"command": line, "kind": "dps-control"}}
        if not line or line.startswith("#"):
            return {"found": False, "text": "", "data": {}}
        self._ensure_ready()
        checked = self.session.request("checkCommand", {"command": line})
        root = line.split(maxsplit=1)[0]
        text = (
            f"Minecraft command root: {root}\n"
            f"Profile: {self.config.version}\n"
            f"Valid: {str(bool(checked.get('valid'))).lower()}\n"
            f"{checked.get('message', '')}"
        )
        return {"found": True, "text": text, "data": checked}

    def status_data(self) -> dict[str, Any]:
        return {
            "version": self.config.version,
            "packs": [str(path) for path in self.config.packs],
            "minecraftAssets": str(self.config.minecraft_assets) if self.config.minecraft_assets else None,
            "resourcePacks": [str(path) for path in self.config.resource_packs],
            "defaultPlayer": self.config.default_player,
            "cameraPlayer": self.config.camera_player,
            "autoRender": self.config.auto_render,
            "strict": self.config.strict,
            "pendingReset": self._pending_rebuild,
            "state": self._last_state,
        }

    def status_text(self) -> str:
        state = self._last_state
        return (
            f"version={self.config.version} packs={len(self.config.packs)} "
            f"opened={str(self._opened).lower()} pendingReset={str(self._pending_rebuild).lower()} "
            f"gameTime={state.get('gameTime', 0)} entities={state.get('entities', 0)}"
        )

    def _configuration_changed(self, message: str) -> CellOutcome:
        if self._opened:
            self._pending_rebuild = True
            message += "; run `%dps reset --apply` to rebuild the sandbox"
        return CellOutcome(message, self.status_data())

    def _tracked_outcome(self, summary: str, result: dict[str, Any]) -> CellOutcome:
        self._last_state = dict(result.get("state") or {})
        self._track_world_change(result)
        streams = [str(output.get("text", "")) for output in result.get("outputs", []) if output.get("text")]
        image, metadata = self._render() if self.config.auto_render else (None, None)
        data = dict(result)
        if metadata is not None:
            data["render"] = metadata
        return CellOutcome(summary, data, streams=streams, image_png=image)

    def _render(self) -> tuple[str | None, dict[str, Any] | None]:
        params: dict[str, Any] = {
            "width": self.config.render_width,
            "height": self.config.render_height,
            "fieldOfView": self.config.field_of_view,
            "renderDistance": self.config.render_distance,
            "strictAssets": self.config.strict,
        }
        if self.config.minecraft_assets:
            params["minecraftAssets"] = str(self.config.minecraft_assets)
        if self.config.resource_packs:
            params["resourcePacks"] = [str(path) for path in self.config.resource_packs]
        if self.config.camera_player:
            if looks_like_uuid(self.config.camera_player):
                params["cameraEntity"] = self.config.camera_player
            else:
                params["cameraPlayer"] = self.config.camera_player
        cache_key = self._render_cache_key()
        if self._render_cache is not None and self._render_cache[0] == cache_key:
            metadata = dict(self._render_cache[2])
            metadata["reused"] = True
            return self._render_cache[1], metadata
        result = self.session.request("render", params)
        image = result.get("data")
        metadata = dict(result.get("metadata") or {})
        metadata["reused"] = False
        if isinstance(image, str):
            self._render_cache = (cache_key, image, dict(metadata))
        return image, metadata

    def _track_world_change(self, result: dict[str, Any]) -> None:
        if result.get("snapshotDiffs"):
            self._world_revision += 1

    def _render_cache_key(self) -> tuple[Any, ...]:
        return (
            self._world_revision,
            self.config.render_width,
            self.config.render_height,
            self.config.field_of_view,
            self.config.render_distance,
            self.config.strict,
            self.config.camera_player,
            path_signature(self.config.minecraft_assets),
            tuple(path_signature(path) for path in self.config.resource_packs),
        )

    def _ensure_open(self) -> None:
        if not self._opened:
            self._open(force=True)

    def _ensure_ready(self) -> None:
        if self._pending_rebuild:
            raise DpsSessionError("RESET_REQUIRED", "Run `%dps reset --apply` before this operation")
        self._ensure_open()

    def _open(self, force: bool) -> None:
        if self._opened and not force:
            return
        params: dict[str, Any] = {
            "version": self.config.version,
            "packs": [str(path) for path in self.config.packs],
            "unsupported": "error" if self.config.strict else "warn",
            "defaultPlayerName": self.config.default_player,
        }
        self._last_state = self.session.request("createSandbox", params)
        self._opened = True
        self._world_revision += 1
        self._render_cache = None

    def _existing_path(self, raw: str, label: str) -> Path:
        path = self._resolve(raw)
        if not path.exists():
            raise DpsSessionError("INPUT_FORMAT", f"{label} path does not exist: {path}")
        return path

    def _resolve(self, raw: str) -> Path:
        path = Path(raw).expanduser()
        return (path if path.is_absolute() else self.cwd / path).resolve()


def merge_outcomes(outcomes: list[CellOutcome]) -> CellOutcome:
    if len(outcomes) == 1:
        return outcomes[0]
    return CellOutcome(
        summary="\n".join(outcome.summary for outcome in outcomes),
        data={"results": [outcome.data for outcome in outcomes]},
        streams=[stream for outcome in outcomes for stream in outcome.streams],
        image_png=next((outcome.image_png for outcome in reversed(outcomes) if outcome.image_png), None),
    )


def require_arity(command: str, values: list[str], count: int) -> None:
    if len(values) != count:
        raise DpsSessionError("INPUT_FORMAT", f"%dps {command} expects {count} argument(s)")


def parse_boolean(raw: str) -> bool:
    if raw.lower() == "true":
        return True
    if raw.lower() == "false":
        return False
    raise DpsSessionError("INPUT_FORMAT", f"Expected true or false, got {raw!r}")


def looks_like_uuid(raw: str) -> bool:
    compact = raw.replace("-", "")
    return len(compact) == 32 and all(character in "0123456789abcdefABCDEF" for character in compact)


def path_signature(path: Path | None) -> tuple[str, int, int] | None:
    if path is None:
        return None
    try:
        stat = path.stat()
        return str(path), stat.st_mtime_ns, stat.st_size
    except OSError:
        return str(path), -1, -1


def load_kernel_config(cwd: Path) -> KernelConfig:
    config = KernelConfig()
    user = user_config_path()
    if user.is_file():
        apply_config_file(config, user)

    if os.environ.get("DPS_VERSION"):
        config.version = os.environ["DPS_VERSION"]
    if os.environ.get("DPS_PACKS"):
        config.packs = [Path(value).expanduser().resolve() for value in os.environ["DPS_PACKS"].split(os.pathsep) if value]
    if os.environ.get("DPS_MINECRAFT_ASSETS"):
        config.minecraft_assets = Path(os.environ["DPS_MINECRAFT_ASSETS"]).expanduser().resolve()
    if os.environ.get("DPS_RESOURCE_PACKS"):
        config.resource_packs = [
            Path(value).expanduser().resolve() for value in os.environ["DPS_RESOURCE_PACKS"].split(os.pathsep) if value
        ]
    if os.environ.get("DPS_DEFAULT_PLAYER"):
        config.default_player = os.environ["DPS_DEFAULT_PLAYER"]
    if os.environ.get("DPS_CAMERA_PLAYER"):
        config.camera_player = os.environ["DPS_CAMERA_PLAYER"]
    if os.environ.get("DPS_AUTO_RENDER"):
        config.auto_render = parse_boolean(os.environ["DPS_AUTO_RENDER"])
    if os.environ.get("DPS_STRICT"):
        config.strict = parse_boolean(os.environ["DPS_STRICT"])
    project = cwd / ".dps-kernel.json"
    if project.is_file():
        apply_config_file(config, project)
    if config.minecraft_assets is None:
        config.minecraft_assets = discover_minecraft_assets(config.version)
    return config


def apply_config_file(config: KernelConfig, path: Path) -> None:
    try:
        values = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DpsSessionError("INPUT_FORMAT", f"Invalid Kernel config {path}: {error}") from error
    if not isinstance(values, dict):
        raise DpsSessionError("INPUT_FORMAT", f"Kernel config must be a JSON object: {path}")
    base = path.parent

    def resolve(raw: str) -> Path:
        candidate = Path(raw).expanduser()
        return (candidate if candidate.is_absolute() else base / candidate).resolve()

    if "version" in values:
        config.version = str(values["version"])
    if "packs" in values:
        config.packs = [resolve(str(value)) for value in require_list(values["packs"], "packs", path)]
    if values.get("minecraftAssets"):
        config.minecraft_assets = resolve(str(values["minecraftAssets"]))
    if "resourcePacks" in values:
        config.resource_packs = [resolve(str(value)) for value in require_list(values["resourcePacks"], "resourcePacks", path)]
    if "defaultPlayer" in values:
        config.default_player = None if values["defaultPlayer"] is None else str(values["defaultPlayer"])
    if "cameraPlayer" in values:
        config.camera_player = None if values["cameraPlayer"] is None else str(values["cameraPlayer"])
    if "autoRender" in values:
        config.auto_render = bool(values["autoRender"])
    if "strict" in values:
        config.strict = bool(values["strict"])
    render = values.get("render")
    if isinstance(render, dict):
        config.render_width = int(render.get("width", config.render_width))
        config.render_height = int(render.get("height", config.render_height))
        config.field_of_view = float(render.get("fov", config.field_of_view))
        config.render_distance = float(render.get("renderDistance", config.render_distance))


def require_list(value: Any, name: str, path: Path) -> list[Any]:
    if not isinstance(value, list):
        raise DpsSessionError("INPUT_FORMAT", f"Kernel config {path}: {name} must be an array")
    return value


def user_config_path() -> Path:
    configured = os.environ.get("DPS_KERNEL_CONFIG")
    if configured:
        return Path(configured).expanduser().resolve()
    if os.name == "nt" and os.environ.get("APPDATA"):
        return Path(os.environ["APPDATA"]) / "DatapackSandbox" / "kernel.json"
    return Path.home() / ".config" / "datapack-sandbox" / "kernel.json"


def discover_minecraft_assets(version: str) -> Path | None:
    roots = []
    if os.name == "nt" and os.environ.get("APPDATA"):
        roots.append(Path(os.environ["APPDATA"]) / ".minecraft")
    roots.append(Path.home() / ".minecraft")
    for root in roots:
        jar = root / "versions" / version / f"{version}.jar"
        if jar.is_file():
            return jar.resolve()
    return None


SESSION_LOSS_CODES = {
    "MISSING_CONTEXT",
    "PROTOCOL_ERROR",
    "SERVE_EXITED",
    "SERVE_NOT_RUNNING",
    "SERVE_TIMEOUT",
    "SERVE_WRITE_FAILED",
}


DPS_COMMANDS = [
    "assets",
    "camera",
    "config",
    "function",
    "help",
    "outputs",
    "pack",
    "packs",
    "render",
    "reset",
    "resource-pack",
    "snapshot",
    "status",
    "tick",
    "version",
    "world",
]

DPS_HELP = """Datapack Sandbox notebook commands:
%dps version <id>             Configure a Minecraft profile
%dps pack <path>              Add a datapack
%dps assets <path>            Configure a client JAR or assets directory
%dps resource-pack <path>     Add a rendering resource pack
%dps world <fixture.json>     Apply a world fixture
%dps camera <player>          Select a player camera
%dps tick <count>             Advance sandbox ticks
%dps function <id>            Run a loaded function
%dps render [output.png]      Render and optionally save the current state
%dps snapshot                 Display the complete snapshot
%dps outputs                  Display accumulated output events
%dps reset --apply            Rebuild the configured sandbox
%dps status                   Display kernel and sandbox status
%dps config autoRender <bool> Toggle automatic PNG output"""

"""Notebook-oriented state and `%dps` command handling."""

from __future__ import annotations

import base64
import json
import os
import shlex
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, NoReturn

from .session import DpsSession, DpsSessionError


@dataclass
class KernelConfig:
    version: str = "26.2"
    packs: list[Path] = field(default_factory=list)
    minecraft_assets: Path | None = None
    resource_packs: list[Path] = field(default_factory=list)
    player_skins: dict[str, Path] = field(default_factory=dict)
    default_player: str | None = "Steve"
    camera_player: str | None = "Steve"
    camera_entity: str | None = None
    camera_position: tuple[float, float, float] | None = None
    camera_yaw: float = 0.0
    camera_pitch: float = 0.0
    camera_dimension: str = "minecraft:overworld"
    auto_render: bool = True
    strict: bool = False
    render_width: int = 960
    render_height: int = 540
    field_of_view: float = 70.0
    render_distance: float = 128.0
    transparent_background: bool = False
    show_hud: bool = False
    show_debug_overlay: bool = False


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
            self._raise_session_error(error)
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
        if name == "skin":
            require_arity(name, values, 2)
            path = self._existing_path(values[1], "Player skin")
            self.config.player_skins[values[0]] = path
            self._render_cache = None
            return CellOutcome(f"Player skin configured: {values[0]} -> {path}", self.status_data())
        if name == "camera":
            return self._configure_camera(values)
        if name == "world":
            require_arity(name, values, 1)
            self._ensure_ready()
            path = self._existing_path(values[0], "World fixture")
            result = self._tracked_request("applyWorldFixture", {"path": str(path)})
            return self._tracked_outcome("World fixture applied", result)
        if name == "tick":
            require_arity(name, values, 1)
            self._ensure_ready()
            result = self._tracked_request("tick", {"count": int(values[0])})
            return self._tracked_outcome(f"Advanced {values[0]} ticks", result)
        if name == "function":
            require_arity(name, values, 1)
            self._ensure_ready()
            result = self._tracked_request("runFunction", {"id": values[0]})
            return self._tracked_outcome(f"Function completed: {values[0]}", result)
        if name == "load":
            require_arity(name, values, 0)
            self._ensure_ready()
            return self._tracked_outcome("Load functions completed", self._tracked_request("load"))
        if name == "event":
            if not values:
                raise DpsSessionError("INPUT_FORMAT", "%dps event expects Serve event text, for example: player Steve killed_entity minecraft:zombie")
            self._ensure_ready()
            return self._tracked_outcome("Player event injected", self._tracked_request("injectPlayerEvent", {"event": " ".join(values)}))
        if name == "reload":
            if values not in ([], ["--discard-world"]):
                raise DpsSessionError("INPUT_FORMAT", "%dps reload only accepts --discard-world")
            self._ensure_ready()
            self._last_state = self.session.request("reload", {"keepWorld": values != ["--discard-world"]})
            self._world_revision += 1
            self._render_cache = None
            return CellOutcome("Datapacks reloaded", self.status_data())
        if name == "reset-world":
            require_arity(name, values, 0)
            self._ensure_ready()
            self._last_state = self.session.request("resetWorld")
            self._world_revision += 1
            self._render_cache = None
            return CellOutcome("World reset", self.status_data())
        if name == "checkpoint":
            return self._checkpoint(values)
        if name == "coverage":
            self._ensure_ready()
            result = self.session.request("coverage", parse_coverage_options(values))
            summary = (
                f"Coverage lines={float(result.get('linePercentage', 0.0)):.2f}% "
                f"functions={float(result.get('functionPercentage', 0.0)):.2f}% "
                f"passed={str(bool(result.get('passed', False))).lower()}"
            )
            return CellOutcome(summary, {"coverage": result})
        if name == "reset-coverage":
            require_arity(name, values, 0)
            self._ensure_ready()
            result = self.session.request("resetCoverage")
            return CellOutcome("Coverage counters reset", {"coverage": result})
        if name in {"resources", "traces", "event-traces"}:
            require_arity(name, values, 0)
            self._ensure_ready()
            method, key = {
                "resources": ("resources", "resources"),
                "traces": ("traces", "traces"),
                "event-traces": ("eventTraces", "eventTraces"),
            }[name]
            result = self.session.request(method)
            count = len(result.get(key, [])) if isinstance(result.get(key), list) else 0
            return CellOutcome(f"{name}: {count}", {name: result})
        if name == "function-source":
            require_arity(name, values, 1)
            self._ensure_ready()
            result = self.session.request("functionSource", {"id": values[0]})
            return CellOutcome(str(result.get("source", "")), {"functionSource": result})
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
            if len(values) != 2 or values[0] not in RENDER_BOOLEAN_OPTIONS:
                names = "|".join(RENDER_BOOLEAN_OPTIONS)
                raise DpsSessionError("INPUT_FORMAT", f"%dps config usage: %dps config <{names}> <true|false>")
            attribute = RENDER_BOOLEAN_OPTIONS[values[0]]
            configured = parse_boolean(values[1])
            setattr(self.config, attribute, configured)
            self._render_cache = None
            return CellOutcome(f"{values[0]}={str(configured).lower()}", self.status_data())
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
            "playerSkins": {name: str(path) for name, path in self.config.player_skins.items()},
            "defaultPlayer": self.config.default_player,
            "cameraPlayer": self.config.camera_player,
            "cameraEntity": self.config.camera_entity,
            "cameraPosition": self.config.camera_position,
            "autoRender": self.config.auto_render,
            "strict": self.config.strict,
            "transparentBackground": self.config.transparent_background,
            "showHud": self.config.show_hud,
            "showDebugOverlay": self.config.show_debug_overlay,
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

    def _tracked_request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        try:
            return self.session.request(method, params)
        except DpsSessionError as error:
            self._raise_session_error(error)

    def _raise_session_error(self, error: DpsSessionError) -> NoReturn:
        # Serve failures can retain command-boundary changes as a partial result.
        self._world_revision += 1
        self._render_cache = None
        partial = error.details.get("partial") if isinstance(error.details, dict) else None
        if isinstance(partial, dict) and isinstance(partial.get("state"), dict):
            self._last_state = dict(partial["state"])
        if error.code in SESSION_LOSS_CODES:
            self._opened = False
            self._pending_rebuild = True
            raise DpsSessionError(
                "SESSION_LOST",
                "The JVM serve session was lost; the previous world cannot be recovered. "
                "Run `%dps reset --apply` to create a new world.",
                error.details,
            ) from error
        raise error

    def _checkpoint(self, values: list[str]) -> CellOutcome:
        if not values or values == ["list"]:
            self._ensure_ready()
            result = self.session.request("checkpoints")
            names = [str(name) for name in result.get("names", [])]
            return CellOutcome("\n".join(names) if names else "No checkpoints", {"checkpoints": names})
        action = values[0]
        if action not in {"save", "restore", "delete"} or len(values) > 2:
            raise DpsSessionError("INPUT_FORMAT", "%dps checkpoint usage: checkpoint [list|save|restore|delete] [name]")
        self._ensure_ready()
        name = values[1] if len(values) == 2 else "default"
        method = {"save": "saveCheckpoint", "restore": "restoreCheckpoint", "delete": "deleteCheckpoint"}[action]
        result = self.session.request(method, {"name": name})
        if action == "restore":
            self._last_state = dict(result.get("state") or {})
            self._world_revision += 1
            self._render_cache = None
        return CellOutcome(f"Checkpoint {action}: {name}", {"checkpoint": result})

    def _configure_camera(self, values: list[str]) -> CellOutcome:
        if not values:
            raise DpsSessionError("INPUT_FORMAT", "%dps camera expects auto, player, entity, fixed, or a player name")
        mode = values[0].lower()
        self.config.camera_player = None
        self.config.camera_entity = None
        self.config.camera_position = None
        if mode == "auto" and len(values) == 1:
            summary = "Render camera configured: auto"
        elif mode == "player" and len(values) == 2:
            self.config.camera_player = values[1]
            summary = f"Render camera configured: player {values[1]}"
        elif mode == "entity" and len(values) == 2:
            self.config.camera_entity = values[1]
            summary = f"Render camera configured: entity {values[1]}"
        elif mode == "fixed" and 4 <= len(values) <= 7:
            try:
                self.config.camera_position = (float(values[1]), float(values[2]), float(values[3]))
                self.config.camera_yaw = float(values[4]) if len(values) >= 5 else 0.0
                self.config.camera_pitch = float(values[5]) if len(values) >= 6 else 0.0
            except ValueError as error:
                raise DpsSessionError("INPUT_FORMAT", "%dps camera fixed coordinates, yaw, and pitch must be numbers") from error
            self.config.camera_dimension = values[6] if len(values) == 7 else "minecraft:overworld"
            summary = f"Render camera configured: fixed {self.config.camera_position}"
        elif len(values) == 1:
            if looks_like_uuid(values[0]):
                self.config.camera_entity = values[0]
                summary = f"Render camera configured: entity {values[0]}"
            else:
                self.config.camera_player = values[0]
                summary = f"Render camera configured: player {values[0]}"
        else:
            raise DpsSessionError("INPUT_FORMAT", "%dps camera expects auto, player <name>, entity <uuid>, or fixed <x> <y> <z> [yaw pitch dimension]")
        self._render_cache = None
        return CellOutcome(summary, self.status_data())

    def _render(self) -> tuple[str | None, dict[str, Any] | None]:
        params: dict[str, Any] = {
            "width": self.config.render_width,
            "height": self.config.render_height,
            "fieldOfView": self.config.field_of_view,
            "renderDistance": self.config.render_distance,
            "strictAssets": self.config.strict,
            "transparentBackground": self.config.transparent_background,
            "showHud": self.config.show_hud,
            "showDebugOverlay": self.config.show_debug_overlay,
        }
        if self.config.minecraft_assets:
            params["minecraftAssets"] = str(self.config.minecraft_assets)
        if self.config.resource_packs:
            params["resourcePacks"] = [str(path) for path in self.config.resource_packs]
        if self.config.player_skins:
            params["playerSkins"] = {name: str(path) for name, path in self.config.player_skins.items()}
        if self.config.camera_player:
            params["cameraPlayer"] = self.config.camera_player
        elif self.config.camera_entity:
            params["cameraEntity"] = self.config.camera_entity
        elif self.config.camera_position:
            params.update(
                position=list(self.config.camera_position),
                yaw=self.config.camera_yaw,
                pitch=self.config.camera_pitch,
                dimension=self.config.camera_dimension,
            )
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
            self.config.camera_entity,
            self.config.camera_position,
            self.config.camera_yaw,
            self.config.camera_pitch,
            self.config.camera_dimension,
            self.config.transparent_background,
            self.config.show_hud,
            self.config.show_debug_overlay,
            path_signature(self.config.minecraft_assets),
            tuple(path_signature(path) for path in self.config.resource_packs),
            tuple((name, path_signature(path)) for name, path in sorted(self.config.player_skins.items())),
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


def parse_coverage_options(values: list[str]) -> dict[str, Any]:
    options: dict[str, Any] = {}
    repeated: dict[str, list[str]] = {"include": [], "exclude": []}
    names = {
        "--minimum-line": "minimumLine",
        "--minimum-function": "minimumFunction",
        "--include": "include",
        "--exclude": "exclude",
    }
    index = 0
    while index < len(values):
        flag = values[index]
        name = names.get(flag)
        if name is None or index + 1 >= len(values):
            raise DpsSessionError(
                "INPUT_FORMAT",
                "%dps coverage accepts --minimum-line <percent>, --minimum-function <percent>, --include <glob>, and --exclude <glob>",
            )
        raw = values[index + 1]
        if name in repeated:
            repeated[name].append(raw)
        else:
            try:
                options[name] = float(raw)
            except ValueError as error:
                raise DpsSessionError("INPUT_FORMAT", f"{flag} expects a numeric percentage") from error
        index += 2
    options.update({name: entries for name, entries in repeated.items() if entries})
    return options


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
    if "minecraftAssets" in values:
        config.minecraft_assets = resolve(str(values["minecraftAssets"])) if values["minecraftAssets"] else None
    if "resourcePacks" in values:
        config.resource_packs = [resolve(str(value)) for value in require_list(values["resourcePacks"], "resourcePacks", path)]
    if "playerSkins" in values:
        skins = values["playerSkins"]
        if not isinstance(skins, dict) or not all(isinstance(name, str) and isinstance(value, str) for name, value in skins.items()):
            raise DpsSessionError("INPUT_FORMAT", f"Kernel config {path}: playerSkins must map player names to path strings")
        config.player_skins = {name: resolve(value) for name, value in skins.items()}
    if "defaultPlayer" in values:
        config.default_player = None if values["defaultPlayer"] is None else str(values["defaultPlayer"])
    if "cameraPlayer" in values:
        config.camera_player = None if values["cameraPlayer"] is None else str(values["cameraPlayer"])
    if "cameraEntity" in values:
        config.camera_entity = None if values["cameraEntity"] is None else str(values["cameraEntity"])
        if config.camera_entity is not None:
            config.camera_player = None
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
        config.transparent_background = bool(render.get("transparentBackground", config.transparent_background))
        config.show_hud = bool(render.get("showHud", config.show_hud))
        config.show_debug_overlay = bool(render.get("showDebugOverlay", config.show_debug_overlay))
        position = render.get("position")
        if position is not None:
            if not isinstance(position, list) or len(position) != 3:
                raise DpsSessionError("INPUT_FORMAT", f"Kernel config {path}: render.position must contain three numbers")
            try:
                config.camera_position = tuple(float(value) for value in position)  # type: ignore[assignment]
            except (TypeError, ValueError) as error:
                raise DpsSessionError("INPUT_FORMAT", f"Kernel config {path}: render.position must contain three numbers") from error
            config.camera_player = None
            config.camera_entity = None
            config.camera_yaw = float(render.get("yaw", 0.0))
            config.camera_pitch = float(render.get("pitch", 0.0))
            config.camera_dimension = str(render.get("dimension", "minecraft:overworld"))


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


SESSION_LOSS_CODES = {
    "PROTOCOL_ERROR",
    "SERVE_EXITED",
    "SERVE_NOT_RUNNING",
    "SERVE_TIMEOUT",
    "SERVE_WRITE_FAILED",
}


DPS_COMMANDS = [
    "assets",
    "camera",
    "checkpoint",
    "config",
    "coverage",
    "event",
    "event-traces",
    "function",
    "function-source",
    "help",
    "load",
    "outputs",
    "pack",
    "packs",
    "render",
    "reload",
    "reset",
    "reset-coverage",
    "reset-world",
    "resource-pack",
    "resources",
    "skin",
    "snapshot",
    "status",
    "tick",
    "traces",
    "version",
    "world",
]

DPS_HELP = """Datapack Sandbox notebook commands:
%dps version <id>             Configure a Minecraft profile
%dps pack <path>              Add a datapack
%dps assets <path>            Configure a client JAR or assets directory
%dps resource-pack <path>     Add a rendering resource pack
%dps skin <player> <path>     Configure a local player skin
%dps world <fixture.json>     Apply a world fixture
%dps camera <mode...>         Select auto/player/entity/fixed camera
%dps tick <count>             Advance sandbox ticks
%dps function <id>            Run a loaded function
%dps load                     Run datapack load functions
%dps event <event text>       Inject a player event
%dps checkpoint <action>      List, save, restore, or delete checkpoints
%dps coverage [options]       Display accumulated line/function coverage
%dps reset-coverage           Clear accumulated coverage counters
%dps render [output.png]      Render and optionally save the current state
%dps snapshot                 Display the complete snapshot
%dps outputs                  Display accumulated output events
%dps traces / event-traces    Display command or player-event traces
%dps resources                Display the effective resource index
%dps function-source <id>     Display effective loaded function source
%dps reload [--discard-world] Reload datapacks, optionally with a new world
%dps reset-world              Reset only the modeled world
%dps reset --apply            Rebuild the configured sandbox
%dps status                   Display kernel and sandbox status
%dps config <option> <bool>   Toggle automatic rendering or render overlays"""


RENDER_BOOLEAN_OPTIONS = {
    "autoRender": "auto_render",
    "transparentBackground": "transparent_background",
    "showHud": "show_hud",
    "showDebugOverlay": "show_debug_overlay",
}

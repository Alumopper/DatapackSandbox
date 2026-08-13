from __future__ import annotations

import base64
import os
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch

from datapack_sandbox_kernel.kernel import has_unclosed_structure
from datapack_sandbox_kernel.runtime import NotebookRuntime
from datapack_sandbox_kernel.session import DpsSessionError


class FakeSession:
    def __init__(self) -> None:
        self.requests: list[tuple[str, dict[str, Any]]] = []
        self.closed = False
        self.interrupted = False

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        values = params or {}
        self.requests.append((method, values))
        if method in {"createSandbox", "state"}:
            return {"version": values.get("version", "26.2"), "gameTime": 0, "entities": 1, "players": 1}
        if method == "runFunction":
            return {
                "commands": 2,
                "outputs": [{"text": "cell output"}],
                "snapshotDiffs": [{"path": "blocks[0]", "kind": "added"}],
                "state": {"version": "26.2", "gameTime": 0, "entities": 1, "players": 1},
            }
        if method == "render":
            return {
                "data": base64.b64encode(b"png").decode("ascii"),
                "metadata": {"visualParity": False, "lightingModel": "approximate"},
            }
        if method == "completions":
            return {"suggestions": [{"value": "scoreboard", "start": 0, "end": 3}]}
        if method == "coverage":
            return {"coveredLines": 1, "totalLines": 2, "linePercentage": 50.0, "functionPercentage": 100.0, "passed": True, "failures": []}
        if method == "checkpoints":
            return {"names": ["before"]}
        if method in {"saveCheckpoint", "restoreCheckpoint", "deleteCheckpoint"}:
            return {"names": [values.get("name", "default")], "name": values.get("name", "default"), "state": {"version": "26.2", "gameTime": 4}}
        if method == "resources":
            return {"resources": [{"type": "function", "id": "demo:main"}]}
        if method == "traces":
            return {"traces": [{"command": "say hi"}], "from": 0, "total": 1}
        if method == "eventTraces":
            return {"eventTraces": [{"type": "killed_entity"}], "from": 0, "total": 1}
        if method == "functionSource":
            return {"id": values["id"], "source": "say source"}
        return {"commands": 0, "outputs": [], "snapshotDiffs": [], "state": {}}

    def close(self) -> None:
        self.closed = True

    def consume_interrupt(self) -> bool:
        requested = self.interrupted
        self.interrupted = False
        return requested

    def clear_interrupt(self) -> None:
        self.interrupted = False


class UnchangedSession(FakeSession):
    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        result = super().request(method, params)
        if method == "runFunction":
            result["snapshotDiffs"] = []
        return result


class CrashSession(FakeSession):
    fail_upsert = False

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        if method == "upsertFunctionSource" and self.fail_upsert:
            self.requests.append((method, params or {}))
            raise DpsSessionError("SERVE_EXITED", "simulated JVM exit", {"exitCode": 9})
        return super().request(method, params)


class InterruptBetweenRequestsSession(FakeSession):
    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        result = super().request(method, params)
        if method == "upsertFunctionSource":
            self.interrupted = True
        return result


class PartialFailureSession(FakeSession):
    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        if method == "runFunction":
            raise DpsSessionError(
                "EXECUTION_INTERRUPTED",
                "interrupted",
                {"partial": {"state": {"version": "26.2", "gameTime": 9, "entities": 2}, "snapshotDiffs": [{"path": "gameTime"}]}},
            )
        return super().request(method, params)


class MissingContextSession(FakeSession):
    fail = True

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        if method == "runFunction" and self.fail:
            self.fail = False
            raise DpsSessionError("MISSING_CONTEXT", "missing player")
        return super().request(method, params)


class NotebookRuntimeTest(unittest.TestCase):
    def test_mcfunction_cells_share_one_open_session_and_render(self) -> None:
        session = FakeSession()
        runtime = NotebookRuntime(session=session)  # type: ignore[arg-type]

        first = runtime.execute_cell("scoreboard objectives add points dummy", 1, "first")
        second = runtime.execute_cell("scoreboard players add Steve points 1", 2, "second")

        methods = [method for method, _ in session.requests]
        self.assertEqual(1, methods.count("createSandbox"))
        self.assertEqual(2, methods.count("upsertFunctionSource"))
        self.assertEqual(2, methods.count("runFunction"))
        self.assertEqual(2, methods.count("render"))
        self.assertIn("commands=2", first.summary)
        self.assertEqual("cell output", second.streams[0])
        self.assertIsNotNone(second.image_png)

    def test_profile_change_requires_explicit_reset_after_world_opened(self) -> None:
        session = FakeSession()
        runtime = NotebookRuntime(session=session)  # type: ignore[arg-type]
        runtime.execute_cell("say ready", 1)

        changed = runtime.execute_cell("%dps version 1.21.5", 2)

        self.assertIn("reset --apply", changed.summary)
        with self.assertRaises(DpsSessionError) as failure:
            runtime.execute_cell("say blocked", 3)
        self.assertEqual("RESET_REQUIRED", failure.exception.code)
        runtime.execute_cell("%dps reset --apply", 4)
        runtime.execute_cell("say resumed", 5)

    def test_lost_jvm_requires_explicit_reset_and_does_not_fake_world_recovery(self) -> None:
        session = CrashSession()
        runtime = NotebookRuntime(session=session)  # type: ignore[arg-type]
        runtime.execute_cell("say opened", 1)
        session.fail_upsert = True

        with self.assertRaises(DpsSessionError) as failure:
            runtime.execute_cell("say lost", 2)

        self.assertEqual("SESSION_LOST", failure.exception.code)
        self.assertIn("reset --apply", str(failure.exception))
        self.assertTrue(runtime.status_data()["pendingReset"])
        session.fail_upsert = False
        runtime.execute_cell("%dps reset --apply", 3)
        runtime.execute_cell("say new-world", 4)
        self.assertEqual(2, [method for method, _ in session.requests].count("createSandbox"))

    def test_interrupt_between_upsert_and_run_cancels_current_cell(self) -> None:
        session = InterruptBetweenRequestsSession()
        runtime = NotebookRuntime(session=session)  # type: ignore[arg-type]

        with self.assertRaises(DpsSessionError) as failure:
            runtime.execute_cell("say must-not-run", 1)

        self.assertEqual("EXECUTION_INTERRUPTED", failure.exception.code)
        self.assertNotIn("runFunction", [method for method, _ in session.requests])

    def test_render_directive_can_write_png(self) -> None:
        session = FakeSession()
        with tempfile.TemporaryDirectory() as temporary:
            runtime = NotebookRuntime(session=session, cwd=Path(temporary))  # type: ignore[arg-type]
            outcome = runtime.execute_cell("%dps render state.png", 1)
            self.assertEqual(b"png", (Path(temporary) / "state.png").read_bytes())
            self.assertIn("Screenshot written", outcome.summary)

    def test_unchanged_world_reuses_frame_and_marks_metadata(self) -> None:
        session = UnchangedSession()
        runtime = NotebookRuntime(session=session)  # type: ignore[arg-type]

        first = runtime.execute_cell("say first", 1)
        second = runtime.execute_cell("say second", 2)

        self.assertEqual(1, [method for method, _ in session.requests].count("render"))
        self.assertFalse(first.data["render"]["reused"])
        self.assertTrue(second.data["render"]["reused"])
        self.assertEqual(first.image_png, second.image_png)

    def test_uuid_camera_uses_entity_camera_request(self) -> None:
        session = FakeSession()
        runtime = NotebookRuntime(session=session)  # type: ignore[arg-type]
        uuid = "01234567-89ab-cdef-0123-456789abcdef"

        runtime.execute_cell(f"%dps camera {uuid}\nsetblock 0 0 0 stone", 1)

        render_params = next(params for method, params in session.requests if method == "render")
        self.assertEqual(uuid, render_params["cameraEntity"])
        self.assertNotIn("cameraPlayer", render_params)

    def test_fixed_camera_and_render_assets_follow_serve_options(self) -> None:
        session = FakeSession()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            skin = root / "steve.png"
            skin.write_bytes(b"skin")
            runtime = NotebookRuntime(session=session, cwd=root)  # type: ignore[arg-type]
            runtime.execute_cell(
                "%dps skin Steve steve.png\n%dps camera fixed 1 2 3 90 -15 minecraft:the_nether\n"
                "%dps config transparentBackground true\nsetblock 0 0 0 stone",
                1,
            )

        render_params = next(params for method, params in session.requests if method == "render")
        self.assertEqual([1.0, 2.0, 3.0], render_params["position"])
        self.assertEqual(90.0, render_params["yaw"])
        self.assertEqual("minecraft:the_nether", render_params["dimension"])
        self.assertTrue(render_params["transparentBackground"])
        self.assertEqual(str(skin.resolve()), render_params["playerSkins"]["Steve"])

    def test_new_serve_controls_are_available_as_magics(self) -> None:
        session = FakeSession()
        runtime = NotebookRuntime(session=session)  # type: ignore[arg-type]

        coverage = runtime.execute_cell("%dps coverage --minimum-line 40 --include demo:*", 1)
        checkpoints = runtime.execute_cell("%dps checkpoint list", 2)
        runtime.execute_cell("%dps checkpoint restore before", 3)
        runtime.execute_cell("%dps event player Steve killed_entity minecraft:zombie", 4)
        resources = runtime.execute_cell("%dps resources", 5)

        self.assertIn("lines=50.00%", coverage.summary)
        self.assertIn("before", checkpoints.summary)
        self.assertEqual("player Steve killed_entity minecraft:zombie", next(params["event"] for method, params in session.requests if method == "injectPlayerEvent"))
        self.assertIn("resources: 1", resources.summary)
        coverage_params = next(params for method, params in session.requests if method == "coverage")
        self.assertEqual(40.0, coverage_params["minimumLine"])
        self.assertEqual(["demo:*"], coverage_params["include"])

    def test_partial_failure_updates_visible_state(self) -> None:
        runtime = NotebookRuntime(session=PartialFailureSession())  # type: ignore[arg-type]

        with self.assertRaises(DpsSessionError):
            runtime.execute_cell("say interrupted", 1)

        self.assertEqual(9, runtime.status_data()["state"]["gameTime"])

    def test_partial_directive_failure_updates_visible_state(self) -> None:
        runtime = NotebookRuntime(session=PartialFailureSession())  # type: ignore[arg-type]

        with self.assertRaises(DpsSessionError):
            runtime.execute_cell("%dps function demo:main", 1)

        self.assertEqual(9, runtime.status_data()["state"]["gameTime"])

    def test_missing_execution_context_does_not_mark_jvm_session_lost(self) -> None:
        session = MissingContextSession()
        runtime = NotebookRuntime(session=session)  # type: ignore[arg-type]

        with self.assertRaises(DpsSessionError) as failure:
            runtime.execute_cell("say first", 1)
        self.assertEqual("MISSING_CONTEXT", failure.exception.code)
        resumed = runtime.execute_cell("say resumed", 2)

        self.assertIn("commands=2", resumed.summary)
        self.assertFalse(runtime.status_data()["pendingReset"])

    def test_client_assets_are_never_auto_discovered(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            appdata = root / "appdata"
            client_jar = appdata / ".minecraft" / "versions" / "26.2" / "26.2.jar"
            client_jar.parent.mkdir(parents=True)
            client_jar.write_bytes(b"client")
            with patch.dict(os.environ, {"APPDATA": str(appdata), "DPS_KERNEL_CONFIG": str(root / "missing.json")}, clear=True):
                runtime = NotebookRuntime(session=FakeSession(), cwd=root)  # type: ignore[arg-type]

        self.assertIsNone(runtime.config.minecraft_assets)

    def test_completion_delegates_mcf_and_completes_magics(self) -> None:
        runtime = NotebookRuntime(session=FakeSession())  # type: ignore[arg-type]
        command = runtime.complete("sco", 3)
        magic = runtime.complete("%dps res", 8)
        self.assertEqual(["scoreboard"], command["matches"])
        self.assertIn("reset", magic["matches"])
        self.assertIn("resource-pack", magic["matches"])

    def test_completeness_tracks_json_and_quotes(self) -> None:
        self.assertTrue(has_unclosed_structure('tellraw @a {"text":"hello"'))
        self.assertFalse(has_unclosed_structure('tellraw @a {"text":"hello"}'))

    def test_project_config_resolves_paths_relative_to_notebook(self) -> None:
        session = FakeSession()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "pack").mkdir()
            (root / ".dps-kernel.json").write_text(
                '{"version":"1.21.5","packs":["./pack"],"autoRender":false,"render":{"width":640,"height":360}}',
                encoding="utf-8",
            )
            runtime = NotebookRuntime(session=session, cwd=root)  # type: ignore[arg-type]
            self.assertEqual("1.21.5", runtime.config.version)
            self.assertEqual([(root / "pack").resolve()], runtime.config.packs)
            self.assertFalse(runtime.config.auto_render)
            self.assertEqual(640, runtime.config.render_width)

    def test_project_config_overrides_environment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".dps-kernel.json").write_text('{"version":"1.21.5"}', encoding="utf-8")
            with patch.dict(os.environ, {"DPS_VERSION": "1.20.4"}):
                runtime = NotebookRuntime(session=FakeSession(), cwd=root)  # type: ignore[arg-type]

            self.assertEqual("1.21.5", runtime.config.version)

    def test_project_config_can_explicitly_disable_environment_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            configured_assets = root / "client.jar"
            configured_assets.write_bytes(b"client")
            (root / ".dps-kernel.json").write_text('{"minecraftAssets":null}', encoding="utf-8")
            with patch.dict(os.environ, {"DPS_MINECRAFT_ASSETS": str(configured_assets)}):
                runtime = NotebookRuntime(session=FakeSession(), cwd=root)  # type: ignore[arg-type]

            self.assertIsNone(runtime.config.minecraft_assets)


if __name__ == "__main__":
    unittest.main()

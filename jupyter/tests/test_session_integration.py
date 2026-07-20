from __future__ import annotations

import base64
import concurrent.futures
import os
import time
import unittest
from pathlib import Path

from datapack_sandbox_kernel.session import DpsSession, DpsSessionError


class DpsSessionIntegrationTest(unittest.TestCase):
    def test_java_version_is_checked_before_starting_cli(self) -> None:
        jar = Path(os.environ["DPS_CLI_JAR"]).resolve()
        session = DpsSession(jar=jar, java=os.environ.get("PYTHON", os.sys.executable), cwd=jar.parents[3])
        with self.assertRaises(DpsSessionError) as failure:
            session.start()
        self.assertEqual("JAVA_VERSION_MISMATCH", failure.exception.code)

    def test_real_serve_session_executes_and_renders(self) -> None:
        jar = Path(os.environ["DPS_CLI_JAR"]).resolve()
        session = DpsSession(jar=jar, cwd=jar.parents[3])
        try:
            hello = session.start()
            self.assertEqual("dps-jsonl", hello.protocol)
            self.assertTrue(hello.capabilities.get("render"))
            session.request("createSandbox", {"version": "26.2", "defaultPlayerName": "Steve"})
            session.request(
                "upsertFunctionSource",
                {
                    "id": "notebook:cell_1",
                    "text": "setblock 0 0 2 minecraft:stone\nscoreboard objectives add points dummy",
                    "sourceName": "<notebook:test-cell>",
                },
            )
            result = session.request("runFunction", {"id": "notebook:cell_1"})
            rendered = session.request("render", {"width": 320, "height": 180, "cameraPlayer": "Steve"})
            self.assertEqual(2, result["commands"])
            self.assertTrue(result["snapshotDiffs"])
            png = base64.b64decode(rendered["data"])
            self.assertEqual(b"\x89PNG\r\n\x1a\n", png[:8])
            self.assertFalse(rendered["metadata"]["visualParity"])
        finally:
            session.close()
        self.assertFalse(session.running)

    def test_mcfunction_failure_preserves_notebook_source_line(self) -> None:
        jar = Path(os.environ["DPS_CLI_JAR"]).resolve()
        session = DpsSession(jar=jar, cwd=jar.parents[3])
        try:
            session.request("createSandbox", {"version": "26.2", "defaultPlayerName": "Steve"})
            session.request(
                "upsertFunctionSource",
                {
                    "id": "notebook:cell_3",
                    "text": "\n\nscoreboard objectives add",
                    "sourceName": "<notebook:source-lines>",
                },
            )
            with self.assertRaises(DpsSessionError) as failure:
                session.request("runFunction", {"id": "notebook:cell_3"})
            self.assertEqual(3, failure.exception.details["location"]["line"])
            self.assertEqual("<notebook:source-lines>", failure.exception.details["location"]["file"])
        finally:
            session.close()

    def test_interrupt_stops_at_boundary_and_session_recovers(self) -> None:
        jar = Path(os.environ["DPS_CLI_JAR"]).resolve()
        session = DpsSession(jar=jar, cwd=jar.parents[3])
        try:
            session.request("createSandbox", {"version": "26.2", "defaultPlayerName": "Steve"})
            session.request("runCommand", {"command": "scoreboard objectives add points dummy"})
            session.request(
                "upsertFunctionSource",
                {
                    "id": "notebook:long_cell",
                    "text": "\n".join(["scoreboard players add #loop points 1"] * 20_000),
                    "sourceName": "<notebook:long-cell>",
                },
            )
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                running = executor.submit(session.request, "runFunction", {"id": "notebook:long_cell"})
                time.sleep(0.05)
                interrupted = session.request("interrupt")
                self.assertTrue(interrupted["requested"])
                with self.assertRaises(DpsSessionError) as failure:
                    running.result(timeout=30)
            self.assertEqual("EXECUTION_INTERRUPTED", failure.exception.code)
            self.assertIn("interrupted", str(failure.exception))
            self.assertIn("partial", failure.exception.details)
            state = session.request("state")
            self.assertEqual("26.2", state["version"])
            resumed = session.request("runCommand", {"command": "scoreboard players add #after points 1"})
            self.assertEqual(1, resumed["commands"])
        finally:
            session.close()


if __name__ == "__main__":
    unittest.main()

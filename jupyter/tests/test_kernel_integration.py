from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from typing import Any

from jupyter_client import KernelManager


class JupyterKernelIntegrationTest(unittest.TestCase):
    def test_kernel_executes_persistent_cells_and_publishes_rich_png(self) -> None:
        with tempfile.TemporaryDirectory(prefix="dps-jupyter-path-") as temporary:
            data_root = Path(temporary) / "Jupyter 路径"
            notebook_root = Path(temporary) / "Notebook 空格"
            notebook_root.mkdir(parents=True)
            write_kernel_spec(data_root)
            previous_path = os.environ.get("JUPYTER_PATH")
            os.environ["JUPYTER_PATH"] = str(data_root)
            manager = KernelManager(kernel_name="datapack-sandbox")
            client = None
            try:
                manager.start_kernel(cwd=str(notebook_root))
                client = manager.client()
                client.start_channels()
                client.wait_for_ready(timeout=30)

                info_id = client.kernel_info()
                info = matching_shell_message(client, info_id)["content"]
                self.assertEqual("mcfunction", info["language_info"]["name"])
                self.assertEqual(".mcfunction", info["language_info"]["file_extension"])

                first = execute(client, "setblock 0 0 2 minecraft:stone\nscoreboard objectives add points dummy")
                second = execute(client, "scoreboard players set Steve points 7")

                first_bundle = first_message(first, "display_data")["content"]["data"]
                second_bundle = first_message(second, "display_data")["content"]["data"]
                self.assertIn("image/png", first_bundle)
                self.assertIn("text/html", first_bundle)
                self.assertTrue(first_bundle["image/png"].startswith("iVBOR"))
                self.assertEqual(2, first_message(first, "display_data")["metadata"]["datapack-sandbox"]["commands"])
                self.assertEqual(2, first_message(first, "execute_result")["metadata"]["datapack-sandbox"]["commands"])
                self.assertEqual(1, first_message(second, "display_data")["metadata"]["datapack-sandbox"]["commands"])
                self.assertIn("commands=1", second_bundle["text/plain"])

                error_messages = execute(client, "scoreboard objectives add")
                error = first_message(error_messages, "error")["content"]
                self.assertTrue(error["ename"])
                self.assertTrue(any("<notebook:" in line for line in error["traceback"]))

                long_id = client.execute("\n".join(["scoreboard players add #interrupt points 1"] * 20_000))
                time.sleep(0.05)
                manager.interrupt_kernel()
                interrupted = collect_messages(client, long_id)
                interrupt_error = first_message(interrupted, "error")["content"]
                self.assertIn("interrupted", interrupt_error["evalue"])

                recovered = execute(client, "%dps status")
                self.assertIn("display_data", [message["msg_type"] for message in recovered])

                completion_id = client.complete("sco", cursor_pos=3)
                completion = matching_shell_message(client, completion_id)
                self.assertIn("scoreboard", completion["content"]["matches"])

                inspect_id = client.inspect("scoreboard players get Steve points", cursor_pos=35)
                inspected = matching_shell_message(client, inspect_id)
                self.assertTrue(inspected["content"]["found"])
                self.assertIn("Valid: true", inspected["content"]["data"]["text/plain"])
            finally:
                if client is not None:
                    client.stop_channels()
                if manager.has_kernel:
                    manager.shutdown_kernel(now=True)
                if previous_path is None:
                    os.environ.pop("JUPYTER_PATH", None)
                else:
                    os.environ["JUPYTER_PATH"] = previous_path


def execute(client: Any, code: str) -> list[dict[str, Any]]:
    message_id = client.execute(code)
    return collect_messages(client, message_id)


def collect_messages(client: Any, message_id: str) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    while True:
        message = client.get_iopub_msg(timeout=30)
        if message.get("parent_header", {}).get("msg_id") != message_id:
            continue
        messages.append(message)
        if message.get("msg_type") == "status" and message.get("content", {}).get("execution_state") == "idle":
            return messages


def first_message(messages: list[dict[str, Any]], message_type: str) -> dict[str, Any]:
    return next(message for message in messages if message.get("msg_type") == message_type)


def matching_shell_message(client: Any, message_id: str) -> dict[str, Any]:
    while True:
        message = client.get_shell_msg(timeout=20)
        if message.get("parent_header", {}).get("msg_id") == message_id:
            return message


def write_kernel_spec(data_root: Path) -> None:
    root = data_root / "kernels" / "datapack-sandbox"
    root.mkdir(parents=True)
    (root / "kernel.json").write_text(
        json.dumps(
            {
                "argv": [sys.executable, "-m", "datapack_sandbox_kernel", "-f", "{connection_file}"],
                "display_name": "Datapack Sandbox (MCFunction)",
                "language": "mcfunction",
                "interrupt_mode": "message",
            }
        ),
        encoding="utf-8",
    )


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


if __name__ == "__main__":
    unittest.main()

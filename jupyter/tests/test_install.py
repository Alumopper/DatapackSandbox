from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from datapack_sandbox_kernel.install import KERNEL_NAME, install_kernel_spec


class KernelInstallTest(unittest.TestCase):
    def test_installer_uses_jupyter_kernelspec_api_and_mcfunction_metadata(self) -> None:
        with tempfile.TemporaryDirectory(prefix="dps-kernel-prefix-") as temporary:
            destination = Path(install_kernel_spec(user=False, prefix=temporary))
            values = json.loads((destination / "kernel.json").read_text(encoding="utf-8"))

            self.assertEqual(KERNEL_NAME, destination.name)
            self.assertEqual("Datapack Sandbox (MCFunction)", values["display_name"])
            self.assertEqual("mcfunction", values["language"])
            self.assertEqual("message", values["interrupt_mode"])
            self.assertIn("datapack_sandbox_kernel", values["argv"])


if __name__ == "__main__":
    unittest.main()

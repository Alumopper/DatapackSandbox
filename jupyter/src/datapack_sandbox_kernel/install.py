"""Install the Datapack Sandbox kernelspec through Jupyter's public API."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

from jupyter_client.kernelspec import KernelSpecManager


KERNEL_NAME = "datapack-sandbox"


def install_kernel_spec(*, user: bool, prefix: str | None = None) -> str:
    with tempfile.TemporaryDirectory(prefix="dps-kernelspec-") as temporary:
        root = Path(temporary) / KERNEL_NAME
        root.mkdir()
        kernel_json = {
            "argv": [sys.executable, "-m", "datapack_sandbox_kernel", "-f", "{connection_file}"],
            "display_name": "Datapack Sandbox (MCFunction)",
            "language": "mcfunction",
            "interrupt_mode": "message",
            "metadata": {"debugger": False},
        }
        (root / "kernel.json").write_text(json.dumps(kernel_json, indent=2) + "\n", encoding="utf-8")
        destination = KernelSpecManager().install_kernel_spec(
            str(root),
            kernel_name=KERNEL_NAME,
            user=user,
            prefix=prefix,
        )
    return destination


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Install the Datapack Sandbox Jupyter kernelspec")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--user", action="store_true", help="Install for the current user (default)")
    group.add_argument("--sys-prefix", action="store_true", help="Install under sys.prefix")
    parser.add_argument("--prefix", help="Install under an explicit Jupyter prefix")
    parser.add_argument("--remove", action="store_true", help="Remove the installed kernelspec")
    args = parser.parse_args(argv)
    manager = KernelSpecManager()
    if args.remove:
        manager.remove_kernel_spec(KERNEL_NAME)
        print(f"Removed kernelspec {KERNEL_NAME}")
        return
    prefix = sys.prefix if args.sys_prefix else args.prefix
    destination = install_kernel_spec(user=not args.sys_prefix and args.prefix is None, prefix=prefix)
    print(f"Installed {KERNEL_NAME} kernelspec in {destination}")


if __name__ == "__main__":
    main()

"""Verify bundled Kernel artifacts and clean-room packaging boundaries."""

from __future__ import annotations

import sys
import tarfile
import zipfile
from pathlib import Path


def main(argv: list[str]) -> None:
    if not argv:
        raise SystemExit("Pass one or more wheel/sdist paths")
    for raw in argv:
        path = Path(raw)
        if path.suffix == ".whl":
            with zipfile.ZipFile(path) as archive:
                verify_names(path, archive.namelist())
        elif path.name.endswith(".tar.gz"):
            with tarfile.open(path) as archive:
                verify_names(path, archive.getnames())
        else:
            raise SystemExit(f"Unsupported distribution type: {path}")
        print(f"Verified {path}")


def verify_names(path: Path, names: list[str]) -> None:
    normalized = [name.replace("\\", "/").lower() for name in names]
    jars = [name for name in normalized if name.endswith("datapack-sandbox-cli.jar")]
    if len(jars) != 1:
        raise SystemExit(f"{path} must contain exactly one Datapack Sandbox CLI JAR; found {jars}")
    forbidden = [
        name
        for name in normalized
        if "/assets/minecraft/" in name
        or name.endswith("minecraft-client.jar")
        or name.endswith("minecraft-server.jar")
        or "/versions/" in name
    ]
    if forbidden:
        raise SystemExit(f"{path} contains forbidden Minecraft assets/client inputs: {forbidden[:10]}")


if __name__ == "__main__":
    main(sys.argv[1:])

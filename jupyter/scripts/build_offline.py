"""Build deterministic wheel and sdist artifacts using only the Python stdlib."""

from __future__ import annotations

import base64
import gzip
import hashlib
import io
import tarfile
import zipfile
from pathlib import Path

from verify_distribution import main as verify_distributions


VERSION = "1.0.1"
DIST_NAME = "datapack_sandbox_kernel"
PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = PROJECT_ROOT / "src"
PACKAGE_ROOT = SOURCE_ROOT / DIST_NAME
DIST_ROOT = PROJECT_ROOT / "dist"
DIST_INFO = f"{DIST_NAME}-{VERSION}.dist-info"


def main() -> None:
    jar = PACKAGE_ROOT / "resources" / "datapack-sandbox-cli.jar"
    if not jar.is_file():
        raise SystemExit(f"Missing bundled CLI JAR: {jar}. Run ./gradlew prepareJupyterKernel first.")
    DIST_ROOT.mkdir(parents=True, exist_ok=True)
    wheel = build_wheel()
    sdist = build_sdist()
    print(f"Built {wheel}")
    print(f"Built {sdist}")
    verify_distributions([str(wheel), str(sdist)])


def build_wheel() -> Path:
    output = DIST_ROOT / f"{DIST_NAME}-{VERSION}-py3-none-any.whl"
    entries: dict[str, bytes] = {}
    for path in package_files():
        entries[path.relative_to(SOURCE_ROOT).as_posix()] = path.read_bytes()
    entries[f"{DIST_INFO}/METADATA"] = metadata().encode()
    entries[f"{DIST_INFO}/WHEEL"] = (
        "Wheel-Version: 1.0\n"
        "Generator: datapack-sandbox-offline-builder\n"
        "Root-Is-Purelib: true\n"
        "Tag: py3-none-any\n"
    ).encode()
    entries[f"{DIST_INFO}/entry_points.txt"] = (
        "[console_scripts]\n"
        "datapack-sandbox-kernel = datapack_sandbox_kernel.install:main\n"
    ).encode()
    entries[f"{DIST_INFO}/top_level.txt"] = b"datapack_sandbox_kernel\n"
    record_lines = [record_line(name, content) for name, content in sorted(entries.items())]
    record_lines.append(f"{DIST_INFO}/RECORD,,")
    entries[f"{DIST_INFO}/RECORD"] = ("\n".join(record_lines) + "\n").encode()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, content)
    return output


def build_sdist() -> Path:
    output = DIST_ROOT / f"datapack_sandbox_kernel-{VERSION}.tar.gz"
    prefix = f"datapack_sandbox_kernel-{VERSION}"
    selected = [PROJECT_ROOT / "pyproject.toml", PROJECT_ROOT / "README.md", *package_files()]
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as archive:
                for path in sorted(selected):
                    relative = path.relative_to(PROJECT_ROOT).as_posix()
                    content = path.read_bytes()
                    info = tarfile.TarInfo(f"{prefix}/{relative}")
                    info.size = len(content)
                    info.mode = 0o644
                    info.mtime = 0
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    archive.addfile(info, io.BytesIO(content))
    return output


def package_files() -> list[Path]:
    return sorted(
        path
        for path in PACKAGE_ROOT.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts and path.suffix not in {".pyc", ".pyo"}
    )


def metadata() -> str:
    return (
        "Metadata-Version: 2.1\n"
        "Name: datapack-sandbox-kernel\n"
        f"Version: {VERSION}\n"
        "Summary: Jupyter Kernel for executing Minecraft mcfunction cells in Datapack Sandbox\n"
        "License: MIT\n"
        "Requires-Python: >=3.10\n"
        "Requires-Dist: ipykernel (>=6.29,<8)\n"
        "Requires-Dist: jupyter_client (>=8.6,<9)\n"
        "\n"
    )


def record_line(name: str, content: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(content).digest()).decode().rstrip("=")
    return f"{name},sha256={digest},{len(content)}"


if __name__ == "__main__":
    main()

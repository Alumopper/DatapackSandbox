#!/usr/bin/env python3
"""Small cross-platform driver for agentic Datapack Sandbox workflows."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
from typing import Sequence


def candidate_jars(workspace: Path) -> list[Path]:
    candidates: list[Path] = []
    for root in (workspace, *workspace.parents):
        candidates.extend(
            [
                root / "cli" / "build" / "libs" / "datapack-sandbox-cli.jar",
                root / "vscode" / "bin" / "datapack-sandbox-cli.jar",
                root / "datapack-sandbox-cli.jar",
            ]
        )
    return candidates


def resolve_jar(workspace: Path, explicit: str | None) -> Path:
    configured = explicit or os.environ.get("DPS_CLI_JAR")
    if configured:
        jar = Path(configured).expanduser()
        if not jar.is_absolute():
            jar = workspace / jar
        jar = jar.resolve()
        if jar.is_file():
            return jar
        raise SystemExit(f"Datapack Sandbox CLI JAR does not exist: {jar}")
    for jar in candidate_jars(workspace):
        if jar.is_file():
            return jar.resolve()
    checked = "\n  ".join(str(path) for path in candidate_jars(workspace))
    raise SystemExit(
        "Datapack Sandbox CLI JAR was not found. Set DPS_CLI_JAR or build :cli:fatJar.\n"
        f"Checked:\n  {checked}"
    )


def resolve_java(value: str) -> str:
    candidate = Path(value).expanduser()
    if candidate.is_file():
        return str(candidate.resolve())
    resolved = shutil.which(value)
    if resolved:
        return str(Path(resolved).resolve())
    raise SystemExit(f"Java executable was not found: {value}. Install Java 25 or pass --java <path>.")


def display_command(command: Sequence[str]) -> str:
    return " ".join(shlex.quote(part) for part in command)


def execute(command: Sequence[str], cwd: Path, quiet: bool = False) -> int:
    if not quiet:
        print(f"> {display_command(command)}", flush=True)
    completed = subprocess.run(command, cwd=cwd, text=True)
    return completed.returncode


def cli(java: str, jar: Path, args: Sequence[str], cwd: Path, quiet: bool = False) -> int:
    return execute([java, "-jar", str(jar), *args], cwd, quiet)


def relative_or_absolute(value: str, workspace: Path) -> str:
    path = Path(value).expanduser()
    return str((workspace / path).resolve() if not path.is_absolute() else path.resolve())


def doctor(args: argparse.Namespace, workspace: Path, jar: Path) -> int:
    print(json.dumps({"workspace": str(workspace), "jar": str(jar), "java": args.java}, indent=2))
    java_code = execute([args.java, "-version"], workspace)
    if java_code != 0:
        return java_code
    return cli(args.java, jar, ["version", "--json"], workspace)


def catalog(args: argparse.Namespace, workspace: Path, jar: Path) -> int:
    output = (workspace / args.output / args.version).resolve()
    output.mkdir(parents=True, exist_ok=True)
    commands = [
        ["version", "--json", "--output", str(output / "version.json")],
        ["commands", "--json", "--version", args.version, "--output", str(output / "commands.json")],
        ["resources", "--json", "--version", args.version, "--output", str(output / "resources.json")],
    ]
    if args.pack:
        loaded = ["resources", "--json", "--version", args.version]
        for pack in args.pack:
            loaded += ["--pack", relative_or_absolute(pack, workspace)]
        loaded += ["--output", str(output / "loaded-resources.json")]
        commands.append(loaded)
    for command in commands:
        code = cli(args.java, jar, command, workspace)
        if code != 0:
            return code
    version_document = json.loads((output / "version.json").read_text(encoding="utf-8"))
    selected = next((profile for profile in version_document.get("profiles", []) if profile.get("id") == args.version), None)
    if selected is None:
        print(f"Selected profile was not present in version output: {args.version}", file=sys.stderr)
        return 2
    (output / "selected-profile.json").write_text(json.dumps(selected, indent=2) + "\n", encoding="utf-8")
    expected_format = str(selected.get("dataPackFormat", ""))
    print(f"Selected profile {args.version}: pack_format={expected_format} data_version={selected.get('dataVersion')}")
    for pack_value in args.pack:
        pack_path = Path(relative_or_absolute(pack_value, workspace))
        metadata_path = pack_path / "pack.mcmeta"
        if not metadata_path.is_file():
            continue
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            actual_format = format_value(metadata.get("pack", {}).get("pack_format"))
            if actual_format != expected_format:
                print(
                    f"WARNING: {metadata_path} declares pack_format={actual_format}; "
                    f"profile {args.version} expects {expected_format}. Verify supported_formats or update the metadata.",
                    file=sys.stderr,
                )
        except (OSError, ValueError, TypeError) as error:
            print(f"WARNING: Could not inspect {metadata_path}: {error}", file=sys.stderr)
    print(f"Catalog written to {output}")
    return 0


def format_value(value: object) -> str:
    if isinstance(value, list) and value:
        return ".".join(str(part) for part in value)
    return str(value)


def check(args: argparse.Namespace, workspace: Path, jar: Path) -> int:
    output = (workspace / args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    command = ["check", *(relative_or_absolute(value, workspace) for value in args.input)]
    command += [
        "--validate-schema",
        "--snapshot-diff-on-fail",
        "--trace",
        "--trace-file",
        str(output / "trace.jsonl"),
        "--outputs-file",
        str(output / "outputs.jsonl"),
        "--report-file",
        str(output / "report.json"),
    ]
    if args.strict:
        command.append("--strict")
    if args.fail_fast:
        command.append("--fail-fast")
    for trace_filter in args.trace_filter:
        command += ["--trace-filter", trace_filter]
    code = cli(args.java, jar, command, workspace)
    print(f"Check artifacts: {output}")
    return code


def run_feature(args: argparse.Namespace, workspace: Path, jar: Path) -> int:
    output = (workspace / args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    command = ["run", "--version", args.version]
    for pack in args.pack:
        command += ["--pack", relative_or_absolute(pack, workspace)]
    if args.world:
        command += ["--world", relative_or_absolute(args.world, workspace)]
    if args.function:
        command += ["--function", args.function]
    if args.command:
        command += ["--command", args.command]
    if args.load:
        command.append("--load")
    if args.ticks:
        command += ["--ticks", str(args.ticks)]
    command += [
        "--snapshot",
        "--snapshot-file",
        str(output / "snapshot.json"),
        "--snapshot-diff",
        "--trace",
        "--trace-file",
        str(output / "trace.jsonl"),
        "--outputs-file",
        str(output / "outputs.jsonl"),
        "--report-file",
        str(output / "report.json"),
        "--resources",
    ]
    if args.strict:
        command.append("--strict")
    return cli(args.java, jar, command, workspace)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--workspace", default=".", help="Datapack project workspace")
    root.add_argument("--jar", help="Datapack Sandbox CLI JAR (or set DPS_CLI_JAR)")
    root.add_argument("--java", default="java", help="Java 25 executable; defaults to java on PATH")
    subparsers = root.add_subparsers(dest="action", required=True)

    subparsers.add_parser("doctor", help="Verify Java, locate the JAR, and list profiles")

    catalog_parser = subparsers.add_parser("catalog", help="Export profile and support catalogs")
    catalog_parser.add_argument("--version", default="26.2")
    catalog_parser.add_argument("--pack", action="append", default=[])
    catalog_parser.add_argument("--output", default=".dps-agent/catalog")

    check_parser = subparsers.add_parser("check", help="Run schema-aware manifest checks")
    check_parser.add_argument("input", nargs="+", help="Manifest file or directory")
    check_parser.add_argument("--strict", action=argparse.BooleanOptionalAction, default=True)
    check_parser.add_argument("--fail-fast", action="store_true")
    check_parser.add_argument("--trace-filter", action="append", default=[])
    check_parser.add_argument("--output", default=".dps-agent/check")

    run_parser = subparsers.add_parser("run", help="Run a focused function or command")
    run_parser.add_argument("--version", default="26.2")
    run_parser.add_argument("--pack", action="append", default=[])
    run_parser.add_argument("--world")
    run_parser.add_argument("--function")
    run_parser.add_argument("--command")
    run_parser.add_argument("--load", action="store_true")
    run_parser.add_argument("--ticks", type=int, default=0)
    run_parser.add_argument("--strict", action=argparse.BooleanOptionalAction, default=True)
    run_parser.add_argument("--output", default=".dps-agent/run")
    return root


def main() -> int:
    args = parser().parse_args()
    workspace = Path(args.workspace).expanduser().resolve()
    if not workspace.is_dir():
        raise SystemExit(f"Workspace does not exist: {workspace}")
    jar = resolve_jar(workspace, args.jar)
    args.java = resolve_java(args.java)
    actions = {"doctor": doctor, "catalog": catalog, "check": check, "run": run_feature}
    return actions[args.action](args, workspace, jar)


if __name__ == "__main__":
    sys.exit(main())

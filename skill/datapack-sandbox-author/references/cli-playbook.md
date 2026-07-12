# CLI Playbook

## Contents

- Tool discovery
- Catalog before coding
- Fast function runs
- Manifest checks
- Reading artifacts
- Escalation and vanilla boundaries

## Tool discovery

The helper searches, in order:

1. `--jar`;
2. `DPS_CLI_JAR`;
3. `cli/build/libs/datapack-sandbox-cli.jar` in the workspace or ancestors;
4. `vscode/bin/datapack-sandbox-cli.jar` in the workspace or ancestors;
5. `datapack-sandbox-cli.jar` in the workspace.

Run:

```powershell
python <skill>/scripts/dps_agent.py --workspace . doctor
```

The CLI requires Java 25 even when modeling an older Minecraft profile.
The helper uses `java` from `PATH` by default; pass `--java <path>` when the required JDK is not the shell default.

## Catalog before coding

Generate profile-specific catalogs into `.dps-agent/catalog/<version>/`:

```powershell
python <skill>/scripts/dps_agent.py --workspace . catalog --version 26.2 --pack ./pack
```

Inspect:

- `version.json` for pack/data versions;
- `selected-profile.json` for the exact selected `dataPackFormat`, data version, directories, and registries;
- `commands.json` for command behavior levels;
- `resources.json` for format support;
- `loaded-resources.json` for active, overridden, and missing pack resources.

Use the catalogs to make architecture decisions. `modeled` is strongest, `partial` requires a documented boundary, and `unsupported` requires redesign or external validation.

Copy the exact `dataPackFormat` value into `pack.mcmeta`; do not truncate fractional values such as `107.1`. The helper warns when a loaded directory's declared `pack_format` differs.

## Fast function runs

Use a direct function run while building a vertical slice:

```powershell
python <skill>/scripts/dps_agent.py --workspace . run --version 26.2 --pack ./pack --function demo:feature/start
```

The helper writes `report.json`, `trace.jsonl`, `outputs.jsonl`, and `snapshot.json` under `.dps-agent/run/`.

For a standalone source file:

```powershell
java -jar <jar> run --version 26.2 \
  --mcfunction ./scratch.mcfunction \
  --mcfunction-id demo:scratch \
  --snapshot-diff --report-file .dps-agent/scratch-report.json
```

## Manifest checks

Default to strict schema-aware checks during agent work:

```powershell
python <skill>/scripts/dps_agent.py --workspace . check ./tests --strict
```

For a single failure:

```powershell
java -jar <jar> check ./tests/feature.dps.json \
  --strict --validate-schema --snapshot-diff-on-fail --verbose \
  --trace --trace-file .dps-agent/feature-trace.jsonl \
  --outputs-file .dps-agent/feature-outputs.jsonl \
  --report-file .dps-agent/feature-report.json
```

Use `--unsupported error` for release gates. Use `--trace-filter root=scoreboard`, `score=<holder>`, `output=<text>`, `state=<path>`, or `selector=<name>` to narrow noisy traces.

## Reading artifacts

Read failures causally:

1. `report[].diagnostics` and attempt messages;
2. first trace where `success` is false;
3. that trace's `source`, `outputEvents`, and `snapshotDiffs`;
4. final `snapshot` only to confirm propagation;
5. missing references and active/overridden resource data.

Do not begin by editing an assertion unless the trace proves runtime behavior is correct.

## Escalation and vanilla boundaries

The sandbox is a clean-room modeled runtime. For behavior depending on redstone updates, AI/pathfinding, chunk loading, networking, client rendering, or another unsupported edge:

- keep the edge isolated;
- test all modeled state around it;
- export a replay with `diff --script` when useful;
- clearly request vanilla/integration verification for the remaining boundary.

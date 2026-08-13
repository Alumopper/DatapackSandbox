# CLI Reference

## When to use this page

Use this page after choosing a workflow to look up command responsibilities, shared policy, exit codes, and machine-output boundaries. The executable remains authoritative for the exact option spelling installed on your machine:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar --help
java -jar cli/build/libs/datapack-sandbox-cli.jar run --help
```

## Command map

| Subcommand | Primary input | Primary result | Use it for |
| --- | --- | --- | --- |
| `run` | Packs, functions, commands, events, fixture | State/events/report/image | One-shot execution and assertions |
| `check` | Manifest files/directories | Per-manifest/per-version results | Repeatable regression suites |
| `repl` | Packs | Interactive retained world | Manual debugging |
| `viewport` | Packs + optional startup actions | GLFW/OpenGL window | JVM live visual inspection |
| `serve` | JSONL stdin | JSONL stdout | Editors, kernels, long-lived process clients |
| `diff` | Snapshots/reports or a Manifest | Text/JSON difference or script | Golden comparison and external replay |
| `loot` | Pack, table, context | Generated items | Focused loot-table checks |
| `advancement` | Pack, player, advancement action | Progress/state | Focused advancement checks |
| `event` | Pack + textual player event | Resulting outputs/state | One player-event smoke test |
| `benchmark` | Built-in/custom scenario options | Timing/metrics | Scale and performance smoke |
| `schema` | Bundled Manifest schema | JSON Schema file/check | Editor and CI validation |
| `version` | Zero/two profile ids | Catalog or profile diff | Version planning |
| `commands` | Profile and output mode | Command behavior catalog | Support lookup/docs checks |
| `resources` | Profile or packs and filters | Resource/registry catalog | Resource support and override inspection |

## Execution commands

### `run`

`run` is the widest one-shot surface. It accepts folder/zip packs, file/text/stdin functions, direct command streams, a world fixture, load/ticks/functions/events, assertions, resource checks, safety limits, coverage, reports, and screenshot options. Input action families follow a fixed lifecycle; use Manifest `steps` for arbitrary interleaving. See [Run with the CLI](/en/workflows/cli).

### `check`

`check <input>...` recursively discovers `.dps.json` under directories and runs files directly. Important controls are `--fail-fast`, `--validate-schema`, `--strict`, failure snapshots/diffs, trace/output/report/coverage files, a seed override, unsupported policy, and safety limits. One multi-version Manifest creates one attempt per profile.

### `repl`

`repl --pack <path>...` opens a JLine-based session with completion, history, reload/watch, trace/diff/rerun, fixtures, and structured inspection. Pack reload retains world state; `reset world` does not. See [Debug with the REPL](/en/workflows/repl).

### `viewport`

`viewport` opens a native JVM window. It accepts version/packs, explicit `--minecraft-assets`, resource packs, startup commands/functions, window size, target FPS/tick rate, autoplay, input player, FOV, movement/mouse/UI scale, and PNG export directory. Client assets are never discovered automatically.

### `serve`

`serve --protocol jsonl` owns one sandbox and exchanges one JSON object per UTF-8 line. `--ready-file` can signal process readiness to a host. Do not mix protocol stdout with human logging. See [Serve JSONL](/en/reference/serve-jsonl).

## Focused and inspection commands

### `diff`

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar diff expected.json actual.json
java -jar cli/build/libs/datapack-sandbox-cli.jar diff --snapshot --check vanilla-report.json sandbox-report.json
java -jar cli/build/libs/datapack-sandbox-cli.jar diff --script -o replay.mcfunction case.dps.json
```

`--snapshot` extracts report snapshots, `--state` compares state-oriented content, `--json` writes a structured diff, `--check` makes a difference fail the process, and `--script` converts externally replayable Manifest steps to `.mcfunction` text while preserving sandbox-only operations as comments.

### `loot`, `advancement`, and `event`

These are convenience entry points for narrow manual tests. They accept version/packs and domain-specific parameters, then create a short-lived sandbox. Prefer a Manifest/QuickTest when the result needs multiple fixtures or assertions.

### `benchmark`

The benchmark command exercises scale-oriented built-in or pack-backed scenarios and can emit JSON metrics. It is a smoke/performance comparison tool, not a microbenchmark harness with JVM fork/warmup isolation.

### `schema`

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --output build/dps-manifest.schema.json
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --check schema/manifest/dps-manifest.schema.json
```

Export uses the schema embedded in the jar. Check mode compares it with a file, which is how repository drift is detected.

### `version`, `commands`, and `resources`

All three support human and machine-oriented catalog modes. Representative calls:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar version --json
java -jar cli/build/libs/datapack-sandbox-cli.jar version 1.20.4 26.2
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --json --version 26.2
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --pack .\my-pack --json
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --pack .\my-pack --id demo:main --active-only
```

Their `--docs`/`--check` modes maintain generated sections in this repository's command, resource, and version reference pages. Those modes inspect catalogs; they do not execute a datapack scenario.

## Shared runtime policy

### Profiles

Omitting `--version` selects the current default profile, `26.2`. Reproducible scripts should pass it explicitly. A profile controls command/resource/version behavior; it does not change the CLI binary version.

### Unsupported behavior

`--unsupported` accepts warn, ignore, and error modes (including supported aliases). Warn records diagnostics and continues, ignore silently skips recognized unmodeled behavior, and error fails. `run/check --strict` selects error and also treats missing direct resource references as failures.

### Limits

`run` and `check` expose `--max-commands`, `--max-function-depth`, `--max-ticks-per-run`, `--max-output-events`, and `--max-snapshot-bytes`. These correspond to `SandboxLimits` and should be set conservatively for generated or untrusted input.

## Exit codes

| Code | Constant | Meaning |
| --- | --- | --- |
| `0` | `OK` | Success |
| `1` | `ASSERTION_FAILED` | Assertion or threshold failed |
| `2` | `INPUT_FORMAT` | Invalid option, JSON, schema input, or request shape |
| `3` | `UNSUPPORTED_OR_VERSION` | Unsupported feature, version mismatch, missing resource/context, command error, or interruption |

An individual subcommand may use failure semantics such as `diff --check`, but it maps process completion into this stable set. Always propagate the exit code in scripts.

## Machine-output contract

- JSON files contain one complete value; JSONL files contain one object per UTF-8 line.
- `serve` reserves stdout for JSONL protocol envelopes.
- `run/check` console output, colors, tables, and wording are human presentation and may evolve.
- Report objects may gain fields. Consumers should require known fields and ignore unknown ones.
- Trace filters change display/export selection, not the sandbox's underlying record or combined report.

## Rendering assets

Every JVM render surface (`run --screenshot-file`, `viewport`, and Serve `render`) requires the caller to explicitly pass a matching Minecraft client JAR or `assets/` directory for vanilla models/textures. No command infers a `.minecraft` path or downloads assets from `--version`. Without assets, headless rendering uses fallbacks; the live viewport should be given `--minecraft-assets` for meaningful client visuals.

## Limitations

- The fat jar is an application boundary, not a stable JVM library API; use published `core`, `testkit`, and `renderer` modules for embedding.
- Options can grow with new capabilities, so generated wrappers should consult `--help` for their pinned release.
- CLI success validates the clean-room modeled runtime, not every vanilla server/client subsystem.

## Related pages

- [Run with the CLI](/en/workflows/cli)
- [Debug with the REPL](/en/workflows/repl)
- [Manifest Reference](/en/reference/manifest)
- [Serve JSONL](/en/reference/serve-jsonl)
- [Reports and Observability](/en/reference/reports-observability)

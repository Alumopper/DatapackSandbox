# Run Datapacks with the CLI

## When to use this page

Use `run` for a focused, one-shot experiment: execute a function, generated command stream, player event, or single `.mcfunction`, then export exactly the evidence you need. Use `check` when the scenario is already a reusable `.dps.json` regression, and use the REPL when you want to preserve a world while trying commands interactively.

## Prerequisites

Prepare `datapack-sandbox-cli.jar` as described in [Install and Obtain](/en/workflows/installation). The examples below run from the repository root and use profile `26.2` explicitly for reproducibility.

Before testing a custom pack, confirm its current resource layout. New profiles use singular directories such as `data/demo/function`, `loot_table`, and `advancement`.

## Minimal runnable examples

### Run one file

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --mcfunction examples/coverage/pack/data/demo/function/main.mcfunction `
  --snapshot-diff
```

With a bare `--mcfunction` path, the synthetic entry id defaults to `sandbox:main` and is executed as part of the lightweight-function phase.

### Run a pack function

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --pack examples/full-stack/pack `
  --function demo:reward `
  --trace `
  --outputs-file build/reward-outputs.jsonl `
  --report-file build/reward-report.json
```

The pack is loaded, `demo:reward` runs in the same sparse world, and the output/report artifacts describe that operation.

## Understand the execution order

`run` composes inputs in a fixed lifecycle:

1. Select the version profile and create a sandbox from `--pack` plus lightweight functions.
2. Apply `--world` and the optional `--seed` override.
3. Execute the lightweight synthetic entry when `--mcfunction`, `--mcfunction-text`, or `--stdin` supplied one.
4. Run `--load`, then advance `--ticks`.
5. Execute each `--function`, direct `--command`/`--command-file`, and `--event`/`--event-file` in option order within its family.
6. Evaluate `--assert` and `--assert-file` expectations.
7. Print or write resources, snapshots, diffs, traces, events, coverage, screenshots, and the combined report.

If ordering between heterogeneous actions is part of the contract, write a Manifest whose `steps` array represents the order directly.

## Choose an input form

| Input | Best for | Important detail |
| --- | --- | --- |
| `--pack <dir-or-zip>` | Complete packs and dependencies | Repeatable; later packs have higher resource priority |
| `--mcfunction <path>` | One scratch/generated file | Use `id=path` when another function calls it |
| `--mcfunction-text <text>` | Small generated content | Use `id=text` for multiple/callable functions |
| `--mcfunction-id <id>` | Override the default entry id | Applies to the unqualified lightweight entry |
| `--stdin --stdin-mode mcfunction` | Pipe a generated function | Keeps commands off the shell command line |
| `--stdin --stdin-mode commands` | Pipe raw direct commands | Commands execute independently rather than as one function |
| `--command` / `--command-file` | Setup or ad-hoc commands | Files ignore blank/comment lines |
| `--function <id>` | Invoke a loaded function | May be repeated |
| `--event` / `--event-file` | Player behavior events | One textual event per file line |
| `--world <json>` | Manifest-style fixture | Paths inside it resolve from the fixture/managing input |

For multiple lightweight functions, give every callable source an id:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --mcfunction demo:main=./scratch/main.mcfunction `
  --mcfunction demo:helper=./scratch/helper.mcfunction `
  --mcfunction-text "demo:inline=scoreboard players add #clock ticks 1"
```

## Add a fixture and assertions

`--world` accepts the same world object used by a Manifest. `--seed` overrides its seed, which also affects the `seed` command and default random sequences.

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --world .\fixture-world.json `
  --seed 42 `
  --command "function demo:main" `
  --assert '{"world":{"seed":42}}' `
  --assert '{"score":{"target":"#case","objective":"runs","equals":1}}' `
  --assert "output:ready"
```

`--assert` accepts either a JSON assertion object or a compact shorthand. `--assert-file` accepts JSON assertions or one non-empty, non-comment shorthand per line. Prefer JSON for precise output segments, event traces, world fields, or diagnostic constraints; shorthands are convenient for local smoke checks.

When a direct command is expected to fail, add `--allow-command-failure`. Execution then continues so a diagnostic or trace assertion can inspect the failure. This flag applies to direct commands, command files, and command-mode stdin; it does not turn arbitrary function failures into success.

## Select evidence

| Goal | Console/file options | Format |
| --- | --- | --- |
| Complete final state | `--snapshot`, `--snapshot-file` | JSON |
| State changes from the start | `--snapshot-diff`, `--snapshot-diff-file` | JSON / rendered text |
| Command call and diagnostic trail | `--trace`, `--trace-file`, `--trace-filter` | JSONL file |
| Player-event decisions | `--event-trace-file` | JSONL |
| Chat/title/sound/structured outputs | `--outputs-file` | JSONL |
| Resource counts and missing references | `--resources`, combined report | Console / JSON |
| Executed functions and lines | `--coverage`, `--coverage-file`, thresholds/filters | JSON |
| Everything needed by CI | `--report-file` | JSON |
| Current modeled world image | `--screenshot-file` and render options | PNG |

Use separate JSONL files when a consumer streams events. Use `--report-file` when one self-contained artifact is more convenient. The report embeds unfiltered runtime data even when console/trace exports are narrowed for diagnosis.

## Strictness and safety limits

`--unsupported warn|ignore|error` controls recognized but unmodeled behavior. `--strict` selects error behavior and also fails direct missing-resource references. `--fail-on-missing-resources` enables only the resource-reference part.

Bound untrusted or generated input with:

- `--max-commands`
- `--max-function-depth`
- `--max-ticks-per-run`
- `--max-output-events`
- `--max-snapshot-bytes`

The command budget resets per top-level operation according to `SandboxLimits`; a limit failure is still observable in traces and, for supported integrations, partial results.

## Render a screenshot

Rendering never discovers or downloads Minecraft assets. Pass `--minecraft-assets` with a matching client JAR or a directory containing `assets/`; resource packs and player skins are additional explicit inputs.

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --pack .\my-pack `
  --function demo:build `
  --screenshot-file build/world.png `
  --minecraft-assets D:\Minecraft\versions\26.2\26.2.jar `
  --screenshot-width 1280 `
  --screenshot-height 720 `
  --require-render-assets
```

Without `--minecraft-assets`, the renderer uses deterministic procedural fallbacks. `--require-render-assets` makes missing/invalid assets fail instead. Camera selection can target a player, entity UUID, or fixed position; see [Rendering and Live Viewports](/en/guide/rendering-notebook).

## Read the result

Exit code `0` means the operation and assertions passed. `1` is an assertion or coverage-threshold failure, `2` is malformed input, and `3` covers unsupported/version/resource/command/interruption/context diagnostics. CI should use the exit code and machine files together: the code gates the job, while the report explains why.

## Limitations

- The world is sparse and deterministic; `run` does not generate terrain or emulate networking, permissions, entity AI, redstone, or vanilla server threading.
- Multiple action families do not form one arbitrary interleaved sequence; use Manifest `steps` when exact cross-family order matters.
- Console presentation is for people. Do not parse colors, spacing, or localized wording as an API.
- Relaxing safety limits for untrusted input can cause excessive CPU or memory use.

## Related pages

- [CLI Reference](/en/reference/cli)
- [Debug with the REPL](/en/workflows/repl)
- [Manifest Regression Tests](/en/workflows/manifest-tests)
- [Reports and Observability](/en/reference/reports-observability)

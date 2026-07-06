# Datapack Sandbox

[中文文档](README.zh-CN.md)

A lightweight, clean-room Minecraft Java datapack sandbox focused on local CLI
debugging, manifest checks, and JVM-level quick tests. Built-in version profiles
cover every Minecraft Java release from `1.20.4` through `26.2`, with `26.2` as
the default latest profile.

The runtime does not embed or distribute a vanilla server. Build tooling uses
public `SpyglassMC/vanilla-mcdoc` data and the official `@spyglassmc/mcdoc`
parser to generate NBT schemas for validation.

## Build

```bash
./gradlew :cli:fatJar
```

On Windows:

```powershell
.\gradlew.bat :cli:fatJar
```

Run release smoke checks for the standalone jar:

```powershell
.\gradlew.bat :cli:smokeCliJar
```

The smoke checks build the jar, export the bundled manifest schema, run all
example manifests, and execute the concrete README `loot` and player `event`
examples. The standard `check` lifecycle also runs unit tests, manifest
examples, and the standalone jar smoke checks; CI runs `check` on Linux,
Windows, and macOS.

The standalone jar is written to:

```text
cli/build/libs/datapack-sandbox-cli.jar
```

## CLI Examples

Start a REPL:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar repl --version 26.2 --pack ./my_pack
```

List supported version profiles and their data pack formats:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version
java -jar cli/build/libs/datapack-sandbox-cli.jar version 1.20.4 26.2
```

The REPL supports TAB completion, live multi-line suggestions while typing,
history suggestions, colored output, Ctrl+C exit, runtime datapack reloads,
trace/diff helpers, rerun-last, reset-world, and fixture loading:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar repl --pack ./my_pack --watch
dps> reload
dps> function demo:main
dps> trace on
dps> diff last
dps> inspect raw damage_type
dps> inspect resources damage_type
dps> inspect outputs
```

Run a quick smoke test:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --load --ticks 20 --function demo:main --snapshot
```

For debugging function call chains or command-generator output, enable structured
trace output:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --function demo:main --trace --trace-file trace.jsonl
```

Write observable command outputs as JSONL for CI artifacts:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --function demo:main --outputs-file outputs.jsonl
```

Write a combined JSON report with assertion failures, outputs, traces, event
traces, the final snapshot, snapshot diffs, and resource summary details:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --function demo:main --report-file run-report.json
```

Use `--trace-filter root=scoreboard`, `--trace-filter score=#clock`,
`--trace-filter output=generated ok`, or `--trace-filter selector=Steve` to keep
only the relevant trace events in both console output and JSONL files. Trace
entries include per-command `outputEvents` and `snapshotDiffs`, so filters can
target output text, output targets, state paths, and command text.

To inspect state changes before and after a run, print a snapshot diff:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --function demo:main --snapshot-diff
```

Run a single `.mcfunction` file without creating a full datapack:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 --mcfunction ./scratch/test.mcfunction --snapshot
```

Run inline `.mcfunction` text directly:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 --mcfunction-text "say hello from inline"
```

Run one or more raw command files from a generator:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --command-file ./generated/setup.commands \
  --command-file ./generated/body.commands \
  --assert "output:generated ok"
```

Read `.mcfunction` text from standard input:

```bash
printf 'scoreboard objectives add runs dummy\nscoreboard players set #stdin runs 1\n' \
  | java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 --stdin --snapshot
```

Load multiple lightweight functions together. Use `id=path` or `id=text` when a
function must be callable by another function:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --mcfunction-id demo:main \
  --mcfunction demo:main=./scratch/main.mcfunction \
  --mcfunction demo:helper=./scratch/helper.mcfunction \
  --mcfunction-text "demo:inline=scoreboard players add #clock ticks 1"
```

Add one or more folder/zip datapacks as dependencies for lightweight functions:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --pack ./deps/library_pack \
  --pack ./deps/items.zip \
  --mcfunction-id demo:main \
  --mcfunction-text "demo:main=function library:setup"
```

For quick pack-level sanity checks, `run` can use the same direct missing
resource validation as manifest checks, and `--resources` prints the resource
summary without requiring a full manifest:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --resources --fail-on-missing-resources
```

Run JSON check manifests:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --validate-schema
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --fail-on-missing-resources
```

Export the bundled manifest JSON Schema for editors or CI tooling:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --output dps-manifest.schema.json
```

Use `--verbose` to include deterministic resource summaries, pack overlay
entries, missing load/tick function references, and manifest output events:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --verbose
```

When assertions fail, print the final snapshot or a minimal snapshot diff:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --snapshot-on-fail --snapshot-diff-on-fail
```

For CI artifacts, `check` can also write command traces:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --trace-filter root=scoreboard --trace-file check-trace.jsonl --outputs-file check-outputs.jsonl --report-file check-report.json
```

For ad hoc checks without a full manifest, `run` can apply a manifest-style
world fixture, override its seed, and evaluate one or more inline JSON
assertions. The seed is visible to the `seed` command and default `random`
sequences:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --world ./fixture-world.json \
  --seed 42 \
  --assert '{"world":{"seed":42}}' \
  --assert '{"score":{"target":"#fixture","objective":"ready","equals":1}}' \
  --assert-file ./assertions.json
```

For quick score, storage, advancement, player, item, entity-count, diff, trace, trace-output, warning, unsupported warning, output, and normalized output checks,
`--assert` also accepts compact shorthands. `--assert-file` can contain JSON
assertions or one shorthand per non-empty, non-comment line:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --command "scoreboard objectives add runs dummy" \
  --command "scoreboard players set #fixture runs 1" \
  --command "data merge storage demo:env {ready:true}" \
  --command "give Steve minecraft:stick 3" \
  --command "summon minecraft:pig 0 0 0 {Tags:[\"fixture\"]}" \
  --command "say generated ok" \
  --assert "score:#fixture:runs=1" \
  --assert "storage:demo:env:ready=true" \
  --assert "player:Steve?" \
  --assert "item:Steve:minecraft:stick=3" \
  --assert "entity:minecraft:pig@fixture=1" \
  --assert "diff:/scores/runs=added" \
  --assert "trace:scoreboard=2" \
  --assert "trace-output:generated ok@Steve" \
  --assert "output:generated ok" \
  --assert "output-normalized:generated ok"
```

Player events can be injected in the same quick `run` flow and checked with
regular assertions; use `--event-file` for one event per line from generator
output:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --event "player Steve key_input key.jump release" \
  --event-trace-file ./event-trace.jsonl \
  --assert '{"player":{"name":"Steve","lastInput":{"device":"keyboard","code":"key.jump","action":"release"}}}' \
  --assert "event-trace:Steve:key_input=1" \
  --assert '{"eventTrace":{"player":"Steve","type":"key_input","success":true,"count":1}}'
```

World fixtures can reference reusable fixture files with `fixture`, `fixtures`,
or `extends`; referenced files are applied first, then local fields override the
shared setup.

Manifest `steps` can also contain `commands`, `functionText`, or a relative
`mcfunction` path for command-generator output tests; assertions can check
`world`, `team`, `bossbar`, `trace`, whitespace-normalized `output`, and player
inventory `item` results.

Manifest files use the `.dps.json` suffix:

```json
{
  "version": "26.2",
  "unsupported": "warn",
  "packs": ["./packs/counter"],
  "world": {
    "difficulty": "normal",
    "blocks": [
      { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
    ],
    "entities": [
      { "type": "minecraft:pig", "pos": [1, 64, 0], "tags": ["fixture"] }
    ],
    "players": [
      { "name": "Alex", "position": [2, 65, 3], "xp": 5 }
    ],
    "teams": [
      { "name": "red", "members": ["Alex"], "options": { "color": "red" } }
    ],
    "bossbars": [
      { "id": "demo:bar", "name": "Demo", "value": 3, "max": 10 }
    ],
    "scores": [
      { "target": "#fixture", "objective": "ready", "value": 1 }
    ],
    "storage": {
      "demo:env": { "ready": true }
    }
  },
  "steps": [
    { "load": true },
    { "ticks": 20 },
    { "function": "demo:main" }
  ],
  "assertions": [
    {
      "score": {
        "target": "#clock",
        "objective": "ticks",
        "equals": 20
      }
    },
    {
      "world": {
        "difficulty": "normal"
      }
    },
    {
      "output": {
        "command": "tellraw",
        "channel": "chat",
        "target": "Steve",
        "contains": "reward",
        "segment": {
          "color": "yellow"
        },
        "count": 1
      }
    }
  ]
}
```

The same manifest can also run across multiple version profiles:

```json
{
  "versions": ["1.20.4", "26.1.2", "26.2"],
  "packs": {
    "1.20.4": ["./packs/demo-1_20_4"],
    "26.1.2": ["./packs/demo-26_1_2"],
    "26.2": ["./packs/demo-26_2"]
  },
  "steps": [
    { "load": true }
  ],
  "assertions": [
    {
      "score": {
        "target": "#clock",
        "objective": "ticks",
        "equals": 0
      }
    }
  ]
}
```

## Runtime Scope

This is not an embedded vanilla server. It implements a deterministic runtime
for datapack-visible logic:

- functions, load/tick tags, and scheduled functions
- scoreboard, storage, gamerules, time, weather, bossbars, world border, forced
  chunks, biome overrides, and world/player spawn state
- sparse void worlds, explicit block/entity/player fixtures, and selected Java
  Anvil save imports
- readable player NBT, writable non-player entity and block entity NBT validated
  against generated vanilla mcdoc schemas
- selectors including `@s`, `@a`, `@p`, `@e`, `@n`
- predicates, loot tables, advancements, player events, and keyboard/mouse input
  events
- raw JSON registry resources such as recipes, item modifiers, damage types,
  dimensions, worldgen JSON, enchantments, trim resources, and variants
- observable output commands such as `tellraw`, `title`, `say`, `msg`,
  `playsound`, `stopsound`, and `particle`
- configurable unsupported-command policy: `warn` by default, `error` for strict
  validation, `ignore` for silent skipping

The sandbox does not simulate networking, client UI, permissions, chunk
generation, redstone, entity AI, full combat, physics, or the vanilla server
threading model. Entities do not tick AI by default, but the runtime does not
write `NoAI:1b` unless test data does so explicitly.

## Examples

Run all example manifests:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check examples
```

The `examples/` directory covers full-stack datapack events, single-function
scratch tests, command-generator output, and multi-version manifests.

Generate a loot table:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar loot --pack examples/full-stack/pack --table demo:gift --context minecraft:advancement_reward --seed 42
```

Trigger a player event:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar event --pack examples/full-stack/pack player Steve item-used minecraft:carrot_on_a_stick
```

Inspect support boundaries in:

- `docs/command-support.md`
- `docs/code-test-api.md`
- `docs/resource-formats.md`
- `docs/player-events.md`
- `docs/version-profile.md`
- `docs/runtime-world.md`

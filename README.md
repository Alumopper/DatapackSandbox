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
```

The REPL supports TAB completion, live multi-line suggestions while typing,
history suggestions, colored output, Ctrl+C exit, and runtime datapack reloads:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar repl --pack ./my_pack --watch
dps> reload
dps> function demo:main
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

Run JSON check manifests:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases
```

When assertions fail, print the final snapshot or a minimal snapshot diff:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --snapshot-on-fail --snapshot-diff-on-fail
```

Manifest `steps` can also contain `commands`, `functionText`, or a relative
`mcfunction` path for command-generator output tests; assertions can check
`world`, `team`, `bossbar`, `trace`, and player inventory `item` results.

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
- observable output commands such as `tellraw`, `title`, `say`, `msg`,
  `playsound`, `stopsound`, and `particle`
- configurable unsupported-command policy: `warn` by default, `error` for strict
  validation, `ignore` for silent skipping

The sandbox does not simulate networking, client UI, permissions, chunk
generation, redstone, entity AI, full combat, physics, or the vanilla server
threading model. Entities do not tick AI by default, but the runtime does not
write `NoAI:1b` unless test data does so explicitly.

## Full-Stack Example

Run all example manifests:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check examples
```

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

# Datapack Sandbox

[中文文档](README.zh-CN.md)

A lightweight, clean-room Minecraft Java datapack sandbox focused on local CLI
debugging. The first bundled version profile targets Minecraft Java `26.1.2`.

## Build

```bash
./gradlew :cli:fatJar
```

On Windows:

```powershell
.\gradlew.bat :cli:fatJar
```

The build downloads `SpyglassMC/vanilla-mcdoc` and installs the official
`@spyglassmc/mcdoc` parser to generate runtime NBT schemas. `node_modules/` is
local build tooling and is not distributed in the CLI jar.

The standalone jar is written to:

```text
cli/build/libs/datapack-sandbox-cli.jar
```

## CLI Examples

Start a REPL:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar repl --version 26.1.2 --pack ./my_pack
```

The REPL supports TAB completion, live tail-tip suggestions while typing,
command history suggestions, colored output, and runtime reloads:

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

Run JSON check manifests:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases
```

Manifest files use the `.dps.json` suffix:

```json
{
  "version": "26.1.2",
  "unsupported": "warn",
  "packs": ["./packs/counter"],
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
    }
  ]
}
```

## v1 Scope

This is not an embedded vanilla server. It implements a small deterministic
runtime for datapack logic: functions, load/tick tags, scoreboard state, storage,
minimal entities, a default readable player, simple selectors including `@n`,
entity/block/item NBT checked against generated vanilla mcdoc schemas,
predicates, loot tables, advancements, player events, observable output
commands, and configurable handling for unsupported vanilla commands. The
default policy is `warn`, which records a warning output event and continues;
use `--unsupported error` for strict failures or `--unsupported ignore` to
silence those warnings.

Output-oriented commands such as `tellraw`, `title`, `say`, `msg`,
`playsound`, `stopsound`, and `particle` are recorded as sandbox output events.
They do not emulate a real client UI, but they are visible in REPL output,
`run`, `check --verbose`, and snapshots.

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

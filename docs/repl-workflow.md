# Debug with the REPL

## When to use this page

Use the REPL while editing a pack when you need a persistent in-memory world, command completion, quick before/after diffs, or structured inspection. It is ideal for discovering the right reproduction. Once the sequence is stable, move it into a Manifest or QuickTest so CI can repeat it.

## Prerequisites

Prepare at least one datapack directory or zip. `repl` requires one or more `--pack` values and accepts `--version`, `--watch`, and `--unsupported`. It does not expose the full `run` artifact/limit option set.

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar repl `
  --version 26.2 `
  --pack .\my-pack `
  --watch
```

The startup card shows profile, pack count, watch/trace state, game time, players, and entities. The prompt includes active `watch` and `trace` modes.

## Minimal debugging session

```text
status
function demo:main
diff last
inspect scores
inspect outputs
trace on
rerun last
snapshot build/repl-state.json
```

`function demo:main` changes the retained world. `diff last` compares the snapshots immediately before and after that operation. After enabling trace, `rerun last` executes the same world-changing input again and prints the new trace stream.

## Input and completion

The line editor provides history, syntax highlighting, TAB completion, inline hints, and multi-line descriptions. Completion is profile-aware and can use loaded resources and current world data. A line that does not start with a recognized REPL command is treated as a Minecraft command; a leading `/` is optional.

| Command | Purpose |
| --- | --- |
| `help` / `help <command>` | List commands or show focused syntax |
| `status` | Redraw the session dashboard |
| `load` | Run `#minecraft:load` |
| `tick [n]` | Advance the world, default one tick |
| `function <id>` | Run a loaded datapack function |
| `player <name>` | Create a sandbox player |
| `event player ...` | Inject a supported player behavior/input event |
| `load fixture <file>` | Apply a Manifest-style world fixture |
| `reload` | Reload pack resources and keep the world |
| `reset world` | Recreate a fresh sparse world and default `Steve` |
| `trace on|off|status` | Control live command trace printing |
| `diff last` | Show the last world-changing operation's diff |
| `rerun last` | Repeat that operation |
| `snapshot [file]` | Print or save complete state |
| `inspect <kind> ...` | Query modeled world/resources/events |
| `exit` | Close the session |

Use `help inspect` and completion rather than memorizing every inspection argument.

## Reload and watch semantics

`reload` reparses the configured packs and rebuilds the datapack view while retaining the current `SandboxWorld`. `--watch` checks pack modification times before the next line is handled and performs the same reload automatically.

This distinction matters:

- Reloading changes functions/resources but retains scores, storage, blocks, entities, players, time, outputs, and traces.
- `reset world` creates a new world using the same pack configuration.
- A reload error prints a diagnostic and keeps the interactive process alive; correct the file and submit another line.
- Watch mode is demand-driven. It does not execute functions merely because a file changed.

After changing load tags or initialization logic, choose explicitly between `reload` + `load` (retain old state) and `reset world` + `load` (test a clean start).

## Inspect the sandbox

Inspection groups cover:

- `world`: time, weather, difficulty, game mode, border, spawn, and tick state;
- `scores`, `scoreboard`, `teams`, and `bossbars`;
- `storage`, gamerules, scheduled functions, random sequences, and forced chunks;
- blocks, biomes, entities, players, inventory items, recipes, and advancement progress;
- resource index, active/overridden resources, registries, and raw registry entries;
- outputs and player-event traces.

Prefer a narrow query such as one player, resource id, or registry group when a pack is large. Resource inspection exposes override order and missing references, which often explains why a function or predicate appears absent.

## Trace, diff, and rerun

Trace printing affects subsequent operations; existing traces remain in the world record. Each trace carries command/root, source location when available, success or diagnostic, command count, emitted outputs, and snapshot diffs.

`diff last` and `rerun last` refer to the most recent world-changing REPL input, not the most recent inspection/help command. Rerunning is intentionally not a rollback: it applies the operation again to current state. Save a snapshot for evidence, or use a checkpoint-capable host/Manifest when a clean baseline is required.

## Load fixtures interactively

```text
load fixture fixtures/arena.json
player Alex
function demo:setup
inspect player Alex
inspect block 0 64 0
```

Fixture files use Manifest world syntax and may set players, blocks, entities, scores, storage, time, teams, bossbars, and other sparse state. Relative paths inside the fixture follow the fixture's own base. For a documented catalog, see [QuickTest Fixtures](/en/reference/quicktest-fixtures).

## A practical edit loop

1. Start with `--watch`, execute the narrowest function, and inspect its direct result.
2. Enable trace only when the state is wrong or a call chain is unclear.
3. Use `diff last` to isolate changed JSON paths and `inspect outputs` for user-visible behavior.
4. Fix the pack; the next submitted line triggers watch reload.
5. Use `reset world` before validating initialization from scratch.
6. Transfer the final fixture, steps, and assertions to a `.dps.json` file.

## Limitations

- Watch mode uses modification times, not transactional filesystem snapshots; a multi-file save may be observed between editor writes.
- The REPL does not persist its world after process exit unless you explicitly save a snapshot, and a snapshot is evidence rather than a vanilla save.
- `rerun last` can accumulate state and is not equivalent to test isolation.
- Interactive console text is not a machine protocol. Use `run`, `check`, or `serve` for automation.

## Related pages

- [Run with the CLI](/en/workflows/cli)
- [Manifest Regression Tests](/en/workflows/manifest-tests)
- [Command Support](/en/runtime/command-support)
- [World Model](/en/runtime/world-model)
- [Reports and Observability](/en/reference/reports-observability)

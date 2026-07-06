# Command Support

Default profile: Minecraft Java `26.2`. Compatibility profiles are available
down to `1.20.4`.

This sandbox does not embed a vanilla server. Command support means "the
datapack-visible state that this clean-room runtime models is updated
deterministically." Network, permissions, world generation, client UI, redstone,
entity AI, and full combat systems remain outside the runtime boundary.

Unsupported vanilla root commands do not fail by default. The default
unsupported policy is `warn`: the command records a warning output event and the
run continues. Use `--unsupported error`, manifest `"unsupported": "error"`, or
`UnsupportedFeatureMode.ERROR` when strict validation is required. For
generator-output checks, `run --strict` and `check --strict` combine unsupported
errors with missing resource reference failures; `check --strict` also validates
manifest schema before execution.

## Status Meanings

| Status | Meaning |
|---|---|
| Supported | Main sandbox-visible behavior is implemented. |
| Partial | Useful datapack test behavior is implemented, but vanilla side effects are incomplete. |
| No-op | Accepted and recorded, but no mutable vanilla-equivalent state exists in the sandbox. |
| Unsupported | Not implemented; handled by the unsupported policy. |

## Behavior Levels

| Level | Meaning |
|---|---|
| `exact` | The documented surface is intended to match vanilla-observable behavior. |
| `modeled` | The sandbox uses a deterministic clean-room model for datapack-visible behavior. |
| `observed-noop` | The command is accepted and produces output or diagnostics, but real side effects are intentionally absent. |
| `unsupported` | The command is routed through the configured unsupported policy. |

## Vanilla Command Matrix

| Command | Status | Behavior | Supported forms / sandbox behavior |
|---|---:|---:|---|
| `advancement` | Partial | `modeled` | `grant`, `revoke`, `test`; `test` records passed counts and per-player result payloads; progress is per player; rewards support functions, loot, XP, and recipes. |
| `attribute` | Partial | `modeled` | `get`, `base get`, `base set`, `base reset`; get commands record structured data output for assertions and `execute store result`; modifier subcommands are accepted as no-op warning output. |
| `ban`, `ban-ip`, `banlist` | Unsupported | `unsupported` | Server administration and ban lists are not simulated. |
| `bossbar` | Partial | `modeled` | `add`, `remove`, `list`, `get`, `set`; mutations and `get` record structured data output for assertions and `execute store result`; state is stored and appears in snapshots, no real client UI. |
| `clear` | Partial | `modeled` | Removes matching item stacks from sandbox player inventories, including JSON/SNBT-lite NBT and component payload filters; records the matched/removed count, and treats `maxCount=0` as a query-only check. |
| `clone` | Partial | `modeled` | Copies sparse sandbox block state/NBT and records structured copied/changed-position output; no updates, drops, or overlap physics. |
| `damage` | Partial | `modeled` | Reduces entity/player health, emits sandbox damage/death advancement events, and records structured health-change output; no armor, invulnerability, death loot, or combat rules. |
| `data` | Partial | `modeled` | `get` with optional numeric scale, `merge`, `modify`, `remove` for `storage`, `entity`, and `block`; write operations record structured before/after output; paths support fields, positive/negative numeric indexes, and simple object matchers; `modify` supports `value`, `from`, and `string` sources; top-level NBT is schema-checked. |
| `datapack` | Partial | `modeled` | `list` reports loaded typed/raw/tag/resource-index counts plus resource overlay diagnostics; `enable`/`disable` are accepted as no-op because pack order is fixed at sandbox creation. |
| `debug`, `jfr`, `perf` | Unsupported | `unsupported` | Profiling commands do not apply to this runtime. |
| `defaultgamemode` | Supported | `modeled` | Stores world default game mode and records structured before/after output. |
| `difficulty` | Supported | `modeled` | Stores and reports world difficulty with structured before/after output. |
| `deop`, `op` | Unsupported | `unsupported` | Permission system is not simulated. |
| `effect` | Partial | `modeled` | `give`, `clear`; updates player effect state with advancement events and non-player entity active effects visible through snapshot and `ActiveEffects` NBT; records structured output for reports/assertions. |
| `enchant` | Partial | `modeled` | Writes enchantment components to player selected items and non-player mainhand equipment, with structured output for reports/assertions; no enchantability checks. |
| `execute` | Partial | `modeled` | `as`, `at`, `positioned <pos>`, `positioned as <selector>`, `align`, `anchored`, `facing`, `in`, `rotated`, `store`, `if`, `unless`, `run`; `as` changes only the executor, `at` moves execution position/dimension/rotation to the target, and `positioned as` moves only the execution position; `align` floors validated `x`/`y`/`z` axes; `rotated` and `facing` update the command rotation context used by relative `tp` rotations and local coordinates; `anchored` updates the local-coordinate base; `store` targets score, storage, entity NBT, block NBT, and bossbar value/max; conditions support `entity`, `score`, `data`, `block`, `blocks`, `predicate`, `function`, `dimension`, `biome`, and `loaded`. |
| `experience`, `xp` | Partial | `modeled` | `add`, `set`, `query`; points and levels share the sandbox XP integer; `query` records structured data output for assertions and `execute store result`. |
| `fill` | Partial | `modeled` | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`; records structured changed-position output; position arguments accept local coordinates; no updates/drops. |
| `fillbiome` | Partial | `modeled` | Stores biome overrides for explicit block ranges and records structured changed-position output; no chunk biome container or generation effects. |
| `forceload` | Partial | `modeled` | `add`, `remove`, `remove all`, `query`, `query <pos>`; stores forced chunk coordinates and records structured mutation/query output. |
| `function` | Supported | `modeled` | `function <id>`. |
| `gamemode` | Supported | `modeled` | `gamemode <mode> [targets]`; updates sandbox player game mode and records structured before/after output. |
| `gamerule` | Partial | `modeled` | Stores arbitrary gamerule string values and records structured mutation/query output; no gameplay side effects. |
| `give` | Partial | `modeled` | Adds item stacks to player inventories, records structured output for reports/assertions, and fires inventory advancement events; item arguments accept sandbox JSON/SNBT-lite NBT and component payloads. |
| `help` | Partial | `modeled` | Reports command roots and basic sandbox help text. |
| `item` | Partial | `modeled` | `replace entity|block ... with <item> [count]` and `from entity|block ...`; `replace` and `modify` record structured output for reports/assertions; item arguments accept sandbox JSON/SNBT-lite NBT and component payloads; entity slots cover player inventory/selected-mainhand/`enderchest.*` slots and non-player equipment slots; `modify entity|block ... <modifier>` applies common item modifier functions (`set_components`, `set_custom_data`, `set_count`, `limit_count`, `set_item`, `discard`, `set_damage`, `set_name`, `set_lore`, `filtered`, `reference`, `sequence`). |
| `kick` | Unsupported | `unsupported` | Network sessions are not simulated. |
| `kill` | Supported | `modeled` | Removes selected sandbox entities, records structured target output for reports/assertions, and player execution contexts fire `killed_entity` advancement events for non-player targets. |
| `list` | Supported | `modeled` | Reports sandbox players and UUIDs. |
| `locate` | Partial | `modeled` | Accepts `biome`, `structure`, `poi`; reports no result in the void world instead of querying worldgen. |
| `loot` | Partial | `modeled` | Supports `give`, `insert`, `spawn`, `replace entity`, `replace block`, with structured loot output for reports/assertions; `spawn` creates item entities in the current execution dimension; `replace entity` writes player inventory/selected-mainhand/`enderchest.*` slots and non-player equipment slots; sources include `loot <table>`, `fish <table> <pos> [tool]`, `mine <pos> [tool]`, `kill <target>` when entities declare `DeathLootTable`, plus sandbox context sources `entity <table> <target>`, `block <table> <pos> [tool]`, and `equipment <table> <target> <slot>`; common functions include count, item id, discard, components/custom data, damage, name, and lore. |
| `me` | Supported | `modeled` | Recorded as chat output. |
| `msg`, `tell`, `w` | Supported | `modeled` | Recorded as private chat output. |
| `pardon`, `pardon-ip` | Unsupported | `unsupported` | Server administration is not simulated. |
| `particle` | Partial | `observed-noop` | Recorded as visual output event; no client particles. |
| `place` | Partial | `observed-noop` | Accepts `feature`, `jigsaw`, `structure`, and `template`; records kind, resource id, position, and extra placement arguments as structured worldgen output, but does not mutate the world. |
| `playsound` | Partial | `observed-noop` | Recorded as sound output event. |
| `publish` | Unsupported | `unsupported` | LAN/networking is not simulated. |
| `random` | Partial | `modeled` | `value`, `roll`, `reset`; deterministic sandbox sequence state seeded from the world seed unless explicitly reset. |
| `recipe` | Partial | `modeled` | `give`, `take`; supports `*` for loaded datapack recipes, updates per-player recipe sets, and records changed counts. |
| `reload` | No-op | `observed-noop` | Accepted and recorded; REPL `reload` performs real datapack reload, vanilla command does not mutate this immutable sandbox instance. |
| `return` | Supported | `modeled` | Stops the current function; supports `return <value>`, `return fail`, and `return run <command>` for function conditions and store result tests. |
| `ride` | Partial | `modeled` | Tracks vehicle/passenger relationships and records structured mount/dismount output; no physics/control. |
| `rotate` | Partial | `modeled` | Updates yaw/pitch and records structured before/after rotation output. |
| `save-all`, `save-off`, `save-on` | Unsupported | `unsupported` | No real world save lifecycle exists. |
| `say` | Supported | `modeled` | Recorded as chat output. |
| `schedule` | Partial | `modeled` | `schedule function <id> <time> [append|replace]`, `schedule clear <id>`; records structured scheduling and clearing output. |
| `scoreboard` | Partial | `modeled` | Objectives `add`, `remove`, `list`; players `set`, `add`, `remove`, `get`, `reset`, `list`, `enable`, `operation`; `players get` records a structured data output for assertions and `execute store result`. |
| `seed` | Supported | `modeled` | Reports deterministic sandbox seed. |
| `setblock` | Partial | `modeled` | Mutates sparse block state/NBT and records structured before/after block output; position arguments accept local coordinates; no neighbor updates. |
| `setidletimeout` | Unsupported | `unsupported` | Server administration command. |
| `setworldspawn` | Partial | `modeled` | Stores sandbox world spawn position/angle and records structured spawn output. |
| `spawnpoint` | Partial | `modeled` | Stores per-player spawn point/angle and records structured target output. |
| `spectate` | Partial | `modeled` | Sets spectator mode and records target; no camera/client state. |
| `spreadplayers` | Partial | `modeled` | Deterministically distributes selected entities around a center; no collision/team algorithm. |
| `stop` | Unsupported | `unsupported` | Runtime lifecycle is controlled by the host process, not commands. |
| `stopsound` | Partial | `observed-noop` | Recorded as sound output event. |
| `summon` | Partial | `modeled` | Creates entities in the current execution dimension with position, tags, schema-checked NBT, and structured creation output for reports/assertions; AI does not tick. |
| `tag` | Supported | `modeled` | `add`, `remove`, `list`. |
| `team` | Partial | `modeled` | `add`, `remove`, `list`, `join`, `leave`, `empty`, `modify`; records structured team/member/option output and has no gameplay effects. |
| `teammsg`, `tm` | Supported | `modeled` | Recorded as team chat output. |
| `teleport`, `tp` | Partial | `modeled` | Coordinate teleport supports local coordinates, optional rotation, `facing`, and the current execution dimension; destination-entity teleport copies destination position, dimension, and rotation; records structured movement output for reports/assertions. |
| `tellraw` | Supported | `modeled` | Resolves JSON text components into output events. |
| `tick` | Partial | `modeled` | `query`, `rate`, `freeze`, `unfreeze`, `step`, `sprint`, `stop`; updates sandbox tick state, can advance ticks, and records structured state/advance output for debugging. |
| `time` | Partial | `modeled` | `set`, `add`, `query daytime|gametime|day`; mutations and queries record structured data output for assertions and `execute store result`. |
| `title` | Supported | `modeled` | `clear`, `reset`, `title`, `subtitle`, `actionbar`, `times` output events. |
| `transfer` | Unsupported | `unsupported` | Networking/server transfer is not simulated. |
| `trigger` | Partial | `modeled` | `trigger <objective> [add|set] [value]`; uses current/default sandbox player. |
| `weather` | Partial | `modeled` | `clear`, `rain`, `thunder`; stores state and records structured weather output. |
| `whitelist` | Unsupported | `unsupported` | Server administration command. |
| `worldborder` | Partial | `modeled` | `get`, `set`, `add`, `center`, `damage`, `warning`; stores state and records structured mutation/query output for assertions. |

## Text And Output Commands

Output commands are deterministic `OutputEvent`s. They appear in snapshots,
REPL output, `run`, `check --verbose`, and the code test API:

- chat: `say`, `me`, `msg`, `tell`, `w`, `teammsg`, `tm`, `tellraw`
- title: `title`
- sound: `playsound`, `stopsound`
- visual: `particle`
- data: structured state and query outputs from modeled commands
- debug: manifest and tooling helper outputs
- worldgen: `place`
- warning: unsupported or no-op command notices

JSON text components support `text`, `score`, `selector`, `translate`,
`keybind`, basic `nbt`, `extra`, and common formatting flags. The sandbox stores
both plain text and segment metadata.

## Selectors

Implemented selectors: `@s`, `@a`, `@p`, `@e`, `@n`.

Common options: `type`, `tag`, `name`, `limit`, `sort`, `distance`, `x`, `y`,
`z`, `dx`, `dy`, `dz`. Unsupported selector options produce an unsupported
diagnostic under the active unsupported policy.

## World/NBT Notes

- The initial world is sparse void.
- Blocks exist only when explicitly placed or imported from a save fixture.
- Block and entity NBT writes are validated against generated vanilla mcdoc
  schemas; unknown top-level custom fields fail.
- Player NBT is readable but not writable through `data`; the view includes the
  current non-empty mainhand `SelectedItem`; use commands/events to change
  player state.
- Entity AI, gravity, redstone, block updates, loot drops from block breaking,
  and real combat are not simulated.

## Sandbox-Only CLI/REPL Commands

These are tooling commands, not vanilla commands:

| Command | Purpose |
|---|---|
| `event player <name> <type> ...` | Inject player events for advancements/predicates. |
| `player <name>` | Create or reuse a sandbox player. |
| `inspect <...>` | Inspect score, storage, entities, blocks, player, loot, predicate, advancement, recipe, item_modifier, raw JSON resources, tags, resource index, registry, outputs. |
| `snapshot [file]` | Print or write deterministic world JSON. |
| `reload` | REPL-only datapack reload while preserving world state. |
| `load` | Run `#minecraft:load` in REPL. |
| `load fixture <file>` | Apply a manifest-style world JSON fixture in REPL. |
| `tick [n]` | Advance sandbox ticks in REPL. |
| `trace <on|off|status>` | Toggle automatic REPL trace printing for newly executed commands. |
| `diff last` | Print the before/after snapshot diff for the last tracked REPL command. |
| `rerun last` | Re-execute the last tracked REPL command. |
| `reset world` | Replace the current REPL world with a fresh sparse world. |
| CLI `loot --table <id> --context <context>` | Generate a loot table directly. |
| CLI `run --trace --trace-filter <filter>` | Print or write only matching trace events; filters support `root=`, `command=`, `contains=`, `function=`, `file=`, `selector=`/`target=`, `success=`, `output=` text, `outputs=` count/boolean, `diff=`, `path=`, `score=`, and `storage=`. Trace JSONL entries include per-command output events and snapshot diffs for state changed by that command. |
| CLI `run --outputs-file <file>` | Write observable output events as JSONL for CI artifacts or generated-command regression tests. |
| CLI `run --report-file <file>` | Write a combined JSON report with pass/fail status, assertion failures, outputs, traces, event traces, the final snapshot, snapshot diffs, and resource summary details. |
| CLI `run --resources` | Print deterministic resource counts, overlay diagnostics, and missing direct resource references for quick pack checks. |
| CLI `run --snapshot-diff` | Print before/after state changes; use `--snapshot-diff-file` to write JSON. |
| CLI `run --stdin` | Read `.mcfunction` text from standard input; `--stdin-mode commands` executes stdin as raw command lines. |
| CLI `run --command-file <file>` | Execute one or more raw command files in argument order, useful for command-generator output. |
| CLI `run --event "<event>"` | Inject player events in quick runs using `player <name> <type> [id] [detail/action]`, then assert player state or `eventTrace`. |
| CLI `run --event-file <file>` | Inject one player event per non-empty, non-comment line, using the same `--event` shorthand. |
| CLI `run --event-trace-file <file>` | Write player event trace JSONL for event-driven datapack debugging and CI artifacts. |
| CLI `run --seed <long>` | Override the quick-run world seed after world fixtures are applied. |
| CLI `run --world` | Apply a manifest-style world JSON fixture, including fixture references, before execution. |
| CLI `run --strict` | Treat unsupported vanilla commands as errors and fail the run on direct missing resource references, without needing separate `--unsupported error` and `--fail-on-missing-resources` flags. |
| CLI `run --assert`, `run --assert-file` | Evaluate inline or file-backed manifest assertions after execution, including before/after `snapshotDiff` assertions. `--assert-file` accepts JSON object/array files or one shorthand per non-empty, non-comment line. Shorthands include `score:<target>:<objective>=N`, `score:<target>:<objective>>=N`, `score:<target>:<objective><=N`, `storage:<id>[:<path>]=<json>`, `storage:<id>[:<path>]?`, `storage:<id>[:<path>]!`, `advancement:<player>:<id>[=<true\|false>]`, `player:<name>[:<field>=<value>]`, `item:<player>:<id>[@slot]=N`, `entity:<type|*>[@tag]=N`, `diff:<json-pointer>[=<kind>]`, `event-trace:<player>:<type>[=N]`, `trace:<root>=N`, `trace:<text>`, `trace-output:<text>[@target]`, `warning=N`, `warning:<text>`, `unsupported=N`, `unsupported:<text>`, `output:<text>`, and `output-normalized:<text>`. |
| CLI `run --fail-on-missing-resources` | Fail a quick run when direct load/tick tag, advancement parent/reward, predicate references in predicate/loot/item modifier resources, or nested loot table references point at missing resources, useful before creating a full manifest. |
| CLI `check <manifest-or-directory>` | Run `.dps.json` manifests; `--validate-schema` checks manifest structure before execution; `--fail-on-missing-resources` turns direct missing resource references into failures; `--strict` combines schema validation, unsupported-command errors, and direct missing-reference failures; `--verbose` prints resource summaries, overlay entries, missing references, and output events; use `--snapshot-diff-on-fail` for state changes, plus `--trace-file`, `--trace-filter`, `--outputs-file`, `--event-trace-file`, and `--report-file` for CI artifacts. Report JSON includes output, command trace, player event trace, final snapshot, snapshot diff, and resource summary details per attempt. |
| CLI `schema [--output <file>]` | Print or write the bundled `.dps.json` manifest JSON Schema for editor and CI integration. |

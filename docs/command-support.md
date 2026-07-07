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

The CLI can export the version-scoped command catalog for scripts, docs, and CI:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar commands
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --docs
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --docs --output docs/command-catalog.md
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --check docs/command-support.md
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --check docs/command-support.zh-CN.md
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --json --version 26.2
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --json --output build/command-catalog.json --version 26.2
```

`commands --check` verifies that each version-scoped root command appears in
the document with the matching behavior level. The Gradle `check` lifecycle
runs both English and localized docs checks through standalone jar smoke tasks.

## Vanilla Command Matrix

| Command | Status | Behavior | Supported forms / sandbox behavior |
|---|---:|---:|---|
| `advancement` | Partial | `modeled` | `grant`, `revoke`, `test`; `grant`/`revoke` support `only`, `from`, `through`, `until`, and `everything`, record structured changed-criterion output, and can feed `execute store result`; `test` records passed counts and per-player result payloads; progress is per player; rewards support functions, loot, XP, and recipes. |
| `attribute` | Partial | `modeled` | `get`, `base get`, `base set`, `base reset`, `modifier add`, `modifier remove`, and `modifier value get`; get commands record structured data output for assertions and `execute store result`; modifier state is exposed in snapshots and entity NBT. |
| `ban`, `ban-ip`, `banlist` | No-op | `observed-noop` | Records requested ban target/IP, reason, or list filter as structured debug output; no ban list state is stored. |
| `bossbar` | Partial | `modeled` | `add`, `remove`, `list`, `get`, `set`; mutations and `get` record structured data output for assertions and `execute store result`; state is stored and appears in snapshots, no real client UI. |
| `clear` | Partial | `modeled` | Removes matching item stacks from sandbox player inventories, including JSON/SNBT-lite NBT and component payload filters; records the matched/removed count, and treats `maxCount=0` as a query-only check. |
| `clone` | Partial | `modeled` | Copies sparse sandbox block state/NBT and records structured copied/changed-position output; no updates, drops, or overlap physics. |
| `damage` | Partial | `modeled` | Reduces entity/player health, supports `at`, `by`, and `from` context in structured output, exposes loaded `damage_type` JSON metadata for custom damage sources, emits sandbox damage/death advancement events, and records health changes; no armor, invulnerability, death loot, or combat rules. |
| `data` | Partial | `modeled` | `get` with optional numeric scale, `merge`, `modify`, `remove` for `storage`, `entity`, and `block`; write operations record structured before/after output; paths support fields, positive/negative numeric indexes, and simple object matchers; `modify` supports `value`, `from`, and `string` sources; append/prepend/insert reject existing non-list targets instead of overwriting them; top-level NBT is schema-checked. |
| `datapack` | Partial | `modeled` | `list [available|enabled]` reports loaded pack paths, typed/raw/tag/resource-index counts, resource overlay diagnostics, and missing-reference diagnostics in a structured payload; `enable`/`disable` are accepted as no-op because pack order is fixed at sandbox creation and record the requested pack name/order arguments for assertions. |
| `debug`, `jfr`, `perf` | No-op | `observed-noop` | Accept action/argument tokens and record structured debug output; profiling and flight recording are not simulated. |
| `defaultgamemode` | Supported | `modeled` | Stores world default game mode and records structured before/after output. |
| `difficulty` | Supported | `modeled` | Stores and reports world difficulty with structured before/after output. |
| `deop`, `op` | No-op | `observed-noop` | Records requested permission target as structured debug output; no permission state is stored. |
| `effect` | Partial | `modeled` | `give`, `clear`; updates player effect state with advancement events and non-player entity active effects visible through snapshot and `ActiveEffects` NBT; records structured output for reports/assertions. |
| `enchant` | Partial | `modeled` | Writes enchantment components to player selected items and non-player mainhand equipment, exposes loaded `enchantment` JSON metadata in structured output when present, and records modified items for reports/assertions; no enchantability checks. |
| `execute` | Partial | `modeled` | `as`, `at`, `positioned <pos>`, `positioned as <selector>`, `align`, `anchored`, `facing`, `in`, `rotated`, `store`, `if`, `unless`, `run`; `as` changes only the executor, `at` moves execution position/dimension/rotation to the target, and `positioned as` moves only the execution position; `align` floors validated `x`/`y`/`z` axes; `rotated` and `facing` update the command rotation context used by relative `tp` rotations and local coordinates; `anchored` updates the local-coordinate base; `store` targets score, storage, entity NBT, block NBT, and bossbar value/max, honors byte/short/int/long/float/double type plus scale for NBT targets with integer narrowing behavior, with nested condition failure and `return fail` stored as success/result `0`; conditions support `entity`, `score`, `data`, `block`, `blocks`, `predicate`, `function`, `dimension`, `biome`, and `loaded`. |
| `experience`, `xp` | Partial | `modeled` | `add`, `set`, `query`; points and levels are stored separately on players; mutation and query commands record structured data output for assertions and `execute store result`. |
| `fill` | Partial | `modeled` | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`; records structured changed-position output; position arguments accept local coordinates; no updates/drops. |
| `fillbiome` | Partial | `modeled` | Stores biome overrides for explicit block ranges and records structured changed-position output; the same explicit overrides are visible to `execute if biome` and predicate `location_check` biome tests; no chunk biome container or generation effects. |
| `forceload` | Partial | `modeled` | `add`, `remove`, `remove all`, `query`, `query <pos>`; stores forced chunk coordinates and records structured mutation/query output. |
| `function` | Supported | `modeled` | `function <id>`. |
| `gamemode` | Supported | `modeled` | `gamemode <mode> [targets]`; updates sandbox player game mode and records structured before/after output. |
| `gamerule` | Partial | `modeled` | Stores arbitrary gamerule string values and records structured mutation/query output; no gameplay side effects. |
| `give` | Partial | `modeled` | Adds item stacks to player inventories, records structured output for reports/assertions, exposes loaded equipment asset, banner pattern, instrument, jukebox song, and armor trim material/pattern metadata for matching item components when present, and fires inventory advancement events; item arguments accept sandbox JSON/SNBT-lite NBT and component payloads. |
| `help` | Partial | `modeled` | Reports command roots and basic sandbox help text. |
| `item` | Partial | `modeled` | `replace entity|block ... with <item> [count]` and `from entity|block ...`; `replace` and `modify` record structured output for reports/assertions, including loaded equipment asset, banner pattern, instrument, jukebox song, and armor trim material/pattern metadata for matching item components when present; item arguments accept sandbox JSON/SNBT-lite NBT and component payloads; container item-stack NBT validation accepts legacy/current `Count`/`count` and `Slot`/`slot` aliases; entity slots cover player inventory/selected-mainhand/`enderchest.*` slots and non-player equipment slots; `modify entity|block ... <modifier>` applies common item modifier functions (`set_components`, `set_custom_data`, `set_count`, `limit_count`, `set_item`, `discard`, `set_damage`, `set_name`, `set_lore`, `copy_nbt`, `copy_components`, `filtered`, `reference`, `sequence`). |
| `kick` | No-op | `observed-noop` | Records requested kick target and message as structured debug output; no network session is removed. |
| `kill` | Supported | `modeled` | Removes selected sandbox entities, records structured target output for reports/assertions, exposes loaded dimension metadata for target dimensions when present, and player execution contexts fire `killed_entity` advancement events for non-player targets. |
| `list` | Supported | `modeled` | Reports sandbox players and UUIDs. |
| `locate` | Partial | `modeled` | Accepts `biome`, `structure`, `poi`; reports no result in the void world instead of querying worldgen. |
| `loot` | Partial | `modeled` | Supports `give`, `insert`, `spawn`, `replace entity`, `replace block`, with structured loot output for reports/assertions; `spawn` creates item entities in the current execution dimension and exposes loaded dimension metadata when present; `replace entity` writes player inventory/selected-mainhand/`enderchest.*` slots and non-player equipment slots; sources include `loot <table>`, `fish <table> <pos> [tool]`, `mine <pos> [tool]`, `kill <target>` when entities declare `DeathLootTable`, plus sandbox context sources `entity <table> <target>`, `block <table> <pos> [tool]`, and `equipment <table> <target> <slot>`; entries include item, nested loot table, groups, alternatives, sequences, and item tags with nested/optional tag values where `expand=false` emits the whole tag and `expand=true` selects expanded tag items; common functions include count, item id, discard, components/custom data, copied tool components, entity name copy, deterministic enchantment components, tool-enchantment bonus counts, damage, name, and lore. |
| `me` | Supported | `modeled` | Recorded as chat output; loaded `chat_type` JSON metadata is exposed when the modeled command chat type is present. |
| `msg`, `tell`, `w` | Supported | `modeled` | Recorded as private chat output; loaded `chat_type` JSON metadata is exposed when the modeled command chat type is present. |
| `pardon`, `pardon-ip` | No-op | `observed-noop` | Records requested pardon target/IP as structured debug output; no ban list state is stored. |
| `particle` | Partial | `observed-noop` | Recorded as visual output event; no client particles. |
| `place` | Partial | `modeled` | `place structure <id> [pos]` and `place template <id> [pos] [rotation] [mirror] [integrity] [seed]` apply loaded sandbox structure JSON resources (`worldgen/structure` with `blocks`/`entities` or palette-style blocks) and binary structure NBT resources from `worldgen/structure`, `structure`, or `structures` directories to the sparse world and record changed blocks/entities; binary NBT resources expose `sourceFormat=binary-structure-nbt` in structured output. Structure JSON can reference `worldgen/processor_list` resources for `block_ignore`, `protected_blocks`, `jigsaw_replacement`, `capped`, `nop`, and rule replacement processors with block/tag predicates. Template placement supports deterministic `none`/90-degree rotations, `front_back`/`left_right` mirroring, and integrity filtering. `place jigsaw <pool> <target> <maxDepth> [pos]` resolves loaded `worldgen/template_pool` single/legacy/list/feature elements, follows fallback pools when the selected pool has no supported element, applies element processors, and places the selected structure or feature. `place feature <id> [pos]` resolves loaded `worldgen/placed_feature`/`worldgen/configured_feature` simple_block, block_column, deterministic disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/replacement/selector/random_patch/flower JSON, and sparse-world ore targets, placing or replacing one or more blocks around the command position. Missing or unsupported resources still record structured worldgen intent with `placed=false`. |
| `playsound` | Partial | `observed-noop` | Recorded as sound output event. |
| `publish` | No-op | `observed-noop` | Accepts `allowCommands`, `gamemode`, and `port`, records the requested LAN publish settings as structured debug output, and performs no network publishing. |
| `random` | Partial | `modeled` | `value`, `roll`, `reset`; deterministic sandbox sequence state seeded from the world seed unless explicitly reset; value/roll/reset record structured sequence-state output for assertions and `execute store result`. |
| `recipe` | Partial | `modeled` | `give`, `take`; supports `*` for loaded datapack recipes, updates per-player recipe sets, and records changed counts. |
| `reload` | No-op | `observed-noop` | Accepted and recorded with structured no-op payload; REPL `reload` performs real datapack reload, vanilla command does not mutate this immutable sandbox instance. |
| `return` | Supported | `modeled` | Stops the current function; supports `return <value>`, `return fail`, and `return run <command>` for function conditions and store result tests. |
| `ride` | Partial | `modeled` | Tracks vehicle/passenger relationships and records structured mount/dismount output; no physics/control. |
| `rotate` | Partial | `modeled` | Updates yaw/pitch and records structured before/after rotation output. |
| `save-all`, `save-off`, `save-on` | No-op | `observed-noop` | Records requested save lifecycle action, including `save-all flush`, as structured debug output; no filesystem save mode changes occur. |
| `say` | Supported | `modeled` | Recorded as chat output; loaded `chat_type` JSON metadata is exposed when the modeled command chat type is present. |
| `schedule` | Partial | `modeled` | `schedule function <id> <time> [append|replace]`, `schedule clear <id>`; records structured scheduling and clearing output. |
| `scoreboard` | Partial | `modeled` | Objectives `add`, `remove`, `list`, `modify`, `setdisplay`; `modify` tracks display name, render type, and display-auto-update metadata, display slots are stored in snapshots, and mutations record structured output; players `set`, `add`, `remove`, `get`, `reset`, `list`, `enable`, `operation`; `players get` records a structured data output for assertions and `execute store result`. |
| `seed` | Supported | `modeled` | Reports deterministic sandbox seed. |
| `setblock` | Partial | `modeled` | Mutates sparse block state/NBT and records structured before/after block output; position arguments accept local coordinates; no neighbor updates. |
| `setidletimeout` | No-op | `observed-noop` | Validates and records requested idle timeout minutes as structured debug output; no player idle enforcement is simulated. |
| `setworldspawn` | Partial | `modeled` | Stores sandbox world spawn position/angle and records structured spawn output with loaded dimension metadata when present. |
| `spawnpoint` | Partial | `modeled` | Stores per-player spawn point/angle and records structured target output with loaded dimension metadata when present. |
| `spectate` | Partial | `modeled` | Sets spectator mode and records target; no camera/client state. |
| `spreadplayers` | Partial | `modeled` | Deterministically distributes selected entities around a center; no collision/team algorithm. |
| `stop` | No-op | `observed-noop` | Records a structured debug lifecycle request; the host process remains in control and is not stopped by sandbox commands. |
| `stopsound` | Partial | `observed-noop` | Recorded as sound output event. |
| `summon` | Partial | `modeled` | Creates entities in the current execution dimension with position, tags, schema-checked NBT, structured creation output, loaded dimension/dimension_type metadata, and loaded entity variant metadata for cat/chicken/cow/frog/painting/pig/wolf variants when present; AI does not tick. |
| `tag` | Supported | `modeled` | `add`, `remove`, `list`. |
| `team` | Partial | `modeled` | `add`, `remove`, `list`, `join`, `leave`, `empty`, `modify`; records structured team/member/option output and has no gameplay effects. |
| `teammsg`, `tm` | Supported | `modeled` | Recorded as team chat output; loaded `chat_type` JSON metadata is exposed when the modeled command chat type is present. |
| `teleport`, `tp` | Partial | `modeled` | Coordinate teleport supports local coordinates, optional rotation, `facing`, and the current execution dimension; destination-entity teleport copies destination position, dimension, and rotation; records structured movement output with loaded from/to dimension metadata when present. |
| `tellraw` | Supported | `modeled` | Resolves JSON text components into output events. |
| `tick` | Partial | `modeled` | `query`, `rate`, `freeze`, `unfreeze`, `step`, `sprint`, `stop`; updates sandbox tick state, can advance ticks, and records structured state/advance output for debugging. |
| `time` | Partial | `modeled` | `set`, `add`, `query daytime|gametime|day`; mutations and queries record structured data output for assertions and `execute store result`. |
| `title` | Supported | `modeled` | `clear`, `reset`, `title`, `subtitle`, `actionbar`, `times` output events. |
| `transfer` | Partial | `observed-noop` | Records requested host, port, target players, and accepted syntax as structured debug output; no network/server transfer is performed. |
| `trigger` | Partial | `modeled` | `trigger <objective> [add|set] [value]`; uses current/default sandbox player. |
| `weather` | Partial | `modeled` | `clear`, `rain`, `thunder`; stores state and records structured weather output. |
| `whitelist` | No-op | `observed-noop` | Accepts `add`, `remove`, `list`, `on`, `off`, and `reload`, records the requested whitelist action as structured debug output, and stores no whitelist state. |
| `worldborder` | Partial | `modeled` | `get`, `set`, `add`, `center`, `damage`, `warning`; stores state and records structured mutation/query output for assertions. |

## Text And Output Commands

Output commands are deterministic `OutputEvent`s. They appear in snapshots,
REPL output, `run`, `check --verbose`, and the code test API:

- chat: `say`, `me`, `msg`, `tell`, `w`, `teammsg`, `tm`, `tellraw`
- title: `title`
- sound: `playsound`, `stopsound`
- visual: `particle`
- data: structured state and query outputs from modeled commands
- debug: manifest/tooling helper outputs plus profiling/network/lifecycle/server-admin observed-noop requests such as `debug`, `jfr`, `perf`, `transfer`, `publish`, `stop`, `ban`, and `whitelist`
- worldgen: `place`
- warning: unsupported or no-op command notices

JSON text components support `text`, `score`, `selector`, `translate`,
`keybind`, basic `nbt`, `extra`, and common formatting flags. The sandbox stores
both plain text and segment metadata.

## Selectors

Implemented selectors: `@s`, `@a`, `@p`, `@e`, `@n`.

Implemented options: `type`, `tag`, `name`, `gamemode`, `team`, `nbt`,
`predicate`, `scores`, `advancements`, `level`, `x_rotation`, `y_rotation`,
`limit`, `sort`, `distance`, `x`, `y`, `z`, `dx`, `dy`, `dz`. Score and level
filters support integer ranges such as `scores={kills=1..,deaths=..0}` and
`level=..5`; advancement filters match sandbox player progress by
whole-advancement or criterion booleans; NBT filters use contains-style object
matching with numeric equality; predicate filters evaluate loaded datapack
predicates with the candidate entity as `this` and with the candidate
position/dimension, and support `!` negation; and rotation filters support
signed numeric ranges. `sort=random` uses a
deterministic per-origin ordering for repeatable tests. Unsupported selector
options produce an unsupported diagnostic under the active unsupported policy.

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

| Command | Behavior | Purpose |
|---|---:|---|
| `event player <name> <type> ...` | `modeled` | Inject player events for advancements/predicates and observable player state such as consumed/picked-up items, dimension, health, recipes, and input metadata. |
| `player <name>` | `modeled` | Create or reuse a sandbox player. |
| `inspect <...>` | `modeled` | Inspect world state, world border, score, storage, gamerules, random sequences, scheduled functions, forced chunks, scoreboard objectives/displays, teams, bossbars, entity state, blocks, biome overrides, player, player item slots, player recipes, advancement progress, loot, predicate, advancement, recipe, item_modifier, raw JSON resources, tags, resource index, registry groups, outputs, and player event traces. |
| `snapshot [file]` | `modeled` | Print or write deterministic world JSON. |
| `help` | `modeled` | Show REPL help. |
| `exit`, `quit` | `modeled` | Leave the REPL. |
| `reload` | `observed-noop` | REPL-only datapack reload while preserving world state. |
| `load` | `modeled` | Run `#minecraft:load` in REPL. |
| `load fixture <file>` | `modeled` | Apply a manifest-style world JSON fixture in REPL. |
| `tick [n]` | `modeled` | Advance sandbox ticks in REPL. |
| `trace <on|off|status>` | `modeled` | Toggle automatic REPL trace printing for newly executed commands. |
| `diff last` | `modeled` | Print the before/after snapshot diff for the last tracked REPL command. |
| `rerun last` | `modeled` | Re-execute the last tracked REPL command. |
| `reset world` | `modeled` | Replace the current REPL world with a fresh sparse world. |
| CLI `loot --table <id> --context <context>` | | Generate a loot table directly. |
| CLI `run --trace --trace-filter <filter>` | | Print or write only matching trace events; filters support `root=`, `command=`, `contains=`, `function=`, `file=`, `selector=`/`target=`, `success=`, `error=`/`diagnostic=`, `error-code=`/`diagnostic-code=`, `error-message=`/`diagnostic-message=`, `output=` text, `outputs=` count/boolean, `output-channel=`, `output-payload=<path>[=<json>]`, `diff=`, `path=`, `score=`, and `storage=`. Trace JSONL entries include per-command output events and snapshot diffs for state changed by that command. |
| CLI `run --outputs-file <file>` | | Write observable output events as JSONL for CI artifacts or generated-command regression tests. |
| CLI `run --report-file <file>` | | Write a combined JSON report with pass/fail status, assertion failures, outputs, traces, diagnostics derived from failed trace entries, event traces, the final snapshot, snapshot diffs, and resource summary details. |
| CLI `run --resources` | | Print deterministic resource counts, overlay diagnostics, and missing direct resource references for quick pack checks. |
| CLI `resources --pack <path>` | | Inspect the loaded resource index for one or more packs, including type, namespace/id, source file, pack label, load order, active/overridden state, overlay links, filters (`--type`, `--id`, `--namespace`, `--source-pack`, `--order-min`, `--order-max`, `--active-only`, `--overridden-only`), and JSON artifact output. |
| CLI `resources --registry` | | Export version-profile registry groups and entries, optionally filtered with `--registry-group <group>`, in plain text or JSON. |
| CLI `run --snapshot-diff` | | Print before/after state changes; use `--snapshot-diff-file` to write JSON. |
| CLI `run --stdin` | | Read `.mcfunction` text from standard input; `--stdin-mode commands` executes stdin as raw command lines. |
| CLI `run --command-file <file>` | | Execute one or more raw command files in argument order, useful for command-generator output. |
| CLI `run --event "<event>"` | | Inject player events in quick runs using `player <name> <type> [id] [detail/action\|x y z\|pos=x,y,z]`, then assert player state, sparse-world block changes, or `eventTrace`. |
| CLI `run --event-file <file>` | | Inject one player event per non-empty, non-comment line, using the same `--event` shorthand. |
| CLI `run --event-trace-file <file>` | | Write player event trace JSONL for event-driven datapack debugging and CI artifacts. |
| CLI `run --seed <long>` | | Override the quick-run world seed after world fixtures are applied. |
| CLI `run --world` | | Apply a manifest-style world JSON fixture, including fixture references, before execution. |
| CLI `run --strict` | | Treat unsupported vanilla commands as errors and fail the run on direct missing resource references, without needing separate `--unsupported error` and `--fail-on-missing-resources` flags. |
| CLI `run --allow-command-failure` | | Continue after direct `--command`, `--command-file`, or `--stdin-mode commands` errors so quick tests can assert expected diagnostics and still run follow-up output/state checks. |
| CLI `run --max-commands`, `--max-function-depth`, `--max-ticks-per-run`, `--max-output-events`, `--max-snapshot-bytes` | | Override sandbox safety limits for quick runs to stop runaway generated commands, recursive functions, oversized tick requests, unbounded output, or huge snapshots. |
| CLI `run --assert`, `run --assert-file` | | Evaluate inline or file-backed manifest assertions after execution, including final `snapshot` equality/existence assertions and before/after `snapshotDiff` assertions. Manifest output assertions support exact, contains, regex `matches`, normalized text, normalized regex, segment, payload, count, and order fields; storage and NBT/component path expectations support `equals`, `exists`/`missing`, `contains`, and regex `matches`. `--assert-file` accepts JSON object/array files or one shorthand per non-empty, non-comment line. Shorthands include `score:<target>:<objective>=N`, `score:<target>:<objective>>=N`, `score:<target>:<objective><=N`, `storage:<id>[:<path>]=<json>`, `storage:<id>[:<path>]?`, `storage:<id>[:<path>]!`, `advancement:<player>:<id>[=<true\|false>][:done=<true\|false>][:criterion=<name>][:criterionDone=<true\|false>]`, `predicate:<id>[=<true\|false>][:player=<name>][:equals=<true\|false>]`, `loot:<table>[:context=<id>][:player=<name>][:seed=N][:count=N][:item=<id>]`, `player:<name>[:<field>=<value>]`, `world:<field>=<value>`, `gamerule:<rule>=<value>`, `gamerule:<rule>?`, `gamerule:<rule>!`, `random-sequence:<name>=N`, `snapshot:<path>=<json>`, `snapshot:<path>?`, `snapshot:<path>!`, `block:<x>,<y>,<z>=<id>`, `block:<x>,<y>,<z>?`, `block:<x>,<y>,<z>!`, `biome:<x>,<y>,<z>=<id>`, `team:<name>?`, `team:<name>!`, `team:<name>@<member>`, `team:<name>=N`, `bossbar:<id>?`, `bossbar:<id>!`, `bossbar:<id>:<field>=<value>`, `item:<player>:<id>[@slot]=N`, `entity:<type|*>[@tag]=N`, `diff:<json-pointer>[=<kind>]`, `event-trace:<player>:<type>[@x,y,z][=N]`, `trace:<root>=N`, `trace:<text>`, `trace-output:<text>[@target]`, `diagnostic=N`, `diagnostic:<code>=N`, `diagnostic:<code>:<text>[=N]`, `warning=N`, `warning:<text>`, `unsupported=N`, `unsupported:<text>`, `output:<text>`, `output-count:<text>=N`, `output-order:<N>:<text>`, `output-exact:<text>`, `output-matches:<regex>`, `output-command:<command>=N`, `output-command:<command>?`, `output-command:<command>!`, `output-channel:<channel>=N`, `output-channel:<channel>?`, `output-channel:<channel>!`, `output-target:<target>=N`, `output-target:<target>?`, `output-target:<target>!`, `output-normalized:<text>`, `output-normalized-exact:<text>`, `output-normalized-matches:<regex>`, `output-segment:<text>[|color=<color>|bold=<true\|false>][@target]`, `output-segment-exact:<text>[|color=<color>|bold=<true\|false>][@target]`, `output-segment-matches:<regex>[|color=<color>|bold=<true\|false>][@target]`, and `output-payload:<command>:<path>[=<json>]`. |
| Assertion shorthand `scheduled:<id>` | | Check scheduled function snapshot state with `scheduled:<id>=<dueTick>`, `scheduled:<id>?`, or `scheduled:<id>!`. |
| Manifest assertion `gamerule` | | Check stored gamerule values and missing rules with typed `.dps.json` fields. |
| Manifest assertions `randomSequence` / `forcedChunk` | | Check deterministic random sequence state and forced chunk presence with typed `.dps.json` fields. |
| Assertion shorthand `scoreboard-objective:<name>` | | Check objective metadata with `scoreboard-objective:<name>?`, `scoreboard-objective:<name>!`, or `scoreboard-objective:<name>:<field>=<value>` for `criteria`, `displayName`, `renderType`, or `displayAutoUpdate`. |
| Assertion shorthand `scoreboard-display:<slot>` | | Check objective display slots with `scoreboard-display:<slot>=<objective>`, `scoreboard-display:<slot>?`, or `scoreboard-display:<slot>!`, including dotted slots such as `sidebar.team.red`. |
| Manifest assertions `scoreboardObjective` / `scoreboardDisplay` | | Check objective metadata and display slots with typed `.dps.json` fields instead of snapshot paths. |
| Assertion shorthand `forced-chunk:<x>,<z>` / `forceload:<x>,<z>` | | Check final forced chunk snapshot state with `forced-chunk:<x>,<z>?`, `forced-chunk:<x>,<z>!`, or the `forceload:` alias. |
| CLI `diff <before.json> <after.json>` / `diff --script <manifest.dps.json>` | | Compare two deterministic JSON snapshots or reports and print field-level JSON Pointer differences; `--snapshot` extracts a single `snapshot` field from run/check reports, `--state` ignores trace bookkeeping, `--json --output <file>` writes a diff artifact, and `--check` exits non-zero when differences exist. `--script --output <file>` exports manifest command/function steps as a replay script for optional external vanilla or third-party differential harnesses, preserving sandbox-only steps as comments. |
| CLI `benchmark` | | Run built-in performance smoke scenarios for scoreboard writes, large storage merge, function chains, and batch manifest execution; optional `--pack` measures pack loading, optional `--loot-table` samples loot generation, and `--json --output <file>` writes a benchmark artifact. |
| CLI `run --fail-on-missing-resources` | | Fail a quick run when direct load/tick tag, advancement parent/reward, predicate references in predicate/loot/item modifier resources, or nested loot table references point at missing resources, useful before creating a full manifest. |
| CLI `check <manifest-or-directory>` | | Run `.dps.json` manifests; manifest `include` can share world fixtures, steps, assertions, and common/default pack matrices before case-local packs; `--validate-schema` checks manifest structure before execution; `--fail-on-missing-resources` turns direct missing resource references into failures; `--strict` combines schema validation, unsupported-command errors, and direct missing-reference failures; `--max-commands`, `--max-function-depth`, `--max-ticks-per-run`, `--max-output-events`, and `--max-snapshot-bytes` override sandbox safety limits for each attempt; `--verbose` prints resource summaries, overlay entries, missing references, and output events; use `--snapshot-diff-on-fail` for state changes, plus `--trace-file`, `--trace-filter`, `--outputs-file`, `--event-trace-file`, and `--report-file` for CI artifacts. Report JSON includes output, command trace, diagnostics derived from failed trace entries, player event trace, final snapshot, snapshot diff, and resource summary details per attempt. |
| CLI `schema [--output <file>]` | | Print or write the bundled `.dps.json` manifest JSON Schema for editor and CI integration. |

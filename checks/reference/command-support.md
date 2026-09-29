# Command Support

## When to use this page

Before depending on a vanilla command, selector, or special-entity behavior, use this page to confirm parsing, behavior level, and observable side effects for the active profile.

## Prerequisites

Choose the target Minecraft version profile first. A command root can be absent from one profile or handled under a different unsupported policy.

## Minimal runnable example

Run `java -jar cli/build/libs/datapack-sandbox-cli.jar commands --version 26.2` to get the version-scoped catalog that also drives the matrix below.

## Full capabilities

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
| `damage` | Partial | `modeled` | Reduces entity/player health, supports `at`, `by`, and `from` context in structured output, exposes loaded `damage_type` JSON metadata for custom damage sources, emits sandbox damage/death advancement events, and records health changes; display, marker, interaction, marker-mode armor stand, and `Invulnerable` entities reject damage; no armor calculation, invulnerability frames, death loot, or full combat rules. |
| `data` | Partial | `modeled` | `get` with optional numeric scale, `merge`, `modify`, `remove` for `storage`, `entity`, and `block`; write operations record structured before/after output; paths support fields, positive/negative numeric indexes, and simple object matchers; `modify` supports `value`, `from`, and `string` sources; append/prepend/insert reject existing non-list targets instead of overwriting them; top-level NBT is schema-checked. |
| `datapack` | Partial | `modeled` | `list [available|enabled]` reports loaded pack paths, typed/raw/tag/resource-index counts, resource overlay diagnostics, and missing-reference diagnostics in a structured payload; `enable`/`disable` are accepted as no-op because pack order is fixed at sandbox creation and record the requested pack name/order arguments for assertions. |
| `debug`, `jfr`, `perf` | No-op | `observed-noop` | Accept action/argument tokens and record structured debug output; profiling and flight recording are not simulated. |
| `defaultgamemode` | Supported | `modeled` | Stores world default game mode and records structured before/after output. |
| `difficulty` | Supported | `modeled` | Stores and reports world difficulty with structured before/after output. |
| `dialog` | Unsupported | `unsupported` | No client dialog UI or dialog selection model; routed through the configured unsupported policy. |
| `deop`, `op` | No-op | `observed-noop` | Records requested permission target as structured debug output; no permission state is stored. |
| `effect` | Partial | `modeled` | `give`, `clear`; updates player effect state with advancement events and non-player entity active effects visible through snapshot and `ActiveEffects` NBT; records structured output for reports/assertions. |
| `enchant` | Partial | `modeled` | Writes enchantment components to player selected items and non-player mainhand equipment, exposes loaded `enchantment` JSON metadata in structured output when present, and records modified items for reports/assertions; no enchantability checks. |
| `execute` | Partial | `modeled` | `as`, `at`, `on target|attacker`, `positioned <pos>`, `positioned as <selector>`, `align`, `anchored`, `facing`, `in`, `rotated`, `store`, `if`, `unless`, `run`; `on target`/`on attacker` resolve the last player recorded by an interaction entity; `as` changes only the executor, `at` moves execution position/dimension/rotation to the target, and `positioned as` moves only the execution position; `align` floors validated `x`/`y`/`z` axes; `rotated` and `facing` update the command rotation context used by relative `tp` rotations and local coordinates; `anchored` updates the local-coordinate base; `store` targets score, storage, entity NBT, block NBT, and bossbar value/max, honors byte/short/int/long/float/double type plus scale for NBT targets with integer narrowing behavior, with nested condition failure and `return fail` stored as success/result `0`; conditions support `entity`, `score`, `data`, `block`, `blocks`, `predicate`, `function`, `dimension`, `biome`, and `loaded`. |
| `experience`, `xp` | Partial | `modeled` | `add`, `set`, `query`; points and levels are stored separately on players; mutation and query commands record structured data output for assertions and `execute store result`. |
| `fetchprofile` | Unsupported | `unsupported` | No network profile lookup; routed through the configured unsupported policy. |
| `fill` | Partial | `modeled` | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`; records structured changed-position output; position arguments accept local coordinates; no updates/drops. |
| `fillbiome` | Partial | `modeled` | Stores biome overrides for explicit block ranges and records structured changed-position output; the same explicit overrides are visible to `execute if biome` and predicate `location_check` biome tests; no chunk biome container or generation effects. |
| `forceload` | Partial | `modeled` | `add`, `remove`, `remove all`, `query`, `query <pos>`; stores forced chunk coordinates and records structured mutation/query output. |
| `function` | Supported | `modeled` | `function <id>`. |
| `gamemode` | Supported | `modeled` | `gamemode <mode> [targets]`; updates sandbox player game mode and records structured before/after output. |
| `gamerule` | Partial | `modeled` | Stores arbitrary gamerule string values and records structured mutation/query output; no gameplay side effects. |
| `give` | Partial | `modeled` | Adds item stacks to player inventories, records structured output for reports/assertions, exposes loaded equipment asset, banner pattern, instrument, jukebox song, and armor trim material/pattern metadata for matching item components when present, and fires inventory advancement events; item arguments accept sandbox JSON/SNBT-lite NBT and component payloads. |
| `help` | Partial | `modeled` | Reports command roots and basic sandbox help text. |
| `item` | Partial | `modeled` | `replace entity|block ... with <item> [count]` and `from entity|block ...`; `replace` and `modify` record structured output for reports/assertions, including loaded equipment asset, banner pattern, instrument, jukebox song, and armor trim material/pattern metadata for matching item components when present; item arguments accept sandbox JSON/SNBT-lite NBT and component payloads; container item-stack NBT validation accepts legacy/current `Count`/`count` and `Slot`/`slot` aliases; entity slots cover player inventory/selected-mainhand/`enderchest.*`, ordinary non-player equipment, armor stand equipment, and item display `inventory.0`; other non-living special entities reject equipment slots; `modify entity|block ... <modifier>` applies common item modifier functions (`set_components`, `set_custom_data`, `set_count`, `limit_count`, `set_item`, `discard`, `set_damage`, `set_name`, `set_lore`, `copy_nbt`, `copy_components`, `filtered`, `reference`, `sequence`). |
| `kick` | No-op | `observed-noop` | Records requested kick target and message as structured debug output; no network session is removed. |
| `kill` | Supported | `modeled` | Removes selected sandbox entities, records structured target output for reports/assertions, exposes loaded dimension metadata for target dimensions when present, and player execution contexts fire `killed_entity` advancement events for non-player targets. |
| `list` | Supported | `modeled` | Reports sandbox players and UUIDs. |
| `locate` | Partial | `modeled` | Accepts `biome`, `structure`, `poi`; reports no result in the void world instead of querying worldgen. |
| `loot` | Partial | `modeled` | Supports `give`, `insert`, `spawn`, `replace entity`, `replace block`, with structured loot output for reports/assertions; `spawn` creates item entities in the current execution dimension and exposes loaded dimension metadata when present; `replace entity` writes player inventory/selected-mainhand/`enderchest.*` slots and non-player equipment slots; sources include `loot <table>`, `fish <table> <pos> [tool]`, `mine <pos> [tool]`, `kill <target>` when entities declare `DeathLootTable`, plus sandbox context sources `entity <table> <target>`, `block <table> <pos> [tool]`, and `equipment <table> <target> <slot>`; entries include item, nested loot table, groups, alternatives, sequences, and item tags with nested/optional tag values where `expand=false` emits the whole tag and `expand=true` selects expanded tag items; common functions include count, item id, discard, components/custom data, copied tool components, entity name copy, deterministic enchantment components, tool-enchantment bonus counts, damage, name, and lore. |
| `me` | Supported | `modeled` | Recorded as chat output; loaded `chat_type` JSON metadata is exposed when the modeled command chat type is present. |
| `msg`, `tell`, `w` | Supported | `modeled` | Recorded as private chat output; loaded `chat_type` JSON metadata is exposed when the modeled command chat type is present. |
| `pardon`, `pardon-ip` | No-op | `observed-noop` | Records requested pardon target/IP as structured debug output; no ban list state is stored. |
| `particle` | Partial | `observed-noop` | Recorded as visual output event; no client particles. |
| `place` | Partial | `modeled` | `place structure <id> [pos]` and `place template <id> [pos] [rotation] [mirror] [integrity] [seed]` apply loaded sandbox structure JSON resources (`worldgen/structure` with `blocks`/`entities` or palette-style blocks) and binary structure NBT resources from `worldgen/structure`, `structure`, or `structures` directories to the sparse world and record changed blocks/entities; binary NBT resources expose `sourceFormat=binary-structure-nbt` in structured output. Structure JSON can reference `worldgen/processor_list` resources for `block_ignore`, `protected_blocks`, `jigsaw_replacement`, `capped`, `nop`, and rule replacement processors with block/tag predicates. Template placement supports deterministic `none`/90-degree rotations, `front_back`/`left_right` mirroring, and integrity filtering. `place jigsaw <pool> <target> <maxDepth> [pos]` resolves loaded `worldgen/template_pool` single/legacy/list/feature elements, follows fallback pools when the selected pool has no supported element, applies element processors, places the selected structure or feature, and for `maxDepth > 1` follows basic jigsaw block `pool` connectors with deterministic direction offsets while exposing `jigsawConnections` debug payloads. `place feature <id> [pos]` resolves loaded `worldgen/placed_feature`/`worldgen/configured_feature` simple_block, block_column, deterministic disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replacement/selector/random_patch/flower JSON, and sparse-world ore targets, placing or replacing one or more blocks around the command position. Missing or unsupported resources still record structured worldgen intent with `placed=false`. |
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
| `stopwatch` | Unsupported | `unsupported` | No vanilla stopwatch state is modeled; routed through the configured unsupported policy. |
| `summon` | Partial | `modeled` | Creates entities in the current execution dimension with position, tags, schema-checked NBT, structured creation output, loaded dimension/dimension_type metadata, and loaded entity variant metadata for cat/chicken/cow/frog/painting/pig/wolf variants when present. Display entities validate block/item/text content, transforms, render properties, grouped transform/style interpolation, and teleport interpolation; armor stands validate pose/marker/size/arm/base-plate/disabled-slot state; markers preserve arbitrary compound `data`; interaction entities model width/height/response, last attack/right-click records, hitboxes, and relation targets. Derived render state and hitboxes appear under entity snapshot `special`; no client rendering or entity AI. |
| `swing` | Unsupported | `unsupported` | No client hand-swing animation; routed through the configured unsupported policy. |
| `tag` | Supported | `modeled` | `add`, `remove`, `list`. |
| `team` | Partial | `modeled` | `add`, `remove`, `list`, `join`, `leave`, `empty`, `modify`; records structured team/member/option output and has no gameplay effects. |
| `teammsg`, `tm` | Supported | `modeled` | Recorded as team chat output; loaded `chat_type` JSON metadata is exposed when the modeled command chat type is present. |
| `test` | Unsupported | `unsupported` | No vanilla game-test execution environment; routed through the configured unsupported policy. |
| `teleport`, `tp` | Partial | `modeled` | Coordinate teleport supports local coordinates, optional rotation, `facing`, and the current execution dimension; destination-entity teleport copies destination position, dimension, and rotation; display `teleport_duration` produces a deterministic snapshot render-position interpolation while selectors immediately use the server target position; records structured movement output with loaded from/to dimension metadata when present. |
| `tellraw` | Supported | `modeled` | Resolves JSON text components into output events. |
| `tick` | Partial | `modeled` | `query`, `rate`, `freeze`, `unfreeze`, `step`, `sprint`, `stop`; updates sandbox tick state, can advance ticks, and records structured state/advance output for debugging. |
| `time` | Partial | `modeled` | `set`, `add`, `query daytime|gametime|day`; mutations and queries record structured data output for assertions and `execute store result`. |
| `title` | Supported | `modeled` | `clear`, `reset`, `title`, `subtitle`, `actionbar`, `times` output events. |
| `transfer` | Partial | `observed-noop` | Records requested host, port, target players, and accepted syntax as structured debug output; no network/server transfer is performed. |
| `trigger` | Partial | `modeled` | `trigger <objective> [add|set] [value]`; uses current/default sandbox player. |
| `unpublish` | Unsupported | `unsupported` | No LAN publication state is modeled; routed through the configured unsupported policy. |
| `version` | Unsupported | `unsupported` | No vanilla version-report command behavior; routed through the configured unsupported policy. |
| `waypoint` | Unsupported | `unsupported` | No waypoint transmission or client UI model; routed through the configured unsupported policy. |
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
rendered plain text, raw command message text, and segment metadata. For
example, `say hello` records `text = "<Server> hello"` and
`rawText = "hello"`.

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

## Special Entities

Special-entity NBT is modeled in every built-in profile from `1.20.4` through
`26.2`:

| Entity | Modeled state and behavior |
|---|---|
| `block_display`, `item_display`, `text_display` | Validates content and common display fields and accepts decomposed or 16-number transformations. Decomposed translation/scale use linear interpolation and left/right quaternions use normalized shortest-arc SLERP; matrix inputs use component-wise linear interpolation. Grouped shadow/text-style state advances over ticks, while `teleport_duration` linearly interpolates the rendered pose. The logical position used by commands changes immediately. |
| `armor_stand` | Validates and stores `Pose`, `Marker`, `Small`, `ShowArms`, `NoBasePlate`, `Invisible`, and `DisabledSlots`; snapshots expose the effective pose and normal/small/marker hitbox. Marker-mode stands reject targeted attacks/interactions and damage. |
| `marker` | Stores arbitrary compound `data`, remains selectable as a logic anchor, exposes a zero-sized non-interactable hitbox, and rejects damage. |
| `interaction` | Validates `width`, `height`, `response`, `attack`, and `interaction`; targeted player events record UUID/timestamp actions, expose hitbox/response metadata, and feed `execute on target` / `execute on attacker`. |

Entity snapshots place derived state under `entities[*].special`. Display
snapshots include `renderTransformation`, `renderPosition`, interpolation
progress, `cullingBox`, a zero-sized gameplay `hitbox`, and stored content.
Armor-stand and interaction snapshots include their effective gameplay
hitboxes. Targeted player events represent an already-resolved client hit; the
sandbox does not raycast a player's view or draw client models/text.

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

This compact catalog is retained for the Gradle drift check. It records tooling entry points and behavior levels, while complete usage lives in the task pages:

| Tool entry | Behavior | Authoritative guide |
| --- | --- | --- |
| `event player <name> <type> ...` | `modeled` | [Player Events](/en/runtime/player-events) |
| `inspect <target>` | `modeled` | [Debug with the REPL](/en/workflows/repl) |
| `load` | `modeled` | [Debug with the REPL](/en/workflows/repl) |
| `trace <on\|off\|status>` | `modeled` | [Reports and Observability](/en/reference/reports-observability) |
| `diff last` | `modeled` | [Debug with the REPL](/en/workflows/repl) |
| `rerun last` | `modeled` | [Debug with the REPL](/en/workflows/repl) |
| `exit` | `modeled` | [Debug with the REPL](/en/workflows/repl) |
| `quit` | `modeled` | [Debug with the REPL](/en/workflows/repl) |

The former tooling-command, run-option, assertion-shorthand, and artifact appendix is now split by task:

- See the [CLI Reference](/en/reference/cli) for commands and complete option groups.
- See [Debug with the REPL](/en/workflows/repl) for persistent-world `inspect`, `trace`, `diff last`, `rerun last`, and reload workflows.
- See [Reports and Observability](/en/reference/reports-observability) for trace, output, snapshot-diff, coverage, and report formats and filters.
- See the [Manifest Reference](/en/reference/manifest) for typed step and assertion fields.

This heading remains so existing deep links still reach the new authoritative entries.

## Limitations

The matrix describes observable behavior in the clean-room sandbox, not every vanilla side effect. Networking, permissions, client UI, world generation, redstone, entity AI, and full combat remain out of scope.

## Related pages

- [CLI Reference](/en/reference/cli)
- [Debug with the REPL](/en/workflows/repl)
- [Reports and Observability](/en/reference/reports-observability)
- [Version Profiles](/en/resources/version-profile)

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
`UnsupportedFeatureMode.ERROR` when strict validation is required.

## Status Meanings

| Status | Meaning |
|---|---|
| Supported | Main sandbox-visible behavior is implemented. |
| Partial | Useful datapack test behavior is implemented, but vanilla side effects are incomplete. |
| No-op | Accepted and recorded, but no mutable vanilla-equivalent state exists in the sandbox. |
| Unsupported | Not implemented; handled by the unsupported policy. |

## Vanilla Command Matrix

| Command | Status | Supported forms / sandbox behavior |
|---|---:|---|
| `advancement` | Partial | `grant`, `revoke`, `test`; progress is per player; rewards support functions, loot, XP, and recipes. |
| `attribute` | Partial | `get`, `base get`, `base set`, `base reset`; modifier subcommands are accepted as no-op warning output. |
| `ban`, `ban-ip`, `banlist` | Unsupported | Server administration and ban lists are not simulated. |
| `bossbar` | Partial | `add`, `remove`, `list`, `get`, `set`; state is stored and appears in snapshots, no real client UI. |
| `clear` | Partial | Removes matching item stacks from sandbox player inventories. |
| `clone` | Partial | Copies sparse sandbox block state/NBT; no updates, drops, or overlap physics. |
| `damage` | Partial | Reduces entity/player health; no armor, invulnerability, death loot, or combat rules. |
| `data` | Partial | `get`, `merge`, `modify`, `remove` for `storage`, `entity`, and `block`; top-level NBT is schema-checked. |
| `datapack` | Partial | `list` reports loaded resource counts; `enable`/`disable` are accepted as no-op because pack order is fixed at sandbox creation. |
| `debug`, `jfr`, `perf` | Unsupported | Profiling commands do not apply to this runtime. |
| `defaultgamemode` | Supported | Stores world default game mode. |
| `difficulty` | Supported | Stores and reports world difficulty. |
| `deop`, `op` | Unsupported | Permission system is not simulated. |
| `effect` | Partial | `give`, `clear`; updates player effect state and advancement events. |
| `enchant` | Partial | Writes enchantment components to selected item; no enchantability checks. |
| `execute` | Partial | `as`, `at`, `positioned`, `align`, `anchored`, `facing`, `in`, `rotated`, `store`, `if`, `unless`, `run`; conditions support `entity`, `score`, `data`, `block`. |
| `experience`, `xp` | Partial | `add`, `set`, `query`; points and levels share the sandbox XP integer. |
| `fill` | Partial | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`; no updates/drops. |
| `fillbiome` | Partial | Stores biome overrides for explicit block ranges; no chunk biome container or generation effects. |
| `forceload` | Partial | `add`, `remove`, `remove all`, `query`; stores forced chunk coordinates. |
| `function` | Supported | `function <id>`. |
| `gamemode` | Supported | `gamemode <mode> [targets]`; updates sandbox player game mode. |
| `gamerule` | Partial | Stores arbitrary gamerule string values; no gameplay side effects. |
| `give` | Partial | Adds item stacks to player inventories and fires inventory advancement events. |
| `help` | Partial | Reports command roots and basic sandbox help text. |
| `item` | Partial | `replace entity <targets> <slot> with <item> [count]`; `modify` accepted as resource-hook no-op. |
| `kick` | Unsupported | Network sessions are not simulated. |
| `kill` | Supported | Removes selected sandbox entities. |
| `list` | Supported | Reports sandbox players and UUIDs. |
| `locate` | Partial | Accepts `biome`, `structure`, `poi`; reports no result in the void world instead of querying worldgen. |
| `loot` | Partial | Supports `give`, `insert`, `spawn`, `replace entity`, `replace block` with source `loot <table>`. Other vanilla loot sources are unsupported. |
| `me` | Supported | Recorded as chat output. |
| `msg`, `tell`, `w` | Supported | Recorded as private chat output. |
| `pardon`, `pardon-ip` | Unsupported | Server administration is not simulated. |
| `particle` | Partial | Recorded as visual output event; no client particles. |
| `place` | Unsupported | Structure/feature placement and worldgen are not simulated. |
| `playsound` | Partial | Recorded as sound output event. |
| `publish` | Unsupported | LAN/networking is not simulated. |
| `random` | Partial | `value`, `roll`, `reset`; deterministic sandbox sequence state. |
| `recipe` | Partial | `give`, `take`; updates per-player recipe sets. |
| `reload` | No-op | Accepted and recorded; REPL `reload` performs real datapack reload, vanilla command does not mutate this immutable sandbox instance. |
| `return` | Supported | Stops the current function. |
| `ride` | Partial | Tracks vehicle/passenger relationships; no physics/control. |
| `rotate` | Partial | Updates yaw/pitch. |
| `save-all`, `save-off`, `save-on` | Unsupported | No real world save lifecycle exists. |
| `say` | Supported | Recorded as chat output. |
| `schedule` | Partial | `schedule function <id> <time> [append|replace]`, `schedule clear <id>`. |
| `scoreboard` | Partial | Objectives `add`, `remove`, `list`; players `set`, `add`, `remove`, `get`, `reset`, `list`, `enable`, `operation`. |
| `seed` | Supported | Reports deterministic sandbox seed. |
| `setblock` | Partial | Mutates sparse block state/NBT; no neighbor updates. |
| `setidletimeout` | Unsupported | Server administration command. |
| `setworldspawn` | Partial | Stores sandbox world spawn position and angle. |
| `spawnpoint` | Partial | Stores per-player spawn point and angle. |
| `spectate` | Partial | Sets spectator mode and records target; no camera/client state. |
| `spreadplayers` | Partial | Deterministically distributes selected entities around a center; no collision/team algorithm. |
| `stop` | Unsupported | Runtime lifecycle is controlled by the host process, not commands. |
| `stopsound` | Partial | Recorded as sound output event. |
| `summon` | Partial | Creates entities with position, tags, and schema-checked NBT; AI does not tick. |
| `tag` | Supported | `add`, `remove`, `list`. |
| `team` | Partial | `add`, `remove`, `list`, `join`, `leave`, `empty`, `modify`; no gameplay effects. |
| `teammsg`, `tm` | Supported | Recorded as team chat output. |
| `teleport`, `tp` | Partial | Coordinate and destination teleport; local-coordinate/facing semantics remain incomplete. |
| `tellraw` | Supported | Resolves JSON text components into output events. |
| `tick` | Partial | `query`, `rate`, `freeze`, `unfreeze`, `step`, `sprint`, `stop`; updates sandbox tick state and can advance ticks. |
| `time` | Partial | `set`, `add`, `query daytime|gametime|day`. |
| `title` | Supported | `clear`, `reset`, `title`, `subtitle`, `actionbar`, `times` output events. |
| `transfer` | Unsupported | Networking/server transfer is not simulated. |
| `trigger` | Partial | `trigger <objective> [add|set] [value]`; uses current/default sandbox player. |
| `weather` | Partial | `clear`, `rain`, `thunder`; stored state only. |
| `whitelist` | Unsupported | Server administration command. |
| `worldborder` | Partial | `get`, `set`, `add`, `center`, `damage`, `warning`; stored state only. |

## Text And Output Commands

Output commands are deterministic `OutputEvent`s. They appear in snapshots,
REPL output, `run`, `check --verbose`, and the code test API:

- chat: `say`, `me`, `msg`, `tell`, `w`, `teammsg`, `tm`, `tellraw`
- title: `title`
- sound: `playsound`, `stopsound`
- visual: `particle`
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
- Player NBT is readable but not writable through `data`; use commands/events to
  change player state.
- Entity AI, gravity, redstone, block updates, loot drops from block breaking,
  and real combat are not simulated.

## Sandbox-Only CLI/REPL Commands

These are tooling commands, not vanilla commands:

| Command | Purpose |
|---|---|
| `event player <name> <type> ...` | Inject player events for advancements/predicates. |
| `player <name>` | Create or reuse a sandbox player. |
| `inspect <...>` | Inspect score, storage, entities, blocks, player, loot, predicate, advancement, registry, outputs. |
| `snapshot [file]` | Print or write deterministic world JSON. |
| `reload` | REPL-only datapack reload while preserving world state. |
| `load` | Run `#minecraft:load` in REPL. |
| `tick [n]` | Advance sandbox ticks in REPL. |
| CLI `loot --table <id> --context <context>` | Generate a loot table directly. |
| CLI `check <manifest-or-directory>` | Run `.dps.json` manifests. |

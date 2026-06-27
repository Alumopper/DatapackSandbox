# Command Support for 26.2

This project defaults to Minecraft Java `26.2` and keeps compatibility profiles
down to `1.20.4`. Command syntax is checked against Minecraft Wiki command
syntax notes and should be validated against generated vanilla reports from the
active target version's server jar when extending this table. The table compares
vanilla root command families in the latest profile with the sandbox
implementation.

Status meanings:

- `Supported`: the main sandbox-visible behavior is implemented.
- `Partial`: the root command exists, but only data-pack test paths are covered.
- `Unsupported`: the sandbox does not implement the command. By default, vanilla
  root commands are recorded as warning output events instead of aborting the
  run. Use CLI/manifest/API unsupported mode `error` for strict failures, or
  `ignore` to silence these warnings.
- `Unknown for profile`: the command root is not present in the active version
  profile and fails as `INPUT_FORMAT` instead of producing an unsupported
  warning.
- `Sandbox-only`: this is not a vanilla command.

## Vanilla Command Comparison

| Vanilla command | Sandbox status | Supported forms / notes |
|---|---:|---|
| `advancement` | Partial | `grant|revoke|test`; criteria progress is per player; rewards support function, loot, XP, and recipes |
| `attribute` | Unsupported | Entity attribute system is not simulated |
| `ban` / `ban-ip` / `banlist` | Unsupported | Server administration commands |
| `bossbar` | Partial | `add|remove|list|get|set`; state is stored, no real client UI |
| `clear` | Partial | Removes matching item stacks from sandbox player inventories |
| `clone` | Partial | Copies sparse sandbox block state/NBT; no updates, drops, or overlap physics |
| `damage` | Partial | Applies health reduction to sandbox entities/players; full combat rules are not simulated |
| `data` | Partial | `get|merge|modify|remove storage|entity|block`; top-level NBT fields are schema-checked |
| `datapack` | Unsupported | Pack enable/order state is not simulated; use REPL `reload` |
| `debug` / `jfr` / `perf` | Unsupported | Profiling commands do not apply to the clean-room runtime |
| `defaultgamemode` / `gamemode` | Unsupported | Player game mode field exists, command is not wired |
| `deop` / `op` | Unsupported | Permission system is not simulated |
| `difficulty` | Unsupported | Difficulty does not affect current runtime behavior |
| `effect` | Partial | `give|clear` updates sandbox player effects and fires advancement events |
| `enchant` | Partial | Writes enchantment components to the selected item; no enchantability checks |
| `execute` | Partial | `as|at|positioned|align|anchored|facing|in|rotated|store|if|unless ... run`; conditions support `entity|score|data|block` |
| `experience` / `xp` | Partial | `add|set|query`; points and levels share the sandbox XP integer |
| `fill` | Partial | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`, no updates/drops |
| `fillbiome` | Unsupported | Biome storage is not simulated |
| `forceload` | Unsupported | Chunk loading is not simulated |
| `function` | Supported | `function <id>` |
| `gamerule` | Partial | Stores arbitrary gamerule string values; rules have no gameplay side effects |
| `give` | Partial | Adds item stacks to sandbox player inventories and fires inventory advancement events |
| `help` | Unsupported | REPL has sandbox `help`, but vanilla `/help` is not implemented |
| `item` | Partial | `item replace entity <targets> <slot> with <item> [count]`; `modify` is accepted as a no-op resource hook |
| `kick` | Unsupported | Network/login sessions are not simulated |
| `kill` | Supported | `kill [targets]` removes sandbox entities |
| `list` | Unsupported | Server player list command is not simulated |
| `locate` | Unsupported | Worldgen/structure lookup is not simulated |
| `loot` | Unsupported | Vanilla `/loot` is not implemented; CLI has `loot --table ...` |
| `me` | Supported | Recorded as sandbox chat output |
| `msg` / `tell` / `w` | Supported | Recorded as sandbox chat output |
| `pardon` / `pardon-ip` | Unsupported | Server administration commands |
| `particle` | Partial | Recorded as sandbox visual output, no client particles |
| `place` | Unsupported | Structure/feature placement is not simulated |
| `playsound` | Partial | Recorded as sandbox sound output |
| `publish` | Unsupported | LAN/networking is not simulated |
| `random` | Partial | `value|roll <range> [sequence]`, `reset`; deterministic sandbox sequence state |
| `recipe` | Partial | `give|take <targets> <recipe|*>`, updates sandbox player recipe sets |
| `reload` | Unsupported | Vanilla reload is not implemented; REPL has sandbox `reload` |
| `return` | Supported | Stops the current function |
| `ride` | Partial | Tracks vehicle/passenger relationships; no control or physics |
| `rotate` | Partial | Updates entity yaw/pitch fields |
| `save-all` / `save-off` / `save-on` | Unsupported | No real world save exists |
| `say` | Supported | Recorded as sandbox chat output |
| `schedule` | Partial | `schedule function <id> <time> [append|replace]`, `schedule clear <id>` |
| `scoreboard` | Partial | objectives `add|remove|list`; players `set|add|remove|get|reset|list|enable|operation` |
| `seed` | Unsupported | World seed/generation is not simulated |
| `setblock` | Partial | `setblock <pos> <block[state]{nbt}> [replace|keep|destroy|strict]`, no neighbor updates |
| `setidletimeout` | Unsupported | Server administration command |
| `setworldspawn` / `spawnpoint` | Unsupported | Spawn logic is not simulated |
| `spectate` | Unsupported | Spectator target/client camera are not simulated |
| `spreadplayers` | Unsupported | Spatial distribution/collision rules are not implemented |
| `stop` | Unsupported | Runtime lifecycle is not controlled by commands |
| `stopsound` | Partial | Recorded as sandbox sound output |
| `summon` | Partial | `summon <entity> [x y z] [snbt]`, creates no-AI-ticked entities with NBT schema checks |
| `tag` | Supported | `tag <targets> add|remove|list` |
| `team` | Partial | `add|remove|list|join|leave|empty|modify`; no collision/nameplate gameplay effects |
| `teammsg` / `tm` | Supported | Recorded as sandbox chat output |
| `teleport` / `tp` | Partial | Coordinate and destination teleport; facing/local-coordinate semantics are incomplete |
| `tellraw` | Supported | JSON text component is resolved into sandbox chat output |
| `tick` | Unsupported | Vanilla tick command is not implemented; REPL `tick [n]` is sandbox-only |
| `time` | Partial | `set|add|query daytime|gametime|day`; day time is stored independently from tick count |
| `title` | Supported | `clear|reset|title|subtitle|actionbar|times` output events |
| `transfer` | Unsupported | Server transfer/networking is not simulated |
| `trigger` | Unsupported | Trigger objective workflow is not implemented |
| `weather` | Partial | `clear|rain|thunder [duration]`; stored state only |
| `whitelist` | Unsupported | Server administration command |
| `worldborder` | Unsupported | World border is not simulated |

Output commands are represented as deterministic sandbox output events rather
than client UI/network effects. Raw JSON text components are resolved for
`text`, `score`, `selector`, `translate`, `keybind`, basic `nbt`, `extra`, and
common formatting flags, then included in snapshots and rendered by the CLI.

Selectors currently include `@s`, `@a`, `@p`, `@e`, and `@n`. `@n` selects the
nearest entity after filters, with a default limit of one.

## World/NBT Notes

- The initial world is sparse void: unset block positions have no block entry.
- `setblock` and `fill` only mutate sandbox block state. They do not run block
  updates, physics, neighbor updates, scheduled ticks, or loot drops.
- The sandbox creates a default player named `Steve`, with readable
  vanilla-style player NBT. Explicit `player Steve` calls are idempotent.
- Entities do not tick AI or physics. The sandbox also does not inject
  `NoAI: true`; it simply does not simulate AI.
- Non-player entity NBT is writable through `data modify|merge|remove entity`,
  but top-level fields are checked against generated vanilla mcdoc schemas and
  unknown custom fields fail.
- Block NBT is exposed for block entities discovered from generated vanilla
  mcdoc block dispatch mappings. Block entity writes also reject unknown custom
  top-level fields.
- Player NBT is readable through `data get entity <player>`, but write attempts
  fail. Use `tp|teleport`, manifest player steps, or player events to change
  sandbox-visible player state.
- Item stack compounds inside entity/block NBT are also validated from the
  generated mcdoc item stack schema.

## Sandbox-Only Commands

These commands are not vanilla commands; they are CLI/REPL tooling:

| Command | Purpose |
|---|---|
| `event player <name> <type> [id] [action]` | Inject a player behavior event for predicates/advancements/rewards; supports `key_input` and `mouse_input` |
| `player <name>` | Create or reuse a sandbox player |
| `inspect <...>` | Inspect score, storage, entities, blocks, player, loot, predicate, advancement, registry, outputs |
| `snapshot [file]` | Print or write deterministic world JSON |
| `reload` | Reload datapack files in REPL while preserving world state |
| `load` | Run `#minecraft:load` in REPL |
| `tick [n]` | Advance sandbox ticks in REPL |
| CLI `loot --table <id> --context <context>` | Generate a loot table directly |
| CLI `check <manifest-or-directory>` | Run `.dps.json` manifests |

## Unsupported Command Policy

For vanilla root commands that are not implemented, the default policy is
`warn`: execution continues and a yellow `warning` output event is recorded.
This keeps datapack smoke tests from failing only because they contain
cosmetic/admin commands outside the sandbox boundary.

You can choose the policy per CLI command:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check examples --unsupported ignore
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./pack --unsupported error --command "worldborder get"
```

Manifest files can set the same policy with top-level `"unsupported":
"warn"`, `"ignore"`, or `"error"`. The Kotlin API exposes
`UnsupportedFeatureMode.WARN`, `IGNORE`, and `ERROR`.

Worldgen, redstone, full combat AI, networking, and real client input are
outside the current runtime boundary.

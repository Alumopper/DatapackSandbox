# Resource Formats

The loader reads datapack resources from directories or zip files. It validates
`pack.mcmeta` against the active `VersionProfile`.

For Minecraft Java `1.20.4`, use data pack format `26` and legacy plural
resource directories:

```json
{
  "pack": {
    "pack_format": 26,
    "description": "Example 1.20.4 datapack"
  }
}
```

- `data/<namespace>/functions/**/*.mcfunction`
- `data/<namespace>/loot_tables/**/*.json`
- `data/<namespace>/predicates/**/*.json`
- `data/<namespace>/advancements/**/*.json`
- `data/<namespace>/recipes/**/*.json`
- `data/<namespace>/item_modifiers/**/*.json`
- `data/<namespace>/tags/<registry>/**/*.json`

For Minecraft Java `26.2`, use data pack format `107.1` and current singular
resource directories:

```json
{
  "pack": {
    "pack_format": 107.1,
    "description": "Example 26.2 datapack"
  }
}
```

- `data/<namespace>/function/**/*.mcfunction`
- `data/<namespace>/loot_table/**/*.json`
- `data/<namespace>/predicate/**/*.json`
- `data/<namespace>/advancement/**/*.json`
- `data/<namespace>/recipe/**/*.json`
- `data/<namespace>/item_modifier/**/*.json`
- `data/<namespace>/(chat_type|damage_type|dimension|dimension_type|enchantment|jukebox_song|trim_material|trim_pattern|...)/**/*.json`
- `data/<namespace>/worldgen/(configured_feature|placed_feature|structure|processor_list|...)/**/*.json`
- `data/<namespace>/tags/<registry>/**/*.json`

Compatibility profiles keep their own data pack formats. For example,
`26.1`/`26.1.1`/`26.1.2` use `101.1`, `1.21.11` uses `94.1`, and `1.20.5`
through `1.20.6` use `41`.

Legacy aliases are accepted only where the active `VersionProfile` allows them.
Format mismatches fail with `VERSION_MISMATCH`. JSON parse failures include the
file path, resource id, and version.

For newer packs that declare a supported range instead of a single
`pack_format`, the loader accepts `min_format`/`max_format` and
`supported_formats` when the active profile's data pack format is inside the
range.

## `.dps.json` Manifests

Manifests may contain `version` or `versions`, `unsupported`, `seed`,
`failOnMissingResources`, `packs`, `world`, `include`, `steps`, and
`assertions`. The JSON Schema is available at:

```text
docs/dps-manifest.schema.json
```

The standalone CLI also bundles the schema:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --output dps-manifest.schema.json
```

Use `check --validate-schema` to validate manifest structure before execution:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --validate-schema
```

`include` accepts a relative manifest path string or an array of paths. Included
manifests are applied before the including manifest. Their `world`, `steps`, and
`assertions` are concatenated in order, and their `version`/`versions`, `packs`,
`unsupported`, `seed`, and `failOnMissingResources` fields act as defaults when the
including manifest omits them. Relative paths inside included world setup and
steps are resolved from the included file's directory.

Set top-level `"seed"` to define the default deterministic world and loot seed
for the manifest. A `world.seed` fixture value overrides the top-level default
for world state.

Set top-level `"failOnMissingResources": true`, or pass
`check --fail-on-missing-resources`, to fail a manifest when loaded resources
directly reference missing load/tick functions, advancement parents, or
advancement reward resources, predicate references in predicate/loot/item
modifier resources, or nested loot tables.
The same missing references are always present in structured check reports and
`check --verbose` resource summaries.

Inside `world`, `fixture`, `fixtures`, and `extends` accept a relative world
fixture path string or an array of paths. Referenced files may contain either a
raw world fixture object or a top-level `{ "world": { ... } }` object. They are
applied before the local `world` object, so scalar fields, same-position blocks,
same-name players/teams/bossbars, scores, and storage entries can be overridden
by the including manifest. Nested fixture paths are resolved from the referenced
file's directory, and cycles fail as input format errors.

`steps` support full datapack execution and lightweight generated-output tests:

- `{ "load": true }`
- `{ "ticks": 20 }`
- `{ "function": "demo:main" }`
- `{ "command": "say hello" }`
- `{ "commands": ["scoreboard objectives add runs dummy", "..."], "source": "<generator>" }`
- `{ "functionText": "say inline\nscoreboard ...", "source": "<inline>" }`
- `{ "mcfunction": "relative/path/generated.mcfunction" }`
- `{ "snapshot": "artifacts/before.json" }`
- `{ "trace": { "file": "artifacts/trace.jsonl", "output": true } }`
- `{ "reset": true }`
- `{ "player": { ... } }`, `{ "block": { ... } }`, `{ "event": { ... } }`, `{ "loot": { ... } }`

Event steps require `player` and `type`. Optional context fields include
`item`, `entity`, `block`, `recipe`, `from`/`to`, `damageSource` or
`damageType`, `amount`, keyboard `key`, mouse `button`, `action`, and pointer
`x`/`y`:

```json
{ "event": { "player": "Steve", "type": "damage", "damageSource": "minecraft:fall", "amount": 4.5 } }
```

`snapshot` and `trace` steps accept `true` to record a debug output event, a
relative file path string to write an artifact, or an object with `file` and
`output`. `reset` replaces the current world with a fresh sparse world and the
default `Steve` player while keeping the loaded packs and manifest default seed.

Add `"allowFailure": true` to a step when the failure itself is expected and
should be asserted later with a `diagnostic` assertion.

`world` can predefine sparse blocks/entities/players, scoreboards, storage,
gamerules, time/weather, seed/difficulty/default game mode, world/player spawn
points, world border, forced chunks, biome overrides, teams, bossbars, and scoped Java save
imports.

Assertions support score, storage, world, player, team, bossbar, block,
entityCount, advancement, predicate, loot, output, item, trace, event trace,
diagnostic, and snapshot diff checks:

Assertion failures are prefixed with the merged assertion index and JSON Pointer
path, for example `assertion 1 (/assertions/0): ...`.

```json
{ "score": { "target": "#clock", "objective": "ticks", "min": 20, "max": 40 } }
```

```json
{ "storage": { "id": "demo:env", "path": "debug.last", "missing": true } }
```

```json
{ "entityCount": { "type": "minecraft:pig", "tag": "fixture", "min": 1, "max": 3 } }
```

```json
{ "world": { "difficulty": "hard", "forcedChunk": [0, 0], "worldBorder": { "size": 100 } } }
```

```json
{ "item": { "player": "Steve", "id": "minecraft:apple", "count": 3 } }
```

```json
{ "output": { "command": "say", "contains": "hello", "order": 1, "count": 1 } }
```

```json
{ "trace": { "root": "scoreboard", "success": true, "count": 2 } }
```

```json
{ "eventTrace": { "player": "Steve", "type": "damage", "success": true, "criterion": "fell", "count": 1 } }
```

```json
{ "diagnostic": { "step": 1, "code": "COMMAND_ERROR", "contains": "Unknown scoreboard objective", "count": 1 } }
```

```json
{ "snapshotDiff": { "path": "/scores/runs/#clock", "kind": "changed", "after": 20, "count": 1 } }
```

`player` assertions can also check existence, dimension, game mode, health,
food, selected slot, recipe, effect, stat, position, last input, and spawn point. `team` and
`bossbar` assertions inspect their stored runtime state. `item` assertions can
check player inventory by slot, id, exact/min/max count, components path, and
NBT path. `block` assertions can check sparse-world existence, id, and block
entity NBT path equality or existence.
`storage` assertions can compare a path with `equals` or check `exists` and
`missing` for storage roots or nested paths.
`output` assertions can check command/channel/targets, text/contains,
whitespace-normalized text/contains, payload path/value, segment style, count,
and one-based `order`. When an output assertion misses, the failure message
includes a bounded list of actual output candidates.
`trace` assertions can check command/root/contains, success, count, source file,
and function stack. When a trace assertion misses, the failure message includes
a bounded list of actual command trace candidates. `eventTrace` assertions check
player event dispatch by player, type, success, advancement id, criterion, and
count; misses include actual player event trace candidates. `diagnostic`
assertions check expected step failures by step, version, code, command, root,
message substring, and count. `snapshotDiff` assertions compare the manifest
state before and after steps by JSON Pointer path, diff kind, before/after
values, rendered text substring, and count.

## Raw JSON Resources and Tags

Recipes, item modifiers, and additional registry resources are loaded as raw
JSON resources and included in the resource index. The sandbox does not yet
execute the full crafting system, every item modifier function, or worldgen
semantics, but these resources are version-profile checked, participate in pack
overlay behavior, and can be inspected from API or REPL. `item modify entity`
models common item modifier functions such as `set_components`,
`set_custom_data`, `set_count`, `set_name`, and `set_lore`.

Current directories:

```text
data/<namespace>/recipe/**/*.json
data/<namespace>/item_modifier/**/*.json
data/<namespace>/chat_type/**/*.json
data/<namespace>/damage_type/**/*.json
data/<namespace>/dimension/**/*.json
data/<namespace>/dimension_type/**/*.json
data/<namespace>/worldgen/configured_feature/**/*.json
data/<namespace>/worldgen/placed_feature/**/*.json
data/<namespace>/worldgen/structure/**/*.json
data/<namespace>/worldgen/processor_list/**/*.json
data/<namespace>/enchantment/**/*.json
data/<namespace>/jukebox_song/**/*.json
data/<namespace>/trim_material/**/*.json
data/<namespace>/trim_pattern/**/*.json
data/<namespace>/banner_pattern/**/*.json
data/<namespace>/wolf_variant/**/*.json
data/<namespace>/painting_variant/**/*.json
```

Legacy aliases, when allowed by the active profile:

```text
data/<namespace>/recipes/**/*.json
data/<namespace>/item_modifiers/**/*.json
```

Tags are loaded from `data/<namespace>/tags/<registry>/**/*.json`. The loader
keeps the registry directory, tag id, `replace`, and `values`; values may be
strings or `{ "id": "...", "required": false }` objects, and tag references
keep the leading `#`. When a later pack sets `"replace": true`, earlier values
for the same tag are discarded; function `load`/`tick` tags use the same replace
semantics when building runtime entrypoint lists. Missing function entries marked
`"required": false` are skipped instead of becoming runtime entrypoints or
missing-reference failures.

REPL inspection:

```text
inspect recipe
inspect item_modifier
inspect raw
inspect raw <type>
inspect raw <type> <id>
inspect tags [registry]
inspect resources [type]
```

The resource index records type, id, source pack, file path, active/overridden
state, and pack overlay relationships. `datapack list` includes
`overriddenResources` and a `resourceOverrides` array in its structured output
payload so generated command-output tests can assert overlay behavior without
entering the REPL. `check --verbose` also prints a resource summary, overlay
entries, and missing direct references from load/tick function tags and
advancement parents/rewards, predicate references in predicate/loot/item
modifier resources, and nested loot tables.

## SNBT and Data Paths

The runtime accepts SNBT-lite values such as:

```snbt
{foo:[1b,2s,{bar:"baz"}],flag:true}
```

Data paths support fields, numeric list indexes, and simple object matchers for
arrays:

```text
foo.bar
foo[0].bar
Items[{Slot:0b}].id
```

The same path engine is used by `data`, predicates, loot functions, and
advancement conditions.

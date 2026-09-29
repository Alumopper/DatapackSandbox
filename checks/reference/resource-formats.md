# Resource Formats

## When to use this page

Use this page while authoring or debugging datapack directories, `pack.mcmeta`, JSON/SNBT resources, tag overlays, and resource behavior levels.

## Prerequisites

Choose the target version profile and prepare a directory or ZIP datapack. New `26.2` examples should prefer singular resource directories.

## Minimal runnable example

Run `java -jar cli/build/libs/datapack-sandbox-cli.jar resources --pack examples/full-stack/pack` to inspect loaded resources and overlay relationships.

## Full capabilities

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
- `data/<namespace>/(chat_type|damage_type|dimension|dimension_type|enchantment|equipment_asset|jukebox_song|trim_material|trim_pattern|...)/**/*.json`
- `data/<namespace>/worldgen/(configured_feature|placed_feature|structure|processor_list|...)/**/*.json`
- `data/<namespace>/tags/<registry>/**/*.json`

Compatibility profiles keep their own data pack formats. For example,
`26.1`/`26.1.1`/`26.1.2` use `101.1`, `1.21.11` uses `94.1`, and `1.20.5`
through `1.20.6` use `41`.

Legacy aliases are accepted only where the active `VersionProfile` allows them.
Format mismatches are reported as non-fatal `VERSION_MISMATCH` load warnings so
tests can continue against packs that intentionally use a nearby format. JSON
parse failures and malformed metadata still fail and include the file path,
resource id, and version.

For newer packs that declare a supported range instead of a single
`pack_format`, the loader accepts `min_format`/`max_format` and
`supported_formats` when the active profile's data pack format is inside the
range.

Format values may be written either as JSON numbers or as one/two-integer
tuples. For example, `[107, 1]` is treated as `107.1`, and `[94]` is treated as
`94`:

```json
{
  "pack": {
    "min_format": [94],
    "max_format": [107, 1],
    "description": "Example range pack"
  }
}
```

## Resource Behavior Levels

The runtime exposes the same type list through `ResourceCatalog`; loader raw
JSON coverage and docs tooling should use that catalog instead of maintaining
separate resource-type lists.

The standalone CLI can export the resource catalog for scripts and docs:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar resources
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs --output docs/resource-catalog.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --check docs/resource-formats.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs --locale zh-CN --check docs/resource-formats.zh-CN.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --json --output build/resource-catalog.json
```

`resources --check` verifies that each cataloged resource type appears in the
document with the matching behavior level. Use `--locale zh-CN` to generate or
check the localized catalog table in the Chinese document. The Gradle `check`
lifecycle runs both English and localized docs checks through standalone jar
smoke tasks.

| Level | Meaning |
|---|---|
| `exact` | The documented surface is intended to match vanilla-observable behavior. |
| `modeled` | The resource is loaded into deterministic sandbox runtime behavior. |
| `observed-noop` | The resource is version-checked, indexed, overlay-aware, and inspectable, but full runtime semantics are intentionally absent. |
| `unsupported` | The resource is not loaded or is rejected by the current sandbox. |

| Resource | Behavior | Runtime/debug surface |
|---|---|---|
| `function` | `modeled` | mcfunction execution, trace source locations, and missing-reference checks |
| `tag/function` | `modeled` | load/tick/function tag execution and replace semantics |
| `loot_table` | `modeled` | deterministic loot generation for supported contexts and commands |
| `predicate` | `modeled` | predicate command/API checks, advancement conditions, loot conditions, item modifiers, and enchantment-aware random chance |
| `advancement` | `modeled` | player progress, criteria matching, rewards, structured reward output, and event trace |
| `recipe` | `modeled` | resource index entries plus player recipe state for commands and rewards |
| `item_modifier` | `modeled` | common item modifier functions applied by item modify |
| `tag/<registry>` | `observed-noop` | general tags with replace semantics and resource-index visibility |
| `banner_pattern` | `modeled` | item component registry JSON metadata exposed by item command outputs |
| `cat_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |
| `chat_type` | `modeled` | chat type JSON metadata exposed by modeled chat commands |
| `chicken_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |
| `cow_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |
| `damage_type` | `modeled` | damage type JSON metadata exposed by the damage command |
| `dialog` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `dimension` | `modeled` | dimension JSON metadata exposed by dimension-aware command outputs |
| `dimension_type` | `modeled` | dimension type JSON metadata exposed through dimension resources |
| `enchantment` | `modeled` | enchantment JSON metadata exposed by the enchant command |
| `enchantment_provider` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `equipment_asset` | `modeled` | equipment asset JSON metadata exposed by item command outputs |
| `frog_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |
| `instrument` | `modeled` | instrument JSON metadata exposed by item command outputs |
| `jukebox_song` | `modeled` | jukebox song JSON metadata exposed by item command outputs |
| `painting_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |
| `pig_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |
| `test_environment` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `test_instance` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `trim_material` | `modeled` | armor trim material JSON metadata exposed by item command outputs |
| `trim_pattern` | `modeled` | armor trim pattern JSON metadata exposed by item command outputs |
| `wolf_sound_variant` | `modeled` | wolf sound variant JSON metadata exposed by the summon command |
| `wolf_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |
| `worldgen/biome` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `worldgen/configured_carver` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `worldgen/configured_feature` | `modeled` | simple_block, block_column, disk, vegetation_patch, tree, basalt_columns, delta_feature, lake, spring_feature, block_pile, glowstone_blob, forest_rock, netherrack_replace_blobs, chorus_plant, replace_single_block, replace_blob, selector, random_patch, flower, and ore feature JSON consumed by place feature |
| `worldgen/density_function` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `worldgen/flat_level_generator_preset` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `worldgen/multi_noise_biome_source_parameter_list` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `worldgen/noise` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `worldgen/noise_settings` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `worldgen/placed_feature` | `modeled` | placed feature JSON resolving configured simple_block/block_column/disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replace_single_block/replace_blob/selector/random_patch/flower/ore resources for place feature |
| `worldgen/processor_list` | `modeled` | block_ignore, protected_blocks, jigsaw_replacement, capped, nop, and rule processors with block/tag predicates consumed by sandbox structure placement |
| `worldgen/structure` | `modeled` | sandbox structure JSON and binary structure NBT palette blocks/entities can be expanded by `place structure` and `place template` |
| `worldgen/structure_set` | `observed-noop` | version-checked raw JSON resource indexed for inspection |
| `worldgen/template_pool` | `modeled` | single/legacy/list/feature pool elements, fallback pools, and deterministic jigsaw connector expansion consumed by sandbox place jigsaw |
| `worldgen/world_preset` | `observed-noop` | version-checked raw JSON resource indexed for inspection |

## `.dps.json` Manifests

Manifests declare version matrices, datapacks, world fixtures, execution steps, assertions, and coverage gates. Start with [Manifest Regression Tests](/en/workflows/manifest-tests), then use the [Manifest Reference](/en/reference/manifest) for complete fields, include merging, relative-path resolution, steps, and assertions.

The canonical JSON Schema remains at `schema/manifest/dps-manifest.schema.json` and can also be exported or checked through the `schema` CLI command. This heading remains so existing section links continue to work.

## Raw JSON Resources and Tags

Chat types, damage types, dimensions, dimension types, enchantments, entity variants, item component registries, armor trim resources, recipes, item modifiers, and additional registry resources are loaded as raw
JSON resources and included in the resource index. The sandbox does not yet
execute the full crafting system, every item modifier function, or full
worldgen semantics, but it models sandbox structure placement, processor_list
block_ignore/protected_blocks/jigsaw_replacement/capped/nop/rule block/tag predicate handling, template_pool single/legacy/list/feature/fallback jigsaw placement plus deterministic connector expansion,
simple_block, block_column, deterministic disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replacement/selector/random_patch/flower and sparse-world ore feature placement, chat type metadata in modeled chat commands, and
damage type metadata in `damage` command output, dimension and dimension type metadata in dimension-aware command outputs, enchantment metadata in `enchant` command output, entity variant metadata in `summon` output, plus equipment asset, banner pattern, instrument, jukebox song, and armor trim material/pattern metadata in item command outputs; the remaining resources are version-profile checked,
participate in pack overlay behavior, and can be inspected from API or REPL.
`recipe give` and `recipe take` update player recipe state and report the
concrete changed recipe ids in structured output. Loot tables can expand
item tag entries, including nested tags and optional values; `expand=false`
emits the full tag and `expand=true` makes tag items selectable entries. Loot functions include
common count/item/component/enchantment mutations, tool-driven `apply_bonus`,
`copy_name` from entity context, and `copy_components` from the active tool with
`include`/`exclude` filters, plus `reference` to reuse item modifier resources.
Loot
conditions and predicates can read the active tool's `minecraft:enchantments`
component for `random_chance_with_enchanted_bonus`, including flat and `levels`
component forms, modern `enchanted_chance` level values, and legacy looting or
bonus multipliers. Common loot conditions also cover `table_bonus`,
`killed_by_player`, and `value_check` with constant, uniform, binomial, and
score-based number providers. `item modify entity`
models common item modifier functions such as `set_components`,
`set_custom_data`, `set_count`, `limit_count`, `set_item`, `discard`,
`set_damage`, `set_name`, `set_lore`, `copy_nbt`, `copy_components` with
`include`/`exclude`, `filtered`, `reference`, and `sequence`.
Entity item commands support player inventory, selected-mainhand, and
`enderchest.*` slots, plus non-player equipment slots such as
`weapon.mainhand`, `weapon.offhand`, and
`armor.*`. `give`, `clear`, and `item replace ... with` accept item arguments with
JSON/SNBT-lite NBT (`minecraft:stick{marked:true}`) and component payloads
(`minecraft:stick[custom_data={marked:true}]`); whitespace inside bracketed
payloads is preserved during command tokenization. Equipment is also exposed through snapshots and
`HandItems`/`ArmorItems` entity NBT. Entity
predicates can match `equipment` fields for `mainhand`, `offhand`, `head`,
`chest`, `legs`, and `feet`, active `effects` with amplifier/duration and
particle visibility, plus `distance` ranges for `absolute`, `horizontal`, and
axis-specific `x`/`y`/`z` distances; item predicates can match explicit item ids
or `#` item tags, plus `enchantments` or `stored_enchantments` by id and level
range. Entity `nbt` predicates are checked
against the full generated entity NBT view. Predicate `location_check` block
conditions read explicit sparse blocks by id, block tag, state/property values,
and block entity NBT at the tested location. Its biome conditions read the
sparse biome overrides declared by `fillbiome`, world fixtures, manifests, or
quick-test setup; unassigned positions do not imply a generated biome.
`block_state_property` checks the current block id and, when a full sparse block
context is available, its state properties.

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
data/<namespace>/worldgen/structure/**/*.nbt
data/<namespace>/structure/**/*.nbt
data/<namespace>/structures/**/*.nbt
data/<namespace>/worldgen/processor_list/**/*.json
data/<namespace>/enchantment/**/*.json
data/<namespace>/equipment_asset/**/*.json
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
inspect registry [group]
```

The resource index records type, id, source pack, file path, active/overridden
state, and pack overlay relationships. `datapack list [available|enabled]`
includes `filter`, `packCount`, `packs`, `overriddenResources`,
`resourceOverrides`, and `missingReferences` in its structured output payload
so generated command-output tests can assert loaded-pack, overlay, and
missing-reference behavior without entering the REPL. `check --verbose` also prints a resource summary, overlay
entries, and missing direct references from load/tick function tags and
advancement parents/rewards, predicate references in predicate/loot/item
modifier resources, and nested loot tables. `inspect registry [group]` lists
version-profile registry entries with `source=profile:<version>`, so registry
lookups can be debugged with the same loaded profile that command execution and
completion use. The non-interactive `resources --registry --registry-group
<group> --json` form exports the same profile registry data for CI artifacts and
generated-command tests.

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

## Limitations

An `observed-noop` resource is validated, indexed, and inspectable, but does not imply complete runtime semantics. Full crafting, world generation, and every item modifier remain outside the model.

## Related pages

- [Manifest Regression Tests](/en/workflows/manifest-tests)
- [Manifest Reference](/en/reference/manifest)
- [Version Profiles](/en/resources/version-profile)
- [Command Support](/en/runtime/command-support)

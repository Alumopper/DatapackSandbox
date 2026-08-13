# Manifest Reference

## When to use this page

Use this page to look up top-level fields, merge/path rules, world fixture groups, steps, and assertion families. Read [Manifest Regression Tests](/en/workflows/manifest-tests) first for the authoring workflow. The schema embedded in the current CLI jar is authoritative for exact JSON shapes.

## Export and validate the schema

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --output build/dps-manifest.schema.json
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --check schema/manifest/dps-manifest.schema.json
java -jar cli/build/libs/datapack-sandbox-cli.jar check cases --validate-schema
```

Use `$schema` for editor feedback, but do not rely on an editor alone: CLI validation uses the schema packaged with the runtime that will execute the case.

## Minimal valid shape

```json
{
  "$schema": "../../schema/manifest/dps-manifest.schema.json",
  "version": "26.2",
  "packs": ["pack"],
  "steps": [{ "function": "demo:main" }],
  "assertions": [
    { "score": { "target": "#case", "objective": "runs", "equals": 1 } }
  ]
}
```

The `.dps.json` suffix is used by recursive discovery.

## Top-level fields

| Field | Shape | Default / meaning |
| --- | --- | --- |
| `include` | string or string[] | Shared Manifest files applied first |
| `version` | string | One profile; mutually exclusive with `versions` |
| `versions` | string[] | Isolated attempt for every listed profile |
| `unsupported` | `warn`, `ignore`, or `error` | Recognized unmodeled behavior policy |
| `seed` | integer | Deterministic world/random seed override |
| `failOnMissingResources` | boolean | Fail direct missing resource references |
| `coverage` | object | Thresholds and resource-id glob filters |
| `packs` | string[] or version map | Ordered folder/zip datapacks |
| `world` | object | Sparse fixture applied before steps |
| `steps` | array | Ordered actions |
| `assertions` | array | Expectations evaluated after steps |

Unknown top-level fields are rejected. Omitting both `version` and `versions` selects the CLI's current default profile; explicit selection is recommended.

### Coverage

| Field | Meaning |
| --- | --- |
| `minimumLine` | Minimum executable-line percentage |
| `minimumFunction` | Minimum invoked-function percentage |
| `include` | One or more included resource-id globs |
| `exclude` | One or more excluded resource-id globs |

Threshold failures are assertion failures. Filters change the denominator, so review the detailed coverage artifact when changing them.

## Include and merge rules

Includes may nest and are resolved depth-first; cycles fail. Sections apply in include-to-current order:

| Section | Merge behavior |
| --- | --- |
| Scalar defaults (`version`, policy, seed, etc.) | Later/current value overrides an inherited default |
| `packs` | Append in order; later packs have higher resource priority |
| `steps` | Append; execution preserves the merged order |
| `assertions` | Append; all are evaluated |
| `world` | Apply each section sequentially; later same-key/same-position state wins |

Use one semantic baseline rather than long include chains. The final report describes resolved attempts, but the source location in diagnostics still points to the declaring section when available.

## Path resolution

| Path-bearing value | Base directory |
| --- | --- |
| CLI root input | Process working directory |
| `include` | Manifest that contains the include |
| `packs` entry | Manifest that contains that packs section |
| `world.extends` / `fixture` / `fixtures` | World fixture/Manifest that declares it |
| `world.save` / `saves` | Declaring world fixture/Manifest |
| Step `mcfunction` | Manifest that declares the step |
| Snapshot `equalsFile` | Manifest that declares the assertion |

Normalize paths in generators, but keep them relative when a case should be relocatable with its project tree.

## World fixture

The `world` object represents explicit sparse state, not generated terrain. Its schema groups are:

| Group | Fields / purpose |
| --- | --- |
| Reuse | `extends`, `fixture`, `fixtures` |
| Time/profile state | `gameTime`/`time`, `dayTime`, `seed`, `difficulty`, `defaultGameMode`, `weather`, `weatherDuration` |
| World geometry | `worldSpawn`, `worldBorder`, `forcedChunks`, `biomes`, `blocks`, `regions`, `structures` |
| Actors | `entities`, `players`, `teams`, `bossbars` |
| Data systems | `scores`, `storage`/`storages`, `gamerules`, `randomSequences` |
| Existing-save slice | `save`, `saves` |

Aliases such as `defaultGamemode` and plural/singular storage/save forms exist for supported input compatibility; new files should use the primary spelling shown in generated schema descriptions.

### Fixture example

```json
{
  "world": {
    "gameTime": 100,
    "weather": "clear",
    "worldSpawn": [0, 64, 0],
    "forcedChunks": [[0, 0]],
    "biomes": [{ "pos": [0, 64, 0], "id": "minecraft:plains" }],
    "blocks": [{ "pos": [0, 63, 0], "id": "minecraft:stone" }],
    "entities": [{ "type": "minecraft:pig", "pos": [1, 64, 0], "tags": ["fixture"] }],
    "players": [{ "name": "Alex", "position": [2, 65, 3], "xp": 5 }],
    "scores": [{ "target": "#fixture", "objective": "ready", "value": 1 }],
    "storage": { "demo:env": { "ready": true } }
  }
}
```

See [QuickTest Fixtures](/en/reference/quicktest-fixtures) for fixture semantics and save-import boundaries.

## Step reference

A step chooses one primary action. Common companion fields are `source` for diagnostic attribution and `allowFailure` for expected command/function failures.

| Entry | Value | Effect |
| --- | --- | --- |
| `load` | boolean | Run load-tag functions when true |
| `ticks` | integer | Advance that many ticks |
| `function` | resource id | Invoke a loaded function |
| `command` | string | Execute one command |
| `commands` | string[] | Execute ordered raw commands |
| `functionText` | string | Execute inline `.mcfunction` content |
| `mcfunction` | path | Load and execute a generated file |
| `player` | object/string form | Create/configure a player |
| `block` | object | Set fixture-style block state |
| `event` | object | Inject a player event; requires `player` and `type` |
| `snapshot` | boolean/path options | Capture state during the sequence |
| `trace` | boolean/options | Control trace behavior during the sequence |
| `reset` | boolean/options | Reset modeled world state |
| `loot` | object | Generate a loot request |

`commands` skips no semantic errors unless `allowFailure` is set. `functionText`/`mcfunction` should use `source` when generator provenance matters.

### Player event fields

Every event has `player` and `type`. Depending on the type, the object may also provide item/count/components/NBT, entity/target, block id and position, recipe, dimension transition, damage source/type/amount, or keyboard/mouse input code/action/coordinates. Unsupported combinations produce input or missing-context diagnostics; see [Player Events](/en/runtime/player-events).

## Assertion reference

Each assertion object selects one family. Add enough constraints to avoid matching unrelated state/events.

| Family | Important fields |
| --- | --- |
| `score` | `target`, `objective`, `equals` or `min`/`max` |
| `storage` | `id`, optional `path`, equality/existence/contains/regex |
| `world` | time/seed/weather/difficulty/gamemode/random/forced chunk/biome/spawn/border |
| `gamerule`, `randomSequence`, `forcedChunk` | Key/coordinates, expected value/state/existence |
| `block` | `pos`, id/existence/NBT |
| `entityCount` | type/tag/dimension plus equals/min/max |
| `entity` | identity/position/dimension/health/vehicle/NBT/count and nested equipment/effect/attribute |
| `player` | name, existence, XP, inventory, dimension/mode/health/food, recipe/effect/stat/NBT/input/spawn |
| `team`, `bossbar` | existence, display/options/members or value/style/players |
| `scheduled` | function id, due tick, existence, count |
| `scoreboardObjective`, `scoreboardDisplay` | metadata/display-slot state |
| `advancement` | player/id plus done/criterion state |
| `predicate` | id, optional player, boolean result |
| `loot` | table/context/player/seed plus item/count |
| `item` | player/id/count range/slot/container/existence/components/NBT |
| `trace` | command/root/source/function/success/count, related output/diff filters |
| `eventTrace` | player/type/success, criterion/failure, subject, damage/input details, count |
| `diagnostic` | step/version/code/command/root/message/count |
| `snapshot` | optional data path plus equals/file/existence |
| `snapshotDiff` | JSON Pointer/path substring, kind, before/after/contains/count |
| `output` | command/channel/target, raw/normalized text, payload, styled segment, count/order |

### Snapshot path distinction

`snapshot.path` uses the sandbox's data-path lookup syntax such as `storage.demo:golden.ready`. `snapshotDiff.path` is a JSON Pointer such as `/storage/demo:golden/ready`. Do not interchange them.

### Output matching

Output assertions can distinguish raw and normalized text, constrain command/channel/targets, inspect styled segments, and compare a structured payload path. Prefer payload matching for machine semantics and text/segment matching for user presentation. Set `count` and, when relevant, `order` to make broad matches deterministic.

## Validation versus execution

- Schema validation checks JSON shape, required fields, types, enums, and unknown properties.
- Runtime resolution checks paths, resource ids, active pack overrides, version support, and context.
- Assertions check final observations after the merged step sequence.
- `--strict` combines schema validation with unsupported-as-error and missing-resource failure.

## Limitations

- Pack order is semantic; reordering can change the active resource without changing the schema.
- Steps retain some extension flexibility but still need one meaningful entry point; do not place unrelated actions in one object.
- Full snapshots/reports may gain modeled fields. Prefer narrow assertions and ignore unknown report fields.
- Consult the exported schema for nested entity/player/item component/NBT shapes not reproduced in this task-oriented reference.

## Related pages

- [Manifest Regression Tests](/en/workflows/manifest-tests)
- [QuickTest Fixtures](/en/reference/quicktest-fixtures)
- [QuickTest Assertions](/en/reference/quicktest-assertions)
- [Resource Formats](/en/resources/resource-formats)

# QuickTest Fixture Reference

## When to use this page

Configure a fixture before behavior when a function depends on players, blocks, entities, scores, storage, world time, teams, bossbars, registries, or selected Java-save data. Fixtures make those dependencies explicit and deterministic without starting a vanilla world.

## Prerequisites

Depend on `testkit` and create a `SandboxQuickTest` scenario. Fixture calls mutate the scenario's current sandbox immediately; they are not deferred configuration. Apply fixtures before `load()`, `function()`, commands, ticks, or events that consume them.

## Minimal runnable example

```kotlin
import moe.afox.dpsandbox.core.SandboxQuickTest

SandboxQuickTest.singleFunctionText(source, version = "26.2")
    .world {
        player("Alex", x = 0.0, y = 64.0, z = 0.0, xp = 3)
        block(0, 63, 0, "minecraft:stone")
        score("Alex", "runs", 0)
        storage("demo:state", "{ready:true}")
    }
    .function()
    .assertScore("Alex", "runs", 1)
    .requirePassed()
```

The player, block, objective/score, and storage exist before `sandbox:main` runs.

## Fixture lifecycle

The recommended chain is:

```text
create scenario → apply world/setup/import → run behavior → assert → report
```

Calling `.world { ... }` more than once applies each block in call order. Later operations can replace the same block, entity key, score, storage value, or world property. Behavior can also mutate fixture state, so do not reuse one mutable scenario across tests or threads.

## World-level state

`SandboxWorldSetup` can set:

| Area | Examples |
| --- | --- |
| Clock | game time and day time |
| Identity/policy | seed, difficulty, default game mode |
| Environment | weather and duration, world spawn, world border |
| Tick state | tick rate and freeze-related modeled state |
| Spatial indexes | forced chunks and biome overrides |
| Data systems | gamerules, random sequences, scores, storage |

Only values you set exist in the sparse model. A biome override at one position or a forced chunk entry does not generate terrain or start vanilla chunk ticking.

## Blocks, regions, and structures

### Individual blocks

Use `block(x, y, z, id, ...)` for a precise state. Optional block properties and NBT represent modeled block state/block-entity data and are validated against the active profile where supported.

```kotlin
.world {
    block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
    block(1, 64, 0, "minecraft:red_wool")
}
```

### Regions

Region helpers fill an explicit cuboid with one state. Prefer them over hundreds of repeated calls, but keep bounds small so test intent and snapshot size remain clear.

### Structures

Structure fixtures can place a reusable explicit collection of structure blocks and structure entities. Placement is deterministic and does not invoke the vanilla structure-placement engine, processors, terrain adaptation, or world generation. When testing the modeled `place structure` command itself, distinguish fixture setup from the command's observable output/payload.

## Entities and players

Entity fixtures can set type, UUID/name/tag identity, position/dimension, health, NBT, equipment, effects, attributes, and supported special-entity state. Player fixtures additionally model game mode, XP/levels, food, selected slot, inventory/ender items, effects, recipes, statistics, spawn, and last input where supported by the setup model.

```kotlin
.world {
    player("Steve", x = 0.0, y = 65.0, z = 0.0, xp = 5)
    entity("minecraft:pig", x = 1.0, y = 64.0, z = 0.0, tags = listOf("fixture"))
}
```

Use the returned/created player through normal commands and player events; selectors resolve against this fixture state. Entities do not gain vanilla AI/physics merely because they were created.

## Inventory, equipment, effects, and attributes

Player inventories and entity equipment accept item ids, counts, slots/containers, components, and NBT supported by the active version profile. Effects include id, duration, amplifier, and visibility fields. Attributes include base/current modeled values. Create only the exact inventory/effect state needed by the test, then use `assertItem`, `assertEntityEquipment`, `assertEntityEffect`, or `assertEntityAttribute` rather than comparing a whole player/entity snapshot.

## Scoreboards, storage, teams, and bossbars

- `score(target, objective, value, criteria)` ensures the objective and score exist.
- `storage(id, snbt/json)` seeds command-visible storage.
- Team fixtures create members and modeled options such as display/color behavior.
- Bossbar fixtures create id, name, value/max, color/style, visibility, and player membership.
- Scoreboard metadata/display helpers model objectives and display slots when those are the contract.

These fixtures are especially useful before a load/function test because they avoid setup commands obscuring coverage and traces of the function under test.

## Reuse a setup object

Create a `SandboxWorldSetup`, configure it once, and apply it with `.setupWorld(setup)` to scenarios that need the same deterministic environment. Reusable setup is ordinary mutable configuration: finalize it before sharing, and do not mutate it concurrently while tests apply it.

For file-based reuse across CLI and JVM tests, keep a Manifest-style fixture JSON and apply the matching Manifest workflow. The JSON `world` object covers the same concepts and supports `extends`, `fixture`, and `fixtures` layering.

## Import a Java save slice

Use an explicit list of chunks and dimension:

```kotlin
test.importSave(
    path = Path.of("fixtures/world"),
    chunks = listOf(ChunkPos(0, 0), ChunkPos(1, 0)),
    dimension = "minecraft:overworld",
    includeBlocks = true,
    includeBlockEntities = true,
    includeEntities = false,
)
```

The importer reads only requested Java Anvil data and copies selected modeled content into the sparse world. It does not launch that save, run datafixers like a vanilla server, generate missing chunks, or import player/network state implicitly. Keep a tiny, reviewed chunk selection under test data; large world directories are slow, opaque fixtures.

## Matrix fixtures

`SandboxQuickTestMatrix` mirrors the primary behavior/fixture/assertion surface across version-specific scenarios. A matrix fixture is applied to every selected profile's isolated sandbox. Use version-keyed pack lists when resource layouts/content differ, and avoid fixture values that are invalid in one profile unless the test is intentionally profile-specific.

## Manifest equivalent

```json
{
  "world": {
    "time": 100,
    "players": [{ "name": "Alex", "position": [0, 64, 0], "xp": 3 }],
    "blocks": [{ "pos": [0, 63, 0], "id": "minecraft:stone" }],
    "scores": [{ "target": "Alex", "objective": "runs", "value": 0 }],
    "storage": { "demo:state": { "ready": true } }
  }
}
```

Use this representation when the same setup should be readable by CLI `check`, `run --world`, REPL fixture loading, or Serve `applyWorldFixture`.

## Diagnose fixture problems

| Symptom | Check |
| --- | --- |
| Selector matches nobody | Player/entity name, tag, type, dimension, and position |
| Score command fails | Objective was created and target spelling matches |
| Storage path is missing | Seeded JSON/SNBT shape and namespaced storage id |
| Block NBT is rejected | Block id, active version profile, and schema-valid top-level fields |
| Save import is empty | Dimension, chunk coordinates, inclusion flags, and Anvil files |
| Function observes old state | Fixture was applied before behavior and no reset replaced it |

Inspect `test.sandbox.snapshotJson()` or use targeted world access while debugging; convert the final observation into an assertion rather than leaving ad-hoc inspection in the test.

## Limitations

- The world is sparse. Unset blocks, biomes, entities, and chunks do not imply generated vanilla state.
- NBT/components are profile-sensitive and only modeled fields/operations are meaningful.
- Save imports are explicit slices, not complete-world migration.
- Fixture helpers create state; they do not simulate the gameplay sequence that would normally create it. Use steps/events when that sequence is the behavior under test.

## Related pages

- [World Model](/en/runtime/world-model)
- [QuickTest Overview](/en/guide/code-test-api)
- [QuickTest Assertions](/en/reference/quicktest-assertions)
- [Manifest Reference](/en/reference/manifest)

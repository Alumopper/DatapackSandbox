# Runtime World Model

The sandbox world is an in-memory sparse model:

- Blocks are stored only when explicitly placed. The initial world is void.
- `setblock` and `fill` do not run physics, redstone, neighbor updates, or block
  entity ticks. They accept block state plus initial block entity SNBT, for
  example `minecraft:chest{Items:[...]}`.
- The runtime creates a default player named `Steve`. That player is included in
  selectors and exposes readable vanilla-style NBT.
- Entities store a deterministic NBT view with generated `id`, `UUID`, `Pos`,
  and `Tags`, plus vanilla fields allowed by the generated mcdoc schema.
  Non-player entity NBT can be mutated with `data`, but unknown top-level fields
  are rejected.
- Block entities are discovered from generated mcdoc block-to-block-entity
  dispatch mappings. Their NBT view exposes vanilla-style `id`, `x`, `y`, and
  `z` plus schema fields. Unknown custom block NBT fields are rejected instead
  of being stored as arbitrary sandbox JSON.
- Players expose a readable NBT view but are read-only for `data` writes.
  Movement uses `tp|teleport` or manifest/player-event APIs.
- Ticks run scheduled functions, tick tags, and sandbox player events. Entity AI
  and gravity are not simulated; `NoAI` is not injected.

## Test World Fixtures

Both `.dps.json` manifests and the quick-test API can define initial world
state before any `steps` or commands run. Supported fixture inputs include:

- `blocks` with block id, state properties, and validated block entity NBT.
- `entities` with type, UUID, position, dimension, health, tags, rotation,
  vehicle/passengers, equipment, active effects, attributes, and validated entity NBT.
- `players` with position, dimension, game mode, inventory, ender items,
  advancement progress, XP, health, and food; newly created players start with
  the current `defaultGameMode`.
- `scores`, `storage`, `gamerules`, `gameTime`, `dayTime`, and `weather`.

Example manifest:

```json
{
  "version": "26.2",
  "packs": ["./pack"],
  "world": {
    "blocks": [
      { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
    ],
    "entities": [
      {
        "type": "minecraft:pig",
        "uuid": "00000000-0000-0000-0000-000000000101",
        "pos": [1, 64, 0],
        "dimension": "minecraft:the_nether",
        "health": 8.0,
        "vehicle": "00000000-0000-0000-0000-000000000102",
        "tags": ["fixture"],
        "equipment": {
          "weapon.mainhand": { "id": "minecraft:iron_sword" }
        },
        "effects": [
          { "id": "minecraft:strength", "duration": 80, "amplifier": 2 }
        ],
        "attributes": {
          "minecraft:max_health": 12.0
        }
      },
      {
        "type": "minecraft:cow",
        "uuid": "00000000-0000-0000-0000-000000000102",
        "pos": [1, 64, 1],
        "tags": ["fixture_vehicle"],
        "passengers": ["00000000-0000-0000-0000-000000000101"]
      }
    ],
    "players": [
      { "name": "Alex", "position": [2, 65, 3], "xp": 5 }
    ],
    "storage": {
      "demo:env": { "ready": true }
    }
  },
  "steps": [],
  "assertions": [
    {
      "entity": {
        "type": "minecraft:pig",
        "tag": "fixture",
        "dimension": "minecraft:the_nether",
        "health": 8.0,
        "vehicle": "00000000-0000-0000-0000-000000000102",
        "nbt": { "path": "Health", "equals": 8.0 },
        "equipment": { "slot": "weapon.mainhand", "id": "minecraft:iron_sword" },
        "effect": { "id": "minecraft:strength", "duration": 80, "amplifier": 2 },
        "attribute": { "id": "minecraft:max_health", "equals": 12.0 }
      }
    }
  ]
}
```

## Java Save Imports

World fixtures can also import selected chunks from an existing Minecraft Java
Edition save:

```json
{
  "world": {
    "save": {
      "path": "./saves/MyWorld",
      "dimension": "minecraft:overworld",
      "chunks": [[0, 0], [1, 0]],
      "includeBlocks": true,
      "includeBlockEntities": true,
      "includeEntities": true
    }
  }
}
```

Use `"from": [x, y, z]` and `"to": [x, y, z]` instead of `chunks` when a block
range is more convenient; the sandbox expands that range to chunk coordinates.

The importer reads Java Anvil `.mca` files from `region/` and `entities/`.
GZip, zlib, uncompressed, and LZ4 chunk compression are supported. Overworld,
Nether, End, and custom dimension folders under `dimensions/<ns>/<id>` are
supported. It imports block states, block entities, and entity NBT, then passes
the NBT through the same version-profile validation used by commands.
It intentionally does not import lighting, heightmaps, POI, scheduled block
ticks, chunk tickets, playerdata, region metadata, or full vanilla world
lifecycle state.

## Vanilla Data From mcdoc

Run:

```powershell
.\gradlew.bat generateVanillaNbtSchemas --no-daemon --console=plain
```

This downloads `SpyglassMC/vanilla-mcdoc` into `build/vanilla-mcdoc`, installs
the official `@spyglassmc/mcdoc` parser, parses the mcdoc AST, and writes
`build/generated/vanilla-mcdoc/resources/vanilla-nbt-schemas.json`. The `:core`
resource pipeline depends on this task, so the runtime loads that generated
schema from the classpath and only falls back to the old conservative in-code
schema if the resource is absent.

The older `generateVanillaMcdocIndex` task still exists as a quick lexical
inventory for humans and experiments, but it is not the NBT validation source.
Fine-grained `since`/`until` attribute pruning is still a follow-up; the current
runtime consumes the upstream vanilla mcdoc snapshot and keeps strict unknown
field diagnostics at the top level.

## Client vs Sandbox

Embedding a real Minecraft client/server is heavier for this project: it brings
rendering, assets, auth/session assumptions, native/platform concerns, and
vanilla thread/world lifecycle constraints. For datapack-oriented local checks,
the finite clean-room simulation is lighter, deterministic, easier to run in
CLI/CI-like contexts, and easier to make strict about missing context.

A vanilla runtime is still useful as an oracle for command trees, registries,
generated reports, and edge-case behavior. The practical split is: keep this
sandbox as the fast deterministic runner, and use vanilla/mcdoc data as
reference input for validation and tests.

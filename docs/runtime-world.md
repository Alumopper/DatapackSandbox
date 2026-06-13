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

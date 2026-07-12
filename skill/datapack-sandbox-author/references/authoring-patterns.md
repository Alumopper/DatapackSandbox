# Complex Datapack Authoring Patterns

## Contents

- Current resource layout
- System decomposition
- State choices
- Execution context
- Idempotency and cleanup
- Scheduling and tick work
- Cross-version design
- Agent-sized implementation slices

## Current resource layout

For current profiles, prefer:

```text
pack.mcmeta
data/<namespace>/function/
data/<namespace>/advancement/
data/<namespace>/predicate/
data/<namespace>/loot_table/
data/<namespace>/item_modifier/
data/<namespace>/recipe/
data/<namespace>/tags/function/
```

Do not mechanically rename an existing legacy pack. Query the target profile and follow the pack's established layout.

## System decomposition

Organize a complex feature around stable boundaries:

```text
function/feature/start.mcfunction       public entry
function/feature/tick.mcfunction        scheduled/tick entry
function/feature/internal/validate.mcfunction
function/feature/internal/advance.mcfunction
function/feature/internal/reward.mcfunction
function/feature/internal/cleanup.mcfunction
```

Keep trigger resources such as advancements and predicates declarative. Route substantial logic into functions so traces and focused tests remain readable.

## State choices

Use:

- scoreboard for small numeric state, timers, flags, and arithmetic;
- storage for structured global/session data and generated records;
- advancement criteria for player trigger progress;
- entity/block NBT only when the game object is the true owner of state;
- tags for membership, dispatch, and resource grouping.

Prefer one canonical owner for each state value. Avoid mirroring the same flag in scoreboard, storage, and tags without an explicit synchronization rule.

## Execution context

Declare whether each public function expects server, player, or entity execution. Manifest `function` steps run with a synthetic server source. For a player API, use a modeled player event or:

```json
{ "command": "execute as Steve at @s run function demo:feature/start" }
```

Test selectors and relative coordinates under the expected executor instead of calling player-bound functions directly.

## Idempotency and cleanup

Design these cases explicitly:

- load function executed more than once;
- reload after objectives or storage already exist;
- player trigger repeated in the same tick;
- scheduled function already queued;
- reward function called after completion;
- player/entity missing during cleanup;
- reset after partial progress.

Test both the first transition and the repeated no-op. A feature that passes once but duplicates rewards on retry is not complete.

## Scheduling and tick work

Keep tick functions cheap:

1. select only active participants;
2. delegate one transition per internal function;
3. remove completed participants immediately;
4. avoid broad selectors when a tag or score narrows the set;
5. make scheduled re-entry replace/append behavior intentional.

Use manifest `ticks` steps to assert exact boundaries before and after a due tick.

## Cross-version design

Choose the target profile before resource authoring. Use a manifest `versions` matrix only when the same pack layout and behavior are intended to work across profiles. For divergent formats, keep profile-specific pack roots and map them in `packs`.

Never assume that an accepted command has identical semantics across profiles. Compare live command/resource catalogs and add assertions for the behavior that matters.

## Agent-sized implementation slices

A good slice changes a small coherent resource set and gains one passing test, for example:

1. load objective + load manifest;
2. start trigger + state assertion;
3. tick transition + timing assertion;
4. reward + inventory/output assertion;
5. cleanup + repeat/no-op assertion;
6. full journey scenario.

Do not generate the entire system before the first CLI run. Early traces reveal unsupported assumptions while changes are still cheap.

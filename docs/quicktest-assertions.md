# QuickTest Assertion Reference

## When to use this page

After executing behavior, use fluent assertions to lock down observable state, resource behavior, output, traces, or coverage. Assertions accumulate failures so a test can report several mismatches at once. `report()` returns the current result; `requirePassed()` throws `SandboxQuickTestAssertionError` when any registered assertion failed.

## Prerequisites

```kotlin
val test = SandboxQuickTest
    .singleFunctionText(source, version = "26.2")
    .function()
```

The assertion reads the scenario at the point the method is called. Continue behavior first, then assert the intended checkpoint.

## Minimal runnable example

```kotlin
test
    .assertScore("#case", "runs", 1)
    .assertStorageEquals("demo:state", "ready", "true")
    .assertOutput(command = "say", contains = "ready", count = 1)
    .assertTrace(root = "scoreboard", success = true)
    .requirePassed()
```

## Failure collection and reports

Assertion methods return the same mutable scenario. A failed assertion records a human-readable message and the chain continues:

```kotlin
val report = test
    .assertScoreAtLeast("#case", "runs", 1)
    .assertOutputContains("ready")
    .report()

if (!report.passed) {
    report.failures.forEach(::println)
}
```

`SandboxQuickTestReport` contains `passed`, `failures`, `outputs`, `traces`, `playerEventTraces`, `snapshotDiffs`, `resourceSummary`, and the final `snapshot`. Use `requirePassed()` at the test boundary so JUnit/Gradle receives a failing test rather than relying on a manual boolean check.

## Scores and storage

| Goal | Entry point |
| --- | --- |
| Exact score | `assertScore(target, objective, expected)` |
| Lower/upper bound | `assertScoreAtLeast`, `assertScoreAtMost` |
| Optional range | `assertScoreRange(target, objective, min, max)` |
| Exact storage path value | `assertStorageEquals(id, path, expected)` |
| Path/root presence | `assertStorageExists` |
| Path/root absence | `assertStorageMissing` |

Storage expected values use the sandbox's NBT/JSON comparison semantics. Specify a path when only one field is contractual; comparing an entire storage object couples the test to unrelated fields.

```kotlin
test
    .assertScoreRange("#clock", "ticks", 20, 40)
    .assertStorageExists("demo:state", "result")
    .assertStorageEquals("demo:state", "result.ready", "true")
```

## World and scoreboard state

- `assertWorld(...)` checks selected clock, weather, difficulty, game mode, seed, spawn, border, tick, and related modeled fields.
- `assertBlock(x, y, z, id, exists, nbt, ...)` checks sparse block state.
- `assertGamerule`, `assertRandomSequence`, and `assertForcedChunk` check data-system state.
- `assertScheduledFunction` checks id, due tick, replacement/existence, or count as exposed by its overloads.
- `assertScoreboardObjective` checks objective existence and metadata.
- `assertScoreboardDisplay` checks a display slot and its objective.

Prefer focused helpers over a full snapshot whenever the contract is one world field.

## Players, entities, and collections

### Players

`assertPlayer` can constrain name/existence, position/dimension, game mode, XP/levels, health/food, inventory counts, selected slot, recipes, effects, statistics, NBT, spawn, and last input. Dedicated helpers include `assertPlayerXp`, `assertPlayerXpLevels`, and `assertPlayerLastInput`.

### Entities

`assertEntity` can match by type/tag/UUID/name/position/dimension and check existence, count, health, vehicle/passenger, NBT, or supported special state. Narrow matches are important: type-only expectations can accidentally select a fixture entity unrelated to the behavior.

Related helpers:

| State | Entry point |
| --- | --- |
| Exact/min/max/range count | `assertEntityCount*` |
| Equipment slot/item/components/NBT | `assertEntityEquipment` |
| Effect duration/amplifier/visibility/existence | `assertEntityEffect` |
| Attribute exact/min/max/existence | `assertEntityAttribute` |
| Inventory/container item count/components/NBT | `assertItem` |
| Team options/members/display | `assertTeam` |
| Bossbar value/style/visibility/players | `assertBossbar` |

Use resource ids and tags together when several same-type entities are expected.

## Resource behavior

`assertPredicate(id, expected, player)` evaluates a loaded predicate in the optional player's context. `assertLoot(table, context, player, seed, count, item)` evaluates a loot table deterministically. `assertAdvancementDone(player, id, expected)` checks completed advancement state; the broader player/manifest surfaces can inspect individual criteria.

These assertions execute or inspect modeled resource behavior. A missing resource is different from a false predicate or empty loot result and should remain a resource diagnostic.

## Output assertions

The convenience `assertOutputContains(text)` searches observable output text. The structured `assertOutput(...)`/`OutputExpectation` can constrain:

- command root, output channel, one target, or target set;
- raw exact/contains/regex text;
- normalized exact/contains/regex text;
- styled text segment fields;
- structured payload and payload path;
- match count and order.

```kotlin
test.assertOutput(
    command = "tellraw",
    channel = "chat",
    target = "Steve",
    contains = "reward ready",
    count = 1,
)
```

Use normalized text when whitespace/layout differences are irrelevant. Use raw text or styled segments when presentation is part of the contract. Use payload matching for structured effects such as resource ids or placement result flags.

`matchingOutputs(expectation)` returns matches without recording a failure, useful for custom aggregate checks. Avoid a bare text-only assertion when several commands can emit the same phrase.

## Command traces and snapshot diffs

`assertTrace(...)`/`TraceExpectation` can filter command/root, source/function, success, count, attached outputs, and attached snapshot changes. Trace entries are best for answering “which command caused this?” rather than replacing final-state assertions.

```kotlin
test
    .assertTrace(root = "scoreboard", success = true)
    .assertSnapshotDiff(path = "/scores/runs", kind = SnapshotDiffKind.ADDED)
```

`assertSnapshotDiff` uses JSON Pointer paths and checks kind (`ADDED`, `REMOVED`, or `CHANGED`), optional content, and count. Save/checkpoint boundaries determine which diffs have been recorded by the scenario. `matchingTraces` and `snapshotDiffs()` provide query access without adding failures.

## Player-event traces

`assertPlayerEventTrace(...)` can constrain player, event type, success, advancement/criterion result or failure reason, item/entity/block/recipe/dimension/damage details, keyboard/mouse device/code/action, and count.

Use it when the important contract is not only final state but why an advancement or player behavior did/did not trigger. Pair a trace assertion with a final-state assertion: the trace explains the decision, while state proves the effect.

`matchingPlayerEventTraces` queries the event record without registering a failure.

## Coverage assertions

`coverageReport(options)` returns accumulated function invocation and executable-line data. `assertCoverage(options)` records threshold failures in the QuickTest result. Apply filters to resource ids, not filesystem paths, and call coverage assertions after every behavior operation whose hits should count.

Coverage says that modeled lines/functions executed; it does not validate vanilla parity. Keep semantic assertions even when coverage is 100%.

## Matrix assertions

`SandboxQuickTestMatrix` mirrors the assertion surface and applies each expectation to every selected version scenario. `report()` returns `SandboxQuickTestMatrixReport` with aggregate failures and a report map keyed by version; `requirePassed()` throws `SandboxQuickTestMatrixAssertionError` when any version fails.

When expected behavior intentionally differs, split the scenario or assert only the shared contract. Do not weaken one expectation until it accidentally passes all profiles.

## Choosing durable assertions

1. Prefer user-observable output or small world/resource fields.
2. Constrain collection/event matches by id, target/tag, channel/type, and count.
3. Add trace assertions for causality, not as the only result assertion.
4. Use snapshot diffs for selected JSON Pointer paths.
5. Reserve whole-snapshot golden files for contracts that truly require complete state.

## Limitations

- Broad expectations with few constraints can match unrelated events or entities.
- Floating-point world/entity fields are deterministic sandbox values; avoid importing noisy external values without normalization.
- Complete snapshots may gain modeled fields over time; targeted assertions provide a more stable compatibility boundary.
- Assertion overloads are Kotlin/Java API. Consult `api/testkit.api` for binary signatures and the source/build for the exact release rather than depending on internal classes.

## Related pages

- [QuickTest Overview](/en/guide/code-test-api)
- [QuickTest Fixtures](/en/reference/quicktest-fixtures)
- [Reports and Observability](/en/reference/reports-observability)
- [Testing Patterns](/en/guide/testing-patterns)

# Troubleshooting

This page is organized by what you see when a test fails. Identify the failure category first, then decide whether to change the test, change the datapack, or keep the issue as a warning.

## Quick Diagnosis

| Symptom | Likely cause | Start here |
| --- | --- | --- |
| Build fails with Java or classfile errors | JDK/toolchain mismatch | [Getting Started](/en/guide/getting-started#minimal-dependency) |
| `pack_format ... expected ...` | version profile and pack metadata disagree | [Format mismatches](/en/guide/getting-started#treat-format-mismatches-as-warnings-first) |
| Command has no effect | unsupported behavior, low behavior level, or selector mismatch | [Command Support](/en/runtime/command-support) |
| Output assertion fails even though the command ran | matching `text` instead of `rawText`, or wrong target/channel | [Testing Patterns](/en/guide/testing-patterns#pattern-2-output-commands) |
| score/storage value is wrong | setup mixed with behavior, or the function was not called | trace + snapshot diff |
| player event does not trigger | incomplete player fixture, context mismatch, or advancement conditions not met | [Player Events](/en/runtime/player-events) |
| manifest resource loading fails | invalid path, namespace, JSON/SNBT, or schema | [Resource Formats](/en/resources/resource-formats) |

## Keep the Report First

When debugging, do not call `requirePassed()` immediately:

```kotlin
val report = SandboxQuickTest.singleFunctionText(source, version = "26.2")
    .function()
    .assertScore("#unit", "runs", 1)
    .report()

report.failures.forEach(::println)
report.outputs.forEach { println("${it.channel}: ${it.rawText}") }
report.traces.forEach { println("${it.root}: ${it.success}") }
report.snapshotDiffs.forEach { println(it.render()) }
```

This lets you inspect failed assertions, output events, traces, and snapshot diffs. Switch back to `requirePassed()` once the test is stable.

## pack_format and Version Profiles

Typical message:

```text
Datapack format pack_format 100 or range 100..100 is not compatible with version 26.2; expected 107.1
```

Handle it in this order:

1. Confirm that `version = "26.2"` is the Minecraft version you intended to model.
2. Check `pack.mcmeta` for `pack_format` or `supported_formats`.
3. If this is temporary compatibility testing, keep it as a warning and continue validating command/resource behavior.
4. If the pack targets that version for release, update pack metadata.

`min_format` and `max_format` may be arrays:

```json
{
  "supported_formats": {
    "min_inclusive": [104, 1],
    "max_inclusive": [107, 1]
  }
}
```

The array `[104, 1]` means `104.1`, and `[94]` means `94`.

## Command Support Problems

Separate three cases:

| Case | How to debug |
| --- | --- |
| parse failure | check command syntax and version profile |
| parse succeeds but behavior is no-op | check the command matrix behavior level |
| behavior runs but result is wrong | add `assertTrace` and snapshot diff |

Temporary trace assertion:

```kotlin
.assertTrace(
    root = CommandRoot.EXECUTE,
    success = true,
)
```

If trace records execution but the snapshot does not change, the command path was reached but that side effect may not be modeled by the runtime yet.

## Output Assertion Failures

Common mistake with chat commands:

```kotlin
// Fragile: rendered chat line
.assertOutput(text = "Hello Minecraft world !")

// Usually better: message passed to say
.assertOutput(rawText = "Hello Minecraft world !")
```

Also check:

- `channel`, such as `OutputChannel.CHAT`, `TITLE`, or `WARNING`;
- `target` or `targets` after selector resolution;
- whether you need `contains`, `rawContains`, or a regex instead of exact equality.

## Selector Did Not Match

When `execute as @a` or `tell @p` has no effect, first confirm that the fixture has a player:

```kotlin
.world {
    player(
        name = "Steve",
        x = 0.0,
        y = 64.0,
        z = 0.0,
    )
}
.assertPlayer("Steve", exists = true)
```

If the selector depends on dimension, tag, distance, predicate, or gamemode, add those fields to the fixture. Do not assume default sandbox player state is identical to a real server.

## Resource Path Errors

Datapack resource path errors usually happen before command behavior. Check in this order:

1. `pack.mcmeta` exists;
2. namespace is under `data/<namespace>/...`;
3. functions use `.mcfunction`;
4. JSON matches the resource type;
5. SNBT paths stay within the supported subset;
6. manifest references use namespaced IDs.

If you test generated output, keep the generated directory when the test fails. The assertion error alone may not be enough.

## When to Use Snapshot Diff

Use snapshot diff when you do not know what state changed:

```kotlin
.assertSnapshotDiff(
    path = "/scoreboard/scores/#unit/runs",
    kind = SnapshotDiffKind.CHANGED,
)
```

Diffs are useful when:

- a command ran but wrote to a different key;
- initial state differs from your assumption;
- multiple functions modify the same storage;
- version profiles change loaded resources.

## Tighten Assertions Last

During debugging, use contains, range, and existence assertions. Once stable, switch to exact values.

| Debugging | Stable test |
| --- | --- |
| `assertOutput(rawContains = "...")` | `assertOutput(rawText = "...")` |
| `assertScoreAtLeast(...)` | `assertScore(...)` |
| `assertEntityCountAtLeast(...)` | `assertEntityCount(...)` |
| `report()` | `requirePassed()` |

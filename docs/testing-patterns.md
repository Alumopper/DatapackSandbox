# Testing Patterns

This page organizes testing techniques by developer task. Use it as a cookbook: find the behavior you want to test, copy the matching shape, then replace function names, players, scoreboards, or storage IDs with your project values.

## Pick a Pattern

| Goal | Recommended pattern | Avoid |
| --- | --- | --- |
| A function writes the right score or storage value | Single-function QuickTest | Starting a full manifest for one local behavior |
| A full pack loads and runs a smoke function | Manifest smoke test | Cramming all resources into one string |
| A command is supported or output is stable | command/output assertions | Only checking that no exception was thrown |
| Advancement, predicate, or loot depends on player context | fixture + player event | Hand-writing incomplete player NBT |
| Generated datapack output should behave correctly | generated directory + manifest/check | Comparing raw strings only |
| Version differences should be explicit | matrix test | Testing only the latest profile |

## Pattern 1: Single-Function Behavior

Use this for local command behavior. It gives the shortest failure path.

```kotlin
SandboxQuickTest.singleFunctionText(
    """
    scoreboard objectives add state dummy
    scoreboard players set #gate state 1
    """.trimIndent(),
    version = "26.2",
)
    .function()
    .assertScore("#gate", "state", 1)
    .assertTrace(root = CommandRoot.SCOREBOARD, success = true)
    .requirePassed()
```

Checklist:

- Prefer enum overloads for fixed option sets, such as `CommandRoot.SCOREBOARD`.
- Assert both final state and trace when you need to distinguish "command did not run" from "state is wrong".
- Keep each test focused on one behavior.

## Pattern 2: Output Commands

Chat-like commands expose two text layers:

| Field | Use it for |
| --- | --- |
| `text` | Rendered chat line, such as `<Server> Hello` |
| `rawText` | Raw message passed to the command, such as `Hello` |

```kotlin
SandboxQuickTest.singleFunctionText(
    """say Hello Minecraft world !""",
    version = "26.2",
)
    .function()
    .assertOutput(
        channel = OutputChannel.CHAT,
        rawText = "Hello Minecraft world !",
    )
    .requirePassed()
```

Most tests should prefer `rawText`. Match `text` only when you intentionally care about chat decoration, target rendering, or formatting.

## Pattern 3: Predefined World State

When a test depends on players, blocks, entities, time, weather, or gamerules, model them as fixtures instead of writing a long setup function.

```kotlin
SandboxQuickTest.singleFunctionText(
    """
    scoreboard objectives add used dummy
    execute as @a run scoreboard players add @s used 1
    """.trimIndent(),
    version = "26.2",
)
    .world {
        player(
            name = "Steve",
            x = 0.0,
            y = 64.0,
            z = 0.0,
            gameMode = SandboxGameMode.SURVIVAL.id,
        )
    }
    .function()
    .assertScore("Steve", "used", 1)
    .assertPlayer("Steve", gameMode = SandboxGameMode.SURVIVAL)
    .requirePassed()
```

This separates "environment setup" from "behavior under test", making failures easier to diagnose.

## Pattern 4: Player Events

Use player events for advancement triggers, predicates, item use, block interaction, and input behavior.

```kotlin
SandboxQuickTest.create(listOf(datapackPath), version = "26.2")
    .player("Alex")
    .event("Alex", PlayerEventType.ITEM_USED.id, "minecraft:carrot_on_a_stick")
    .assertPlayerEventTrace(
        player = "Alex",
        type = PlayerEventType.ITEM_USED,
        success = true,
    )
    .requirePassed()
```

Assert all three layers when possible:

- the event trace dispatched successfully;
- important context fields matched;
- the final state changed, such as score, storage, or advancement completion.

## Pattern 5: Multi-Version Matrix

Use a matrix when a datapack supports multiple Minecraft versions or pack formats.

```kotlin
SandboxQuickTest.matrix(
    mapOf(
        "25.4" to listOf(Path.of("packs/demo-25_4")),
        "26.2" to listOf(Path.of("packs/demo-26_2")),
    ),
)
    .load()
    .function("demo:smoke")
    .assertTrace(root = CommandRoot.FUNCTION, success = true)
    .requirePassed()
```

Matrix tests are good at surfacing:

- `pack_format` warnings for a version profile;
- command support changes;
- resource format or vanilla-data differences;
- fixtures that accidentally assume one version.

## Pattern 6: Generated Datapack Regression

If your project generates `.mcfunction`, loot tables, or tags, do not only compare generated text. Prefer this workflow:

1. Run the generator and write the pack to `build/generated-datapacks/<case>`.
2. Load that directory with a manifest or QuickTest.
3. Assert observable behavior.
4. Keep the generated directory and sandbox report as CI artifacts when the test fails.

This catches syntax errors, resource path mistakes, version-format issues, and runtime behavior regressions.

## Assertion Combinations

| Behavior | Recommended assertions |
| --- | --- |
| scoreboard write | `assertScore` + `assertTrace` |
| storage write | `assertStorageEquals` + `assertSnapshotDiff` |
| chat output | `assertOutput(rawText = ...)` + `OutputChannel.CHAT` |
| advancement | `assertAdvancementDone` + `assertPlayerEventTrace` |
| loot | `assertLoot(seed = ...)` + item count |
| entity operation | `assertEntity` + `assertEntityCountRange` |
| block operation | `assertBlock` + snapshot diff |

## Naming Tests

Name tests as behavior statements rather than API calls:

```kotlin
fun generatedRewardFunctionAddsOnePoint()
fun usingKeyItemUnlocksDoorAdvancement()
fun unsupportedCommandReportsWarningButDoesNotAbort()
```

When a report fails, the test name should map directly to a user scenario.

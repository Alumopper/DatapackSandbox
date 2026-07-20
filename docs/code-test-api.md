# Code Test API

In addition to the CLI and `.dps.json` manifests, Kotlin/Java projects can call
the `:testkit` quick-test API directly. This is useful for local unit tests, plugin
tests, and build-tool smoke tests.

## Artifact and Requirements

The fluent testing API is the `testkit` artifact:

```text
moe.afox.dpsandbox:testkit:1.0.1
```

The `cli` module and `datapack-sandbox-cli.jar` are for command-line use. JVM
projects should depend on `testkit` instead; it brings in the lower-level `core` runtime transitively.

The published artifacts are built with the project's Java 25 toolchain, so
consumers need Java 25 or newer for compilation and test execution. Runtime
dependencies are resolved transitively, but external builds should include
Maven Central and Mojang's library repository because the core runtime depends
on Gson, Brigadier, and LZ4.

## Gradle Dependency

For a released artifact:

```kotlin
repositories {
    maven("https://nexus.mcfpp.top/repository/maven-releases/")
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

dependencies {
    testImplementation("moe.afox.dpsandbox:testkit:1.0.1")
}
```

Use `implementation(...)` instead of `testImplementation(...)` when embedding
the sandbox into a tool, plugin, or service instead of only using it from tests.
For snapshot builds, use the same coordinates with a `-SNAPSHOT` version and
the `https://nexus.mcfpp.top/repository/maven-snapshots/` repository.

Inside the same multi-project build:

```kotlin
dependencies {
    testImplementation(project(":testkit"))
}
```

## Maven Dependency

```xml
<repositories>
  <repository>
    <id>dpsandbox-releases</id>
    <url>https://nexus.mcfpp.top/repository/maven-releases/</url>
  </repository>
  <repository>
    <id>mojang-libraries</id>
    <url>https://libraries.minecraft.net</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>moe.afox.dpsandbox</groupId>
    <artifactId>testkit</artifactId>
    <version>1.0.1</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

## API Entry Points

Most tests should use `SandboxQuickTest`, which provides fluent setup, execution,
assertions, and a self-contained `SandboxQuickTestReport`. Java callers can use
`DatapackSandboxTestApi`, a static facade over the same quick-test API.

Use the lower-level factories only when you need direct runtime control:

| Entry point | Use when |
|---|---|
| `SandboxQuickTest.create(...)` | Testing one or more real datapack directories or zip files. |
| `SandboxQuickTest.singleFunction(...)` | Testing one generated `.mcfunction` file. |
| `SandboxQuickTest.singleFunctionText(...)` | Testing generated command text without writing a file. |
| `SandboxQuickTest.functions(...)` | Testing several synthetic functions, optionally with datapack dependencies. |
| `SandboxQuickTest.matrix(...)` | Running the same scenario across multiple Minecraft version profiles. |
| `DatapackSandboxTestApi` | Calling the quick-test API from Java with static methods. |
| `createSandbox(...)` | Direct access to `DatapackSandbox` for custom runners. |
| `createFunctionSandbox(...)` | Direct low-level runtime backed by synthetic function sources. |

## Kotlin Example

```kotlin
import moe.afox.dpsandbox.core.SandboxQuickTest
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import java.nio.file.Path

class MyDatapackTest {
    @Test
    fun counterWorks() {
        SandboxQuickTest.create(
            packs = listOf(Path.of("packs/counter")),
            unsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        )
            .load()
            .ticks(20)
            .function("demo:main")
            .assertScore("#clock", "ticks", 25)
            .requirePassed()
    }
}
```

`requirePassed()` throws `SandboxQuickTestAssertionError` when assertions fail.
The error contains all failures, a minimal snapshot diff from the scenario's
initial state, and a short trace summary. Output assertion failures include
candidate output events with command/channel/targets/text summaries; trace
assertion failures include candidate command traces with root/command/status
summaries. The full report still exposes the final snapshot for custom
test-framework rendering.

Reports returned by `report()` and `requirePassed()` also include structured
command traces and player event traces. Command traces record the command text,
root command, success flag, executed command count, output count, source
file/line, function stack, executor, and position. Player event traces record
the dispatched event context, item/entity/block/recipe/dimension/damage/input
metadata, advancement criteria matched by that event, and failed advancement
criteria with readable reasons.
This is useful for debugging generated commands, function call chains, and
event-driven datapacks:

```kotlin
val report = SandboxQuickTest.singleFunctionText(
    functionText = "say traced",
    version = "26.2",
)
    .function()
    .requirePassed()

println(report.traces.single().command)
```

The same matcher is available as `assertTrace(...)` for fluent assertions and
`matchingTraces(...)` for inspection without registering a failure. Use
`assertPlayerEventTrace(...)` / `matchingPlayerEventTraces(...)`,
`playerEventTraces()`, or `report.playerEventTraces` to inspect injected player
events and advancement criteria matched by those events.

When you need to compare two states, use `assertSnapshotDiff(...)` in fluent
tests or `snapshotDiffs()`/`report.snapshotDiffs` for inspection. Lower-level
code can still call `SnapshotDiff.diff(before, after)` for stable JSON Pointer
paths or `SnapshotDiff.render(...)` for readable failure logs.

`unsupportedFeatureMode` can be `WARN` (default), `IGNORE`, or `ERROR`. Use
`ERROR` when you want unsupported vanilla commands to fail the test immediately.

## Version and Pack Metadata

`SandboxQuickTest.create(...)` defaults to the latest built-in profile
(`26.2` in this build). Pass `version = "..."` explicitly when your datapack is
for another Minecraft version; the API does not infer the runtime profile from
`pack.mcmeta`.

`pack.mcmeta` format mismatches are load warnings, not exceptions. They are
available both as `sandbox.datapack.warnings` on the low-level API and as
`warning` output events in quick-test reports:

```kotlin
val report = SandboxQuickTest.create(
    packs = listOf(Path.of("packs/demo")),
    version = "26.2",
)
    .load()
    .report()

val formatWarnings = report.outputs.filter {
    it.channel == "warning" && it.command == "datapack load"
}
```

Modern pack format values can be written as integer tuples. The loader treats
`[107, 1]` as `107.1` and `[94]` as `94`, so this metadata is valid:

```json
{
  "pack": {
    "min_format": [94],
    "max_format": [107, 1],
    "description": "Example 26.2 datapack"
  }
}
```

Low-level sandbox factories also accept `SandboxLimits` to stop runaway tests
deterministically. The limits currently cover total command lines executed by a
sandbox instance, nested function call depth, the maximum tick count allowed in
one `runTicks` call, retained output events, and rendered snapshot size:

```kotlin
val sandbox = createFunctionSandbox(
    version = "26.2",
    functionFile = Path.of("scratch/generated.mcfunction"),
    limits = SandboxLimits(
        maxCommands = 10_000,
        maxFunctionDepth = 32,
        maxTicksPerRun = 5_000,
        maxOutputEvents = 2_000,
        maxSnapshotBytes = 1_000_000,
    ),
)
```

## Single Mcfunction Tests

You can test one `.mcfunction` file without creating a datapack directory. The
Minecraft version is explicit in the API call, and the temporary function id
defaults to `sandbox:main`.

```kotlin
SandboxQuickTest.singleFunction(
    functionFile = Path.of("scratch/test.mcfunction"),
    version = "26.2",
)
    .function()
    .assertScore("#single", "runs", 1)
    .requirePassed()
```

For lower-level access:

```kotlin
val sandbox = createFunctionSandbox(
    version = "26.2",
    functionFile = Path.of("scratch/test.mcfunction"),
)
sandbox.runFunction("sandbox:main")
```

You can also pass function text directly without creating a file:

```kotlin
SandboxQuickTest.singleFunctionText(
    functionText = """
        scoreboard objectives add runs dummy
        scoreboard players set #inline runs 1
    """.trimIndent(),
    version = "26.2",
)
    .function()
    .assertScore("#inline", "runs", 1)
    .requirePassed()
```

For multiple lightweight functions, mix file-backed and in-memory sources:

```kotlin
SandboxQuickTest.functions(
    functionSources = listOf(
        FunctionSource.text("demo:main", "function demo:helper"),
        FunctionSource.file("demo:helper", Path.of("scratch/helper.mcfunction")),
        FunctionSource.text("demo:inline", "say generated in memory"),
    ),
    version = "26.2",
    defaultFunctionId = "demo:main",
)
    .function()
    .requirePassed()
```

Folder or zip datapacks can be loaded as dependencies for those lightweight
functions:

```kotlin
SandboxQuickTest.functions(
    functionSources = listOf(
        FunctionSource.text("demo:main", "function library:setup"),
    ),
    version = "26.2",
    defaultFunctionId = "demo:main",
    dependencyPacks = listOf(
        Path.of("deps/library_pack"),
        Path.of("deps/items.zip"),
    ),
)
    .function()
    .requirePassed()
```

Scheduled functions can be asserted directly without walking snapshot JSON:

```kotlin
SandboxQuickTest.functions(
    functionSources = listOf(
        FunctionSource.text("demo:main", "schedule function demo:later 5t append"),
        FunctionSource.text("demo:later", "say later"),
    ),
    version = "26.2",
    defaultFunctionId = "demo:main",
)
    .function()
    .assertScheduledFunction("demo:later", dueTick = 5, count = 1)
    .ticks(5)
    .assertScheduledFunction("demo:later", exists = false)
    .requirePassed()
```

`dueTick` is the absolute sandbox game tick when the queued function should run.
`count` is useful for `append` mode tests where duplicate schedule entries are
expected.

Gamerule state is stored as strings and can be asserted without snapshot paths:

```kotlin
SandboxQuickTest.create(listOf(pack), version = "26.2")
    .command("gamerule doDaylightCycle false")
    .assertGamerule("doDaylightCycle", "false")
    .assertGamerule("missingRule", exists = false)
    .requirePassed()
```

Scoreboard UI state can be asserted directly from generated command output:

```kotlin
SandboxQuickTest.create(listOf(pack), version = "26.2")
    .command("scoreboard objectives add health dummy")
    .command("scoreboard objectives modify health displayname Health Points")
    .command("scoreboard objectives modify health rendertype hearts")
    .command("scoreboard objectives setdisplay sidebar.team.red health")
    .assertScoreboardObjective(
        "health",
        renderType = ScoreboardRenderType.HEARTS,
        criteria = "dummy",
        displayName = "Health Points",
    )
    .assertScoreboardDisplay(ScoreboardDisplaySlot.SIDEBAR_TEAM_RED, "health")
    .requirePassed()
```

## Predefined World State

Tests can start from an explicit world fixture without issuing setup commands.
NBT written through this API is still validated against the active version
profile, so custom top-level entity or block-entity fields are rejected in the
same way as `data modify`.

```kotlin
SandboxQuickTest.create(
    packs = listOf(Path.of("packs/demo")),
    version = "26.2",
    defaultPlayerName = null,
)
    .world {
        seed(123)
        randomSequence("demo:seq", 42)
        difficulty("hard")
        defaultGameMode("creative")
        worldSpawn(4.0, 70.0, 5.0, forced = true)
        forcedChunk(0, 0)
        biome(0, 64, 0, "minecraft:plains")
        worldBorder(centerX = 5.0, centerZ = -6.0, size = 100.0, warningDistance = 8)
        block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
        structure(10, 64, 10) {
            block(0, 0, 0, "minecraft:stone")
            entity("minecraft:pig", offsetX = 0.5, offsetY = 1.0, offsetZ = 0.5, tags = listOf("structure_fixture"))
        }
        entity(
            "minecraft:pig",
            1.0,
            64.0,
            0.0,
            tags = listOf("fixture"),
            uuid = "00000000-0000-0000-0000-000000000101",
            vehicle = "00000000-0000-0000-0000-000000000102",
            equipment = mapOf("weapon.mainhand" to item("minecraft:iron_sword")),
            effects = listOf(effect("minecraft:strength", durationTicks = 80, amplifier = 2)),
            attributes = mapOf("minecraft:max_health" to 12.0),
            dimension = "minecraft:the_nether",
            health = 8.0,
        )
        entity(
            "minecraft:cow",
            1.0,
            64.0,
            1.0,
            tags = listOf("fixture_vehicle"),
            uuid = "00000000-0000-0000-0000-000000000102",
            passengers = listOf("00000000-0000-0000-0000-000000000101"),
        )
        player(
            "Alex",
            x = 2.0,
            y = 65.0,
            z = 3.0,
            xp = 5,
            xpLevels = 4,
            inventory = listOf(item("minecraft:stick", 2)),
            enderItems = listOf(item("minecraft:ender_pearl", 4)),
        )
        playerEffect("Alex", "minecraft:speed", durationTicks = 40, amplifier = 1)
        playerRecipe("Alex", "minecraft:bread")
        playerAdvancementCriterion("Alex", "demo:use_carrot", "use_carrot")
        playerSpawn("Alex", 2.0, 66.0, 3.0, angle = 90.0)
        team("red", members = listOf("Alex"), options = mapOf("color" to "red"))
        bossbar("demo:bar", "Demo", value = 3, max = 10, players = listOf("Alex"))
        score("#fixture", "ready", 1)
        storage("demo:env", "{ready:true}")
        gamerule("doDaylightCycle", "false")
    }
    .assertWorld(
        difficulty = SandboxDifficulty.HARD,
        defaultGameMode = SandboxGameMode.CREATIVE,
        seed = 123,
        forcedChunkX = 0,
        forcedChunkZ = 0,
        biomeX = 0,
        biomeY = 64,
        biomeZ = 0,
        biome = "minecraft:plains",
        worldSpawn = Position(4.0, 70.0, 5.0),
        worldSpawnDimension = "minecraft:overworld",
        worldSpawnAngle = 90.0,
        worldSpawnForced = true,
        worldBorderCenterX = 5.0,
        worldBorderCenterZ = -6.0,
        worldBorderSize = 100.0,
        worldBorderWarningDistance = 8,
    )
    .assertBlock(0, 64, 0, "minecraft:chest", nbtPath = "Items", nbtEquals = "[]")
    .assertEntity(
        type = "minecraft:pig",
        tag = "fixture",
        dimension = "minecraft:the_nether",
        health = 8.0,
        vehicle = "00000000-0000-0000-0000-000000000102",
        nbtPath = "Health",
        nbtEquals = "8.0",
    )
    .assertEntity(
        type = "minecraft:cow",
        tag = "fixture_vehicle",
        passenger = "00000000-0000-0000-0000-000000000101",
        passengerCount = 1,
    )
    .assertEntityEquipment(EntityEquipmentSlot.MAINHAND, type = "minecraft:pig", tag = "fixture", id = "minecraft:iron_sword", dimension = "minecraft:the_nether")
    .assertEntityEffect("minecraft:strength", type = "minecraft:pig", tag = "fixture", durationTicks = 80, amplifier = 2, dimension = "minecraft:the_nether")
    .assertEntityAttribute("minecraft:max_health", type = "minecraft:pig", tag = "fixture", value = 12.0, dimension = "minecraft:the_nether")
    .assertEntityCount(expected = 1, type = "minecraft:pig", tag = "fixture", dimension = "minecraft:the_nether")
    .assertEntityCountRange(min = 1, max = 3, type = "minecraft:pig", tag = "fixture", dimension = "minecraft:the_nether")
    .assertPlayer(
        "Alex",
        gameMode = SandboxGameMode.SURVIVAL,
        xp = 5,
        xpLevels = 4,
        recipe = "minecraft:bread",
        effect = "minecraft:speed",
        spawn = Position(2.0, 66.0, 3.0),
        spawnDimension = "minecraft:overworld",
        spawnAngle = 90.0,
        spawnForced = false,
        nbtPath = "Health",
        nbtEquals = "20.0",
    )
    .assertTeam("red", TeamOption.COLOR, "red", member = "Alex", memberCount = 1)
    .assertBossbar("demo:bar", name = "Demo", value = 3, max = 10, player = "Alex")
    .assertItem("Alex", ItemContainer.INVENTORY, "minecraft:stick", 2, minCount = 1, maxCount = 3)
    .assertScore("#fixture", "ready", 1)
    .assertScoreRange("#fixture", "ready", min = 1, max = 3)
    .assertStorageExists("demo:env", "ready")
    .assertStorageMissing("demo:env", "debug.last")
    .assertRandomSequence("demo:seq", 42)
    .assertForcedChunk(0, 0)
    .assertPlayerXp("Alex", 5)
    .assertPlayerXpLevels("Alex", 4)
    .requirePassed()
```

To seed a test from an existing Java Edition save, import an explicit chunk set
or block range. The importer reads Anvil `.mca` files for block states, block
entities, and entity regions; it does not load a whole save by default.

```kotlin
SandboxQuickTest.create(listOf(Path.of("packs/demo")), version = "26.2")
    .importSave(
        path = Path.of("saves/MyWorld"),
        chunks = listOf(ChunkPos(0, 0), ChunkPos(1, 0)),
        dimension = "minecraft:overworld",
    )
    .function("demo:check_loaded_area")
    .requirePassed()
```

## Multi-Version Matrix Tests

Use `SandboxQuickTest.matrix(...)` when the same behavior should pass on
multiple Minecraft versions. Each version can point at its own datapack so the
`pack_format` and resource directory layout stay correct.

```kotlin
SandboxQuickTest.matrix(
    mapOf(
        "1.20.4" to listOf(Path.of("packs/demo-1_20_4")),
        "26.1.2" to listOf(Path.of("packs/demo-26_1_2")),
        "26.2" to listOf(Path.of("packs/demo-26_2")),
    ),
)
    .load()
    .world { player("Alex", xp = 5, xpLevels = 2) }
    .keyInput("Alex", "jump")
    .assertScore("#clock", "ticks", 0)
    .assertPlayerXp("Alex", 5)
    .assertPlayerXpLevels("Alex", 2)
    .assertPlayerLastInput("Alex", "keyboard", "jump", "press")
    .requirePassed()
```

Use `forEachScenario { ... }` to apply any current or future single-scenario
operation/assertion across the matrix without waiting for a mirrored matrix
convenience method.

## Java Example

```java
import moe.afox.dpsandbox.core.DatapackSandboxTestApi;
import java.nio.file.Path;
import java.util.List;

class MyDatapackTest {
    @org.junit.jupiter.api.Test
    void counterWorks() {
        DatapackSandboxTestApi.scenario(List.of(Path.of("packs/counter")), "26.2")
            .load()
            .ticks(20)
            .assertScore("#clock", "ticks", 20)
            .requirePassed();
    }

    @org.junit.jupiter.api.Test
    void generatedFunctionWorks() {
        DatapackSandboxTestApi.singleFunctionTextScenario(
            "scoreboard objectives add runs dummy\nscoreboard players set #java runs 1",
            "26.2"
        )
            .function()
            .assertScore("#java", "runs", 1)
            .requirePassed();
    }
}
```

## Common Methods

| Method | Purpose |
|---|---|
| `DatapackSandboxTestApi.scenario(...)` | Java-friendly static facade for `SandboxQuickTest.create(...)` |
| `DatapackSandboxTestApi.runFunctionText(...)` | Run one in-memory function from Java and return a report |
| `load()` | Run `#minecraft:load` |
| `ticks(n)` | Advance sandbox ticks |
| `function(id)` | Run a datapack function |
| `function()` | Run the default single-file function created by `singleFunction(...)` |
| `singleFunctionText(text, version)` | Create a quick-test scenario from one function string |
| `functions(sources, version)` | Create a quick-test scenario from multiple `FunctionSource` values, optionally with datapack dependencies |
| `matrix(packsByVersion)` | Create a multi-version quick-test matrix |
| `command(raw)` | Execute one command |
| `world { ... }` | Apply an in-memory world fixture before running behavior |
| `setupWorld(setup)` | Apply a reusable `SandboxWorldSetup` |
| `importSave(path, chunks, dimension)` | Import selected Java Anvil save chunks |
| `player(name)` | Create or reuse a player |
| `event(player, type, id, action)` | Inject a player event; interaction/attack `id` may be a selector or UUID targeting one real entity |
| `blockEvent(player, type, id, x, y, z)` | Inject a block player event and update the sparse-world position |
| `keyInput(player, key, action)` | Inject keyboard input |
| `mouseInput(player, button, action, x, y)` | Inject mouse input |
| `playerAdvancementCriterion(player, advancement, criterion, done)` | Predefine one player advancement criterion state |
| `playerAdvancement(player, advancement, criteria)` | Predefine multiple player advancement criterion states |
| `assertScore(target, objective, expected)` | Assert scoreboard state |
| `assertScoreAtLeast(target, objective, minimum)` | Assert a scoreboard lower bound |
| `assertScoreAtMost(target, objective, maximum)` | Assert a scoreboard upper bound |
| `assertScoreRange(target, objective, min, max)` | Assert optional scoreboard bounds |
| `assertStorageEquals(id, path, expectedJson)` | Assert a storage path |
| `assertStorageExists(id, path)` | Assert that a storage root or path exists |
| `assertStorageMissing(id, path)` | Assert that a storage root or path is absent |
| `assertWorld(...)` | Assert selected world-level state, forced chunks, biome overrides, world spawn, and world border |
| `assertRandomSequence(name, expected, exists)` | Assert deterministic random sequence state or missing state |
| `assertForcedChunk(x, z, exists)` | Assert forced chunk state by chunk coordinates |
| `assertGamerule(name, value, exists)` | Assert stored gamerule state as string values |
| `assertScheduledFunction(id, dueTick, exists, count)` | Assert queued scheduled functions by id, absolute due tick, existence, or duplicate count |
| `assertScoreboardObjective(name, exists, criteria, displayName, renderType, displayAutoUpdate)` | Assert scoreboard objective criteria and UI metadata |
| `assertScoreboardDisplay(slot, objective, exists)` | Assert scoreboard display slots such as `sidebar`, `list`, or `sidebar.team.red` |
| `assertPlayer(...)` | Assert selected player state, ender item count, spawn point details, and full-NBT path filters |
| `assertTeam(...)` | Assert selected team state, members, member count, and options |
| `assertBossbar(...)` | Assert selected bossbar state and assigned players |
| `assertPredicate(id, expected, playerName)` | Assert a loaded predicate result |
| `assertLoot(table, context, playerName, seed, count, item)` | Assert deterministic loot generation |
| `assertBlock(x, y, z, id, exists, nbtPath, nbtEquals, nbtExists)` | Assert a sparse-world block |
| `assertEntity(type, tag, uuid, position, exists, count, dimension, health, vehicle, passenger, passengerCount, nbtPath, nbtEquals, nbtExists)` | Assert matching entity existence, count, and full-NBT path filters |
| `assertEntityEquipment(slot, type, tag, uuid, position, id, count, exists, minCount, maxCount, componentsPath, componentsEquals, componentsExists, nbtPath, nbtEquals, nbtExists, dimension)` | Assert non-player entity equipment |
| `assertEntityEffect(effect, type, tag, uuid, position, exists, durationTicks, amplifier, hideParticles, dimension)` | Assert a non-player entity active effect |
| `assertEntityAttribute(attribute, type, tag, uuid, position, exists, value, min, max, dimension)` | Assert a non-player entity attribute |
| `assertEntityCount(expected, type, tag, dimension)` | Assert matching entity count |
| `assertEntityCountAtLeast(minimum, type, tag, dimension)` | Assert a matching entity count lower bound |
| `assertEntityCountAtMost(maximum, type, tag, dimension)` | Assert a matching entity count upper bound |
| `assertEntityCountRange(min, max, type, tag, dimension)` | Assert optional matching entity count bounds |
| `assertItem(player, id, count, slot, exists, minCount, maxCount, componentsPath, componentsEquals, componentsExists, nbtPath, nbtEquals, nbtExists, container)` | Assert a matching player inventory or enderItems item |
| `assertPlayerXp(player, expected)` | Assert player XP points |
| `assertPlayerXpLevels(player, expected)` | Assert player XP levels |
| `assertPlayerLastInput(player, device, code, action)` | Assert the latest player input |
| `assertAdvancementDone(player, id, expected)` | Assert advancement completion |
| `assertOutputContains(text)` | Assert output event text |
| `assertOutput(...)` | Assert command/channel/target/rendered text/rawText/regex/normalized text/payload path/segment/count/order for output events |
| `assertTrace(...)` | Assert command/root/source/success/output count/output text/output target/diff path/diff kind/count for trace events |
| `assertPlayerEventTrace(...)` | Assert player event trace player/type/success/context/target UUID/interaction response/block position/input metadata/advancement/failed advancement/count |
| `assertSnapshotDiff(...)` | Assert before/after snapshot path/kind/rendered text/count; failures list actual diff candidates |
| `outputs()` | Return recorded output events |
| `traces()` | Return recorded structured command trace events |
| `playerEventTraces()` | Return recorded player event trace records |
| `snapshotDiffs()` | Return stable JSON Pointer diffs from initial to current state |
| `matchingTraces(...)` | Return trace events matching a structured expectation |
| `matchingPlayerEventTraces(...)` | Return player event trace records matching a structured expectation |
| `matchingOutputs(...)` | Return output events matching a structured expectation |
| `report()` | Return `SandboxQuickTestReport` without throwing |
| `requirePassed()` | Return report or throw an assertion error |

`assertBlock` and `assertItem` path checks use the same `JsonPaths` syntax as
manifest assertions. `nbtEquals` and `componentsEquals` accept JSON/SNBT-lite
text.

## Assertion Semantics

All fluent `assert...` methods record a failure on the scenario or matrix and
return the same object for chaining. Call `report()` to inspect failures without
throwing, or `requirePassed()` to throw `SandboxQuickTestAssertionError` when
any recorded assertion failed.

Most optional assertion parameters are filters: `null` means "do not check this
field". For event-list assertions such as `assertOutput(...)`,
`assertTrace(...)`, and `assertPlayerEventTrace(...)`, omitting `count` requires
at least one match; setting `count` requires exactly that many matches. Output
`order` is one-based and checks the global output event position.

String overloads remain available for custom ids, future vanilla values, and
advanced tests. Fixed-choice parameters also have enum overloads so Kotlin and
Java callers get autocomplete:

| Enum | Used by |
| --- | --- |
| `OutputChannel` | `assertOutput(...)`, `matchingOutputs(...)` channel filters such as `CHAT`, `TITLE`, `WORLDGEN`, `WARNING` |
| `CommandRoot` | `assertTrace(...)`, `matchingTraces(...)` root filters such as `SAY`, `SCOREBOARD`, `FUNCTION`, `EXECUTE` |
| `SandboxWeather`, `SandboxDifficulty`, `SandboxGameMode` | `assertWorld(...)` and `assertPlayer(...)` weather, difficulty, default game mode, and player game mode |
| `PlayerInputDevice`, `PlayerInputAction`, `PlayerEventType` | `keyInput(...)`, `mouseInput(...)`, `assertPlayerLastInput(...)`, `assertPlayerEventTrace(...)`, `matchingPlayerEventTraces(...)` |
| `ScoreboardRenderType`, `ScoreboardDisplaySlot` | `assertScoreboardObjective(...)` render type and `assertScoreboardDisplay(...)` slot |
| `EntityEquipmentSlot`, `ItemContainer`, `LootContextId` | `assertEntityEquipment(...)`, `assertItem(...)`, `assertLoot(...)` |
| `BossbarColor`, `BossbarStyle`, `TeamOption` | `assertBossbar(...)` and `assertTeam(...)` fixed options |

### Assertion Reference

| Assertion | Main checks |
| --- | --- |
| `assertScore`, `assertScoreAtLeast`, `assertScoreAtMost`, `assertScoreRange` | Scoreboard value equality and optional numeric bounds. |
| `assertStorageEquals`, `assertStorageExists`, `assertStorageMissing` | Storage root/path existence or exact JSON/SNBT-lite value. |
| `assertWorld` | `gameTime`, `dayTime`, `weather`, `difficulty`, `defaultGameMode`, `seed`, forced chunks, biome overrides, world spawn, and world border fields. |
| `assertRandomSequence`, `assertForcedChunk`, `assertGamerule`, `assertScheduledFunction` | World runtime bookkeeping that otherwise requires reading snapshot JSON. |
| `assertScoreboardObjective`, `assertScoreboardDisplay` | Objective criteria, display name, render type, display auto-update, and display slot binding. |
| `assertPlayer`, `assertPlayerXp`, `assertPlayerXpLevels`, `assertPlayerLastInput` | Player existence, position, dimension, game mode, XP, health, food, inventory/ender counts, recipe/effect/stat presence, spawn point, NBT path, and last input. |
| `assertTeam`, `assertBossbar` | Team existence, display name, members, options, bossbar value/max/color/style/visibility/players. |
| `assertPredicate`, `assertLoot`, `assertAdvancementDone` | Predicate result, deterministic loot table output with context/player/seed, and advancement completion. |
| `assertBlock` | Sparse-world block id, existence, and block NBT path. |
| `assertEntity`, `assertEntityCount*` | Entity existence/count by type, tag, UUID, position, dimension, health, vehicle/passenger, passenger count, and NBT path. |
| `assertEntityEquipment`, `assertEntityEffect`, `assertEntityAttribute` | Entity equipment item filters, active effect fields, and attribute values/ranges. |
| `assertItem` | Player inventory or `enderItems` item id/count/slot/min/max plus component and NBT paths. |
| `assertOutputContains`, `assertOutput` | Output event command, channel, target(s), rendered `text`, command-visible `rawText`, regex/normalized matching, payload paths, text segments, count, and order. |
| `assertTrace` | Command trace command/root/source file/function, success flag, output count/text/target, and snapshot diff path/kind/rendered text. |
| `assertPlayerEventTrace` | Player event dispatch player/type/success, advancement matches/failures, item/entity/target/interaction response/block/recipe/dimension/damage metadata, and input device/code/action. |
| `assertSnapshotDiff` | Initial-to-current snapshot diff path, kind, rendered text, and count. |

For `say`, `me`, `msg`/`tell`/`w`, and `teammsg`/`tm`, `OutputEvent.text`
contains the rendered chat line, for example `<Server> hello`. `rawText`
contains only the message supplied to the command, for example `hello`. Use
`rawText`, `rawContains`, or `normalizedRawText` when you want to assert the
developer-facing command output content instead of the decorated chat line.

## Output Tests

```kotlin
val report = SandboxQuickTest.singleFunction(Path.of("scratch/output.mcfunction"), "26.2")
    .function()
    .assertOutput(
        channel = OutputChannel.CHAT,
        command = "say",
        target = "Steve",
        text = "<Server> hello",
        rawText = "hello",
    )
    .assertOutput(command = "tellraw", normalizedText = "generated output")
    .assertOutput(
        OutputExpectation(
            command = "tellraw",
            text = "gold",
            segment = OutputSegmentExpectation(text = "gold", color = "yellow"),
            count = 1,
        ),
    )
    .requirePassed()

val tellrawEvents = report.outputs.filter { it.command == "tellraw" }
```

Structured command payloads can be matched by path:

```kotlin
SandboxQuickTest.singleFunctionText("tick query", "26.2")
    .function()
    .assertOutput(
        OutputExpectation(
            command = "tick query",
            payloadPath = "rate",
            payloadEquals = JsonPrimitive(20.0),
        ),
    )
    .requirePassed()

SandboxQuickTest.singleFunctionText("place structure demo:ruin 1 64 2", "26.2")
    .function()
    .assertOutput(
        command = "place structure",
        channel = "worldgen",
        payloadPath = "placed",
        payloadEquals = JsonPrimitive(false),
    )
    .requirePassed()
```

When a payload assertion fails, the failure message includes the candidate
output's actual payload value at that path.

Trace assertions can also match command side effects:

```kotlin
SandboxQuickTest.singleFunctionText("scoreboard players set #gen runs 1", "26.2")
    .function()
    .assertTrace(
        command = "scoreboard players set #gen runs 1",
        outputs = 0,
        hasDiff = true,
        diffPath = "/scores/runs/#gen",
        diffKind = SnapshotDiffKind.ADDED,
    )
    .requirePassed()
```

## Keyboard/Mouse Input Tests

```kotlin
SandboxQuickTest.create(listOf(Path.of("packs/demo")))
    .keyInput("Steve", "key.jump", PlayerInputAction.PRESS)
    .mouseInput("Steve", "left", PlayerInputAction.CLICK, 12.0, 8.0)
    .assertPlayerLastInput("Steve", PlayerInputDevice.MOUSE, "left", PlayerInputAction.CLICK)
    .requirePassed()
```

Input events are recorded on the player as `lastInput` and `inputEvents`; inspect
them through snapshots, `inspect player`, or `SandboxQuickTestReport.snapshot`.

## Lower-Level API

For full control, use the runtime directly:

```kotlin
val sandbox = createSandbox(
    version = "26.2",
    packs = listOf(Path.of("packs/demo")),
    unsupportedFeatureMode = UnsupportedFeatureMode.ERROR,
)
sandbox.runLoad()
sandbox.executeCommand("scoreboard objectives list")
sandbox.handlePlayerEvent(PlayerEvents.keyInput("Steve", "key.jump"))
```

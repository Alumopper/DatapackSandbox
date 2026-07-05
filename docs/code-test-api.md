# Code Test API

In addition to the CLI and `.dps.json` manifests, Kotlin/Java projects can call
the `:core` quick-test API directly. This is useful for local unit tests, plugin
tests, and build-tool smoke tests.

## Gradle Dependency

Inside the same multi-project build:

```kotlin
dependencies {
    testImplementation(project(":core"))
}
```

If the library is published later, use normal Maven coordinates instead:

```kotlin
dependencies {
    testImplementation("moe.afox.dpsandbox:core:<version>")
}
```

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
The error contains all failures and the final snapshot.

Reports returned by `report()` and `requirePassed()` also include structured
command traces. Each trace records the command text, root command, success
flag, executed command count, output count, source file/line, function stack,
executor, and position. This is useful for debugging generated commands or
function call chains:

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
`matchingTraces(...)` for inspection without registering a failure.

When you need to compare two states, use `SnapshotDiff.diff(before, after)` for
stable JSON Pointer paths or `SnapshotDiff.render(...)` for readable failure
logs.

`unsupportedFeatureMode` can be `WARN` (default), `IGNORE`, or `ERROR`. Use
`ERROR` when you want unsupported vanilla commands to fail the test immediately.

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
        difficulty("hard")
        defaultGameMode("creative")
        worldSpawn(4.0, 70.0, 5.0)
        forcedChunk(0, 0)
        biome(0, 64, 0, "minecraft:plains")
        block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
        entity("minecraft:pig", 1.0, 64.0, 0.0, tags = listOf("fixture"))
        player("Alex", x = 2.0, y = 65.0, z = 3.0, xp = 5, inventory = listOf(item("minecraft:stick", 2)))
        playerEffect("Alex", "minecraft:speed", durationTicks = 40, amplifier = 1)
        playerRecipe("Alex", "minecraft:bread")
        playerSpawn("Alex", 2.0, 66.0, 3.0)
        team("red", members = listOf("Alex"), options = mapOf("color" to "red"))
        bossbar("demo:bar", "Demo", value = 3, max = 10, players = listOf("Alex"))
        score("#fixture", "ready", 1)
        storage("demo:env", "{ready:true}")
        gamerule("doDaylightCycle", "false")
    }
    .assertWorld(difficulty = "hard", defaultGameMode = "creative", seed = 123)
    .assertBlock(0, 64, 0, "minecraft:chest")
    .assertEntity(type = "minecraft:pig", tag = "fixture")
    .assertEntityCount(expected = 1, type = "minecraft:pig", tag = "fixture")
    .assertPlayer("Alex", xp = 5, recipe = "minecraft:bread", effect = "minecraft:speed")
    .assertItem("Alex", "minecraft:stick", 2)
    .assertScore("#fixture", "ready", 1)
    .assertPlayerXp("Alex", 5)
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
    .assertScore("#clock", "ticks", 0)
    .requirePassed()
```

## Java Example

```java
import moe.afox.dpsandbox.core.SandboxQuickTest;
import java.nio.file.Path;
import java.util.List;

class MyDatapackTest {
    @org.junit.jupiter.api.Test
    void counterWorks() {
        SandboxQuickTest.create(List.of(Path.of("packs/counter")))
            .load()
            .ticks(20)
            .assertScore("#clock", "ticks", 20)
            .requirePassed();
    }
}
```

## Common Methods

| Method | Purpose |
|---|---|
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
| `event(player, type, id, action)` | Inject a player event |
| `keyInput(player, key, action)` | Inject keyboard input |
| `mouseInput(player, button, action, x, y)` | Inject mouse input |
| `assertScore(target, objective, expected)` | Assert scoreboard state |
| `assertStorageEquals(id, path, expectedJson)` | Assert a storage path |
| `assertWorld(...)` | Assert selected world-level state |
| `assertPlayer(...)` | Assert selected player state |
| `assertBlock(x, y, z, id, exists)` | Assert a sparse-world block |
| `assertEntity(type, tag, uuid, position, exists, count)` | Assert matching entity existence or count |
| `assertEntityCount(expected, type, tag)` | Assert matching entity count |
| `assertItem(player, id, count, slot, exists)` | Assert a matching player inventory item |
| `assertPlayerXp(player, expected)` | Assert player XP |
| `assertPlayerLastInput(player, device, code, action)` | Assert the latest player input |
| `assertAdvancementDone(player, id, expected)` | Assert advancement completion |
| `assertOutputContains(text)` | Assert output event text |
| `assertOutput(...)` | Assert command/channel/target/text/count for output events |
| `assertTrace(...)` | Assert command/root/source/success/count for trace events |
| `outputs()` | Return recorded output events |
| `traces()` | Return recorded structured command trace events |
| `matchingTraces(...)` | Return trace events matching a structured expectation |
| `matchingOutputs(...)` | Return output events matching a structured expectation |
| `report()` | Return `SandboxQuickTestReport` without throwing |
| `requirePassed()` | Return report or throw an assertion error |

## Output Tests

```kotlin
val report = SandboxQuickTest.singleFunction(Path.of("scratch/output.mcfunction"), "26.2")
    .function()
    .assertOutput(command = "say", channel = "chat", target = "Steve", contains = "hello")
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

## Keyboard/Mouse Input Tests

```kotlin
SandboxQuickTest.create(listOf(Path.of("packs/demo")))
    .keyInput("Steve", "key.jump")
    .mouseInput("Steve", "left", "click", 12.0, 8.0)
    .assertPlayerLastInput("Steve", "mouse", "left", "click")
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

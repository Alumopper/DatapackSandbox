# 代码测试 API

除了 CLI 和 `.dps.json` 清单，Kotlin/Java 项目也可以直接调用 `:core` 的 quick-test API。适用场景包括本地单元测试、插件测试、构建脚本冒烟测试，以及只想验证一个 `.mcfunction` 文件的轻量用法。

## Gradle 依赖

在同一个 multi-project build 中：

```kotlin
dependencies {
    testImplementation(project(":core"))
}
```

如果后续发布到 Maven，可改成常规坐标：

```kotlin
dependencies {
    testImplementation("moe.afox.dpsandbox:core:<version>")
}
```

## Kotlin 示例

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

`requirePassed()` 在断言失败时抛出 `SandboxQuickTestAssertionError`。异常中包含所有失败项、从场景初始状态到当前状态的最小 snapshot diff，以及简短 trace 摘要。output 断言失败时还会列出候选输出事件的 command/channel/targets/text 摘要；trace 断言失败时会列出候选命令 trace 的 root/command/status 摘要。完整报告仍会暴露最终 snapshot，方便测试框架自定义渲染。

`report()` 和 `requirePassed()` 返回的报告还包含结构化命令 trace 和玩家事件 trace。命令 trace 记录命令文本、命令根、是否成功、执行的命令数、产生的输出数、来源文件/行号、函数调用栈、执行者和位置。玩家事件 trace 记录事件上下文以及本次事件匹配到的 advancement criteria。需要调试生成器输出、函数调用链或事件驱动数据包时，可以直接读取：

```kotlin
val report = SandboxQuickTest.singleFunctionText(
    functionText = "say traced",
    version = "26.2",
)
    .function()
    .requirePassed()

println(report.traces.single().command)
```

同一套 matcher 也可以通过 `assertTrace(...)` 做 fluent 断言，或通过 `matchingTraces(...)` 只检查匹配事件而不登记失败。玩家事件可通过 `assertPlayerEventTrace(...)`、`matchingPlayerEventTraces(...)`、`playerEventTraces()` 或 `report.playerEventTraces` 检查，包括事件输入上下文、item/entity/block/recipe/dimension/damage/input 元数据、事件匹配到的 advancement criteria，以及未匹配 advancement criterion 的可读失败原因。

需要比较两个状态时，fluent 测试可直接用 `assertSnapshotDiff(...)`，检查时可读取 `snapshotDiffs()` 或 `report.snapshotDiffs`。更底层的代码仍可用 `SnapshotDiff.diff(before, after)` 获得稳定 JSON Pointer 路径差异，或用 `SnapshotDiff.render(...)` 输出适合测试失败日志的文本。

`unsupportedFeatureMode` 可选值：

- `WARN`：默认行为，未支持命令记录 warning 并继续。
- `IGNORE`：静默跳过未支持命令。
- `ERROR`：严格模式，遇到未支持命令立即失败。

底层 sandbox factory 还可以传入 `SandboxLimits`，用于在单元测试或 CI 中确定性阻止
runaway 执行。目前限制覆盖一个 sandbox 实例累计执行的命令行数、嵌套 function 调用深度，
单次 `runTicks` 允许推进的最大 tick 数、保留的输出事件数，以及渲染后的 snapshot 大小：

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

## 单文件 `.mcfunction` 测试

可以不创建完整数据包目录，直接测试一个 `.mcfunction` 文件。API 中必须显式指定 Minecraft 版本；临时函数 id 默认为 `sandbox:main`。

```kotlin
SandboxQuickTest.singleFunction(
    functionFile = Path.of("scratch/test.mcfunction"),
    version = "26.2",
)
    .function()
    .assertScore("#single", "runs", 1)
    .requirePassed()
```

需要更底层控制时：

```kotlin
val sandbox = createFunctionSandbox(
    version = "26.2",
    functionFile = Path.of("scratch/test.mcfunction"),
)
sandbox.runFunction("sandbox:main")
```

也可以直接传入函数字符串，不需要创建临时文件：

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

需要多个轻量函数时，可以混合文件和内存字符串：

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

这些轻量函数也可以加载文件夹或 zip 数据包作为依赖：

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

## 预定义世界状态

测试可以在执行任何步骤前定义初始世界。通过 API 写入的 NBT 仍会按当前版本 profile 做校验，所以未知顶层实体/方块实体字段会像 `data modify` 一样失败。

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
        worldSpawn(4.0, 70.0, 5.0, forced = true)
        forcedChunk(0, 0)
        biome(0, 64, 0, "minecraft:plains")
        worldBorder(centerX = 5.0, centerZ = -6.0, size = 100.0, warningDistance = 8)
        block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
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
        difficulty = "hard",
        defaultGameMode = "creative",
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
    .assertEntityEquipment("weapon.mainhand", type = "minecraft:pig", tag = "fixture", id = "minecraft:iron_sword", dimension = "minecraft:the_nether")
    .assertEntityEffect("minecraft:strength", type = "minecraft:pig", tag = "fixture", durationTicks = 80, amplifier = 2, dimension = "minecraft:the_nether")
    .assertEntityAttribute("minecraft:max_health", type = "minecraft:pig", tag = "fixture", value = 12.0, dimension = "minecraft:the_nether")
    .assertEntityCount(expected = 1, type = "minecraft:pig", tag = "fixture", dimension = "minecraft:the_nether")
    .assertEntityCountRange(min = 1, max = 3, type = "minecraft:pig", tag = "fixture", dimension = "minecraft:the_nether")
    .assertPlayer(
        "Alex",
        xp = 5,
        recipe = "minecraft:bread",
        effect = "minecraft:speed",
        spawn = Position(2.0, 66.0, 3.0),
        spawnDimension = "minecraft:overworld",
        spawnAngle = 90.0,
        spawnForced = false,
        nbtPath = "Health",
        nbtEquals = "20.0",
    )
    .assertTeam("red", member = "Alex", memberCount = 1, optionName = "color", optionEquals = "red")
    .assertBossbar("demo:bar", name = "Demo", value = 3, max = 10, player = "Alex")
    .assertItem("Alex", "minecraft:stick", 2, minCount = 1, maxCount = 3)
    .assertScore("#fixture", "ready", 1)
    .assertScoreRange("#fixture", "ready", min = 1, max = 3)
    .assertStorageExists("demo:env", "ready")
    .assertStorageMissing("demo:env", "debug.last")
    .assertPlayerXp("Alex", 5)
    .requirePassed()
```

## 从 Java 存档导入测试环境

可以从已有 Minecraft Java Edition 存档导入指定 chunk 或方块范围。导入器读取 Anvil `.mca` 文件中的方块状态、方块实体和实体区域；不会默认加载整个存档。

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

## 多版本矩阵测试

同一行为需要在多个 Minecraft 版本上通过时，使用 `SandboxQuickTest.matrix(...)`。每个版本可以指向自己的数据包目录，这样 `pack_format` 和资源目录布局保持正确。

```kotlin
SandboxQuickTest.matrix(
    mapOf(
        "1.20.4" to listOf(Path.of("packs/demo-1_20_4")),
        "26.1.2" to listOf(Path.of("packs/demo-26_1_2")),
        "26.2" to listOf(Path.of("packs/demo-26_2")),
    ),
)
    .load()
    .world { player("Alex", xp = 5) }
    .keyInput("Alex", "jump")
    .assertScore("#clock", "ticks", 0)
    .assertPlayerXp("Alex", 5)
    .assertPlayerLastInput("Alex", "keyboard", "jump", "press")
    .requirePassed()
```

## Java 示例

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

## 常用方法

| 方法 | 用途 |
|---|---|
| `load()` | 运行 `#minecraft:load`。 |
| `ticks(n)` | 推进沙盒 tick。 |
| `function(id)` | 运行指定数据包函数。 |
| `function()` | 运行 `singleFunction(...)` 创建的默认函数。 |
| `singleFunctionText(text, version)` | 从一个函数字符串创建 quick-test 场景。 |
| `functions(sources, version)` | 从多个 `FunctionSource` 创建 quick-test 场景，可附加数据包依赖。 |
| `matrix(packsByVersion)` | 创建多版本 quick-test 矩阵。 |
| `command(raw)` | 执行一条命令。 |
| `world { ... }` | 在行为执行前应用内存世界 fixture。 |
| `setupWorld(setup)` | 应用可复用的 `SandboxWorldSetup`。 |
| `importSave(path, chunks, dimension)` | 导入指定 Java Anvil 存档 chunk。 |
| `player(name)` | 创建或复用玩家。 |
| `event(player, type, id, action)` | 注入玩家事件。 |
| `keyInput(player, key, action)` | 注入键盘输入。 |
| `mouseInput(player, button, action, x, y)` | 注入鼠标输入。 |
| `playerAdvancementCriterion(player, advancement, criterion, done)` | 预置玩家单个 advancement criterion 状态。 |
| `playerAdvancement(player, advancement, criteria)` | 预置玩家多个 advancement criterion 状态。 |
| `assertScore(target, objective, expected)` | 断言 scoreboard。 |
| `assertScoreAtLeast(target, objective, minimum)` | 断言 scoreboard 下界。 |
| `assertScoreAtMost(target, objective, maximum)` | 断言 scoreboard 上界。 |
| `assertScoreRange(target, objective, min, max)` | 断言 scoreboard 可选上下界。 |
| `assertStorageEquals(id, path, expectedJson)` | 断言 storage 路径。 |
| `assertStorageExists(id, path)` | 断言 storage 根对象或路径存在。 |
| `assertStorageMissing(id, path)` | 断言 storage 根对象或路径不存在。 |
| `assertWorld(...)` | 断言选定的世界级状态、force-loaded chunk、biome override、世界出生点和世界边界。 |
| `assertPlayer(...)` | 断言选定的玩家状态、末影箱物品数量、出生点细节和完整 NBT path。 |
| `assertTeam(...)` | 断言选定 team 状态、成员、成员数量和选项。 |
| `assertBossbar(...)` | 断言选定 bossbar 状态和关联玩家。 |
| `assertPredicate(id, expected, playerName)` | 断言已加载 predicate 的执行结果。 |
| `assertLoot(table, context, playerName, seed, count, item)` | 断言确定性的 loot 生成结果。 |
| `assertBlock(x, y, z, id, exists, nbtPath, nbtEquals, nbtExists)` | 断言 sparse world 中的方块。 |
| `assertEntity(type, tag, uuid, position, exists, count, dimension, health, vehicle, passenger, passengerCount, nbtPath, nbtEquals, nbtExists)` | 断言匹配实体存在性、数量和完整 NBT path。 |
| `assertEntityEquipment(slot, type, tag, uuid, position, id, count, exists, minCount, maxCount, componentsPath, componentsEquals, componentsExists, nbtPath, nbtEquals, nbtExists, dimension)` | 断言非玩家实体装备。 |
| `assertEntityEffect(effect, type, tag, uuid, position, exists, durationTicks, amplifier, hideParticles, dimension)` | 断言非玩家实体 active effect。 |
| `assertEntityAttribute(attribute, type, tag, uuid, position, exists, value, min, max, dimension)` | 断言非玩家实体 attribute。 |
| `assertEntityCount(expected, type, tag, dimension)` | 断言匹配实体数量。 |
| `assertEntityCountAtLeast(minimum, type, tag, dimension)` | 断言匹配实体数量下界。 |
| `assertEntityCountAtMost(maximum, type, tag, dimension)` | 断言匹配实体数量上界。 |
| `assertEntityCountRange(min, max, type, tag, dimension)` | 断言匹配实体数量的可选上下界。 |
| `assertItem(player, id, count, slot, exists, minCount, maxCount, componentsPath, componentsEquals, componentsExists, nbtPath, nbtEquals, nbtExists, container)` | 断言玩家背包或末影箱中的匹配物品。 |
| `assertPlayerXp(player, expected)` | 断言玩家 XP。 |
| `assertPlayerLastInput(player, device, code, action)` | 断言玩家最后一次输入。 |
| `assertAdvancementDone(player, id, expected)` | 断言 advancement 是否完成。 |
| `assertOutputContains(text)` | 断言输出事件包含文本。 |
| `assertOutput(...)` | 按 command/channel/target/text/规范化文本/payload path/count/order 断言输出事件。 |
| `assertTrace(...)` | 按 command/root/source/success/输出数量/输出文本/输出目标/diff path/diff kind/count 断言 trace 事件。 |
| `assertPlayerEventTrace(...)` | 按 player/type/success/上下文/advancement/失败 advancement/count 断言玩家事件 trace。 |
| `assertSnapshotDiff(...)` | 按 before/after snapshot 的 path/kind/渲染文本/count 断言状态变化。 |
| `outputs()` | 返回记录的输出事件。 |
| `traces()` | 返回记录的结构化命令 trace。 |
| `playerEventTraces()` | 返回记录的玩家事件 trace。 |
| `snapshotDiffs()` | 返回从初始状态到当前状态的稳定 JSON Pointer diff。 |
| `matchingTraces(...)` | 返回匹配结构化期望的 trace 事件。 |
| `matchingPlayerEventTraces(...)` | 返回匹配结构化期望的玩家事件 trace。 |
| `matchingOutputs(...)` | 返回匹配结构化期望的输出事件。 |
| `report()` | 返回 `SandboxQuickTestReport`，不抛异常。 |
| `requirePassed()` | 返回报告；如果失败则抛异常。 |

`assertBlock` 和 `assertItem` 的 path 检查使用和 manifest 断言相同的
`JsonPaths` 语义。`nbtEquals` 和 `componentsEquals` 接受 JSON/SNBT-lite 文本。

## 输出断言

输出命令会进入 `OutputEvent`。可用于测试 `tellraw`、`title`、`say`、`msg`、`playsound`、`particle`、warning 等可观测行为。

```kotlin
val report = SandboxQuickTest.singleFunction(Path.of("scratch/output.mcfunction"), "26.2")
    .function()
    .assertOutput(command = "say", channel = "chat", target = "Steve", contains = "hello")
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

结构化命令 payload 可以按 path 匹配：

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

Trace 断言也可以直接匹配命令副作用：

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

## 键盘/鼠标输入测试

```kotlin
SandboxQuickTest.create(listOf(Path.of("packs/demo")))
    .keyInput("Steve", "key.jump")
    .mouseInput("Steve", "left", "click", 12.0, 8.0)
    .assertPlayerLastInput("Steve", "mouse", "left", "click")
    .requirePassed()
```

输入事件会写入玩家的 `lastInput` 和 `inputEvents`，可通过 snapshot、`inspect player` 或 `SandboxQuickTestReport.snapshot` 查看。

## 底层 API

需要完全控制运行时时，可直接使用底层 API：

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

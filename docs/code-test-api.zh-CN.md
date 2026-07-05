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

`requirePassed()` 在断言失败时抛出 `SandboxQuickTestAssertionError`。异常中包含所有失败项和最终 snapshot。

`report()` 和 `requirePassed()` 返回的报告还包含结构化命令 trace。每条 trace 记录命令文本、命令根、是否成功、执行的命令数、产生的输出数、来源文件/行号、函数调用栈、执行者和位置。需要调试生成器输出或函数调用链时，可以直接读取：

```kotlin
val report = SandboxQuickTest.singleFunctionText(
    functionText = "say traced",
    version = "26.2",
)
    .function()
    .requirePassed()

println(report.traces.single().command)
```

需要比较两个状态时，可以用 `SnapshotDiff.diff(before, after)` 获得稳定 JSON Pointer 路径差异，或用 `SnapshotDiff.render(...)` 输出适合测试失败日志的文本。

`unsupportedFeatureMode` 可选值：

- `WARN`：默认行为，未支持命令记录 warning 并继续。
- `IGNORE`：静默跳过未支持命令。
- `ERROR`：严格模式，遇到未支持命令立即失败。

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
    .assertEntityCount(expected = 1, type = "minecraft:pig", tag = "fixture")
    .assertItem("Alex", "minecraft:stick", 2)
    .assertScore("#fixture", "ready", 1)
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
    .assertScore("#clock", "ticks", 0)
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
| `assertScore(target, objective, expected)` | 断言 scoreboard。 |
| `assertStorageEquals(id, path, expectedJson)` | 断言 storage 路径。 |
| `assertWorld(...)` | 断言选定的世界级状态。 |
| `assertBlock(x, y, z, id, exists)` | 断言 sparse world 中的方块。 |
| `assertEntityCount(expected, type, tag)` | 断言匹配实体数量。 |
| `assertItem(player, id, count, slot, exists)` | 断言玩家背包中的匹配物品。 |
| `assertPlayerXp(player, expected)` | 断言玩家 XP。 |
| `assertPlayerLastInput(player, device, code, action)` | 断言玩家最后一次输入。 |
| `assertAdvancementDone(player, id, expected)` | 断言 advancement 是否完成。 |
| `assertOutputContains(text)` | 断言输出事件包含文本。 |
| `assertOutput(...)` | 按 command/channel/target/text/count 断言输出事件。 |
| `outputs()` | 返回记录的输出事件。 |
| `traces()` | 返回记录的结构化命令 trace。 |
| `matchingOutputs(...)` | 返回匹配结构化期望的输出事件。 |
| `report()` | 返回 `SandboxQuickTestReport`，不抛异常。 |
| `requirePassed()` | 返回报告；如果失败则抛异常。 |

## 输出断言

输出命令会进入 `OutputEvent`。可用于测试 `tellraw`、`title`、`say`、`msg`、`playsound`、`particle`、warning 等可观测行为。

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

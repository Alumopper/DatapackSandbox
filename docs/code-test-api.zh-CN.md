# 代码测试 API

除了 CLI 和 `.dps.json` 清单，Kotlin/Java 项目也可以直接调用 `:testkit` 的 quick-test API。适用场景包括本地单元测试、插件测试、构建脚本冒烟测试，以及只想验证一个 `.mcfunction` 文件的轻量用法。

## Artifact 与运行要求

fluent 测试 API 对应的是 `testkit` artifact：

```text
moe.afox.dpsandbox:testkit:1.0.1
```

`cli` 模块和 `datapack-sandbox-cli.jar` 面向命令行使用。JVM 测试应依赖 `testkit`，它会传递引入底层 `core` runtime；不要把 standalone CLI jar 当作库依赖。

发布 artifact 使用项目的 Java 25 toolchain 构建，因此编译和测试执行需要 Java 25 或更新版本。运行时依赖会通过 Maven 传递解析，但外部构建需要包含 Maven Central 和 Mojang library 仓库，因为 core runtime 依赖 Gson、Brigadier 和 LZ4。

## Gradle 依赖

使用已发布 artifact：

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

如果想要把沙盒嵌入自己的工具、插件或服务，而不是只在测试中使用，把 `testImplementation(...)` 改成 `implementation(...)`。如果使用 snapshot 版本，坐标版本使用 `-SNAPSHOT`，仓库改为 `https://nexus.mcfpp.top/repository/maven-snapshots/`。

在同一个 multi-project build 中：

```kotlin
dependencies {
    testImplementation(project(":testkit"))
}
```

## Maven 依赖

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

## API 入口

大多数测试应使用 `SandboxQuickTest`。它提供 fluent 的 setup、执行、断言和自包含的 `SandboxQuickTestReport`。Java 调用方可以使用 `DatapackSandboxTestApi`，这是同一套 quick-test API 的静态门面。

只有需要完全控制 runtime 时，才直接使用底层 factory：

| 入口 | 适用场景 |
|---|---|
| `SandboxQuickTest.create(...)` | 测试一个或多个真实数据包目录或 zip。 |
| `SandboxQuickTest.singleFunction(...)` | 测试一个生成的 `.mcfunction` 文件。 |
| `SandboxQuickTest.singleFunctionText(...)` | 不写文件，直接测试生成出的命令文本。 |
| `SandboxQuickTest.functions(...)` | 测试多个 synthetic function，可附加数据包依赖。 |
| `SandboxQuickTest.matrix(...)` | 同一场景跨多个 Minecraft version profile 运行。 |
| `DatapackSandboxTestApi` | Java 侧用静态方法调用 quick-test API。 |
| `createSandbox(...)` | 直接取得 `DatapackSandbox`，用于自定义 runner。 |
| `createFunctionSandbox(...)` | 直接创建由 synthetic function source 支撑的底层 runtime。 |

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

## 版本与 pack 元数据

`SandboxQuickTest.create(...)` 默认使用当前内置的最新 profile（此版本是 `26.2`）。如果数据包面向其他 Minecraft 版本，应显式传入 `version = "..."`；API 不会根据 `pack.mcmeta` 自动推断运行时 profile。

`pack.mcmeta` 的 format 不匹配现在是加载 warning，不是异常。底层 API 可以从 `sandbox.datapack.warnings` 读取，quick-test 报告里也会作为 `warning` output event 出现：

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

现代 pack format 值可以写成整数数组。加载器会把 `[107, 1]` 按 `107.1` 处理，把 `[94]` 按 `94` 处理，因此下面的 `pack.mcmeta` 是有效的：

```json
{
  "pack": {
    "min_format": [94],
    "max_format": [107, 1],
    "description": "Example 26.2 datapack"
  }
}
```

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

scheduled function 队列可以直接断言，不必手写 snapshot JSON path：

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

`dueTick` 是排程函数应执行的绝对 sandbox game tick。`count` 适合检查
`append` 模式下预期出现的重复排程条目。

gamerule 状态以字符串保存，也可以不写 snapshot path 直接断言：

```kotlin
SandboxQuickTest.create(listOf(pack), version = "26.2")
    .command("gamerule doDaylightCycle false")
    .assertGamerule("doDaylightCycle", "false")
    .assertGamerule("missingRule", exists = false)
    .requirePassed()
```

scoreboard UI 状态也可以直接断言，适合检查命令生成器输出：

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
    .world { player("Alex", xp = 5, xpLevels = 2) }
    .keyInput("Alex", "jump")
    .assertScore("#clock", "ticks", 0)
    .assertPlayerXp("Alex", 5)
    .assertPlayerXpLevels("Alex", 2)
    .assertPlayerLastInput("Alex", "keyboard", "jump", "press")
    .requirePassed()
```

可以使用 `forEachScenario { ... }` 将任意当前或未来的单场景操作/断言应用到整个矩阵，不必等待矩阵类再增加一份镜像便捷方法。

## Java 示例

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

## 常用方法

| 方法 | 用途 |
|---|---|
| `DatapackSandboxTestApi.scenario(...)` | Java 友好的 `SandboxQuickTest.create(...)` 静态门面。 |
| `DatapackSandboxTestApi.runFunctionText(...)` | 从 Java 运行一段内存函数文本并返回 report。 |
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
| `event(player, type, id, action)` | 注入玩家事件；交互/攻击事件的 `id` 可填写只定向一个真实实体的 selector 或 UUID。 |
| `blockEvent(player, type, id, x, y, z)` | 注入带坐标的方块玩家事件，并更新 sparse world 目标位置。 |
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
| `assertRandomSequence(name, expected, exists)` | 断言确定性随机序列状态或缺失状态。 |
| `assertForcedChunk(x, z, exists)` | 按 chunk 坐标断言强加载状态。 |
| `assertGamerule(name, value, exists)` | 以字符串值断言已保存的 gamerule 状态。 |
| `assertScheduledFunction(id, dueTick, exists, count)` | 按函数 id、绝对 due tick、存在性或重复条目数量断言 scheduled function 队列。 |
| `assertScoreboardObjective(name, exists, criteria, displayName, renderType, displayAutoUpdate)` | 断言 scoreboard objective 的 criteria 和 UI 元数据。 |
| `assertScoreboardDisplay(slot, objective, exists)` | 断言 `sidebar`、`list` 或 `sidebar.team.red` 等 scoreboard display slot。 |
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
| `assertPlayerXp(player, expected)` | 断言玩家 XP points。 |
| `assertPlayerXpLevels(player, expected)` | 断言玩家 XP levels。 |
| `assertPlayerLastInput(player, device, code, action)` | 断言玩家最后一次输入。 |
| `assertAdvancementDone(player, id, expected)` | 断言 advancement 是否完成。 |
| `assertOutputContains(text)` | 断言输出事件包含文本。 |
| `assertOutput(...)` | 按 command/channel/target/渲染后 text/rawText/正则/规范化文本/payload path/segment/count/order 断言输出事件。 |
| `assertTrace(...)` | 按 command/root/source/success/输出数量/输出文本/输出目标/diff path/diff kind/count 断言 trace 事件。 |
| `assertPlayerEventTrace(...)` | 按 player/type/success/上下文/目标 UUID/interaction response/方块坐标/input 元数据/advancement/失败 advancement/count 断言玩家事件 trace。 |
| `assertSnapshotDiff(...)` | 按 before/after snapshot 的 path/kind/渲染文本/count 断言状态变化；失败时列出实际 diff 候选。 |
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

## 断言语义

所有 fluent `assert...` 方法都会把失败记录到当前 scenario 或 matrix，并返回同一个对象继续链式调用。用
`report()` 可以查看失败而不抛异常；用 `requirePassed()` 会在存在失败时抛出
`SandboxQuickTestAssertionError`。

大多数可选断言参数都是过滤条件：`null` 表示不检查该字段。`assertOutput(...)`、
`assertTrace(...)`、`assertPlayerEventTrace(...)` 这类事件列表断言在没有设置 `count`
时要求至少一个匹配；设置 `count` 后要求匹配数量严格相等。输出断言的 `order`
是从 1 开始的全局输出事件序号。

字符串重载仍然保留，用于自定义 id、未来 vanilla 值或高级测试。固定取值参数同时提供枚举重载，Kotlin/Java
调用者可以通过 IDE 自动补全：

| 枚举 | 使用位置 |
| --- | --- |
| `OutputChannel` | `assertOutput(...)`、`matchingOutputs(...)` 的 channel，例如 `CHAT`、`TITLE`、`WORLDGEN`、`WARNING` |
| `CommandRoot` | `assertTrace(...)`、`matchingTraces(...)` 的 root，例如 `SAY`、`SCOREBOARD`、`FUNCTION`、`EXECUTE` |
| `SandboxWeather`、`SandboxDifficulty`、`SandboxGameMode` | `assertWorld(...)` 和 `assertPlayer(...)` 的天气、难度、默认游戏模式和玩家游戏模式 |
| `PlayerInputDevice`、`PlayerInputAction`、`PlayerEventType` | `keyInput(...)`、`mouseInput(...)`、`assertPlayerLastInput(...)`、`assertPlayerEventTrace(...)`、`matchingPlayerEventTraces(...)` |
| `ScoreboardRenderType`、`ScoreboardDisplaySlot` | `assertScoreboardObjective(...)` 的 render type 和 `assertScoreboardDisplay(...)` 的 slot |
| `EntityEquipmentSlot`、`ItemContainer`、`LootContextId` | `assertEntityEquipment(...)`、`assertItem(...)`、`assertLoot(...)` |
| `BossbarColor`、`BossbarStyle`、`TeamOption` | `assertBossbar(...)` 和 `assertTeam(...)` 的固定选项 |

### 断言参数速查

| 断言 | 主要检查内容 |
| --- | --- |
| `assertScore`、`assertScoreAtLeast`、`assertScoreAtMost`、`assertScoreRange` | scoreboard 值相等和可选数值上下界。 |
| `assertStorageEquals`、`assertStorageExists`、`assertStorageMissing` | storage 根/路径存在性或精确 JSON/SNBT-lite 值。 |
| `assertWorld` | `gameTime`、`dayTime`、`weather`、`difficulty`、`defaultGameMode`、`seed`、强加载 chunk、biome override、世界出生点和世界边界。 |
| `assertRandomSequence`、`assertForcedChunk`、`assertGamerule`、`assertScheduledFunction` | 不需要手动读 snapshot JSON 的世界运行时状态。 |
| `assertScoreboardObjective`、`assertScoreboardDisplay` | objective criteria、显示名、render type、display auto-update 和 display slot 绑定。 |
| `assertPlayer`、`assertPlayerXp`、`assertPlayerXpLevels`、`assertPlayerLastInput` | 玩家存在性、位置、维度、游戏模式、XP、生命、饥饿、背包/末影箱数量、recipe/effect/stat、出生点、NBT path 和最后一次输入。 |
| `assertTeam`、`assertBossbar` | team 存在性、显示名、成员、选项，以及 bossbar 的 value/max/color/style/visible/players。 |
| `assertPredicate`、`assertLoot`、`assertAdvancementDone` | predicate 结果、带 context/player/seed 的确定性 loot 输出，以及 advancement 完成状态。 |
| `assertBlock` | sparse world 方块 id、存在性和方块 NBT path。 |
| `assertEntity`、`assertEntityCount*` | 按 type、tag、UUID、位置、维度、生命、载具/乘客、乘客数量和 NBT path 检查实体存在性/数量。 |
| `assertEntityEquipment`、`assertEntityEffect`、`assertEntityAttribute` | 实体装备物品过滤、active effect 字段和 attribute 值/范围。 |
| `assertItem` | 玩家 `inventory` 或 `enderItems` 中物品 id/count/slot/min/max，以及 component 和 NBT path。 |
| `assertOutputContains`、`assertOutput` | 输出事件的 command、channel、target(s)、渲染后 `text`、命令可见 `rawText`、正则/规范化匹配、payload path、文本 segment、count 和 order。 |
| `assertTrace` | 命令 trace 的 command/root/source file/function、success、输出数量/文本/目标，以及 snapshot diff path/kind/渲染文本。 |
| `assertPlayerEventTrace` | 玩家事件 dispatch 的 player/type/success、advancement 命中/失败、item/entity/target/interaction response/block/recipe/dimension/damage 元数据和 input device/code/action。 |
| `assertSnapshotDiff` | 从初始状态到当前状态的 snapshot diff path、kind、渲染文本和数量。 |

对于 `say`、`me`、`msg`/`tell`/`w`、`teammsg`/`tm`，`OutputEvent.text`
是渲染后的聊天行，例如 `<Server> hello`；`rawText` 只保留命令传入的消息内容，例如
`hello`。如果你关心开发者通常理解的“命令输出内容”，优先用 `rawText`、`rawContains`
或 `normalizedRawText`，而不是匹配带装饰前缀的聊天文本。

## 输出断言

输出命令会进入 `OutputEvent`。可用于测试 `tellraw`、`title`、`say`、`msg`、`playsound`、`particle`、warning 等可观测行为。

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

payload 断言失败时，失败消息会带上候选输出在该 path 上的实际 payload 值。

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
    .keyInput("Steve", "key.jump", PlayerInputAction.PRESS)
    .mouseInput("Steve", "left", PlayerInputAction.CLICK, 12.0, 8.0)
    .assertPlayerLastInput("Steve", PlayerInputDevice.MOUSE, "left", PlayerInputAction.CLICK)
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

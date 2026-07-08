# 测试模式

这页按开发者任务组织测试写法。你可以把它当作 cookbook：先找到测试目标，再复制对应结构，最后按项目资源替换函数、玩家、scoreboard 或 storage 名称。

## 选哪种测试

| 测试目标 | 推荐模式 | 不推荐 |
| --- | --- | --- |
| 一段函数是否写入正确 score/storage | 单函数 QuickTest | 为此启动完整 manifest |
| 一个完整 pack 是否能加载并运行 smoke function | manifest smoke | 把所有资源都塞进单函数文本 |
| 某个命令是否被支持或输出是否稳定 | command/output 断言 | 只检查没有抛异常 |
| advancement、predicate、loot 依赖玩家上下文 | fixture + player event | 手写不完整 NBT 模拟玩家 |
| 生成器输出是否符合预期 | 生成目录 + manifest/check | 只比较字符串 |
| 版本差异是否可接受 | matrix test | 只在当前最新 profile 上测 |

## 模式 1：单函数行为测试

适合验证局部命令行为，失败时反馈最短。

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

检查点：

- 使用枚举参数让 IDE 自动补全固定选项，例如 `CommandRoot.SCOREBOARD`。
- 同时断言最终状态和 trace，可以区分“命令没执行”和“执行后状态不对”。
- 不要把太多无关命令堆在一个测试里；一个测试对应一个行为目标。

## 模式 2：输出命令测试

聊天类命令有两个文本层：

| 字段 | 适合断言什么 |
| --- | --- |
| `text` | 渲染后的完整聊天行，例如 `<Server> Hello` |
| `rawText` | 命令参数中的原始消息，例如 `Hello` |

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

开发者通常更关心 `rawText`。只有在你确实要验证聊天装饰、目标选择或渲染格式时，才匹配 `text`。

## 模式 3：预置世界状态

当测试依赖玩家、方块、实体、时间、天气或 gamerule 时，把它们作为 fixture 输入，不要把测试前置命令写得过长。

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

这种写法把“准备环境”和“被测行为”分开，失败时更容易判断是 fixture 问题还是函数逻辑问题。

## 模式 4：玩家事件测试

玩家事件适合覆盖 advancement trigger、predicate、物品使用、方块交互和输入事件。

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

建议同时断言：

- event trace 是否成功 dispatch；
- 关键上下文字段是否匹配；
- 被事件触发的最终状态，例如 score、storage、advancement done。

## 模式 5：多版本矩阵

当数据包同时支持多个 Minecraft 版本或 pack format 时，使用 matrix 测试让差异显式化。

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

矩阵测试适合发现：

- 某个版本 profile 的 `pack_format` warning；
- 命令支持等级变化；
- 资源格式或 vanilla data 目录变化；
- 测试 fixture 对版本假设过强。

## 模式 6：生成器产物回归测试

如果你的项目生成 `.mcfunction`、loot table 或 tags，不要只比较文本。推荐流程：

1. 运行生成器，把 pack 输出到 `build/generated-datapacks/<case>`。
2. 用 manifest 或 QuickTest 加载生成目录。
3. 断言可观察行为。
4. CI 失败时保留生成目录和 sandbox report。

这样可以同时发现语法错误、资源路径错误、版本格式错误和运行时行为错误。

## 断言组合建议

| 行为 | 推荐断言组合 |
| --- | --- |
| scoreboard 写入 | `assertScore` + `assertTrace` |
| storage 写入 | `assertStorageEquals` + `assertSnapshotDiff` |
| 聊天输出 | `assertOutput(rawText = ...)` + `OutputChannel.CHAT` |
| advancement | `assertAdvancementDone` + `assertPlayerEventTrace` |
| loot | `assertLoot(seed = ...)` + 结果物品数量 |
| entity 操作 | `assertEntity` + `assertEntityCountRange` |
| 方块操作 | `assertBlock` + snapshot diff |

## 命名约定

测试名建议写成行为句，而不是 API 名：

```kotlin
fun generatedRewardFunctionAddsOnePoint()
fun usingKeyItemUnlocksDoorAdvancement()
fun unsupportedCommandReportsWarningButDoesNotAbort()
```

这样 report 失败时可以直接映射到用户场景。

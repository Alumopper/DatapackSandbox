# Core API 嵌入参考

## 适用场景

当 JVM 应用需要长期持有一个 `DatapackSandbox` 时直接依赖 `core`，例如自定义编辑器、构建插件、不使用 JUnit 的测试框架，或需要精确控制 load、tick、function、command 顺序的服务。若 QuickTest 的测试生命周期已经够用，请使用 [QuickTest](/guide/code-test-api)；若更需要进程隔离和文件产物，请使用 [CLI](/reference/cli)。

## 前置条件

```kotlin
dependencies {
    implementation("moe.afox.dpsandbox:core:1.1.0")
}
```

Core 需要 Java 25。必须明确选择 Minecraft profile；`26.2` 是当前默认 profile，但集成应为每个文档或测试保存实际使用的版本。`api/core.api` 是构建时检查的 JVM ABI 基线，不是逐符号生成的文档，因此本页按实际任务说明公开入口。

## 最小可运行示例

```kotlin
import moe.afox.dpsandbox.core.CommandTraceMode
import moe.afox.dpsandbox.core.SandboxWorldSetup
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.createFunctionSandboxFromString

val sandbox = createFunctionSandboxFromString(
    version = "26.2",
    functionText = """
        scoreboard objectives add runs dummy
        scoreboard players add #core runs 1
        say core finished
    """.trimIndent(),
)

SandboxWorldSetup()
    .block(0, 64, 0, "minecraft:stone")
    .applyTo(sandbox.world, sandbox.profile)
sandbox.world.commandTraceMode = CommandTraceMode.FULL

val before = sandbox.snapshotJson()
val result = sandbox.runFunction("sandbox:main")
val after = sandbox.snapshotJson()

check(result.success)
check(sandbox.world.getScore("#core", "runs") == 1)
val stateChanges = SnapshotDiff.stateDiff(before, after)
println("commands=${result.commandsExecuted}, changes=${stateChanges.size}")
```

单字符串工厂默认把源码注册为 `sandbox:main`；可用 `functionId` 覆盖。默认玩家为 `Steve`；若测试需要空玩家列表，传入 `defaultPlayerName = null`。

## 选择工厂

| 工厂 | 适合场景 | 关键行为 |
| --- | --- | --- |
| `createSandbox(version, packs, ...)` | 一个或多个真实数据包目录/ZIP | `packs` 按 pack 优先级顺序加载 |
| `createFunctionSandbox(version, functionSources, ...)` | 不建完整 pack 树的多函数测试 | 接受 `FunctionSource.text` 和 `FunctionSource.file` |
| `createFunctionSandbox(version, packs, functionSources, ...)` | 真实依赖包 + 少量测试函数 | synthetic function 覆盖 dependency packs |
| `createFunctionSandbox(version, functionFile, ...)` | 磁盘上的单个 `.mcfunction` | 为文件分配临时资源 id |
| `createFunctionSandboxFromString(...)` | 单个聚焦场景 | 建立可运行 sandbox 的最短路径 |

所有工厂还可接收预配置的 `SandboxWorld`、可选默认玩家、`UnsupportedFeatureMode` 与 `SandboxLimits`。工厂构造时完成资源加载和校验；pack 文件变化后应新建 sandbox，或使用集成层自己的 reload 流程。

## 准备世界状态

执行前优先用 `SandboxWorldSetup` 描述 fixture。它支持时间、seed/random sequence、天气/难度、世界出生点和边界、强制加载区块、biome、方块/区域/结构、实体、玩家、背包/effect/advancement、score、storage、gamerule、team、bossbar 与有范围限制的存档导入。

```kotlin
SandboxWorldSetup()
    .player("Alex", 0.5, 65.0, 0.5, "minecraft:overworld")
    .entity("minecraft:pig", 2.0, 64.0, 2.0, tags = listOf("test_target"))
    .block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
    .storage("demo:state", "{phase:1}")
    .score("Alex", "points", 5)
    .applyTo(sandbox.world, sandbox.profile)
```

Fixture 只修改可变 world，不添加数据包资源。`importSave` 只导入指定区块或有界方块范围，不会复制完整运行中服务器；playerdata、光照、POI 和原版 scheduled tick 不在导入边界内。单个 region fixture 上限为 32,768 个方块。

## 执行生命周期

| 操作 | 效果 | 返回值 |
| --- | --- | --- |
| `runLoad()` | 运行 `#minecraft:load` 的全部函数 | 执行命令数 |
| `runTicks(count)` | 推进时间、到期 schedule、`#minecraft:tick` 与玩家 tick advancement 事件 | 执行命令数 |
| `runFunction(id, context)` | 运行函数、嵌套调用和 macro | 命令数、return value、success |
| `executeCommand(text, location, context)` | 运行一条原始命令，允许前导 `/` | 命令数和 success |
| `checkCommand` / `checkCommands` | 在当前 world 的隔离副本中校验 | validity 与结构化诊断 |
| `handlePlayerEvent(event)` | 应用已建模的玩家动作和 advancement criterion | advancement updates |
| `generateLoot(...)` | 使用显式 context/seed 计算 loot table | 确定性 loot result |

`runTicks` 不模拟实体 AI、物理、红石或方块更新，只推进 clean-room runtime 明确建模的生命周期。`checkCommands` 共享一个 preview world，前一条有效命令可为后一条准备状态；live world、output、trace、checkpoint、执行预算与资源均不改变。

### 执行上下文

当相对坐标、selector、执行者身份、旋转、anchor 或 dimension 会影响执行时，传入 `ExecutionContext`：

```kotlin
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation

val alex = sandbox.world.requirePlayer("Alex")
sandbox.runFunction(
    "demo:relative",
    ExecutionContext(
        entity = alex,
        position = Position(10.0, 70.0, -4.0),
        dimension = ResourceLocation.parse("minecraft:overworld"),
        yaw = 90.0,
        pitch = 0.0,
        anchor = "eyes",
    ),
)
```

Predicate engine 由 sandbox 注入，应用通常不需要设置 `predicateEngine`。

## 读取状态、输出与资源

`sandbox.world` 暴露已建模的玩家、实体、方块、计分板、storage、schedule、output、command trace、player event trace、bossbar、gamerule、team、random sequence、forced chunk、biome 和 world border。若存在 `getScore`、`setScore`、`storage`、`block`、`createPlayer`、`snapshot` 等公开操作，应优先使用它们。

`sandbox.datapack` 暴露 function、loot table、predicate、advancement、tag、raw resource、warning 和资源索引。资源索引会记录 active/overridden 项，可用于解释 pack priority。

Output event 保存在 `world.outputs`，也可通过 `addOutputListener` / `removeOutputListener` 流式接收。每个 output 包含 tick、command、channel、targets、规范化文本、可选 raw text、带样式 segments、结构化 payload 与 command source。设计外部日志格式前请阅读 [报告与可观测性](/reference/reports-observability)。

## Snapshot、diff、trace 与 coverage

`snapshotJson()` / `snapshotString()` 返回稳定序列化的 modeled state 与 active version。`SnapshotDiff.diff(before, after)` 比较所有变化；不希望 trace bookkeeping 进入比较时用 `SnapshotDiff.stateDiff(...)`。Diff kind 为 `ADDED`、`REMOVED`、`CHANGED`，路径使用 JSON Pointer。

`world.commandTraceMode` 可设为：

- `OFF`：不记录 command event；
- 轻量 trace 模式：只需要 command/source/result；
- `FULL`：每条命令还需要 output event 与 state diff。

Full trace 会在每条命令前后生成状态快照，因此 CPU 和内存成本更高。Coverage 在多次操作间累计：

```kotlin
val coverage = sandbox.coverageReport()
println("functions=${coverage.functions.size}")
sandbox.resetCoverage()
```

## Checkpoint 与取消

`saveCheckpoint`、`restoreCheckpoint`、`deleteCheckpoint`、`checkpointNames` 最多管理 32 个 world 副本。名称只能是 1–64 个 ASCII 字母、数字、`.`、`_`、`-`，且只能在命令边界操作。Checkpoint 包含 modeled world state，不包含 datapack resource 与单调累计的执行预算；restore 不会消耗 checkpoint。

长操作可以从另一条控制路径调用 `requestExecutionCancellation()`。取消只在命令/tick 边界协作生效，并抛出 `EXECUTION_INTERRUPTED`；已完成命令不会回滚。打算复用已取消的 sandbox 前，先调用 `clearExecutionCancellation()`。

## 诊断与安全策略

运行失败使用 `SandboxException`。公开 diagnostic code 区分输入格式、版本不匹配、资源缺失、未支持能力、命令错误、中断、断言失败与缺失执行上下文。集成应保留 `code`、`message`、`location`、`version`、`command`，不要只拼成字符串。

`UnsupportedFeatureMode` 控制“当前版本存在、但沙盒尚未完整建模”的命令：

- `WARN`：记录 warning output 后继续；
- `IGNORE`：静默继续；
- `ERROR`：抛出 `UNSUPPORTED_FEATURE`。

未知 command root 无论模式如何都是输入错误。回归测试和 CI 通常适合 `ERROR`，能更早暴露覆盖缺口。

默认 `SandboxLimits`：100,000 条命令、函数深度 64、单次 `runTicks` 100,000 ticks、100,000 个保留 output、10,000,000 字节 snapshot。命令数默认按整个 sandbox 生命周期累计；只有当受控的长期交互会话需要按每个顶层操作刷新预算时，才启用 `resetCommandBudgetPerOperation = true`。

## 并发与所有权

`DatapackSandbox` / `SandboxWorld` 是可变对象，不是多线程服务器抽象。每个并发文档/测试应使用独立 sandbox，或由单一 owner 串行化所有 mutation。Renderer 的 capture 本身不修改状态，但 capture 期间也不应并发修改 runtime。

## 限制

- Core 建模 datapack 可见行为，不是原版服务器；不提供网络、完整世界生成、区块模拟、AI 或原版线程调度。
- Profile 描述版本/资源/命令行为，不内置 Minecraft server 或 client。
- 稳定 snapshot 是 sandbox contract，不是原版 level-save 格式。
- 集成应依赖公开工厂与模型，不依赖 internal 类或 CLI 实现细节。

## 相关页面

- [QuickTest 总览](/guide/code-test-api)
- [世界 Fixture](/reference/quicktest-fixtures)
- [世界模型](/runtime/world-model)
- [命令支持](/runtime/command-support)
- [Renderer API](/reference/renderer-api)
- [报告与可观测性](/reference/reports-observability)

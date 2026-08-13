# QuickTest 断言参考

## 适用场景

行为执行后，用 fluent assertion 锁定可观察 state、resource behavior、output、trace 或 coverage。Assertion 会累计 failure，一次测试可同时报告多个不匹配；`report()` 返回当前结果，任一已注册 assertion 失败时 `requirePassed()` 抛 `SandboxQuickTestAssertionError`。

## 前置条件

```kotlin
val test = SandboxQuickTest
    .singleFunctionText(source, version = "26.2")
    .function()
```

Assertion 在调用时读取 scenario 状态。先完成需要的 behavior，再断言目标 checkpoint。

## 最小可运行示例

```kotlin
test
    .assertScore("#case", "runs", 1)
    .assertStorageEquals("demo:state", "ready", "true")
    .assertOutput(command = "say", contains = "ready", count = 1)
    .assertTrace(root = "scoreboard", success = true)
    .requirePassed()
```

## Failure 收集与 report

Assertion method 返回同一可变 scenario。失败会记录人类可读消息，链条继续：

```kotlin
val report = test
    .assertScoreAtLeast("#case", "runs", 1)
    .assertOutputContains("ready")
    .report()

if (!report.passed) {
    report.failures.forEach(::println)
}
```

`SandboxQuickTestReport` 包含 `passed`、`failures`、`outputs`、`traces`、`playerEventTraces`、`snapshotDiffs`、`resourceSummary`、最终 `snapshot`。测试边界使用 `requirePassed()`，让 JUnit/Gradle 得到失败 test，而不是依赖手工 boolean check。

## Score 与 storage

| 目标 | 入口 |
| --- | --- |
| 精确 score | `assertScore(target, objective, expected)` |
| 下/上界 | `assertScoreAtLeast`, `assertScoreAtMost` |
| 可选范围 | `assertScoreRange(target, objective, min, max)` |
| 精确 storage path value | `assertStorageEquals(id, path, expected)` |
| Path/root 存在 | `assertStorageExists` |
| Path/root 缺失 | `assertStorageMissing` |

Storage expected value 使用沙盒 NBT/JSON 比较语义。契约只有一个字段时指定 path；比较整个 storage object 会把测试耦合到无关字段。

```kotlin
test
    .assertScoreRange("#clock", "ticks", 20, 40)
    .assertStorageExists("demo:state", "result")
    .assertStorageEquals("demo:state", "result.ready", "true")
```

## World 与 scoreboard state

- `assertWorld(...)` 检查选定 clock、weather、difficulty、game mode、seed、spawn、border、tick 等字段。
- `assertBlock(x, y, z, id, exists, nbt, ...)` 检查稀疏 block state。
- `assertGamerule`、`assertRandomSequence`、`assertForcedChunk` 检查数据系统状态。
- `assertScheduledFunction` 按 overload 检查 id、due tick、replace/existence、count。
- `assertScoreboardObjective` 检查 objective existence/metadata。
- `assertScoreboardDisplay` 检查 display slot 与 objective。

契约只有一个 world field 时，优先专用 helper，不要完整 snapshot。

## Player、entity 与 collection

### Player

`assertPlayer` 可约束 name/existence、position/dimension、game mode、XP/level、health/food、inventory count、selected slot、recipe、effect、stat、NBT、spawn、last input。专用 helper 有 `assertPlayerXp`、`assertPlayerXpLevels`、`assertPlayerLastInput`。

### Entity

`assertEntity` 可按 type/tag/UUID/name/position/dimension 匹配，并检查 existence、count、health、vehicle/passenger、NBT 或支持的 special state。窄匹配非常重要；只有 type 的 expectation 可能误选与行为无关的 fixture entity。

相关 helper：

| 状态 | 入口 |
| --- | --- |
| 精确/min/max/range count | `assertEntityCount*` |
| Equipment slot/item/components/NBT | `assertEntityEquipment` |
| Effect duration/amplifier/visibility/existence | `assertEntityEffect` |
| Attribute exact/min/max/existence | `assertEntityAttribute` |
| Inventory/container item count/components/NBT | `assertItem` |
| Team option/member/display | `assertTeam` |
| Bossbar value/style/visibility/player | `assertBossbar` |

预计有多个同类型实体时，同时使用资源 id 与 tag。

## Resource behavior

`assertPredicate(id, expected, player)` 在可选 player context 评估已加载 predicate；`assertLoot(table, context, player, seed, count, item)` 确定性评估 loot table；`assertAdvancementDone(player, id, expected)` 检查 advancement 完成状态，较宽的 player/Manifest surface 可检查单独 criterion。

这些 assertion 执行或检查 modeled resource behavior。资源缺失不同于 predicate false 或空 loot，应保留为 resource diagnostic。

## Output assertion

便捷 `assertOutputContains(text)` 搜索可观察 output text。结构化 `assertOutput(...)`/`OutputExpectation` 可约束：

- command root、output channel、单 target 或 target set；
- raw exact/contains/regex text；
- normalized exact/contains/regex text；
- styled text segment field；
- structured payload 与 payload path；
- match count 与 order。

```kotlin
test.assertOutput(
    command = "tellraw",
    channel = "chat",
    target = "Steve",
    contains = "reward ready",
    count = 1,
)
```

空白/排版差异无关时用 normalized text；展示属于契约时用 raw text 或 styled segment；resource id、placement result flag 等机器语义使用 payload match。

`matchingOutputs(expectation)` 只返回 match，不记录 failure，适合自定义 aggregate check。多个命令都可能发出同一句时，不要只写裸 text assertion。

## Command trace 与 snapshot diff

`assertTrace(...)`/`TraceExpectation` 可过滤 command/root、source/function、success、count、attached output 与 snapshot change。Trace 最适合回答“是哪条命令造成的”，不应代替 final-state assertion。

```kotlin
test
    .assertTrace(root = "scoreboard", success = true)
    .assertSnapshotDiff(path = "/scores/runs", kind = SnapshotDiffKind.ADDED)
```

`assertSnapshotDiff` 使用 JSON Pointer，检查 kind（`ADDED`/`REMOVED`/`CHANGED`）、可选 content 与 count。Scenario 中记录了哪些 diff 取决于 save/checkpoint boundary。`matchingTraces` 与 `snapshotDiffs()` 可只查询而不添加 failure。

## Player-event trace

`assertPlayerEventTrace(...)` 可约束 player、event type、success、advancement/criterion result 或 failure reason、item/entity/block/recipe/dimension/damage detail、keyboard/mouse device/code/action 与 count。

契约不仅关心 final state，还关心 advancement/player behavior 为什么触发或未触发时使用它。Trace assertion 与 final-state assertion 配对：前者解释判定，后者证明效果。

`matchingPlayerEventTraces` 只查询事件记录，不注册 failure。

## Coverage assertion

`coverageReport(options)` 返回累计 function invocation 与 executable-line data；`assertCoverage(options)` 把 threshold failure 记录到 QuickTest result。Filter 使用资源 id，不是文件系统 path；所有需要计入 hit 的 behavior 完成后再调用。

Coverage 表示 modeled line/function 执行过，不验证原版 parity。即使 100% coverage 也要保留 semantic assertion。

## Matrix assertion

`SandboxQuickTestMatrix` 镜像 assertion surface，把每个期望应用到所选版本 scenario。`report()` 返回 `SandboxQuickTestMatrixReport`，包含 aggregate failures 和按 version 的 report map；任一版本失败时 `requirePassed()` 抛 `SandboxQuickTestMatrixAssertionError`。

预期行为有意不同应拆分 scenario，或只断言共享契约；不要把一个 expectation 弱化到偶然通过所有 profile。

## 选择耐久 assertion

1. 优先用户可观察 output 或小 world/resource field。
2. Collection/event 匹配用 id、target/tag、channel/type、count 约束。
3. Trace assertion 用于因果，不要作为唯一结果 assertion。
4. Snapshot diff 使用选定 JSON Pointer path。
5. 只有契约确实要求完整状态时才用 whole-snapshot golden file。

## 限制

- 约束很少的宽 expectation 可能匹配无关 event/entity。
- 浮点 world/entity field 是确定性 sandbox value；外部导入噪声值应先规范化。
- 完整 snapshot 可能增加 modeled field；targeted assertion 是更稳定的兼容边界。
- Assertion overload 属于 Kotlin/Java API；binary signature 查 `api/testkit.api` 和对应 release source/build，不要依赖 internal class。

## 相关页面

- [QuickTest 总览](/guide/code-test-api)
- [QuickTest Fixture](/reference/quicktest-fixtures)
- [报告与可观测性](/reference/reports-observability)
- [测试模式](/guide/testing-patterns)

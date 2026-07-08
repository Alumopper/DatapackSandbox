# 排障手册

这页按“你看到了什么错误”组织。先定位错误类别，再决定是改测试、改数据包，还是只记录 warning。

## 快速判断

| 现象 | 最可能原因 | 先看哪里 |
| --- | --- | --- |
| 构建直接失败，提示 Java 或 classfile | JDK/toolchain 不匹配 | [开发者入门](/guide/getting-started#最小依赖) |
| `pack_format ... expected ...` | version profile 与 pack metadata 不一致 | [版本与格式](/guide/getting-started#版本与格式先按-warning-处理) |
| 命令没有效果 | 命令未实现、行为等级较低、selector 没选中 | [命令支持状态](/runtime/command-support) |
| 输出断言失败但命令确实执行了 | 匹配了 `text` 而不是 `rawText`，或目标/通道不对 | [测试模式](/guide/testing-patterns#模式-2-输出命令测试) |
| score/storage 值不对 | setup 与被测行为混在一起，或函数没有被调用 | trace + snapshot diff |
| 玩家事件没有触发 | fixture 玩家缺字段、事件上下文不匹配、advancement 条件未满足 | [玩家事件](/runtime/player-events) |
| manifest 资源加载失败 | 路径、namespace、JSON/SNBT 或 schema 不合法 | [资源格式](/resources/resource-formats) |

## 先保留 report

调试时先不要直接调用 `requirePassed()`：

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

这样你能看到失败断言、输出事件、trace 和 snapshot diff。等测试稳定后再换回 `requirePassed()`。

## pack_format 与 version profile

典型提示：

```text
Datapack format pack_format 100 or range 100..100 is not compatible with version 26.2; expected 107.1
```

处理顺序：

1. 确认测试使用的 `version = "26.2"` 是否是你要模拟的 Minecraft 版本。
2. 检查 `pack.mcmeta` 的 `pack_format` 或 `supported_formats`。
3. 如果只是临时兼容测试，把它作为 warning 记录，继续验证命令和资源行为。
4. 如果发布目标就是该版本，再升级 pack metadata。

`min_format` 和 `max_format` 可以是数组：

```json
{
  "supported_formats": {
    "min_inclusive": [104, 1],
    "max_inclusive": [107, 1]
  }
}
```

数组 `[104, 1]` 表示 `104.1`，`[94]` 表示 `94`。

## 命令支持问题

先区分三种情况：

| 情况 | 排查方式 |
| --- | --- |
| parse 失败 | 看命令语法和版本 profile |
| parse 成功但行为 no-op | 看命令支持矩阵里的行为等级 |
| 行为执行但结果不对 | 加 `assertTrace` 和 snapshot diff |

推荐临时加 trace：

```kotlin
.assertTrace(
    root = CommandRoot.EXECUTE,
    success = true,
)
```

如果 trace 有执行记录但 snapshot 没变化，说明命令路径到了，但当前 runtime 可能没有建模该副作用。

## 输出断言失败

聊天命令常见误区：

```kotlin
// 容易失败：匹配渲染后聊天行
.assertOutput(text = "Hello Minecraft world !")

// 更符合开发者预期：匹配 say 命令传入的消息
.assertOutput(rawText = "Hello Minecraft world !")
```

还要检查：

- `channel` 是否正确，例如 `OutputChannel.CHAT`、`TITLE`、`WARNING`；
- `target` 或 `targets` 是否符合 selector 结果；
- 你是否需要 `contains`、`rawContains` 或正则，而不是精确匹配。

## selector 没选中

当 `execute as @a` 或 `tell @p` 没效果时，先确认 fixture 里真的有玩家：

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

如果 selector 依赖维度、tag、距离、predicate 或 gamemode，也要把这些字段写进 fixture。不要假设默认玩家状态等同于真实服务器。

## resource 路径错误

Datapack 资源路径错误通常比命令错误更早发生。检查顺序：

1. `pack.mcmeta` 是否存在；
2. namespace 是否在 `data/<namespace>/...` 下；
3. 函数是否使用 `.mcfunction`；
4. JSON 是否符合资源类型；
5. SNBT path 是否符合沙盒支持的子集；
6. manifest 中引用的 id 是否带 namespace。

如果你在测试生成器输出，失败时保留生成目录。仅看断言错误通常不够。

## 什么时候看快照 diff

当你不知道“状态到底变了什么”时，使用 snapshot diff：

```kotlin
.assertSnapshotDiff(
    path = "/scoreboard/scores/#unit/runs",
    kind = SnapshotDiffKind.CHANGED,
)
```

diff 适合排查：

- 命令执行了但写到了别的 key；
- 初始化状态和预期不同；
- 多个函数共同修改了同一 storage；
- 版本 profile 导致资源加载路径变化。

## 最后再收紧断言

调试阶段可以用 contains、范围和存在性断言；稳定后再改成精确值。

| 调试阶段 | 稳定阶段 |
| --- | --- |
| `assertOutput(rawContains = "...")` | `assertOutput(rawText = "...")` |
| `assertScoreAtLeast(...)` | `assertScore(...)` |
| `assertEntityCountAtLeast(...)` | `assertEntityCount(...)` |
| `report()` | `requirePassed()` |

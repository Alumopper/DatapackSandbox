# QuickTest 代码测试总览

## 适用场景

当数据包行为要进入现有 Kotlin/Java 测试套件，或者你想用 fluent API 运行单函数、完整 pack 和多版本矩阵时使用 `testkit`。本页只说明依赖、入口、生命周期和报告；fixture 与断言已拆为独立参考。

## 前置条件

运行环境使用 Java 25。JVM 测试依赖 `testkit`，它会传递引入 `core`；不要依赖 CLI fat jar。

```kotlin
repositories {
    maven("https://nexus.mcfpp.top/repository/maven-releases/")
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

dependencies {
    testImplementation("moe.afox.dpsandbox:testkit:1.1.0")
}
```

## 最小可运行示例

```kotlin
import moe.afox.dpsandbox.core.SandboxQuickTest

@Test
fun generatedFunctionKeepsItsContract() {
    SandboxQuickTest.singleFunctionText(
        """
        scoreboard objectives add runs dummy
        scoreboard players set #quick runs 1
        say quicktest ready
        """.trimIndent(),
        version = "26.2",
    )
        .function()
        .assertScore("#quick", "runs", 1)
        .assertOutput(command = "say", contains = "quicktest ready", count = 1)
        .requirePassed()
}
```

## 完整能力

### API 入口

| 入口 | 用途 |
| --- | --- |
| `SandboxQuickTest.create(packs, version, ...)` | 完整数据包 |
| `singleFunction(file, version, ...)` | 单个 `.mcfunction` 文件 |
| `singleFunctionText(text, version, ...)` | 内存生成的命令文本 |
| `functions(functionSources, version, ...)` | 多个 file/text 函数与 dependency packs |
| `matrix(packsByVersion, ...)` | 多 profile 矩阵 |

默认 profile 为当前最新 `26.2`，但可复现测试应显式传入 version。完整 pack 按列表顺序加载；后加载 pack 与 synthetic functions 具有更高优先级。

### 生命周期

Fixture → behavior → assertion → report 是推荐顺序。行为方法 `load()`、`ticks(count)`、`function(id)`、`function()`、`command(text)`、`event(...)` 都立刻改变 `sandbox`。断言收集失败，不会中断链式执行。

### 报告

`report()` 返回 `SandboxQuickTestReport`，包含 passed、failures、outputs、traces、playerEventTraces、snapshotDiffs、resourceSummary 与 snapshot。`requirePassed()` 在失败时抛 `SandboxQuickTestAssertionError`，适合 JUnit/Kotlin test。`coverageReport()` 与 `assertCoverage()` 提供累计行/函数覆盖率。

如需调试实时聊天，在行为方法前调用 `printChatOutput()`；它只打印后续 chat channel，记录和断言仍然保留。

## 已迁移的原章节

### 预定义世界状态

原世界 fixture、结构、实体、玩家和 Java 存档导入目录已迁移到 [QuickTest Fixture 参考](/reference/quicktest-fixtures)。保留此标题以兼容旧深链接。

### 断言语义

原 score/storage/world/entity/output/trace/event trace/snapshot/coverage 目录已迁移到 [QuickTest 断言参考](/reference/quicktest-assertions)。

### 底层 API

需要直接持有 `DatapackSandbox` 时使用 [Core API 嵌入参考](/reference/core-api)。

## 限制

- QuickTest 与 Core 使用稀疏世界模型，不能替代所有原版服务端集成测试。
- 单个 scenario 是可变对象，不应在多个测试线程共享。
- public ABI 的最终防漂移基线仍是 `api/testkit.api` 与 `api/core.api`；本项目不生成 Dokka 站点。

## 相关页面

- [QuickTest Fixture](/reference/quicktest-fixtures)
- [QuickTest 断言](/reference/quicktest-assertions)
- [Core API](/reference/core-api)
- [测试模式](/guide/testing-patterns)

# 开发者入门

这页面向第一次把 Datapack Sandbox 接入项目的开发者。目标不是枚举所有 API，而是帮你判断该从哪个入口开始、要准备哪些文件、失败时先看哪里。

## 先选入口

| 你要做什么 | 推荐入口 | 继续阅读 |
| --- | --- | --- |
| 在 IDE 或 CI 里给数据包写单元测试 | `SandboxQuickTest` | [代码测试 API](/guide/code-test-api) |
| 检查一个完整数据包的资源、命令和断言 | `.dps.json` manifest | [资源格式](/resources/resource-formats) |
| 临时跑一个函数或命令，观察输出和 snapshot | CLI / REPL | [命令支持状态](/runtime/command-support) |
| 验证某个玩家交互是否触发 advancement、predicate 或函数链 | player event | [玩家事件](/runtime/player-events) |
| 排查 pack format、版本 profile 或命令支持差异 | version profile + warning | [版本 Profile](/resources/version-profile) |

一般建议从 `SandboxQuickTest.singleFunctionText(...)` 开始。它不要求你先创建完整数据包，适合确认测试框架、版本 profile 和断言风格都能工作。

## 最小依赖

JVM 项目应依赖 `core` artifact，而不是 CLI fat jar。

```kotlin
repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

dependencies {
    testImplementation("moe.afox.dpsandbox:core:1.0.0")
}
```

项目使用 Java 25 toolchain 构建。运行测试的 JDK 需要和 artifact 要求匹配；如果 Gradle 报 toolchain 或 classfile 版本错误，先检查本地 JDK，而不是先怀疑数据包。

## 第一个测试

```kotlin
import moe.afox.dpsandbox.core.SandboxQuickTest

class MyDatapackTest {
    @Test
    fun scoreboardCanBeAsserted() {
        SandboxQuickTest.singleFunctionText(
            """
            scoreboard objectives add runs dummy
            scoreboard players set #unit runs 1
            """.trimIndent(),
            version = "26.2",
        )
            .function()
            .assertScore("#unit", "runs", 1)
            .requirePassed()
    }
}
```

这段代码做了四件事：

1. 创建一个只包含单个 `.mcfunction` 的临时数据包。
2. 使用 `26.2` 版本 profile 解析 pack metadata 和资源格式。
3. 执行默认函数。
4. 断言 scoreboard 里的 `#unit runs` 等于 `1`。

如果你不想让断言立即抛异常，可以把 `requirePassed()` 换成 `report()`，然后在 IDE 里检查失败项、输出事件、trace 和 snapshot diff。

## 从单函数走向完整数据包

当单函数测试能跑通后，再把测试拆成三层：

| 层级 | 用途 | 典型断言 |
| --- | --- | --- |
| 单函数测试 | 验证一段命令序列的局部行为 | score、storage、output、trace |
| fixture 世界测试 | 验证玩家、方块、实体和预设世界状态 | player、block、entity、world |
| manifest 回归测试 | 验证完整 pack 的资源加载和用户可观察行为 | command status、resource loading、snapshot diff |

这三层不互相替代。单函数测试定位最快，manifest 测试覆盖最完整，fixture 测试适合介于两者之间的集成行为。

## 常用目录建议

```text
src/test/kotlin/
  mypack/
    AdvancementTest.kt
    LootTableTest.kt
    RuntimeSmokeTest.kt

src/test/resources/
  datapacks/
    mypack/
      pack.mcmeta
      data/...
  worlds/
    minimal-player.json
```

如果你只是测试生成器产物，可以把生成输出写到 `build/generated-datapacks/...`，再由 QuickTest 或 manifest 加载该目录。这样 CI 中失败时可以把生成结果作为 artifact 保存。

## 版本与格式先按 warning 处理

Datapack Sandbox 会用 version profile 判断 `pack_format`、`supported_formats`、`min_format` 和 `max_format`。不兼容时应优先作为 warning 观察，而不是把测试入口挡死，因为很多开发者只是想先验证命令和资源行为。

格式值可以是整数，也可以是数组形式：

| 写法 | 含义 |
| --- | --- |
| `94` | `94` |
| `[94]` | `94` |
| `[104, 1]` | `104.1` |

如果你看到类似 `expected 107.1` 的提示，先确认当前 `version` 是否选对，再看 `pack.mcmeta` 中的数据包格式是否真的需要升级。

## 下一步

- 想系统学习测试设计：读 [测试模式](/guide/testing-patterns)。
- 断言失败看不懂：读 [排障手册](/guide/troubleshooting)。
- 需要完整 API 参数：读 [代码测试 API](/guide/code-test-api)。
- 需要确认命令支持程度：读 [命令支持状态](/runtime/command-support)。

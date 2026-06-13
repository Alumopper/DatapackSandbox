# 代码测试 API

除了 CLI 和 `.dps.json` 清单，其他 Kotlin/Java 项目也可以直接调用 `:core` 暴露的 quick-test API。这个接口适合写本地单元测试、插件测试或构建工具里的快速冒烟测试。

## Gradle 依赖

在同一个多模块工程里：

```kotlin
dependencies {
    testImplementation(project(":core"))
}
```

如果后续发布到 Maven，则可以改成普通坐标依赖，例如：

```kotlin
dependencies {
    testImplementation("net.datapacksandbox:core:<version>")
}
```

## Kotlin 示例

```kotlin
import net.datapacksandbox.core.SandboxQuickTest
import java.nio.file.Path

class MyDatapackTest {
    @Test
    fun counterWorks() {
        SandboxQuickTest.create(listOf(Path.of("packs/counter")))
            .load()
            .ticks(20)
            .function("demo:main")
            .assertScore("#clock", "ticks", 25)
            .requirePassed()
    }
}
```

`requirePassed()` 会在断言失败时抛出 `SandboxQuickTestAssertionError`，错误里包含所有失败项和最终 snapshot。

## Java 示例

```java
import net.datapacksandbox.core.SandboxQuickTest;
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

## 常用链式方法

| 方法 | 作用 |
|---|---|
| `load()` | 运行 `#minecraft:load` |
| `ticks(n)` | 推进沙盒 tick |
| `function(id)` | 运行数据包函数 |
| `command(raw)` | 执行一条命令 |
| `player(name)` | 创建或复用玩家 |
| `event(player, type, id, action)` | 注入玩家事件 |
| `keyInput(player, key, action)` | 注入键盘输入事件 |
| `mouseInput(player, button, action, x, y)` | 注入鼠标输入事件 |
| `assertScore(target, objective, expected)` | 断言 scoreboard |
| `assertStorageEquals(id, path, expectedJson)` | 断言 storage 路径 |
| `assertPlayerXp(player, expected)` | 断言玩家 XP |
| `assertPlayerLastInput(player, device, code, action)` | 断言玩家最后一次输入 |
| `assertAdvancementDone(player, id, expected)` | 断言进度完成状态 |
| `assertOutputContains(text)` | 断言输出事件包含文本 |
| `report()` | 返回 `SandboxQuickTestReport`，不抛异常 |
| `requirePassed()` | 返回 report；失败时抛 assertion error |

## 键盘/鼠标输入测试

```kotlin
SandboxQuickTest.create(listOf(Path.of("packs/demo")))
    .keyInput("Steve", "key.jump")
    .mouseInput("Steve", "left", "click", 12.0, 8.0)
    .assertPlayerLastInput("Steve", "mouse", "left", "click")
    .requirePassed()
```

键鼠输入会记录到玩家的 `lastInput` 和 `inputEvents`，可通过 `snapshot`、`inspect player` 或 `SandboxQuickTestReport.snapshot` 检查。

## 低层 API

需要完全控制流程时，可以继续直接使用：

```kotlin
val sandbox = createSandbox("26.1.2", listOf(Path.of("packs/demo")))
sandbox.runLoad()
sandbox.executeCommand("scoreboard objectives list")
sandbox.handlePlayerEvent(PlayerEvents.keyInput("Steve", "key.jump"))
```

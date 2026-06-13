# Code Test API

In addition to the CLI and `.dps.json` manifests, Kotlin/Java projects can call
the `:core` quick-test API directly. This is useful for local unit tests, plugin
tests, and build-tool smoke tests.

## Gradle Dependency

Inside the same multi-project build:

```kotlin
dependencies {
    testImplementation(project(":core"))
}
```

If the library is published later, use normal Maven coordinates instead:

```kotlin
dependencies {
    testImplementation("moe.afox.dpsandbox:core:<version>")
}
```

## Kotlin Example

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

`requirePassed()` throws `SandboxQuickTestAssertionError` when assertions fail.
The error contains all failures and the final snapshot.

`unsupportedFeatureMode` can be `WARN` (default), `IGNORE`, or `ERROR`. Use
`ERROR` when you want unsupported vanilla commands to fail the test immediately.

## Java Example

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

## Common Methods

| Method | Purpose |
|---|---|
| `load()` | Run `#minecraft:load` |
| `ticks(n)` | Advance sandbox ticks |
| `function(id)` | Run a datapack function |
| `command(raw)` | Execute one command |
| `player(name)` | Create or reuse a player |
| `event(player, type, id, action)` | Inject a player event |
| `keyInput(player, key, action)` | Inject keyboard input |
| `mouseInput(player, button, action, x, y)` | Inject mouse input |
| `assertScore(target, objective, expected)` | Assert scoreboard state |
| `assertStorageEquals(id, path, expectedJson)` | Assert a storage path |
| `assertPlayerXp(player, expected)` | Assert player XP |
| `assertPlayerLastInput(player, device, code, action)` | Assert the latest player input |
| `assertAdvancementDone(player, id, expected)` | Assert advancement completion |
| `assertOutputContains(text)` | Assert output event text |
| `report()` | Return `SandboxQuickTestReport` without throwing |
| `requirePassed()` | Return report or throw an assertion error |

## Keyboard/Mouse Input Tests

```kotlin
SandboxQuickTest.create(listOf(Path.of("packs/demo")))
    .keyInput("Steve", "key.jump")
    .mouseInput("Steve", "left", "click", 12.0, 8.0)
    .assertPlayerLastInput("Steve", "mouse", "left", "click")
    .requirePassed()
```

Input events are recorded on the player as `lastInput` and `inputEvents`; inspect
them through snapshots, `inspect player`, or `SandboxQuickTestReport.snapshot`.

## Lower-Level API

For full control, use the runtime directly:

```kotlin
val sandbox = createSandbox(
    version = "26.1.2",
    packs = listOf(Path.of("packs/demo")),
    unsupportedFeatureMode = UnsupportedFeatureMode.ERROR,
)
sandbox.runLoad()
sandbox.executeCommand("scoreboard objectives list")
sandbox.handlePlayerEvent(PlayerEvents.keyInput("Steve", "key.jump"))
```

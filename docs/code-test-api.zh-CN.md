# 浠ｇ爜娴嬭瘯 API

闄や簡 CLI 鍜?`.dps.json` 娓呭崟锛屽叾浠?Kotlin/Java 椤圭洰涔熷彲浠ョ洿鎺ヨ皟鐢?`:core` 鏆撮湶鐨?quick-test API銆傝繖涓帴鍙ｉ€傚悎鍐欐湰鍦板崟鍏冩祴璇曘€佹彃浠舵祴璇曟垨鏋勫缓宸ュ叿閲岀殑蹇€熷啋鐑熸祴璇曘€?
## Gradle 渚濊禆

鍦ㄥ悓涓€涓妯″潡宸ョ▼閲岋細

```kotlin
dependencies {
    testImplementation(project(":core"))
}
```

濡傛灉鍚庣画鍙戝竷鍒?Maven锛屽垯鍙互鏀规垚鏅€氬潗鏍囦緷璧栵紝渚嬪锛?
```kotlin
dependencies {
    testImplementation("moe.afox.dpsandbox:core:<version>")
}
```

## Kotlin 绀轰緥

```kotlin
import moe.afox.dpsandbox.core.SandboxQuickTest
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

`requirePassed()` 浼氬湪鏂█澶辫触鏃舵姏鍑?`SandboxQuickTestAssertionError`锛岄敊璇噷鍖呭惈鎵€鏈夊け璐ラ」鍜屾渶缁?snapshot銆?
## Java 绀轰緥

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

## 甯哥敤閾惧紡鏂规硶

| 鏂规硶 | 浣滅敤 |
|---|---|
| `load()` | 杩愯 `#minecraft:load` |
| `ticks(n)` | 鎺ㄨ繘娌欑洅 tick |
| `function(id)` | 杩愯鏁版嵁鍖呭嚱鏁?|
| `command(raw)` | 鎵ц涓€鏉″懡浠?|
| `player(name)` | 鍒涘缓鎴栧鐢ㄧ帺瀹?|
| `event(player, type, id, action)` | 娉ㄥ叆鐜╁浜嬩欢 |
| `keyInput(player, key, action)` | 娉ㄥ叆閿洏杈撳叆浜嬩欢 |
| `mouseInput(player, button, action, x, y)` | 娉ㄥ叆榧犳爣杈撳叆浜嬩欢 |
| `assertScore(target, objective, expected)` | 鏂█ scoreboard |
| `assertStorageEquals(id, path, expectedJson)` | 鏂█ storage 璺緞 |
| `assertPlayerXp(player, expected)` | 鏂█鐜╁ XP |
| `assertPlayerLastInput(player, device, code, action)` | 鏂█鐜╁鏈€鍚庝竴娆¤緭鍏?|
| `assertAdvancementDone(player, id, expected)` | 鏂█杩涘害瀹屾垚鐘舵€?|
| `assertOutputContains(text)` | 鏂█杈撳嚭浜嬩欢鍖呭惈鏂囨湰 |
| `report()` | 杩斿洖 `SandboxQuickTestReport`锛屼笉鎶涘紓甯?|
| `requirePassed()` | 杩斿洖 report锛涘け璐ユ椂鎶?assertion error |

## 閿洏/榧犳爣杈撳叆娴嬭瘯

```kotlin
SandboxQuickTest.create(listOf(Path.of("packs/demo")))
    .keyInput("Steve", "key.jump")
    .mouseInput("Steve", "left", "click", 12.0, 8.0)
    .assertPlayerLastInput("Steve", "mouse", "left", "click")
    .requirePassed()
```

閿紶杈撳叆浼氳褰曞埌鐜╁鐨?`lastInput` 鍜?`inputEvents`锛屽彲閫氳繃 `snapshot`銆乣inspect player` 鎴?`SandboxQuickTestReport.snapshot` 妫€鏌ャ€?
## 浣庡眰 API

闇€瑕佸畬鍏ㄦ帶鍒舵祦绋嬫椂锛屽彲浠ョ户缁洿鎺ヤ娇鐢細

```kotlin
val sandbox = createSandbox("26.1.2", listOf(Path.of("packs/demo")))
sandbox.runLoad()
sandbox.executeCommand("scoreboard objectives list")
sandbox.handlePlayerEvent(PlayerEvents.keyInput("Steve", "key.jump"))
```

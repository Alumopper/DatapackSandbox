# Datapack Sandbox

一个轻量级、洁净室实现的 Minecraft Java 数据包沙盒，重点面向本地 CLI 调试。首个内置版本配置目标为 Minecraft Java `26.1.2`。

## 构建

```bash
./gradlew :cli:fatJar
```

Windows：

```powershell
.\gradlew.bat :cli:fatJar
```

构建会下载 `SpyglassMC/vanilla-mcdoc`，并安装官方 `@spyglassmc/mcdoc` 解析器来生成运行时 NBT schema。`node_modules/` 只作为本地构建工具使用，不会被打包进 CLI jar。

standalone jar 会输出到：

```text
cli/build/libs/datapack-sandbox-cli.jar
```

## CLI 示例

启动 REPL：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar repl --version 26.1.2 --pack ./my_pack
```

REPL 支持 TAB 自动补全、输入过程中的尾部提示、历史输入提示、彩色输出，以及运行时重载数据包文件：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar repl --pack ./my_pack --watch
dps> reload
dps> function demo:main
dps> inspect outputs
```

运行一次快速冒烟测试：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --load --ticks 20 --function demo:main --snapshot
```

运行 JSON 检查清单：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases
```

清单文件使用 `.dps.json` 后缀：

```json
{
  "version": "26.1.2",
  "unsupported": "warn",
  "packs": ["./packs/counter"],
  "steps": [
    { "load": true },
    { "ticks": 20 },
    { "function": "demo:main" }
  ],
  "assertions": [
    {
      "score": {
        "target": "#clock",
        "objective": "ticks",
        "equals": 20
      }
    }
  ]
}
```

## v1 范围

这不是一个嵌入式原版服务端。它实现的是一个小型、确定性的运行时，用于模拟数据包逻辑：函数、load/tick 标签、scoreboard 状态、storage、最小实体、默认可读玩家、包含 `@n` 的简单选择器、根据 generated vanilla mcdoc schema 校验的实体/方块/物品 NBT、谓词、战利品表、进度、玩家事件、可观察输出命令，以及可配置的未支持原版命令处理策略。默认策略是 `warn`：记录 warning 输出并继续运行；需要严格失败时可使用 `--unsupported error`，需要静默跳过时可使用 `--unsupported ignore`。

`tellraw`、`title`、`say`、`msg`、`playsound`、`stopsound`、`particle` 等偏输出的命令会被记录为沙盒输出事件。它们不会模拟真实客户端 UI，但可以在 REPL、`run`、`check --verbose` 和 snapshot 中查看。

## 完整链路示例

运行所有示例清单：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check examples
```

生成一个战利品表结果：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar loot --pack examples/full-stack/pack --table demo:gift --context minecraft:advancement_reward --seed 42
```

触发一个玩家事件：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar event --pack examples/full-stack/pack player Steve item-used minecraft:carrot_on_a_stick
```

支持边界见：

- `docs/command-support.zh-CN.md`
- `docs/code-test-api.zh-CN.md`
- `docs/resource-formats.zh-CN.md`
- `docs/player-events.zh-CN.md`
- `docs/version-profile.zh-CN.md`
- `docs/runtime-world.zh-CN.md`

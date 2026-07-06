# Datapack Sandbox

一个轻量、洁净室实现的 Minecraft Java 数据包沙盒，重点面向本地 CLI 调试、JSON 清单回归测试和 JVM 项目的代码级快速测试。

内置版本 profile 覆盖 Minecraft Java `1.20.4` 到 `26.2`，默认使用最新 profile `26.2`。项目不嵌入原版服务端运行时，也不分发 Mojang 服务端代码；构建时会使用公开的 `SpyglassMC/vanilla-mcdoc` 资料和官方 `@spyglassmc/mcdoc` 解析器生成 NBT schema，用于运行时校验。

## 构建

```bash
./gradlew :cli:fatJar
```

Windows：

```powershell
.\gradlew.bat :cli:fatJar
```

运行 standalone jar 的发布 smoke 检查：

```powershell
.\gradlew.bat :cli:smokeCliJar
```

smoke 会构建 jar、导出内置 manifest schema、运行全部 examples manifest，并实际执行 README 里的 `loot` 和玩家 `event` 示例。标准 `check` 生命周期也会运行单元测试、manifest 示例和 standalone jar smoke；CI 会在 Linux、Windows 和 macOS 上运行 `check`。

standalone jar 输出到：

```text
cli/build/libs/datapack-sandbox-cli.jar
```

## CLI 示例

启动 REPL：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar repl --version 26.2 --pack ./my_pack
```

查看内置版本 profile：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar version
java -jar cli/build/libs/datapack-sandbox-cli.jar version 1.20.4 26.2
```

REPL 支持 TAB 补全、输入过程中的多行候选提示、历史输入提示、彩色输出、Ctrl+C 退出、运行时重载数据包文件、trace/diff helper、rerun-last、reset-world 和 fixture 加载：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar repl --pack ./my_pack --watch
dps> reload
dps> function demo:main
dps> trace on
dps> diff last
dps> inspect raw damage_type
dps> inspect resources damage_type
dps> inspect outputs
```

运行一次快速冒烟测试：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --load --ticks 20 --function demo:main --snapshot
```

需要调试函数调用链或命令生成器产物时，打开结构化 trace：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --function demo:main --trace --trace-file trace.jsonl
```

将可观察命令输出写成 JSONL，便于作为 CI artifact 保存：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --function demo:main --outputs-file outputs.jsonl
```

写出综合 JSON 报告，包含断言失败、输出、trace、事件 trace、最终 snapshot、snapshot diff 和资源摘要：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --function demo:main --report-file run-report.json
```

需要查看运行前后状态变化时，输出 snapshot diff：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --function demo:main --snapshot-diff
```

不创建完整数据包，直接运行单个 `.mcfunction` 文件：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 --mcfunction ./scratch/test.mcfunction --snapshot
```

直接运行内联 `.mcfunction` 字符串：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 --mcfunction-text "say hello from inline"
```

从标准输入读取 `.mcfunction` 文本：

```bash
printf 'scoreboard objectives add runs dummy\nscoreboard players set #stdin runs 1\n' \
  | java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 --stdin --snapshot
```

一次加载多个轻量函数。函数之间需要互相调用时，使用 `id=path` 或 `id=text` 指定函数 id：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 ^
  --mcfunction-id demo:main ^
  --mcfunction demo:main=./scratch/main.mcfunction ^
  --mcfunction demo:helper=./scratch/helper.mcfunction ^
  --mcfunction-text "demo:inline=scoreboard players add #clock ticks 1"
```

也可以给轻量函数附加一个或多个文件夹/zip 数据包作为依赖：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 ^
  --pack ./deps/library_pack ^
  --pack ./deps/items.zip ^
  --mcfunction-id demo:main ^
  --mcfunction-text "demo:main=function library:setup"
```

快速验包时，`run` 也可以直接使用和 manifest 检查相同的缺失资源引用校验；`--resources` 会打印资源摘要，不需要先写完整 manifest：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./my_pack --resources --fail-on-missing-resources
```

运行 JSON 检查清单：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --validate-schema
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --fail-on-missing-resources
```

manifest 可以用 `{"snapshot":{"equalsFile":"expected.json"}}` 将最终 snapshot
或某个 snapshot path 与仓库里的 golden JSON 文件比较。

导出随 CLI 打包的 manifest JSON Schema，供编辑器或 CI 工具使用：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --output dps-manifest.schema.json
```

断言失败时可以同时输出最终 snapshot 或最小 snapshot diff：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --snapshot-on-fail --snapshot-diff-on-fail
```

`check` 也可以把 trace 和输出事件写成 CI artifact：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --trace-filter root=scoreboard --trace-file check-trace.jsonl --outputs-file check-outputs.jsonl --report-file check-report.json
```

`--trace-filter` 也可以按命令造成的状态变化过滤，例如 `score=#clock`、`storage=demo:env` 或 `path=/scores/runs`；trace JSONL 条目会带上每条命令的 `snapshotDiffs`。

不想写完整 manifest 时，`run` 可以直接加载 manifest-style world fixture、覆盖 seed，并接受一个或多个内联 JSON assertion。该 seed 会被 `seed` 命令和默认 `random` sequence 使用：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --world ./fixture-world.json \
  --seed 42 \
  --assert '{"world":{"seed":42}}' \
  --assert '{"score":{"target":"#fixture","objective":"ready","equals":1}}' \
  --assert-file ./assertions.json
```

world fixture 可以用 `fixture`、`fixtures` 或 `extends` 引用可复用 fixture 文件；
被引用文件会先应用，再由当前文件里的字段覆盖公共 setup。

清单中的 `steps` 也可以直接包含 `commands`、`functionText` 或相对路径 `mcfunction`，用于验证命令生成器输出；`assertions` 可检查 `world`、`team`、`bossbar`、`trace`、空白规范化后的 `output` 和玩家背包 `item`。

清单文件固定使用 `.dps.json` 后缀：

```json
{
  "version": "26.2",
  "unsupported": "warn",
  "packs": ["./packs/counter"],
  "world": {
    "difficulty": "normal",
    "blocks": [
      { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
    ],
    "entities": [
      { "type": "minecraft:pig", "pos": [1, 64, 0], "tags": ["fixture"] }
    ],
    "players": [
      { "name": "Alex", "position": [2, 65, 3], "xp": 5 }
    ],
    "teams": [
      { "name": "red", "members": ["Alex"], "options": { "color": "red" } }
    ],
    "bossbars": [
      { "id": "demo:bar", "name": "Demo", "value": 3, "max": 10 }
    ],
    "scores": [
      { "target": "#fixture", "objective": "ready", "value": 1 }
    ],
    "storage": {
      "demo:env": { "ready": true }
    }
  },
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
    },
    {
      "world": {
        "difficulty": "normal"
      }
    },
    {
      "output": {
        "command": "tellraw",
        "channel": "chat",
        "target": "Steve",
        "contains": "reward",
        "segment": {
          "color": "yellow"
        },
        "count": 1
      }
    }
  ]
}
```

同一个清单也可以跨多个版本 profile 运行。不同版本需要不同 `pack_format` 或资源目录布局时，使用按版本分组的 `packs` 对象：

```json
{
  "versions": ["1.20.4", "26.1.2", "26.2"],
  "packs": {
    "1.20.4": ["./packs/demo-1_20_4"],
    "26.1.2": ["./packs/demo-26_1_2"],
    "26.2": ["./packs/demo-26_2"]
  },
  "steps": [
    { "load": true }
  ],
  "assertions": [
    {
      "score": {
        "target": "#clock",
        "objective": "ticks",
        "equals": 0
      }
    }
  ]
}
```

## 当前范围

这不是一个嵌入式原版服务端。它实现的是确定性的、面向数据包可见状态的运行时：

- 函数、`#minecraft:load`、`#minecraft:tick` 和 `schedule function`。
- scoreboard、storage、gamerule、time、weather、bossbar、worldborder、强加载 chunk、biome 覆盖、世界/玩家出生点等内存状态。
- 稀疏虚空世界、显式方块/实体/玩家 fixture，以及选定 Java Anvil 存档区块导入。
- 默认可读玩家 NBT，非玩家实体和方块实体 NBT 的 schema 校验与读写。
- selector，包括 `@s`、`@a`、`@p`、`@e`、`@n` 以及常用过滤项。
- predicate、loot table、advancement、玩家事件、键盘/鼠标输入事件。
- raw JSON 注册表资源，包括 recipe、item modifier、damage type、dimension、worldgen JSON、enchantment、trim 与 variant 资源。
- `tellraw`、`title`、`say`、`msg`、`playsound`、`stopsound`、`particle` 等可观测输出命令。
- 未支持原版命令的可配置策略：默认 `warn` 记录警告并继续，`error` 严格失败，`ignore` 静默跳过。

沙盒不会模拟网络、客户端 UI、权限系统、区块生成、红石、完整实体 AI、真实战斗规则、物理和服务端线程模型。实体默认不会执行 AI tick，但不会自动写入 `NoAI:1b`，除非测试数据显式写入。

## 完整链路示例

运行所有示例清单：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check examples
```

`examples/` 目录覆盖完整数据包事件、单函数随手测试、命令生成器输出、golden snapshot 断言和多版本 manifest。

直接生成一个 loot table 结果：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar loot --pack examples/full-stack/pack --table demo:gift --context minecraft:advancement_reward --seed 42
```

触发玩家事件：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar event --pack examples/full-stack/pack player Steve item-used minecraft:carrot_on_a_stick
```

## 文档

- [命令支持状态](docs/command-support.zh-CN.md)
- [代码测试 API](docs/code-test-api.zh-CN.md)
- [资源格式与清单格式](docs/resource-formats.zh-CN.md)
- [玩家事件](docs/player-events.zh-CN.md)
- [版本 profile](docs/version-profile.zh-CN.md)
- [运行时世界模型](docs/runtime-world.zh-CN.md)

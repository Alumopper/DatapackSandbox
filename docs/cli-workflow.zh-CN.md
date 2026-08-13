# 使用 CLI 运行数据包

## 适用场景

需要一次性完成一个聚焦实验时使用 `run`：执行函数、生成命令流、玩家事件或单个 `.mcfunction`，然后只导出所需证据。场景已经是可复用 `.dps.json` 回归时使用 `check`；希望在反复尝试命令时保留世界，则使用 REPL。

## 前置条件

先按 [安装与获取](/workflows/installation) 准备 `datapack-sandbox-cli.jar`。以下示例从仓库根目录运行，并显式使用 `26.2` profile，保证可复现。

测试自定义 pack 前先确认当前资源布局；新 profile 使用 `data/demo/function`、`loot_table`、`advancement` 等单数目录。

## 最小可运行示例

### 运行一个文件

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --mcfunction examples/coverage/pack/data/demo/function/main.mcfunction `
  --snapshot-diff
```

只给 `--mcfunction` 路径时，合成入口 id 默认为 `sandbox:main`，并在 lightweight-function 阶段执行。

### 运行 pack 函数

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --pack examples/full-stack/pack `
  --function demo:reward `
  --trace `
  --outputs-file build/reward-outputs.jsonl `
  --report-file build/reward-report.json
```

CLI 加载 pack，在同一稀疏世界中执行 `demo:reward`，并用 outputs/report 记录该操作。

## 理解执行顺序

`run` 按固定生命周期组合输入：

1. 选择 version profile，用 `--pack` 和 lightweight functions 创建沙盒。
2. 应用 `--world`，再用可选 `--seed` 覆盖种子。
3. `--mcfunction`、`--mcfunction-text` 或 `--stdin` 提供合成入口时执行它。
4. 执行 `--load`，再推进 `--ticks`。
5. 按各自选项族内的顺序执行 `--function`、`--command`/`--command-file`、`--event`/`--event-file`。
6. 评估 `--assert` 与 `--assert-file`。
7. 打印或写出资源、snapshot、diff、trace、event、coverage、截图和组合 report。

若不同类型动作之间的交错顺序属于测试契约，应写成 Manifest，用 `steps` 数组直接表达。

## 选择输入形式

| 输入 | 适合场景 | 关键点 |
| --- | --- | --- |
| `--pack <dir-or-zip>` | 完整 pack 和依赖 | 可重复；后面的 pack 资源优先级更高 |
| `--mcfunction <path>` | 单个临时/生成文件 | 被其他函数调用时使用 `id=path` |
| `--mcfunction-text <text>` | 小段生成内容 | 多函数或可调用函数使用 `id=text` |
| `--mcfunction-id <id>` | 改默认入口 id | 作用于未显式 id 的 lightweight 入口 |
| `--stdin --stdin-mode mcfunction` | 管道输入生成函数 | 避免把命令放进 shell 参数 |
| `--stdin --stdin-mode commands` | 管道输入直接命令 | 每条命令独立执行，不合成一个函数 |
| `--command` / `--command-file` | setup 或临时命令 | 文件忽略空行/注释行 |
| `--function <id>` | 调用已加载函数 | 可重复 |
| `--event` / `--event-file` | 玩家行为事件 | 文件每行一个文本事件 |
| `--world <json>` | Manifest 风格 fixture | 内部路径按 fixture/管理输入的位置解析 |

多个 lightweight function 必须给每个可调用 source 指定 id：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --mcfunction demo:main=./scratch/main.mcfunction `
  --mcfunction demo:helper=./scratch/helper.mcfunction `
  --mcfunction-text "demo:inline=scoreboard players add #clock ticks 1"
```

## 添加 fixture 与断言

`--world` 接受 Manifest 使用的同类 world 对象。`--seed` 覆盖其中种子，也影响 `seed` 命令和默认 random sequence。

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --world .\fixture-world.json `
  --seed 42 `
  --command "function demo:main" `
  --assert '{"world":{"seed":42}}' `
  --assert '{"score":{"target":"#case","objective":"runs","equals":1}}' `
  --assert "output:ready"
```

`--assert` 可接 JSON assertion object，也可接紧凑 shorthand。`--assert-file` 可放 JSON assertions，或每个非空非注释行一条 shorthand。需要精确约束 output segment、event trace、world field 或 diagnostic 时优先 JSON；本地 smoke 可用 shorthand。

直接命令预期失败时加 `--allow-command-failure`，让执行继续并由 diagnostic/trace assertion 检查错误。它只作用于直接命令、command file 和 commands 模式 stdin，不会把任意函数失败变成成功。

## 选择证据

| 目标 | 控制台/文件选项 | 格式 |
| --- | --- | --- |
| 完整最终状态 | `--snapshot`, `--snapshot-file` | JSON |
| 相对起点的状态变化 | `--snapshot-diff`, `--snapshot-diff-file` | JSON / 文本渲染 |
| 命令调用与诊断路径 | `--trace`, `--trace-file`, `--trace-filter` | JSONL 文件 |
| 玩家事件判定 | `--event-trace-file` | JSONL |
| chat/title/sound/结构化输出 | `--outputs-file` | JSONL |
| 资源计数与缺失引用 | `--resources`, 组合 report | 控制台 / JSON |
| 执行函数和行 | `--coverage`, `--coverage-file`, threshold/filter | JSON |
| CI 所需的完整上下文 | `--report-file` | JSON |
| 当前模型世界图像 | `--screenshot-file` 与 render 选项 | PNG |

消费者要流式处理事件时写独立 JSONL；希望一个自包含 artifact 时用 `--report-file`。即使控制台/trace 导出被过滤，report 仍保留运行时完整数据。

## 严格度与安全限制

`--unsupported warn|ignore|error` 控制已识别但未建模的行为。`--strict` 会选择 error，并使直接缺失资源引用失败；只需要后者时使用 `--fail-on-missing-resources`。

不可信或生成输入应限制：

- `--max-commands`
- `--max-function-depth`
- `--max-ticks-per-run`
- `--max-output-events`
- `--max-snapshot-bytes`

命令预算按 `SandboxLimits` 在每个顶层操作重置；limit 失败仍会进入 trace，并在支持的集成中返回 partial result。

## 渲染截图

渲染不会自动发现或下载 Minecraft 资源。使用 `--minecraft-assets` 显式传入匹配的 client JAR 或包含 `assets/` 的目录；resource pack 与玩家皮肤也是额外显式输入。

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --pack .\my-pack `
  --function demo:build `
  --screenshot-file build/world.png `
  --minecraft-assets D:\Minecraft\versions\26.2\26.2.jar `
  --screenshot-width 1280 `
  --screenshot-height 720 `
  --require-render-assets
```

省略 `--minecraft-assets` 时使用确定性 procedural fallback；`--require-render-assets` 则让缺失/无效资源失败。相机可选玩家、实体 UUID 或固定坐标，详见 [渲染与实时视窗](/guide/rendering-notebook)。

## 读取结果

退出码 `0` 表示操作和断言通过；`1` 是断言或 coverage threshold 失败，`2` 是输入格式错误，`3` 覆盖 unsupported/version/resource/command/interruption/context 诊断。CI 应同时使用退出码与机器文件：前者 gate job，report 解释原因。

## 限制

- 世界是稀疏、确定性的；`run` 不生成地形，也不模拟网络、权限、实体 AI、红石或原版服务端线程。
- 多个 action family 不能组成任意交错序列；跨类型顺序重要时使用 Manifest `steps`。
- 控制台排版面向人，不要把颜色、空格或本地化文本当 API 解析。
- 对不可信输入放宽安全限制可能造成过高 CPU/内存占用。

## 相关页面

- [CLI 参考](/reference/cli)
- [REPL 调试](/workflows/repl)
- [Manifest 回归测试](/workflows/manifest-tests)
- [报告与可观测性](/reference/reports-observability)

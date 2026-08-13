# CLI 参考

## 适用场景

选好工作流后，用本页查询命令职责、通用策略、退出码和机器输出边界。机器上安装的可执行文件仍是精确选项拼写的权威：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar --help
java -jar cli/build/libs/datapack-sandbox-cli.jar run --help
```

## 命令地图

| 子命令 | 主要输入 | 主要结果 | 用途 |
| --- | --- | --- | --- |
| `run` | Pack、函数、命令、事件、fixture | 状态/事件/report/图像 | 一次性执行与断言 |
| `check` | Manifest 文件/目录 | 按 Manifest/version 的结果 | 可重复回归套件 |
| `repl` | Pack | 保留世界的交互会话 | 人工调试 |
| `viewport` | Pack + 可选启动动作 | GLFW/OpenGL 窗口 | JVM 实时可视检查 |
| `serve` | JSONL stdin | JSONL stdout | 编辑器、kernel、长生命周期进程客户端 |
| `diff` | Snapshot/report 或 Manifest | 文本/JSON diff 或脚本 | Golden 比较与外部 replay |
| `loot` | Pack、table、context | 生成 item | 聚焦 loot-table 检查 |
| `advancement` | Pack、player、advancement action | Progress/state | 聚焦 advancement 检查 |
| `event` | Pack + 文本玩家事件 | 输出/状态 | 单玩家事件 smoke |
| `benchmark` | 内置/自定义场景选项 | Timing/metrics | 规模与性能 smoke |
| `schema` | 内置 Manifest schema | JSON Schema 文件/check | 编辑器与 CI 校验 |
| `version` | 零个/两个 profile id | 目录或 profile diff | 版本规划 |
| `commands` | Profile 与输出模式 | Command behavior 目录 | 支持查询/文档检查 |
| `resources` | Profile 或 pack/filter | Resource/registry 目录 | 资源支持与覆盖检查 |

## 执行命令

### `run`

`run` 是覆盖面最广的一次性入口：目录/zip pack、文件/文本/stdin 函数、直接命令流、world fixture、load/ticks/functions/events、assertion、资源检查、安全限制、coverage、report 和截图。输入 action family 按固定生命周期执行；任意交错使用 Manifest `steps`。详见 [CLI 运行工作流](/workflows/cli)。

### `check`

`check <input>...` 对目录递归发现 `.dps.json`，也可直接运行文件。关键控制有 `--fail-fast`、`--validate-schema`、`--strict`、失败 snapshot/diff、trace/output/report/coverage 文件、seed override、unsupported policy 与 safety limits。多版本 Manifest 每个 profile 产生一个 attempt。

### `repl`

`repl --pack <path>...` 打开带补全、history、reload/watch、trace/diff/rerun、fixture 与结构化检查的 JLine 会话。Pack reload 保留世界，`reset world` 不保留。详见 [REPL 调试](/workflows/repl)。

### `viewport`

`viewport` 打开原生 JVM 窗口，支持 version/packs、显式 `--minecraft-assets`、resource packs、启动 command/function、窗口尺寸、目标 FPS/tick rate、autoplay、input player、FOV、移动/鼠标/UI scale 与 PNG export directory。客户端资源绝不会被自动发现。

### `serve`

`serve --protocol jsonl` 持有一个 sandbox，通过 UTF-8 每行一个 JSON 对象通信。`--ready-file` 可让宿主获知进程就绪。不要把人类日志混入协议 stdout。详见 [Serve JSONL](/reference/serve-jsonl)。

## 聚焦与检查命令

### `diff`

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar diff expected.json actual.json
java -jar cli/build/libs/datapack-sandbox-cli.jar diff --snapshot --check vanilla-report.json sandbox-report.json
java -jar cli/build/libs/datapack-sandbox-cli.jar diff --script -o replay.mcfunction case.dps.json
```

`--snapshot` 从 report 提取 snapshot，`--state` 比较 state-oriented 内容，`--json` 写结构化 diff，`--check` 在有差异时失败，`--script` 把可外部 replay 的 Manifest step 转成 `.mcfunction`，并将 sandbox-only 操作保留为注释。

### `loot`、`advancement`、`event`

这些是窄人工测试的便捷入口：接受 version/packs 与领域参数，然后创建短生命周期 sandbox。结果需要多个 fixture/assertion 时改用 Manifest/QuickTest。

### `benchmark`

Benchmark 对内置或 pack-backed 场景做规模 smoke，可输出 JSON metrics；它不是带 JVM fork/warmup 隔离的 microbenchmark harness。

### `schema`

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --output build/dps-manifest.schema.json
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --check schema/manifest/dps-manifest.schema.json
```

Export 使用 jar 内置 schema；check mode 与文件比较，本仓库用它检测漂移。

### `version`、`commands`、`resources`

三者都有面向人和机器的 catalog 模式：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar version --json
java -jar cli/build/libs/datapack-sandbox-cli.jar version 1.20.4 26.2
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --json --version 26.2
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --pack .\my-pack --json
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --pack .\my-pack --id demo:main --active-only
```

它们的 `--docs`/`--check` 模式维护本仓库命令、资源、版本参考页的生成 section，只检查 catalog，不执行数据包场景。

## 通用运行策略

### Profile

省略 `--version` 会选当前默认 `26.2`；可复现脚本应显式传入。Profile 控制命令/资源/版本行为，不改变 CLI binary 版本。

### Unsupported 行为

`--unsupported` 接受 warn、ignore、error 及支持的 alias。Warn 记录诊断并继续，ignore 静默跳过已识别未建模行为，error 失败。`run/check --strict` 选择 error，并把直接缺失资源引用也当失败。

### Limits

`run`/`check` 暴露 `--max-commands`、`--max-function-depth`、`--max-ticks-per-run`、`--max-output-events`、`--max-snapshot-bytes`，对应 `SandboxLimits`。对生成内容或不可信输入应保守设置。

## 退出码

| 码 | 常量 | 含义 |
| --- | --- | --- |
| `0` | `OK` | 成功 |
| `1` | `ASSERTION_FAILED` | Assertion 或 threshold 失败 |
| `2` | `INPUT_FORMAT` | Option、JSON、schema input 或 request shape 无效 |
| `3` | `UNSUPPORTED_OR_VERSION` | Unsupported feature、version mismatch、missing resource/context、command error 或 interruption |

个别子命令可有 `diff --check` 等失败语义，但进程结果会映射到这组稳定码。脚本必须传播退出码。

## 机器输出契约

- JSON 文件包含一个完整 value；JSONL 每个 UTF-8 行一个对象。
- `serve` 将 stdout 专用于 JSONL envelope。
- `run/check` 控制台颜色、表格与措辞面向人，可继续演进。
- Report object 可以增加字段；消费者应只读取已知字段、忽略未知字段。
- Trace filter 只改变展示/导出，不改变 sandbox 内记录和组合 report。

## 渲染资源

所有 JVM render surface（`run --screenshot-file`、`viewport`、Serve `render`）都要求调用者显式传入匹配的 Minecraft client JAR 或 `assets/` 目录，才能得到原版模型/纹理。命令不会从 `--version` 推导 `.minecraft` 路径或下载资源。没有资源时 headless render 使用 fallback；live viewport 只有提供 `--minecraft-assets` 才能看到有意义的客户端画面。

## 限制

- Fat jar 是应用边界，不是稳定 JVM library API；嵌入使用已发布的 `core`、`testkit`、`renderer`。
- 新能力会增加选项；生成 wrapper 应查询其固定 release 的 `--help`。
- CLI 通过验证的是 clean-room 建模运行时，不是原版服务端/客户端所有子系统。

## 相关页面

- [CLI 运行工作流](/workflows/cli)
- [REPL 调试](/workflows/repl)
- [Manifest 参考](/reference/manifest)
- [Serve JSONL](/reference/serve-jsonl)
- [报告与可观测性](/reference/reports-observability)

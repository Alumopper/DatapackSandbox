# 报告与可观测性参考

## 适用场景

需要解释失败、归档 CI 证据、比较多个 profile 的行为，或把 sandbox state 交给其他工具时，使用 snapshot、diff、output、command trace、player-event trace、diagnostic、coverage 或组合 report。只选择能回答问题的最小载体；full trace/report 比 snapshot diff 更丰富，也刻意更昂贵。

## 前置条件

所有机器文件使用 UTF-8。JSON 文件只有一个 root value；JSONL 每行是一个可独立解析的对象。不要抓取彩色 console output 作为 API，也不要在归档时折行 JSONL。

## 最小完整采集

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --pack .\my-pack `
  --function demo:main `
  --snapshot-file build/snapshot.json `
  --snapshot-diff-file build/diff.json `
  --trace-file build/trace.jsonl `
  --event-trace-file build/events.jsonl `
  --outputs-file build/outputs.jsonl `
  --coverage-file build/coverage.json `
  --report-file build/report.json
```

第一次排查先看 `diff.json` 与 `trace.jsonl` 中的失败项，把 `report.json` 作为自包含 CI artifact。只有还需要人类可读 console trace 时再加 `--trace`。

## 选择产物

| 载体 | 格式 | 内容 | 最适合 |
| --- | --- | --- | --- |
| Snapshot | JSON object | 完整 modeled world + version | Golden state、离线工具 |
| Snapshot diff | JSON array | JSON Pointer 路径上的 added/removed/changed | 隔离单次运行效果 |
| Output stream | JSONL | Chat/title/actionbar/data/warning event 与结构化 payload | 用户可见/语义输出断言 |
| Command trace | JSONL | 每条命令的 source、executor、result、output、diff、error | 函数链与根因分析 |
| Player-event trace | JSONL | 规范化玩家事件、advancement update 与 criterion failure | Trigger/advancement 调试 |
| Coverage | JSON | Function invocation、可执行行 hit、total/percentage/failure | CI gate 与未测路径 |
| Run report | JSON object | 一次 `run` 的主要证据 | 单个可下载 artifact |
| Check report | JSON array | 每个 manifest 与每版本 attempt | 回归 matrix 证据 |

独立文件更容易 streaming/index；report 有意重复内容，保证原进程和 console log 消失后仍可单独使用。

## Snapshot

`--snapshot` 打印稳定 JSON，`--snapshot-file` 写入文件。Snapshot 包含 modeled time/weather/world settings、score/objective、storage、player/entity/block、schedule、适用的 output/trace 与版本。它是 sandbox state contract，不是原版 level-save。

只有完整 modeled state 本身就是 contract 时才使用 baseline；否则优先 targeted assertion 或 diff，因为新增合法 modeled 字段会让宽泛 golden 产生噪音。

Core 等价入口：

```kotlin
val json = sandbox.snapshotJson()
val stableText = sandbox.snapshotString()
```

Snapshot 受 `SandboxLimits.maxSnapshotBytes` 约束（默认 10,000,000 字节），按稳定 UTF-8 JSON 文本计算。

## Snapshot diff

`--snapshot-diff` 打印从运行生命周期之前到最终状态的变化，`--snapshot-diff-file` 写数组。每项形如：

```json
{
  "path": "/scores/#runner/runs",
  "kind": "changed",
  "before": 0,
  "after": 1
}
```

路径使用 JSON Pointer，因此 key 内的 `~`、`/` 按该语法转义。Kind 为 `added`、`removed`、`changed`，缺少的 `before`/`after` 由 kind 决定。

Core 中 `SnapshotDiff.diff(before, after)` 包含全部序列化变化；`SnapshotDiff.stateDiff(before, after)` 排除 trace/output bookkeeping，避免命令自身的可观测记录被误当成要解释的业务行为。CLI run report 与 Serve tracked result 使用 state-oriented 形式。

## Output event

`--outputs-file` 每行写一个 `OutputEvent`。关键字段：

| 字段 | 含义 |
| --- | --- |
| `tick` | 发出时 modeled game time |
| `command` | 产生 output 的 operation/root label |
| `channel` | Chat/actionbar/title/data/warning 等语义 channel |
| `targets` | 已解析接收者/受影响目标 |
| `text` | 适合普通断言的规范化纯文本 |
| `rawText` | 保留时的原始文本表示 |
| `segments` | 带 text/color/emphasis 的样式片段 |
| `payload` | Command-specific 结构化 JSON |
| `source` | 可用时的 function/file/line/command 来源 |

自动化优先使用 `payload`，它无需解析 prose 就保留 id、count、position、before/after。`text` 用于用户读到的内容；只有原始 component syntax 本身就是 contract 时才用 `rawText`，两者不可互换。

JVM 中 `world.outputs` 保留 event；`addOutputListener` 可流式接收新增 event。Owner dispose 时移除 listener。

## Command trace

请求 `--trace-file`、report 或依赖 trace 的输出时会启用 tracing。每个 `CommandTraceEvent` 记录：

- `tick`、规范化 `command` 与 root；
- source file/line/current command 与 function stack；
- executor 和 execution position；
- `success` 与嵌套 `commandsExecuted`；
- output count 与关联 output event；
- full trace 模式下的 state diff；
- 失败时的 `errorCode`、`errorMessage`。

一条外层命令可执行嵌套函数，因此 trace 行数不等于 command budget 使用量；需要同时跟随 source/function stack 和 `commandsExecuted`。

组合 report 的 diagnostics 是失败 trace 的投影，包含已知 version、code、message、command/root、file/line、success、commands executed。它适合 editor/CI 摘要，周边 output/diff 仍需回到 full trace。

## Trace filter

可多次传 `--trace-filter`，所有 filter 必须同时匹配。没有 `=` 的 filter 会在 command、source/function stack、diagnostic、output、diff 中做宽泛 substring/exact-root 搜索。

| Key | 匹配规则 | 示例 |
| --- | --- | --- |
| `root` | 精确 command root | `root=execute` |
| `command` | 精确 command | `command=say ready` |
| `contains` | Command/error message substring | `contains=demo:` |
| `function` | Function-stack id substring | `function=demo:tick` |
| `file`, `source` | Source file substring | `file=data/demo/function` |
| `selector`, `target` | Command、executor、output target/payload | `target=Alex` |
| `success` | 严格 `true`/`false` | `success=false` |
| `error`, `diagnostic` | Boolean presence 或 code/message/command/root match | `diagnostic=true` |
| `error-code`, `diagnostic-code` | 精确 diagnostic enum，输入大小写不敏感 | `error-code=RESOURCE_NOT_FOUND` |
| `error-message`, `diagnostic-message` | Error message substring | `error-message=missing` |
| `outputs` | 精确 count 或 boolean nonempty/empty | `outputs=true` |
| `output` | Count 表达式或 output text/channel/target/payload | `output=ready` |
| `output-channel` | 精确 channel | `output-channel=warning` |
| `output-payload` | JSON path 存在，或 `path=jsonValue` 相等 | `output-payload=position.x=10` |
| `diff`, `path`, `state` | Diff path/rendered entry substring | `path=/storage/demo:state` |
| `score`, `scores` | 同上，仅 `/scores` | `score=runs` |
| `storage` | 同上，仅 `/storage` | `storage=phase` |

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --pack .\my-pack --function demo:main `
  --trace-file build/failed-execute.jsonl `
  --trace-filter root=execute `
  --trace-filter success=false
```

Filter 只改变 export/console selection，不改变 sandbox 内记录。Shell-sensitive value（尤其 JSON payload comparison）需要正确 quote。

## Player-event trace

`--event-trace-file` 把完整 `PlayerEventTraceEvent` 列表写成 JSONL。每项保留规范化 player/event context、resolved target、success/error、advancement updates 和详细 criterion failures。它把“交互是否到达 runtime”与“advancement criterion 为什么没匹配”分开。

调试 trigger 时：

1. 确认 event trace 存在，且规范化 type/target/item/block 正确。
2. 修改 advancement JSON 前先读 criterion failures。
3. 检查 updates 与最终 player advancement progress。
4. 通过 command trace 的 source/function stack 关联 reward 触发的命令。

## Coverage

Coverage 在当前 sandbox 中累计 function invocation 与可执行行 hit。CLI 选项：

- `--coverage`：人类摘要；
- `--coverage-file`：JSON；
- `--minimum-line-coverage` / `--min-line-coverage` / `--min-coverage`；
- `--minimum-function-coverage` / `--min-function-coverage`；
- 可重复的 `--coverage-include`、`--coverage-exclude`。

Threshold failure 会使命令失败。报告 percentage 时必须同时报告 filter set；排除 helper/generated function 会改变分母，应该可审查。新测量窗口开始时显式 reset Core/Serve 的累计 coverage。

Coverage 只说明什么运行过，不说明断言是否正确；应与 state/output assertion 配合，并检查 invocation 已命中但 line coverage 薄弱的函数。

## 组合 report

### `run --report-file`

Root 为一个对象，包含：

- `version`、`passed`、`gameTime`、`commands`、entity count；
- `assertionFailures`；
- 完整 `outputs`、选中的 `traces`、派生 `diagnostics`、`eventTraces`；
- final `snapshot` 与相对 pre-run snapshot 的 state-only `snapshotDiffs`；
- resource summary/overlay/missing reference；
- coverage。

Trace filter 会作用于 trace list，也影响从它派生的 diagnostics。空 filtered trace 不代表没有命令执行；应看顶层 `commands`，并在 CI job 旁记录 filter config。

### `check --report-file`

Root 为 array。每个 manifest result 包含 path、pass、messages、output/trace/diagnostic/event counts 与 arrays，以及 `attempts`。每个 attempt 记录 version、resolved packs、pass/messages/events、可选 snapshot、diff、resource summary、coverage。Matrix manifest 因而保持一个逻辑 result，同时每个版本 attempt 可单独审计。

## CI 消费方式

1. CLI exit code 是主 gate；artifact parser 不能把失败命令变绿。
2. 失败时始终上传 combined report，再按需上传小型 JSONL streams。
3. 在 artifact 旁记录 CLI version、target profile、command line、coverage/trace filters。
4. 增量解析 JSONL，并限制下游 ingestion，即使 sandbox 已有限制 output/snapshot。
5. 忽略未知 object field，但 required field 类型错误时清晰失败。
6. Command/output/storage 含用户或敏感数据时，脱敏或限制 artifact 访问。

## 限制

- Report schema 可能增加 modeled 字段；consumer 应忽略未知字段，不应拒绝整个 artifact。
- Full command state diff 需要反复 snapshot，大型 sparse world 上成本较高。
- Output retention 受 `maxOutputEvents` 限制；适用时 snapshot/report 创建受 `maxSnapshotBytes` 限制。
- Trace 描述 sandbox-modeled 行为，不包含 packet、server thread、entity AI 等未建模原版内部。
- Filtered export 只是 view，不是完整 audit log。

## 相关页面

- [CI 与覆盖率](/workflows/ci-coverage)
- [QuickTest 断言](/reference/quicktest-assertions)
- [Core API](/reference/core-api)
- [Serve JSONL](/reference/serve-jsonl)
- [排障手册](/guide/troubleshooting)

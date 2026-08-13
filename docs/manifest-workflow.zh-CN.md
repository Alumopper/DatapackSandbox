# Manifest 回归测试

## 适用场景

一次回归需要完整 pack、预置世界、按顺序的动作、多类断言、可复用 fixture/baseline，或同一场景要跨 Minecraft profile 时，使用 `.dps.json` Manifest。一次性实验用 `run`；测试必须位于 Kotlin/Java suite 时使用 QuickTest。

## 前置条件

- 把 Manifest 与引用的 pack、fixture、生成 `.mcfunction`、golden JSON 放在稳定项目树中。
- 当前格式使用 `data/<namespace>/function`、`loot_table`、`advancement` 等单数路径。
- 编写时加 `$schema` 获得编辑器校验，CI 仍要运行 CLI validation。
- 为可复现性固定 `version` 或 `versions`，不要依赖当前默认值。

## 最小可运行示例

`examples/single-function/single-function.dps.json` 展示无需 pack 资源的内存函数：

```json
{
  "version": "26.2",
  "packs": ["pack"],
  "steps": [
    { "functionText": "say manifest ok", "source": "<example>" }
  ],
  "assertions": [
    { "output": { "command": "say", "contains": "manifest ok", "count": 1 } }
  ]
}
```

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check `
  examples/single-function/single-function.dps.json `
  --validate-schema `
  --report-file build/single-function-report.json
```

## 构建有用的 case

先选一个可观察契约，再分四层表达：

1. **Profile 与资源**：`version`/`versions` 加 `packs` 选择行为和加载内容。
2. **Fixture**：`world` 只创建契约需要的 player、block、entity、score、storage 等状态。
3. **Steps**：有序数组精确复现行为。
4. **Assertions**：针对用户或后续函数能观察到的结果。

```json
{
  "$schema": "../../schema/manifest/dps-manifest.schema.json",
  "version": "26.2",
  "unsupported": "error",
  "failOnMissingResources": true,
  "seed": 42,
  "packs": ["pack"],
  "world": {
    "players": [{ "name": "Steve", "position": [0, 64, 0] }],
    "scores": [{ "target": "#case", "objective": "runs", "value": 0 }],
    "storage": { "demo:state": { "ready": false } }
  },
  "steps": [
    { "load": true },
    { "function": "demo:main" },
    { "ticks": 2 }
  ],
  "assertions": [
    { "score": { "target": "#case", "objective": "runs", "equals": 1 } },
    { "storage": { "id": "demo:state", "path": "ready", "equals": true } },
    { "output": { "command": "tellraw", "target": "Steve", "contains": "ready", "count": 1 } }
  ]
}
```

Fixture 要尽可能小。无关 entity 或完整 snapshot 会让失败更难解释，baseline 也更难维护。

## Step 顺序与失败测试

Step 严格从上到下执行。每项选择 load、ticks、function、commands、内联/文件函数、player/block 修改、event、snapshot、trace、reset、loot 等一个动作。`source` 给生成命令命名，让 diagnostic 能指回有意义的来源。

```json
{
  "steps": [
    {
      "commands": [
        "scoreboard objectives add generated dummy",
        "scoreboard players set #generated generated 1"
      ],
      "source": "<generator:setup.commands>"
    },
    { "mcfunction": "generated/body.mcfunction" },
    { "command": "function demo:missing", "allowFailure": true }
  ],
  "assertions": [
    { "diagnostic": { "step": 2, "code": "RESOURCE_NOT_FOUND", "count": 1 } }
  ]
}
```

只有错误本身是预期结果时才用 `allowFailure`，并用 diagnostic/trace assertion 精确约束。否则失败命令应终止 attempt。

## 复用 baseline 与 fixture

`include` 递归加载共享 Manifest；included section 先于当前文件应用：

- 默认 scalar 来自 include，当前文件随后覆盖；
- packs、steps、assertions 按 include 到当前文件的顺序追加；
- 每个 world section 依次应用，后面的同名/同坐标状态获胜。

广泛环境 setup 放在 included baseline 里，但 behavior step 和 expected result 应贴近 case。只复用 world 时，`world.extends`、`world.fixture`、`world.fixtures` 会先应用外部 fixture，再应用本地字段。

```json
{
  "include": ["../shared/strict-base.dps.json"],
  "world": {
    "fixtures": ["../fixtures/players.json", "../fixtures/arena.json"],
    "weather": "clear"
  },
  "steps": [{ "function": "demo:arena/start" }],
  "assertions": [{ "entityCount": { "tag": "participant", "min": 1 } }]
}
```

## 路径解析

每个相对路径都属于声明它的文件，不一定属于顶层 Manifest 或当前工作目录。Include、pack、嵌套 fixture、save import、`mcfunction`、snapshot `equalsFile` 都遵循此规则。移动 included baseline 不会悄悄重定向由 including case 声明的路径。

CLI 输入路径先按进程工作目录解析；找到根 Manifest 后，内部路径按声明文件的目录解析。

## 跨版本运行

生成资源随版本变化，但 fixture、steps、assertions 相同时，用 `versions` 和按版本的 `packs` object：

```json
{
  "versions": ["1.20.4", "26.2"],
  "packs": {
    "1.20.4": ["pack-1_20_4"],
    "26.2": ["pack-26_2"]
  },
  "steps": [{ "function": "demo:main" }],
  "assertions": [
    { "output": { "command": "say", "contains": "multi version ok", "count": 1 } }
  ]
}
```

每个 profile 产生隔离 attempt。Report 为每项保留选择的 version 与解析 pack path；除非 `--fail-fast` 停止 discovery，一个 profile 失败不会隐藏其他项。

## 谨慎添加 coverage 与 golden state

Coverage 可以成为 Manifest 契约：

```json
{
  "coverage": {
    "minimumLine": 90,
    "minimumFunction": 80,
    "include": "demo:*",
    "exclude": "demo:generated/*"
  }
}
```

耐久状态基线优先断言选定 snapshot path。`equalsFile` 相对声明 Manifest 解析：

```json
{
  "assertions": [
    { "snapshot": { "path": "scores.golden", "equalsFile": "expected-snapshot.json" } },
    { "snapshotDiff": { "path": "/scores/golden", "kind": "added", "count": 1 } }
  ]
}
```

替换 golden file 前先审查 CLI `diff`。完整 snapshot 可能增加 modeled field，而选定 score/storage/output 契约通常更稳定。

## 诊断失败 Manifest

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar check cases/demo.dps.json `
  --strict `
  --verbose `
  --snapshot-on-fail `
  --snapshot-diff-on-fail `
  --trace-file build/demo-trace.jsonl `
  --event-trace-file build/demo-events.jsonl `
  --outputs-file build/demo-outputs.jsonl `
  --report-file build/demo-report.json
```

建议依次看：schema/path diagnostic、resource summary 与 missing reference、失败 assertion message、snapshot diff，最后是窄 command/event trace。这样不必一开始就读完整的巨大 snapshot。

## 把交互复现迁入 Manifest

1. 用 `world` fixture 替换 REPL 中手工创建的状态。
2. 按相同顺序复制改变世界的 command/function/event 到 `steps`。
3. 把 `inspect` 观察转成最窄 assertion。
4. 固定 version 与 seed。
5. 先跑 `--validate-schema`，再跑 `--strict`。
6. Report/trace 只作为 CI 证据保存，Manifest 本身保持可读。

## 限制

- `version` 与 `versions` 互斥；两者都省略时选择发布版本的默认 profile。
- Include 循环、缺失路径、schema 未知字段会验证失败。
- `--validate-schema` 只验证结构；`--strict` 再增加 unsupported 与 missing-resource failure。
- 世界仍是稀疏 clean-room 模型；Manifest 通过不证明未建模原版系统。

## 相关页面

- [Manifest 参考](/reference/manifest)
- [QuickTest Fixture](/reference/quicktest-fixtures)
- [QuickTest 断言](/reference/quicktest-assertions)
- [CI 与覆盖率](/workflows/ci-coverage)

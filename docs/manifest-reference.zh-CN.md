# Manifest 参考

## 适用场景

本页用于查询顶层字段、合并/路径规则、world fixture group、step 与 assertion family。编写流程先看 [Manifest 回归测试](/workflows/manifest-tests)。精确 JSON 形态以当前 CLI jar 内置 schema 为权威。

## 导出与校验 schema

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --output build/dps-manifest.schema.json
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --check schema/manifest/dps-manifest.schema.json
java -jar cli/build/libs/datapack-sandbox-cli.jar check cases --validate-schema
```

`$schema` 用于编辑器反馈，但不能只依赖编辑器；CLI 校验用的是实际执行用例运行时自带的 schema。

## 最小有效形态

```json
{
  "$schema": "../../schema/manifest/dps-manifest.schema.json",
  "version": "26.2",
  "packs": ["pack"],
  "steps": [{ "function": "demo:main" }],
  "assertions": [
    { "score": { "target": "#case", "objective": "runs", "equals": 1 } }
  ]
}
```

递归发现按 `.dps.json` 后缀识别。

## 顶层字段

| 字段 | 形态 | 默认/语义 |
| --- | --- | --- |
| `include` | string 或 string[] | 先应用的共享 Manifest |
| `version` | string | 一个 profile；与 `versions` 互斥 |
| `versions` | string[] | 每个 profile 一个隔离 attempt |
| `unsupported` | `warn`/`ignore`/`error` | 已识别未建模行为策略 |
| `seed` | integer | 确定性 world/random seed override |
| `failOnMissingResources` | boolean | 直接缺失资源引用失败 |
| `coverage` | object | Threshold 与资源 id glob filter |
| `packs` | string[] 或 version map | 有序目录/zip 数据包 |
| `world` | object | Step 前应用的稀疏 fixture |
| `steps` | array | 有序动作 |
| `assertions` | array | Step 后评估的期望 |

未知顶层字段会被拒绝。省略 `version`/`versions` 使用 CLI 当前默认 profile；建议显式选择。

### Coverage

| 字段 | 语义 |
| --- | --- |
| `minimumLine` | 最低可执行行百分比 |
| `minimumFunction` | 最低调用函数百分比 |
| `include` | 一个或多个纳入的资源 id glob |
| `exclude` | 一个或多个排除的资源 id glob |

Threshold 失败属于 assertion failure。Filter 会改变分母，修改时应检查详细 coverage artifact。

## Include 与合并规则

Include 可嵌套并按 depth-first 解析；循环失败。Section 从 include 到当前文件依次应用：

| Section | 合并行为 |
| --- | --- |
| Scalar 默认值（version、policy、seed 等） | 后面/当前值覆盖继承默认值 |
| `packs` | 按顺序追加；后面的 pack 资源优先级更高 |
| `steps` | 追加；执行保留合并后顺序 |
| `assertions` | 追加；全部评估 |
| `world` | 每个 section 依次应用；后面的同 key/坐标状态获胜 |

用一套语义明确的 baseline，避免过长的 include 链。最终 report 描述 resolved attempt；可用时 diagnostic source 仍指向声明 section。

## 路径解析

| 含路径的值 | 基准目录 |
| --- | --- |
| CLI 根 input | 进程工作目录 |
| `include` | 包含该 include 的 Manifest |
| `packs` entry | 包含该 packs section 的 Manifest |
| `world.extends` / `fixture` / `fixtures` | 声明它的 world fixture/Manifest |
| `world.save` / `saves` | 声明它的 world fixture/Manifest |
| Step `mcfunction` | 声明 step 的 Manifest |
| Snapshot `equalsFile` | 声明 assertion 的 Manifest |

Generator 应规范化路径；case 需要随项目树移动时保留相对路径。

## World fixture

`world` 描述的是显式稀疏状态，不是生成地形。Schema group：

| 分组 | 字段/用途 |
| --- | --- |
| 复用 | `extends`, `fixture`, `fixtures` |
| 时间/profile 状态 | `gameTime`/`time`, `dayTime`, `seed`, `difficulty`, `defaultGameMode`, `weather`, `weatherDuration` |
| 世界几何 | `worldSpawn`, `worldBorder`, `forcedChunks`, `biomes`, `blocks`, `regions`, `structures` |
| Actor | `entities`, `players`, `teams`, `bossbars` |
| 数据系统 | `scores`, `storage`/`storages`, `gamerules`, `randomSequences` |
| 现有存档片段 | `save`, `saves` |

Schema 为输入兼容保留 `defaultGamemode`、storage/save 单复数等 alias；新文件应使用生成 schema description 中的主拼写。

### Fixture 示例

```json
{
  "world": {
    "gameTime": 100,
    "weather": "clear",
    "worldSpawn": [0, 64, 0],
    "forcedChunks": [[0, 0]],
    "biomes": [{ "pos": [0, 64, 0], "id": "minecraft:plains" }],
    "blocks": [{ "pos": [0, 63, 0], "id": "minecraft:stone" }],
    "entities": [{ "type": "minecraft:pig", "pos": [1, 64, 0], "tags": ["fixture"] }],
    "players": [{ "name": "Alex", "position": [2, 65, 3], "xp": 5 }],
    "scores": [{ "target": "#fixture", "objective": "ready", "value": 1 }],
    "storage": { "demo:env": { "ready": true } }
  }
}
```

Fixture 语义和 save-import 边界见 [QuickTest Fixture](/reference/quicktest-fixtures)。

## Step 参考

一个 step 选择一个主要动作。常见搭配包括用于诊断归因的 `source`，以及预期 command/function 失败时使用的 `allowFailure`。

| 入口 | 值 | 效果 |
| --- | --- | --- |
| `load` | boolean | true 时运行 load-tag function |
| `ticks` | integer | 推进指定 tick |
| `function` | resource id | 调用已加载函数 |
| `command` | string | 执行一条命令 |
| `commands` | string[] | 执行有序 raw commands |
| `functionText` | string | 执行内联 `.mcfunction` |
| `mcfunction` | path | 加载并执行生成文件 |
| `player` | object/string form | 创建/配置玩家 |
| `block` | object | 设置 fixture 风格 block state |
| `event` | object | 注入玩家事件；要求 `player` 与 `type` |
| `snapshot` | boolean/path options | 在序列中捕获状态 |
| `trace` | boolean/options | 控制序列中的 trace |
| `reset` | boolean/options | 重置 modeled world |
| `loot` | object | 发起 loot request |

`commands` 的语义错误默认会终止执行，除非设置 `allowFailure`。需要保留生成来源时，`functionText`/`mcfunction` 应设置 `source`。

### 玩家事件字段

每个 event 都有 `player` 与 `type`。依类型还可提供 item/count/components/NBT、entity/target、block id/position、recipe、dimension transition、damage source/type/amount、keyboard/mouse input code/action/coordinates。无效组合产生 input 或 missing-context diagnostic，详见 [玩家事件](/runtime/player-events)。

## Assertion 参考

每个 assertion object 选择一个 family；要给足约束，避免匹配无关 state/event。

| Family | 重要字段 |
| --- | --- |
| `score` | `target`, `objective`, `equals` 或 `min`/`max` |
| `storage` | `id`、可选 `path`、equality/existence/contains/regex |
| `world` | time/seed/weather/difficulty/gamemode/random/forced chunk/biome/spawn/border |
| `gamerule`, `randomSequence`, `forcedChunk` | Key/坐标与预期 value/state/existence |
| `block` | `pos`、id/existence/NBT |
| `entityCount` | type/tag/dimension + equals/min/max |
| `entity` | identity/position/dimension/health/vehicle/NBT/count 与嵌套 equipment/effect/attribute |
| `player` | name、existence、XP、inventory、dimension/mode/health/food、recipe/effect/stat/NBT/input/spawn |
| `team`, `bossbar` | existence、display/options/members 或 value/style/players |
| `scheduled` | function id、due tick、existence、count |
| `scoreboardObjective`, `scoreboardDisplay` | Metadata/display-slot state |
| `advancement` | player/id + done/criterion state |
| `predicate` | id、可选 player、boolean result |
| `loot` | table/context/player/seed + item/count |
| `item` | player/id/count range/slot/container/existence/components/NBT |
| `trace` | command/root/source/function/success/count、相关 output/diff filter |
| `eventTrace` | player/type/success、criterion/failure、subject、damage/input 细节、count |
| `diagnostic` | step/version/code/command/root/message/count |
| `snapshot` | 可选 data path + equals/file/existence |
| `snapshotDiff` | JSON Pointer/path substring、kind、before/after/contains/count |
| `output` | command/channel/target、raw/normalized text、payload、styled segment、count/order |

### Snapshot path 区别

`snapshot.path` 使用沙盒 data-path，例如 `storage.demo:golden.ready`；`snapshotDiff.path` 使用 JSON Pointer，例如 `/storage/demo:golden/ready`。两者不能互换。

### Output 匹配

Output assertion 可区分 raw/normalized text，约束 command/channel/targets，检查 styled segment，并比较结构化 payload path。机器语义优先 payload；用户展示优先 text/segment。设置 `count`，顺序重要时再加 `order`，让宽匹配也能有确定结果。

## Validation 与执行

- Schema validation 检查 JSON shape、required field、type、enum、unknown property。
- Runtime resolution 检查 path、resource id、active pack override、version support、context。
- Assertion 在合并 step 序列完成后检查最终 observation。
- `--strict` 组合 schema validation、unsupported-as-error 和 missing-resource failure。

## 限制

- Pack 顺序有语义；重排可能改变 active resource，却不改变 schema。
- Step 为扩展保留一定灵活性，但仍应只有一个有意义入口；不要在一个 object 混合无关 action。
- 完整 snapshot/report 可以增加 modeled field；优先窄 assertion，消费者忽略未知 report 字段。
- 本任务型参考不复制所有嵌套 entity/player/item component/NBT shape；这些以导出 schema 为准。

## 相关页面

- [Manifest 回归测试](/workflows/manifest-tests)
- [QuickTest Fixture](/reference/quicktest-fixtures)
- [QuickTest 断言](/reference/quicktest-assertions)
- [资源格式](/resources/resource-formats)

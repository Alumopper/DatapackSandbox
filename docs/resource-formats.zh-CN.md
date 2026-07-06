# 资源格式

加载器可以读取目录形式或 zip 形式的数据包，并会按当前 `VersionProfile` 校验 `pack.mcmeta`。

## `pack.mcmeta`

Minecraft Java `1.20.4` 使用 data pack format `26`，资源目录是旧的复数形式：

```json
{
  "pack": {
    "pack_format": 26,
    "description": "Example 1.20.4 datapack"
  }
}
```

- `data/<namespace>/functions/**/*.mcfunction`
- `data/<namespace>/loot_tables/**/*.json`
- `data/<namespace>/predicates/**/*.json`
- `data/<namespace>/advancements/**/*.json`
- `data/<namespace>/recipes/**/*.json`
- `data/<namespace>/item_modifiers/**/*.json`
- `data/<namespace>/tags/<registry>/**/*.json`

Minecraft Java `26.2` 使用 data pack format `107.1`，当前资源目录是单数形式：

```json
{
  "pack": {
    "pack_format": 107.1,
    "description": "Example 26.2 datapack"
  }
}
```

- `data/<namespace>/function/**/*.mcfunction`
- `data/<namespace>/loot_table/**/*.json`
- `data/<namespace>/predicate/**/*.json`
- `data/<namespace>/advancement/**/*.json`
- `data/<namespace>/recipe/**/*.json`
- `data/<namespace>/item_modifier/**/*.json`
- `data/<namespace>/(chat_type|damage_type|dimension|dimension_type|enchantment|jukebox_song|trim_material|trim_pattern|...)/**/*.json`
- `data/<namespace>/worldgen/(configured_feature|placed_feature|structure|processor_list|...)/**/*.json`
- `data/<namespace>/tags/<registry>/**/*.json`

兼容 profile 会保留自己的 data pack format。例如 `26.1`、`26.1.1`、`26.1.2` 使用 `101.1`，`1.21.11` 使用 `94.1`，`1.20.5` 到 `1.20.6` 使用 `41`。

旧目录别名只会在当前 `VersionProfile` 允许时接受。格式不匹配会以 `VERSION_MISMATCH` 失败。JSON 解析失败会包含文件路径、resource id 和版本 profile。

新版 pack 如果不用单个 `pack_format`，也可以声明范围。加载器支持 `min_format`/`max_format` 和 `supported_formats`，只要当前 profile 的 data pack format 落在范围内即可。

## 函数

函数文件是 `.mcfunction`，路径会映射为 resource location：

```text
data/demo/function/reward.mcfunction -> demo:reward
```

函数内每行是一条命令。空行和注释会跳过。解析失败会带上文件路径、行号、版本和命令片段。

## Loot Table

当前目录：

```text
data/<namespace>/loot_table/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/loot_tables/**/*.json
```

loot table JSON 会加载为 typed model，并在执行时检查 context type。CLI 可直接运行：

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar loot --pack ./pack --table demo:gift --context minecraft:advancement_reward --seed 42
```

`check` 清单可断言确定性 loot 输出，包括物品 id、count、NBT/components 和 seed。

## Predicate

当前目录：

```text
data/<namespace>/predicate/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/predicates/**/*.json
```

predicate 根可以是 object 或 array。array 语义是所有 predicate 都必须为 true。predicate 运行需要上下文；缺少必需上下文时默认严格失败，而不是静默返回 false。

## Advancement

当前目录：

```text
data/<namespace>/advancement/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/advancements/**/*.json
```

加载器会解析 parent、display、criteria、requirements、rewards 和 telemetry flags。运行时按玩家独立记录 progress，支持 grant/revoke/test、事件触发和 reward 执行。

## Raw JSON Resource 与 Tags

Recipe、item modifier 以及更多注册表资源会作为 raw JSON resource 加载并进入资源索引。当前沙盒还不执行完整合成系统、全部 item modifier 函数或 worldgen 语义，但这些资源可以被版本 profile 校验目录布局、被 pack overlay 覆盖，并可通过 API 或 REPL inspect 调试。`item modify entity` 会建模 `set_components`、`set_custom_data`、`set_count`、`limit_count`、`set_item`、`discard`、`set_damage`、`set_name`、`set_lore`、`filtered`、`reference`、`sequence` 等常用 item modifier 函数。实体物品命令支持玩家背包槽和非玩家实体装备槽，例如 `weapon.mainhand`、`weapon.offhand` 与 `armor.*`；装备同时暴露在 snapshot 以及 `HandItems`/`ArmorItems` 实体 NBT 中：

```text
data/<namespace>/recipe/**/*.json
data/<namespace>/item_modifier/**/*.json
data/<namespace>/chat_type/**/*.json
data/<namespace>/damage_type/**/*.json
data/<namespace>/dimension/**/*.json
data/<namespace>/dimension_type/**/*.json
data/<namespace>/worldgen/configured_feature/**/*.json
data/<namespace>/worldgen/placed_feature/**/*.json
data/<namespace>/worldgen/structure/**/*.json
data/<namespace>/worldgen/processor_list/**/*.json
data/<namespace>/enchantment/**/*.json
data/<namespace>/jukebox_song/**/*.json
data/<namespace>/trim_material/**/*.json
data/<namespace>/trim_pattern/**/*.json
data/<namespace>/banner_pattern/**/*.json
data/<namespace>/wolf_variant/**/*.json
data/<namespace>/painting_variant/**/*.json
```

兼容旧版本时也接受：

```text
data/<namespace>/recipes/**/*.json
data/<namespace>/item_modifiers/**/*.json
```

普通 tag 会从 `data/<namespace>/tags/<registry>/**/*.json` 读取，保留 registry 目录名、tag id、`replace` 和 `values`。`values` 支持字符串和 `{ "id": "...", "required": false }` 对象；tag 引用会保留 `#` 前缀。后加载的 pack 设置 `"replace": true` 时，会丢弃同一 tag 的旧值；function `load`/`tick` 标签生成运行入口列表时也使用同样的 replace 语义。缺失且标记为 `"required": false` 的 function 条目会被跳过，不会进入运行入口列表，也不会产生缺失引用失败。

REPL 中可以查看：

```text
inspect recipe
inspect item_modifier
inspect raw
inspect raw <type>
inspect raw <type> <id>
inspect tags [registry]
inspect resources [type]
```

资源索引会记录 type、id、来源 pack、文件路径、active/overridden 状态，以及 pack overlay 覆盖关系。`datapack list` 的结构化输出 payload 会包含 `overriddenResources` 和 `resourceOverrides` 数组，便于命令生成器或测试用例直接断言覆盖行为，不必进入 REPL。`check --verbose` 也会打印资源摘要、覆盖条目，以及 load/tick 函数标签、advancement parent/reward、predicate/loot/item modifier 资源中的 predicate reference 和嵌套 loot table 中的直接缺失引用。

## SNBT 与 Data Path

运行时接受常用 SNBT-lite：

```snbt
{foo:[1b,2s,{bar:"baz"}],flag:true}
```

Data path 支持字段、数字 list index，以及数组中的简单对象匹配：

```text
foo.bar
foo[0].bar
Items[{Slot:0b}].id
```

同一套 path 引擎会被 `data` 命令、predicate、loot function 和 advancement condition 复用。写入实体或方块实体 NBT 时，会使用 mcdoc schema 校验顶层字段。

## `.dps.json` 清单

清单可包含：

- `version` 或 `versions`
- `include`
- `unsupported`
- `seed`
- `failOnMissingResources`
- `packs`
- `world`
- `steps`
- `assertions`

JSON Schema 位于：

```text
docs/dps-manifest.schema.json
```

standalone CLI 也会内置该 schema：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --output dps-manifest.schema.json
```

使用 `check --validate-schema` 可以在执行前校验 manifest 结构：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --validate-schema
```

`include` 可以写一个相对清单路径，也可以写路径数组。被 include 的清单会先应用；它们的 `world`、`steps` 和 `assertions` 会按顺序拼接，`version`/`versions`、`packs`、`unsupported`、`seed` 和 `failOnMissingResources` 会在当前清单省略这些字段时作为默认值。include 文件里的 world setup 和 step 相对路径会按 include 文件所在目录解析。

顶层 `"seed"` 会设置 manifest 默认的确定性 world seed 和 loot seed。`world.seed` fixture 值会覆盖这个顶层默认 world seed。

顶层设置 `"failOnMissingResources": true`，或在 CLI 使用
`check --fail-on-missing-resources`，可以在已加载资源直接引用缺失的 load/tick
函数、advancement parent/reward、predicate/loot/item modifier 资源中的 predicate reference 或嵌套 loot table 资源时让 manifest 失败。同一批缺失引用始终会出现在
结构化 check report 和 `check --verbose` 资源摘要中。

在 `world` 内部，`fixture`、`fixtures` 和 `extends` 可以写一个相对 world
fixture 路径，也可以写路径数组。被引用文件既可以是裸 world fixture 对象，
也可以是顶层 `{ "world": { ... } }` 对象。它们会先于当前 `world` 应用，因此
标量字段、同坐标方块、同名玩家/team/bossbar、score 和 storage 都可以被当前
manifest 局部覆盖。嵌套 fixture 的相对路径按被引用文件所在目录解析；循环引用会
作为输入格式错误失败。

最小示例：

```json
{
  "version": "26.2",
  "packs": ["./pack"],
  "steps": [
    { "load": true },
    { "ticks": 20 },
    { "function": "demo:main" }
  ],
  "assertions": [
    { "score": { "target": "#clock", "objective": "ticks", "equals": 20 } }
  ]
}
```

`world` 可预置 sparse 方块、实体、玩家、分数、storage、gamerule、time/weather、seed/difficulty/default game mode、世界/玩家出生点、世界边界、force-loaded chunk、biome 覆盖、team、bossbar，也可以从 Java 存档导入指定 chunk。`assertions` 可检查 score、storage、world、player、team、bossbar、advancement、output、loot 等行为。

`steps` 支持完整数据包执行和轻量生成器产物测试：

- `{ "load": true }`
- `{ "ticks": 20 }`
- `{ "function": "demo:main" }`
- `{ "command": "say hello" }`
- `{ "commands": ["scoreboard objectives add runs dummy", "..."], "source": "<generator>" }`
- `{ "functionText": "say inline\nscoreboard ...", "source": "<inline>" }`
- `{ "mcfunction": "relative/path/generated.mcfunction" }`
- `{ "snapshot": "artifacts/before.json" }`
- `{ "trace": { "file": "artifacts/trace.jsonl", "output": true } }`
- `{ "reset": true }`
- `{ "player": { ... } }`、`{ "block": { ... } }`、`{ "event": { ... } }`、`{ "loot": { ... } }`

事件步骤至少需要 `player` 和 `type`。可选上下文字段包括 `item`、`entity`、
`block`、`recipe`、`from`/`to`、`damageSource` 或 `damageType`、`amount`、
键盘 `key`、鼠标 `button`、`action` 以及指针坐标 `x`/`y`：

```json
{ "event": { "player": "Steve", "type": "damage", "damageSource": "minecraft:fall", "amount": 4.5 } }
```

`snapshot` 和 `trace` 步骤可以写 `true` 记录 debug 输出事件，可以写相对文件路径保存 artifact，也可以写成包含 `file` 和 `output` 的对象。`reset` 会保留已加载的数据包和 manifest 默认 seed，但把当前世界替换为全新的 sparse world，并重新创建默认玩家 `Steve`。

如果某个步骤本来就应该失败，可以在该步骤上加 `"allowFailure": true`，然后用 `diagnostic` 断言检查错误码、命令文本和错误消息。

`assertions` 除了既有 score、storage、player、block、entityCount、advancement、predicate、loot 和 output 外，也支持以下类型。断言失败消息会带合并后的断言序号和 JSON Pointer 路径，例如 `assertion 1 (/assertions/0): ...`：

```json
{ "score": { "target": "#clock", "objective": "ticks", "min": 20, "max": 40 } }
```

```json
{ "storage": { "id": "demo:env", "path": "debug.last", "missing": true } }
```

```json
{ "entityCount": { "type": "minecraft:pig", "tag": "fixture", "min": 1, "max": 3 } }
```

```json
{ "world": { "difficulty": "hard", "forcedChunk": [0, 0], "worldBorder": { "size": 100 } } }
```

```json
{ "item": { "player": "Steve", "id": "minecraft:apple", "count": 3 } }
```

```json
{ "output": { "command": "say", "contains": "hello", "order": 1, "count": 1 } }
```

```json
{ "trace": { "root": "scoreboard", "success": true, "count": 2 } }
```

```json
{ "eventTrace": { "player": "Steve", "type": "damage", "success": true, "criterion": "fell", "count": 1 } }
```

```json
{ "diagnostic": { "step": 1, "code": "COMMAND_ERROR", "contains": "Unknown scoreboard objective", "count": 1 } }
```

```json
{ "snapshotDiff": { "path": "/scores/runs/#clock", "kind": "changed", "after": 20, "count": 1 } }
```

`player` 断言还可检查 exists、dimension、game mode、health、food、selected slot、recipe、effect、stat、position、last input 和 spawn point。`team`、`bossbar` 断言会检查对应运行时状态。`item` 断言可按玩家、slot、id、精确/最小/最大 count、components path 和 NBT path 检查背包结果。`block` 断言可检查 sparse world 中的方块存在性、id 和方块实体 NBT path 是否存在或等于指定值。`storage` 断言可用 `equals` 比较路径值，也可用 `exists`/`missing` 检查 storage 根对象或嵌套路径是否存在。`output` 断言可按 command/channel/targets、text/contains、空白规范化后的 text/contains、payload path/value、segment style、count 和从 1 开始的 `order` 检查输出；未匹配时失败消息会列出截断后的实际输出候选。`trace` 断言可按 command/root/contains/success/count/source file/function stack 检查命令执行链；未匹配时失败消息会列出截断后的实际命令 trace 候选。`eventTrace` 断言可按 player、type、success、advancement、criterion 和 count 检查玩家事件调试链路；未匹配时失败消息会列出截断后的实际玩家事件 trace 候选。`diagnostic` 断言可按 step、version、code、command、root、message substring 和 count 检查预期失败。`snapshotDiff` 断言可按 JSON Pointer path、diff kind、before/after 值、渲染文本片段和 count 检查步骤前后的状态差异。

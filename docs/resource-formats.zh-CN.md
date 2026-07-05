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

Recipe、item modifier 以及更多注册表资源会作为 raw JSON resource 加载并进入资源索引。当前沙盒还不执行完整合成系统、全部 item modifier 函数或 worldgen 语义，但这些资源可以被版本 profile 校验目录布局、被 pack overlay 覆盖，并可通过 API 或 REPL inspect 调试。`item modify entity` 会建模 `set_components`、`set_custom_data`、`set_count` 等常用 item modifier 函数：

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

普通 tag 会从 `data/<namespace>/tags/<registry>/**/*.json` 读取，保留 registry 目录名、tag id、`replace` 和 `values`。`values` 支持字符串和 `{ "id": "...", "required": false }` 对象；tag 引用会保留 `#` 前缀。

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

资源索引会记录 type、id、来源 pack、文件路径、active/overridden 状态，以及 pack overlay 覆盖关系。

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
- `unsupported`
- `packs`
- `world`
- `steps`
- `assertions`

JSON Schema 位于：

```text
docs/dps-manifest.schema.json
```

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

`world` 可预置 sparse 方块、实体、玩家、分数、storage、gamerule、time/weather、seed/difficulty/default game mode、世界/玩家出生点、force-loaded chunk、biome 覆盖、team、bossbar，也可以从 Java 存档导入指定 chunk。`assertions` 可检查 score、storage、world、player、team、bossbar、advancement、output、loot 等行为。

`steps` 支持完整数据包执行和轻量生成器产物测试：

- `{ "load": true }`
- `{ "ticks": 20 }`
- `{ "function": "demo:main" }`
- `{ "command": "say hello" }`
- `{ "commands": ["scoreboard objectives add runs dummy", "..."], "source": "<generator>" }`
- `{ "functionText": "say inline\nscoreboard ...", "source": "<inline>" }`
- `{ "mcfunction": "relative/path/generated.mcfunction" }`
- `{ "player": { ... } }`、`{ "block": { ... } }`、`{ "event": { ... } }`、`{ "loot": { ... } }`

事件步骤至少需要 `player` 和 `type`。可选上下文字段包括 `item`、`entity`、
`block`、`recipe`、`from`/`to`、`damageSource` 或 `damageType`、`amount`、
键盘 `key`、鼠标 `button`、`action` 以及指针坐标 `x`/`y`：

```json
{ "event": { "player": "Steve", "type": "damage", "damageSource": "minecraft:fall", "amount": 4.5 } }
```

`assertions` 除了既有 score、storage、player、block、entityCount、advancement、predicate、loot 和 output 外，也支持：

```json
{ "world": { "difficulty": "hard", "forcedChunk": [0, 0] } }
```

```json
{ "item": { "player": "Steve", "id": "minecraft:apple", "count": 3 } }
```

```json
{ "trace": { "root": "scoreboard", "success": true, "count": 2 } }
```

```json
{ "eventTrace": { "player": "Steve", "type": "damage", "success": true, "criterion": "fell", "count": 1 } }
```

`player` 断言还可检查 dimension、game mode、health、food、selected slot、recipe、effect、stat、position、last input 和 spawn point。`team`、`bossbar` 断言会检查对应运行时状态。`item` 断言可按玩家、slot、id、count、components path 和 NBT path 检查背包结果。`trace` 断言可按 command/root/contains/success/count/source file/function stack 检查命令执行链。`eventTrace` 断言可按 player、type、success、advancement、criterion 和 count 检查玩家事件调试链路。

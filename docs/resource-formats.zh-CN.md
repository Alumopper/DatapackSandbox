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
- `data/<namespace>/(chat_type|damage_type|dimension|dimension_type|enchantment|equipment_asset|jukebox_song|trim_material|trim_pattern|...)/**/*.json`
- `data/<namespace>/worldgen/(configured_feature|placed_feature|structure|processor_list|...)/**/*.json`
- `data/<namespace>/tags/<registry>/**/*.json`

兼容 profile 会保留自己的 data pack format。例如 `26.1`、`26.1.1`、`26.1.2` 使用 `101.1`，`1.21.11` 使用 `94.1`，`1.20.5` 到 `1.20.6` 使用 `41`。

旧目录别名只会在当前 `VersionProfile` 允许时接受。格式不匹配会以 `VERSION_MISMATCH` 失败。JSON 解析失败会包含文件路径、resource id 和版本 profile。

新版 pack 如果不用单个 `pack_format`，也可以声明范围。加载器支持 `min_format`/`max_format` 和 `supported_formats`，只要当前 profile 的 data pack format 落在范围内即可。

## 资源行为等级

运行时通过 `ResourceCatalog` 暴露同一份资源类型列表；loader 的 raw JSON 覆盖和后续文档工具应复用这个目录，而不是各自维护一份资源类型清单。

standalone CLI 可以导出资源目录，供脚本和文档使用：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar resources
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs --output docs/resource-catalog.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --check docs/resource-formats.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs --locale zh-CN --check docs/resource-formats.zh-CN.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --json --output build/resource-catalog.json
```

`resources --check` 会校验目录里的每个资源类型都出现在文档中，并带有匹配的行为等级。默认检查英文目录；加上 `--locale zh-CN` 可检查中文本地化目录。Gradle `check` 生命周期会通过 standalone jar smoke task 同时运行英文和中文文档检查。

| 等级 | 含义 |
|---|---|
| `exact` | 文档覆盖的行为目标是与原版可观察结果一致。 |
| `modeled` | 资源会进入确定性的沙盒运行时语义。 |
| `observed-noop` | 资源会被版本校验、索引、记录覆盖关系并可 inspect，但完整运行时语义有意缺省。 |
| `unsupported` | 资源不会加载，或会被当前沙盒拒绝。 |

| 资源 | 行为等级 | 运行时 / debug 表面 |
|---|---|---|
| `function` | `modeled` | mcfunction 执行、trace source location 和缺失引用检查。 |
| `tag/function` | `modeled` | load/tick/function tag 执行和 `replace` 语义。 |
| `loot_table` | `modeled` | 支持上下文内的确定性 loot 生成和命令输出。 |
| `predicate` | `modeled` | predicate 命令/API、advancement 条件、loot 条件和 item modifier。 |
| `advancement` | `modeled` | 玩家 progress、criteria 匹配、rewards、output 和事件 trace。 |
| `recipe` | `modeled` | 进入资源索引和玩家 recipe 状态，供命令与 rewards 使用。 |
| `item_modifier` | `modeled` | `item modify` 会应用常用 item modifier 函数。 |
| `tag/<registry>` | `observed-noop` | 普通 tag 保留 `replace` 语义，并进入资源索引供 inspect。 |
| `banner_pattern` | `modeled` | item 输出会暴露 banner pattern JSON 元数据。 |
| `cat_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。 |
| `chat_type` | `modeled` | 聊天命令会暴露 chat type JSON 元数据。 |
| `chicken_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。 |
| `cow_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。 |
| `damage_type` | `modeled` | damage 命令会暴露 damage type JSON 元数据。 |
| `dialog` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `dimension` | `modeled` | 维度感知命令输出会暴露 dimension JSON 元数据。 |
| `dimension_type` | `modeled` | dimension 资源会暴露关联的 dimension type JSON 元数据。 |
| `enchantment` | `modeled` | enchant 命令会暴露 enchantment JSON 元数据。 |
| `enchantment_provider` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `equipment_asset` | `modeled` | item 输出会暴露 equipment asset JSON 元数据。 |
| `frog_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。 |
| `instrument` | `modeled` | item 输出会暴露 instrument JSON 元数据。 |
| `jukebox_song` | `modeled` | item 输出会暴露 jukebox song JSON 元数据。 |
| `painting_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。 |
| `pig_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。 |
| `test_environment` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `test_instance` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `trim_material` | `modeled` | item 输出会暴露 armor trim material JSON 元数据。 |
| `trim_pattern` | `modeled` | item 输出会暴露 armor trim pattern JSON 元数据。 |
| `wolf_sound_variant` | `modeled` | summon wolf 会暴露 wolf sound variant JSON 元数据。 |
| `wolf_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。 |
| `worldgen/biome` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/configured_carver` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/configured_feature` | `modeled` | simple_block、block_column、disk、vegetation_patch、tree、basalt_columns、delta_feature、lake、replace_single_block、replace_blob、selector、random_patch、flower 和 ore feature JSON 可被 place feature 消费。 |
| `worldgen/density_function` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/flat_level_generator_preset` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/multi_noise_biome_source_parameter_list` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/noise` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/noise_settings` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/placed_feature` | `modeled` | placed feature 会解析 configured simple_block/block_column/disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/replace_single_block/replace_blob/selector/random_patch/flower/ore 资源，供 place feature 使用。 |
| `worldgen/processor_list` | `modeled` | block_ignore、protected_blocks、jigsaw_replacement、capped、nop 和带 block/tag 谓词的 rule processor 可被沙盒结构放置消费。 |
| `worldgen/structure` | `modeled` | 沙盒结构 JSON 与二进制结构 NBT 的 palette blocks/entities 可被 `place structure` 和 `place template` 展开。 |
| `worldgen/structure_set` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/template_pool` | `modeled` | single/legacy/list/feature pool element 和 fallback pool 可被 place jigsaw 展开。 |
| `worldgen/world_preset` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |

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

`random_chance_with_enchanted_bonus` 会从 predicate/loot 上下文读取当前工具，并检查 flat 形态或 `levels` 形态的 `minecraft:enchantments` 组件。它支持 `unenchanted_chance`、现代 `enchanted_chance` 等级值（如 `constant`、`linear`、`clamped`、`fraction`、`levels_squared`、`lookup`），也兼容旧式 `chance` + looting/bonus multiplier 字段。常用 loot condition 还覆盖 `table_bonus`、`killed_by_player` 和 `value_check`，其中 `value_check` 可使用 constant、uniform、binomial 和 scoreboard score 数值 provider。

## Advancement

当前目录：

```text
data/<namespace>/advancement/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/advancements/**/*.json
```

加载器会解析 parent、display、criteria、requirements、rewards 和 telemetry flags。运行时按玩家独立记录 progress，支持 grant/revoke/test、事件触发和 reward 执行，并为 XP、recipe、function 与 loot 奖励记录结构化 `advancement reward` 输出。

## Raw JSON Resource 与 Tags

Chat type、damage type、dimension、dimension type、enchantment、实体 variant、item component registry、armor trim、recipe、item modifier 以及更多注册表资源会作为 raw JSON resource 加载并进入资源索引。当前沙盒还不执行完整合成系统、全部 item modifier 函数或完整 worldgen 语义，但已建模沙盒结构放置、processor_list 的 block_ignore/protected_blocks/jigsaw_replacement/capped/nop/rule block/tag 谓词处理、template_pool single/legacy/list/feature/fallback jigsaw 放置、simple_block、block_column、确定性 disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/replacement/selector/random_patch/flower 和 sparse-world ore feature 放置、聊天命令中的 chat type 元数据输出、维度感知命令输出中的 dimension/dimension_type 元数据、`enchant` 命令中的 enchantment 元数据、`summon` 输出中的实体 variant 元数据、item 输出中的 equipment asset/banner pattern/instrument/jukebox song/armor trim material/pattern 元数据以及 `damage` 命令中的 damage type 元数据输出；其余资源可以被版本 profile 校验目录布局、被 pack overlay 覆盖，并可通过 API 或 REPL inspect 调试；`recipe give` 和 `recipe take` 会更新玩家 recipe 状态，并在结构化输出中报告实际变更的 recipe id 列表。Loot table 可以展开 item tag entry，包括嵌套 tag 和 optional 值；`expand=false` 会输出整个 tag，`expand=true` 会把 tag 内物品作为展开候选参与选择。Loot function 已覆盖常见 count/item/component/enchantment 修改、基于工具附魔的 `apply_bonus`，并支持从实体上下文复制名称的 `copy_name`、从当前工具复制 components 的 `copy_components`、`include` 和 `exclude` 过滤，以及通过 `reference` 复用 item modifier 资源。`item modify entity` 会建模 `set_components`、`set_custom_data`、`set_count`、`limit_count`、`set_item`、`discard`、`set_damage`、`set_name`、`set_lore`、`copy_nbt`、带 `include`/`exclude` 的 `copy_components`、`filtered`、`reference`、`sequence` 等常用 item modifier 函数。实体物品命令支持玩家背包、当前主手、`enderchest.*` 槽和非玩家实体装备槽，例如 `weapon.mainhand`、`weapon.offhand` 与 `armor.*`；`give`、`clear` 和 `item replace ... with` 的 item argument 支持 JSON/SNBT-lite NBT（如 `minecraft:stick{marked:true}`）和 components payload（如 `minecraft:stick[custom_data={marked:true}]`），括号内空格会在命令分词时保留给后续解析。装备同时暴露在 snapshot 以及 `HandItems`/`ArmorItems` 实体 NBT 中，entity predicate 也可以匹配 `mainhand`、`offhand`、`head`、`chest`、`legs`、`feet` 等 `equipment` 字段、带 amplifier/duration/粒子可见性的 active `effects` 字段，以及 `absolute`、`horizontal`、`x`、`y`、`z` 距离范围；item predicate 可直接按 id 或 `#` item tag 匹配物品，并按等级范围匹配 `enchantments` 或 `stored_enchantments`，且 `nbt` 条件会按完整生成后的实体 NBT 视图检查；predicate `location_check` 的 block 条件会按 id、block tag、state/property 值和方块实体 NBT 读取被测位置的显式 sparse block，biome 条件会读取 `fillbiome`、world fixture、manifest 或 quick-test setup 声明的 sparse biome 覆盖，未声明的位置不会推断生成 biome；`block_state_property` 会检查当前 block id，并在存在完整 sparse block 上下文时检查 state properties：

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
data/<namespace>/worldgen/structure/**/*.nbt
data/<namespace>/structure/**/*.nbt
data/<namespace>/structures/**/*.nbt
data/<namespace>/worldgen/processor_list/**/*.json
data/<namespace>/enchantment/**/*.json
data/<namespace>/equipment_asset/**/*.json
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
inspect registry [group]
```

资源索引会记录 type、id、来源 pack、文件路径、active/overridden 状态，以及 pack overlay 覆盖关系。`datapack list [available|enabled]` 的结构化输出 payload 会包含 `filter`、`packCount`、`packs`、`overriddenResources`、`resourceOverrides` 和 `missingReferences`，便于命令生成器或测试用例直接断言已加载 pack、覆盖和缺失引用行为，不必进入 REPL。`check --verbose` 也会打印资源摘要、覆盖条目，以及 load/tick 函数标签、advancement parent/reward、predicate/loot/item modifier 资源中的 predicate reference 和嵌套 loot table 中的直接缺失引用。
`inspect registry [group]` 会列出当前 version profile 中的 registry 条目，并带上 `source=profile:<version>`，便于按实际执行和补全使用的 profile 调试 registry 查找；非交互场景可用 `resources --registry --registry-group <group> --json` 导出同一份 profile registry 数据，作为 CI artifact 或命令生成器回归输入。

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
java -jar cli/build/libs/datapack-sandbox-cli.jar schema --check docs/dps-manifest.schema.json
```

使用 `check --validate-schema` 可以在执行前校验 manifest 结构：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check ./sandbox-cases --validate-schema
```

`include` 可以写一个相对清单路径，也可以写路径数组。被 include 的清单会先应用；它们的 `world`、`steps` 和 `assertions` 会按顺序拼接，`version`/`versions`、`packs`、`unsupported`、`seed` 和 `failOnMissingResources` 会在当前清单省略这些字段时作为默认值。include 文件里的 world setup 和 step 相对路径会按 include 文件所在目录解析。来自 include 文件的断言失败会在前缀中保留来源 manifest 文件路径和 JSON Pointer，方便从 CI 日志直接定位公共断言。

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

`world` 可预置 sparse 方块、用 `regions` 批量铺设的闭区间方块区域、用 `structures` 声明相对方块/实体集合、玩家、非玩家实体维度、health、vehicle/passenger 关系、装备、active effects 和 attributes、玩家末影箱物品、玩家 advancement progress、分数、storage、gamerule、time/weather、seed/difficulty/default game mode、世界/玩家出生点、世界边界、force-loaded chunk、biome 覆盖、team、bossbar，也可以从 Java 存档导入指定 chunk。`assertions` 可检查 score、storage、world、player、entity、entityCount、team、bossbar、scheduled、advancement、output、loot 等行为。

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

`assertions` 除了既有 score、storage、player、block、entityCount、advancement、predicate、loot、output、snapshot 和 snapshot diff 外，也支持以下类型。断言失败消息会带合并后的断言序号和 JSON Pointer 路径，例如 `assertion 1 (/assertions/0/output): ...`：

```json
{ "score": { "target": "#clock", "objective": "ticks", "min": 20, "max": 40 } }
```

```json
{ "storage": { "id": "demo:env", "path": "debug.last", "missing": true } }
```

```json
{ "entityCount": { "type": "minecraft:pig", "tag": "fixture", "dimension": "minecraft:the_nether", "min": 1, "max": 3 } }
```

```json
{ "entity": { "type": "minecraft:pig", "tag": "fixture", "dimension": "minecraft:the_nether", "health": 8.0, "vehicle": "00000000-0000-0000-0000-000000000102", "nbt": { "path": "Health", "equals": 8.0 }, "equipment": { "slot": "weapon.mainhand", "id": "minecraft:iron_sword" }, "effect": { "id": "minecraft:strength", "duration": 80 }, "attribute": { "id": "minecraft:max_health", "equals": 12.0 } } }
```

```json
{ "world": { "difficulty": "hard", "forcedChunk": [0, 0], "worldBorder": { "size": 100 } } }
```

```json
{ "scheduled": { "id": "demo:later", "dueTick": 5, "count": 2 } }
```

```json
{ "item": { "player": "Steve", "container": "enderItems", "id": "minecraft:apple", "count": 3 } }
```

```json
{ "output": { "command": "say", "contains": "hello", "order": 1, "count": 1 } }
```

```json
{ "trace": { "root": "scoreboard", "success": true, "outputs": 0, "outputContains": "done", "outputTarget": "Steve", "hasDiff": true, "diffPath": "/scores/runs", "diffKind": "added", "diffContains": "#clock", "count": 1 } }
```

`trace` 断言中的 `outputContains` 和 `outputTarget` 会匹配该命令产生的输出事件文本和目标。

```json
{ "eventTrace": { "player": "Steve", "type": "damage", "success": true, "criterion": "fell", "damageSource": "minecraft:fall", "damageAmount": 4.5, "count": 1 } }
```

```json
{ "eventTrace": { "player": "Steve", "type": "block_placed", "failedAdvancement": "demo:place_diamond", "failedCriterion": "place_diamond", "failureContains": "block expected minecraft:diamond_block", "count": 1 } }
```

带 `blockPos` 或 `blockX`/`blockY`/`blockZ` 的 block 事件会把目标坐标写入 event trace，并可用于断言。

```json
{ "diagnostic": { "step": 1, "code": "COMMAND_ERROR", "contains": "Unknown scoreboard objective", "count": 1 } }
```

```json
{ "snapshot": { "path": "scores.runs", "equalsFile": "expected-snapshot.json" } }
```

```json
{ "snapshotDiff": { "path": "/scores/runs/#clock", "kind": "changed", "after": 20, "count": 1 } }
```

`entity` 断言可按 type/tag/uuid/position/dimension/health/vehicle/passenger 过滤后检查 exists/count，并检查完整实体 NBT path、装备物品的 id/count/components/NBT、active effect 的 duration/amplifier/粒子状态，以及显式 attribute 值。`player` 断言还可检查 exists、dimension、game mode、health、food、selected slot、末影箱物品数量、recipe、effect、stat、advancement progress、完整 NBT path、position、last input 和 spawn point。`team`、`bossbar` 断言会检查对应运行时状态。`scheduled` 断言可按函数 id、绝对 `dueTick`、存在性和重复条目数量检查 scheduled function 队列。`item` 断言可按玩家、`container`、slot、id、精确/最小/最大 count、components path 和 NBT path 检查背包或末影箱结果。`block` 断言可检查 sparse world 中的方块存在性、id 和方块实体 NBT path 是否存在或等于指定值。`storage` 断言可用 `equals` 比较路径值，也可用 `exists`/`missing` 检查 storage 根对象或嵌套路径是否存在。`output` 断言可按 command/channel/targets、text/contains、空白规范化后的 text/contains、payload path/value、segment style、count 和从 1 开始的 `order` 检查输出；未匹配时失败消息会列出截断后的实际输出候选，且当断言包含 payload path 或 segment style 时会显示对应实际 payload 值或已解析文本 segment 样式。`trace` 断言可按 command/root/contains/success、输出数量、是否产生 snapshot diff、diff path/kind/渲染文本、count、source file 和 function stack 检查命令执行链；未匹配时失败消息会列出截断后的实际命令 trace 候选。`eventTrace` 断言可按 player、type、success、item/entity/block/recipe 上下文、维度变化、damage source/amount、input device/code/action、advancement、criterion、failed advancement/criterion、失败原因片段和 count 检查玩家事件调试链路；未匹配时失败消息会列出截断后的实际玩家事件 trace 候选。`diagnostic` 断言可按 step、version、code、command、root、message substring 和 count 检查预期失败；未匹配时失败消息会列出截断后的实际 diagnostic 候选。`snapshot` 断言可把最终 snapshot 根对象或选定 `path` 与内联 `equals`、JSON `equalsFile`、`exists` 或 `missing` 比较，文件路径按声明该断言的 manifest/include 文件解析。`snapshotDiff` 断言可按 JSON Pointer path、diff kind、before/after 值、渲染文本片段和 count 检查步骤前后的状态差异；未匹配时失败消息会列出截断后的实际 snapshot diff 候选。

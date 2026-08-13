# 资源格式

## 适用场景

编写或排查数据包目录、`pack.mcmeta`、JSON/SNBT 资源、tag 覆盖和资源行为等级时，使用本页。

## 前置条件

确定目标 version profile，并准备目录或 ZIP 形式的数据包；当前 `26.2` 示例应优先使用单数资源目录。

## 最小可运行示例

运行 `java -jar cli/build/libs/datapack-sandbox-cli.jar resources --pack examples/full-stack/pack`，查看实际加载的资源索引和覆盖关系。

## 完整能力

加载器可以读取目录形式或 zip 形式的数据包，并会按当前 `VersionProfile` 校验 `pack.mcmeta`。

## `pack.mcmeta` 与资源目录

数据包目录布局由版本 profile 决定。旧版使用复数目录名，新版使用单数：

Minecraft Java `1.20.4`（data pack format `26`，复数目录）：

```json
{
  "pack": {
    "pack_format": 26,
    "description": "Example 1.20.4 datapack"
  }
}
```

```text
data/<namespace>/functions/**/*.mcfunction
data/<namespace>/loot_tables/**/*.json
data/<namespace>/predicates/**/*.json
data/<namespace>/advancements/**/*.json
data/<namespace>/recipes/**/*.json
data/<namespace>/item_modifiers/**/*.json
data/<namespace>/tags/<registry>/**/*.json
```

Minecraft Java `26.2`（data pack format `107.1`，单数目录）：

```json
{
  "pack": {
    "pack_format": 107.1,
    "description": "Example 26.2 datapack"
  }
}
```

```text
data/<namespace>/function/**/*.mcfunction
data/<namespace>/loot_table/**/*.json
data/<namespace>/predicate/**/*.json
data/<namespace>/advancement/**/*.json
data/<namespace>/recipe/**/*.json
data/<namespace>/item_modifier/**/*.json
data/<namespace>/(chat_type|damage_type|dimension|dimension_type|enchantment|equipment_asset|jukebox_song|trim_material|trim_pattern|...)/**/*.json
data/<namespace>/worldgen/(configured_feature|placed_feature|structure|processor_list|...)/**/*.json
data/<namespace>/tags/<registry>/**/*.json
```

兼容 profile 保留自己的 data pack format，例如 `26.1`、`26.1.1`、`26.1.2` 使用 `101.1`，`1.21.11` 使用 `94.1`，`1.20.5` 到 `1.20.6` 使用 `41`。

旧目录别名只在当前 `VersionProfile` 允许时接受。格式不匹配会记录非致命的 `VERSION_MISMATCH` 加载 warning，测试继续执行，适合临近版本或刻意放宽的 pack；JSON 解析失败和结构错误仍然失败，并附带文件路径、resource id 和版本 profile。

## 格式范围与数组写法

新版 pack 可以不写单个 `pack_format`，改用范围声明。加载器支持 `min_format`/`max_format` 和 `supported_formats`，只要当前 profile 的 data pack format 落在范围内即可。format 值可以写成 JSON 数字，也可以写成由一到两个整数组成的数组：`[107, 1]` 按 `107.1` 处理，`[94]` 按 `94` 处理。

```json
{
  "pack": {
    "min_format": [94],
    "max_format": [107, 1],
    "description": "Example range pack"
  }
}
```

## 资源行为等级

运行时通过 `ResourceCatalog` 暴露同一份资源类型列表；loader 的 raw JSON 覆盖和文档工具复用这个目录，而不是各自维护一份清单。

standalone CLI 可以导出资源目录，供脚本和文档使用：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar resources
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs --output docs/resource-catalog.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --check docs/resource-formats.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --docs --locale zh-CN --check docs/resource-formats.zh-CN.md
java -jar cli/build/libs/datapack-sandbox-cli.jar resources --json --output build/resource-catalog.json
```

`resources --check` 校验目录里的每个资源类型都出现在文档中，并带有匹配的行为等级。默认检查英文目录，加上 `--locale zh-CN` 可检查中文目录；Gradle `check` 生命周期会通过 standalone jar smoke task 同时运行两种检查。

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
| `worldgen/configured_feature` | `modeled` | simple_block、block_column、disk、vegetation_patch、tree、basalt_columns、delta_feature、lake、spring_feature、block_pile、glowstone_blob、forest_rock、netherrack_replace_blobs、chorus_plant、replace_single_block、replace_blob、selector、random_patch、flower 和 ore feature JSON 可被 place feature 消费。 |
| `worldgen/density_function` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/flat_level_generator_preset` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/multi_noise_biome_source_parameter_list` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/noise` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/noise_settings` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/placed_feature` | `modeled` | placed feature 会解析 configured simple_block/block_column/disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replace_single_block/replace_blob/selector/random_patch/flower/ore 资源，供 place feature 使用。 |
| `worldgen/processor_list` | `modeled` | block_ignore、protected_blocks、jigsaw_replacement、capped、nop 和带 block/tag 谓词的 rule processor 可被沙盒结构放置消费。 |
| `worldgen/structure` | `modeled` | 沙盒结构 JSON 与二进制结构 NBT 的 palette blocks/entities 可被 `place structure` 和 `place template` 展开。 |
| `worldgen/structure_set` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |
| `worldgen/template_pool` | `modeled` | single/legacy/list/feature pool element、fallback pool 和确定性 jigsaw connector 可被 place jigsaw 展开。 |
| `worldgen/world_preset` | `observed-noop` | 经版本校验的 raw JSON 资源，进入索引供 inspect。 |

## 函数

函数文件是 `.mcfunction`，路径会映射为 resource location：

```text
data/demo/function/reward.mcfunction -> demo:reward
```

函数内每行是一条命令，空行和注释会跳过；解析失败会带上文件路径、行号、版本和命令片段。

## 战利品表（Loot Table）

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

## 谓词（Predicate）

当前目录：

```text
data/<namespace>/predicate/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/predicates/**/*.json
```

predicate 根可以是 object 或 array：array 语义是所有 predicate 都必须为 true。predicate 运行需要上下文，缺少必需上下文时默认严格失败，而不是静默返回 false。

`random_chance_with_enchanted_bonus` 会从 predicate/loot 上下文读取当前工具，检查 flat 形态或 `levels` 形态的 `minecraft:enchantments` 组件，支持 `unenchanted_chance`、现代 `enchanted_chance` 等级值（如 `constant`、`linear`、`clamped`、`fraction`、`levels_squared`、`lookup`），也兼容旧式 `chance` + looting/bonus multiplier 字段。常用 loot condition 还覆盖 `table_bonus`、`killed_by_player` 和 `value_check`，其中 `value_check` 可使用 constant、uniform、binomial 和 scoreboard score 数值 provider。

## 进度（Advancement）

当前目录：

```text
data/<namespace>/advancement/**/*.json
```

兼容旧版本时可能允许：

```text
data/<namespace>/advancements/**/*.json
```

加载器会解析 parent、display、criteria、requirements、rewards 和 telemetry flags。运行时按玩家独立记录 progress，支持 grant/revoke/test、事件触发和 reward 执行，并为 XP、recipe、function 与 loot 奖励记录结构化 `advancement reward` 输出。

## Raw JSON 资源与标签

Chat type、damage type、dimension、dimension type、enchantment、实体 variant、item component registry、armor trim、recipe、item modifier 以及更多注册表资源会作为 raw JSON resource 加载并进入资源索引。完整合成系统、全部 item modifier 函数和完整 worldgen 语义仍不在范围内，但下列语义已经建模：

- **结构放置**：`place structure`/`place template` 消费沙盒结构 JSON 与二进制结构 NBT（palette blocks/entities）；`processor_list` 支持 `block_ignore`、`protected_blocks`、`jigsaw_replacement`、`capped`、`nop` 和带 block/tag 谓词的 rule processor。
- **Jigsaw**：`place jigsaw` 解析 template_pool 的 single/legacy/list/feature 元素、fallback pool 和确定性 connector 展开。
- **Feature 放置**：`place feature` 消费 simple_block、block_column、确定性 disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replacement/selector/random_patch/flower 和 sparse-world ore。
- **元数据输出**：聊天命令暴露 chat type；维度感知命令暴露 dimension/dimension_type；`enchant` 暴露 enchantment definition；`summon` 暴露实体 variant；item 输出暴露 equipment asset、banner pattern、instrument、jukebox song、armor trim material/pattern；`damage` 暴露 damage type。
- **loot 与 item modifier**：loot table 支持 item tag entry 展开（嵌套 tag、optional 值、`expand=false` 整 tag、`expand=true` 展开候选）；loot function 覆盖常见 count/item/component/enchantment 修改、工具附魔感知的 `apply_bonus`、`copy_name`、带 `include`/`exclude` 过滤的 `copy_components`，以及通过 `reference` 复用 item modifier 资源。`item modify entity` 建模 `set_components`、`set_custom_data`、`set_count`、`limit_count`、`set_item`、`discard`、`set_damage`、`set_name`、`set_lore`、`copy_nbt`、`copy_components`、`filtered`、`reference`、`sequence`。
- **物品与装备**：实体物品命令支持玩家背包、当前主手、`enderchest.*` 槽和非玩家实体装备槽（`weapon.mainhand`、`weapon.offhand`、`armor.*` 等）；装备同时暴露在 snapshot 与 `HandItems`/`ArmorItems` 实体 NBT 中，entity predicate 也可以匹配。`give`、`clear` 和 `item replace ... with` 的 item argument 支持 JSON/SNBT-lite NBT（如 `minecraft:stick{marked:true}`）和 components payload（如 `minecraft:stick[custom_data={marked:true}]`），括号内空格会在命令分词时保留给后续解析。
- **recipe 状态**：`recipe give`/`recipe take` 更新玩家 recipe 状态，并在结构化输出中报告实际变更的 recipe id 列表。

以上 raw JSON 资源目录：

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

普通 tag 从 `data/<namespace>/tags/<registry>/**/*.json` 读取，保留 registry 目录名、tag id、`replace` 和 `values`。`values` 支持字符串和 `{ "id": "...", "required": false }` 对象，tag 引用保留 `#` 前缀。后加载的 pack 设置 `"replace": true` 时，会丢弃同一 tag 的旧值；function `load`/`tick` 标签生成运行入口列表时使用同样的 replace 语义。缺失且标记为 `"required": false` 的 function 条目会被跳过，不进入运行入口列表，也不会产生缺失引用失败。

### 索引与检查

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

资源索引记录 type、id、来源 pack、文件路径、active/overridden 状态，以及 pack overlay 覆盖关系。`datapack list [available|enabled]` 的结构化输出 payload 包含 `filter`、`packCount`、`packs`、`overriddenResources`、`resourceOverrides` 和 `missingReferences`，命令生成器或测试用例可以直接断言这些字段，不必进入 REPL。`check --verbose` 也会打印资源摘要、覆盖条目，以及 load/tick 函数标签、advancement parent/reward、predicate/loot/item modifier 资源中的 predicate reference 和嵌套 loot table 中的直接缺失引用。

`inspect registry [group]` 列出当前 version profile 的 registry 条目，并带上 `source=profile:<version>`，便于按实际执行和补全使用的 profile 调试 registry 查找；非交互场景可用 `resources --registry --registry-group <group> --json` 导出同一份 profile registry 数据，作为 CI artifact 或命令生成器回归输入。

## SNBT 与数据路径

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

同一套 path 引擎被 `data` 命令、predicate、loot function 和 advancement condition 复用。写入实体或方块实体 NBT 时，会使用 mcdoc schema 校验顶层字段。

## `.dps.json` 清单

Manifest 用于声明版本矩阵、数据包、世界 fixture、执行步骤、断言和覆盖率门槛。最小工作流见 [Manifest 回归测试](/workflows/manifest-tests)；完整字段、include 合并、相对路径解析、steps 与 assertions 见 [Manifest 参考](/reference/manifest)。

canonical JSON Schema 仍位于 `schema/manifest/dps-manifest.schema.json`，也可通过 `schema` CLI 子命令导出或检查。本节保留原标题，使旧章节链接继续有效。

## 限制

`observed-noop` 资源会被校验、索引并可检查，但不代表完整运行时语义；完整 crafting、worldgen 和所有 item modifier 行为仍不在范围内。

## 相关页面

- [Manifest 回归测试](/workflows/manifest-tests)
- [Manifest 参考](/reference/manifest)
- [版本 Profile](/resources/version-profile)
- [命令支持状态](/runtime/command-support)

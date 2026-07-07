# 命令支持状态

默认 profile：Minecraft Java `26.2`。兼容 profile 覆盖到 `1.20.4`。

这个沙盒不嵌入原版服务端。这里的“支持”表示：命令会按沙盒当前建模的、数据包可见状态执行，并产生确定性结果。网络、权限、世界生成、客户端 UI、红石、实体 AI、完整战斗系统和真实服务端生命周期不在运行时范围内。

未支持的原版根命令默认不会让运行失败。默认策略是 `warn`：记录 warning 输出事件并继续执行。需要严格验证时可以使用 CLI `--unsupported error`、清单 `"unsupported": "error"` 或 API `UnsupportedFeatureMode.ERROR`；需要静默跳过时使用 `ignore`。用于检查命令生成器输出时，`run --strict` 和 `check --strict` 会同时启用 unsupported error 和直接缺失资源引用失败；`check --strict` 还会在执行前校验 manifest schema。

## 状态说明

| 状态 | 含义 |
|---|---|
| 支持 | 主要数据包可见行为已经实现。 |
| 部分支持 | 对本地测试有用的行为已经实现，但原版副作用不完整。 |
| 空操作 | 命令会被接受和记录，但沙盒没有对应的可变原版状态。 |
| 未支持 | 未实现；按当前 unsupported 策略处理。 |

## 行为等级

| 等级 | 含义 |
|---|---|
| `exact` | 文档覆盖的行为目标是与原版可观察结果一致。 |
| `modeled` | 沙盒使用确定性的洁净室模型覆盖数据包可见行为。 |
| `observed-noop` | 命令会被接受并产生输出或诊断，但真实副作用有意缺省。 |
| `unsupported` | 命令交给当前 unsupported 策略处理。 |

CLI 可以导出按版本裁剪后的命令目录，供脚本、文档和 CI 使用：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar commands
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --docs
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --docs --output docs/command-catalog.md
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --check docs/command-support.md
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --check docs/command-support.zh-CN.md
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --json --version 26.2
java -jar cli/build/libs/datapack-sandbox-cli.jar commands --json --output build/command-catalog.json --version 26.2
```

`commands --check` 会校验按版本裁剪后的每个根命令都出现在文档中，并带有匹配的行为等级。Gradle `check` 生命周期会通过 standalone jar smoke task 同时运行英文和中文文档检查。

## 原版命令矩阵

| 命令 | 状态 | 行为等级 | 已实现形式 / 沙盒行为 |
|---|---:|---:|---|
| `advancement` | 部分支持 | `modeled` | `grant`、`revoke`、`test`；`grant`/`revoke` 支持 `only`、`from`、`through`、`until` 和 `everything`，会记录结构化 changed criterion 输出并可供 `execute store result` 使用；`test` 会记录通过数量和逐玩家结果 payload；每个玩家独立记录进度；奖励支持 function、loot、XP 和 recipe，并记录 `advancement reward` 结构化输出。 |
| `attribute` | 部分支持 | `modeled` | `get`、`base get`、`base set`、`base reset`、`modifier add`、`modifier remove` 和 `modifier value get`；get 命令会记录结构化 data 输出，供断言和 `execute store result` 使用；modifier 状态会进入 snapshot 和实体 NBT。 |
| `ban`、`ban-ip`、`banlist` | 空操作 | `observed-noop` | 记录请求的封禁目标/IP、原因或列表过滤器为结构化 debug 输出；不存储封禁列表状态。 |
| `bossbar` | 部分支持 | `modeled` | `add`、`remove`、`list`、`get`、`set`；修改命令和 `get` 都会记录结构化 data 输出，供断言和 `execute store result` 使用；状态进入 snapshot，不模拟客户端 UI。 |
| `clear` | 部分支持 | `modeled` | 从沙盒玩家背包移除匹配物品，支持 JSON/SNBT-lite NBT 和 components payload 过滤，记录 matched/removed 数量；`maxCount=0` 作为只查询不删除的检查。 |
| `clone` | 部分支持 | `modeled` | 复制稀疏世界中的方块状态和方块实体 NBT，并记录结构化复制/变化位置输出；不执行更新、掉落或重叠区物理。 |
| `damage` | 部分支持 | `modeled` | 降低实体或玩家生命值，结构化输出支持 `at`、`by`、`from` 上下文，会为自定义伤害来源暴露已加载的 `damage_type` JSON 元数据，发出沙盒 damage/death advancement 事件，并记录生命值变化；不计算盔甲、无敌帧、死亡掉落和完整战斗规则。 |
| `data` | 部分支持 | `modeled` | 支持带可选数值 scale 的 `get`，以及 `merge`、`modify`、`remove`，目标支持 `storage`、`entity`、`block`；写入类操作会记录结构化前后输出；path 支持字段、正/负数字索引和简单对象匹配；`modify` 支持 `value`、`from` 和 `string` 来源；append/prepend/insert 会拒绝已存在的非列表目标而不是覆盖它；顶层 NBT 字段经过 schema 校验。 |
| `datapack` | 部分支持 | `modeled` | `list [available|enabled]` 会在结构化 payload 中报告已加载 pack 路径、typed/raw/tag/resource-index 资源数量、资源覆盖诊断和缺失引用诊断；`enable`/`disable` 作为 no-op 接受，因为沙盒创建后 pack 顺序固定，并会记录请求的 pack 名称和顺序参数供断言。 |
| `debug`、`jfr`、`perf` | 空操作 | `observed-noop` | 接受 action/参数 token 并记录结构化 debug 输出；不模拟 profiling 和 flight recording。 |
| `defaultgamemode` | 支持 | `modeled` | 存储世界默认游戏模式，并记录结构化前后输出。 |
| `difficulty` | 支持 | `modeled` | 存储并报告世界难度，并记录结构化前后输出。 |
| `deop`、`op` | 空操作 | `observed-noop` | 记录请求的权限目标为结构化 debug 输出；不存储权限状态。 |
| `effect` | 部分支持 | `modeled` | `give`、`clear`；更新玩家效果状态并触发相关 advancement 事件，也会更新非玩家实体 active effects，并通过 snapshot 和 `ActiveEffects` NBT 暴露；记录可用于 report/assertion 的结构化输出。 |
| `enchant` | 部分支持 | `modeled` | 向玩家选中物品和非玩家实体主手装备写入附魔组件；命中已加载资源时会在结构化输出中暴露 `enchantment` JSON 元数据，并记录修改后的物品用于 report/assertion；不检查可附魔性。 |
| `execute` | 部分支持 | `modeled` | 支持 `as`、`at`、`positioned <pos>`、`positioned as <selector>`、`align`、`anchored`、`facing`、`in`、`rotated`、`store`、`if`、`unless`、`run` 的核心路径；`as` 只切换执行者，`at` 会把执行位置、维度和旋转移动到目标实体，`positioned as` 只移动执行位置；`align` 会对校验过的 `x`/`y`/`z` 轴取整；`rotated` 和 `facing` 会更新命令旋转上下文，供 `tp` 的相对旋转参数和局部坐标使用；`anchored` 会更新局部坐标基准点；`store` 目标覆盖 score、storage、entity NBT、block NBT 和 bossbar value/max，NBT 目标会按 byte/short/int/long/float/double 类型和 scale 转换数值，整数类型使用窄化转换语义，嵌套条件失败和 `return fail` 会按 success/result `0` 写入；条件覆盖 `entity`、`score`、`data`、`block`、`blocks`、`predicate`、`function`、`dimension`、`biome` 和 `loaded`。 |
| `experience`、`xp` | 部分支持 | `modeled` | `add`、`set`、`query`；玩家 points 与 levels 分开存储；修改和 query 命令都会记录结构化 data 输出，供断言和 `execute store result` 使用。 |
| `fill` | 部分支持 | `modeled` | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`；记录结构化变化位置输出；位置参数支持局部坐标；不执行更新或掉落。 |
| `fillbiome` | 部分支持 | `modeled` | 为显式方块范围记录 biome 覆盖，并记录结构化变化位置输出；同一批显式覆盖可被 `execute if biome` 和 predicate `location_check` 的 biome 条件读取；不模拟区块 biome 容器或生成效果。 |
| `forceload` | 部分支持 | `modeled` | `add`、`remove`、`remove all`、`query`、`query <pos>`；记录强加载 chunk 坐标，并为修改和 query 记录结构化输出。 |
| `function` | 支持 | `modeled` | `function <id>`。 |
| `gamemode` | 支持 | `modeled` | `gamemode <mode> [targets]`；更新沙盒玩家游戏模式，并记录结构化前后输出。 |
| `gamerule` | 部分支持 | `modeled` | 存储任意 gamerule 字符串值，并为修改/query 记录结构化输出；不执行具体游戏规则副作用。 |
| `give` | 部分支持 | `modeled` | 向玩家背包添加物品，记录可用于 report/assertion 的结构化输出；物品含匹配组件且命中已加载资源时会暴露 equipment asset、banner pattern、instrument、jukebox song 和 armor trim material/pattern 元数据；触发 inventory advancement 事件；item argument 支持沙盒 JSON/SNBT-lite NBT 和 components payload。 |
| `help` | 部分支持 | `modeled` | 输出命令根节点和基础沙盒帮助。 |
| `item` | 部分支持 | `modeled` | `replace entity|block ... with <item> [count]` 和 `from entity|block ...`；`replace` 与 `modify` 会记录可用于 report/assertion 的结构化输出，物品含匹配组件且命中已加载资源时会暴露 equipment asset、banner pattern、instrument、jukebox song 和 armor trim material/pattern 元数据；item argument 支持沙盒 JSON/SNBT-lite NBT 和 components payload；container item-stack NBT 校验接受旧/新版 `Count`/`count` 与 `Slot`/`slot` 别名；entity 槽位覆盖玩家背包、当前主手、`enderchest.*` 槽和非玩家实体装备槽；`modify entity|block ... <modifier>` 会应用常用 item modifier 函数（`set_components`、`set_custom_data`、`set_count`、`limit_count`、`set_item`、`discard`、`set_damage`、`set_name`、`set_lore`、`copy_nbt`、`copy_components`、`filtered`、`reference`、`sequence`）。 |
| `kick` | 空操作 | `observed-noop` | 记录请求的踢出目标和消息为结构化 debug 输出；不移除真实网络会话。 |
| `kill` | 支持 | `modeled` | 移除选中的沙盒实体，并记录可用于 report/assertion 的结构化目标输出；目标维度命中已加载资源时会暴露 dimension 元数据；玩家执行上下文会为非玩家目标触发 `killed_entity` advancement 事件。 |
| `list` | 支持 | `modeled` | 报告沙盒玩家及 UUID。 |
| `locate` | 部分支持 | `modeled` | 接受 `biome`、`structure`、`poi`；虚空世界中报告没有结果。 |
| `loot` | 部分支持 | `modeled` | 支持 `give`、`insert`、`spawn`、`replace entity`、`replace block`，并记录可用于 report/assertion 的结构化 loot 输出；`spawn` 会在当前执行维度创建 item 实体，命中已加载资源时会暴露 dimension 元数据；`replace entity` 可写入玩家背包、当前主手、`enderchest.*` 槽和非玩家实体装备槽；source 支持 `loot <table>`、`fish <table> <pos> [tool]`、`mine <pos> [tool]`，以及实体声明 `DeathLootTable` 时的 `kill <target>`；还支持沙盒上下文 source：`entity <table> <target>`、`block <table> <pos> [tool]`、`equipment <table> <target> <slot>`；entry 覆盖 item、嵌套 loot table、group、alternatives、sequence，以及带嵌套/optional 值的 item tag，其中 `expand=false` 会输出整个 tag，`expand=true` 会把 tag 内物品作为展开候选参与选择；常用函数覆盖 count、item id、discard、components/custom data、工具组件复制、实体名称复制、确定性附魔组件、工具附魔数量奖励、damage、name 和 lore。 |
| `me` | 支持 | `modeled` | 记录为 chat 输出事件；命中已加载的命令 chat type 时会暴露 `chat_type` JSON 元数据。 |
| `msg`、`tell`、`w` | 支持 | `modeled` | 记录为私聊输出事件；命中已加载的命令 chat type 时会暴露 `chat_type` JSON 元数据。 |
| `pardon`、`pardon-ip` | 空操作 | `observed-noop` | 记录请求的 pardon 目标/IP 为结构化 debug 输出；不存储封禁列表状态。 |
| `particle` | 部分支持 | `observed-noop` | 记录为 visual 输出事件；不模拟客户端粒子。 |
| `place` | 部分支持 | `modeled` | `place structure <id> [pos]` 和 `place template <id> [pos] [rotation] [mirror] [integrity] [seed]` 会把已加载的沙盒结构 JSON 资源（`worldgen/structure` 中带 `blocks`/`entities`）展开到 sparse world，并记录实际变化的方块和实体；结构 JSON 可引用 `worldgen/processor_list` 资源执行 `block_ignore` 和简单 rule 替换 processor；template placement 支持确定性的 `none`/90 度旋转、`front_back`/`left_right` 镜像和 integrity 过滤。`place jigsaw <pool> <target> <maxDepth> [pos]` 会解析已加载的 `worldgen/template_pool` single/legacy 结构元素，应用元素 processor，并放置选中的结构。`place feature <id> [pos]` 会解析已加载的 `worldgen/placed_feature`/`worldgen/configured_feature` simple_block JSON，并把对应方块放到命令位置。缺失或不支持的资源仍记录结构化 worldgen intent，`placed=false`。 |
| `playsound` | 部分支持 | `observed-noop` | 记录为 sound 输出事件。 |
| `publish` | 空操作 | `observed-noop` | 接受 `allowCommands`、`gamemode` 和 `port`，把请求的 LAN publish 设置记录为结构化 debug 输出；不执行真实网络发布。 |
| `random` | 部分支持 | `modeled` | `value`、`roll`、`reset`；使用确定性的沙盒随机序列状态，默认混入 world seed，显式 reset seed 时优先使用 reset 值；value/roll/reset 会记录结构化序列状态输出，供断言和 `execute store result` 使用。 |
| `recipe` | 部分支持 | `modeled` | `give`、`take`；支持对已加载数据包 recipe 使用 `*`，更新玩家 recipe 集合并记录 changed 数量和实际变更的 recipe id 列表。 |
| `reload` | 空操作 | `observed-noop` | 原版命令会记录结构化 no-op payload；REPL 工具命令 `reload` 会真正重载数据包并保留世界状态。 |
| `return` | 支持 | `modeled` | 结束当前 function；支持 `return <value>`、`return fail` 和 `return run <command>`，用于 function 条件和 store result 测试。 |
| `ride` | 部分支持 | `modeled` | 记录载具和乘客关系，并记录结构化 mount/dismount 输出；不模拟控制或物理。 |
| `rotate` | 部分支持 | `modeled` | 更新 yaw/pitch，并记录结构化前后旋转输出。 |
| `save-all`、`save-off`、`save-on` | 空操作 | `observed-noop` | 记录请求的存档生命周期动作，包括 `save-all flush`，为结构化 debug 输出；不修改真实文件保存模式。 |
| `say` | 支持 | `modeled` | 记录为 chat 输出事件；命中已加载的命令 chat type 时会暴露 `chat_type` JSON 元数据。 |
| `schedule` | 部分支持 | `modeled` | `schedule function <id> <time> [append|replace]`、`schedule clear <id>`；记录结构化调度和清除输出。 |
| `scoreboard` | 部分支持 | `modeled` | objectives 支持 `add`、`remove`、`list`、`modify`、`setdisplay`；`modify` 会记录 display name、render type 和 display-auto-update 元数据，display slot 会进入 snapshot，修改会记录结构化输出；players 支持 `set`、`add`、`remove`、`get`、`reset`、`list`、`enable`、`operation`；`players get` 会记录结构化 data 输出，供断言和 `execute store result` 使用。 |
| `seed` | 支持 | `modeled` | 报告确定性的沙盒 seed。 |
| `setblock` | 部分支持 | `modeled` | 修改稀疏世界方块状态和方块实体 NBT，并记录结构化前后方块输出；位置参数支持局部坐标；不执行邻居更新。 |
| `setidletimeout` | 空操作 | `observed-noop` | 校验并记录请求的 idle timeout 分钟数为结构化 debug 输出；不模拟玩家空闲踢出。 |
| `setworldspawn` | 部分支持 | `modeled` | 存储世界出生点和角度，并记录结构化出生点输出；命中已加载资源时会暴露 dimension 元数据。 |
| `spawnpoint` | 部分支持 | `modeled` | 存储玩家出生点和角度，并记录结构化目标输出；命中已加载资源时会暴露 dimension 元数据。 |
| `spectate` | 部分支持 | `modeled` | 设置旁观模式并记录目标；不模拟客户端镜头状态。 |
| `spreadplayers` | 部分支持 | `modeled` | 确定性地把选中实体分布到中心附近；不实现原版碰撞/队伍算法。 |
| `stop` | 空操作 | `observed-noop` | 记录结构化 debug 生命周期请求；宿主进程仍然控制运行时，不会被沙盒命令停止。 |
| `stopsound` | 部分支持 | `observed-noop` | 记录为 sound 输出事件。 |
| `summon` | 部分支持 | `modeled` | 在当前执行维度创建带位置、tag 和 schema 校验 NBT 的实体，并记录可用于 report/assertion 的结构化创建输出；命中已加载资源时会暴露 dimension/dimension_type 元数据，以及 cat/chicken/cow/frog/painting/pig/wolf 等实体 variant 元数据；实体 AI 不 tick。 |
| `tag` | 支持 | `modeled` | `add`、`remove`、`list`。 |
| `team` | 部分支持 | `modeled` | `add`、`remove`、`list`、`join`、`leave`、`empty`、`modify`；记录结构化队伍/成员/选项输出，不执行 gameplay 副作用。 |
| `teammsg`、`tm` | 支持 | `modeled` | 记录为 team chat 输出事件；命中已加载的命令 chat type 时会暴露 `chat_type` JSON 元数据。 |
| `teleport`、`tp` | 部分支持 | `modeled` | 坐标传送支持局部坐标、可选旋转、`facing` 和当前执行维度；目标实体传送会复制目标位置、维度和旋转；记录可用于 report/assertion 的结构化移动输出，命中已加载资源时会暴露 from/to dimension 元数据。 |
| `tellraw` | 支持 | `modeled` | 解析 JSON text component 并记录输出事件。 |
| `tick` | 部分支持 | `modeled` | `query`、`rate`、`freeze`、`unfreeze`、`step`、`sprint`、`stop`；更新沙盒 tick 状态，可推进 tick，并记录结构化状态/推进输出用于调试。 |
| `time` | 部分支持 | `modeled` | `set`、`add`、`query daytime|gametime|day`；修改和 query 都会记录结构化 data 输出，供断言和 `execute store result` 使用。 |
| `title` | 支持 | `modeled` | `clear`、`reset`、`title`、`subtitle`、`actionbar`、`times` 输出事件。 |
| `transfer` | 部分支持 | `observed-noop` | 记录请求的 host、port、目标玩家和接受的语法为结构化 debug 输出；不执行真实网络/server transfer。 |
| `trigger` | 部分支持 | `modeled` | `trigger <objective> [add|set] [value]`；使用当前/default 玩家。 |
| `weather` | 部分支持 | `modeled` | `clear`、`rain`、`thunder`；存储状态，并记录结构化天气输出。 |
| `whitelist` | 空操作 | `observed-noop` | 接受 `add`、`remove`、`list`、`on`、`off`、`reload`，记录请求的 whitelist 动作为结构化 debug 输出；不存储白名单状态。 |
| `worldborder` | 部分支持 | `modeled` | `get`、`set`、`add`、`center`、`damage`、`warning`；存储状态，并记录可断言的结构化修改/query 输出。 |

## 输出命令

输出命令会产生确定性的 `OutputEvent`。这些事件可以在 snapshot、REPL、`run`、`check --verbose` 和代码测试 API 中读取：

- chat：`say`、`me`、`msg`、`tell`、`w`、`teammsg`、`tm`、`tellraw`
- title：`title`
- sound：`playsound`、`stopsound`
- visual：`particle`
- data：modeled 命令的结构化状态和 query 输出
- debug：manifest/工具辅助输出，以及 `debug`、`jfr`、`perf`、`transfer`、`publish`、`stop`、`ban`、`whitelist` 等 profiling/网络/生命周期/服务器管理类 observed-noop 请求
- worldgen：`place`
- warning：未支持或 no-op 命令提示

JSON text component 支持 `text`、`score`、`selector`、`translate`、`keybind`、基础 `nbt`、`extra` 和常见格式字段。沙盒会同时保存纯文本和分段 metadata，例如颜色、粗体、斜体等。

## Selector

已实现 selector：`@s`、`@a`、`@p`、`@e`、`@n`。

已实现选项：`type`、`tag`、`name`、`gamemode`、`team`、`nbt`、`predicate`、`scores`、`advancements`、`level`、`x_rotation`、`y_rotation`、`limit`、`sort`、`distance`、`x`、`y`、`z`、`dx`、`dy`、`dz`；score 和等级过滤支持 `scores={kills=1..,deaths=..0}`、`level=..5` 这类整数范围，advancement 过滤按玩家当前进度支持整体完成或 criterion 布尔匹配，NBT 过滤使用包含式对象匹配和数值等值比较，predicate 过滤会把候选实体作为 `this` 上下文并使用候选实体的位置/维度交给已加载数据包 predicate 引擎评估，支持 `!` 取反，旋转过滤支持带符号数值范围；`sort=random` 使用基于原点的确定性顺序，便于重复测试。未支持的 selector 选项会按当前 unsupported 策略产生诊断或 warning。

## 世界与 NBT 说明

- 初始世界是稀疏虚空。
- 方块只有在显式放置、fixture 定义或从存档导入后才存在。
- 方块和实体 NBT 写入会使用生成的 vanilla mcdoc schema 校验；未知顶层自定义字段会失败。
- 玩家 NBT 可读但不可通过 `data` 写入；视图包含当前非空主手 `SelectedItem`，玩家状态应通过命令或事件改变。
- 不模拟实体 AI、重力、红石、方块更新、破坏方块掉落和完整战斗系统。

## 沙盒专用 CLI/REPL 命令

这些是工具命令，不是原版命令：

| 命令 | 行为等级 | 用途 |
|---|---:|---|
| `event player <name> <type> ...` | `modeled` | 注入玩家事件，用于 advancement/predicate 测试，并更新可观察玩家状态，例如消耗/拾取物品、维度、health、recipe 和输入元数据。 |
| `player <name>` | `modeled` | 创建或复用沙盒玩家。 |
| `inspect <...>` | `modeled` | 查看世界状态、世界边界、score、storage、gamerule、random sequence、scheduled function、强加载 chunk、scoreboard objective/display、team、bossbar、entity state、blocks、biome override、player、玩家物品槽、玩家 recipe、advancement progress、loot、predicate、advancement、recipe、item_modifier、raw JSON resource、tags、resource index、registry group、outputs 和玩家事件 trace。 |
| `snapshot [file]` | `modeled` | 打印或写出确定性的世界 JSON。 |
| `help` | `modeled` | 显示 REPL 帮助。 |
| `exit`、`quit` | `modeled` | 退出 REPL。 |
| `reload` | `observed-noop` | REPL 专用，保留世界状态并重载数据包文件。 |
| `load` | `modeled` | 在 REPL 中运行 `#minecraft:load`。 |
| `load fixture <file>` | `modeled` | 在 REPL 中应用 manifest-style world JSON fixture。 |
| `tick [n]` | `modeled` | 在 REPL 中推进 tick。 |
| `trace <on|off|status>` | `modeled` | 开关 REPL 对新执行命令的自动 trace 输出。 |
| `diff last` | `modeled` | 输出上一条被跟踪 REPL 命令执行前后的 snapshot diff。 |
| `rerun last` | `modeled` | 重新执行上一条被跟踪 REPL 命令。 |
| `reset world` | `modeled` | 用全新的 sparse world 替换当前 REPL 世界。 |
| CLI `loot --table <id> --context <context>` | | 直接生成 loot table。 |
| CLI `run --trace --trace-filter <filter>` | | 只打印或写出匹配的 trace 事件；过滤器支持 `root=`、`command=`、`contains=`、`function=`、`file=`、`selector=`/`target=`、`success=`、`error=`/`diagnostic=`、`error-code=`/`diagnostic-code=`、`error-message=`/`diagnostic-message=`、文本型 `output=`、数量/布尔型 `outputs=`、`output-channel=`、`output-payload=<path>[=<json>]`、`diff=`、`path=`、`score=` 和 `storage=`。Trace JSONL 条目会包含每条命令产生的输出事件和 snapshot diff。 |
| CLI `run --outputs-file <file>` | | 将可观察输出事件写成 JSONL，适合作为 CI artifact 或命令生成器回归测试产物。 |
| CLI `run --report-file <file>` | | 写出综合 JSON 报告，包括通过状态、断言失败、输出、trace、从失败 trace 提取的 diagnostics、事件 trace、最终 snapshot、snapshot diff 和资源摘要明细。 |
| CLI `run --resources` | | 在轻量验包中打印确定性的资源数量、覆盖诊断和直接缺失资源引用。 |
| CLI `resources --pack <path>` | | 检查一个或多个数据包的已加载资源索引，包含类型、namespace/id、来源文件、pack 标签、加载顺序、active/overridden 状态、覆盖关系、过滤器（`--type`、`--id`、`--namespace`、`--source-pack`、`--order-min`、`--order-max`、`--active-only`、`--overridden-only`）以及 JSON artifact 输出。 |
| CLI `resources --registry` | | 导出 version profile 的 registry group 和条目，可用 `--registry-group <group>` 过滤，并支持纯文本或 JSON。 |
| CLI `run --snapshot-diff` | | 输出执行前后的状态差异，可配合 `--snapshot-diff-file` 写出 JSON。 |
| CLI `run --stdin` | | 从标准输入读取 `.mcfunction` 文本；`--stdin-mode commands` 会按原始命令行执行 stdin。 |
| CLI `run --command-file <file>` | | 按参数顺序执行一个或多个原始命令文件，适合命令生成器输出。 |
| CLI `run --event "<event>"` | | 在轻量 run 流程中注入玩家事件，格式为 `player <name> <type> [id] [detail/action\|x y z\|pos=x,y,z]`，之后可断言玩家状态、sparse-world 方块变化或 `eventTrace`。 |
| CLI `run --event-file <file>` | | 按文件中的非空、非注释行逐条注入玩家事件，格式与 `--event` 相同。 |
| CLI `run --event-trace-file <file>` | | 写出玩家事件 trace JSONL，适合作为事件驱动数据包调试和 CI artifact。 |
| CLI `run --seed <long>` | | 在 world fixture 应用后覆盖轻量运行的世界 seed。 |
| CLI `run --world` | | 执行前应用 manifest-style world JSON fixture，包括 fixture 引用链。 |
| CLI `run --strict` | | 将未支持的原版命令视为错误，并让直接缺失资源引用导致运行失败，无需同时写 `--unsupported error` 和 `--fail-on-missing-resources`。 |
| CLI `run --allow-command-failure` | | 直接 `--command`、`--command-file` 或 `--stdin-mode commands` 报错后继续执行，便于轻量测试断言预期 diagnostic，同时继续检查后续输出或状态。 |
| CLI `run --max-commands`、`--max-function-depth`、`--max-ticks-per-run`、`--max-output-events`、`--max-snapshot-bytes` | | 覆盖轻量 run 的 sandbox 安全边界，用于阻止 runaway 生成命令、递归函数、过大的 tick 请求、无限输出或巨大 snapshot。 |
| CLI `run --assert`、`run --assert-file` | | 执行后评估内联或文件形式的 manifest assertion，包括最终 `snapshot` 等值/存在性断言，以及需要执行前后上下文的 `snapshotDiff` 断言。Manifest output assertion 支持 exact、contains、正则 `matches`、normalized text、normalized regex、segment、payload、count 和 order 字段；storage 和 NBT/component path expectation 支持 `equals`、`exists`/`missing`、`contains` 和正则 `matches`。`--assert-file` 支持 JSON object/array 文件，也支持非空、非注释行逐行写 shorthand。简写包括 `score:<target>:<objective>=N`、`score:<target>:<objective>>=N`、`score:<target>:<objective><=N`、`storage:<id>[:<path>]=<json>`、`storage:<id>[:<path>]?`、`storage:<id>[:<path>]!`、`advancement:<player>:<id>[=<true\|false>][:done=<true\|false>][:criterion=<name>][:criterionDone=<true\|false>]`、`predicate:<id>[=<true\|false>][:player=<name>][:equals=<true\|false>]`、`loot:<table>[:context=<id>][:player=<name>][:seed=N][:count=N][:item=<id>]`、`player:<name>[:<field>=<value>]`、`world:<field>=<value>`、`gamerule:<rule>=<value>`、`gamerule:<rule>?`、`gamerule:<rule>!`、`random-sequence:<name>=N`、`snapshot:<path>=<json>`、`snapshot:<path>?`、`snapshot:<path>!`、`block:<x>,<y>,<z>=<id>`、`block:<x>,<y>,<z>?`、`block:<x>,<y>,<z>!`、`biome:<x>,<y>,<z>=<id>`、`team:<name>?`、`team:<name>!`、`team:<name>@<member>`、`team:<name>=N`、`bossbar:<id>?`、`bossbar:<id>!`、`bossbar:<id>:<field>=<value>`、`item:<player>:<id>[@slot]=N`、`entity:<type|*>[@tag]=N`、`diff:<json-pointer>[=<kind>]`、`event-trace:<player>:<type>[@x,y,z][=N]`、`trace:<root>=N`、`trace:<text>`、`trace-output:<text>[@target]`、`diagnostic=N`、`diagnostic:<code>=N`、`diagnostic:<code>:<text>[=N]`、`warning=N`、`warning:<text>`、`unsupported=N`、`unsupported:<text>`、`output:<text>`、`output-count:<text>=N`、`output-order:<N>:<text>`、`output-exact:<text>`、`output-matches:<regex>`、`output-command:<command>=N`、`output-command:<command>?`、`output-command:<command>!`、`output-channel:<channel>=N`、`output-channel:<channel>?`、`output-channel:<channel>!`、`output-target:<target>=N`、`output-target:<target>?`、`output-target:<target>!`、`output-normalized:<text>`、`output-normalized-exact:<text>`、`output-normalized-matches:<regex>`、`output-segment:<text>[|color=<color>|bold=<true\|false>][@target]`、`output-segment-exact:<text>[|color=<color>|bold=<true\|false>][@target]`、`output-segment-matches:<regex>[|color=<color>|bold=<true\|false>][@target]` 和 `output-payload:<command>:<path>[=<json>]`。 |
| Assertion shorthand `scheduled:<id>` | | 用 `scheduled:<id>=<dueTick>`、`scheduled:<id>?` 或 `scheduled:<id>!` 检查 scheduled function snapshot 状态。 |
| Manifest assertion `gamerule` | | 用类型化 `.dps.json` 字段检查已保存的 gamerule 值和缺失规则。 |
| Manifest assertions `randomSequence` / `forcedChunk` | | 用类型化 `.dps.json` 字段检查确定性随机序列状态和强加载 chunk 存在性。 |
| Assertion shorthand `scoreboard-objective:<name>` | | 用 `scoreboard-objective:<name>?`、`scoreboard-objective:<name>!` 或 `scoreboard-objective:<name>:<field>=<value>` 检查 objective 元数据，字段支持 `criteria`、`displayName`、`renderType` 和 `displayAutoUpdate`。 |
| Assertion shorthand `scoreboard-display:<slot>` | | 用 `scoreboard-display:<slot>=<objective>`、`scoreboard-display:<slot>?` 或 `scoreboard-display:<slot>!` 检查 objective display slot，支持 `sidebar.team.red` 这类带点 slot。 |
| Manifest assertions `scoreboardObjective` / `scoreboardDisplay` | | 用类型化 `.dps.json` 字段检查 objective 元数据和 display slot，不必手写 snapshot path。 |
| Assertion shorthand `forced-chunk:<x>,<z>` / `forceload:<x>,<z>` | | 用 `forced-chunk:<x>,<z>?`、`forced-chunk:<x>,<z>!` 或 `forceload:` 别名检查最终 forced chunk snapshot 状态。 |
| CLI `diff <before.json> <after.json>` / `diff --script <manifest.dps.json>` | | 比较两份确定性 JSON snapshot 或 report，并输出字段级 JSON Pointer 差异；`--snapshot` 可从 run/check report 抽取单个 `snapshot` 字段，`--state` 可忽略 trace bookkeeping，`--json --output <file>` 写出差异 artifact，`--check` 在存在差异时返回非零退出码。`--script --output <file>` 可把 manifest 命令/函数步骤导出为外部原版或第三方差分 harness 可重放的命令脚本，并把沙盒专用步骤保留为注释。 |
| CLI `benchmark` | | 运行内置性能 smoke 场景，覆盖 scoreboard 批量写入、大 storage merge、函数调用链和批量 manifest 执行；可选 `--pack` 测量 pack 加载，可选 `--loot-table` 抽样 loot 生成，`--json --output <file>` 可写出 benchmark artifact。 |
| CLI `run --fail-on-missing-resources` | | 在轻量 run 中把 load/tick 标签、advancement parent/reward、predicate/loot/item modifier 资源中的 predicate reference 和嵌套 loot table 的直接缺失资源引用视为失败，适合在编写完整 manifest 前快速验包。 |
| CLI `check <manifest-or-directory>` | | 运行 `.dps.json` 清单；manifest `include` 可共享 world fixture、steps、assertions 和公共/default pack matrix，并会把公共 pack 排在 case-local pack 之前；`--validate-schema` 会在执行前校验 manifest 结构；`--fail-on-missing-resources` 会把直接资源缺失引用视为失败；`--strict` 会组合 schema 校验、unsupported-command error 和直接缺失引用失败；`--max-commands`、`--max-function-depth`、`--max-ticks-per-run`、`--max-output-events` 和 `--max-snapshot-bytes` 会覆盖每次 attempt 的 sandbox 安全边界；`--verbose` 会打印资源摘要、覆盖条目、缺失引用和输出事件；可用 `--snapshot-diff-on-fail` 输出状态差异，也可用 `--trace-file`、`--trace-filter`、`--outputs-file`、`--event-trace-file` 和 `--report-file` 写出 CI artifact。Report JSON 会按 attempt 包含输出、命令 trace、从失败 trace 提取的 diagnostics、玩家事件 trace、最终 snapshot、snapshot diff 和资源摘要明细。 |
| CLI `schema [--output <file>]` | | 打印或写出内置 `.dps.json` manifest JSON Schema，用于编辑器和 CI 集成。 |

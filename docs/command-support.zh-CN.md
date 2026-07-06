# 命令支持状态

默认 profile：Minecraft Java `26.2`。兼容 profile 覆盖到 `1.20.4`。

这个沙盒不嵌入原版服务端。这里的“支持”表示：命令会按沙盒当前建模的、数据包可见状态执行，并产生确定性结果。网络、权限、世界生成、客户端 UI、红石、实体 AI、完整战斗系统和真实服务端生命周期不在运行时范围内。

未支持的原版根命令默认不会让运行失败。默认策略是 `warn`：记录 warning 输出事件并继续执行。需要严格验证时可以使用 CLI `--unsupported error`、清单 `"unsupported": "error"` 或 API `UnsupportedFeatureMode.ERROR`；需要静默跳过时使用 `ignore`。

## 状态说明

| 状态 | 含义 |
|---|---|
| 支持 | 主要数据包可见行为已经实现。 |
| 部分支持 | 对本地测试有用的行为已经实现，但原版副作用不完整。 |
| 空操作 | 命令会被接受和记录，但沙盒没有对应的可变原版状态。 |
| 未支持 | 未实现；按当前 unsupported 策略处理。 |

## 原版命令矩阵

| 命令 | 状态 | 已实现形式 / 沙盒行为 |
|---|---:|---|
| `advancement` | 部分支持 | `grant`、`revoke`、`test`；`test` 会记录通过数量和逐玩家结果 payload；每个玩家独立记录进度；奖励支持 function、loot、XP 和 recipe。 |
| `attribute` | 部分支持 | `get`、`base get`、`base set`、`base reset`；get 命令会记录结构化 data 输出，供断言和 `execute store result` 使用；modifier 子命令作为 no-op warning 接受。 |
| `ban`、`ban-ip`、`banlist` | 未支持 | 不模拟服务器封禁列表。 |
| `bossbar` | 部分支持 | `add`、`remove`、`list`、`get`、`set`；`get` 会记录结构化 data 输出，供断言和 `execute store result` 使用；状态进入 snapshot，不模拟客户端 UI。 |
| `clear` | 部分支持 | 从沙盒玩家背包移除匹配物品，支持 JSON/SNBT-lite NBT 和 components payload 过滤，记录 matched/removed 数量；`maxCount=0` 作为只查询不删除的检查。 |
| `clone` | 部分支持 | 复制稀疏世界中的方块状态和方块实体 NBT，并记录结构化复制/变化位置输出；不执行更新、掉落或重叠区物理。 |
| `damage` | 部分支持 | 降低实体或玩家生命值，发出沙盒 damage/death advancement 事件，并记录结构化生命值变化输出；不计算盔甲、无敌帧、死亡掉落和完整战斗规则。 |
| `data` | 部分支持 | 支持带可选数值 scale 的 `get`，以及 `merge`、`modify`、`remove`，目标支持 `storage`、`entity`、`block`；写入类操作会记录结构化前后输出；path 支持字段、正/负数字索引和简单对象匹配；`modify` 支持 `value`、`from` 和 `string` 来源；顶层 NBT 字段经过 schema 校验。 |
| `datapack` | 部分支持 | `list` 报告已加载 typed/raw/tag/resource-index 资源数量和资源覆盖诊断；`enable`/`disable` 作为 no-op 接受，因为沙盒创建后 pack 顺序固定。 |
| `debug`、`jfr`、`perf` | 未支持 | 原版 profiling 不适用于此运行时。 |
| `defaultgamemode` | 支持 | 存储世界默认游戏模式，并记录结构化前后输出。 |
| `difficulty` | 支持 | 存储并报告世界难度，并记录结构化前后输出。 |
| `deop`、`op` | 未支持 | 不模拟权限系统。 |
| `effect` | 部分支持 | `give`、`clear`；更新玩家效果状态并触发相关 advancement 事件，也会更新非玩家实体 active effects，并通过 snapshot 和 `ActiveEffects` NBT 暴露；记录可用于 report/assertion 的结构化输出。 |
| `enchant` | 部分支持 | 向玩家选中物品和非玩家实体主手装备写入附魔组件，并记录可用于 report/assertion 的结构化输出；不检查可附魔性。 |
| `execute` | 部分支持 | 支持 `as`、`at`、`positioned <pos>`、`positioned as <selector>`、`align`、`anchored`、`facing`、`in`、`rotated`、`store`、`if`、`unless`、`run` 的核心路径；`as` 只切换执行者，`at` 会把执行位置、维度和旋转移动到目标实体，`positioned as` 只移动执行位置；`align` 会对校验过的 `x`/`y`/`z` 轴取整；`rotated` 和 `facing` 会更新命令旋转上下文，供 `tp` 的相对旋转参数和局部坐标使用；`anchored` 会更新局部坐标基准点；`store` 目标覆盖 score、storage、entity NBT、block NBT 和 bossbar value/max；条件覆盖 `entity`、`score`、`data`、`block`、`blocks`、`predicate`、`function`、`dimension`、`biome` 和 `loaded`。 |
| `experience`、`xp` | 部分支持 | `add`、`set`、`query`；沙盒内 points/levels 共用玩家 XP 整数字段；`query` 会记录结构化 data 输出，供断言和 `execute store result` 使用。 |
| `fill` | 部分支持 | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`；记录结构化变化位置输出；位置参数支持局部坐标；不执行更新或掉落。 |
| `fillbiome` | 部分支持 | 为显式方块范围记录 biome 覆盖；不模拟区块 biome 容器或生成效果。 |
| `forceload` | 部分支持 | `add`、`remove`、`remove all`、`query`、`query <pos>`；记录强加载 chunk 坐标，并为 query 记录结构化输出。 |
| `function` | 支持 | `function <id>`。 |
| `gamemode` | 支持 | `gamemode <mode> [targets]`；更新沙盒玩家游戏模式。 |
| `gamerule` | 部分支持 | 存储任意 gamerule 字符串值，并为查询记录结构化输出；不执行具体游戏规则副作用。 |
| `give` | 部分支持 | 向玩家背包添加物品，记录可用于 report/assertion 的结构化输出，并触发 inventory advancement 事件；item argument 支持沙盒 JSON/SNBT-lite NBT 和 components payload。 |
| `help` | 部分支持 | 输出命令根节点和基础沙盒帮助。 |
| `item` | 部分支持 | `replace entity|block ... with <item> [count]` 和 `from entity|block ...`；`replace` 与 `modify` 会记录可用于 report/assertion 的结构化输出；item argument 支持沙盒 JSON/SNBT-lite NBT 和 components payload；entity 槽位覆盖玩家背包、当前主手、`enderchest.*` 槽和非玩家实体装备槽；`modify entity|block ... <modifier>` 会应用常用 item modifier 函数（`set_components`、`set_custom_data`、`set_count`、`limit_count`、`set_item`、`discard`、`set_damage`、`set_name`、`set_lore`、`filtered`、`reference`、`sequence`）。 |
| `kick` | 未支持 | 不模拟网络会话。 |
| `kill` | 支持 | 移除选中的沙盒实体，并记录可用于 report/assertion 的结构化目标输出；玩家执行上下文会为非玩家目标触发 `killed_entity` advancement 事件。 |
| `list` | 支持 | 报告沙盒玩家及 UUID。 |
| `locate` | 部分支持 | 接受 `biome`、`structure`、`poi`；虚空世界中报告没有结果。 |
| `loot` | 部分支持 | 支持 `give`、`insert`、`spawn`、`replace entity`、`replace block`，并记录可用于 report/assertion 的结构化 loot 输出；`spawn` 会在当前执行维度创建 item 实体；`replace entity` 可写入玩家背包、当前主手、`enderchest.*` 槽和非玩家实体装备槽；source 支持 `loot <table>`、`fish <table> <pos> [tool]`、`mine <pos> [tool]`，以及实体声明 `DeathLootTable` 时的 `kill <target>`；还支持沙盒上下文 source：`entity <table> <target>`、`block <table> <pos> [tool]`、`equipment <table> <target> <slot>`；常用函数覆盖 count、item id、discard、components/custom data、damage、name 和 lore。 |
| `me` | 支持 | 记录为 chat 输出事件。 |
| `msg`、`tell`、`w` | 支持 | 记录为私聊输出事件。 |
| `pardon`、`pardon-ip` | 未支持 | 不模拟服务器封禁管理。 |
| `particle` | 部分支持 | 记录为 visual 输出事件；不模拟客户端粒子。 |
| `place` | 未支持 | 不模拟结构、地物或世界生成放置。 |
| `playsound` | 部分支持 | 记录为 sound 输出事件。 |
| `publish` | 未支持 | 不模拟 LAN/网络发布。 |
| `random` | 部分支持 | `value`、`roll`、`reset`；使用确定性的沙盒随机序列状态，默认混入 world seed，显式 reset seed 时优先使用 reset 值。 |
| `recipe` | 部分支持 | `give`、`take`；支持对已加载数据包 recipe 使用 `*`，更新玩家 recipe 集合并记录 changed 数量。 |
| `reload` | 空操作 | 原版命令作为 no-op 记录；REPL 工具命令 `reload` 会真正重载数据包并保留世界状态。 |
| `return` | 支持 | 结束当前 function；支持 `return <value>`、`return fail` 和 `return run <command>`，用于 function 条件和 store result 测试。 |
| `ride` | 部分支持 | 记录载具和乘客关系，并记录结构化 mount/dismount 输出；不模拟控制或物理。 |
| `rotate` | 部分支持 | 更新 yaw/pitch，并记录结构化前后旋转输出。 |
| `save-all`、`save-off`、`save-on` | 未支持 | 沙盒没有真实存档生命周期。 |
| `say` | 支持 | 记录为 chat 输出事件。 |
| `schedule` | 部分支持 | `schedule function <id> <time> [append|replace]`、`schedule clear <id>`；记录结构化调度和清除输出。 |
| `scoreboard` | 部分支持 | objectives 支持 `add`、`remove`、`list`；players 支持 `set`、`add`、`remove`、`get`、`reset`、`list`、`enable`、`operation`；`players get` 会记录结构化 data 输出，供断言和 `execute store result` 使用。 |
| `seed` | 支持 | 报告确定性的沙盒 seed。 |
| `setblock` | 部分支持 | 修改稀疏世界方块状态和方块实体 NBT，并记录结构化前后方块输出；位置参数支持局部坐标；不执行邻居更新。 |
| `setidletimeout` | 未支持 | 服务器管理命令。 |
| `setworldspawn` | 部分支持 | 存储世界出生点和角度，并记录结构化出生点输出。 |
| `spawnpoint` | 部分支持 | 存储玩家出生点和角度，并记录结构化目标输出。 |
| `spectate` | 部分支持 | 设置旁观模式并记录目标；不模拟客户端镜头状态。 |
| `spreadplayers` | 部分支持 | 确定性地把选中实体分布到中心附近；不实现原版碰撞/队伍算法。 |
| `stop` | 未支持 | 运行时生命周期由宿主进程控制。 |
| `stopsound` | 部分支持 | 记录为 sound 输出事件。 |
| `summon` | 部分支持 | 在当前执行维度创建带位置、tag 和 schema 校验 NBT 的实体，并记录可用于 report/assertion 的结构化创建输出；实体 AI 不 tick。 |
| `tag` | 支持 | `add`、`remove`、`list`。 |
| `team` | 部分支持 | `add`、`remove`、`list`、`join`、`leave`、`empty`、`modify`；不执行 gameplay 副作用。 |
| `teammsg`、`tm` | 支持 | 记录为 team chat 输出事件。 |
| `teleport`、`tp` | 部分支持 | 坐标传送支持局部坐标、可选旋转、`facing` 和当前执行维度；目标实体传送会复制目标位置、维度和旋转；记录可用于 report/assertion 的结构化移动输出。 |
| `tellraw` | 支持 | 解析 JSON text component 并记录输出事件。 |
| `tick` | 部分支持 | `query`、`rate`、`freeze`、`unfreeze`、`step`、`sprint`、`stop`；更新沙盒 tick 状态，可推进 tick。 |
| `time` | 部分支持 | `set`、`add`、`query daytime|gametime|day`；修改和 query 都会记录结构化 data 输出，供断言和 `execute store result` 使用。 |
| `title` | 支持 | `clear`、`reset`、`title`、`subtitle`、`actionbar`、`times` 输出事件。 |
| `transfer` | 未支持 | 不模拟网络/server transfer。 |
| `trigger` | 部分支持 | `trigger <objective> [add|set] [value]`；使用当前/default 玩家。 |
| `weather` | 部分支持 | `clear`、`rain`、`thunder`；存储状态，并记录结构化天气输出。 |
| `whitelist` | 未支持 | 服务器管理命令。 |
| `worldborder` | 部分支持 | `get`、`set`、`add`、`center`、`damage`、`warning`；只存储状态。 |

## 输出命令

输出命令会产生确定性的 `OutputEvent`。这些事件可以在 snapshot、REPL、`run`、`check --verbose` 和代码测试 API 中读取：

- chat：`say`、`me`、`msg`、`tell`、`w`、`teammsg`、`tm`、`tellraw`
- title：`title`
- sound：`playsound`、`stopsound`
- visual：`particle`
- warning：未支持或 no-op 命令提示

JSON text component 支持 `text`、`score`、`selector`、`translate`、`keybind`、基础 `nbt`、`extra` 和常见格式字段。沙盒会同时保存纯文本和分段 metadata，例如颜色、粗体、斜体等。

## Selector

已实现 selector：`@s`、`@a`、`@p`、`@e`、`@n`。

常用选项：`type`、`tag`、`name`、`limit`、`sort`、`distance`、`x`、`y`、`z`、`dx`、`dy`、`dz`。未支持的 selector 选项会按当前 unsupported 策略产生诊断或 warning。

## 世界与 NBT 说明

- 初始世界是稀疏虚空。
- 方块只有在显式放置、fixture 定义或从存档导入后才存在。
- 方块和实体 NBT 写入会使用生成的 vanilla mcdoc schema 校验；未知顶层自定义字段会失败。
- 玩家 NBT 可读但不可通过 `data` 写入；视图包含当前非空主手 `SelectedItem`，玩家状态应通过命令或事件改变。
- 不模拟实体 AI、重力、红石、方块更新、破坏方块掉落和完整战斗系统。

## 沙盒专用 CLI/REPL 命令

这些是工具命令，不是原版命令：

| 命令 | 用途 |
|---|---|
| `event player <name> <type> ...` | 注入玩家事件，用于 advancement/predicate 测试。 |
| `player <name>` | 创建或复用沙盒玩家。 |
| `inspect <...>` | 查看 score、storage、entities、blocks、player、loot、predicate、advancement、recipe、item_modifier、raw JSON resource、tags、resource index、registry、outputs。 |
| `snapshot [file]` | 打印或写出确定性的世界 JSON。 |
| `reload` | REPL 专用，保留世界状态并重载数据包文件。 |
| `load` | 在 REPL 中运行 `#minecraft:load`。 |
| `load fixture <file>` | 在 REPL 中应用 manifest-style world JSON fixture。 |
| `tick [n]` | 在 REPL 中推进 tick。 |
| `trace <on|off|status>` | 开关 REPL 对新执行命令的自动 trace 输出。 |
| `diff last` | 输出上一条被跟踪 REPL 命令执行前后的 snapshot diff。 |
| `rerun last` | 重新执行上一条被跟踪 REPL 命令。 |
| `reset world` | 用全新的 sparse world 替换当前 REPL 世界。 |
| CLI `loot --table <id> --context <context>` | 直接生成 loot table。 |
| CLI `run --trace --trace-filter <filter>` | 只打印或写出匹配的 trace 事件；过滤器支持 `root=`、`command=`、`contains=`、`function=`、`file=`、`success=`、`outputs=`、`diff=`、`path=`、`score=` 和 `storage=`。Trace JSONL 条目会包含每条命令造成的 snapshot diff。 |
| CLI `run --outputs-file <file>` | 将可观察输出事件写成 JSONL，适合作为 CI artifact 或命令生成器回归测试产物。 |
| CLI `run --report-file <file>` | 写出综合 JSON 报告，包括通过状态、断言失败、输出、trace、事件 trace、最终 snapshot、snapshot diff 和资源摘要明细。 |
| CLI `run --resources` | 在轻量验包中打印确定性的资源数量、覆盖诊断和直接缺失资源引用。 |
| CLI `run --snapshot-diff` | 输出执行前后的状态差异，可配合 `--snapshot-diff-file` 写出 JSON。 |
| CLI `run --stdin` | 从标准输入读取 `.mcfunction` 文本；`--stdin-mode commands` 会按原始命令行执行 stdin。 |
| CLI `run --command-file <file>` | 按参数顺序执行一个或多个原始命令文件，适合命令生成器输出。 |
| CLI `run --event "<event>"` | 在轻量 run 流程中注入玩家事件，格式为 `player <name> <type> [id] [detail/action]`，之后可断言玩家状态或 `eventTrace`。 |
| CLI `run --event-file <file>` | 按文件中的非空、非注释行逐条注入玩家事件，格式与 `--event` 相同。 |
| CLI `run --event-trace-file <file>` | 写出玩家事件 trace JSONL，适合作为事件驱动数据包调试和 CI artifact。 |
| CLI `run --seed <long>` | 在 world fixture 应用后覆盖轻量运行的世界 seed。 |
| CLI `run --world` | 执行前应用 manifest-style world JSON fixture，包括 fixture 引用链。 |
| CLI `run --assert`、`run --assert-file` | 执行后评估内联或文件形式的 manifest assertion，包括需要执行前后上下文的 `snapshotDiff` 断言。`--assert-file` 支持 JSON object/array 文件，也支持非空、非注释行逐行写 shorthand。简写包括 `score:<target>:<objective>=N`、`score:<target>:<objective>>=N`、`score:<target>:<objective><=N`、`storage:<id>[:<path>]=<json>`、`storage:<id>[:<path>]?`、`storage:<id>[:<path>]!`、`player:<name>[:<field>=<value>]`、`item:<player>:<id>[@slot]=N`、`entity:<type|*>[@tag]=N`、`trace:<root>=N`、`trace:<text>`、`warning=N`、`warning:<text>` 和 `output:<text>`。 |
| CLI `run --fail-on-missing-resources` | 在轻量 run 中把 load/tick 标签、advancement parent/reward、predicate/loot/item modifier 资源中的 predicate reference 和嵌套 loot table 的直接缺失资源引用视为失败，适合在编写完整 manifest 前快速验包。 |
| CLI `check <manifest-or-directory>` | 运行 `.dps.json` 清单；`--validate-schema` 会在执行前校验 manifest 结构；`--fail-on-missing-resources` 会把直接资源缺失引用视为失败；`--verbose` 会打印资源摘要、覆盖条目、缺失引用和输出事件；可用 `--snapshot-diff-on-fail` 输出状态差异，也可用 `--trace-file`、`--trace-filter`、`--outputs-file`、`--event-trace-file` 和 `--report-file` 写出 CI artifact。Report JSON 会按 attempt 包含输出、命令 trace、玩家事件 trace、最终 snapshot、snapshot diff 和资源摘要明细。 |
| CLI `schema [--output <file>]` | 打印或写出内置 `.dps.json` manifest JSON Schema，用于编辑器和 CI 集成。 |

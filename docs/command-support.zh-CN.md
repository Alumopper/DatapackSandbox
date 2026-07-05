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
| `advancement` | 部分支持 | `grant`、`revoke`、`test`；每个玩家独立记录进度；奖励支持 function、loot、XP 和 recipe。 |
| `attribute` | 部分支持 | `get`、`base get`、`base set`、`base reset`；modifier 子命令作为 no-op warning 接受。 |
| `ban`、`ban-ip`、`banlist` | 未支持 | 不模拟服务器封禁列表。 |
| `bossbar` | 部分支持 | `add`、`remove`、`list`、`get`、`set`；状态进入 snapshot，不模拟客户端 UI。 |
| `clear` | 部分支持 | 从沙盒玩家背包移除匹配物品。 |
| `clone` | 部分支持 | 复制稀疏世界中的方块状态和方块实体 NBT；不执行更新、掉落或重叠区物理。 |
| `damage` | 部分支持 | 降低实体或玩家生命值；不计算盔甲、无敌帧、死亡掉落和完整战斗规则。 |
| `data` | 部分支持 | `get`、`merge`、`modify`、`remove`，目标支持 `storage`、`entity`、`block`；`modify` 支持 `value` 和 `from` 来源；顶层 NBT 字段经过 schema 校验。 |
| `datapack` | 部分支持 | `list` 报告已加载资源数量；`enable`/`disable` 作为 no-op 接受，因为沙盒创建后 pack 顺序固定。 |
| `debug`、`jfr`、`perf` | 未支持 | 原版 profiling 不适用于此运行时。 |
| `defaultgamemode` | 支持 | 存储世界默认游戏模式。 |
| `difficulty` | 支持 | 存储并报告世界难度。 |
| `deop`、`op` | 未支持 | 不模拟权限系统。 |
| `effect` | 部分支持 | `give`、`clear`；更新玩家效果状态并触发相关 advancement 事件。 |
| `enchant` | 部分支持 | 向选中物品写入附魔组件；不检查可附魔性。 |
| `execute` | 部分支持 | 支持 `as`、`at`、`positioned`、`align`、`anchored`、`facing`、`in`、`rotated`、`store`、`if`、`unless`、`run` 的核心路径；条件覆盖 `entity`、`score`、`data`、`block`、`blocks`、`predicate`、`dimension`、`biome` 和 `loaded`。 |
| `experience`、`xp` | 部分支持 | `add`、`set`、`query`；沙盒内 points/levels 共用玩家 XP 整数字段。 |
| `fill` | 部分支持 | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`；不执行更新或掉落。 |
| `fillbiome` | 部分支持 | 为显式方块范围记录 biome 覆盖；不模拟区块 biome 容器或生成效果。 |
| `forceload` | 部分支持 | `add`、`remove`、`remove all`、`query`；记录强加载 chunk 坐标。 |
| `function` | 支持 | `function <id>`。 |
| `gamemode` | 支持 | `gamemode <mode> [targets]`；更新沙盒玩家游戏模式。 |
| `gamerule` | 部分支持 | 存储任意 gamerule 字符串值；不执行具体游戏规则副作用。 |
| `give` | 部分支持 | 向玩家背包添加物品并触发 inventory advancement 事件。 |
| `help` | 部分支持 | 输出命令根节点和基础沙盒帮助。 |
| `item` | 部分支持 | `replace entity <targets> <slot> with <item> [count]`；`modify` 作为资源钩子 no-op 接受。 |
| `kick` | 未支持 | 不模拟网络会话。 |
| `kill` | 支持 | 移除选中的沙盒实体。 |
| `list` | 支持 | 报告沙盒玩家及 UUID。 |
| `locate` | 部分支持 | 接受 `biome`、`structure`、`poi`；虚空世界中报告没有结果。 |
| `loot` | 部分支持 | 支持 `give`、`insert`、`spawn`、`replace entity`、`replace block`；source 支持 `loot <table>`、`fish <table> <pos> [tool]`、`mine <pos> [tool]`，以及实体声明 `DeathLootTable` 时的 `kill <target>`。 |
| `me` | 支持 | 记录为 chat 输出事件。 |
| `msg`、`tell`、`w` | 支持 | 记录为私聊输出事件。 |
| `pardon`、`pardon-ip` | 未支持 | 不模拟服务器封禁管理。 |
| `particle` | 部分支持 | 记录为 visual 输出事件；不模拟客户端粒子。 |
| `place` | 未支持 | 不模拟结构、地物或世界生成放置。 |
| `playsound` | 部分支持 | 记录为 sound 输出事件。 |
| `publish` | 未支持 | 不模拟 LAN/网络发布。 |
| `random` | 部分支持 | `value`、`roll`、`reset`；使用确定性的沙盒随机序列状态。 |
| `recipe` | 部分支持 | `give`、`take`；更新玩家 recipe 集合。 |
| `reload` | 空操作 | 原版命令作为 no-op 记录；REPL 工具命令 `reload` 会真正重载数据包并保留世界状态。 |
| `return` | 支持 | 结束当前 function。 |
| `ride` | 部分支持 | 记录载具和乘客关系；不模拟控制或物理。 |
| `rotate` | 部分支持 | 更新 yaw/pitch。 |
| `save-all`、`save-off`、`save-on` | 未支持 | 沙盒没有真实存档生命周期。 |
| `say` | 支持 | 记录为 chat 输出事件。 |
| `schedule` | 部分支持 | `schedule function <id> <time> [append|replace]`、`schedule clear <id>`。 |
| `scoreboard` | 部分支持 | objectives 支持 `add`、`remove`、`list`；players 支持 `set`、`add`、`remove`、`get`、`reset`、`list`、`enable`、`operation`。 |
| `seed` | 支持 | 报告确定性的沙盒 seed。 |
| `setblock` | 部分支持 | 修改稀疏世界方块状态和方块实体 NBT；不执行邻居更新。 |
| `setidletimeout` | 未支持 | 服务器管理命令。 |
| `setworldspawn` | 部分支持 | 存储世界出生点和角度。 |
| `spawnpoint` | 部分支持 | 存储玩家出生点和角度。 |
| `spectate` | 部分支持 | 设置旁观模式并记录目标；不模拟客户端镜头状态。 |
| `spreadplayers` | 部分支持 | 确定性地把选中实体分布到中心附近；不实现原版碰撞/队伍算法。 |
| `stop` | 未支持 | 运行时生命周期由宿主进程控制。 |
| `stopsound` | 部分支持 | 记录为 sound 输出事件。 |
| `summon` | 部分支持 | 创建带位置、tag 和 schema 校验 NBT 的实体；实体 AI 不 tick。 |
| `tag` | 支持 | `add`、`remove`、`list`。 |
| `team` | 部分支持 | `add`、`remove`、`list`、`join`、`leave`、`empty`、`modify`；不执行 gameplay 副作用。 |
| `teammsg`、`tm` | 支持 | 记录为 team chat 输出事件。 |
| `teleport`、`tp` | 部分支持 | 支持坐标和目标传送；局部坐标/facing 语义仍不完整。 |
| `tellraw` | 支持 | 解析 JSON text component 并记录输出事件。 |
| `tick` | 部分支持 | `query`、`rate`、`freeze`、`unfreeze`、`step`、`sprint`、`stop`；更新沙盒 tick 状态，可推进 tick。 |
| `time` | 部分支持 | `set`、`add`、`query daytime|gametime|day`。 |
| `title` | 支持 | `clear`、`reset`、`title`、`subtitle`、`actionbar`、`times` 输出事件。 |
| `transfer` | 未支持 | 不模拟网络/server transfer。 |
| `trigger` | 部分支持 | `trigger <objective> [add|set] [value]`；使用当前/default 玩家。 |
| `weather` | 部分支持 | `clear`、`rain`、`thunder`；只存储状态。 |
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
- 玩家 NBT 可读但不可通过 `data` 写入；玩家状态应通过命令或事件改变。
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
| CLI `run --snapshot-diff` | 输出执行前后的状态差异，可配合 `--snapshot-diff-file` 写出 JSON。 |
| CLI `run --stdin` | 从标准输入读取 `.mcfunction` 文本；`--stdin-mode commands` 会按原始命令行执行 stdin。 |
| CLI `run --world` | 执行前应用 manifest-style world JSON fixture。 |
| CLI `run --assert` | 执行后评估一个内联 manifest assertion JSON 对象。 |
| CLI `check <manifest-or-directory>` | 运行 `.dps.json` 清单；失败时可用 `--snapshot-diff-on-fail` 输出最小状态差异。 |

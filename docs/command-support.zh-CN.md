# 26.1.2 命令支持情况

本项目目标版本是 Minecraft Java `26.1.2`。命令语法应以 Minecraft Wiki
说明和 `26.1.2` 原版生成报告为基线。下表比较原版根命令和沙盒当前实现。

状态含义：

- `支持`：主要沙盒可见行为已实现。
- `部分支持`：根命令存在，但只覆盖数据包测试常用路径或可观测状态。
- `未支持`：沙盒不实现该命令。默认情况下，原版根命令不会中断运行，而是记录 warning 输出事件；可通过 CLI、manifest 或 API 切换为 `error` 或 `ignore`。
- `沙盒专用`：不是原版命令，只用于 REPL/CLI 工具。

## 原版命令对比表

| 原版命令 | 沙盒状态 | 已支持/说明 |
|---|---:|---|
| `advancement` | 部分支持 | `grant|revoke|test`；每个玩家独立 progress；奖励支持 function、loot、XP、recipe |
| `attribute` | 未支持 | 实体属性系统未模拟 |
| `ban` / `ban-ip` / `banlist` | 未支持 | 服务端管理命令，不在沙盒边界内 |
| `bossbar` | 部分支持 | `add|remove|list|get|set`；保存 bossbar 状态，不模拟真实客户端 UI |
| `clear` | 部分支持 | 从沙盒玩家背包移除匹配物品 |
| `clone` | 部分支持 | 复制稀疏世界中的方块状态/NBT；不执行方块更新、掉落或重叠物理 |
| `damage` | 部分支持 | 扣除实体/玩家生命值；不模拟完整战斗规则 |
| `data` | 部分支持 | `get|merge|modify|remove storage|entity|block`；实体和方块 NBT 顶层字段按 vanilla-mcdoc schema 校验 |
| `datapack` | 未支持 | 不模拟数据包启停/排序；REPL 可用沙盒 `reload` |
| `debug` / `jfr` / `perf` | 未支持 | 性能采样不适用于洁净室运行时 |
| `defaultgamemode` / `gamemode` | 未支持 | 玩家 game mode 字段存在，但命令尚未接入 |
| `deop` / `op` | 未支持 | 不模拟权限系统 |
| `difficulty` | 未支持 | 难度不会影响当前运行时 |
| `effect` | 部分支持 | `give|clear` 更新玩家效果并触发 advancement 事件 |
| `enchant` | 部分支持 | 写入选中物品的 enchantment component；不校验可附魔性 |
| `execute` | 部分支持 | `as|at|positioned|align|anchored|facing|in|rotated|store|if|unless ... run`；条件支持 `entity|score|data|block` |
| `experience` / `xp` | 部分支持 | `add|set|query`；points 和 levels 共用沙盒 XP 整数 |
| `fill` | 部分支持 | `fill <from> <to> <block[state]{nbt}> [replace|keep|destroy|hollow|outline]`；无更新/掉落 |
| `fillbiome` | 未支持 | 不模拟生物群系存储 |
| `forceload` | 未支持 | 不模拟区块加载 |
| `function` | 支持 | `function <id>` |
| `gamerule` | 部分支持 | 保存任意 gamerule 字符串值；不实现具体游戏规则副作用 |
| `give` | 部分支持 | 向玩家背包添加物品，并触发 inventory advancement 事件 |
| `help` | 未支持 | REPL 有沙盒 `help`，但不是原版 `/help` |
| `item` | 部分支持 | `item replace entity <targets> <slot> with <item> [count]`；`modify` 作为资源钩子接受但暂不转换组件 |
| `kick` | 未支持 | 不模拟网络/登录会话 |
| `kill` | 支持 | `kill [targets]` 移除沙盒实体 |
| `list` | 未支持 | 不模拟服务端玩家列表命令 |
| `locate` | 未支持 | 不模拟世界生成/结构查找 |
| `loot` | 未支持 | 原版 `/loot` 未实现；CLI 提供 `loot --table ...` |
| `me` | 支持 | 记录为沙盒 chat 输出事件 |
| `msg` / `tell` / `w` | 支持 | 记录为沙盒 chat 输出事件 |
| `pardon` / `pardon-ip` | 未支持 | 服务端管理命令 |
| `particle` | 部分支持 | 记录为 visual 输出事件，不模拟客户端粒子 |
| `place` | 未支持 | 不模拟结构/地物放置 |
| `playsound` | 部分支持 | 记录为 sound 输出事件 |
| `publish` | 未支持 | 不模拟 LAN/网络 |
| `random` | 部分支持 | `value|roll <range> [sequence]`、`reset`；使用确定性的沙盒 sequence 状态 |
| `recipe` | 部分支持 | `give|take <targets> <recipe|*>`，更新玩家 recipe 集合 |
| `reload` | 未支持 | 原版 reload 未实现；REPL 有沙盒 `reload` |
| `return` | 支持 | 停止当前 function |
| `ride` | 部分支持 | 记录 vehicle/passenger 关系；不模拟控制和物理 |
| `rotate` | 部分支持 | 更新实体 yaw/pitch |
| `save-all` / `save-off` / `save-on` | 未支持 | 没有真实 world save |
| `say` | 支持 | 记录为 chat 输出事件 |
| `schedule` | 部分支持 | `schedule function <id> <time> [append|replace]`、`schedule clear <id>` |
| `scoreboard` | 部分支持 | objectives `add|remove|list`；players `set|add|remove|get|reset|list|enable|operation` |
| `seed` | 未支持 | 不模拟世界种子/生成 |
| `setblock` | 部分支持 | `setblock <pos> <block[state]{nbt}> [replace|keep|destroy|strict]`；无邻居更新 |
| `setidletimeout` | 未支持 | 服务端管理命令 |
| `setworldspawn` / `spawnpoint` | 未支持 | 不模拟出生点逻辑 |
| `spectate` | 未支持 | 不模拟旁观目标/客户端相机 |
| `spreadplayers` | 未支持 | 不实现空间分布和碰撞规则 |
| `stop` | 未支持 | 运行时生命周期不由命令控制 |
| `stopsound` | 部分支持 | 记录为 sound 输出事件 |
| `summon` | 部分支持 | `summon <entity> [x y z] [snbt]`；生成不会 tick AI 的实体，并校验 NBT schema |
| `tag` | 支持 | `tag <targets> add|remove|list` |
| `team` | 部分支持 | `add|remove|list|join|leave|empty|modify`；不模拟碰撞/名牌等游戏副作用 |
| `teammsg` / `tm` | 支持 | 记录为 chat 输出事件 |
| `teleport` / `tp` | 部分支持 | 坐标和目标传送；facing/local-coordinate 语义不完整 |
| `tellraw` | 支持 | JSON text component 解析为沙盒 chat 输出事件 |
| `tick` | 未支持 | 原版 tick 命令未实现；REPL `tick [n]` 是沙盒命令 |
| `time` | 部分支持 | `set|add|query daytime|gametime|day`；day time 与 tick 计数分开保存 |
| `title` | 支持 | `clear|reset|title|subtitle|actionbar|times` 输出事件 |
| `transfer` | 未支持 | 不模拟服务器转移/网络 |
| `trigger` | 未支持 | trigger objective 流程未实现 |
| `weather` | 部分支持 | `clear|rain|thunder [duration]`；只保存状态 |
| `whitelist` | 未支持 | 服务端管理命令 |
| `worldborder` | 未支持 | 不模拟 world border |

输出类命令会表示为确定性的沙盒输出事件，而不是模拟真实客户端 UI 或网络效果。Raw JSON text component 会解析 `text`、`score`、`selector`、`translate`、`keybind`、基础 `nbt`、`extra` 和常见格式标记，然后进入 snapshot 并由 CLI 渲染。

当前选择器支持 `@s`、`@a`、`@p`、`@e` 和 `@n`。`@n` 会在过滤条件之后选择最近的实体，默认 `limit` 为 1。

## 世界与 NBT 说明

- 初始世界是稀疏虚空：没有设置过的坐标没有方块条目。
- `setblock`、`fill`、`clone` 只修改沙盒方块状态，不执行方块更新、物理、邻居更新、计划刻或掉落。
- 沙盒默认创建名为 `Steve` 的玩家，并提供可读的原版风格玩家 NBT。显式执行 `player Steve` 是幂等的。
- 实体不会 tick AI 或物理。沙盒也不会自动写入 `NoAI: true`；它只是没有模拟 AI。
- 非玩家实体 NBT 可通过 `data modify|merge|remove entity` 写入，但顶层字段会根据 generated vanilla-mcdoc schema 校验，未知自定义字段会失败。
- 方块 NBT 会对 generated vanilla-mcdoc block dispatch 映射发现的方块实体暴露。方块实体写入同样会拒绝未知自定义顶层字段。
- 玩家 NBT 可通过 `data get entity <player>` 读取，但写入会失败。需要改变玩家状态时，使用 `tp|teleport`、manifest 玩家步骤或玩家事件。
- 实体/方块 NBT 内的物品栈 compound 也会根据 generated mcdoc item stack schema 校验。

## 未支持命令策略

未实现的原版根命令默认策略是 `warn`：继续执行，并记录黄色 `warning` 输出事件。这能避免数据包冒烟测试只因为包含装饰性命令或管理命令就失败。

CLI 可设置策略：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar check examples --unsupported ignore
java -jar cli/build/libs/datapack-sandbox-cli.jar run --pack ./pack --unsupported error --command "worldborder get"
```

`.dps.json` manifest 可以添加顶层字段：

```json
{
  "unsupported": "ignore"
}
```

可选值为 `warn`、`ignore`、`error`。Kotlin API 对应 `UnsupportedFeatureMode.WARN`、`IGNORE`、`ERROR`。

## 沙盒专用命令

这些不是原版命令，只用于本工具：

| 命令 | 用途 |
|---|---|
| `event player <name> <type> [id] [action]` | 注入玩家行为事件，触发 predicate、advancement 和奖励；支持 `key_input` 与 `mouse_input` |
| `player <name>` | 创建或复用沙盒玩家 |
| `inspect <...>` | 查看 score、storage、entity、block、player、loot、predicate、advancement、registry、outputs |
| `snapshot [file]` | 打印或写出确定性世界快照 |
| `reload` | REPL 中重载数据包文件，同时保留内存世界 |
| `load` | REPL 中运行 `#minecraft:load` |
| `tick [n]` | REPL 中推进沙盒 tick |
| CLI `loot --table <id> --context <context>` | 直接生成战利品表结果 |
| CLI `check <manifest-or-directory>` | 运行 `.dps.json` 清单 |

世界生成、红石、完整战斗 AI、网络和真实客户端输入都不属于当前运行时边界。

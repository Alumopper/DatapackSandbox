# 玩家事件

`event` 是沙盒工具命令，不是原版 Minecraft 命令。它用于把“数据包可见的玩家行为”注入运行时。沙盒不模拟完整客户端输入、物理、寻路或战斗 AI；事件只负责喂给 predicate context、loot context 和 advancement trigger。

## REPL 用法

```text
dps> event player Steve item_used minecraft:carrot_on_a_stick
dps> event player Steve entity_interacted minecraft:villager
dps> event player Steve entity_interacted @e[type=minecraft:interaction,tag=button,limit=1]
dps> event player Steve entity_attacked @e[type=minecraft:interaction,tag=button,limit=1]
dps> event player Steve damage minecraft:fall 4.5
dps> event player Steve entity_killed minecraft:zombie
dps> event player Steve block_placed minecraft:oak_log 0 64 0
dps> event player Steve changed_dimension minecraft:overworld minecraft:the_nether
dps> event player Steve key_input key.jump
dps> event player Steve mouse_input left
dps> inspect player Steve
dps> inspect advancement
```

命令形状：

```text
event player <name> <event-type> [resource-id] [detail/action|x y z|pos=x,y,z]
```

可选 `resource-id` 会按事件类型解释：

- `item_used`、`item_consumed`、`inventory_changed`、`item_picked_up`：物品 id。
- `entity_interacted`、`killed_entity`、`entity_killed`、`player_killed_entity`、`entity_killed_player`：实体类型 id。
- `damage`、`death`：damage source 类型 id，`detail` 可填写伤害数值。
- `placed_block`、`block_placed`、`broke_block`、`block_broken`、`broken_block`：方块 id。
- `changed_dimension`：`resource-id` 是来源维度，`detail` 是目标维度。
- `recipe_unlocked`：recipe id。

对于 `entity_interacted`、`entity_attacked`、`player_attacked_entity`、
`attack_entity` 和 `player_hurt_entity`，id 也可以填写只命中一个真实沙盒实体的
selector 或 UUID。目标为 interaction 实体时，事件会把玩家 UUID 和当前 game tick
写入 `interaction` 或 `attack`，并在 event trace 中记录 `response` 与目标 UUID。
零尺寸 interaction 命中箱、display、marker 和 Marker 模式盔甲架会拒绝这类定向玩家动作。

方块放置/破坏事件的尾部参数也可以声明目标 sparse-world 坐标，支持
`0 64 0`、`pos=0,64,0`、`blockPos=0,64,0` 或 `@0,64,0`。传入坐标后，
事件会同步更新 sparse world，并在 event trace 里记录 `blockPos`。

键盘/鼠标事件把输入代码放在 `[resource-id]` 位置：

```text
event player Steve key_input key.jump
event player Steve key_input key.jump release
event player Steve mouse_input left
event player Steve mouse_input left release
```

第五个参数可覆盖 action，例如 `press`、`release`、`click`、`move`。

## CLI 用法

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar event --pack examples/full-stack/pack player Steve item-used minecraft:carrot_on_a_stick
```

CLI 接受连字符或下划线；`item-used` 会标准化为 `item_used`。

随手小测可以用 `run --event` 注入同一套简写事件；多条事件可写入文件后
通过 `run --event-file` 按行注入，然后断言玩家状态或 `eventTrace`：

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --event "player Steve key_input key.jump release" \
  --event-trace-file ./event-trace.jsonl \
  --assert '{"eventTrace":{"player":"Steve","type":"key_input","success":true,"count":1}}'
```

## 清单用法

```json
{
  "steps": [
    {
      "player": {
        "name": "Steve",
        "inventory": [
          { "id": "minecraft:carrot_on_a_stick", "count": 1 }
        ]
      }
    },
    {
      "event": {
        "player": "Steve",
        "type": "item_used",
        "item": "minecraft:carrot_on_a_stick"
      }
    },
    {
      "event": {
        "player": "Steve",
        "type": "damage",
        "damageSource": "minecraft:fall",
        "amount": 4.5
      }
    },
    {
      "event": {
        "player": "Steve",
        "type": "entity_interacted",
        "target": "@e[type=minecraft:interaction,tag=button,limit=1]"
      }
    }
  ]
}
```

## 支持的事件

| 事件类型 | 可选字段 / id | 效果 |
|---|---|---|
| `tick` | 无 | 触发 `minecraft:tick` advancement trigger。 |
| `inventory_changed` | `item` | 用于 inventory changed 条件。 |
| `item_used` | `item` | 用于 item used 条件。 |
| `item_consumed` | `item` | 用于 consume item 条件；存在匹配背包/选中物品时会消耗 1 个，并对已知食物应用确定性 food 增益。 |
| `item_picked_up` / `item_added` | `item` | 把物品加入玩家背包，并作为 inventory changed 的别名路径。 |
| `key_input` / `key_pressed` / `key_released` | `key`、`action` | 记录玩家键盘输入；沙盒自定义 `key_input` advancement trigger 可匹配。 |
| `mouse_input` / `mouse_clicked` / `mouse_released` / `mouse_moved` | `button`、`action`、`x`、`y` | 记录玩家鼠标输入；沙盒自定义 `mouse_input` advancement trigger 可匹配。 |
| `entity_interacted` | `entity` 或 `target` selector/UUID | 触发 `minecraft:player_interacted_with_entity`；真实 interaction 目标会记录右击玩家、tick 和 response。 |
| `entity_attacked` / `player_attacked_entity` / `attack_entity` / `player_hurt_entity` | `entity` 或 `target` selector/UUID | 触发 `minecraft:player_hurt_entity`；真实 interaction 目标会记录攻击玩家和 tick，但不扣生命值。 |
| `damage` | `damageSource`、`amount`、`entity` | 扣减玩家 health，并触发 `minecraft:entity_hurt_player`；`entity` 表示伤害来源实体。 |
| `death` | `damageSource`、`amount`、`entity` | 把玩家 health 置为 0，并触发沙盒自定义 `death`；带来源实体的 `damage` 命令击杀玩家时也会触发 `entity_killed_player`。 |
| `killed_entity` / `entity_killed` / `player_killed_entity` | `entity` | 触发 `minecraft:player_killed_entity`。 |
| `entity_killed_player` | `entity` | 触发 `minecraft:entity_killed_player`。 |
| `location` | 无 | 用于 location 条件。 |
| `changed_dimension` | `from`、`to` | 提供 `to` 时更新玩家维度，并用于 dimension change 条件。 |
| `placed_block` / `block_placed` | `block` | 用于 placed block 条件。 |
| `broke_block` / `block_broken` / `broken_block` | `block` | 映射到已实现的 block break trigger 子集。 |
| `recipe_unlocked` | `recipe` | 把 recipe 加入玩家 recipe 集合，并用于 recipe unlocked 条件。 |
| `effects_changed` | 无 | 用于 effects changed 条件。 |

REPL 快捷命令暴露一个可选 `[resource-id]` 和一个可选 detail 或输入 action。需要更完整上下文时使用 JSON 清单，例如带 NBT 的物品、精确实体上下文、伤害源元数据或鼠标坐标。

定向 interaction 事件发生后，可按原版 interaction 关系形状切换执行者：

```mcfunction
execute as @e[type=minecraft:interaction,tag=button] on target run function demo:right_click
execute as @e[type=minecraft:interaction,tag=button] on attacker run function demo:left_click
```

`on target` 使用最近右击记录，`on attacker` 使用最近攻击记录；尚未产生对应记录时，该执行分支没有上下文。

原版风格的 `damage` 命令也会发出玩家事件：玩家受伤时发出 `damage`；
生命值归零时发出 `death`；如果命令使用 `by <entity>` 或 `from <entity>`，
还会发出 `entity_killed_player`。当玩家是非玩家实体的伤害来源时，沙盒会发出
`player_hurt_entity`，致命伤害还会发出 `killed_entity`。`damage` 命令的
payload 会记录解析后的 `at` 位置、直接 `by` 实体和最终 `from` 实体，便于
report 和断言调试生成出来的战斗命令，而不需要模拟完整战斗物理。

高层事件也会在不需要客户端物理的范围内更新可观察玩家状态：consume 事件可减少背包物品并增加 food，pickup/add 事件会增加背包物品，dimension change 会更新玩家维度，damage/death 会更新 health，recipe unlock 会更新玩家 recipe 集合。这些变化可以通过 snapshot、`inspect player`、manifest assertion 和 QuickTest assertion 检查。

键鼠清单示例：

```json
{ "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "press" } }
{ "event": { "player": "Steve", "type": "mouse_input", "button": "left", "action": "click", "x": 12, "y": 8 } }
```

事件会更新玩家 advancement progress，并可能执行 advancement reward，例如 function、loot、experience、recipe。触发后可用 `inspect player <name>`、`inspect advancement`、`inspect outputs`、`inspect event-traces`、snapshot 中的 `playerEventTraces` 或 manifest `eventTrace` 断言查看结果。`eventTrace` 可按 player/type/success、advancement criterion、item/entity/block/recipe id、维度变化、damage source/amount、键鼠 input device/code/action，以及未匹配 advancement criterion 的可读失败原因过滤。

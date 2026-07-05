# 玩家事件

`event` 是沙盒工具命令，不是原版 Minecraft 命令。它用于把“数据包可见的玩家行为”注入运行时。沙盒不模拟完整客户端输入、物理、寻路或战斗 AI；事件只负责喂给 predicate context、loot context 和 advancement trigger。

## REPL 用法

```text
dps> event player Steve item_used minecraft:carrot_on_a_stick
dps> event player Steve entity_interacted minecraft:villager
dps> event player Steve damage minecraft:fall 4.5
dps> event player Steve killed_entity minecraft:zombie
dps> event player Steve placed_block minecraft:oak_log
dps> event player Steve changed_dimension minecraft:overworld minecraft:the_nether
dps> event player Steve key_input key.jump
dps> event player Steve mouse_input left
dps> inspect player Steve
dps> inspect advancement
```

命令形状：

```text
event player <name> <event-type> [resource-id] [detail]
```

可选 `resource-id` 会按事件类型解释：

- `item_used`、`item_consumed`、`inventory_changed`、`item_picked_up`：物品 id。
- `entity_interacted`、`killed_entity`、`entity_killed_player`：实体类型 id。
- `damage`、`death`：damage source 类型 id，`detail` 可填写伤害数值。
- `placed_block`、`broke_block`：方块 id。
- `changed_dimension`：`resource-id` 是来源维度，`detail` 是目标维度。
- `recipe_unlocked`：recipe id。

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
| `item_consumed` | `item` | 用于 consume item 条件。 |
| `item_picked_up` | `item` | 作为 inventory changed 的别名路径。 |
| `key_input` / `key_pressed` / `key_released` | `key`、`action` | 记录玩家键盘输入；沙盒自定义 `key_input` advancement trigger 可匹配。 |
| `mouse_input` / `mouse_clicked` / `mouse_released` / `mouse_moved` | `button`、`action`、`x`、`y` | 记录玩家鼠标输入；沙盒自定义 `mouse_input` advancement trigger 可匹配。 |
| `entity_interacted` | `entity` | 触发 `minecraft:player_interacted_with_entity`。 |
| `damage` | `damageSource`、`amount`、`entity` | 触发 `minecraft:entity_hurt_player`；`entity` 表示伤害来源实体。 |
| `death` | `damageSource`、`amount`、`entity` | 触发沙盒自定义 `death`；带来源实体的 `damage` 命令击杀玩家时也会触发 `entity_killed_player`。 |
| `killed_entity` | `entity` | 触发 `minecraft:player_killed_entity`。 |
| `entity_killed_player` | `entity` | 触发 `minecraft:entity_killed_player`。 |
| `location` | 无 | 用于 location 条件。 |
| `changed_dimension` | `from`、`to` | 用于 dimension change 条件。 |
| `placed_block` | `block` | 用于 placed block 条件。 |
| `broke_block` | `block` | 映射到已实现的 block break trigger 子集。 |
| `recipe_unlocked` | `recipe` | 用于 recipe unlocked 条件。 |
| `effects_changed` | 无 | 用于 effects changed 条件。 |

REPL 快捷命令暴露一个可选 `[resource-id]` 和一个可选 detail 或输入 action。需要更完整上下文时使用 JSON 清单，例如带 NBT 的物品、精确实体上下文、伤害源元数据或鼠标坐标。

原版风格的 `damage` 命令也会发出玩家事件：玩家受伤时发出 `damage`；
生命值归零时发出 `death`；如果命令使用 `by <entity>` 或 `from <entity>`，
还会发出 `entity_killed_player`。当玩家是非玩家实体的伤害来源时，沙盒会发出
`player_hurt_entity`，致命伤害还会发出 `killed_entity`。

键鼠清单示例：

```json
{ "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "press" } }
{ "event": { "player": "Steve", "type": "mouse_input", "button": "left", "action": "click", "x": 12, "y": 8 } }
```

事件会更新玩家 advancement progress，并可能执行 advancement reward，例如 function、loot、experience、recipe。触发后可用 `inspect player <name>`、`inspect advancement`、`inspect outputs`、snapshot 中的 `playerEventTraces` 或 manifest `eventTrace` 断言查看结果。

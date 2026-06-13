# 玩家事件

`event` 是沙盒命令，不是原版 Minecraft 命令。它的作用是告诉沙盒：“某个玩家刚刚发生了一个数据包能看见的行为”。沙盒不会模拟完整客户端输入、物理或战斗 AI；这些事件会进入谓词上下文、战利品上下文和进度触发器。

## REPL 用法

```text
dps> event player Steve item_used minecraft:carrot_on_a_stick
dps> event player Steve killed_entity minecraft:zombie
dps> event player Steve placed_block minecraft:oak_log
dps> event player Steve key_input key.jump
dps> event player Steve mouse_input left
dps> inspect player Steve
dps> inspect advancement
```

命令格式：

```text
event player <玩家名> <事件类型> [资源ID]
```

最后的 `[资源ID]` 会按事件类型解释：`item_used` 把它当物品，`killed_entity` 把它当实体类型，`placed_block`/`broke_block` 把它当方块，`recipe_unlocked` 把它当配方。

对键鼠事件，`[资源ID]` 位置写输入代码：`key_input key.jump`、`key_pressed space`、`mouse_input left`。第五个参数可作为 action，例如 `event player Steve key_input key.jump release`。

## CLI 用法

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar event --pack examples/full-stack/pack player Steve item-used minecraft:carrot_on_a_stick
```

CLI 接受连字符或下划线，`item-used` 会被规范化为 `item_used`。

## Manifest 用法

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
    }
  ]
}
```

## 支持的事件

| 事件类型 | 可选字段/ID | 会影响什么 |
|---|---|---|
| `tick` | 无 | `minecraft:tick` 进度触发 |
| `inventory_changed` | `item` | 背包变化类进度条件 |
| `item_used` | `item` | 使用物品类进度条件，例如 carrot on a stick |
| `item_consumed` | `item` | 消耗物品类进度条件 |
| `item_picked_up` | `item` | 作为 `inventory_changed` 的别名路径 |
| `key_input` / `key_pressed` / `key_released` | `key`、`action` | 记录玩家键盘输入；沙盒自定义 `key_input` advancement trigger 可匹配 |
| `mouse_input` / `mouse_clicked` / `mouse_released` / `mouse_moved` | `button`、`action`、`x`、`y` | 记录玩家鼠标输入；沙盒自定义 `mouse_input` advancement trigger 可匹配 |
| `killed_entity` | `entity` | `minecraft:player_killed_entity` |
| `entity_killed_player` | `entity` | `minecraft:entity_killed_player` |
| `location` | 无 | 位置类进度条件 |
| `changed_dimension` | `from`、`to` | 维度变化类进度条件 |
| `placed_block` | `block` | 放置方块类进度条件 |
| `broke_block` | `block` | 目前映射到部分破坏方块类触发 |
| `recipe_unlocked` | `recipe` | 解锁配方类进度条件 |
| `effects_changed` | 无 | 药水效果变化类进度条件 |

REPL 的简写命令只提供一个 `[资源ID]` 和可选 action。需要同时指定 `from`/`to`、精确 item 组件、鼠标坐标等更复杂上下文时，用 manifest JSON。

键鼠 manifest 示例：

```json
{ "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "press" } }
{ "event": { "player": "Steve", "type": "mouse_input", "button": "left", "action": "click", "x": 12, "y": 8 } }
```

事件会更新每个玩家独立的进度状态，并可能执行进度奖励，例如 function、loot、experience 和 recipe。事件执行后可以用 `inspect player <name>`、`inspect advancement`、`inspect outputs` 或 snapshot 查看结果。

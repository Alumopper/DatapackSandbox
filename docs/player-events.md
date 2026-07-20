# Player Events

`event` is a sandbox command, not a vanilla Minecraft command. It injects a
data-pack-visible player behavior into the runtime. The sandbox does not
simulate full client input, physics, or combat AI; events feed predicate
contexts, loot contexts, and advancement triggers.

## REPL Usage

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

Command shape:

```text
event player <name> <event-type> [resource-id] [detail/action|x y z|pos=x,y,z]
```

The optional resource id is interpreted by event type: `item_used` treats it as
an item, `entity_interacted`/`killed_entity`/`entity_killed` as an entity type,
`damage`/`death` as a damage source type,
`placed_block`/`block_placed`/`broke_block`/`block_broken` as a block,
`recipe_unlocked` as a recipe, and `changed_dimension` as the source
dimension. For `damage`/`death`, optional `[detail]` is the damage amount. For
`changed_dimension`, the optional `[detail]` argument is the destination
dimension.

For `entity_interacted`, `entity_attacked`, `player_attacked_entity`,
`attack_entity`, and `player_hurt_entity`, the id may instead be a selector or
UUID resolving to exactly one real sandbox entity. Targeting an interaction
entity writes its `interaction` or `attack` record with the player's UUID and
current game tick. The configured `response` flag and target UUID are included
in the event trace. Zero-sized interaction hitboxes, displays, markers, and
marker-mode armor stands reject targeted player actions.

For block place/break events, the optional tail can be a target sparse-world
block position. Accepted forms are `0 64 0`, `pos=0,64,0`,
`blockPos=0,64,0`, or `@0,64,0`. When present, the event updates the sparse
world and the event trace records `blockPos`.

For keyboard/mouse events, put the input code in the `[resource-id]` slot:
`key_input key.jump`, `key_pressed space`, or `mouse_input left`. A fifth
argument can override the action, for example `event player Steve key_input
key.jump release`.

## CLI Usage

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar event --pack examples/full-stack/pack player Steve item-used minecraft:carrot_on_a_stick
```

The CLI accepts hyphens or underscores; `item-used` is normalized to
`item_used`.

For quick one-off runs, inject the same shorthand with `run --event` or one
event per line with `run --event-file`, then assert the resulting player state
or event trace:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --event "player Steve key_input key.jump release" \
  --event-trace-file ./event-trace.jsonl \
  --assert '{"eventTrace":{"player":"Steve","type":"key_input","success":true,"count":1}}'
```

## Manifest Usage

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

## Supported Events

| Event type | Optional field/id | Effect |
|---|---|---|
| `tick` | none | `minecraft:tick` advancement trigger |
| `inventory_changed` | `item` | Inventory-change advancement conditions |
| `item_used` | `item` | Item-use advancement conditions |
| `item_consumed` | `item` | Consume-item advancement conditions; decrements one matching inventory/selected item when present and applies a deterministic food increase for known foods |
| `item_picked_up` / `item_added` | `item` | Adds the item stack to player inventory and follows the inventory-change alias path |
| `key_input` / `key_pressed` / `key_released` | `key`, `action` | Records player keyboard input; sandbox custom `key_input` advancement triggers can match it |
| `mouse_input` / `mouse_clicked` / `mouse_released` / `mouse_moved` | `button`, `action`, `x`, `y` | Records player mouse input; sandbox custom `mouse_input` advancement triggers can match it |
| `entity_interacted` | `entity` or `target` selector/UUID | `minecraft:player_interacted_with_entity`; a real interaction target records the right-click player/tick and response |
| `entity_attacked` / `player_attacked_entity` / `attack_entity` / `player_hurt_entity` | `entity` or `target` selector/UUID | `minecraft:player_hurt_entity`; a real interaction target records the attacking player/tick without taking health damage |
| `damage` | `damageSource`, `amount`, `entity` | Reduces player health, triggers `minecraft:entity_hurt_player`; `entity` is the source entity when provided |
| `death` | `damageSource`, `amount`, `entity` | Sets player health to zero and triggers sandbox `death`; command damage with a source entity also emits `entity_killed_player` |
| `killed_entity` / `entity_killed` / `player_killed_entity` | `entity` | `minecraft:player_killed_entity` |
| `entity_killed_player` | `entity` | `minecraft:entity_killed_player` |
| `location` | none | Location advancement conditions |
| `changed_dimension` | `from`, `to` | Updates player dimension when `to` is present and checks dimension-change advancement conditions |
| `placed_block` / `block_placed` | `block` | Placed-block advancement conditions |
| `broke_block` / `block_broken` / `broken_block` | `block` | Mapped to the implemented block-break trigger subset |
| `recipe_unlocked` | `recipe` | Adds the recipe to the player recipe set and checks recipe-unlocked advancement conditions |
| `effects_changed` | none | Effects-changed advancement conditions |

The REPL shorthand exposes one optional `[resource-id]` and optional detail or
input action. Use manifest JSON for richer item data, exact entity context,
damage source metadata, or mouse coordinates.

After a targeted interaction event, relationship execution follows vanilla's
interaction-entity shape:

```mcfunction
execute as @e[type=minecraft:interaction,tag=button] on target run function demo:right_click
execute as @e[type=minecraft:interaction,tag=button] on attacker run function demo:left_click
```

`on target` uses the last right-click record and `on attacker` uses the last
attack record. If that relation has not been recorded, the execution branch
produces no contexts.

The vanilla-style `damage` command also emits player events. Damaging a player
emits `damage`; reducing a player's health to zero emits `death`; and if the
command uses `by <entity>` or `from <entity>`, the death also emits
`entity_killed_player`. When a player is the source entity for damage to a
non-player entity, the sandbox emits `player_hurt_entity` and, on lethal
damage, `killed_entity`. The damage command payload records the parsed `at`
position plus direct `by` and causing `from` entities so reports and assertions
can debug generated combat commands without simulating full combat physics.

High-level events also update observable player state when the state can be
modeled without client physics: consume events can reduce inventory and food,
pickup/add events add inventory items, dimension changes update the player's
dimension, damage/death events update health, and recipe unlock events update
the player's recipe set. These changes are visible through snapshots,
`inspect player`, manifest assertions, and QuickTest assertions.

Keyboard/mouse manifest examples:

```json
{ "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "press" } }
{ "event": { "player": "Steve", "type": "mouse_input", "button": "left", "action": "click", "x": 12, "y": 8 } }
```

Events update per-player advancement progress and may run advancement rewards
such as function, loot, experience, and recipe rewards. After dispatching an
event, inspect results with `inspect player <name>`, `inspect advancement`,
`inspect outputs`, `inspect event-traces`, `snapshot` `playerEventTraces`, or
manifest `eventTrace` assertions. `eventTrace` can filter by player/type/success, advancement
criterion, item/entity/block/recipe ids, dimension changes, damage
source/amount, keyboard/mouse input device/code/action, and failed advancement
criteria with readable reasons.

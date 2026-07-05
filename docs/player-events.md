# Player Events

`event` is a sandbox command, not a vanilla Minecraft command. It injects a
data-pack-visible player behavior into the runtime. The sandbox does not
simulate full client input, physics, or combat AI; events feed predicate
contexts, loot contexts, and advancement triggers.

## REPL Usage

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

Command shape:

```text
event player <name> <event-type> [resource-id] [detail]
```

The optional resource id is interpreted by event type: `item_used` treats it as
an item, `entity_interacted`/`killed_entity` as an entity type,
`damage`/`death` as a damage source type, `placed_block`/`broke_block` as a
block, `recipe_unlocked` as a recipe, and `changed_dimension` as the source
dimension. For `damage`/`death`, optional `[detail]` is the damage amount. For
`changed_dimension`, the optional `[detail]` argument is the destination
dimension.

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

For quick one-off runs, inject the same shorthand with `run --event` and assert
the resulting player state or event trace:

```bash
java -jar cli/build/libs/datapack-sandbox-cli.jar run --version 26.2 \
  --event "player Steve key_input key.jump release" \
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
| `item_consumed` | `item` | Consume-item advancement conditions |
| `item_picked_up` | `item` | Alias path for inventory changes |
| `key_input` / `key_pressed` / `key_released` | `key`, `action` | Records player keyboard input; sandbox custom `key_input` advancement triggers can match it |
| `mouse_input` / `mouse_clicked` / `mouse_released` / `mouse_moved` | `button`, `action`, `x`, `y` | Records player mouse input; sandbox custom `mouse_input` advancement triggers can match it |
| `entity_interacted` | `entity` | `minecraft:player_interacted_with_entity` |
| `damage` | `damageSource`, `amount`, `entity` | `minecraft:entity_hurt_player`; `entity` is the source entity when provided |
| `death` | `damageSource`, `amount`, `entity` | Sandbox `death` trigger; command damage with a source entity also emits `entity_killed_player` |
| `killed_entity` | `entity` | `minecraft:player_killed_entity` |
| `entity_killed_player` | `entity` | `minecraft:entity_killed_player` |
| `location` | none | Location advancement conditions |
| `changed_dimension` | `from`, `to` | Dimension-change advancement conditions |
| `placed_block` | `block` | Placed-block advancement conditions |
| `broke_block` | `block` | Mapped to the implemented block-break trigger subset |
| `recipe_unlocked` | `recipe` | Recipe-unlocked advancement conditions |
| `effects_changed` | none | Effects-changed advancement conditions |

The REPL shorthand exposes one optional `[resource-id]` and optional detail or
input action. Use manifest JSON for richer item data, exact entity context,
damage source metadata, or mouse coordinates.

The vanilla-style `damage` command also emits player events. Damaging a player
emits `damage`; reducing a player's health to zero emits `death`; and if the
command uses `by <entity>` or `from <entity>`, the death also emits
`entity_killed_player`. When a player is the source entity for damage to a
non-player entity, the sandbox emits `player_hurt_entity` and, on lethal
damage, `killed_entity`.

Keyboard/mouse manifest examples:

```json
{ "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "press" } }
{ "event": { "player": "Steve", "type": "mouse_input", "button": "left", "action": "click", "x": 12, "y": 8 } }
```

Events update per-player advancement progress and may run advancement rewards
such as function, loot, experience, and recipe rewards. After dispatching an
event, inspect results with `inspect player <name>`, `inspect advancement`,
`inspect outputs`, `snapshot` `playerEventTraces`, or manifest `eventTrace`
assertions.

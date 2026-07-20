package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path

internal fun writeExecuteFunctionPack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "execute function condition test"
          }
        }
        """.trimIndent(),
    )
    val functionRoot = root.resolve("data").resolve("demo").resolve("function")
    Files.createDirectories(functionRoot)
    Files.writeString(
        functionRoot.resolve("pass.mcfunction"),
        """
        scoreboard players add #condition checks 1
        return 1
        scoreboard players add #condition checks 100
        """.trimIndent(),
    )
    Files.writeString(
        functionRoot.resolve("fail.mcfunction"),
        """
        scoreboard players add #condition checks 10
        return fail
        scoreboard players add #condition checks 100
        """.trimIndent(),
    )
    Files.writeString(
        functionRoot.resolve("return_run.mcfunction"),
        """
        return run random value 4..4
        """.trimIndent(),
    )
    Files.writeString(
        functionRoot.resolve("return_after_output.mcfunction"),
        """
        random value 9..9
        return 4
        """.trimIndent(),
    )
    val tagRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("tags")
            .resolve("function")
    Files.createDirectories(tagRoot)
    Files.writeString(
        tagRoot.resolve("group.json"),
        """
        {
          "values": [
            "demo:pass",
            "demo:fail",
            { "id": "demo:missing", "required": false }
          ]
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeItemModifierPack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "item modifier test"
          }
        }
        """.trimIndent(),
    )
    val modifierRoot = root.resolve("data").resolve("demo").resolve("item_modifier")
    Files.createDirectories(modifierRoot)
    Files.writeString(
        modifierRoot.resolve("change_item.json"),
        """
        {
          "function": "minecraft:set_item",
          "item": "minecraft:carrot"
        }
        """.trimIndent(),
    )
    Files.writeString(
        modifierRoot.resolve("discard.json"),
        """
        {
          "function": "minecraft:discard"
        }
        """.trimIndent(),
    )
    Files.writeString(
        modifierRoot.resolve("filtered_pass.json"),
        """
        {
          "function": "minecraft:set_components",
          "components": {
            "demo:filtered": "pass"
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        modifierRoot.resolve("shared.json"),
        """
        {
          "function": "minecraft:set_components",
          "components": {
            "demo:referenced": "shared"
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        modifierRoot.resolve("copy_selected.json"),
        """
        {
          "function": "minecraft:copy_components",
          "source": "this",
          "include": [
            "demo:source",
            "minecraft:damage",
            "demo:skip"
          ],
          "exclude": [
            "demo:skip"
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        modifierRoot.resolve("copy_nbt.json"),
        """
        {
          "function": "minecraft:copy_nbt",
          "source": "tool",
          "ops": [
            {
              "source": "source.level",
              "target": "copied.level"
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        modifierRoot.resolve("mark.json"),
        """
        [
          {
            "function": "minecraft:set_components",
            "components": {
              "demo:tag": "tagged"
            }
          },
          {
            "function": "minecraft:set_custom_data",
            "tag": {
              "marked": true
            }
          },
          {
            "function": "minecraft:set_count",
            "count": 8
          },
          {
            "function": "minecraft:limit_count",
            "limit": {
              "max": 4
            }
          },
          {
            "function": "minecraft:set_damage",
            "damage": 3
          },
          {
            "function": "minecraft:set_name",
            "name": { "text": "Marked Stick" }
          },
          {
            "function": "minecraft:set_lore",
            "lore": [
              { "text": "debuggable" }
            ]
          },
          {
            "function": "minecraft:sequence",
            "functions": [
              {
                "function": "minecraft:set_components",
                "components": {
                  "demo:sequence": "applied"
                }
              }
            ]
          },
          {
            "function": "minecraft:filtered",
            "item_filter": {
              "items": "minecraft:stick"
            },
            "on_pass": "demo:filtered_pass",
            "on_fail": [
              {
                "function": "minecraft:set_components",
                "components": {
                  "demo:filtered_fail": "fail"
                }
              }
            ]
          },
          {
            "function": "minecraft:reference",
            "name": "demo:shared"
          }
        ]
        """.trimIndent(),
    )
    return root
}

internal fun writeEffectPredicatePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "effect predicate test"
          }
        }
        """.trimIndent(),
    )
    val predicateRoot = root.resolve("data").resolve("demo").resolve("predicate")
    Files.createDirectories(predicateRoot)
    Files.writeString(
        predicateRoot.resolve("zombie_speed_effect.json"),
        """
        {
          "condition": "minecraft:entity_properties",
          "entity": "this",
          "predicate": {
            "type": "minecraft:zombie",
            "effects": {
              "minecraft:speed": {
                "amplifier": 2,
                "duration": {
                  "min": 140,
                  "max": 140
                },
                "hide_particles": true
              }
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("zombie_no_strength.json"),
        """
        {
          "condition": "minecraft:entity_properties",
          "entity": "this",
          "predicate": {
            "type": "minecraft:zombie",
            "effects": {
              "minecraft:strength": false
            }
          }
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeEquipmentPredicateEntries(root: Path) {
    val predicateRoot = root.resolve("data").resolve("demo").resolve("predicate")
    Files.createDirectories(predicateRoot)
    Files.writeString(
        predicateRoot.resolve("zombie_mainhand_stick.json"),
        """
        {
          "condition": "minecraft:entity_properties",
          "entity": "this",
          "predicate": {
            "type": "minecraft:zombie",
            "equipment": {
              "mainhand": {
                "items": "minecraft:stick",
                "count": 4,
                "nbt": "{marked:true}",
                "enchantments": {
                  "minecraft:sharpness": 3
                }
              }
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("zombie_offhand_stick.json"),
        """
        {
          "condition": "minecraft:entity_properties",
          "entity": "this",
          "predicate": {
            "type": "minecraft:zombie",
            "equipment": {
              "offhand": {
                "items": "minecraft:stick"
              }
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("zombie_helmet_nbt.json"),
        """
        {
          "condition": "minecraft:entity_properties",
          "entity": "this",
          "predicate": {
            "type": "minecraft:zombie",
            "nbt": {
              "ArmorItems": [
                {
                  "id": "minecraft:iron_helmet",
                  "count": 1,
                  "components": {}
                }
              ]
            }
          }
        }
        """.trimIndent(),
    )
}

internal fun writePredicatePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "execute condition test"
          }
        }
        """.trimIndent(),
    )
    val predicateRoot = root.resolve("data").resolve("demo").resolve("predicate")
    Files.createDirectories(predicateRoot)
    Files.writeString(
        predicateRoot.resolve("is_player.json"),
        """
        {
          "condition": "minecraft:entity_properties",
          "entity": "this",
          "predicate": {
            "type": "minecraft:player"
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("in_nether.json"),
        """
        {
          "condition": "minecraft:location_check",
          "predicate": {
            "dimension": "minecraft:the_nether"
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("in_forest.json"),
        """
        {
          "condition": "minecraft:location_check",
          "predicate": {
            "biome": "minecraft:forest"
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("in_desert.json"),
        """
        {
          "condition": "minecraft:location_check",
          "predicate": {
            "biome": "minecraft:desert"
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("at_four.json"),
        """
        {
          "condition": "minecraft:location_check",
          "predicate": {
            "position": {
              "x": 4,
              "y": 64,
              "z": 4
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("debug_stone.json"),
        """
        {
          "condition": "minecraft:location_check",
          "predicate": {
            "block": {
              "blocks": "#demo:debug_blocks",
              "state": {
                "mode": "debug"
              }
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("debug_dirt.json"),
        """
        {
          "condition": "minecraft:location_check",
          "predicate": {
            "block": {
              "blocks": "minecraft:dirt",
              "state": {
                "mode": "debug"
              }
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        predicateRoot.resolve("void_air.json"),
        """
        {
          "condition": "minecraft:location_check",
          "predicate": {
            "block": {
              "blocks": "minecraft:air"
            }
          }
        }
        """.trimIndent(),
    )
    val blockTagRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("tags")
            .resolve("block")
    Files.createDirectories(blockTagRoot)
    Files.writeString(
        blockTagRoot.resolve("debug_blocks.json"),
        """
        {
          "values": [
            "minecraft:stone"
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(predicateRoot.resolve("false.json"), "false")
    return root
}

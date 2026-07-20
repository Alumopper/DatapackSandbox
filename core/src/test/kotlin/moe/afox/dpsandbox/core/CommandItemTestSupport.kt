package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path

internal fun writeLootPack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "command expansion test"
          }
        }
        """.trimIndent(),
    )
    val lootRoot = root.resolve("data").resolve("demo").resolve("loot_table")
    Files.createDirectories(lootRoot)
    Files.writeString(
        lootRoot.resolve("gift.json"),
        """
        {
          "type": "minecraft:command",
          "pools": [
            {
              "rolls": 1,
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:diamond",
                  "functions": [
                    {
                      "function": "minecraft:set_item",
                      "item": "minecraft:emerald"
                    },
                    {
                      "function": "minecraft:set_name",
                      "name": { "text": "Gift" }
                    },
                    {
                      "function": "minecraft:set_lore",
                      "lore": [
                        { "text": "from loot" }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeLootSourcePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "loot source test"
          }
        }
        """.trimIndent(),
    )
    val lootRoot = root.resolve("data").resolve("demo").resolve("loot_table")
    Files.createDirectories(lootRoot)
    Files.writeString(
        lootRoot.resolve("fish.json"),
        """
        {
          "pools": [
            {
              "rolls": 1,
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:diamond"
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("entity_context.json"),
        """
        {
          "type": "minecraft:entity",
          "pools": [
            {
              "rolls": 1,
              "conditions": [
                {
                  "condition": "minecraft:entity_properties",
                  "entity": "this",
                  "predicate": {
                    "type": "minecraft:zombie"
                  }
                }
              ],
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:gold_ingot"
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("copy_name.json"),
        """
        {
          "type": "minecraft:entity",
          "pools": [
            {
              "rolls": 1,
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:name_tag",
                  "functions": [
                    {
                      "function": "minecraft:copy_name",
                      "source": "this"
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("block_context.json"),
        """
        {
          "type": "minecraft:block",
          "pools": [
            {
              "rolls": 1,
              "conditions": [
                {
                  "condition": "minecraft:block_state_property",
                  "block": "minecraft:stone",
                  "properties": {
                    "variant": "smooth"
                  }
                }
              ],
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:cobblestone"
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("copy_components.json"),
        """
        {
          "type": "minecraft:block",
          "pools": [
            {
              "rolls": 1,
              "conditions": [
                {
                  "condition": "minecraft:block_state_property",
                  "block": "minecraft:stone"
                }
              ],
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:iron_nugget",
                  "functions": [
                    {
                      "function": "minecraft:copy_components",
                      "source": "tool",
                      "include": [
                        "minecraft:damage",
                        "demo:copied",
                        "demo:skip"
                      ],
                      "exclude": [
                        "demo:skip"
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("apply_bonus.json"),
        """
        {
          "type": "minecraft:block",
          "pools": [
            {
              "rolls": 1,
              "conditions": [
                {
                  "condition": "minecraft:block_state_property",
                  "block": "minecraft:stone"
                }
              ],
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:raw_gold",
                  "functions": [
                    {
                      "function": "minecraft:set_count",
                      "count": 1
                    },
                    {
                      "function": "minecraft:apply_bonus",
                      "enchantment": "minecraft:fortune",
                      "formula": "minecraft:binomial_with_bonus_count",
                      "parameters": {
                        "extra": 1,
                        "probability": 1.0
                      }
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("equipment_context.json"),
        """
        {
          "type": "minecraft:equipment",
          "pools": [
            {
              "rolls": 1,
              "conditions": [
                {
                  "condition": "minecraft:match_tool",
                  "predicate": {
                    "items": "minecraft:stick"
                  }
                }
              ],
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:apple"
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("enchanted.json"),
        """
        {
          "type": "minecraft:command",
          "pools": [
            {
              "rolls": 1,
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:experience_bottle",
                  "functions": [
                    {
                      "function": "minecraft:enchant_randomly",
                      "options": [
                        "minecraft:sharpness"
                      ]
                    },
                    {
                      "function": "minecraft:enchant_with_levels",
                      "options": [
                        "minecraft:unbreaking"
                      ],
                      "levels": 3
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("tag_items.json"),
        """
        {
          "type": "minecraft:command",
          "pools": [
            {
              "rolls": 1,
              "entries": [
                {
                  "type": "minecraft:tag",
                  "name": "demo:ore_drops",
                  "expand": false,
                  "functions": [
                    {
                      "function": "minecraft:set_count",
                      "count": 2
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("tag_pick.json"),
        """
        {
          "type": "minecraft:command",
          "pools": [
            {
              "rolls": 1,
              "entries": [
                {
                  "type": "minecraft:tag",
                  "name": "demo:ore_drops",
                  "expand": true,
                  "functions": [
                    {
                      "function": "minecraft:set_count",
                      "count": 2
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        lootRoot.resolve("reference_function.json"),
        """
        {
          "type": "minecraft:command",
          "pools": [
            {
              "rolls": 1,
              "entries": [
                {
                  "type": "minecraft:item",
                  "name": "minecraft:lapis_lazuli",
                  "functions": [
                    {
                      "function": "minecraft:reference",
                      "name": "demo:referenced_loot"
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    val itemTagRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("tags")
            .resolve("item")
    Files.createDirectories(itemTagRoot)
    Files.writeString(
        itemTagRoot.resolve("ore_drops.json"),
        """
        {
          "values": [
            "minecraft:raw_gold",
            "#demo:bonus_drops",
            { "id": "#demo:missing_optional", "required": false }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        itemTagRoot.resolve("bonus_drops.json"),
        """
        {
          "values": [
            "minecraft:emerald"
          ]
        }
        """.trimIndent(),
    )
    val modifierRoot = root.resolve("data").resolve("demo").resolve("item_modifier")
    Files.createDirectories(modifierRoot)
    Files.writeString(
        modifierRoot.resolve("referenced_loot.json"),
        """
        [
          {
            "function": "minecraft:set_components",
            "components": {
              "demo:referenced_loot": "applied"
            }
          },
          {
            "function": "minecraft:set_count",
            "count": 3
          }
        ]
        """.trimIndent(),
    )
    return root
}

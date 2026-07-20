package moe.afox.dpsandbox.core

import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

internal fun writeStructurePlacePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "place structure test"
          }
        }
        """.trimIndent(),
    )
    val structureRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("worldgen")
            .resolve("structure")
    Files.createDirectories(structureRoot)
    Files.writeString(
        structureRoot.resolve("room.json"),
        """
        {
          "blocks": [
            { "offset": [0, 0, 0], "id": "minecraft:stone" },
            {
              "offset": [1, 0, 0],
              "id": "minecraft:chest",
              "properties": { "facing": "north" },
              "nbt": { "CustomName": "marker" }
            }
          ],
          "entities": [
            {
              "offset": [0.5, 1.0, 0.5],
              "type": "minecraft:pig",
              "tags": ["placed_structure"],
              "dimension": "minecraft:the_nether",
              "health": 6.0
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        structureRoot.resolve("palette_room.json"),
        """
        {
          "palette": [
            { "Name": "minecraft:polished_deepslate" },
            {
              "Name": "minecraft:barrel",
              "Properties": { "facing": "east" }
            }
          ],
          "blocks": [
            { "pos": [0, 0, 0], "state": 0 },
            {
              "pos": [1, 0, 0],
              "state": 1,
              "nbt": { "CustomName": "palette" }
            }
          ],
          "entities": [
            {
              "pos": [0.5, 1.0, 0.5],
              "nbt": {
                "id": "minecraft:armor_stand",
                "Tags": ["palette_structure"]
              }
            }
          ]
        }
        """.trimIndent(),
    )
    val nbtStructureRoot = root.resolve("data").resolve("demo").resolve("structures")
    Files.createDirectories(nbtStructureRoot)
    writeBinaryStructureNbt(nbtStructureRoot.resolve("binary_room.nbt"))
    return root
}

internal fun writeBinaryStructureNbt(file: Path) {
    Files.newOutputStream(file).use { output ->
        DataOutputStream(output).use { nbt ->
            nbt.writeByte(10)
            nbt.writeStructureNbtString("")

            nbt.writeNbtHeader(9, "palette")
            nbt.writeByte(10)
            nbt.writeInt(2)
            nbt.writeNbtHeader(8, "Name")
            nbt.writeStructureNbtString("minecraft:cut_copper")
            nbt.writeByte(0)
            nbt.writeNbtHeader(8, "Name")
            nbt.writeStructureNbtString("minecraft:furnace")
            nbt.writeNbtHeader(10, "Properties")
            nbt.writeNbtHeader(8, "facing")
            nbt.writeStructureNbtString("south")
            nbt.writeByte(0)
            nbt.writeByte(0)

            nbt.writeNbtHeader(9, "blocks")
            nbt.writeByte(10)
            nbt.writeInt(2)
            nbt.writeNbtHeader(9, "pos")
            nbt.writeByte(3)
            nbt.writeInt(3)
            nbt.writeInt(0)
            nbt.writeInt(0)
            nbt.writeInt(0)
            nbt.writeNbtHeader(3, "state")
            nbt.writeInt(0)
            nbt.writeByte(0)
            nbt.writeNbtHeader(9, "pos")
            nbt.writeByte(3)
            nbt.writeInt(3)
            nbt.writeInt(1)
            nbt.writeInt(0)
            nbt.writeInt(0)
            nbt.writeNbtHeader(3, "state")
            nbt.writeInt(1)
            nbt.writeNbtHeader(10, "nbt")
            nbt.writeNbtHeader(8, "CustomName")
            nbt.writeStructureNbtString("nbt")
            nbt.writeByte(0)
            nbt.writeByte(0)

            nbt.writeNbtHeader(9, "entities")
            nbt.writeByte(10)
            nbt.writeInt(1)
            nbt.writeNbtHeader(9, "pos")
            nbt.writeByte(6)
            nbt.writeInt(3)
            nbt.writeDouble(0.5)
            nbt.writeDouble(1.0)
            nbt.writeDouble(0.5)
            nbt.writeNbtHeader(10, "nbt")
            nbt.writeNbtHeader(8, "id")
            nbt.writeStructureNbtString("minecraft:armor_stand")
            nbt.writeNbtHeader(9, "Tags")
            nbt.writeByte(8)
            nbt.writeInt(1)
            nbt.writeStructureNbtString("binary_structure")
            nbt.writeByte(0)
            nbt.writeByte(0)

            nbt.writeByte(0)
        }
    }
}

internal fun DataOutputStream.writeNbtHeader(
    type: Int,
    name: String,
) {
    writeByte(type)
    writeStructureNbtString(name)
}

internal fun DataOutputStream.writeStructureNbtString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeShort(bytes.size)
    write(bytes)
}

internal fun writeProcessedStructurePlacePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "place processor test"
          }
        }
        """.trimIndent(),
    )
    val structureRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("worldgen")
            .resolve("structure")
    Files.createDirectories(structureRoot)
    Files.writeString(
        structureRoot.resolve("processed.json"),
        """
        {
          "processors": "demo:cleanup",
          "blocks": [
            { "offset": [0, 0, 0], "id": "minecraft:air" },
            { "offset": [1, 0, 0], "id": "minecraft:stone" },
            { "offset": [2, 0, 0], "id": "minecraft:dirt" },
            {
              "offset": [3, 0, 0],
              "id": "minecraft:jigsaw",
              "nbt": { "final_state": "minecraft:emerald_block" }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        structureRoot.resolve("advanced_processors.json"),
        """
        {
          "processors": "demo:advanced",
          "blocks": [
            { "offset": [0, 0, 0], "id": "minecraft:stone" },
            { "offset": [1, 0, 0], "id": "minecraft:stone" },
            { "offset": [2, 0, 0], "id": "minecraft:oak_planks" },
            { "offset": [3, 0, 0], "id": "minecraft:copper_block" },
            { "offset": [4, 0, 0], "id": "minecraft:diamond_block" }
          ]
        }
        """.trimIndent(),
    )
    val tagRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("tags")
            .resolve("block")
    Files.createDirectories(tagRoot)
    Files.writeString(
        tagRoot.resolve("protected.json"),
        """
        {
          "values": ["minecraft:obsidian"]
        }
        """.trimIndent(),
    )
    Files.writeString(
        tagRoot.resolve("wooden.json"),
        """
        {
          "values": ["minecraft:oak_planks"]
        }
        """.trimIndent(),
    )
    val processorRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("worldgen")
            .resolve("processor_list")
    Files.createDirectories(processorRoot)
    Files.writeString(
        processorRoot.resolve("cleanup.json"),
        """
        {
          "processors": [
            {
              "type": "minecraft:block_ignore",
              "blocks": ["minecraft:air"]
            },
            {
              "type": "minecraft:protected_blocks",
              "value": "demo:protected"
            },
            {
              "type": "minecraft:jigsaw_replacement"
            },
            {
              "type": "minecraft:rule",
              "rules": [
                {
                  "input_predicate": {
                    "predicate_type": "minecraft:matching_blocks",
                    "blocks": ["minecraft:stone"]
                  },
                  "output_state": {
                    "Name": "minecraft:gold_block"
                  }
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        processorRoot.resolve("advanced.json"),
        """
        {
          "processors": [
            {
              "type": "minecraft:capped",
              "limit": { "type": "minecraft:constant", "value": 1 },
              "delegate": {
                "type": "minecraft:rule",
                "rules": [
                  {
                    "input_predicate": {
                      "predicate_type": "minecraft:block_match",
                      "block": "minecraft:stone"
                    },
                    "output_state": { "Name": "minecraft:gold_block" }
                  }
                ]
              }
            },
            { "type": "minecraft:nop" },
            {
              "type": "minecraft:rule",
              "rules": [
                {
                  "input_predicate": {
                    "predicate_type": "minecraft:tag_match",
                    "tag": "demo:wooden"
                  },
                  "output_state": { "Name": "minecraft:stripped_oak_log" }
                },
                {
                  "input_predicate": {
                    "predicate_type": "minecraft:blockstate_match",
                    "block_state": { "Name": "minecraft:copper_block" }
                  },
                  "output_state": { "Name": "minecraft:waxed_copper_block" }
                },
                {
                  "input_predicate": {
                    "predicate_type": "minecraft:random_block_match",
                    "block": "minecraft:diamond_block",
                    "probability": 0.0
                  },
                  "output_state": { "Name": "minecraft:emerald_block" }
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeJigsawPlacePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "place jigsaw test"
          }
        }
        """.trimIndent(),
    )
    val structureRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("worldgen")
            .resolve("structure")
    Files.createDirectories(structureRoot)
    Files.writeString(
        structureRoot.resolve("jigsaw_room.json"),
        """
        {
          "blocks": [
            { "offset": [0, 0, 0], "id": "minecraft:stone" },
            { "offset": [1, 0, 0], "id": "minecraft:chest" }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        structureRoot.resolve("linked_root.json"),
        """
        {
          "blocks": [
            { "offset": [0, 0, 0], "id": "minecraft:stone" },
            {
              "offset": [1, 0, 0],
              "id": "minecraft:jigsaw",
              "properties": { "orientation": "east_up" },
              "nbt": {
                "pool": "demo:linked_child_pool",
                "name": "demo:target",
                "target": "demo:target",
                "final_state": "minecraft:air"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        structureRoot.resolve("linked_child.json"),
        """
        {
          "blocks": [
            { "offset": [0, 0, 0], "id": "minecraft:gold_block" },
            {
              "offset": [1, 0, 0],
              "id": "minecraft:jigsaw",
              "properties": { "orientation": "east_up" },
              "nbt": {
                "pool": "demo:linked_grandchild_pool",
                "name": "demo:target",
                "target": "demo:target",
                "final_state": "minecraft:air"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        structureRoot.resolve("linked_grandchild.json"),
        """
        {
          "blocks": [
            { "offset": [0, 0, 0], "id": "minecraft:diamond_block" }
          ]
        }
        """.trimIndent(),
    )
    val processorRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("worldgen")
            .resolve("processor_list")
    Files.createDirectories(processorRoot)
    Files.writeString(
        processorRoot.resolve("jigsaw_processors.json"),
        """
        {
          "processors": [
            {
              "type": "minecraft:rule",
              "rules": [
                {
                  "input_predicate": {
                    "predicate_type": "minecraft:matching_blocks",
                    "blocks": ["minecraft:stone"]
                  },
                  "output_state": "minecraft:emerald_block"
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )
    val poolRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("worldgen")
            .resolve("template_pool")
    Files.createDirectories(poolRoot)
    Files.writeString(
        poolRoot.resolve("start.json"),
        """
        {
          "fallback": "minecraft:empty",
          "elements": [
            {
              "weight": 1,
              "element": {
                "element_type": "minecraft:single_pool_element",
                "location": "demo:jigsaw_room",
                "processors": "demo:jigsaw_processors",
                "projection": "rigid"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        poolRoot.resolve("fallback_start.json"),
        """
        {
          "fallback": "demo:fallback_pool",
          "elements": [
            {
              "weight": 1,
              "element": {
                "element_type": "minecraft:empty_pool_element"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        poolRoot.resolve("fallback_pool.json"),
        """
        {
          "fallback": "minecraft:empty",
          "elements": [
            {
              "weight": 1,
              "element": {
                "element_type": "minecraft:single_pool_element",
                "location": "demo:jigsaw_room",
                "processors": "demo:jigsaw_processors",
                "projection": "rigid"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        poolRoot.resolve("linked_start.json"),
        """
        {
          "fallback": "minecraft:empty",
          "elements": [
            {
              "weight": 1,
              "element": {
                "element_type": "minecraft:single_pool_element",
                "location": "demo:linked_root",
                "projection": "rigid"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        poolRoot.resolve("linked_child_pool.json"),
        """
        {
          "fallback": "minecraft:empty",
          "elements": [
            {
              "weight": 1,
              "element": {
                "element_type": "minecraft:single_pool_element",
                "location": "demo:linked_child",
                "projection": "rigid"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        poolRoot.resolve("linked_grandchild_pool.json"),
        """
        {
          "fallback": "minecraft:empty",
          "elements": [
            {
              "weight": 1,
              "element": {
                "element_type": "minecraft:single_pool_element",
                "location": "demo:linked_grandchild",
                "projection": "rigid"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    Files.writeString(
        poolRoot.resolve("list_start.json"),
        """
        {
          "fallback": "minecraft:empty",
          "elements": [
            {
              "weight": 1,
              "element": {
                "element_type": "minecraft:list_pool_element",
                "elements": [
                  {
                    "element_type": "minecraft:empty_pool_element"
                  },
                  {
                    "element_type": "minecraft:single_pool_element",
                    "location": "demo:jigsaw_room",
                    "processors": "demo:jigsaw_processors",
                    "projection": "rigid"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent(),
    )
    val configuredRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("worldgen")
            .resolve("configured_feature")
    Files.createDirectories(configuredRoot)
    Files.writeString(
        configuredRoot.resolve("jigsaw_feature.json"),
        """
        {
          "type": "minecraft:simple_block",
          "config": {
            "to_place": {
              "type": "minecraft:simple_state_provider",
              "state": { "Name": "minecraft:lapis_block" }
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        poolRoot.resolve("feature_start.json"),
        """
        {
          "fallback": "minecraft:empty",
          "elements": [
            {
              "weight": 1,
              "element": {
                "element_type": "minecraft:feature_pool_element",
                "feature": "demo:jigsaw_feature"
              }
            }
          ]
        }
        """.trimIndent(),
    )
    return root
}

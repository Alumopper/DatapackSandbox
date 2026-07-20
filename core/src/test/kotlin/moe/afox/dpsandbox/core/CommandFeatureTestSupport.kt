package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path

internal fun writeFeaturePlacePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "place feature test"
          }
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
    val tagRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("tags")
            .resolve("block")
    Files.createDirectories(tagRoot)
    Files.writeString(
        tagRoot.resolve("soil.json"),
        """
        {
          "values": ["minecraft:dirt"]
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("simple_log.json"),
        """
        {
          "type": "minecraft:simple_block",
          "config": {
            "to_place": {
              "type": "minecraft:simple_state_provider",
              "state": {
                "Name": "minecraft:oak_log",
                "Properties": { "axis": "y" }
              }
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("flower_patch.json"),
        """
        {
          "type": "minecraft:random_patch",
          "config": {
            "tries": 3,
            "xz_spread": 1,
            "y_spread": 0,
            "feature": {
              "feature": {
                "type": "minecraft:simple_block",
                "config": {
                  "to_place": {
                    "type": "minecraft:simple_state_provider",
                    "state": { "Name": "minecraft:poppy" }
                  }
                }
              },
              "placement": []
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("selector_log.json"),
        """
        {
          "type": "minecraft:simple_random_selector",
          "config": {
            "features": [
              { "feature": "demo:simple_log" },
              {
                "feature": {
                  "type": "minecraft:simple_block",
                  "config": {
                    "to_place": {
                      "type": "minecraft:simple_state_provider",
                      "state": { "Name": "minecraft:birch_log" }
                    }
                  }
                }
              }
            ]
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("ore_cluster.json"),
        """
        {
          "type": "minecraft:ore",
          "config": {
            "size": 4,
            "targets": [
              {
                "target": {
                  "predicate_type": "minecraft:matching_blocks",
                  "blocks": ["minecraft:stone", "minecraft:deepslate"]
                },
                "state": { "Name": "minecraft:diamond_ore" }
              }
            ]
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("column.json"),
        """
        {
          "type": "minecraft:block_column",
          "config": {
            "direction": "up",
            "layers": [
              {
                "height": 2,
                "provider": {
                  "type": "minecraft:simple_state_provider",
                  "state": {
                    "Name": "minecraft:oak_log",
                    "Properties": { "axis": "y" }
                  }
                }
              },
              {
                "height": { "type": "minecraft:constant", "value": 1 },
                "provider": {
                  "type": "minecraft:simple_state_provider",
                  "state": { "Name": "minecraft:oak_leaves" }
                }
              }
            ]
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("replace_single.json"),
        """
        {
          "type": "minecraft:replace_single_block",
          "config": {
            "targets": [
              {
                "target": {
                  "predicate_type": "minecraft:matching_blocks",
                  "blocks": ["minecraft:stone"]
                },
                "state": { "Name": "minecraft:gold_block" }
              }
            ]
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("replace_blob.json"),
        """
        {
          "type": "minecraft:replace_blob",
          "config": {
            "target": "minecraft:stone",
            "state": { "Name": "minecraft:moss_block" },
            "radius": { "type": "minecraft:constant", "value": 1 }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("grass_patch.json"),
        """
        {
          "type": "minecraft:vegetation_patch",
          "config": {
            "replaceable": {
              "predicate_type": "minecraft:matching_block_tag",
              "tag": "demo:soil"
            },
            "ground_state": {
              "type": "minecraft:simple_state_provider",
              "state": { "Name": "minecraft:grass_block" }
            },
            "vegetation_feature": {
              "type": "minecraft:simple_block",
              "config": {
                "to_place": {
                  "type": "minecraft:simple_state_provider",
                  "state": { "Name": "minecraft:poppy" }
                }
              }
            },
            "xz_radius": { "type": "minecraft:constant", "value": 1 },
            "depth": 1
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("tree.json"),
        """
        {
          "type": "minecraft:tree",
          "config": {
            "dirt_provider": {
              "type": "minecraft:simple_state_provider",
              "state": { "Name": "minecraft:dirt" }
            },
            "trunk_provider": {
              "type": "minecraft:simple_state_provider",
              "state": {
                "Name": "minecraft:oak_log",
                "Properties": { "axis": "y" }
              }
            },
            "foliage_provider": {
              "type": "minecraft:simple_state_provider",
              "state": { "Name": "minecraft:oak_leaves" }
            },
            "trunk_placer": {
              "type": "minecraft:straight_trunk_placer",
              "base_height": 3,
              "height_rand_a": 0,
              "height_rand_b": 0
            },
            "foliage_placer": {
              "type": "minecraft:blob_foliage_placer",
              "radius": { "type": "minecraft:constant", "value": 1 },
              "offset": 0,
              "height": 1
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("basalt_columns.json"),
        """
        {
          "type": "minecraft:basalt_columns",
          "config": {
            "height": { "type": "minecraft:constant", "value": 3 },
            "reach": { "type": "minecraft:constant", "value": 1 },
            "state_provider": {
              "type": "minecraft:simple_state_provider",
              "state": { "Name": "minecraft:basalt" }
            }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("delta.json"),
        """
        {
          "type": "minecraft:delta_feature",
          "config": {
            "contents": { "Name": "minecraft:lava" },
            "rim": { "Name": "minecraft:magma_block" },
            "size": { "type": "minecraft:constant", "value": 1 },
            "rim_size": 1
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("lake.json"),
        """
        {
          "type": "minecraft:lake",
          "config": {
            "state": { "Name": "minecraft:water" },
            "barrier": { "Name": "minecraft:clay" },
            "radius": { "type": "minecraft:constant", "value": 1 },
            "depth": 1
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("spring.json"),
        """
        {
          "type": "minecraft:spring_feature",
          "config": {
            "state": { "Name": "minecraft:water" },
            "requires_block_below": false,
            "valid_blocks": ["minecraft:stone", "minecraft:netherrack"]
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("pile.json"),
        """
        {
          "type": "minecraft:block_pile",
          "config": {
            "state_provider": {
              "type": "minecraft:simple_state_provider",
              "state": { "Name": "minecraft:moss_block" }
            },
            "radius": 1
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("glowstone.json"),
        """
        {
          "type": "minecraft:glowstone_blob",
          "config": {
            "radius": 1
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("forest_rock.json"),
        """
        {
          "type": "minecraft:forest_rock",
          "config": {
            "state_provider": {
              "type": "minecraft:simple_state_provider",
              "state": { "Name": "minecraft:mossy_cobblestone" }
            },
            "radius": 1
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("nether_blob.json"),
        """
        {
          "type": "minecraft:netherrack_replace_blobs",
          "config": {
            "target_state": { "Name": "minecraft:basalt" },
            "replace_state": { "Name": "minecraft:netherrack" },
            "radius": 1
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("chorus.json"),
        """
        {
          "type": "minecraft:chorus_plant",
          "config": {
            "height": 3,
            "plant_state": { "Name": "minecraft:chorus_plant" },
            "flower_state": { "Name": "minecraft:chorus_flower" }
          }
        }
        """.trimIndent(),
    )
    Files.writeString(
        configuredRoot.resolve("disk.json"),
        """
        {
          "type": "minecraft:disk",
          "config": {
            "radius": { "type": "minecraft:constant", "value": 1 },
            "half_height": 0,
            "state_provider": {
              "type": "minecraft:simple_state_provider",
              "state": { "Name": "minecraft:clay" }
            },
            "targets": [
              {
                "predicate_type": "minecraft:matching_blocks",
                "blocks": ["minecraft:dirt"]
              }
            ]
          }
        }
        """.trimIndent(),
    )
    val placedRoot =
        root
            .resolve("data")
            .resolve("demo")
            .resolve("worldgen")
            .resolve("placed_feature")
    Files.createDirectories(placedRoot)
    Files.writeString(
        placedRoot.resolve("placed_stone.json"),
        """
        {
          "feature": "demo:simple_log",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_flowers.json"),
        """
        {
          "feature": "demo:flower_patch",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_selector.json"),
        """
        {
          "feature": "demo:selector_log",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_ore.json"),
        """
        {
          "feature": "demo:ore_cluster",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_column.json"),
        """
        {
          "feature": "demo:column",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_replace_single.json"),
        """
        {
          "feature": "demo:replace_single",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_replace_blob.json"),
        """
        {
          "feature": "demo:replace_blob",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_grass_patch.json"),
        """
        {
          "feature": "demo:grass_patch",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_tree.json"),
        """
        {
          "feature": "demo:tree",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_basalt_columns.json"),
        """
        {
          "feature": "demo:basalt_columns",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_delta.json"),
        """
        {
          "feature": "demo:delta",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_lake.json"),
        """
        {
          "feature": "demo:lake",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_spring.json"),
        """
        {
          "feature": "demo:spring",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_pile.json"),
        """
        {
          "feature": "demo:pile",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_glowstone.json"),
        """
        {
          "feature": "demo:glowstone",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_forest_rock.json"),
        """
        {
          "feature": "demo:forest_rock",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_nether_blob.json"),
        """
        {
          "feature": "demo:nether_blob",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_chorus.json"),
        """
        {
          "feature": "demo:chorus",
          "placement": []
        }
        """.trimIndent(),
    )
    Files.writeString(
        placedRoot.resolve("placed_disk.json"),
        """
        {
          "feature": "demo:disk",
          "placement": []
        }
        """.trimIndent(),
    )
    return root
}

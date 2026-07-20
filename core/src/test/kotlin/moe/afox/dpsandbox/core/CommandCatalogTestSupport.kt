package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path

internal fun fixturePack(): Path = Path.of("src/test/resources/packs/counter")

internal fun writeMissingFunctionTagPack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "missing function diagnostics test"
          }
        }
        """.trimIndent(),
    )
    val tagRoot =
        root
            .resolve("data")
            .resolve("minecraft")
            .resolve("tags")
            .resolve("function")
    Files.createDirectories(tagRoot)
    Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:missing_load"]}""")
    return root
}

internal fun writeChatTypePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "chat type test"
          }
        }
        """.trimIndent(),
    )
    val chatTypeRoot = root.resolve("data").resolve("minecraft").resolve("chat_type")
    Files.createDirectories(chatTypeRoot)
    Files.writeString(
        chatTypeRoot.resolve("say_command.json"),
        """
        {
          "chat": {
            "translation_key": "chat.type.sandbox_say",
            "parameters": ["sender", "content"]
          },
          "narration": {
            "translation_key": "chat.type.sandbox_say.narrate",
            "parameters": ["sender", "content"]
          }
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeDimensionPack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "dimension metadata test"
          }
        }
        """.trimIndent(),
    )
    val dimensionRoot = root.resolve("data").resolve("demo").resolve("dimension")
    Files.createDirectories(dimensionRoot)
    Files.writeString(
        dimensionRoot.resolve("debug.json"),
        """
        {
          "type": "demo:debug_type",
          "generator": {
            "type": "minecraft:noise",
            "settings": "minecraft:overworld",
            "biome_source": {
              "type": "minecraft:fixed",
              "biome": "minecraft:plains"
            }
          }
        }
        """.trimIndent(),
    )
    val dimensionTypeRoot = root.resolve("data").resolve("demo").resolve("dimension_type")
    Files.createDirectories(dimensionTypeRoot)
    Files.writeString(
        dimensionTypeRoot.resolve("debug_type.json"),
        """
        {
          "ultrawarm": false,
          "natural": false,
          "coordinate_scale": 8.0,
          "piglin_safe": true,
          "respawn_anchor_works": true,
          "bed_works": false,
          "has_raids": false,
          "has_skylight": false,
          "has_ceiling": true,
          "ambient_light": 0.25,
          "logical_height": 256,
          "min_y": 0,
          "height": 256,
          "infiniburn": "#minecraft:infiniburn_overworld",
          "effects": "minecraft:the_nether",
          "monster_spawn_light_level": 7,
          "monster_spawn_block_light_limit": 0
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeEnchantmentPack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "enchantment metadata test"
          }
        }
        """.trimIndent(),
    )
    val enchantmentRoot = root.resolve("data").resolve("demo").resolve("enchantment")
    Files.createDirectories(enchantmentRoot)
    Files.writeString(
        enchantmentRoot.resolve("debug_edge.json"),
        """
        {
          "description": { "text": "Debug Edge" },
          "supported_items": "#minecraft:enchantable/weapon",
          "primary_items": "#minecraft:enchantable/sword",
          "weight": 2,
          "max_level": 4,
          "min_cost": { "base": 1, "per_level_above_first": 10 },
          "max_cost": { "base": 20, "per_level_above_first": 10 },
          "anvil_cost": 4,
          "slots": ["mainhand"],
          "effects": {}
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeEntityVariantPack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "entity variant metadata test"
          }
        }
        """.trimIndent(),
    )
    val paintingRoot = root.resolve("data").resolve("demo").resolve("painting_variant")
    Files.createDirectories(paintingRoot)
    Files.writeString(
        paintingRoot.resolve("small_canvas.json"),
        """
        {
          "asset_id": "demo:small_canvas",
          "width": 2,
          "height": 1
        }
        """.trimIndent(),
    )
    val wolfRoot = root.resolve("data").resolve("demo").resolve("wolf_variant")
    Files.createDirectories(wolfRoot)
    Files.writeString(
        wolfRoot.resolve("ashen.json"),
        """
        {
          "wild_texture": "demo:entity/wolf/ashen",
          "tame_texture": "demo:entity/wolf/ashen_tame",
          "angry_texture": "demo:entity/wolf/ashen_angry"
        }
        """.trimIndent(),
    )
    val wolfSoundRoot = root.resolve("data").resolve("demo").resolve("wolf_sound_variant")
    Files.createDirectories(wolfSoundRoot)
    Files.writeString(
        wolfSoundRoot.resolve("quiet.json"),
        """
        {
          "ambient_sound": "minecraft:entity.wolf.ambient",
          "death_sound": "minecraft:entity.wolf.death",
          "growl_sound": "minecraft:entity.wolf.growl",
          "hurt_sound": "minecraft:entity.wolf.hurt",
          "pant_sound": "minecraft:entity.wolf.pant",
          "whine_sound": "minecraft:entity.wolf.whine"
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeTrimPack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "armor trim metadata test"
          }
        }
        """.trimIndent(),
    )
    val materialRoot = root.resolve("data").resolve("demo").resolve("trim_material")
    Files.createDirectories(materialRoot)
    Files.writeString(
        materialRoot.resolve("debug_gold.json"),
        """
        {
          "asset_name": "debug_gold",
          "ingredient": "minecraft:gold_ingot",
          "item_model_index": 0.73,
          "description": { "text": "Debug Gold" }
        }
        """.trimIndent(),
    )
    val patternRoot = root.resolve("data").resolve("demo").resolve("trim_pattern")
    Files.createDirectories(patternRoot)
    Files.writeString(
        patternRoot.resolve("debug_spire.json"),
        """
        {
          "asset_id": "demo:spire",
          "template_item": "minecraft:spire_armor_trim_smithing_template",
          "description": { "text": "Debug Spire" },
          "decal": false
        }
        """.trimIndent(),
    )
    return root
}

internal fun writeItemComponentResourcePack(root: Path): Path {
    Files.writeString(
        root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
        """
        {
          "pack": {
            "pack_format": 107.1,
            "description": "item component registry metadata test"
          }
        }
        """.trimIndent(),
    )
    val instrumentRoot = root.resolve("data").resolve("demo").resolve("instrument")
    Files.createDirectories(instrumentRoot)
    Files.writeString(
        instrumentRoot.resolve("debug_horn.json"),
        """
        {
          "sound_event": "minecraft:item.goat_horn.sound.0",
          "use_duration": 140,
          "range": 24.0,
          "description": { "text": "Debug Horn" }
        }
        """.trimIndent(),
    )
    val songRoot = root.resolve("data").resolve("demo").resolve("jukebox_song")
    Files.createDirectories(songRoot)
    Files.writeString(
        songRoot.resolve("debug_song.json"),
        """
        {
          "sound_event": "minecraft:music_disc.cat",
          "description": { "text": "Debug Song" },
          "length_in_seconds": 12.5,
          "comparator_output": 7
        }
        """.trimIndent(),
    )
    val bannerRoot = root.resolve("data").resolve("demo").resolve("banner_pattern")
    Files.createDirectories(bannerRoot)
    Files.writeString(
        bannerRoot.resolve("debug_banner.json"),
        """
        {
          "asset_id": "demo:debug_banner",
          "translation_key": "block.demo.banner.debug"
        }
        """.trimIndent(),
    )
    val equipmentRoot = root.resolve("data").resolve("demo").resolve("equipment_asset")
    Files.createDirectories(equipmentRoot)
    Files.writeString(
        equipmentRoot.resolve("debug_armor.json"),
        """
        {
          "texture": "demo:models/equipment/debug_armor",
          "layers": [
            { "texture": "demo:models/equipment/debug_armor", "dyeable": false }
          ]
        }
        """.trimIndent(),
    )
    return root
}

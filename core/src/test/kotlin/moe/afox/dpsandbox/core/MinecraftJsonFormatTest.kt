package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftJsonFormatTest {
    private val currentEntityTargets = setOf(
        "this",
        "attacker",
        "direct_attacker",
        "attacking_player",
        "target_entity",
        "interacting_entity",
    )

    private val minecraftJsonRoots = listOf(
        Path.of("src/test/resources/packs"),
        Path.of("../examples"),
    )

    @Test
    fun `minecraft json resources parse as json`() {
        minecraftJsonFiles().forEach { file ->
            val text = Files.readString(file)
            JsonParser.parseString(text)
        }
    }

    @Test
    fun `pack mcmeta uses a supported data pack format`() {
        minecraftJsonFiles()
            .filter { it.name == "pack.mcmeta" }
            .forEach { file ->
                val root = JsonParser.parseString(Files.readString(file)).asJsonObject
                val actual = DataPackFormat.parse(root.getAsJsonObject("pack").get("pack_format"))
                assertTrue(
                    VersionProfiles.all.any { it.dataPackFormat.matches(actual) },
                    "Unsupported pack_format $actual in $file",
                )
            }
    }

    @Test
    fun `loot tables avoid removed set_nbt function`() {
        minecraftJsonFiles()
            .filter { it.toString().replace('\\', '/').contains("/loot_table/") }
            .forEach { file ->
                val text = Files.readString(file)
                assertFalse(text.contains("minecraft:set_nbt"), "Use minecraft:set_custom_data in $file")
            }
    }

    @Test
    fun `entity predicates use type_specific for player subtype`() {
        minecraftJsonFiles()
            .filter { it.toString().replace('\\', '/').contains("/predicate/") }
            .forEach { file ->
                val root = JsonParser.parseString(Files.readString(file))
                if (root.isJsonObject) assertNoLegacyPlayerPredicate(root.asJsonObject, file)
            }
    }

    @Test
    fun `entity predicate conditions use current entity target ids`() {
        minecraftJsonFiles()
            .filter { it.toString().replace('\\', '/').contains("/predicate/") }
            .forEach { file ->
                val root = JsonParser.parseString(Files.readString(file))
                if (root.isJsonObject) assertValidEntityTargets(root.asJsonObject, file)
            }
    }

    private fun minecraftJsonFiles(): List<Path> =
        minecraftJsonRoots.flatMap { root ->
            if (!Files.exists(root)) emptyList() else Files.walk(root).use { walk ->
                walk.filter {
                    Files.isRegularFile(it) &&
                        (it.name.endsWith(".json") || it.name == "pack.mcmeta") &&
                        !it.name.endsWith(".dps.json")
                }.toList()
            }
        }

    private fun assertNoLegacyPlayerPredicate(root: JsonObject, file: Path) {
        root.entrySet().forEach { (key, value) ->
            assertTrue(key != "player" || !root.has("type"), "Use type_specific for player entity predicates in $file")
            if (value.isJsonObject) assertNoLegacyPlayerPredicate(value.asJsonObject, file)
            if (value.isJsonArray) value.asJsonArray.filter { it.isJsonObject }.forEach { assertNoLegacyPlayerPredicate(it.asJsonObject, file) }
        }
    }

    private fun assertValidEntityTargets(root: JsonObject, file: Path) {
        val condition = root.get("condition")?.takeIf { it.isJsonPrimitive }?.asString?.substringAfter(':')
        if (condition == "entity_properties" || condition == "entity_scores") {
            val entity = root.get("entity")?.takeIf { it.isJsonPrimitive }?.asString
            if (entity != null) assertTrue(entity in currentEntityTargets, "Invalid entity target '$entity' in $file")
        }
        root.entrySet().forEach { (_, value) ->
            if (value.isJsonObject) assertValidEntityTargets(value.asJsonObject, file)
            if (value.isJsonArray) value.asJsonArray.filter { it.isJsonObject }.forEach { assertValidEntityTargets(it.asJsonObject, file) }
        }
    }
}

package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatapackResourceIndexTest {
    @Test
    fun `loads current recipe item modifier and tag resources`() {
        val pack = writePack(
            name = "current",
            packFormat = "107.1",
            recipeDir = "recipe",
            itemModifierDir = "item_modifier",
            itemTagDir = "item",
            recipeMarker = "current",
        )

        val sandbox = createSandbox("26.2", listOf(pack))
        val datapack = sandbox.datapack

        assertEquals("current", datapack.recipe(ResourceLocation.parse("demo:marker")).root.asJsonObject.get("marker").asString)
        assertEquals("item_modifier", datapack.itemModifier(ResourceLocation.parse("demo:mark_item")).kind)
        assertEquals("current", datapack.rawResource("damage-type", ResourceLocation.parse("demo:debug_damage")).root.asJsonObject.get("marker").asString)
        assertEquals("26.2", datapack.rawResource("worldgen/configured_feature", ResourceLocation.parse("demo:debug_feature")).version)

        val tag = datapack.tag("item", ResourceLocation.parse("demo:debug_items"))
        assertFalse(tag.replace)
        assertEquals(listOf("minecraft:stick", "#demo:optional_items"), tag.values.map { it.id })
        assertEquals(listOf(true, false), tag.values.map { it.required })

        assertTrue(datapack.resourceIndex.any { it.type == "recipe" && it.id == ResourceLocation.parse("demo:marker") && it.active })
        assertTrue(datapack.resourceIndex.any { it.type == "item_modifier" && it.id == ResourceLocation.parse("demo:mark_item") && it.active })
        assertTrue(datapack.resourceIndex.any { it.type == "tag/item" && it.id == ResourceLocation.parse("demo:debug_items") && it.active })
        assertEquals(
            ResourceBehaviorLevel.MODELED,
            datapack.resourceIndex.single { it.type == "recipe" && it.id == ResourceLocation.parse("demo:marker") }.behaviorLevel,
        )
        assertEquals(
            ResourceBehaviorLevel.OBSERVED_NOOP,
            datapack.resourceIndex.single { it.type == "damage_type" && it.id == ResourceLocation.parse("demo:debug_damage") }.behaviorLevel,
        )
        assertEquals(
            ResourceBehaviorLevel.OBSERVED_NOOP,
            datapack.resourceIndex.single { it.type == "tag/item" && it.id == ResourceLocation.parse("demo:debug_items") }.behaviorLevel,
        )
        expectedRawKinds.forEach { kind ->
            assertTrue(kind in datapack.rawResources.keys, "missing raw resource kind $kind")
            assertTrue(datapack.resourceIndex.any { it.type == kind && it.active }, "missing resource index for $kind")
        }

        sandbox.executeCommand("datapack list")
        val payload = sandbox.world.outputs.single { it.command == "datapack list" }.payload?.asJsonObject
            ?: error("missing datapack list payload")
        assertEquals(datapack.rawResources.size, payload.get("rawResourceKinds").asInt)
        assertEquals(datapack.rawResources.values.sumOf { it.size }, payload.get("rawResources").asInt)
        assertEquals(datapack.tags.size, payload.get("tags").asInt)
        assertEquals(datapack.resourceIndex.count { it.active }, payload.get("activeResources").asInt)
        assertEquals(0, payload.get("overriddenResources").asInt)
        assertEquals(0, payload.getAsJsonArray("resourceOverrides").size())
    }

    @Test
    fun `loads legacy resource aliases from zip datapacks`() {
        val root = writePack(
            name = "legacy",
            packFormat = "26",
            recipeDir = "recipes",
            itemModifierDir = "item_modifiers",
            itemTagDir = "items",
            recipeMarker = "legacy",
        )
        val zip = zipPack(root, Files.createTempFile("dps-legacy-pack", ".zip"))

        val sandbox = createSandbox("1.20.4", listOf(zip))
        val datapack = sandbox.datapack

        assertEquals("legacy", datapack.recipe(ResourceLocation.parse("demo:marker")).root.asJsonObject.get("marker").asString)
        assertNotNull(datapack.itemModifier(ResourceLocation.parse("demo:mark_item")))
        assertEquals("1.20.4", datapack.rawResource("damage_type", ResourceLocation.parse("demo:debug_damage")).version)
        assertEquals(2, datapack.tag("items", ResourceLocation.parse("demo:debug_items")).values.size)
    }

    @Test
    fun `datapack load cache invalidates when directory content changes`() {
        DatapackLoader.clearCache()
        try {
            val pack = writeFunctionTagPack("cache-invalidate", replace = false, functionName = "cached", message = "cached one")
            val profile = VersionProfiles.get("26.2")
            val functionId = ResourceLocation.parse("demo:cached")

            val first = DatapackLoader.load(listOf(pack), profile)
            assertEquals("say cached one", first.function(functionId).lines.single().command)

            Files.writeString(pack.resolve("data").resolve("demo").resolve("function").resolve("cached.mcfunction"), "say cached two")

            val second = DatapackLoader.load(listOf(pack), profile)
            assertEquals("say cached two", second.function(functionId).lines.single().command)
        } finally {
            DatapackLoader.clearCache()
        }
    }

    @Test
    fun `datapack load cache returns isolated json resources`() {
        DatapackLoader.clearCache()
        try {
            val pack = writePack("cache-copy", "107.1", "recipe", "item_modifier", "item", "original")
            val profile = VersionProfiles.get("26.2")
            val recipeId = ResourceLocation.parse("demo:marker")

            val first = DatapackLoader.load(listOf(pack), profile)
            first.recipe(recipeId).root.asJsonObject.addProperty("marker", "mutated")

            val second = DatapackLoader.load(listOf(pack), profile)
            assertEquals("original", second.recipe(recipeId).root.asJsonObject.get("marker").asString)
        } finally {
            DatapackLoader.clearCache()
        }
    }

    @Test
    fun `resource index records overridden resources`() {
        val first = writePack("overlay-first", "107.1", "recipe", "item_modifier", "item", "first")
        val second = writePack("overlay-second", "107.1", "recipe", "item_modifier", "item", "second")

        val sandbox = createSandbox("26.2", listOf(first, second))
        val datapack = sandbox.datapack

        assertEquals("second", datapack.recipe(ResourceLocation.parse("demo:marker")).root.asJsonObject.get("marker").asString)
        val recipeEntries = datapack.resourceIndex.filter { it.type == "recipe" && it.id == ResourceLocation.parse("demo:marker") }

        assertEquals(2, recipeEntries.size)
        assertFalse(recipeEntries[0].active)
        assertTrue(recipeEntries[0].overriddenBy?.contains("overlay-second") == true, recipeEntries[0].toString())
        assertTrue(recipeEntries[1].active)
        assertTrue(recipeEntries[1].overrides?.contains("overlay-first") == true, recipeEntries[1].toString())

        assertEquals("second", datapack.rawResource("damage_type", ResourceLocation.parse("demo:debug_damage")).root.asJsonObject.get("marker").asString)
        val damageEntries = datapack.resourceIndex.filter { it.type == "damage_type" && it.id == ResourceLocation.parse("demo:debug_damage") }
        assertEquals(2, damageEntries.size)
        assertFalse(damageEntries[0].active)
        assertTrue(damageEntries[1].active)

        sandbox.executeCommand("datapack list")
        val payload = sandbox.world.outputs.single { it.command == "datapack list" }.payload?.asJsonObject
            ?: error("missing datapack list payload")
        val overlayEntries = datapack.resourceIndex.filter { !it.active || it.overrides != null || it.overriddenBy != null }
        assertEquals(datapack.resourceIndex.count { !it.active }, payload.get("overriddenResources").asInt)
        assertEquals(overlayEntries.size, payload.getAsJsonArray("resourceOverrides").size())

        val recipeOverride = payload.getAsJsonArray("resourceOverrides")
            .map { it.asJsonObject }
            .single { it.get("type").asString == "recipe" && it.get("id").asString == "demo:marker" && it.get("active").asBoolean }
        assertTrue(recipeOverride.get("overrides").asString.contains("overlay-first"), recipeOverride.toString())
        assertEquals("modeled", recipeOverride.get("behavior").asString)

        val overriddenRecipe = payload.getAsJsonArray("resourceOverrides")
            .map { it.asJsonObject }
            .single { it.get("type").asString == "recipe" && it.get("id").asString == "demo:marker" && !it.get("active").asBoolean }
        assertTrue(overriddenRecipe.get("overriddenBy").asString.contains("overlay-second"), overriddenRecipe.toString())
    }

    @Test
    fun `function tag replace resets earlier load entries`() {
        val first = writeFunctionTagPack("load-first", replace = false, functionName = "first_load", message = "first load")
        val second = writeFunctionTagPack("load-second", replace = true, functionName = "second_load", message = "second load")

        val sandbox = createSandbox("26.2", listOf(first, second))

        assertEquals(listOf(ResourceLocation.parse("demo:second_load")), sandbox.datapack.loadFunctions)
        sandbox.runLoad()
        assertEquals(listOf("<Server> second load"), sandbox.world.outputs.map { it.text })
    }

    @Test
    fun `optional missing function tag entries are skipped`() {
        val pack = writeFunctionTagPack(
            name = "load-optional",
            replace = false,
            functionName = "present_load",
            message = "present load",
            valuesJson = "{ \"id\": \"demo:missing_optional\", \"required\": false }, \"demo:present_load\"",
        )

        val sandbox = createSandbox("26.2", listOf(pack))

        assertEquals(listOf(ResourceLocation.parse("demo:present_load")), sandbox.datapack.loadFunctions)
        sandbox.runLoad()
        assertEquals(listOf("<Server> present load"), sandbox.world.outputs.map { it.text })
    }

    @Test
    fun `function tag validation reports typed field errors with location and version`() {
        val pack = writeFunctionTagPack(
            name = "invalid-function-tag-required",
            replace = false,
            functionName = "present_load",
            message = "present load",
            valuesJson = """{ "id": "demo:present_load", "required": "no" }""",
        )

        val error = assertFailsWith<SandboxException> {
            createSandbox("26.2", listOf(pack))
        }

        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertEquals("26.2", error.version)
        assertTrue(error.message.contains("Function tag values[0] 'required' must be a boolean"), error.render())
        assertTrue(error.location?.file?.endsWith("load.json") == true, error.render())
    }

    @Test
    fun `function tag validation reports invalid resource locations with location and version`() {
        val pack = writeFunctionTagPack(
            name = "invalid-function-tag-id",
            replace = false,
            functionName = "present_load",
            message = "present load",
            valuesJson = """{ "id": "Demo:present_load" }""",
        )

        val error = assertFailsWith<SandboxException> {
            createSandbox("26.2", listOf(pack))
        }

        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertEquals("26.2", error.version)
        assertTrue(error.message.contains("Function tag values[0] has invalid resource location 'Demo:present_load'"), error.render())
        assertTrue(error.location?.file?.endsWith("load.json") == true, error.render())
    }

    @Test
    fun `regular tag replace resets earlier values and records overlay`() {
        val first = writeTagPack("tag-first", replace = false, value = "minecraft:stick")
        val second = writeTagPack("tag-second", replace = true, value = "minecraft:diamond")

        val sandbox = createSandbox("26.2", listOf(first, second))
        val tag = sandbox.datapack.tag("item", ResourceLocation.parse("demo:replace_items"))

        assertTrue(tag.replace)
        assertEquals(listOf("minecraft:diamond"), tag.values.map { it.id })

        val entries = sandbox.datapack.resourceIndex
            .filter { it.type == "tag/item" && it.id == ResourceLocation.parse("demo:replace_items") }
        assertEquals(2, entries.size)
        assertFalse(entries[0].active)
        assertTrue(entries[0].overriddenBy?.contains("tag-second") == true, entries[0].toString())
        assertTrue(entries[1].active)
        assertTrue(entries[1].overrides?.contains("tag-first") == true, entries[1].toString())
    }

    @Test
    fun `regular tag validation reports replace type errors with location and version`() {
        val pack = writeRawTagPack(
            name = "invalid-tag-replace",
            body = """
            {
              "replace": "yes",
              "values": ["minecraft:stick"]
            }
            """.trimIndent(),
        )

        val error = assertFailsWith<SandboxException> {
            createSandbox("26.2", listOf(pack))
        }

        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertEquals("26.2", error.version)
        assertTrue(error.message.contains("Tag 'item/demo:replace_items' 'replace' must be a boolean"), error.render())
        assertTrue(error.location?.file?.endsWith("replace_items.json") == true, error.render())
    }

    @Test
    fun `json resource validation reports invalid resource ids with location and version`() {
        val pack = writeInvalidRecipeIdPack()

        val error = assertFailsWith<SandboxException> {
            createSandbox("26.2", listOf(pack))
        }

        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertEquals("26.2", error.version)
        assertTrue(error.message.contains("Invalid recipe resource id 'Demo:marker'"), error.render())
        assertTrue(error.message.contains("Invalid namespace: Demo"), error.render())
        assertTrue(error.location?.file?.endsWith("marker.json") == true, error.render())
    }

    private fun writePack(
        name: String,
        packFormat: String,
        recipeDir: String,
        itemModifierDir: String,
        itemTagDir: String,
        recipeMarker: String,
    ): Path {
        val root = Files.createTempDirectory("dps-$name-pack")
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": $packFormat,
                "description": "temporary $name pack"
              }
            }
            """.trimIndent(),
        )

        val functionRoot = root.resolve("data").resolve("demo").resolve(if (recipeDir == "recipes") "functions" else "function")
        Files.createDirectories(functionRoot)
        Files.writeString(functionRoot.resolve("noop.mcfunction"), "say $name")

        val recipeRoot = root.resolve("data").resolve("demo").resolve(recipeDir)
        Files.createDirectories(recipeRoot)
        Files.writeString(
            recipeRoot.resolve("marker.json"),
            """
            {
              "type": "minecraft:crafting_shapeless",
              "marker": "$recipeMarker",
              "ingredients": [],
              "result": { "id": "minecraft:stone", "count": 1 }
            }
            """.trimIndent(),
        )

        val modifierRoot = root.resolve("data").resolve("demo").resolve(itemModifierDir)
        Files.createDirectories(modifierRoot)
        Files.writeString(
            modifierRoot.resolve("mark_item.json"),
            """
            {
              "function": "minecraft:set_custom_data",
              "tag": { "marked": true }
            }
            """.trimIndent(),
        )

        val tagRoot = root.resolve("data").resolve("demo").resolve("tags").resolve(itemTagDir)
        Files.createDirectories(tagRoot)
        Files.writeString(
            tagRoot.resolve("debug_items.json"),
            """
            {
              "values": [
                "minecraft:stick",
                { "id": "#demo:optional_items", "required": false }
              ]
            }
            """.trimIndent(),
        )

        writeRawRegistryResources(root, recipeMarker)
        return root
    }

    private fun writeFunctionTagPack(
        name: String,
        replace: Boolean,
        functionName: String,
        message: String,
        valuesJson: String? = null,
    ): Path {
        val tagValuesJson = valuesJson ?: "\"demo:$functionName\""
        val root = Files.createTempDirectory("dps-$name-pack")
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary $name pack"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        Files.writeString(functionRoot.resolve("$functionName.mcfunction"), "say $message")
        val tagRoot = root.resolve("data").resolve("minecraft").resolve("tags").resolve("function")
        Files.createDirectories(tagRoot)
        Files.writeString(
            tagRoot.resolve("load.json"),
            """
            {
              "replace": $replace,
              "values": [$tagValuesJson]
            }
            """.trimIndent(),
        )
        return root
    }

    private fun writeTagPack(name: String, replace: Boolean, value: String): Path {
        val root = Files.createTempDirectory("dps-$name-pack")
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary $name pack"
              }
            }
            """.trimIndent(),
        )
        val tagRoot = root.resolve("data").resolve("demo").resolve("tags").resolve("item")
        Files.createDirectories(tagRoot)
        Files.writeString(
            tagRoot.resolve("replace_items.json"),
            """
            {
              "replace": $replace,
              "values": ["$value"]
            }
            """.trimIndent(),
        )
        return root
    }

    private fun writeInvalidRecipeIdPack(): Path {
        val root = Files.createTempDirectory("dps-invalid-recipe-id-pack")
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary invalid recipe id pack"
              }
            }
            """.trimIndent(),
        )
        val recipeRoot = root.resolve("data").resolve("Demo").resolve("recipe")
        Files.createDirectories(recipeRoot)
        Files.writeString(
            recipeRoot.resolve("marker.json"),
            """
            {
              "type": "minecraft:crafting_shapeless",
              "ingredients": [],
              "result": { "id": "minecraft:stone", "count": 1 }
            }
            """.trimIndent(),
        )
        return root
    }

    private fun writeRawTagPack(name: String, body: String): Path {
        val root = Files.createTempDirectory("dps-$name-pack")
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary $name pack"
              }
            }
            """.trimIndent(),
        )
        val tagRoot = root.resolve("data").resolve("demo").resolve("tags").resolve("item")
        Files.createDirectories(tagRoot)
        Files.writeString(tagRoot.resolve("replace_items.json"), body)
        return root
    }

    private fun writeRawRegistryResources(root: Path, marker: String) {
        expectedRawKinds.forEach { kind ->
            val idPath = rawResourceIds.getValue(kind)
            val file = root.resolve("data").resolve("demo").resolve(kind).resolve("$idPath.json")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """
                {
                  "marker": "$marker",
                  "kind": "$kind"
                }
                """.trimIndent(),
            )
        }
    }

    private fun zipPack(root: Path, output: Path): Path {
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            Files.walk(root).use { walk ->
                walk.filter { Files.isRegularFile(it) }.forEach { file ->
                    val entryName = root.relativize(file).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(entryName))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
        return output
    }

    private companion object {
        val expectedRawKinds = listOf(
            "damage_type",
            "chat_type",
            "dimension",
            "dimension_type",
            "worldgen/configured_feature",
            "worldgen/placed_feature",
            "worldgen/structure",
            "worldgen/processor_list",
            "enchantment",
            "jukebox_song",
            "trim_material",
            "trim_pattern",
            "banner_pattern",
            "wolf_variant",
            "painting_variant",
        )

        val rawResourceIds = mapOf(
            "damage_type" to "debug_damage",
            "chat_type" to "debug_chat",
            "dimension" to "debug_dimension",
            "dimension_type" to "debug_dimension_type",
            "worldgen/configured_feature" to "debug_feature",
            "worldgen/placed_feature" to "debug_feature",
            "worldgen/structure" to "debug_structure",
            "worldgen/processor_list" to "debug_processor",
            "enchantment" to "debug_enchantment",
            "jukebox_song" to "debug_song",
            "trim_material" to "debug_trim_material",
            "trim_pattern" to "debug_trim_pattern",
            "banner_pattern" to "debug_banner",
            "wolf_variant" to "debug_wolf",
            "painting_variant" to "debug_painting",
        )
    }
}

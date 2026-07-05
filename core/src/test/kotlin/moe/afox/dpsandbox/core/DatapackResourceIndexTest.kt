package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

        val tag = datapack.tag("item", ResourceLocation.parse("demo:debug_items"))
        assertFalse(tag.replace)
        assertEquals(listOf("minecraft:stick", "#demo:optional_items"), tag.values.map { it.id })
        assertEquals(listOf(true, false), tag.values.map { it.required })

        assertTrue(datapack.resourceIndex.any { it.type == "recipe" && it.id == ResourceLocation.parse("demo:marker") && it.active })
        assertTrue(datapack.resourceIndex.any { it.type == "item_modifier" && it.id == ResourceLocation.parse("demo:mark_item") && it.active })
        assertTrue(datapack.resourceIndex.any { it.type == "tag/item" && it.id == ResourceLocation.parse("demo:debug_items") && it.active })
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
        assertEquals(2, datapack.tag("items", ResourceLocation.parse("demo:debug_items")).values.size)
    }

    @Test
    fun `resource index records overridden resources`() {
        val first = writePack("overlay-first", "107.1", "recipe", "item_modifier", "item", "first")
        val second = writePack("overlay-second", "107.1", "recipe", "item_modifier", "item", "second")

        val datapack = createSandbox("26.2", listOf(first, second)).datapack

        assertEquals("second", datapack.recipe(ResourceLocation.parse("demo:marker")).root.asJsonObject.get("marker").asString)
        val recipeEntries = datapack.resourceIndex.filter { it.type == "recipe" && it.id == ResourceLocation.parse("demo:marker") }

        assertEquals(2, recipeEntries.size)
        assertFalse(recipeEntries[0].active)
        assertTrue(recipeEntries[0].overriddenBy?.contains("overlay-second") == true, recipeEntries[0].toString())
        assertTrue(recipeEntries[1].active)
        assertTrue(recipeEntries[1].overrides?.contains("overlay-first") == true, recipeEntries[1].toString())
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
        return root
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
}

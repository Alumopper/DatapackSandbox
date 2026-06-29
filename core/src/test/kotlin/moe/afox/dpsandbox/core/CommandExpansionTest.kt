package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandExpansionTest {
    @Test
    fun `world and player state commands update sandbox snapshot`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))
        sandbox.runLoad()

        sandbox.executeCommand("difficulty hard")
        sandbox.executeCommand("defaultgamemode creative")
        sandbox.executeCommand("gamemode adventure Steve")
        sandbox.executeCommand("setworldspawn 10 70 10 45")
        sandbox.executeCommand("spawnpoint Steve 1 65 2 90")
        sandbox.executeCommand("worldborder set 100")
        sandbox.executeCommand("worldborder center 5 -6")
        sandbox.executeCommand("forceload add 0 0 16 16")
        sandbox.executeCommand("fillbiome 0 0 0 1 0 1 minecraft:plains")
        sandbox.executeCommand("tick freeze")
        sandbox.executeCommand("tick step 2")

        val snapshot = sandbox.snapshotJson()
        assertEquals("hard", snapshot.get("difficulty").asString)
        assertEquals("creative", snapshot.get("defaultGameMode").asString)
        assertEquals("adventure", snapshot.getAsJsonObject("players").getAsJsonObject("Steve").get("gameMode").asString)
        assertEquals(true, snapshot.get("tickFrozen").asBoolean)
        assertEquals(2, snapshot.get("gameTime").asLong)
        assertEquals(4, snapshot.getAsJsonArray("biomes").size())
        assertTrue(snapshot.getAsJsonArray("forcedChunks").size() >= 1)
        assertEquals(100.0, snapshot.getAsJsonObject("worldBorder").get("size").asDouble)
        assertEquals(1.0, snapshot.getAsJsonObject("players").getAsJsonObject("Steve").getAsJsonObject("spawnPoint").get("x").asDouble)
    }

    @Test
    fun `utility commands record deterministic outputs`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("help gamemode")
        sandbox.executeCommand("list uuids")
        sandbox.executeCommand("seed")
        sandbox.executeCommand("locate biome minecraft:plains")
        sandbox.executeCommand("datapack list")
        sandbox.executeCommand("reload")
        sandbox.executeCommand("scoreboard objectives add trig trigger")
        sandbox.executeCommand("trigger trig set 4")

        assertEquals(4, sandbox.world.getScore("Steve", "trig"))
        assertTrue(sandbox.world.outputs.any { it.command == "help" && it.text.contains("gamemode") })
        assertTrue(sandbox.world.outputs.any { it.command == "list" && it.text.contains("Steve") })
        assertTrue(sandbox.world.outputs.any { it.command == "locate" && it.payload?.asJsonObject?.get("found")?.asBoolean == false })
        assertTrue(sandbox.world.outputs.any { it.command == "datapack list" })
        assertTrue(sandbox.world.outputs.any { it.command == "reload" })
    }

    @Test
    fun `attribute and loot commands expose sandbox-visible state`() {
        val pack = writeLootPack(Files.createTempDirectory("dps-command-expansion-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("summon minecraft:zombie 0 64 0")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health base set 40")
        sandbox.executeCommand("loot give Steve loot demo:gift")

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        assertEquals(40.0, zombie.attributes[ResourceLocation.parse("minecraft:max_health")])
        assertTrue(sandbox.world.requirePlayer("Steve").inventory.any { it.id == ResourceLocation.parse("minecraft:diamond") })
    }

    private fun fixturePack(): Path =
        Path.of("src/test/resources/packs/counter")

    private fun writeLootPack(root: Path): Path {
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
                      "name": "minecraft:diamond"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        return root
    }
}

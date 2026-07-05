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

    @Test
    fun `loot command supports fish mine and empty kill sources`() {
        val pack = writeLootSourcePack(Files.createTempDirectory("dps-loot-source-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("setblock 0 64 0 minecraft:stone")
        sandbox.executeCommand("loot give Steve fish demo:fish 0 64 0 minecraft:stick")
        sandbox.executeCommand("loot give Steve mine 0 64 0 minecraft:stick")
        sandbox.executeCommand("summon minecraft:zombie 2 64 0")
        sandbox.executeCommand("loot give Steve kill @e[type=minecraft:zombie,limit=1]")
        sandbox.executeCommand("loot spawn 1 64 1 mine 0 64 0")

        val inventoryIds = sandbox.world.requirePlayer("Steve").inventory.map { it.id }.toSet()
        assertTrue(ResourceLocation.parse("minecraft:diamond") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:stone") in inventoryIds)
        assertTrue(
            sandbox.world.entities.any {
                it.type == ResourceLocation.parse("minecraft:item") &&
                    it.nbt.getAsJsonObject("Item")?.get("id")?.asString == "minecraft:stone"
            },
        )
    }

    @Test
    fun `execute conditions cover predicate dimension biome and loaded state`() {
        val pack = writePredicatePack(Files.createTempDirectory("dps-execute-conditions-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("execute if dimension minecraft:overworld run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute in minecraft:the_nether if dimension minecraft:the_nether run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute in minecraft:the_nether unless dimension minecraft:overworld run scoreboard players add #pass checks 1")
        sandbox.executeCommand("forceload add 0 0")
        sandbox.executeCommand("execute if loaded 1 64 1 run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless loaded 32 64 32 run scoreboard players add #pass checks 1")
        sandbox.executeCommand("fillbiome 0 64 0 0 64 0 minecraft:forest")
        sandbox.executeCommand("execute if biome 0 64 0 minecraft:forest run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless biome 0 64 0 minecraft:desert run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute as Steve if predicate demo:is_player run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute in minecraft:the_nether if predicate demo:in_nether run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless predicate demo:false run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if dimension minecraft:the_nether run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if loaded 32 64 32 run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if biome 0 64 0 minecraft:desert run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if predicate demo:in_nether run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if predicate demo:false run scoreboard players add #fail checks 1")

        assertEquals(10, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
    }

    @Test
    fun `execute blocks condition compares sparse block regions`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("setblock 0 64 0 minecraft:stone")
        sandbox.executeCommand("setblock 1 64 0 minecraft:chest[facing=north]")
        sandbox.executeCommand("setblock 4 64 0 minecraft:stone")
        sandbox.executeCommand("setblock 5 64 0 minecraft:chest[facing=north]")
        sandbox.executeCommand("setblock 6 64 0 minecraft:dirt")
        sandbox.executeCommand("execute if blocks 0 64 0 1 64 0 4 64 0 all run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if blocks 0 64 0 2 64 0 4 64 0 masked run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless blocks 0 64 0 2 64 0 4 64 0 all run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if blocks 0 64 0 2 64 0 4 64 0 all run scoreboard players add #fail checks 1")

        assertEquals(3, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
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

    private fun writeLootSourcePack(root: Path): Path {
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
        return root
    }

    private fun writePredicatePack(root: Path): Path {
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
        Files.writeString(predicateRoot.resolve("false.json"), "false")
        return root
    }
}

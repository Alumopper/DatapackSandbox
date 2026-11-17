package moe.afox.dpsandbox.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectorAndPathParityTest {
    private fun sandbox() =
        createFunctionSandboxFromString("26.3", "").also {
            it.executeCommand("scoreboard objectives add result dummy")
        }

    @Test
    fun `repeated selector filters intersect and empty tag tests tag presence`() {
        val box = sandbox()
        box.executeCommand("summon pig ~ ~ ~ {Tags:[a]}")
        box.executeCommand("summon cow ~ ~ ~ {Tags:[a,b]}")
        box.executeCommand("summon marker ~ ~ ~")

        fun count(selector: String): Int {
            box.executeCommand("execute store result score #n result if entity $selector")
            return box.world.getScore("#n", "result")
        }
        assertEquals(1, count("@e[type=!player,type=!pig,type=!cow]"))
        assertEquals(2, count("@e[tag=!]"))
        assertEquals(1, count("@e[type=!player,tag=]"))
        assertEquals(1, count("@e[nbt={Tags:[a]},nbt=!{Tags:[b]}]"))
        assertEquals(1, count("@r"))
        assertFailsWith<SandboxException> { count("@e[type=cow,type=pig]") }
        assertFailsWith<SandboxException> { count("@e[limit=0]") }
    }

    @Test
    fun `volume selects intersecting hitboxes only in the execution dimension`() {
        val box = sandbox()
        val nbt = "{width:1f,height:2f}"
        box.executeCommand("summon interaction 1.25 0 0.5 $nbt")
        box.executeCommand("summon interaction 1.5 0 0.5 $nbt")
        box.executeCommand("execute in the_nether run summon interaction 1.25 0 0.5 $nbt")
        for (selector in listOf("x=0,y=1,z=0,dx=0", "x=0,y=2,z=0,dy=-1")) {
            box.executeCommand("execute store result score #n result if entity @e[type=interaction,$selector]")
            assertEquals(1, box.world.getScore("#n", "result"))
        }
        box.executeCommand("execute store result score #n result if entity @e[type=interaction,distance=..3]")
        assertEquals(2, box.world.getScore("#n", "result"))
    }

    @Test
    fun `random player choices advance and repeat after restoring the world`() {
        val box = sandbox()
        box.createPlayer("Alex")
        box.createPlayer("Blair")
        box.saveCheckpoint()

        fun choices() = List(8) { EntitySelectors.select(box.world, "@r", ExecutionContext()).single().scoreHolder }
        val first = choices()
        assertTrue(first.distinct().size > 1)
        box.restoreCheckpoint()
        assertEquals(first, choices())
    }

    @Test
    fun `selector resolves nested entity type tags and wrapped angles`() {
        val root = writeExecuteFunctionPack(Files.createTempDirectory("dps-selector-tag"))
        val tags = Files.createDirectories(root.resolve("data/demo/tags/entity_type"))
        Files.writeString(tags.resolve("farm.json"), """{"values":["minecraft:pig","#demo:herd"]}""")
        Files.writeString(tags.resolve("herd.json"), """{"values":["minecraft:cow"]}""")
        val box = createSandbox("26.3", listOf(root))
        box.executeCommand("scoreboard objectives add result dummy")
        box.executeCommand("summon pig ~ ~ ~")
        box.executeCommand("summon cow ~ ~ ~")
        box.executeCommand("rotate @e[type=pig,limit=1] -175 0")
        box.executeCommand("rotate @e[type=cow,limit=1] 175 0")
        box.executeCommand("execute store result score #n result if entity @e[type=#demo:farm,y_rotation=170..-170]")
        assertEquals(2, box.world.getScore("#n", "result"))
    }

    @Test
    fun `filtered path creates matching compound and honors root filter`() {
        val box = sandbox()
        box.executeCommand("data modify storage test:s records[{id:2}].value set value 8")
        val state = box.world.storage(ResourceLocation.parse("test:s"))
        assertEquals(8, JsonPaths.get(state, "records[0].value")?.asInt)
        assertEquals(2, JsonPaths.get(state, "records[0].id")?.asInt)
        assertTrue(box.executeCommand("execute if data storage test:s {records:[{id:2}]}.records").success)
        assertFalse(box.executeCommand("execute if data storage test:s {records:[{id:9}]}.records").success)
    }

    @Test
    fun `writes across lists retain contents order and count each changed destination`() {
        val box = sandbox()
        box.executeCommand("data merge storage test:s {rows:[{v:[1]},{v:[2]},{}],extra:[7,8]}")

        fun apply(action: String): Int {
            box.executeCommand("execute store result score #n result run data modify storage test:s rows[].v $action")
            return box.world.getScore("#n", "result")
        }
        assertEquals(3, apply("insert -1 from storage test:s extra[]"))
        assertEquals(3, apply("prepend value 5"))
        val rows = box.world.storage(ResourceLocation.parse("test:s")).getAsJsonArray("rows")
        assertEquals(listOf("[5,1,7,8]", "[5,2,7,8]", "[5,7,8]"), rows.map { it.asJsonObject.get("v").toString() })
        box.executeCommand("execute store result score #n result run data remove storage test:s rows[].v[]")
        assertEquals(11, box.world.getScore("#n", "result"))
    }

    @Test
    fun `indices select exact slots and failed insertion leaves every list intact`() {
        val box = sandbox()
        box.executeCommand("data merge storage test:s {list:[1,2,1],rows:[[1,2],[]]}")
        box.executeCommand("data remove storage test:s list[-1]")
        val state = box.world.storage(ResourceLocation.parse("test:s"))
        assertEquals("[1,2]", state.get("list").toString())
        for (operation in listOf("list[9] set value 3", "rows[] insert 2 value 3")) {
            val result = box.executeCommand("execute store success score #ok result run data modify storage test:s $operation")
            assertFalse(result.success)
            assertEquals(0, box.world.scores[ScoreKey("#ok", "result")])
        }
        assertEquals(
            "[[1,2],[]]",
            box.world
                .storage(ResourceLocation.parse("test:s"))
                .get("rows")
                .toString(),
        )
        assertEquals(
            "[1,2]",
            box.world
                .storage(ResourceLocation.parse("test:s"))
                .get("list")
                .toString(),
        )
    }
}

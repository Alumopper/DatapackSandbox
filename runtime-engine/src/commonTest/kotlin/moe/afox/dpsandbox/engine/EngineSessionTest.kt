package moe.afox.dpsandbox.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineSessionTest {
    @Test
    fun persistsStateAcrossExecutionsAndKeepsChecksNonMutating() {
        val session = EngineSession("26.2")
        session.configure(listOf("setblock", "scoreboard"), listOf("minecraft:stone"), emptyList(), emptyList())
        session.beginExecution()
        session.executeLine("scoreboard objectives add runs dummy", 1)
        session.executeLine("scoreboard players set #browser runs 1", 2)
        val first = session.finishExecutionJson()
        assertContains(first, "#browser")
        assertEquals("[]", session.checkJson("scoreboard players add #browser runs 1"))
        assertContains(session.snapshotJson(), "\"#browser\":1")

        session.beginExecution()
        session.executeLine("scoreboard players add #browser runs 2", 1)
        assertContains(session.finishExecutionJson(), "\"#browser\":3")
    }

    @Test
    fun emitsRealtimeViewportOutputShapes() {
        val session = EngineSession("26.2")
        session.configure(listOf("particle", "title", "tellraw"), emptyList(), emptyList(), emptyList())
        session.beginExecution()
        session.executeLine("particle minecraft:flame 1 2 3 0.5 0.25 1 0.1 24 force Steve", 1)
        session.executeLine("title Steve actionbar {\"text\":\"Ready\"}", 2)
        session.executeLine("tellraw Steve {\"text\":\"Hello\"}", 3)
        val result = session.finishExecutionJson()

        assertContains(result, "\"channel\":\"visual\"")
        assertContains(result, "\"renderCount\":24")
        assertContains(result, "\"command\":\"title actionbar\"")
        assertContains(result, "\"text\":\"Ready\"")
        assertContains(result, "\"channel\":\"chat\"")
        assertContains(result, "\"targets\":[\"Steve\"]")
    }

    @Test
    fun rejectsTraversalAndDuplicateVirtualPaths() {
        assertFailsWith<IllegalArgumentException> { VirtualPath.normalize("../level.dat") }
        assertFailsWith<IllegalArgumentException> { VirtualPath.validateUnique(listOf("pack.mcmeta", "pack.mcmeta")) }
        assertEquals("data/demo/function/main.mcfunction", VirtualPath.normalize("data\\demo\\function\\main.mcfunction"))
    }

    @Test
    fun restoresReusableNamedCheckpointExactly() {
        val session = EngineSession("26.2")
        session.configure(listOf("setblock", "scoreboard", "summon"), listOf("minecraft:stone"), emptyList(), listOf("minecraft:zombie"))
        session.beginExecution()
        session.executeLine("scoreboard objectives add runs dummy", 1)
        session.executeLine("scoreboard players set #branch runs 1", 2)
        session.executeLine("setblock 0 0 2 minecraft:stone", 3)
        session.finishExecutionJson()
        val saved = session.saveCheckpoint("branch")

        session.beginExecution()
        session.executeLine("scoreboard players add #branch runs 9", 1)
        session.executeLine("summon minecraft:zombie 1 2 3", 2)
        session.finishExecutionJson()

        assertEquals(saved, session.restoreCheckpoint("branch"))
        assertEquals(saved, session.snapshotJson())
        assertEquals(listOf("branch"), parseStringArray(session.checkpointNamesJson()))

        session.beginExecution()
        session.executeLine("summon minecraft:zombie 1 2 3", 1)
        session.finishExecutionJson()
        session.restoreCheckpoint("branch")
        assertEquals(saved, session.snapshotJson())
    }

    @Test
    fun modelsDisplayEntitySnbtForBrowserRendering() {
        val session = EngineSession("26.2")
        session.configure(listOf("summon", "data", "tick"), emptyList(), emptyList(), listOf(
            "minecraft:block_display",
            "minecraft:item_display",
            "minecraft:text_display",
        ))
        session.beginExecution()
        session.executeLine(
            """summon minecraft:block_display 1.5 2 3.5 {Tags:[display],block_state:{Name:"minecraft:stone",Properties:{axis:"y"}},billboard:"fixed",transformation:{translation:[1f,2f,3f],left_rotation:[0f,0f,0f,1f],scale:[2f,1f,0.5f],right_rotation:[0f,0f,0f,1f]},brightness:{sky:15,block:7}}""",
            1,
        )
        session.executeLine(
            """summon minecraft:text_display 0 1 0 {Tags:[label],text:'{"text":"Hello","color":"red"}',billboard:"center",line_width:80,background:1073741824,text_opacity:200,shadow:true,see_through:true,alignment:"left"}""",
            2,
        )
        session.finishExecutionJson()

        val block = session.renderEntities().first { it.type == "minecraft:block_display" }.display!!
        assertEquals("minecraft:stone", block.blockId)
        assertEquals("y", block.blockProperties["axis"])
        assertEquals(15, block.brightnessSky)
        assertEquals(2.0, block.transformation[0])
        assertEquals(1.0, block.transformation[3])

        val text = session.renderEntities().first { it.type == "minecraft:text_display" }.display!!
        assertEquals("Hello", text.text)
        assertEquals("center", text.billboard)
        assertEquals("left", text.alignment)
        assertEquals(200, text.textOpacity)
        assertEquals(true, text.seeThrough)

        session.beginExecution()
        session.executeLine("""data merge entity @e[tag=label,limit=1] {text:"Changed",alignment:"right"}""", 1)
        session.finishExecutionJson()
        val changed = session.renderEntities().first { it.type == "minecraft:text_display" }.display!!
        assertEquals("Changed", changed.text)
        assertEquals("right", changed.alignment)
        assertContains(session.snapshotJson(), "renderTransformation")
    }

    @Test
    fun interpolatesDisplayVisualsAndTeleportPoseAtTickBoundaries() {
        val session = EngineSession("26.2")
        session.configure(
            listOf("summon", "data", "tp", "tick"),
            emptyList(),
            emptyList(),
            listOf("minecraft:text_display"),
        )
        session.beginExecution()
        session.executeLine(
            """summon minecraft:text_display 0 0 0 {Tags:[display],text:"start",interpolation_duration:4,teleport_duration:4,transformation:{translation:[0f,0f,0f],scale:[1f,1f,1f]},shadow_radius:0f,text_opacity:255,background:1073741824}""",
            1,
        )
        session.executeLine(
            """data merge entity @e[tag=display,limit=1] {text:"target",transformation:{translation:[4f,2f,0f],scale:[3f,3f,3f]},shadow_radius:4f,text_opacity:0,background:0}""",
            2,
        )
        session.executeLine("tick 2", 3)
        session.finishExecutionJson()

        val halfway = session.renderEntities().single()
        assertEquals("target", halfway.display!!.text)
        assertEquals(2.0, halfway.display!!.transformation[0])
        assertEquals(2.0, halfway.display!!.transformation[3])
        assertEquals(1.0, halfway.display!!.transformation[7])
        assertEquals(2.0, halfway.display!!.shadowRadius)
        assertEquals(128, halfway.display!!.textOpacity)

        session.beginExecution()
        session.executeLine("tp @e[tag=display,limit=1] 8 0 0", 1)
        session.finishExecutionJson()
        assertEquals(0.0, session.renderEntities().single().x)
        session.beginExecution()
        session.executeLine("tick 2", 1)
        session.finishExecutionJson()
        assertEquals(4.0, session.renderEntities().single().x)
        session.beginExecution()
        session.executeLine("tick 2", 1)
        session.finishExecutionJson()
        assertEquals(8.0, session.renderEntities().single().x)
        assertEquals(3.0, session.renderEntities().single().display!!.transformation[0])
    }

    @Test
    fun interpolatesDisplayQuaternionOverTheShortestSphericalArc() {
        val session = EngineSession("26.2")
        session.configure(
            listOf("summon", "data", "tick"),
            emptyList(),
            listOf("minecraft:diamond"),
            listOf("minecraft:item_display"),
        )
        session.beginExecution()
        session.executeLine(
            """summon minecraft:item_display 0 0 0 {Tags:[spin],item:{id:"minecraft:diamond",count:1},interpolation_duration:4,transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[1f,1f,1f],right_rotation:[0f,0f,0f,1f]}}""",
            1,
        )
        session.executeLine(
            """data merge entity @e[tag=spin,limit=1] {transformation:{translation:[2f,0f,0f],left_rotation:[0f,0.70710678f,0f,-0.70710678f],scale:[2f,2f,2f],right_rotation:[0f,0f,0f,1f]}}""",
            2,
        )
        session.executeLine("tick 2", 3)
        session.finishExecutionJson()

        val matrix = session.renderEntities().single().display!!.transformation
        assertEquals(1.06066017, matrix[0], 0.0001)
        assertEquals(-1.06066017, matrix[2], 0.0001)
        assertEquals(1.0, matrix[3], 0.0001)
        assertEquals(1.06066017, matrix[8], 0.0001)
        assertEquals(1.06066017, matrix[10], 0.0001)
    }

    @Test
    fun runsLifecycleFunctionTagsAndRecordsPlayerInputWithoutPhysics() {
        val session = EngineSession("26.2")
        session.configure(
            listOf("scoreboard"),
            emptyList(),
            emptyList(),
            listOf("minecraft:player"),
        )
        session.upsertFunction(
            "demo:load",
            "scoreboard objectives add lifecycle dummy\nscoreboard players set #ticks lifecycle 0",
        )
        session.upsertFunction("demo:tick", "scoreboard players add #ticks lifecycle 1")
        session.setFunctionTag("minecraft:load", listOf("demo:load"))
        session.setFunctionTag("minecraft:tick", listOf("demo:tick"))

        assertContains(session.runLoadJson(), "#ticks")
        val tickResult = session.runSimulationTicksJson(2, null)
        assertContains(tickResult, "\"gameTime\":2")
        assertContains(tickResult, "\"#ticks\":2")

        val input = session.dispatchInputJson("Steve", "keyboard", "key.forward", "press", null, null)
        assertContains(input, "player.input Steve keyboard key.forward press")
        assertContains(input, "\"uuid\":\"player:Steve\"")
        assertContains(input, "\"playerInputs\"")
        assertEquals(0.0, session.renderEntities().single().x)
    }

    private fun parseStringArray(value: String): List<String> =
        value.removeSurrounding("[", "]").takeIf(String::isNotBlank)?.split(',')?.map { it.trim('"') } ?: emptyList()
}

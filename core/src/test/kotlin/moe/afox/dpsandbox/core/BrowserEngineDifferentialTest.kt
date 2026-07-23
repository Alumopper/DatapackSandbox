package moe.afox.dpsandbox.core

import com.google.gson.JsonParser
import moe.afox.dpsandbox.engine.EngineSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class BrowserEngineDifferentialTest {
    @Test
    fun sharedEngineMatchesJvmForPersistentPlaygroundState() {
        val source =
            """
            scoreboard objectives add runs dummy
            scoreboard players set #browser runs 1
            scoreboard players add #browser runs 2
            setblock 0 0 2 minecraft:stone
            """.trimIndent()
        val jvm = createFunctionSandboxFromString("26.2", source, defaultPlayerName = null)
        jvm.runFunction(SingleFunctionDatapack.DEFAULT_ID)

        val profile = VersionProfiles.minecraft262
        val browser = EngineSession(profile.id)
        browser.configure(
            profile.commands.roots,
            profile.registryView.blocks.map { it.toString() },
            profile.registryView.items.map { it.toString() },
            profile.registryView.entityTypes.map { it.toString() },
        )
        browser.beginExecution()
        source.lineSequence().forEachIndexed { index, line -> browser.executeLine(line, index + 1) }
        browser.finishExecutionJson()
        val browserSnapshot = JsonParser.parseString(browser.snapshotJson()).asJsonObject
        val jvmSnapshot = jvm.snapshotJson()

        assertEquals(
            jvmSnapshot
                .getAsJsonObject("scores")
                .getAsJsonObject("runs")
                .get("#browser")
                .asInt,
            browserSnapshot
                .getAsJsonObject("scores")
                .getAsJsonObject("runs")
                .get("#browser")
                .asInt,
        )
        assertEquals(
            jvmSnapshot
                .getAsJsonArray("blocks")
                .first()
                .asJsonObject
                .get("id")
                .asString,
            browserSnapshot
                .getAsJsonArray("blocks")
                .first()
                .asJsonObject
                .get("id")
                .asString,
        )
    }

    @Test
    fun jvmAndSharedEngineUseReusableNamedCheckpointSemantics() {
        val initial =
            """
            scoreboard objectives add runs dummy
            scoreboard players set #branch runs 1
            setblock 0 0 2 minecraft:stone
            """.trimIndent()
        val jvm = createFunctionSandboxFromString("26.2", initial, defaultPlayerName = null)
        jvm.runFunction(SingleFunctionDatapack.DEFAULT_ID)
        val jvmSaved = jvm.saveCheckpoint("branch")

        val profile = VersionProfiles.minecraft262
        val browser = EngineSession(profile.id)
        browser.configure(
            profile.commands.roots,
            profile.registryView.blocks.map { it.toString() },
            profile.registryView.items.map { it.toString() },
            profile.registryView.entityTypes.map { it.toString() },
        )
        browser.beginExecution()
        initial.lineSequence().forEachIndexed { index, line -> browser.executeLine(line, index + 1) }
        browser.finishExecutionJson()
        val browserSaved = browser.saveCheckpoint("branch")

        jvm.executeCommand("scoreboard players add #branch runs 9")
        jvm.executeCommand("setblock 0 0 2 minecraft:dirt")
        browser.beginExecution()
        browser.executeLine("scoreboard players add #branch runs 9", 1)
        browser.executeLine("setblock 0 0 2 minecraft:dirt", 2)
        browser.finishExecutionJson()

        assertEquals(jvmSaved, jvm.restoreCheckpoint("branch"))
        assertEquals(browserSaved, browser.restoreCheckpoint("branch"))
        assertEquals(
            1,
            jvm
                .snapshotJson()
                .getAsJsonObject("scores")
                .getAsJsonObject("runs")
                .get("#branch")
                .asInt,
        )
        assertEquals(
            1,
            JsonParser
                .parseString(browser.snapshotJson())
                .asJsonObject
                .getAsJsonObject("scores")
                .getAsJsonObject("runs")
                .get("#branch")
                .asInt,
        )
        assertEquals(
            "minecraft:stone",
            jvm
                .snapshotJson()
                .getAsJsonArray("blocks")
                .first()
                .asJsonObject
                .get("id")
                .asString,
        )
        assertEquals(
            "minecraft:stone",
            JsonParser
                .parseString(browser.snapshotJson())
                .asJsonObject
                .getAsJsonArray("blocks")
                .first()
                .asJsonObject
                .get("id")
                .asString,
        )
        assertEquals(listOf("branch"), jvm.checkpointNames())
        assertFailsWith<SandboxException> { jvm.restoreCheckpoint("missing") }
        assertFailsWith<moe.afox.dpsandbox.engine.EngineFailure> { browser.restoreCheckpoint("missing") }
    }

    @Test
    fun jvmAndSharedEngineMatchDisplayInterpolationAndTeleportPose() {
        val summon =
            """summon minecraft:text_display 0 0 0 {Tags:["display"],text:"moving",interpolation_duration:4,teleport_duration:4,transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[1f,1f,1f],right_rotation:[0f,0f,0f,1f]},shadow_radius:0f,text_opacity:255,background:1073741824}"""
        val merge =
            """data merge entity @e[tag=display,limit=1] {start_interpolation:0,interpolation_duration:4,transformation:{translation:[4f,2f,0f],left_rotation:[0f,0.70710678f,0f,-0.70710678f],scale:[3f,3f,3f],right_rotation:[0f,0f,0f,1f]},shadow_radius:4f,text_opacity:0,background:0}"""
        val jvm = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        jvm.executeCommand(summon)
        jvm.executeCommand(merge)
        jvm.runTicks(2)

        val profile = VersionProfiles.minecraft262
        val browser = EngineSession(profile.id)
        browser.configure(
            profile.commands.roots,
            profile.registryView.blocks.map { it.toString() },
            profile.registryView.items.map { it.toString() },
            profile.registryView.entityTypes.map { it.toString() },
        )
        browser.beginExecution()
        browser.executeLine(summon, 1)
        browser.executeLine(merge, 2)
        browser.executeLine("tick 2", 3)
        browser.finishExecutionJson()

        val special =
            assertNotNull(
                jvm.world.entities
                    .single()
                    .toJson(profile)
                    .asJsonObject
                    .getAsJsonObject("special"),
            )
        val jvmMatrix = DisplayTransformation.parse(special.get("renderTransformation"), null).toMatrix()
        val browserDisplay = assertNotNull(browser.renderEntities().single().display)
        jvmMatrix.zip(browserDisplay.transformation).forEach { (expected, actual) -> assertEquals(expected, actual, 0.0001) }
        assertEquals(special.get("shadowRadius").asDouble, browserDisplay.shadowRadius, 0.0001)
        assertEquals(special.get("textOpacity").asInt, browserDisplay.textOpacity)
        assertEquals(special.get("background").asInt, browserDisplay.background)

        jvm.executeCommand("tp @e[tag=display] 8 4 0")
        jvm.runTicks(2)
        browser.beginExecution()
        browser.executeLine("tp @e[tag=display] 8 4 0", 1)
        browser.executeLine("tick 2", 2)
        browser.finishExecutionJson()
        val jvmPose =
            jvm.world.entities
                .single()
                .toJson(profile)
                .asJsonObject
                .getAsJsonObject("special")
                .getAsJsonObject("renderPosition")
        val browserPose = browser.renderEntities().single()
        assertEquals(jvmPose.get("x").asDouble, browserPose.x, 0.0001)
        assertEquals(jvmPose.get("y").asDouble, browserPose.y, 0.0001)
        assertEquals(jvmPose.get("z").asDouble, browserPose.z, 0.0001)
    }

    @Test
    fun jvmAndSharedEngineMatchRealtimeViewportOutputs() {
        val commands =
            listOf(
                "particle minecraft:flame 1 2 3 0.5 0.25 1 0.1 24 force Steve",
                "title Steve actionbar {\"text\":\"Ready\"}",
                "tellraw Steve {\"text\":\"Hello\"}",
            )
        val jvm = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        jvm.createPlayer("Steve")
        commands.forEach(jvm::executeCommand)

        val profile = VersionProfiles.minecraft262
        val browser = EngineSession(profile.id)
        browser.configure(profile.commands.roots, emptyList(), emptyList(), emptyList())
        browser.beginExecution()
        commands.forEachIndexed { index, command -> browser.executeLine(command, index + 1) }
        val browserOutputs =
            JsonParser
                .parseString(browser.finishExecutionJson())
                .asJsonObject
                .getAsJsonArray("outputs")
                .map { it.asJsonObject }

        assertEquals(jvm.world.outputs.map { it.command }, browserOutputs.map { it.get("command").asString })
        assertEquals(jvm.world.outputs.map { it.channel }, browserOutputs.map { it.get("channel").asString })
        assertEquals(jvm.world.outputs.map { it.text }, browserOutputs.map { it.get("text").asString })
        val jvmParticle =
            jvm.world.outputs
                .first()
                .payload!!
                .asJsonObject
        val browserParticle = browserOutputs.first().getAsJsonObject("payload")
        listOf("particle", "x", "y", "z", "deltaX", "deltaY", "deltaZ", "speed", "renderCount", "mode").forEach { key ->
            assertEquals(jvmParticle.get(key), browserParticle.get(key), "particle payload key '$key'")
        }
    }
}

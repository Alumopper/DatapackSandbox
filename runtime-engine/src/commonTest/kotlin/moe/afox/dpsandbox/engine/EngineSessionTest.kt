package moe.afox.dpsandbox.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineSessionTest {
    @Test
    fun completesNestedCommandChildrenWithoutRepeatingCommittedLiterals() {
        val environment = completionEnvironment()

        assertCompletion(environment, "execute ", "as", "if", "positioned", "run", "store")
        assertCompletion(environment, "scoreboard players ", "add", "get", "operation", "reset", "set")
        assertCompletion(environment, "scoreboard objectives ", "add", "modify", "setdisplay")

        assertFalse("execute" in completionValues(environment, "execute "))
        assertFalse("players" in completionValues(environment, "scoreboard players "))
        assertFalse("objectives" in completionValues(environment, "scoreboard objectives "))
    }

    @Test
    fun walksExecuteConditionsStoresAndRunRedirects() {
        val environment = completionEnvironment()
        val cases =
            mapOf(
                "execute as " to "@s",
                "execute as @s " to "run",
                "execute positioned " to "as",
                "execute positioned as " to "@s",
                "execute positioned 0 0 0 " to "if",
                "execute facing entity @s " to "eyes",
                "execute if " to "score",
                "execute if score " to "@s",
                "execute if score @s " to "runs",
                "execute if score @s runs " to "matches",
                "execute if score @s runs matches " to "0..",
                "execute if biome 0 64 0 " to "minecraft:plains",
                "execute if data entity @s " to "path",
                "execute store " to "result",
                "execute store result " to "score",
                "execute store result score " to "@s",
                "execute store result score @s " to "runs",
                "execute store result block 0 0 0 " to "path",
                "execute run " to "scoreboard",
                "execute run scoreboard players " to "operation",
            )

        cases.forEach { (source, expected) -> assertCompletion(environment, source, expected) }
    }

    @Test
    fun completesEveryModeledBrowserCommandFamily() {
        val environment = completionEnvironment()
        val cases =
            mapOf(
                "setblock 0 0 0 " to "minecraft:stone",
                "fill 0 0 0 1 1 1 " to "minecraft:stone",
                "summon " to "minecraft:zombie",
                "give Steve " to "minecraft:apple",
                "clear Steve " to "minecraft:apple",
                "function " to "demo:main",
                "kill " to "@e",
                "tag @e " to "add",
                "data " to "modify",
                "data modify storage demo:state path " to "set",
                "data get storage demo:state " to "path",
                "tellraw Steve " to "{\"text\":\"\"}",
                "title Steve " to "actionbar",
                "particle " to "minecraft:flame",
                "time " to "set",
                "weather " to "thunder",
                "gamerule " to "doDaylightCycle",
                "tp " to "@s",
                "return " to "run",
                "tick " to "step",
                "advancement " to "grant",
                "attribute " to "@s",
                "attribute @s " to "minecraft:max_health",
                "bossbar " to "set",
                "clone 0 0 0 1 1 1 2 2 2 " to "replace",
                "damage @s 1 " to "minecraft:generic",
                "effect " to "give",
                "enchant " to "@s",
                "experience " to "add",
                "fillbiome 0 0 0 1 1 1 " to "minecraft:plains",
                "forceload " to "remove",
                "gamemode " to "survival",
                "item " to "replace",
                "item replace entity @s hotbar.0 from " to "entity",
                "item replace entity @s hotbar.0 from entity @s " to "weapon.mainhand",
                "place " to "structure",
                "playsound " to "minecraft:block.note_block.harp",
                "random " to "value",
                "recipe " to "give",
                "ride @s " to "mount",
                "rotate " to "@s",
                "schedule " to "function",
                "spawnpoint " to "@s",
                "spectate " to "@e",
                "spreadplayers 0 0 1 5 " to "true",
                "team " to "modify",
                "trigger " to "runs",
                "worldborder " to "warning",
            )

        cases.forEach { (source, expected) -> assertCompletion(environment, source, expected) }
        assertCompletion(environment, "scoreboard players display ", "name", "numberformat")
        assertTrue(completionValues(environment, "title @s reset ").isEmpty())
        assertTrue(completionValues(environment, "worldborder get ").isEmpty())
    }

    @Test
    fun auditsEveryProfileRootAndKeepsVersionSpecificRootsScoped() {
        val environment = completionEnvironment()
        val terminalRoots = setOf("reload", "save-off", "save-on", "seed", "stop")

        environment.roots.forEach { root ->
            val children = completionValues(environment, "$root ")
            if (root != "help") assertFalse(root in children, "Committed root '$root' must not repeat itself: $children")
            if (root !in terminalRoots) {
                assertTrue(children.isNotEmpty(), "Expected a next-token completion for '$root '")
            }
        }

        val legacy = environment.copy(roots = environment.roots - "transfer")
        assertFalse("transfer" in completionValues(legacy, "tran"))
        assertTrue(completionValues(legacy, "transfer ").isEmpty())
    }

    @Test
    fun completionRangesReplaceOnlyTheActiveToken() {
        val environment = completionEnvironment()
        val partial = EngineCommandCompletion.complete("scoreboard pla", 14, environment).single { it.value == "players" }
        assertEquals(11, partial.start)
        assertEquals(14, partial.end)

        val child = EngineCommandCompletion.complete("scoreboard players ", 19, environment).first()
        assertEquals(19, child.start)
        assertEquals(19, child.end)

        val slash = EngineCommandCompletion.complete("/exec", 5, environment).single { it.value == "/execute" }
        assertEquals(0, slash.start)
        assertEquals(5, slash.end)
    }

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
    fun completesObjectivesDeclaredEarlierInTheEditorWithoutExecutingThem() {
        val session = EngineSession("26.2")
        session.configure(listOf("scoreboard"), emptyList(), emptyList(), emptyList())
        val source =
            "scoreboard objectives add qwq dummy\n" +
                "scoreboard players set #browser qw"

        assertEquals("[]", session.checkJson("${source}q 1"))
        val completion = session.completionJson(source, source.length)
        assertContains(completion, "\"value\":\"qwq\"")
        assertContains(completion, "\"start\":${source.lastIndexOf("qw")}")
        assertFalse("\"qwq\"" in session.snapshotJson())
    }

    @Test
    fun restoresCompletionStateFromTheCoreSnapshot() {
        val core = EngineSession("26.2")
        core.configure(listOf("scoreboard", "data", "gamerule"), emptyList(), emptyList(), emptyList())
        core.beginExecution()
        core.executeLine("scoreboard objectives add qwq dummy", 1)
        core.executeLine("scoreboard players set #browser qwq 1", 2)
        core.executeLine("data merge storage demo:state {}", 3)
        core.executeLine("gamerule keepInventory true", 4)
        core.finishExecutionJson()

        val renderer = EngineSession("26.2")
        renderer.configure(listOf("scoreboard", "data", "gamerule"), emptyList(), emptyList(), emptyList())
        renderer.replaceSnapshotJson(core.snapshotJson())

        val scoreSource = "scoreboard players get #browser "
        val storageSource = "data get storage "
        val gameruleSource = "gamerule keep"
        assertContains(renderer.completionJson(scoreSource, scoreSource.length), "\"value\":\"qwq\"")
        assertContains(renderer.completionJson(storageSource, storageSource.length), "\"value\":\"demo:state\"")
        assertContains(renderer.completionJson(gameruleSource, gameruleSource.length), "\"value\":\"keepInventory\"")
    }

    @Test
    fun usesConfiguredVanillaCatalogsForCommandArguments() {
        val environment =
            completionEnvironment().copy(
                blocks = listOf("minecraft:copper_block"),
                biomes = listOf("minecraft:pale_garden"),
                biomeTags = listOf("#minecraft:is_overworld"),
                damageTypes = listOf("minecraft:outside_border"),
                enchantments = listOf("minecraft:wind_burst"),
                effects = listOf("minecraft:weaving"),
                attributes = listOf("minecraft:burning_time"),
                particles = listOf("minecraft:trial_spawner_detection"),
                sounds = listOf("minecraft:block.copper_bulb.turn_on"),
                scoreboardCriteria = listOf("minecraft.mined:minecraft:copper_block"),
                advancements = listOf("minecraft:adventure/heart_transplanter"),
                recipes = listOf("minecraft:copper_chest"),
                pointOfInterestTypes = listOf("minecraft:home"),
                pointOfInterestTypeTags = listOf("#minecraft:village"),
                structures = listOf("minecraft:trial_chambers"),
                structureTags = listOf("#minecraft:stronghold_biased_to"),
                configuredFeatures = listOf("minecraft:ore_sulfur_lower"),
                templatePools = listOf("minecraft:village/plains/town_centers"),
                testInstances = listOf("minecraft:always_pass"),
                worldClocks = listOf("minecraft:overworld"),
                timelines = listOf("minecraft:day"),
            )

        assertCompletion(environment, "setblock 0 0 0 minecraft:copp", "minecraft:copper_block")
        assertCompletion(environment, "fillbiome 0 0 0 1 1 1 minecraft:pale", "minecraft:pale_garden")
        assertCompletion(environment, "damage @s 1 minecraft:out", "minecraft:outside_border")
        assertCompletion(environment, "enchant @s minecraft:wind", "minecraft:wind_burst")
        assertCompletion(environment, "effect give @s minecraft:weav", "minecraft:weaving")
        assertCompletion(environment, "attribute @s minecraft:burn", "minecraft:burning_time")
        assertCompletion(environment, "particle minecraft:trial", "minecraft:trial_spawner_detection")
        assertCompletion(environment, "playsound minecraft:block.copper", "minecraft:block.copper_bulb.turn_on")
        assertCompletion(environment, "locate biome #minecraft:is_", "#minecraft:is_overworld")
        assertCompletion(environment, "locate poi minecraft:ho", "minecraft:home")
        assertCompletion(environment, "locate structure #minecraft:strong", "#minecraft:stronghold_biased_to")
        assertCompletion(environment, "place feature minecraft:ore_su", "minecraft:ore_sulfur_lower")
        assertCompletion(environment, "place jigsaw minecraft:village/plains/to", "minecraft:village/plains/town_centers")
        assertCompletion(environment, "place structure minecraft:trial", "minecraft:trial_chambers")
        assertCompletion(environment, "recipe give @s minecraft:copp", "minecraft:copper_chest")
        assertCompletion(environment, "advancement grant @s only minecraft:adventure/he", "minecraft:adventure/heart_transplanter")
        assertCompletion(environment, "test run minecraft:alw", "minecraft:always_pass")
        assertCompletion(environment, "time of minecraft:ov", "minecraft:overworld")
        assertCompletion(environment, "time query minecraft:d", "minecraft:day")
        assertCompletion(
            environment,
            "scoreboard objectives add copper minecraft.mined:minecraft:copp",
            "minecraft.mined:minecraft:copper_block",
        )
    }

    private fun completionEnvironment(): EngineCompletionEnvironment =
        EngineCompletionEnvironment(
            roots = fullCommandRoots,
            blocks = listOf("minecraft:stone"),
            items = listOf("minecraft:apple"),
            entities = listOf("minecraft:zombie"),
            functions = listOf("demo:main"),
            functionTags = listOf("demo:load"),
            objectives = listOf("runs"),
            scoreHolders = listOf("#value", "Steve"),
            storages = listOf("demo:state"),
            tags = listOf("mob"),
            gamerules = listOf("doDaylightCycle"),
            biomes = listOf("minecraft:plains"),
            damageTypes = listOf("minecraft:generic"),
            enchantments = listOf("minecraft:sharpness"),
            effects = listOf("minecraft:speed"),
            dimensions = listOf("minecraft:overworld"),
            attributes = listOf("minecraft:max_health"),
            particles = listOf("minecraft:flame"),
            sounds = listOf("minecraft:block.note_block.harp"),
            scoreboardCriteria = listOf("dummy", "trigger"),
        )

    private val fullCommandRoots =
        """
        advancement attribute ban ban-ip banlist bossbar clear clone damage data datapack debug
        defaultgamemode deop difficulty effect enchant execute experience fill fillbiome forceload
        function gamemode gamerule give help item jfr kick kill list locate loot me msg op pardon
        pardon-ip particle perf place playsound publish random recipe reload return ride rotate save-all
        save-off save-on say schedule scoreboard seed setblock setidletimeout setworldspawn spawnpoint
        spectate spreadplayers stop stopsound summon tag team teammsg tell tellraw tick time title tm tp
        test transfer trigger w weather whitelist worldborder xp
        """.trimIndent().split(Regex("\\s+"))

    private fun assertCompletion(
        environment: EngineCompletionEnvironment,
        source: String,
        vararg expected: String,
    ) {
        val values = completionValues(environment, source)
        expected.forEach { value -> assertTrue(value in values, "Expected '$value' for '$source', got $values") }
    }

    private fun completionValues(
        environment: EngineCompletionEnvironment,
        source: String,
    ): List<String> = EngineCommandCompletion.complete(source, source.length, environment).map { it.value }

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
        session.executeLine(
            """summon minecraft:item_display 0 2 0 {Tags:[modeled],item:{id:"minecraft:clay_ball",components:{"minecraft:item_model":"dice:d6"}}}""",
            3,
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

        val item = session.renderEntities().first { it.type == "minecraft:item_display" }.display!!
        assertEquals("dice:d6", item.itemId)

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

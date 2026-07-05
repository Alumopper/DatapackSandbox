package moe.afox.dpsandbox.core

import java.nio.file.Path
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SandboxQuickTestTest {
    private fun fixturePack(): Path =
        Path.of("src/test/resources/packs/counter")

    @Test
    fun `runs quick code tests from core api`() {
        val report = SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2")
            .load()
            .ticks(20)
            .assertScore("#clock", "ticks", 20)
            .requirePassed()

        assertTrue(report.passed)
        assertEquals(20, report.snapshot.asJsonObject.get("scores").asJsonObject.get("ticks").asJsonObject.get("#clock").asInt)
    }

    @Test
    fun `quick code tests collect assertion failures`() {
        val error = assertFailsWith<SandboxQuickTestAssertionError> {
            SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2")
                .assertScore("#clock", "ticks", 1)
                .requirePassed()
        }

        assertTrue(error.report.failures.single().contains("expected 1 but was 0"))
    }

    @Test
    fun `quick storage existence assertions explain failures`() {
        val report = SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2")
            .world {
                storage("demo:env", "{ready:true}")
            }
            .assertStorageExists("demo:env", "absent")
            .assertStorageMissing("demo:env", "ready")
            .report()

        assertTrue(!report.passed)
        assertTrue(report.failures.any { "storage demo:env absent expected present but was <missing>" in it }, report.failures.joinToString())
        assertTrue(report.failures.any { "storage demo:env ready expected missing but was true" in it }, report.failures.joinToString())
    }

    @Test
    fun `quick score range assertions explain failures`() {
        val report = SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2")
            .command("scoreboard objectives add runs dummy")
            .command("scoreboard players set #range runs 5")
            .assertScoreAtLeast("#range", "runs", 6)
            .assertScoreAtMost("#range", "runs", 4)
            .assertScoreRange("#range", "runs")
            .report()

        assertTrue(!report.passed)
        assertTrue(report.failures.any { "score #range runs expected >= 6 but was 5" in it }, report.failures.joinToString())
        assertTrue(report.failures.any { "score #range runs expected <= 4 but was 5" in it }, report.failures.joinToString())
        assertTrue(report.failures.any { "score #range runs range assertion requires min or max" in it }, report.failures.joinToString())
    }

    @Test
    fun `quick entity count range assertions explain failures`() {
        val report = SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2")
            .world {
                entity("minecraft:pig", 0.0, 64.0, 0.0, tags = listOf("range"))
                entity("minecraft:pig", 1.0, 64.0, 0.0, tags = listOf("range"))
            }
            .assertEntityCountAtLeast(3, type = "minecraft:pig", tag = "range")
            .assertEntityCountAtMost(1, type = "minecraft:pig", tag = "range")
            .assertEntityCountRange(type = "minecraft:pig", tag = "range")
            .report()

        assertTrue(!report.passed)
        assertTrue(report.failures.any { "entityCount type=minecraft:pig, tag=range expected >= 3 but was 2" in it }, report.failures.joinToString())
        assertTrue(report.failures.any { "entityCount type=minecraft:pig, tag=range expected <= 1 but was 2" in it }, report.failures.joinToString())
        assertTrue(report.failures.any { "entityCount type=minecraft:pig, tag=range range assertion requires min or max" in it }, report.failures.joinToString())
    }

    @Test
    fun `records keyboard and mouse player input events`() {
        val scenario = SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2")
            .keyInput("Steve", "key.jump")
            .mouseInput("Steve", "left", "click", 12.0, 8.0)
            .assertPlayerLastInput("Steve", "mouse", "left", "click")
            .assertPlayerEventTrace(player = "Steve", type = "key-input", success = true, count = 1)
            .assertPlayerEventTrace(player = "Steve", type = "mouse_input", success = true, count = 1)
        val traces = scenario.playerEventTraces()
        val mouseTraces = scenario.matchingPlayerEventTraces(player = "Steve", type = "mouse-input")
        val report = scenario.requirePassed()

        val player = report.snapshot.asJsonObject.get("players").asJsonObject.get("Steve").asJsonObject
        assertEquals("mouse", player.get("lastInput").asJsonObject.get("device").asString)
        assertEquals(2, player.get("inputEvents").asJsonArray.size())
        assertEquals(2, traces.size)
        assertEquals(1, mouseTraces.size)
        assertEquals("mouse_input", report.playerEventTraces.last().type)
        assertEquals(2, report.snapshot.asJsonObject.getAsJsonArray("playerEventTraces").size())
    }

    @Test
    fun `player event trace assertion failures include actual event candidates`() {
        val error = assertFailsWith<SandboxQuickTestAssertionError> {
            SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2")
                .keyInput("Steve", "key.jump")
                .assertPlayerEventTrace(player = "Steve", type = "damage")
                .requirePassed()
        }
        val message = error.message.orEmpty()

        assertTrue("actual event traces:" in message, message)
        assertTrue("player=Steve" in message, message)
        assertTrue("type=key_input" in message, message)
    }

    @Test
    fun `runs single mcfunction files from api with explicit version`() {
        val functionFile = Files.createTempFile("dps-single-function", ".mcfunction")
        Files.writeString(
            functionFile,
            """
            scoreboard objectives add runs dummy
            scoreboard players set #single runs 7
            """.trimIndent(),
        )

        val report = SandboxQuickTest.singleFunction(functionFile, "26.2")
            .function()
            .assertScore("#single", "runs", 7)
            .requirePassed()

        assertTrue(report.passed)
        assertEquals(7, report.snapshot.asJsonObject.get("scores").asJsonObject.get("runs").asJsonObject.get("#single").asInt)
    }

    @Test
    fun `single mcfunction files may start with a utf8 bom`() {
        val functionFile = Files.createTempFile("dps-single-function-bom", ".mcfunction")
        Files.writeString(
            functionFile,
            "\uFEFFscoreboard objectives add runs dummy\nscoreboard players set #bom runs 3",
        )

        SandboxQuickTest.singleFunction(functionFile, "26.2")
            .function()
            .assertScore("#bom", "runs", 3)
            .requirePassed()
    }

    @Test
    fun `runs single mcfunction strings from api`() {
        SandboxQuickTest.singleFunctionText(
            """
            scoreboard objectives add runs dummy
            scoreboard players set #string runs 5
            """.trimIndent(),
            version = "26.2",
        )
            .function()
            .assertScore("#string", "runs", 5)
            .requirePassed()
    }

    @Test
    fun `loads multiple mcfunction files and strings together`() {
        val helper = Files.createTempFile("dps-helper-function", ".mcfunction")
        Files.writeString(
            helper,
            """
            scoreboard players add #multi runs 2
            function demo:inline
            """.trimIndent(),
        )

        SandboxQuickTest.functions(
            functionSources = listOf(
                FunctionSource.text(
                    "demo:main",
                    """
                    scoreboard objectives add runs dummy
                    scoreboard players set #multi runs 1
                    function demo:helper
                    """.trimIndent(),
                ),
                FunctionSource.file("demo:helper", helper),
                FunctionSource.text("demo:inline", "scoreboard players add #multi runs 4"),
            ),
            version = "26.2",
            defaultFunctionId = "demo:main",
        )
            .function()
            .assertScore("#multi", "runs", 7)
            .requirePassed()
    }

    @Test
    fun `loads folder and zip datapacks as dependencies for function sources`() {
        val root = Files.createTempDirectory("dps-function-deps")
        val folderPack = writeDependencyPack(root.resolve("folder-pack"), "folder", "scoreboard players add #deps runs 2")
        val zipPack = zipPack(writeDependencyPack(root.resolve("zip-pack"), "zip", "scoreboard players add #deps runs 4"), root.resolve("zip-pack.zip"))

        SandboxQuickTest.functions(
            functionSources = listOf(
                FunctionSource.text(
                    "demo:main",
                    """
                    scoreboard objectives add runs dummy
                    scoreboard players set #deps runs 1
                    function demo:folder
                    function demo:zip
                    """.trimIndent(),
                ),
            ),
            version = "26.2",
            defaultFunctionId = "demo:main",
            dependencyPacks = listOf(folderPack, zipPack),
        )
            .function()
            .assertScore("#deps", "runs", 7)
            .requirePassed()
    }

    @Test
    fun `quick tests can get and assert structured outputs`() {
        val functionFile = Files.createTempFile("dps-output-function", ".mcfunction")
        Files.writeString(
            functionFile,
            """
            say hello from test
            tellraw Steve {"text":"gold","color":"yellow"}
            """.trimIndent(),
        )

        val scenario = SandboxQuickTest.singleFunction(functionFile, "26.2")
            .function()
            .assertOutput(command = "say", channel = "chat", target = "Steve", contains = "hello from test", order = 1)
            .assertOutput(
                OutputExpectation(
                    command = "tellraw",
                    target = "Steve",
                    text = "gold",
                    segment = OutputSegmentExpectation(text = "gold", color = "yellow"),
                    count = 1,
                    order = 2,
                ),
            )

        val outputs = scenario.outputs()
        val tellraw = scenario.matchingOutputs(command = "tellraw", text = "gold")

        assertEquals(2, outputs.size)
        assertEquals(1, tellraw.size)
        scenario.requirePassed()
    }

    @Test
    fun `output assertion failures include actual output candidates`() {
        val error = assertFailsWith<SandboxQuickTestAssertionError> {
            SandboxQuickTest.singleFunctionText("say actual candidate", version = "26.2")
                .function()
                .assertOutput(command = "say", contains = "missing candidate")
                .requirePassed()
        }
        val message = error.message.orEmpty()

        assertTrue("actual outputs:" in message, message)
        assertTrue("command=say" in message, message)
        assertTrue("<Server> actual candidate" in message, message)
    }

    @Test
    fun `quick output helpers support normalized text matching`() {
        val scenario = SandboxQuickTest.singleFunctionText("tellraw Steve {\"text\":\"generated     output\"}", version = "26.2")
            .function()
            .assertOutput(command = "tellraw", normalizedText = "generated output", normalizedContains = "generated output", count = 1)

        val matches = scenario.matchingOutputs(command = "tellraw", normalizedText = "generated output")

        assertEquals(1, matches.size)
        scenario.requirePassed()
    }

    @Test
    fun `quick tests can get and assert structured traces`() {
        val scenario = SandboxQuickTest.singleFunctionText(
            """
            say traced from quick test
            scoreboard objectives add traced dummy
            """.trimIndent(),
            version = "26.2",
        )
            .function()
            .assertTrace(root = "say", contains = "quick test", success = true)
            .assertTrace(TraceExpectation(root = "scoreboard", count = 1))

        val traces = scenario.traces()
        val scoreboardTraces = scenario.matchingTraces(root = "scoreboard")

        assertEquals(2, traces.size)
        assertEquals(1, scoreboardTraces.size)
        scenario.requirePassed()
    }

    @Test
    fun `trace assertion failures include actual trace candidates`() {
        val error = assertFailsWith<SandboxQuickTestAssertionError> {
            SandboxQuickTest.singleFunctionText("say actual trace candidate", version = "26.2")
                .function()
                .assertTrace(root = "scoreboard")
                .requirePassed()
        }
        val message = error.message.orEmpty()

        assertTrue("actual traces:" in message, message)
        assertTrue("root=say" in message, message)
        assertTrue("say actual trace candidate" in message, message)
    }

    @Test
    fun `quick tests can assert snapshot diffs`() {
        val scenario = SandboxQuickTest.singleFunctionText(
            """
            scoreboard objectives add runs dummy
            scoreboard players set #quick_diff runs 9
            """.trimIndent(),
            version = "26.2",
        )
            .function()
            .assertSnapshotDiff(path = "/scores/runs", kind = SnapshotDiffKind.ADDED, contains = "\"#quick_diff\": 9", count = 1)

        val report = scenario.requirePassed()

        assertTrue(report.snapshotDiffs.any { it.path == "/scores/runs" && it.kind == SnapshotDiffKind.ADDED })
    }

    @Test
    fun `quick test assertion errors include snapshot diff and trace summary`() {
        val error = assertFailsWith<SandboxQuickTestAssertionError> {
            SandboxQuickTest.singleFunctionText(
                """
                scoreboard objectives add runs dummy
                scoreboard players set #quick_failure runs 4
                """.trimIndent(),
                version = "26.2",
            )
                .function()
                .assertScore("#quick_failure", "runs", 5)
                .requirePassed()
        }
        val message = error.message.orEmpty()

        assertTrue("score #quick_failure runs expected 5 but was 4" in message, message)
        assertTrue("snapshot diff:" in message, message)
        assertTrue("\"#quick_failure\": 4" in message, message)
        assertTrue("trace summary:" in message, message)
        assertTrue("[OK] scoreboard players set #quick_failure runs 4" in message, message)
    }

    @Test
    fun `quick tests can predefine world state`() {
        val report = SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2", defaultPlayerName = null)
            .world {
                seed(123)
                difficulty("hard")
                defaultGameMode("creative")
                worldSpawn(4.0, 70.0, 5.0, angle = 90.0)
                forcedChunk(0, 0)
                biome(0, 64, 0, "minecraft:plains")
                block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
                entity("minecraft:pig", 1.0, 64.0, 0.0, tags = listOf("fixture"))
                player("Alex", x = 2.0, y = 65.0, z = 3.0, xp = 5, inventory = listOf(item("minecraft:stick", 2)))
                playerEffect("Alex", "minecraft:speed", durationTicks = 40, amplifier = 1)
                playerRecipe("Alex", "minecraft:bread")
                playerStat("Alex", "minecraft:jump", 3)
                playerSpawn("Alex", 2.0, 66.0, 3.0)
                team("red", members = listOf("Alex"), options = mapOf("color" to "red"))
                bossbar("demo:bar", "Demo", value = 3, max = 10, players = listOf("Alex"))
                score("#fixture", "ready", 1)
                storage("demo:env", "{ready:true}")
                gamerule("doDaylightCycle", "false")
            }
            .assertWorld(difficulty = "hard", defaultGameMode = "creative", seed = 123)
            .assertBlock(0, 64, 0, "minecraft:chest")
            .assertEntity(type = "minecraft:pig", tag = "fixture", position = Position(1.0, 64.0, 0.0))
            .assertEntityCount(expected = 1, type = "minecraft:pig", tag = "fixture")
            .assertEntityCountAtLeast(1, type = "minecraft:pig", tag = "fixture")
            .assertEntityCountAtMost(1, type = "minecraft:pig", tag = "fixture")
            .assertEntityCountRange(min = 1, max = 1, type = "minecraft:pig", tag = "fixture")
            .assertPlayer(
                name = "Alex",
                position = Position(2.0, 65.0, 3.0),
                dimension = "minecraft:overworld",
                gameMode = "survival",
                xp = 5,
                inventoryCount = 1,
                recipe = "minecraft:bread",
                effect = "minecraft:speed",
                stat = "minecraft:jump",
                statValue = 3,
            )
            .assertItem("Alex", "minecraft:stick", 2)
            .assertScore("#fixture", "ready", 1)
            .assertScoreAtLeast("#fixture", "ready", 1)
            .assertScoreAtMost("#fixture", "ready", 1)
            .assertScoreRange("#fixture", "ready", min = 1, max = 1)
            .assertStorageExists("demo:env")
            .assertStorageExists("demo:env", "ready")
            .assertStorageEquals("demo:env", "ready", "true")
            .assertStorageMissing("demo:env", "absent")
            .assertPlayerXp("Alex", 5)
            .requirePassed()

        val snapshot = report.snapshot.asJsonObject
        assertEquals("minecraft:chest", snapshot.get("blocks").asJsonArray[0].asJsonObject.get("id").asString)
        assertEquals("false", snapshot.get("gamerules").asJsonObject.get("doDaylightCycle").asString)
        assertEquals(1, snapshot.get("entities").asJsonArray.count { it.asJsonObject.get("type").asString == "minecraft:pig" })
        assertEquals("hard", snapshot.get("difficulty").asString)
        assertEquals(1, snapshot.get("forcedChunks").asJsonArray.size())
        assertEquals("Alex", snapshot.get("teams").asJsonObject.get("red").asJsonObject.getAsJsonArray("members")[0].asString)
        assertEquals(3, snapshot.get("bossbars").asJsonObject.get("demo:bar").asJsonObject.get("value").asInt)
    }

    @Test
    fun `runs quick tests across multiple version-specific packs`() {
        val dir = Files.createTempDirectory("dps-quick-matrix")
        val pack1204 = writeVersionPack(dir.resolve("pack-1204"), packFormat = "26", functionDir = "functions")
        val pack2612 = writeVersionPack(dir.resolve("pack-2612"), packFormat = "101.1", functionDir = "function")
        val pack262 = writeVersionPack(dir.resolve("pack-262"), packFormat = "107.1", functionDir = "function")

        val report = SandboxQuickTest.matrix(
            mapOf(
                "1.20.4" to listOf(pack1204),
                "26.1.2" to listOf(pack2612),
                "26.2" to listOf(pack262),
            ),
        )
            .load()
            .assertScore("#matrix", "runs", 6)
            .requirePassed()

        assertTrue(report.passed)
        assertEquals(setOf("1.20.4", "26.1.2", "26.2"), report.reports.keys)
    }

    private fun writeVersionPack(root: Path, packFormat: String, functionDir: String): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": $packFormat,
                "description": "temporary matrix pack"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve(functionDir)
        Files.createDirectories(functionRoot)
        Files.writeString(
            functionRoot.resolve("load.mcfunction"),
            """
            scoreboard objectives add runs dummy
            scoreboard players set #matrix runs 6
            """.trimIndent(),
        )
        val tagRoot = root.resolve("data").resolve("minecraft").resolve("tags").resolve(functionDir)
        Files.createDirectories(tagRoot)
        Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:load"]}""")
        return root
    }

    private fun writeDependencyPack(root: Path, functionName: String, body: String): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary dependency pack"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        Files.writeString(functionRoot.resolve("$functionName.mcfunction"), body)
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

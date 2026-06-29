package moe.afox.dpsandbox.core

import java.nio.file.Path
import java.nio.file.Files
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
    fun `records keyboard and mouse player input events`() {
        val scenario = SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2")
            .keyInput("Steve", "key.jump")
            .mouseInput("Steve", "left", "click", 12.0, 8.0)
            .assertPlayerLastInput("Steve", "mouse", "left", "click")
            .requirePassed()

        val player = scenario.snapshot.asJsonObject.get("players").asJsonObject.get("Steve").asJsonObject
        assertEquals("mouse", player.get("lastInput").asJsonObject.get("device").asString)
        assertEquals(2, player.get("inputEvents").asJsonArray.size())
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
            .assertOutput(command = "say", channel = "chat", target = "Steve", contains = "hello from test")
            .assertOutput(
                OutputExpectation(
                    command = "tellraw",
                    target = "Steve",
                    text = "gold",
                    segment = OutputSegmentExpectation(text = "gold", color = "yellow"),
                    count = 1,
                ),
            )

        val outputs = scenario.outputs()
        val tellraw = scenario.matchingOutputs(command = "tellraw", text = "gold")

        assertEquals(2, outputs.size)
        assertEquals(1, tellraw.size)
        scenario.requirePassed()
    }

    @Test
    fun `quick tests can predefine world state`() {
        val report = SandboxQuickTest.create(listOf(fixturePack()), version = "26.1.2", defaultPlayerName = null)
            .world {
                block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
                entity("minecraft:pig", 1.0, 64.0, 0.0, tags = listOf("fixture"))
                player("Alex", x = 2.0, y = 65.0, z = 3.0, xp = 5, inventory = listOf(item("minecraft:stick", 2)))
                score("#fixture", "ready", 1)
                storage("demo:env", "{ready:true}")
                gamerule("doDaylightCycle", "false")
            }
            .assertScore("#fixture", "ready", 1)
            .assertStorageEquals("demo:env", "ready", "true")
            .assertPlayerXp("Alex", 5)
            .requirePassed()

        val snapshot = report.snapshot.asJsonObject
        assertEquals("minecraft:chest", snapshot.get("blocks").asJsonArray[0].asJsonObject.get("id").asString)
        assertEquals("false", snapshot.get("gamerules").asJsonObject.get("doDaylightCycle").asString)
        assertEquals(1, snapshot.get("entities").asJsonArray.count { it.asJsonObject.get("type").asString == "minecraft:pig" })
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
}

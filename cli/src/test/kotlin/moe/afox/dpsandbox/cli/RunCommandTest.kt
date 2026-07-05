package moe.afox.dpsandbox.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunCommandTest {
    @Test
    fun `run executes a single mcfunction without a datapack`() {
        val functionFile = Files.createTempFile("dps-cli-single-function", ".mcfunction")
        Files.writeString(
            functionFile,
            """
            scoreboard objectives add runs dummy
            scoreboard players set #cli runs 9
            """.trimIndent(),
        )

        val output = captureStdout {
            main(arrayOf("run", "--mcfunction", functionFile.toString(), "--version", "26.2", "--snapshot"))
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#cli\": 9" in output, output)
    }

    @Test
    fun `run executes inline mcfunction text`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "scoreboard objectives add runs dummy\nscoreboard players set #inline runs 6",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#inline\": 6" in output, output)
    }

    @Test
    fun `run reads stdin as mcfunction text`() {
        val output = captureStdout(
            stdin = """
            scoreboard objectives add runs dummy
            scoreboard players set #stdin runs 8
            """.trimIndent(),
        ) {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--stdin",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#stdin\": 8" in output, output)
    }

    @Test
    fun `run reads stdin as raw command lines`() {
        val output = captureStdout(
            stdin = """
            scoreboard objectives add runs dummy
            scoreboard players set #stdin_commands runs 4
            """.trimIndent(),
        ) {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--stdin",
                    "--stdin-mode",
                    "commands",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#stdin_commands\": 4" in output, output)
    }

    @Test
    fun `run executes multiple command files in order`() {
        val first = Files.createTempFile("dps-cli-commands-first", ".txt")
        val second = Files.createTempFile("dps-cli-commands-second", ".txt")
        Files.writeString(
            first,
            """
            scoreboard objectives add runs dummy
            scoreboard players set #command_files runs 2
            """.trimIndent(),
        )
        Files.writeString(second, "scoreboard players add #command_files runs 3")

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--command-file",
                    first.toString(),
                    "--command-file",
                    second.toString(),
                    "--assert",
                    "score:#command_files:runs=5",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#command_files\": 5" in output, output)
    }

    @Test
    fun `run applies world fixtures and inline assertions without a pack`() {
        val worldFile = Files.createTempFile("dps-cli-world", ".json")
        Files.writeString(
            worldFile,
            """
            {
              "seed": 42,
              "players": [
                {
                  "name": "Alex",
                  "inventory": [
                    { "id": "minecraft:stick", "count": 2 }
                  ]
                }
              ],
              "scores": [
                { "target": "#fixture", "objective": "ready", "value": 1 }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--world",
                    worldFile.toString(),
                    "--assert",
                    """{"world":{"seed":42}}""",
                    "--assert",
                    """{"score":{"target":"#fixture","objective":"ready","equals":1}}""",
                    "--assert",
                    """{"item":{"player":"Alex","id":"minecraft:stick","count":2}}""",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"seed\": 42" in output, output)
        assertTrue("\"#fixture\": 1" in output, output)
    }

    @Test
    fun `run accepts shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "scoreboard objectives add runs dummy\nscoreboard players set #short runs 5\nsay shorthand ok",
                    "--assert",
                    "score:#short:runs=5",
                    "--assert",
                    "score:#short:runs>=4",
                    "--assert",
                    "score:#short:runs<=6",
                    "--assert",
                    "output:shorthand ok",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts storage shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--command",
                    "data merge storage demo:env {ready:true}",
                    "--assert",
                    "storage:demo:env?",
                    "--assert",
                    "storage:demo:env:ready=true",
                    "--assert",
                    "storage:demo:env:absent!",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts item shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--command",
                    "give Steve minecraft:stick 3",
                    "--assert",
                    "item:Steve:minecraft:stick=3",
                    "--assert",
                    "item:Steve:minecraft:stick>=2",
                    "--assert",
                    "item:Steve:minecraft:stick<=4",
                    "--assert",
                    "item:Steve:minecraft:stick@0=3",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts entity and warning shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--command",
                    """summon minecraft:pig 0 0 0 {Tags:["fixture"]}""",
                    "--command",
                    "summon minecraft:cow 0 0 0",
                    "--command",
                    "ban Steve",
                    "--assert",
                    "entity:*=3",
                    "--assert",
                    "entity:minecraft:pig@fixture=1",
                    "--assert",
                    "entity:minecraft:cow>=1",
                    "--assert",
                    "warning=1",
                    "--assert",
                    "warning:Command 'ban'",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run loads multiple mcfunction files and strings together`() {
        val mainFile = Files.createTempFile("dps-cli-main-function", ".mcfunction")
        val helperFile = Files.createTempFile("dps-cli-helper-function", ".mcfunction")
        Files.writeString(
            mainFile,
            """
            scoreboard objectives add runs dummy
            scoreboard players set #cli_multi runs 1
            function demo:helper
            """.trimIndent(),
        )
        Files.writeString(
            helperFile,
            """
            scoreboard players add #cli_multi runs 2
            function demo:inline
            """.trimIndent(),
        )

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-id",
                    "demo:main",
                    "--mcfunction",
                    "demo:main=$mainFile",
                    "--mcfunction",
                    "demo:helper=$helperFile",
                    "--mcfunction-text",
                    "demo:inline=scoreboard players add #cli_multi runs 4",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#cli_multi\": 7" in output, output)
    }

    @Test
    fun `run uses folder and zip packs as mcfunction dependencies`() {
        val root = Files.createTempDirectory("dps-cli-function-deps")
        val folderPack = writeDependencyPack(root.resolve("folder-pack"), "folder", "scoreboard players add #cli_deps runs 2")
        val zipPack = zipPack(writeDependencyPack(root.resolve("zip-pack"), "zip", "scoreboard players add #cli_deps runs 4"), root.resolve("zip-pack.zip"))

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--pack",
                    folderPack.toString(),
                    "--pack",
                    zipPack.toString(),
                    "--mcfunction-id",
                    "demo:main",
                    "--mcfunction-text",
                    """
                    demo:main=scoreboard objectives add runs dummy
                    scoreboard players set #cli_deps runs 1
                    function demo:folder
                    function demo:zip
                    """.trimIndent(),
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#cli_deps\": 7" in output, output)
    }

    @Test
    fun `run can print and write command trace`() {
        val traceFile = Files.createTempFile("dps-cli-trace", ".jsonl")

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "say traced from cli",
                    "--trace",
                    "--trace-file",
                    traceFile.toString(),
                ),
            )
        }

        assertTrue("trace OK say traced from cli" in output, output)
        assertTrue("trace written: $traceFile" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"command\": \"say traced from cli\"" in traceJson, traceJson)
        assertTrue("\"root\": \"say\"" in traceJson, traceJson)
        assertTrue("\"success\": true" in traceJson, traceJson)
    }

    @Test
    fun `run writes output events as jsonl`() {
        val outputsFile = Files.createTempFile("dps-cli-outputs", ".jsonl")

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "say output artifact",
                    "--outputs-file",
                    outputsFile.toString(),
                ),
            )
        }

        assertTrue("outputs written: $outputsFile" in output, output)
        val outputJson = Files.readString(outputsFile)
        assertTrue("\"command\": \"say\"" in outputJson, outputJson)
        assertTrue("\"channel\": \"chat\"" in outputJson, outputJson)
        assertTrue("\"text\": \"<Server> output artifact\"" in outputJson, outputJson)
    }

    @Test
    fun `run filters printed and written command traces`() {
        val traceFile = Files.createTempFile("dps-cli-filtered-trace", ".jsonl")

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "say hidden from filtered trace\nscoreboard objectives add runs dummy\nscoreboard players set #filtered runs 1",
                    "--trace",
                    "--trace-filter",
                    "root=scoreboard",
                    "--trace-file",
                    traceFile.toString(),
                ),
            )
        }

        assertTrue("trace OK scoreboard objectives add runs dummy" in output, output)
        assertTrue("trace OK scoreboard players set #filtered runs 1" in output, output)
        assertFalse("trace OK say hidden from filtered trace" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"root\": \"scoreboard\"" in traceJson, traceJson)
        assertTrue("\"command\": \"scoreboard players set #filtered runs 1\"" in traceJson, traceJson)
        assertFalse("\"command\": \"say hidden from filtered trace\"" in traceJson, traceJson)
    }

    @Test
    fun `run inline assertions can check snapshot diffs`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "scoreboard objectives add runs dummy\nscoreboard players set #inline_diff runs 8",
                    "--assert",
                    """{"snapshotDiff":{"path":"/scores/runs","kind":"added","contains":"\"#inline_diff\": 8","count":1}}""",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run reads assertions from json files`() {
        val assertionsFile = Files.createTempFile("dps-cli-assertions", ".json")
        Files.writeString(
            assertionsFile,
            """
            {
              "assertions": [
                { "score": { "target": "#file_assert", "objective": "runs", "equals": 11 } },
                { "output": { "command": "say", "contains": "assert file ok", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "scoreboard objectives add runs dummy\nscoreboard players set #file_assert runs 11\nsay assert file ok",
                    "--assert-file",
                    assertionsFile.toString(),
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run can print and write snapshot diff`() {
        val diffFile = Files.createTempFile("dps-cli-snapshot-diff", ".json")

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "scoreboard objectives add runs dummy\nscoreboard players set #diff runs 5",
                    "--snapshot-diff",
                    "--snapshot-diff-file",
                    diffFile.toString(),
                ),
            )
        }

        assertTrue("+ /scores/runs =" in output, output)
        assertTrue("\"#diff\": 5" in output, output)
        assertTrue("snapshot diff written: $diffFile" in output, output)
        val diffJson = Files.readString(diffFile)
        assertTrue("\"path\": \"/scores/runs\"" in diffJson, diffJson)
        assertTrue("\"kind\": \"added\"" in diffJson, diffJson)
    }

    @Test
    fun `version lists supported datapack formats`() {
        val output = captureStdout {
            main(arrayOf("version"))
        }

        assertTrue("1.20.4 java=17 pack_format=26 data=3700" in output, output)
        assertTrue("26.1.2 java=25 pack_format=101.1" in output, output)
        assertTrue("26.2 java=25 pack_format=107.1 data=4903 default" in output, output)
    }

    @Test
    fun `version reports profile diffs`() {
        val output = captureStdout {
            main(arrayOf("version", "1.20.4", "26.2"))
        }

        assertTrue("profile diff 1.20.4 -> 26.2" in output, output)
        assertTrue("java: 17 -> 25" in output, output)
        assertTrue("pack_format: 26 -> 107.1" in output, output)
        assertTrue("command_roots: added=transfer" in output, output)
    }

    private fun captureStdout(stdin: String? = null, block: () -> Unit): String {
        val original = System.out
        val originalIn = System.`in`
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
        if (stdin != null) {
            System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
        }
        return try {
            block()
            bytes.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
            System.setIn(originalIn)
        }
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

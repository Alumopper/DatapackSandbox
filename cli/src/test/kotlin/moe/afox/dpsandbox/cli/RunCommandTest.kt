package moe.afox.dpsandbox.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
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

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
        return try {
            block()
            bytes.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
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

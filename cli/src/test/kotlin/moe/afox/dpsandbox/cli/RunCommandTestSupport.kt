package moe.afox.dpsandbox.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

abstract class RunCommandTestSupport {
    protected fun captureStdout(
        stdin: String? = null,
        block: () -> Unit,
    ): String {
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

    protected fun runCliProcess(vararg args: String): ProcessResult {
        val javaBinary =
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
        val process =
            ProcessBuilder(
                listOf(
                    javaBinary.toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "moe.afox.dpsandbox.cli.MainKt",
                ) + args,
            ).redirectErrorStream(true)
                .start()

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        if (!finished) {
            process.destroyForcibly()
            error("CLI process timed out:${System.lineSeparator()}$output")
        }
        return ProcessResult(process.exitValue(), output)
    }

    protected data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    protected fun writeDependencyPack(
        root: Path,
        functionName: String,
        body: String,
    ): Path {
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

    protected fun writeMissingReferencePack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "missing resource reference test"
              }
            }
            """.trimIndent(),
        )
        val tagRoot =
            root
                .resolve("data")
                .resolve("minecraft")
                .resolve("tags")
                .resolve("function")
        Files.createDirectories(tagRoot)
        Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:missing_load"]}""")
        return root
    }

    protected fun writeResourceOverlayPack(
        root: Path,
        marker: String,
        includeMissingLoad: Boolean,
    ): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "overlay report $marker"
              }
            }
            """.trimIndent(),
        )

        val functionRoot = root.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        Files.writeString(functionRoot.resolve("noop.mcfunction"), "say $marker")

        val recipeRoot = root.resolve("data").resolve("demo").resolve("recipe")
        Files.createDirectories(recipeRoot)
        Files.writeString(
            recipeRoot.resolve("marker.json"),
            """
            {
              "type": "minecraft:crafting_shapeless",
              "marker": "$marker",
              "ingredients": [],
              "result": { "id": "minecraft:stone", "count": 1 }
            }
            """.trimIndent(),
        )

        if (includeMissingLoad) {
            val tagRoot =
                root
                    .resolve("data")
                    .resolve("minecraft")
                    .resolve("tags")
                    .resolve("function")
            Files.createDirectories(tagRoot)
            Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:missing_load"]}""")
        }
        return root
    }

    protected fun writeAdvancementPack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary advancement pack"
              }
            }
            """.trimIndent(),
        )
        val advancementRoot = root.resolve("data").resolve("demo").resolve("advancement")
        Files.createDirectories(advancementRoot)
        Files.writeString(
            advancementRoot.resolve("shorthand.json"),
            """
            {
              "criteria": {
                "done": {
                  "trigger": "minecraft:tick"
                }
              }
            }
            """.trimIndent(),
        )
        return root
    }

    protected fun zipPack(
        root: Path,
        output: Path,
    ): Path {
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

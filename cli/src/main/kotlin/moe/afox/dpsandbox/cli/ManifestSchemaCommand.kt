package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class ManifestSchemaCommand : CliktCommand(name = "schema") {
    private val output by option("--output", "-o").path()
    private val check by option("--check").path()

    override fun run() {
        try {
            if (output != null && check != null) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "schema accepts only one file mode: --output or --check")
            }
            val schema = ManifestSchema.readText()
            val checkPath = check
            if (checkPath != null) {
                checkSchemaFile(checkPath, schema)
                return
            }
            val outputPath = output
            if (outputPath == null) {
                print(schema)
            } else {
                outputPath.parent?.let(Files::createDirectories)
                Files.writeString(outputPath, schema, StandardCharsets.UTF_8)
                println(ConsoleStyle.green("schema written: $outputPath"))
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun checkSchemaFile(
        path: Path,
        schema: String,
    ) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "schema check file does not exist: $path")
        }
        val actual = normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8)).trimEnd()
        val expected = normalizeNewlines(schema).trimEnd()
        if (actual != expected) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "schema is out of date: $path; regenerate with schema --output <file>",
            )
        }
        println(ConsoleStyle.green("schema up to date: $path"))
    }

    private fun normalizeNewlines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')
}

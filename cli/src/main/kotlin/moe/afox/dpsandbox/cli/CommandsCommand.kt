package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfiles
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class CommandsCommand : CliktCommand(name = "commands") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val docs by option("--docs").flag(default = false)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()
    private val check by option("--check").path()

    override fun run() {
        try {
            if (docs && json) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "commands accepts only one output mode: --docs or --json")
            }
            if (check != null && output != null) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "commands accepts only one file mode: --output or --check")
            }
            val profile = VersionProfiles.get(version)
            val commands = DpsCommandCatalog.rootCommands(profile)
            val checkPath = check
            when {
                checkPath != null -> checkCommandDocs(checkPath, commands)
                docs -> emit(renderMarkdown(commands))
                json -> emit(JsonValues.render(renderJson(profile.id, commands)))
                else -> emit(renderPlain(commands))
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun renderPlain(commands: List<CompletionSuggestion>): String =
        commands.joinToString(System.lineSeparator()) { command ->
            val behavior = command.behaviorLevel?.id ?: "unknown"
            "${command.value} $behavior - ${command.description}"
        }

    private fun renderMarkdown(commands: List<CompletionSuggestion>): String =
        buildString {
            appendLine("| Command | Behavior | Description |")
            appendLine("|---|---|---|")
            commands.forEach { command ->
                val behavior = command.behaviorLevel?.id ?: "unknown"
                appendLine("| `${markdownCell(command.value)}` | `$behavior` | ${markdownCell(command.description)} |")
            }
        }.trimEnd()

    private fun renderJson(
        version: String,
        commands: List<CompletionSuggestion>,
    ): JsonObject =
        JsonObject().also { root ->
            root.addProperty("version", version)
            root.add(
                "commands",
                JsonArray().also { array ->
                    commands.forEach { command ->
                        array.add(
                            JsonObject().also { json ->
                                json.addProperty("command", command.value)
                                json.addProperty("behavior", command.behaviorLevel?.id ?: "unknown")
                                json.addProperty("description", command.description)
                                json.addProperty("group", command.group)
                            },
                        )
                    }
                },
            )
        }

    private fun markdownCell(value: String): String = value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    private fun emit(content: String) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("commands output written: $outputPath"))
        }
    }

    private fun checkCommandDocs(
        path: Path,
        commands: List<CompletionSuggestion>,
    ) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "commands check file does not exist: $path")
        }
        val docLines = normalizeDoc(Files.readString(path, StandardCharsets.UTF_8)).lineSequence().toList()
        val missing =
            commands.mapNotNull { command ->
                val behavior = command.behaviorLevel?.id ?: "unknown"
                val commandPattern = Regex("""`[^`]*\b${Regex.escape(command.value)}\b[^`]*`""")
                val covered = docLines.any { line -> commandPattern.containsMatchIn(line) && "`$behavior`" in line }
                if (covered) null else "${command.value} ($behavior)"
            }
        if (missing.isNotEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "commands docs are out of date: $path; missing ${missing.joinToString()}",
            )
        }
        println(ConsoleStyle.green("commands docs cover catalog: $path"))
    }

    private fun normalizeDoc(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')
}

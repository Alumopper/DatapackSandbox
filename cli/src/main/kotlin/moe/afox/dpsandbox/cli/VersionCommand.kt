package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfileDiffs
import moe.afox.dpsandbox.core.VersionProfileDocs
import moe.afox.dpsandbox.core.VersionProfileJson
import moe.afox.dpsandbox.core.VersionProfiles
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class VersionCommand : CliktCommand(name = "version") {
    private val versions by argument("version").multiple(required = false)
    private val docs by option("--docs").flag(default = false)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()
    private val docsCheck by option("--check").path()
    private val docsLocale by option("--locale").default("en")

    override fun run() {
        try {
            if (docs && json) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version accepts only one output mode: --docs or --json")
            }
            if (docsCheck != null && output != null) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version accepts only one file mode: --output or --check")
            }
            if (docsCheck != null && !docs) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version --check is only supported with --docs")
            }
            if (!docs && docsLocale != "en") {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version --locale is only supported with --docs")
            }
            if (docs) {
                if (versions.isNotEmpty()) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version --docs does not accept profile arguments")
                }
                val locale = normalizedDocsLocale(docsLocale)
                val table = VersionProfileDocs.renderMarkdownTable(locale = locale)
                val checkPath = docsCheck
                if (checkPath == null) {
                    emit(table)
                } else {
                    checkDocsTable(checkPath, table, locale)
                }
                return
            }
            if (json) {
                val report =
                    when (versions.size) {
                        0 -> VersionProfileJson.profiles()
                        2 -> {
                            val from = VersionProfiles.get(versions[0])
                            val to = VersionProfiles.get(versions[1])
                            VersionProfileJson.diff(VersionProfileDiffs.diff(from, to))
                        }
                        else -> throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "version --json accepts either no arguments or two profile ids to diff",
                        )
                    }
                emit(JsonValues.render(report))
                return
            }
            when (versions.size) {
                0 ->
                    emit(
                        VersionProfiles.all.joinToString(System.lineSeparator()) { profile ->
                            val marker = if (profile == VersionProfiles.default) " default" else ""
                            val dataVersion = profile.dataVersion?.let { " data=$it" }.orEmpty()
                            "${profile.id} java=${profile.javaMajor} pack_format=${profile.dataPackFormat}$dataVersion$marker - ${profile.description}"
                        },
                    )
                2 -> {
                    val from = VersionProfiles.get(versions[0])
                    val to = VersionProfiles.get(versions[1])
                    emit(VersionProfileDiffs.render(VersionProfileDiffs.diff(from, to)))
                }
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "version accepts either no arguments or two profile ids to diff",
                )
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun emit(content: String) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("version output written: $outputPath"))
        }
    }

    private fun checkDocsTable(
        path: Path,
        table: String,
        locale: String,
    ) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version docs check file does not exist: $path")
        }
        val actual = normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8))
        val expected = normalizeNewlines(table)
        if (!actual.contains(expected)) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "version docs are out of date: $path; regenerate with version --docs ${localeOption(locale)}--output <file>",
            )
        }
        println(ConsoleStyle.green("version docs up to date: $path"))
    }

    private fun normalizedDocsLocale(value: String): String =
        when (value.lowercase().replace('_', '-')) {
            "en", "en-us" -> "en"
            "zh", "zh-cn", "zh-hans", "zh-hans-cn" -> "zh-CN"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported version docs locale '$value'; expected en or zh-CN")
        }

    private fun localeOption(locale: String): String = if (locale == "en") "" else "--locale $locale "

    private fun normalizeNewlines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')
}

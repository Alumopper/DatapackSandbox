package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.toJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class DiffCommand : CliktCommand(name = "diff") {
    private val before by argument("before").path(mustExist = true)
    private val after by argument("after").path(mustExist = true).optional()
    private val snapshot by option("--snapshot").flag(default = false)
    private val state by option("--state").flag(default = false)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()
    private val check by option("--check").flag(default = false)
    private val script by option("--script").flag(default = false)

    override fun run() {
        try {
            if (script) {
                if (after != null) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "diff --script expects one manifest path, not before/after JSON inputs",
                    )
                }
                if (snapshot || state || json || check) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "diff --script supports --output only; remove --snapshot, --state, --json, or --check",
                    )
                }
                emit(ManifestRunner.exportExternalDiffScript(before), writtenMessage = "diff script written")
                return
            }
            val afterPath =
                after
                    ?: throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "diff requires <after.json>; use --script with a single manifest to export an external replay script",
                    )
            val beforeJson = readComparisonJson(before)
            val afterJson = readComparisonJson(afterPath)
            val entries =
                if (state) {
                    SnapshotDiff.stateDiff(beforeJson, afterJson)
                } else {
                    SnapshotDiff.diff(beforeJson, afterJson)
                }
            val content = if (json) JsonValues.render(SnapshotDiff.toJson(entries)) else SnapshotDiff.render(entries)
            emit(content, writtenMessage = "diff output written")
            if (check && entries.isNotEmpty()) {
                exitProcess(ExitCodes.ASSERTION_FAILED)
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun readComparisonJson(path: Path): JsonElement {
        val parsed =
            try {
                JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
            } catch (error: Exception) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid JSON for diff input $path", cause = error)
            }
        return if (snapshot) extractSingleSnapshot(path, parsed) else parsed
    }

    private fun extractSingleSnapshot(
        path: Path,
        root: JsonElement,
    ): JsonElement {
        val snapshots = collectSnapshots(root)
        return when (snapshots.size) {
            1 -> snapshots.single().deepCopy()
            0 -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "diff --snapshot input has no snapshot field: $path")
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "diff --snapshot input has ${snapshots.size} snapshots; compare a single attempt or a raw snapshot: $path",
            )
        }
    }

    private fun collectSnapshots(root: JsonElement): List<JsonElement> =
        when {
            root.isJsonObject -> {
                val obj = root.asJsonObject
                when {
                    obj.has("snapshot") -> listOf(obj.get("snapshot"))
                    obj.has("attempts") && obj.get("attempts").isJsonArray -> obj.getAsJsonArray("attempts").flatMap(::collectSnapshots)
                    else -> emptyList()
                }
            }
            root.isJsonArray -> root.asJsonArray.flatMap(::collectSnapshots)
            else -> emptyList()
        }

    private fun emit(
        content: String,
        writtenMessage: String,
    ) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("$writtenMessage: $outputPath"))
        }
    }
}

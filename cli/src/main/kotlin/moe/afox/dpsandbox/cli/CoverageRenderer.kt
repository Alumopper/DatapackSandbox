package moe.afox.dpsandbox.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.DatapackCoverageOptions
import moe.afox.dpsandbox.core.DatapackCoverageReport
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.SandboxException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal object CoverageRenderer {
    fun print(
        version: String,
        coverage: DatapackCoverageReport,
    ) {
        println(
            "COVERAGE $version " +
                "lines=${formatPercentage(coverage.linePercentage)}% (${coverage.coveredLines}/${coverage.totalLines}) " +
                "functions=${formatPercentage(coverage.functionPercentage)}% " +
                "(${coverage.coveredFunctions}/${coverage.totalFunctions})",
        )
        coverage.functions
            .filter { function -> !function.covered || function.coveredLines < function.totalLines }
            .forEach { function ->
                val uncovered =
                    function.lines
                        .filterNot { it.covered }
                        .joinToString { it.line.toString() }
                        .ifBlank { "-" }
                println(
                    "  ${function.id} lines=${function.coveredLines}/${function.totalLines} " +
                        "invocations=${function.invocations} uncovered=$uncovered",
                )
            }
    }
}

internal fun coverageOptions(
    minimumLinePercentage: Double?,
    minimumFunctionPercentage: Double?,
    includes: List<String>,
    excludes: List<String>,
): DatapackCoverageOptions =
    try {
        DatapackCoverageOptions(
            minimumLinePercentage = minimumLinePercentage,
            minimumFunctionPercentage = minimumFunctionPercentage,
            includes = includes,
            excludes = excludes,
        )
    } catch (error: IllegalArgumentException) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, error.message ?: "Invalid coverage options", cause = error)
    }

internal fun DatapackCoverageReport.toCoverageJson(): JsonObject =
    JsonObject().also { json ->
        json.addProperty("coveredLines", coveredLines)
        json.addProperty("totalLines", totalLines)
        json.addProperty("linePercentage", linePercentage)
        json.addProperty("coveredFunctions", coveredFunctions)
        json.addProperty("totalFunctions", totalFunctions)
        json.addProperty("functionPercentage", functionPercentage)
        json.add(
            "functions",
            JsonArray().also { functionsJson ->
                functions.forEach { function ->
                    functionsJson.add(
                        JsonObject().also { functionJson ->
                            functionJson.addProperty("id", function.id.toString())
                            function.file?.let { functionJson.addProperty("file", it) }
                            functionJson.addProperty("covered", function.covered)
                            functionJson.addProperty("invocations", function.invocations)
                            functionJson.addProperty("coveredLines", function.coveredLines)
                            functionJson.addProperty("totalLines", function.totalLines)
                            functionJson.addProperty("linePercentage", function.linePercentage)
                            functionJson.add(
                                "lines",
                                JsonArray().also { linesJson ->
                                    function.lines.forEach { line ->
                                        linesJson.add(
                                            JsonObject().also { lineJson ->
                                                lineJson.addProperty("line", line.line)
                                                lineJson.addProperty("command", line.command)
                                                lineJson.addProperty("covered", line.covered)
                                                lineJson.addProperty("hits", line.hits)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

internal fun writeCoverageFile(
    path: Path,
    coverage: DatapackCoverageReport,
) {
    Files.writeString(path, JsonValues.render(coverage.toCoverageJson()), StandardCharsets.UTF_8)
    println(ConsoleStyle.green("coverage written: $path"))
}

internal fun writeManifestCoverageFile(
    path: Path,
    results: List<ManifestResult>,
) {
    val json =
        JsonArray().also { manifestsJson ->
            results.forEach { result ->
                manifestsJson.add(
                    JsonObject().also { manifestJson ->
                        manifestJson.addProperty("path", result.path.toString())
                        manifestJson.addProperty("passed", result.passed)
                        manifestJson.add(
                            "attempts",
                            JsonArray().also { attemptsJson ->
                                result.attempts.forEach { attempt ->
                                    attemptsJson.add(
                                        JsonObject().also { attemptJson ->
                                            attemptJson.addProperty("version", attempt.version)
                                            attemptJson.addProperty("passed", attempt.passed)
                                            attempt.coverage?.let { attemptJson.add("coverage", it.toCoverageJson()) }
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
    Files.writeString(path, JsonValues.render(json), StandardCharsets.UTF_8)
    println(ConsoleStyle.green("coverage written: $path"))
}

private fun formatPercentage(value: Double): String = "%.2f".format(Locale.ROOT, value)

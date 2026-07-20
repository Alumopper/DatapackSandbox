package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.FunctionSource
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createFunctionSandbox
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.core.toJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.system.exitProcess

class BenchmarkCommand : CliktCommand(name = "benchmark") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple()
    private val scale by option("--scale").int().default(50)
    private val lootTable by option("--loot-table")
    private val lootContext by option("--loot-context").default("minecraft:empty")
    private val seed by option("--seed").long().default(0)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()

    override fun run() {
        try {
            require(scale > 0) { "benchmark --scale must be positive" }
            val results = mutableListOf<BenchmarkScenario>()
            if (packs.isNotEmpty()) {
                results +=
                    timed("pack-load") {
                        val sandbox = createSandbox(version, packs)
                        val resources = ManifestRunner.summarizeResources(sandbox)
                        mapOf(
                            "packs" to packs.size,
                            "resources" to resources.resourceIndex,
                        )
                    }
            }
            results += runScoreboardBenchmark()
            results += runStorageBenchmark()
            results += runFunctionChainBenchmark()
            results += runManifestBatchBenchmark()
            lootTable?.let { results += runLootBenchmark(it) }
            emit(if (json) renderJson(results) else renderPlain(results))
        } catch (error: IllegalArgumentException) {
            println(ConsoleStyle.diagnostic("${DiagnosticCode.INPUT_FORMAT}: ${error.message}"))
            exitProcess(ExitCodes.INPUT_FORMAT)
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun runScoreboardBenchmark(): BenchmarkScenario =
        timed("scoreboard") {
            val sandbox = createBenchmarkSandbox()
            sandbox.executeCommand("scoreboard objectives add bench dummy")
            repeat(scale) { index ->
                sandbox.executeCommand("scoreboard players set #bench_$index bench $index")
            }
            mapOf(
                "commands" to scale + 1,
                "scores" to scale,
                "snapshotBytes" to sandbox.snapshotString().toByteArray(StandardCharsets.UTF_8).size,
            )
        }

    private fun runStorageBenchmark(): BenchmarkScenario =
        timed("storage") {
            val sandbox = createBenchmarkSandbox()
            val payload = (0 until scale).joinToString(prefix = "{", postfix = "}") { index -> "v$index:$index" }
            sandbox.executeCommand("data merge storage benchmark:large $payload")
            mapOf(
                "commands" to 1,
                "entries" to scale,
                "snapshotBytes" to sandbox.snapshotString().toByteArray(StandardCharsets.UTF_8).size,
            )
        }

    private fun runFunctionChainBenchmark(): BenchmarkScenario =
        timed("function-chain") {
            val depth = minOf(scale, 48)
            val sources =
                buildList {
                    add(FunctionSource.text("bench:main", "scoreboard objectives add bench dummy\nfunction bench:f0", "<benchmark:main>"))
                    repeat(depth) { index ->
                        val next =
                            if (index == depth - 1) {
                                "scoreboard players add #chain bench 1"
                            } else {
                                "function bench:f${index + 1}"
                            }
                        add(FunctionSource.text("bench:f$index", next, "<benchmark:f$index>"))
                    }
                }
            val sandbox = createFunctionSandbox(version, packs, sources)
            val executed = sandbox.runFunction("bench:main").commandsExecuted
            mapOf(
                "depth" to depth,
                "commands" to executed,
                "score" to sandbox.world.getScore("#chain", "bench"),
            )
        }

    private fun runManifestBatchBenchmark(): BenchmarkScenario =
        timed("manifest-batch") {
            val dir = Files.createTempDirectory("dps-benchmark-manifest")
            val pack = dir.resolve("pack")
            Files.createDirectories(pack)
            Files.writeString(
                pack.resolve("pack.mcmeta"),
                """{"pack":{"pack_format":${VersionProfiles.get(version).dataPackFormat},"description":"Benchmark manifest pack"}}""",
                StandardCharsets.UTF_8,
            )
            val commands =
                buildString {
                    append("scoreboard objectives add bench dummy")
                    repeat(scale) { index ->
                        append("\nscoreboard players set #manifest_$index bench $index")
                    }
                }
            val manifest = dir.resolve("benchmark.dps.json")
            val root =
                JsonObject().also { json ->
                    json.addProperty("version", version)
                    json.add(
                        "packs",
                        JsonArray().also { packArray -> packArray.add(pack.toString()) },
                    )
                    json.add(
                        "steps",
                        JsonArray().also { steps ->
                            steps.add(
                                JsonObject().also { step ->
                                    step.addProperty("functionText", commands)
                                    step.addProperty("source", "<benchmark:manifest>")
                                },
                            )
                        },
                    )
                    json.add(
                        "assertions",
                        JsonArray().also { assertions ->
                            assertions.add(
                                JsonObject().also { assertion ->
                                    assertion.add(
                                        "score",
                                        JsonObject().also { score ->
                                            score.addProperty("target", "#manifest_${scale - 1}")
                                            score.addProperty("objective", "bench")
                                            score.addProperty("equals", scale - 1)
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            Files.writeString(manifest, JsonValues.render(root), StandardCharsets.UTF_8)
            val result = ManifestRunner.run(manifest)
            if (!result.passed) {
                throw SandboxException(DiagnosticCode.ASSERTION_FAILED, "benchmark manifest failed: ${result.messages.joinToString("; ")}")
            }
            mapOf(
                "manifests" to 1,
                "commands" to scale + 1,
                "assertions" to 1,
            )
        }

    private fun runLootBenchmark(table: String): BenchmarkScenario =
        timed("loot-sampling") {
            val sandbox = createBenchmarkSandbox()
            var items = 0
            repeat(scale) { index ->
                items +=
                    sandbox
                        .generateLoot(
                            ResourceLocation.parse(table),
                            ResourceLocation.parse(lootContext),
                            seed = seed + index,
                        ).items.size
            }
            mapOf(
                "samples" to scale,
                "items" to items,
                "table" to table,
                "context" to lootContext,
            )
        }

    private fun createBenchmarkSandbox(): DatapackSandbox =
        if (packs.isEmpty()) {
            createFunctionSandbox(version, listOf(FunctionSource.text("bench:noop", "", "<benchmark:noop>")))
        } else {
            createSandbox(version, packs)
        }

    private fun timed(
        name: String,
        block: () -> Map<String, Any>,
    ): BenchmarkScenario {
        val start = System.nanoTime()
        val metrics = block()
        val elapsedNanos = System.nanoTime() - start
        return BenchmarkScenario(name, elapsedNanos, metrics)
    }

    private fun renderPlain(results: List<BenchmarkScenario>): String =
        buildString {
            appendLine("benchmark version=$version scale=$scale")
            results.forEach { result ->
                append(result.name)
                append(" elapsedMs=")
                append(result.elapsedMillisString())
                result.metrics.forEach { (key, value) -> append(" $key=$value") }
                appendLine()
            }
        }.trimEnd()

    private fun renderJson(results: List<BenchmarkScenario>): String =
        JsonValues.render(
            JsonObject().also { root ->
                root.addProperty("version", version)
                root.addProperty("scale", scale)
                root.add(
                    "scenarios",
                    JsonArray().also { array ->
                        results.forEach { result -> array.add(result.toJson()) }
                    },
                )
            },
        )

    private fun emit(content: String) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("benchmark output written: $outputPath"))
        }
    }

    private data class BenchmarkScenario(
        val name: String,
        val elapsedNanos: Long,
        val metrics: Map<String, Any>,
    ) {
        fun elapsedMillisString(): String = "%.3f".format(java.util.Locale.ROOT, elapsedNanos / 1_000_000.0)

        fun toJson(): JsonObject =
            JsonObject().also { json ->
                json.addProperty("name", name)
                json.addProperty("elapsedNanos", elapsedNanos)
                json.addProperty("elapsedMs", elapsedNanos / 1_000_000.0)
                metrics.forEach { (key, value) ->
                    when (value) {
                        is Int -> json.addProperty(key, value)
                        is Long -> json.addProperty(key, value)
                        is Double -> json.addProperty(key, value)
                        is Float -> json.addProperty(key, value)
                        is Boolean -> json.addProperty(key, value)
                        else -> json.addProperty(key, value.toString())
                    }
                }
            }
    }
}

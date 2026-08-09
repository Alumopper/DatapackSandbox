package moe.afox.dpsandbox.core

/**
 * Selection and threshold settings for datapack function coverage.
 *
 * Include and exclude entries are glob patterns matched against the complete
 * function resource id. `*` matches any number of characters and `?` matches
 * one character. When [includes] is empty, every loaded function is included.
 */
data class DatapackCoverageOptions(
    val minimumLinePercentage: Double? = null,
    val minimumFunctionPercentage: Double? = null,
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
) {
    init {
        validatePercentage("minimumLinePercentage", minimumLinePercentage)
        validatePercentage("minimumFunctionPercentage", minimumFunctionPercentage)
        require(includes.none(String::isBlank)) { "Coverage include patterns must not be blank" }
        require(excludes.none(String::isBlank)) { "Coverage exclude patterns must not be blank" }
    }
}

/** One executable `.mcfunction` line and its cumulative hit count. */
data class DatapackLineCoverage(
    val line: Int,
    val command: String,
    val hits: Long,
) {
    val covered: Boolean
        get() = hits > 0
}

/** Coverage details for one loaded datapack function. */
data class DatapackFunctionCoverage(
    val id: ResourceLocation,
    val file: String?,
    val invocations: Long,
    val lines: List<DatapackLineCoverage>,
) {
    val covered: Boolean
        get() = invocations > 0

    val coveredLines: Int
        get() = lines.count(DatapackLineCoverage::covered)

    val totalLines: Int
        get() = lines.size

    val linePercentage: Double
        get() = coveragePercentage(coveredLines, totalLines)
}

/** Deterministic coverage report for the selected loaded functions. */
data class DatapackCoverageReport(
    val functions: List<DatapackFunctionCoverage>,
) {
    val coveredLines: Int
        get() = functions.sumOf(DatapackFunctionCoverage::coveredLines)

    val totalLines: Int
        get() = functions.sumOf(DatapackFunctionCoverage::totalLines)

    val linePercentage: Double
        get() = coveragePercentage(coveredLines, totalLines)

    val coveredFunctions: Int
        get() = functions.count(DatapackFunctionCoverage::covered)

    val totalFunctions: Int
        get() = functions.size

    val functionPercentage: Double
        get() = coveragePercentage(coveredFunctions, totalFunctions)

    /** Returns stable failure messages for percentages below [options]. */
    fun thresholdFailures(options: DatapackCoverageOptions): List<String> =
        buildList {
            options.minimumLinePercentage?.let { minimum ->
                if (linePercentage < minimum) {
                    add(
                        "coverage line percentage ${formatCoveragePercentage(linePercentage)}% is below required " +
                            "${formatCoveragePercentage(minimum)}% ($coveredLines/$totalLines lines)",
                    )
                }
            }
            options.minimumFunctionPercentage?.let { minimum ->
                if (functionPercentage < minimum) {
                    add(
                        "coverage function percentage ${formatCoveragePercentage(functionPercentage)}% is below required " +
                            "${formatCoveragePercentage(minimum)}% ($coveredFunctions/$totalFunctions functions)",
                    )
                }
            }
        }
}

internal fun coveragePercentage(
    covered: Int,
    total: Int,
): Double = if (total == 0) 100.0 else covered.toDouble() * 100.0 / total

private fun validatePercentage(
    name: String,
    value: Double?,
) {
    require(value == null || (value.isFinite() && value in 0.0..100.0)) { "$name must be between 0 and 100" }
}

private fun formatCoveragePercentage(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)

internal fun coverageGlobMatches(
    pattern: String,
    value: String,
): Boolean {
    val regex =
        buildString {
            append('^')
            pattern.forEach { character ->
                when (character) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> append('\\').append(character)
                    else -> append(character)
                }
            }
            append('$')
        }
    return Regex(regex).matches(value)
}

internal class DatapackCoverageTracker(
    private val datapack: Datapack,
) {
    private val functionInvocationHits = linkedMapOf<ResourceLocation, Long>()
    private val functionLineHits = linkedMapOf<ResourceLocation, LongArray>()

    fun recordInvocation(id: ResourceLocation) {
        functionInvocationHits[id] = incrementHit(functionInvocationHits[id] ?: 0)
    }

    fun recordLine(
        id: ResourceLocation,
        index: Int,
        lineCount: Int,
    ) {
        val hits = functionLineHits.getOrPut(id) { LongArray(lineCount) }
        hits[index] = incrementHit(hits[index])
    }

    fun report(options: DatapackCoverageOptions): DatapackCoverageReport {
        val functions =
            datapack.functions
                .toSortedMap()
                .filterKeys { id ->
                    val value = id.toString()
                    (options.includes.isEmpty() || options.includes.any { coverageGlobMatches(it, value) }) &&
                        options.excludes.none { coverageGlobMatches(it, value) }
                }.map { (id, function) -> functionCoverage(id, function) }
        return DatapackCoverageReport(functions)
    }

    fun reset() {
        functionInvocationHits.clear()
        functionLineHits.clear()
    }

    private fun functionCoverage(
        id: ResourceLocation,
        function: DatapackFunction,
    ): DatapackFunctionCoverage {
        val hits = functionLineHits[id]
        val file =
            function.lines
                .firstOrNull()
                ?.location
                ?.file
                ?: datapack.resourceIndex.lastOrNull { it.active && it.type == "function" && it.id == id }?.file
        return DatapackFunctionCoverage(
            id = id,
            file = file,
            invocations = functionInvocationHits[id] ?: 0,
            lines =
                function.lines.mapIndexed { index, line ->
                    DatapackLineCoverage(
                        line = line.location.line ?: index + 1,
                        command = line.command,
                        hits = hits?.getOrNull(index) ?: 0,
                    )
                },
        )
    }

    private fun incrementHit(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1
}

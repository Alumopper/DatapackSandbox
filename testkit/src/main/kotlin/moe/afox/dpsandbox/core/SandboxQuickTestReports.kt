package moe.afox.dpsandbox.core

import com.google.gson.JsonElement

/**
 * Immutable result returned by a single quick-test scenario.
 *
 * The report is intentionally self-contained: callers can inspect assertion
 * failures, command output events, and the final deterministic sandbox snapshot
 * without holding on to the mutable [DatapackSandbox] instance.
 */
data class SandboxQuickTestReport(
    /** True when no fluent quick-test assertion has failed. */
    val passed: Boolean,
    /** Human-readable assertion failures collected by assertion methods. */
    val failures: List<String>,
    /** Output events recorded by commands such as `tellraw`, `say`, `title`, and warnings. */
    val outputs: List<OutputEvent>,
    /** Structured command trace events recorded during execution. */
    val traces: List<CommandTraceEvent>,
    /** Structured player event trace records captured during event dispatch. */
    val playerEventTraces: List<PlayerEventTraceEvent>,
    /** Stable JSON Pointer diffs from the scenario's initial state to the final snapshot. */
    val snapshotDiffs: List<SnapshotDiffEntry>,
    /** Loaded resource counts, overlays, and direct missing references for debug reports. */
    val resourceSummary: DatapackResourceSummary,
    /** Final deterministic world snapshot after all executed steps. */
    val snapshot: JsonElement,
)

/**
 * Assertion error thrown by [SandboxQuickTest.requirePassed].
 *
 * The full [report] is exposed so test frameworks can render the snapshot or
 * structured output events in custom failure output.
 */
class SandboxQuickTestAssertionError(
    val report: SandboxQuickTestReport,
) : AssertionError(formatQuickTestFailure(report))

/**
 * Aggregated result for a multi-version quick-test matrix.
 */
data class SandboxQuickTestMatrixReport(
    /** True only when every version-specific scenario passed. */
    val passed: Boolean,
    /** Assertion failures prefixed with their version id. */
    val failures: List<String>,
    /** Per-version quick-test reports keyed by Minecraft profile id. */
    val reports: Map<String, SandboxQuickTestReport>,
)

/**
 * Assertion error thrown by [SandboxQuickTestMatrix.requirePassed].
 */
class SandboxQuickTestMatrixAssertionError(
    val report: SandboxQuickTestMatrixReport,
) : AssertionError(formatMatrixQuickTestFailure(report))

private fun formatQuickTestFailure(report: SandboxQuickTestReport): String =
    buildList {
        addAll(report.failures)
        if (report.snapshotDiffs.isNotEmpty()) {
            add("snapshot diff:")
            add(SnapshotDiff.render(report.snapshotDiffs))
        }
        if (report.traces.isNotEmpty()) {
            add("trace summary:")
            report.traces.takeLast(5).forEach { trace ->
                val status = if (trace.success) "OK" else "ERR"
                val error = trace.errorCode?.let { " ${it.name}: ${trace.errorMessage}" }.orEmpty()
                add("[$status] ${trace.command} commands=${trace.commandsExecuted} outputs=${trace.outputs}$error")
            }
        }
    }.joinToString(separator = "\n")

private fun formatMatrixQuickTestFailure(report: SandboxQuickTestMatrixReport): String =
    buildList {
        addAll(report.failures)
        report.reports.filterValues { !it.passed }.forEach { (version, scenario) ->
            if (scenario.snapshotDiffs.isNotEmpty()) {
                add("[$version] snapshot diff:")
                add(SnapshotDiff.render(scenario.snapshotDiffs))
            }
            if (scenario.traces.isNotEmpty()) {
                add("[$version] trace summary:")
                scenario.traces.takeLast(5).forEach { trace ->
                    val status = if (trace.success) "OK" else "ERR"
                    val error = trace.errorCode?.let { " ${it.name}: ${trace.errorMessage}" }.orEmpty()
                    add("[$version][$status] ${trace.command} commands=${trace.commandsExecuted} outputs=${trace.outputs}$error")
                }
            }
        }
    }.joinToString(separator = "\n")

internal fun canonicalEquipmentSlot(raw: String): String? =
    when (raw) {
        "weapon.mainhand", "hotbar.selected" -> "weapon.mainhand"
        "weapon.offhand" -> "weapon.offhand"
        "armor.feet" -> "armor.feet"
        "armor.legs" -> "armor.legs"
        "armor.chest" -> "armor.chest"
        "armor.head" -> "armor.head"
        else -> null
    }

internal fun playerEventTraceExpectation(
    player: String?,
    type: String?,
    success: Boolean?,
    advancement: String?,
    criterion: String?,
    count: Int?,
    failedAdvancement: String?,
    failedCriterion: String?,
    failureContains: String?,
    item: String?,
    entity: String?,
    block: String?,
    blockX: Int?,
    blockY: Int?,
    blockZ: Int?,
    recipe: String?,
    fromDimension: String?,
    toDimension: String?,
    damageSource: String?,
    damageAmount: Double?,
    inputDevice: String?,
    inputCode: String?,
    inputAction: String?,
    target: String?,
    targetUuid: String?,
    interactionResponse: Boolean?,
): PlayerEventTraceExpectation =
    PlayerEventTraceExpectation(
        player = player,
        type = type,
        success = success,
        advancement = advancement?.let(ResourceLocation::parse),
        criterion = criterion,
        failedAdvancement = failedAdvancement?.let(ResourceLocation::parse),
        failedCriterion = failedCriterion,
        failureContains = failureContains,
        item = item?.let(ResourceLocation::parse),
        entity = entity?.let(ResourceLocation::parse),
        block = block?.let(ResourceLocation::parse),
        blockX = blockX,
        blockY = blockY,
        blockZ = blockZ,
        recipe = recipe?.let(ResourceLocation::parse),
        fromDimension = fromDimension?.let(ResourceLocation::parse),
        toDimension = toDimension?.let(ResourceLocation::parse),
        damageSource = damageSource?.let(ResourceLocation::parse),
        damageAmount = damageAmount,
        inputDevice = inputDevice,
        inputCode = inputCode,
        inputAction = inputAction,
        target = target,
        targetUuid = targetUuid,
        interactionResponse = interactionResponse,
        count = count,
    )

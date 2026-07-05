package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import java.nio.file.Path

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

/**
 * Fluent quick-test runner for executing the same scenario across multiple
 * Minecraft version profiles.
 *
 * Matrix operations are applied to every contained [SandboxQuickTest]. If a
 * runtime [SandboxException] occurs, the exception message is annotated with
 * the version and operation that failed.
 */
class SandboxQuickTestMatrix private constructor(
    private val scenarios: Map<String, SandboxQuickTest>,
) {
    /** Version profile ids included in this matrix. */
    val versions: List<String> = scenarios.keys.toList()

    /**
     * Returns the mutable scenario for [version].
     *
     * Use this when a matrix test needs version-specific setup or assertions.
     *
     * @throws SandboxException when the matrix does not contain [version].
     */
    fun scenario(version: String): SandboxQuickTest =
        scenarios[version]
            ?: throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "No scenario exists for version '$version'. Available versions: ${versions.joinToString()}",
                version = version,
            )

    /**
     * Runs all functions referenced by `#minecraft:load` in every scenario.
     *
     * @return this matrix for fluent chaining.
     */
    fun load(): SandboxQuickTestMatrix = apply {
        eachScenario("load") { it.load() }
    }

    /**
     * Advances every scenario by [count] ticks.
     *
     * Each tick runs due scheduled functions, tick-tag functions, and sandbox
     * player tick advancement triggers.
     *
     * @throws SandboxException when [count] is negative.
     * @return this matrix for fluent chaining.
     */
    fun ticks(count: Int): SandboxQuickTestMatrix = apply {
        eachScenario("ticks") { it.ticks(count) }
    }

    /**
     * Runs the loaded datapack function [id] in every scenario.
     *
     * @param id Function resource location, for example `demo:main`.
     * @return this matrix for fluent chaining.
     */
    fun function(id: String): SandboxQuickTestMatrix = apply {
        eachScenario("function $id") { it.function(id) }
    }

    /**
     * Executes one raw Minecraft command in every scenario.
     *
     * The command may include a leading slash; it is normalized by the runtime.
     *
     * @return this matrix for fluent chaining.
     */
    fun command(command: String): SandboxQuickTestMatrix = apply {
        eachScenario(command) { it.command(command) }
    }

    /**
     * Applies an in-memory world fixture to every scenario.
     *
     * The fixture is applied immediately and validated against each scenario's
     * active [VersionProfile].
     *
     * @return this matrix for fluent chaining.
     */
    fun world(configure: SandboxWorldSetup.() -> Unit): SandboxQuickTestMatrix = apply {
        eachScenario("world setup") { it.world(configure) }
    }

    /**
     * Applies a reusable [SandboxWorldSetup] to every scenario.
     *
     * @return this matrix for fluent chaining.
     */
    fun setupWorld(setup: SandboxWorldSetup): SandboxQuickTestMatrix = apply {
        eachScenario("world setup") { it.setupWorld(setup) }
    }

    /**
     * Imports selected chunks from a Java Edition save into every scenario.
     *
     * Only explicitly requested chunks are read. The import covers sparse block
     * states, block entities, and entity NBT depending on the include flags; it
     * does not load playerdata, lighting, POI, scheduled block ticks, or full
     * vanilla world lifecycle state.
     *
     * @param path Root directory of the Minecraft save.
     * @param chunks Chunk coordinates to import.
     * @param dimension Dimension id, for example `minecraft:overworld`.
     * @return this matrix for fluent chaining.
     */
    @JvmOverloads
    fun importSave(
        path: Path,
        chunks: Iterable<ChunkPos>,
        dimension: String = "minecraft:overworld",
        includeBlocks: Boolean = true,
        includeBlockEntities: Boolean = true,
        includeEntities: Boolean = true,
    ): SandboxQuickTestMatrix = apply {
        eachScenario("save import") { it.importSave(path, chunks, dimension, includeBlocks, includeBlockEntities, includeEntities) }
    }

    /**
     * Creates or reuses a player in every scenario.
     *
     * @param name Player name to create; defaults to `Steve` for Java callers.
     * @return this matrix for fluent chaining.
     */
    @JvmOverloads
    fun player(name: String = "Steve"): SandboxQuickTestMatrix = apply {
        eachScenario("player $name") { it.player(name) }
    }

    /**
     * Injects a keyboard input event for [playerName] in every scenario.
     *
     * Keyboard events are sandbox-visible input records and may drive custom
     * advancement triggers; they do not simulate real client movement.
     *
     * @return this matrix for fluent chaining.
     */
    @JvmOverloads
    fun keyInput(playerName: String, key: String, action: String = "press"): SandboxQuickTestMatrix = apply {
        eachScenario("key input $playerName $key") { it.keyInput(playerName, key, action) }
    }

    /**
     * Injects a mouse input event for [playerName] in every scenario.
     *
     * Optional [x] and [y] values are recorded as input metadata only.
     *
     * @return this matrix for fluent chaining.
     */
    @JvmOverloads
    fun mouseInput(playerName: String, button: String, action: String = "click", x: Double? = null, y: Double? = null): SandboxQuickTestMatrix = apply {
        eachScenario("mouse input $playerName $button") { it.mouseInput(playerName, button, action, x, y) }
    }

    /**
     * Adds a scoreboard assertion to every scenario.
     *
     * The assertion is evaluated immediately against each current world state.
     */
    fun assertScore(target: String, objective: String, expected: Int): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertScore(target, objective, expected) }
    }

    /**
     * Adds a lower-bound scoreboard assertion to every scenario.
     */
    fun assertScoreAtLeast(target: String, objective: String, minimum: Int): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertScoreAtLeast(target, objective, minimum) }
    }

    /**
     * Adds an upper-bound scoreboard assertion to every scenario.
     */
    fun assertScoreAtMost(target: String, objective: String, maximum: Int): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertScoreAtMost(target, objective, maximum) }
    }

    /**
     * Adds optional lower and upper scoreboard bounds to every scenario.
     */
    @JvmOverloads
    fun assertScoreRange(target: String, objective: String, min: Int? = null, max: Int? = null): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertScoreRange(target, objective, min, max) }
    }

    /**
     * Adds a storage equality assertion to every scenario.
     */
    fun assertStorageEquals(id: String, path: String?, expectedJson: String): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertStorageEquals(id, path, expectedJson) }
    }

    /**
     * Adds a storage existence assertion to every scenario.
     */
    @JvmOverloads
    fun assertStorageExists(id: String, path: String? = null): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertStorageExists(id, path) }
    }

    /**
     * Adds a storage missing assertion to every scenario.
     */
    @JvmOverloads
    fun assertStorageMissing(id: String, path: String? = null): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertStorageMissing(id, path) }
    }

    /**
     * Applies a world-level state assertion to every scenario.
     */
    @JvmOverloads
    fun assertWorld(
        gameTime: Long? = null,
        dayTime: Long? = null,
        weather: String? = null,
        difficulty: String? = null,
        defaultGameMode: String? = null,
        seed: Long? = null,
    ): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertWorld(gameTime, dayTime, weather, difficulty, defaultGameMode, seed) }
    }

    /**
     * Applies a player state assertion to every scenario.
     */
    fun assertPlayer(
        name: String,
        exists: Boolean = true,
        position: Position? = null,
        dimension: String? = null,
        gameMode: String? = null,
        xp: Int? = null,
        health: Double? = null,
        food: Int? = null,
        selectedSlot: Int? = null,
        inventoryCount: Int? = null,
        recipe: String? = null,
        effect: String? = null,
        stat: String? = null,
        statValue: Int? = null,
    ): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach {
            it.assertPlayer(name, exists, position, dimension, gameMode, xp, health, food, selectedSlot, inventoryCount, recipe, effect, stat, statValue)
        }
    }

    /**
     * Applies a sparse-world block assertion to every scenario.
     */
    @JvmOverloads
    fun assertBlock(x: Int, y: Int, z: Int, id: String? = null, exists: Boolean = true): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertBlock(x, y, z, id, exists) }
    }

    /**
     * Applies an entity existence/count assertion to every scenario.
     */
    fun assertEntity(
        type: String? = null,
        tag: String? = null,
        uuid: String? = null,
        position: Position? = null,
        exists: Boolean = true,
        count: Int? = null,
    ): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertEntity(type, tag, uuid, position, exists, count) }
    }

    /**
     * Applies an entity count assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityCount(expected: Int, type: String? = null, tag: String? = null): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertEntityCount(expected, type, tag) }
    }

    /**
     * Applies a lower-bound entity count assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityCountAtLeast(minimum: Int, type: String? = null, tag: String? = null): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertEntityCountAtLeast(minimum, type, tag) }
    }

    /**
     * Applies an upper-bound entity count assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityCountAtMost(maximum: Int, type: String? = null, tag: String? = null): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertEntityCountAtMost(maximum, type, tag) }
    }

    /**
     * Applies optional lower and upper entity count bounds to every scenario.
     */
    @JvmOverloads
    fun assertEntityCountRange(min: Int? = null, max: Int? = null, type: String? = null, tag: String? = null): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertEntityCountRange(min, max, type, tag) }
    }

    /**
     * Applies an inventory item assertion to every scenario.
     */
    @JvmOverloads
    fun assertItem(playerName: String, id: String? = null, count: Int? = null, slot: Int? = null, exists: Boolean = true): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertItem(playerName, id, count, slot, exists) }
    }

    /**
     * Asserts that every scenario recorded at least one output event containing [text].
     */
    fun assertOutputContains(text: String): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertOutputContains(text) }
    }

    /**
     * Applies a structured output assertion to every scenario.
     */
    fun assertOutput(expectation: OutputExpectation): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertOutput(expectation) }
    }

    /**
     * Builds and applies a structured output assertion to every scenario.
     *
     * Null parameters are treated as wildcards. [count], when provided, requires
     * exactly that many matching output events; otherwise at least one match is
     * required.
     */
    @JvmOverloads
    fun assertOutput(
        command: String? = null,
        channel: String? = null,
        target: String? = null,
        text: String? = null,
        contains: String? = null,
        count: Int? = null,
        order: Int? = null,
        normalizedText: String? = null,
        normalizedContains: String? = null,
    ): SandboxQuickTestMatrix =
        assertOutput(
            OutputExpectation(
                command = command,
                channel = channel,
                target = target,
                text = text,
                contains = contains,
                count = count,
                order = order,
                normalizedText = normalizedText,
                normalizedContains = normalizedContains,
            ),
        )

    /**
     * Applies a structured trace assertion to every scenario.
     */
    fun assertTrace(expectation: TraceExpectation): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertTrace(expectation) }
    }

    /**
     * Builds and applies a structured trace assertion to every scenario.
     */
    @JvmOverloads
    fun assertTrace(
        command: String? = null,
        root: String? = null,
        contains: String? = null,
        success: Boolean? = null,
        fileContains: String? = null,
        function: String? = null,
        count: Int? = null,
    ): SandboxQuickTestMatrix =
        assertTrace(
            TraceExpectation(
                command = command,
                root = root,
                contains = contains,
                success = success,
                fileContains = fileContains,
                function = function,
                count = count,
            ),
        )

    /**
     * Applies a structured player event trace assertion to every scenario.
     */
    fun assertPlayerEventTrace(expectation: PlayerEventTraceExpectation): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertPlayerEventTrace(expectation) }
    }

    /**
     * Builds and applies a player event trace assertion to every scenario.
     */
    @JvmOverloads
    fun assertPlayerEventTrace(
        player: String? = null,
        type: String? = null,
        success: Boolean? = null,
        advancement: String? = null,
        criterion: String? = null,
        count: Int? = null,
    ): SandboxQuickTestMatrix =
        assertPlayerEventTrace(
            PlayerEventTraceExpectation(
                player = player,
                type = type,
                success = success,
                advancement = advancement?.let(ResourceLocation::parse),
                criterion = criterion,
                count = count,
            ),
        )

    /**
     * Asserts a before/after snapshot diff entry in every scenario.
     */
    @JvmOverloads
    fun assertSnapshotDiff(path: String? = null, kind: SnapshotDiffKind? = null, contains: String? = null, count: Int? = null): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertSnapshotDiff(path, kind, contains, count) }
    }

    private fun eachScenario(operation: String, block: (SandboxQuickTest) -> Unit) {
        scenarios.forEach { (version, scenario) ->
            try {
                block(scenario)
            } catch (error: SandboxException) {
                throw SandboxException(
                    code = error.code,
                    message = "Version $version failed during $operation: ${error.message}",
                    location = error.location,
                    version = error.version ?: version,
                    command = error.command,
                    cause = error,
                )
            }
        }
    }

    /**
     * Builds an immutable report for the current state of all scenarios.
     */
    fun report(): SandboxQuickTestMatrixReport {
        val reports = scenarios.mapValues { (_, scenario) -> scenario.report() }
        val failures = reports.flatMap { (version, report) ->
            report.failures.map { "[$version] $it" }
        }
        return SandboxQuickTestMatrixReport(
            passed = reports.values.all { it.passed },
            failures = failures,
            reports = reports,
        )
    }

    /**
     * Returns [report] when every scenario passed; otherwise throws
     * [SandboxQuickTestMatrixAssertionError].
     */
    fun requirePassed(): SandboxQuickTestMatrixReport {
        val report = report()
        if (!report.passed) throw SandboxQuickTestMatrixAssertionError(report)
        return report
    }

    companion object {
        /**
         * Creates a multi-version quick-test matrix.
         *
         * @param packsByVersion Map from Minecraft profile id to datapack paths
         * loaded for that profile. Each version may use a different datapack
         * directory or `pack_format`.
         * @param defaultPlayerName Name of the default player created in each
         * scenario, or `null` to start without an implicit player.
         * @param unsupportedFeatureMode Policy for vanilla commands recognized
         * by the profile but not implemented by the sandbox.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            packsByVersion: Map<String, List<Path>>,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        ): SandboxQuickTestMatrix {
            if (packsByVersion.isEmpty()) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "At least one version entry is required for a quick-test matrix")
            }
            return SandboxQuickTestMatrix(
                packsByVersion.toSortedMap().mapValues { (version, packs) ->
                    SandboxQuickTest.create(
                        packs = packs,
                        version = version,
                        defaultPlayerName = defaultPlayerName,
                        unsupportedFeatureMode = unsupportedFeatureMode,
                    )
                },
            )
        }
    }
}

/**
 * Fluent API for a single datapack sandbox test.
 *
 * Methods mutate the underlying [sandbox] immediately and return `this` for
 * chaining. Assertion methods collect failures instead of throwing; call
 * [requirePassed] to integrate with unit-test frameworks.
 */
class SandboxQuickTest private constructor(
    /** Mutable runtime used by this quick-test scenario. */
    val sandbox: DatapackSandbox,
    private val defaultFunctionId: ResourceLocation? = null,
) {
    private val failures = mutableListOf<String>()
    private val initialSnapshot: JsonElement = sandbox.snapshotJson()

    /**
     * Runs all functions referenced by `#minecraft:load`.
     *
     * @return this scenario for fluent chaining.
     */
    fun load(): SandboxQuickTest = apply {
        sandbox.runLoad()
    }

    /**
     * Advances the runtime by [count] ticks.
     *
     * @throws SandboxException when [count] is negative.
     * @return this scenario for fluent chaining.
     */
    fun ticks(count: Int): SandboxQuickTest = apply {
        sandbox.runTicks(count)
    }

    /**
     * Runs a loaded function by resource location.
     *
     * @param id Function id such as `demo:main`.
     * @return this scenario for fluent chaining.
     */
    fun function(id: String): SandboxQuickTest = apply {
        sandbox.runFunction(id)
    }

    /**
     * Runs the default single-file function configured by [singleFunction].
     *
     * @throws SandboxException when this scenario was not created from a single
     * `.mcfunction` file.
     * @return this scenario for fluent chaining.
     */
    fun function(): SandboxQuickTest = apply {
        val id = defaultFunctionId
            ?: throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "No default function is configured; call function(id) or create the scenario with singleFunction(...)",
                version = sandbox.profile.id,
            )
        sandbox.runFunction(id)
    }

    /**
     * Executes one raw Minecraft command.
     *
     * The command may include a leading slash. Unsupported vanilla commands
     * follow the scenario's [UnsupportedFeatureMode].
     *
     * @return this scenario for fluent chaining.
     */
    fun command(command: String): SandboxQuickTest = apply {
        sandbox.executeCommand(command)
    }

    /**
     * Applies an inline world fixture before or between behavior steps.
     *
     * NBT in the fixture is validated against the active [VersionProfile].
     *
     * @return this scenario for fluent chaining.
     */
    fun world(configure: SandboxWorldSetup.() -> Unit): SandboxQuickTest = apply {
        setupWorld(SandboxWorldSetup().apply(configure))
    }

    /**
     * Applies a reusable world setup object to this scenario.
     *
     * @return this scenario for fluent chaining.
     */
    fun setupWorld(setup: SandboxWorldSetup): SandboxQuickTest = apply {
        sandbox.world.applySetup(setup, sandbox.profile)
    }

    /**
     * Imports selected chunks from an existing Java Edition save into this scenario.
     *
     * The import is intentionally scoped to explicit chunks and optional content
     * groups. It is suitable for deterministic fixtures, not for loading a full
     * vanilla world.
     *
     * @return this scenario for fluent chaining.
     */
    @JvmOverloads
    fun importSave(
        path: Path,
        chunks: Iterable<ChunkPos>,
        dimension: String = "minecraft:overworld",
        includeBlocks: Boolean = true,
        includeBlockEntities: Boolean = true,
        includeEntities: Boolean = true,
    ): SandboxQuickTest =
        setupWorld(
            SandboxWorldSetup().importSave(
                path = path,
                chunks = chunks,
                dimension = dimension,
                includeBlocks = includeBlocks,
                includeBlockEntities = includeBlockEntities,
                includeEntities = includeEntities,
            ),
        )

    /**
     * Creates or reuses a sandbox player.
     *
     * @return this scenario for fluent chaining.
     */
    @JvmOverloads
    fun player(name: String = "Steve"): SandboxQuickTest = apply {
        sandbox.createPlayer(name)
    }

    /**
     * Dispatches a fully constructed player event.
     *
     * Events are visible to advancement triggers, predicates, and snapshots.
     *
     * @return this scenario for fluent chaining.
     */
    fun event(event: PlayerEvent): SandboxQuickTest = apply {
        sandbox.handlePlayerEvent(event)
    }

    /**
     * Creates and dispatches a shorthand player event.
     *
     * @param type Event type, accepting either hyphen or underscore naming.
     * @param id Optional resource id interpreted by event type, such as an item
     * id for `item_used`, an entity id for `killed_entity`, or a damage source
     * id for `damage`/`death`.
     * @param action Optional input action for keyboard/mouse events.
     * @return this scenario for fluent chaining.
     */
    @JvmOverloads
    fun event(playerName: String, type: String, id: String? = null, action: String? = null): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.shorthand(playerName, type, id, action))
    }

    /**
     * Records a keyboard input event for [playerName].
     *
     * @return this scenario for fluent chaining.
     */
    @JvmOverloads
    fun keyInput(playerName: String, key: String, action: String = "press"): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.keyInput(playerName, key, action))
    }

    /**
     * Records a mouse input event for [playerName].
     *
     * @return this scenario for fluent chaining.
     */
    @JvmOverloads
    fun mouseInput(playerName: String, button: String, action: String = "click", x: Double? = null, y: Double? = null): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.mouseInput(playerName, button, action, x, y))
    }

    /**
     * Asserts the current scoreboard value for [target] and [objective].
     *
     * The failure is collected and reported later by [report] or [requirePassed].
     */
    fun assertScore(target: String, objective: String, expected: Int): SandboxQuickTest = apply {
        val actual = sandbox.world.getScore(target, objective)
        if (actual != expected) {
            failures += "score $target $objective expected $expected but was $actual"
        }
    }

    /**
     * Asserts that the current scoreboard value is at least [minimum].
     */
    fun assertScoreAtLeast(target: String, objective: String, minimum: Int): SandboxQuickTest = apply {
        assertScoreBounds(target, objective, min = minimum, max = null)
    }

    /**
     * Asserts that the current scoreboard value is at most [maximum].
     */
    fun assertScoreAtMost(target: String, objective: String, maximum: Int): SandboxQuickTest = apply {
        assertScoreBounds(target, objective, min = null, max = maximum)
    }

    /**
     * Asserts optional lower and upper bounds for a scoreboard value.
     */
    @JvmOverloads
    fun assertScoreRange(target: String, objective: String, min: Int? = null, max: Int? = null): SandboxQuickTest = apply {
        assertScoreBounds(target, objective, min, max)
    }

    private fun assertScoreBounds(target: String, objective: String, min: Int?, max: Int?) {
        val actual = sandbox.world.getScore(target, objective)
        var checked = false
        min?.let {
            checked = true
            if (actual < it) {
                failures += "score $target $objective expected >= $it but was $actual"
            }
        }
        max?.let {
            checked = true
            if (actual > it) {
                failures += "score $target $objective expected <= $it but was $actual"
            }
        }
        if (!checked) {
            failures += "score $target $objective range assertion requires min or max"
        }
    }

    /**
     * Asserts that a storage root or path equals [expectedJson].
     *
     * @param id Storage id such as `demo:state`.
     * @param path Optional data path. Use `null` to compare the whole storage object.
     * @param expectedJson JSON/SNBT-lite value rendered in the syntax accepted by [JsonValues.parse].
     */
    fun assertStorageEquals(id: String, path: String?, expectedJson: String): SandboxQuickTest = apply {
        val storageId = ResourceLocation.parse(id)
        val expected = JsonValues.parse(expectedJson)
        val actual = storageValue(storageId, path)
        if (actual != expected) {
            failures += "${storageLabel(storageId, path)} expected ${JsonValues.render(expected)} but was ${actual?.let(JsonValues::render) ?: "<missing>"}"
        }
    }

    /**
     * Asserts that a storage root or path exists.
     */
    @JvmOverloads
    fun assertStorageExists(id: String, path: String? = null): SandboxQuickTest = apply {
        val storageId = ResourceLocation.parse(id)
        if (storageValue(storageId, path) == null) {
            failures += "${storageLabel(storageId, path)} expected present but was <missing>"
        }
    }

    /**
     * Asserts that a storage root or path is absent.
     */
    @JvmOverloads
    fun assertStorageMissing(id: String, path: String? = null): SandboxQuickTest = apply {
        val storageId = ResourceLocation.parse(id)
        val actual = storageValue(storageId, path)
        if (actual != null) {
            failures += "${storageLabel(storageId, path)} expected missing but was ${JsonValues.render(actual)}"
        }
    }

    private fun storageValue(id: ResourceLocation, path: String?): JsonElement? =
        sandbox.world.storages[id]?.let { JsonPaths.get(it, path) }

    private fun storageLabel(id: ResourceLocation, path: String?): String =
        "storage $id ${path ?: "<root>"}"

    /**
     * Asserts the current XP integer stored on a player.
     */
    fun assertPlayerXp(playerName: String, expected: Int): SandboxQuickTest = apply {
        val actual = sandbox.world.requirePlayer(playerName).xp
        if (actual != expected) {
            failures += "player $playerName xp expected $expected but was $actual"
        }
    }

    /**
     * Asserts the latest recorded keyboard or mouse input for a player.
     */
    fun assertPlayerLastInput(playerName: String, device: String, code: String, action: String): SandboxQuickTest = apply {
        val actual = sandbox.world.requirePlayer(playerName).lastInput
        if (actual == null) {
            failures += "player $playerName last input expected $device:$code/$action but was <none>"
        } else if (actual.device != device || actual.code != code || actual.action != action) {
            failures += "player $playerName last input expected $device:$code/$action but was ${actual.device}:${actual.code}/${actual.action}"
        }
    }

    /**
     * Asserts selected player state.
     *
     * Null parameters are ignored. When [stat] is set without [statValue], the
     * assertion only requires that the stat exists.
     */
    fun assertPlayer(
        name: String,
        exists: Boolean = true,
        position: Position? = null,
        dimension: String? = null,
        gameMode: String? = null,
        xp: Int? = null,
        health: Double? = null,
        food: Int? = null,
        selectedSlot: Int? = null,
        inventoryCount: Int? = null,
        recipe: String? = null,
        effect: String? = null,
        stat: String? = null,
        statValue: Int? = null,
    ): SandboxQuickTest = apply {
        val player = sandbox.world.players[name]
        if (!exists) {
            if (player != null) failures += "player $name expected missing but exists"
            return@apply
        }
        if (player == null) {
            failures += "player $name expected to exist"
            return@apply
        }

        position?.let { if (player.position != it) failures += "player $name position expected $it but was ${player.position}" }
        dimension?.let { if (player.dimension != ResourceLocation.parse(it)) failures += "player $name dimension expected $it but was ${player.dimension}" }
        gameMode?.let { if (player.gameMode != it) failures += "player $name gameMode expected $it but was ${player.gameMode}" }
        xp?.let { if (player.xp != it) failures += "player $name xp expected $it but was ${player.xp}" }
        health?.let { if (player.health != it) failures += "player $name health expected $it but was ${player.health}" }
        food?.let { if (player.food != it) failures += "player $name food expected $it but was ${player.food}" }
        selectedSlot?.let { if (player.selectedSlot != it) failures += "player $name selectedSlot expected $it but was ${player.selectedSlot}" }
        inventoryCount?.let { if (player.inventory.size != it) failures += "player $name inventoryCount expected $it but was ${player.inventory.size}" }
        recipe?.let {
            val id = ResourceLocation.parse(it)
            if (id !in player.recipes) failures += "player $name expected recipe $id"
        }
        effect?.let {
            val id = ResourceLocation.parse(it)
            if (id !in player.effects) failures += "player $name expected effect $id"
        }
        stat?.let {
            val id = ResourceLocation.parse(it)
            val actualValue = player.stats[id]
            if (statValue == null) {
                if (actualValue == null) failures += "player $name expected stat $id"
            } else if ((actualValue ?: 0) != statValue) {
                failures += "player $name stat $id expected $statValue but was ${actualValue ?: 0}"
            }
        }
    }

    /**
     * Asserts selected world-level state.
     *
     * Null parameters are ignored.
     */
    @JvmOverloads
    fun assertWorld(
        gameTime: Long? = null,
        dayTime: Long? = null,
        weather: String? = null,
        difficulty: String? = null,
        defaultGameMode: String? = null,
        seed: Long? = null,
    ): SandboxQuickTest = apply {
        gameTime?.let { if (sandbox.world.gameTime != it) failures += "world gameTime expected $it but was ${sandbox.world.gameTime}" }
        dayTime?.let { if (sandbox.world.dayTime != it) failures += "world dayTime expected $it but was ${sandbox.world.dayTime}" }
        weather?.let { if (sandbox.world.weather != it) failures += "world weather expected $it but was ${sandbox.world.weather}" }
        difficulty?.let { if (sandbox.world.difficulty != it) failures += "world difficulty expected $it but was ${sandbox.world.difficulty}" }
        defaultGameMode?.let { if (sandbox.world.defaultGameMode != it) failures += "world defaultGameMode expected $it but was ${sandbox.world.defaultGameMode}" }
        seed?.let { if (sandbox.world.seed != it) failures += "world seed expected $it but was ${sandbox.world.seed}" }
    }

    /**
     * Asserts an explicit sparse-world block.
     */
    @JvmOverloads
    fun assertBlock(x: Int, y: Int, z: Int, id: String? = null, exists: Boolean = true): SandboxQuickTest = apply {
        val pos = BlockPos(x, y, z)
        val actual = sandbox.world.block(pos)
        if ((actual != null) != exists) {
            failures += "block $pos exists expected $exists but was ${actual != null}"
        }
        id?.let {
            val expected = ResourceLocation.parse(it)
            if (actual?.id != expected) failures += "block $pos id expected $expected but was ${actual?.id ?: "void"}"
        }
    }

    /**
     * Asserts that at least one entity, no entity, or exactly [count] entities
     * match the optional filters.
     */
    fun assertEntity(
        type: String? = null,
        tag: String? = null,
        uuid: String? = null,
        position: Position? = null,
        exists: Boolean = true,
        count: Int? = null,
    ): SandboxQuickTest = apply {
        val expectedType = type?.let(ResourceLocation::parse)
        val matches = sandbox.world.entities.filter { entity ->
            (expectedType == null || entity.type == expectedType) &&
                (tag == null || tag in entity.tags) &&
                (uuid == null || entity.uuid == uuid) &&
                (position == null || entity.position == position)
        }
        val description = describeEntityExpectation(type, tag, uuid, position)
        if (count != null) {
            if (matches.size != count) failures += "entity expected $count match(es) but found ${matches.size}: $description"
            return@apply
        }
        if (exists && matches.isEmpty()) {
            failures += "entity expected at least one match: $description"
        }
        if (!exists && matches.isNotEmpty()) {
            failures += "entity expected missing but found ${matches.size} match(es): $description"
        }
    }

    /**
     * Asserts the number of entities matching optional type and tag filters.
     */
    @JvmOverloads
    fun assertEntityCount(expected: Int, type: String? = null, tag: String? = null): SandboxQuickTest = apply {
        val actual = entityCount(type, tag)
        if (actual != expected) {
            failures += "entityCount ${describeEntityCount(type, tag)} expected $expected but was $actual"
        }
    }

    /**
     * Asserts that the number of matching entities is at least [minimum].
     */
    @JvmOverloads
    fun assertEntityCountAtLeast(minimum: Int, type: String? = null, tag: String? = null): SandboxQuickTest = apply {
        assertEntityCountBounds(min = minimum, max = null, type = type, tag = tag)
    }

    /**
     * Asserts that the number of matching entities is at most [maximum].
     */
    @JvmOverloads
    fun assertEntityCountAtMost(maximum: Int, type: String? = null, tag: String? = null): SandboxQuickTest = apply {
        assertEntityCountBounds(min = null, max = maximum, type = type, tag = tag)
    }

    /**
     * Asserts optional lower and upper bounds for the number of matching entities.
     */
    @JvmOverloads
    fun assertEntityCountRange(min: Int? = null, max: Int? = null, type: String? = null, tag: String? = null): SandboxQuickTest = apply {
        assertEntityCountBounds(min, max, type, tag)
    }

    private fun assertEntityCountBounds(min: Int?, max: Int?, type: String?, tag: String?) {
        val actual = entityCount(type, tag)
        val label = describeEntityCount(type, tag)
        var checked = false
        min?.let {
            checked = true
            if (actual < it) {
                failures += "entityCount $label expected >= $it but was $actual"
            }
        }
        max?.let {
            checked = true
            if (actual > it) {
                failures += "entityCount $label expected <= $it but was $actual"
            }
        }
        if (!checked) {
            failures += "entityCount $label range assertion requires min or max"
        }
    }

    private fun entityCount(type: String?, tag: String?): Int {
        val expectedType = type?.let(ResourceLocation::parse)
        return sandbox.world.entities.count { entity ->
            (expectedType == null || entity.type == expectedType) &&
                (tag == null || tag in entity.tags)
        }
    }

    private fun describeEntityCount(type: String?, tag: String?): String =
        listOfNotNull(
            type?.let { "type=$it" },
            tag?.let { "tag=$it" },
        ).ifEmpty { listOf("<any entity>") }.joinToString(", ")

    /**
     * Asserts that a player's inventory contains or does not contain a matching item.
     */
    @JvmOverloads
    fun assertItem(playerName: String, id: String? = null, count: Int? = null, slot: Int? = null, exists: Boolean = true): SandboxQuickTest = apply {
        val player = sandbox.world.requirePlayer(playerName)
        val expectedId = id?.let(ResourceLocation::parse)
        val candidates = slot?.let { player.inventory.getOrNull(it)?.let(::listOf) ?: emptyList() } ?: player.inventory
        val matches = candidates.filter { item ->
            (expectedId == null || item.id == expectedId) &&
                (count == null || item.count == count)
        }
        if (exists && matches.isEmpty()) {
            failures += "item for player $playerName expected ${id ?: "<any>"}${count?.let { " x$it" }.orEmpty()} but inventory was ${player.inventory.map { "${it.id}x${it.count}" }}"
        }
        if (!exists && matches.isNotEmpty()) {
            failures += "item for player $playerName expected missing ${id ?: "<any>"} but found ${matches.map { "${it.id}x${it.count}" }}"
        }
    }

    /**
     * Asserts whether an advancement is complete for a player.
     *
     * Completion is computed from the loaded advancement definition and the
     * player's current criterion progress.
     */
    @JvmOverloads
    fun assertAdvancementDone(playerName: String, id: String, expected: Boolean = true): SandboxQuickTest = apply {
        val player = sandbox.world.requirePlayer(playerName)
        val advancementId = ResourceLocation.parse(id)
        val advancement = sandbox.datapack.advancements[advancementId]
        val progress = player.advancementProgress[advancementId]
        val actual = advancement != null && progress?.isDone(advancement.requirements) == true
        if (actual != expected) {
            failures += "advancement $advancementId for $playerName done expected $expected but was $actual"
        }
    }

    /**
     * Asserts that at least one output event contains [text].
     */
    fun assertOutputContains(text: String): SandboxQuickTest = apply {
        assertOutput(OutputExpectation(contains = text))
    }

    /**
     * Applies a structured output assertion.
     */
    fun assertOutput(expectation: OutputExpectation): SandboxQuickTest = apply {
        failures += OutputAssertions.failures(sandbox.world.outputs, expectation)
    }

    /**
     * Builds and applies a structured output assertion.
     *
     * Null parameters are wildcards. When [count] is provided, exactly that many
     * events must match; otherwise at least one event must match.
     */
    @JvmOverloads
    fun assertOutput(
        command: String? = null,
        channel: String? = null,
        target: String? = null,
        text: String? = null,
        contains: String? = null,
        count: Int? = null,
        order: Int? = null,
        normalizedText: String? = null,
        normalizedContains: String? = null,
    ): SandboxQuickTest =
        assertOutput(
            OutputExpectation(
                command = command,
                channel = channel,
                target = target,
                text = text,
                contains = contains,
                count = count,
                order = order,
                normalizedText = normalizedText,
                normalizedContains = normalizedContains,
            ),
        )

    /**
     * Applies a structured trace assertion.
     */
    fun assertTrace(expectation: TraceExpectation): SandboxQuickTest = apply {
        failures += TraceAssertions.failures(sandbox.world.traces, expectation)
    }

    /**
     * Builds and applies a structured trace assertion.
     *
     * Null parameters are wildcards. When [count] is provided, exactly that many
     * events must match; otherwise at least one match is required.
     */
    @JvmOverloads
    fun assertTrace(
        command: String? = null,
        root: String? = null,
        contains: String? = null,
        success: Boolean? = null,
        fileContains: String? = null,
        function: String? = null,
        count: Int? = null,
    ): SandboxQuickTest =
        assertTrace(
            TraceExpectation(
                command = command,
                root = root,
                contains = contains,
                success = success,
                fileContains = fileContains,
                function = function,
                count = count,
            ),
        )

    /**
     * Applies a structured player event trace assertion.
     */
    fun assertPlayerEventTrace(expectation: PlayerEventTraceExpectation): SandboxQuickTest = apply {
        failures += PlayerEventTraceAssertions.failures(sandbox.world.playerEventTraces, expectation)
    }

    /**
     * Builds and applies a structured player event trace assertion.
     *
     * Null parameters are wildcards. When [count] is provided, exactly that many
     * event traces must match; otherwise at least one event trace must match.
     */
    @JvmOverloads
    fun assertPlayerEventTrace(
        player: String? = null,
        type: String? = null,
        success: Boolean? = null,
        advancement: String? = null,
        criterion: String? = null,
        count: Int? = null,
    ): SandboxQuickTest =
        assertPlayerEventTrace(
            PlayerEventTraceExpectation(
                player = player,
                type = type,
                success = success,
                advancement = advancement?.let(ResourceLocation::parse),
                criterion = criterion,
                count = count,
            ),
        )

    /**
     * Returns a defensive copy of all output events recorded so far.
     */
    fun outputs(): List<OutputEvent> =
        sandbox.world.outputs.toList()

    /**
     * Returns a defensive copy of all command trace events recorded so far.
     */
    fun traces(): List<CommandTraceEvent> =
        sandbox.world.traces.toList()

    /**
     * Returns a defensive copy of all player event trace records.
     */
    fun playerEventTraces(): List<PlayerEventTraceEvent> =
        sandbox.world.playerEventTraces.toList()

    /**
     * Returns player event trace records matching [expectation] without registering a failure.
     */
    fun matchingPlayerEventTraces(expectation: PlayerEventTraceExpectation): List<PlayerEventTraceEvent> =
        PlayerEventTraceAssertions.matching(sandbox.world.playerEventTraces, expectation)

    /**
     * Builds a player event trace expectation and returns matching records without registering a failure.
     */
    @JvmOverloads
    fun matchingPlayerEventTraces(
        player: String? = null,
        type: String? = null,
        success: Boolean? = null,
        advancement: String? = null,
        criterion: String? = null,
    ): List<PlayerEventTraceEvent> =
        matchingPlayerEventTraces(
            PlayerEventTraceExpectation(
                player = player,
                type = type,
                success = success,
                advancement = advancement?.let(ResourceLocation::parse),
                criterion = criterion,
            ),
        )

    /**
     * Returns command trace events matching [expectation] without registering a failure.
     */
    fun matchingTraces(expectation: TraceExpectation): List<CommandTraceEvent> =
        TraceAssertions.matching(sandbox.world.traces, expectation)

    /**
     * Builds a trace expectation and returns matching events without registering a failure.
     */
    @JvmOverloads
    fun matchingTraces(
        command: String? = null,
        root: String? = null,
        contains: String? = null,
        success: Boolean? = null,
        fileContains: String? = null,
        function: String? = null,
    ): List<CommandTraceEvent> =
        matchingTraces(
            TraceExpectation(
                command = command,
                root = root,
                contains = contains,
                success = success,
                fileContains = fileContains,
                function = function,
            ),
        )

    /**
     * Returns stable JSON Pointer diffs from the initial scenario state to now.
     */
    fun snapshotDiffs(): List<SnapshotDiffEntry> =
        SnapshotDiff.diff(initialSnapshot, sandbox.snapshotJson())

    /**
     * Asserts that the current scenario changed the initial snapshot as expected.
     *
     * Null parameters are wildcards. [contains] matches the rendered diff line.
     * When [count] is provided, exactly that many entries must match; otherwise
     * at least one match is required.
     */
    @JvmOverloads
    fun assertSnapshotDiff(path: String? = null, kind: SnapshotDiffKind? = null, contains: String? = null, count: Int? = null): SandboxQuickTest = apply {
        val matches = snapshotDiffs().filter { entry ->
            (path == null || entry.path.ifBlank { "/" } == path) &&
                (kind == null || entry.kind == kind) &&
                (contains == null || contains in entry.render())
        }
        if (count != null && matches.size != count) {
            failures += "snapshotDiff ${describeSnapshotDiffExpectation(path, kind, contains)} expected count $count but was ${matches.size}"
        }
        if (count == null && matches.isEmpty()) {
            failures += "snapshotDiff ${describeSnapshotDiffExpectation(path, kind, contains)} did not match any snapshot change"
        }
    }

    /**
     * Returns output events matching [expectation] without registering a failure.
     */
    fun matchingOutputs(expectation: OutputExpectation): List<OutputEvent> =
        OutputAssertions.matching(sandbox.world.outputs, expectation)

    /**
     * Builds an output expectation and returns matching events without registering a failure.
     */
    @JvmOverloads
    fun matchingOutputs(
        command: String? = null,
        channel: String? = null,
        target: String? = null,
        text: String? = null,
        contains: String? = null,
        normalizedText: String? = null,
        normalizedContains: String? = null,
    ): List<OutputEvent> =
        matchingOutputs(
            OutputExpectation(
                command = command,
                channel = channel,
                target = target,
                text = text,
                contains = contains,
                normalizedText = normalizedText,
                normalizedContains = normalizedContains,
            ),
        )

    private fun describeEntityExpectation(type: String?, tag: String?, uuid: String?, position: Position?): String =
        listOfNotNull(
            type?.let { "type=$it" },
            tag?.let { "tag=$it" },
            uuid?.let { "uuid=$it" },
            position?.let { "position=$it" },
        ).ifEmpty { listOf("<any entity>") }.joinToString(", ")

    private fun describeSnapshotDiffExpectation(path: String?, kind: SnapshotDiffKind?, contains: String?): String =
        listOfNotNull(
            path?.let { "path=$it" },
            kind?.let { "kind=${it.name.lowercase()}" },
            contains?.let { "contains=$it" },
        ).ifEmpty { listOf("<any snapshot diff>") }.joinToString(", ")

    /**
     * Builds an immutable report for the current scenario state.
     */
    fun report(): SandboxQuickTestReport =
        SandboxQuickTestReport(
            passed = failures.isEmpty(),
            failures = failures.toList(),
            outputs = sandbox.world.outputs.toList(),
            traces = sandbox.world.traces.toList(),
            playerEventTraces = sandbox.world.playerEventTraces.toList(),
            snapshotDiffs = snapshotDiffs(),
            snapshot = sandbox.snapshotJson(),
        )

    /**
     * Returns [report] when all collected assertions passed; otherwise throws
     * [SandboxQuickTestAssertionError].
     */
    fun requirePassed(): SandboxQuickTestReport {
        val report = report()
        if (!report.passed) throw SandboxQuickTestAssertionError(report)
        return report
    }

    companion object {
        /**
         * Creates a quick-test scenario from one or more datapack paths.
         *
         * @param packs Directories or zip files to load, in pack priority order.
         * @param version Minecraft version profile id.
         * @param defaultPlayerName Name of the initial player, or `null` to create no implicit player.
         * @param unsupportedFeatureMode Policy for supported-by-vanilla but unsupported-by-sandbox commands.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            packs: List<Path>,
            version: String = VersionProfiles.default.id,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        ): SandboxQuickTest =
            SandboxQuickTest(createSandbox(version, packs, defaultPlayerName = defaultPlayerName, unsupportedFeatureMode = unsupportedFeatureMode))

        /**
         * Creates a multi-version quick-test matrix.
         */
        @JvmStatic
        @JvmOverloads
        fun matrix(
            packsByVersion: Map<String, List<Path>>,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        ): SandboxQuickTestMatrix =
            SandboxQuickTestMatrix.create(packsByVersion, defaultPlayerName, unsupportedFeatureMode)

        /**
         * Creates a quick-test scenario backed by a single `.mcfunction` file.
         *
         * This is the lightest API path when a caller only wants to test one
         * command file and does not want to create a full datapack directory.
         *
         * @param functionFile Path to the `.mcfunction` file.
         * @param version Minecraft version profile id used for command parsing and NBT validation.
         * @param functionId Temporary function id assigned to the file.
         * @param dependencyPacks Datapack directories or zip files loaded before this function.
         */
        @JvmStatic
        @JvmOverloads
        fun singleFunction(
            functionFile: Path,
            version: String,
            functionId: String = SingleFunctionDatapack.DEFAULT_ID,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
            dependencyPacks: List<Path> = emptyList(),
        ): SandboxQuickTest {
            val id = ResourceLocation.parse(functionId)
            return functions(
                functionSources = listOf(FunctionSource.file(id.toString(), functionFile)),
                version = version,
                defaultFunctionId = id.toString(),
                defaultPlayerName = defaultPlayerName,
                unsupportedFeatureMode = unsupportedFeatureMode,
                dependencyPacks = dependencyPacks,
            )
        }

        /**
         * Creates a quick-test scenario backed by one in-memory `.mcfunction` string.
         *
         * This avoids creating temporary files when tests generate command text
         * dynamically.
         *
         * @param functionText Raw `.mcfunction` content.
         * @param version Minecraft version profile id used for command parsing and NBT validation.
         * @param functionId Temporary function id assigned to [functionText].
         * @param sourceName Label used in diagnostics.
         * @param dependencyPacks Datapack directories or zip files loaded before this function.
         */
        @JvmStatic
        @JvmOverloads
        fun singleFunctionText(
            functionText: String,
            version: String,
            functionId: String = SingleFunctionDatapack.DEFAULT_ID,
            sourceName: String = "<string:$functionId>",
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
            dependencyPacks: List<Path> = emptyList(),
        ): SandboxQuickTest =
            functions(
                functionSources = listOf(FunctionSource.text(functionId, functionText, sourceName)),
                version = version,
                defaultFunctionId = functionId,
                defaultPlayerName = defaultPlayerName,
                unsupportedFeatureMode = unsupportedFeatureMode,
                dependencyPacks = dependencyPacks,
            )

        /**
         * Creates a quick-test scenario backed by multiple synthetic function sources.
         *
         * Use [FunctionSource.file] and [FunctionSource.text] to mix file-backed
         * and in-memory functions without creating a full datapack directory.
         *
         * @param functionSources Functions to load into the synthetic datapack.
         * @param defaultFunctionId Function id used by [SandboxQuickTest.function].
         * @param dependencyPacks Datapack directories or zip files loaded before [functionSources].
         */
        @JvmStatic
        @JvmOverloads
        fun functions(
            functionSources: List<FunctionSource>,
            version: String,
            defaultFunctionId: String = SingleFunctionDatapack.DEFAULT_ID,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
            dependencyPacks: List<Path> = emptyList(),
        ): SandboxQuickTest {
            val id = ResourceLocation.parse(defaultFunctionId)
            return SandboxQuickTest(
                sandbox = if (dependencyPacks.isEmpty()) {
                    createFunctionSandbox(
                        version = version,
                        functionSources = functionSources,
                        defaultPlayerName = defaultPlayerName,
                        unsupportedFeatureMode = unsupportedFeatureMode,
                    )
                } else {
                    createFunctionSandbox(
                        version = version,
                        packs = dependencyPacks,
                        functionSources = functionSources,
                        defaultPlayerName = defaultPlayerName,
                        unsupportedFeatureMode = unsupportedFeatureMode,
                    )
                },
                defaultFunctionId = id,
            )
        }
    }
}

/**
 * Java-friendly facade for the quick-test API.
 *
 * Kotlin callers usually use [SandboxQuickTest] directly. Java callers can use
 * this object to avoid companion-object syntax and to access static overloads.
 */
object DatapackSandboxTestApi {
    /**
     * Creates a single quick-test scenario.
     */
    @JvmStatic
    @JvmOverloads
    fun scenario(
        packs: List<Path>,
        version: String = VersionProfiles.default.id,
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    ): SandboxQuickTest =
        SandboxQuickTest.create(packs, version, defaultPlayerName, unsupportedFeatureMode)

    /**
     * Creates a quick-test scenario for one `.mcfunction` file.
     */
    @JvmStatic
    @JvmOverloads
    fun singleFunctionScenario(
        functionFile: Path,
        version: String,
        functionId: String = SingleFunctionDatapack.DEFAULT_ID,
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        dependencyPacks: List<Path> = emptyList(),
    ): SandboxQuickTest =
        SandboxQuickTest.singleFunction(functionFile, version, functionId, defaultPlayerName, unsupportedFeatureMode, dependencyPacks)

    /**
     * Creates a quick-test scenario for one in-memory `.mcfunction` string.
     */
    @JvmStatic
    @JvmOverloads
    fun singleFunctionTextScenario(
        functionText: String,
        version: String,
        functionId: String = SingleFunctionDatapack.DEFAULT_ID,
        sourceName: String = "<string:$functionId>",
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        dependencyPacks: List<Path> = emptyList(),
    ): SandboxQuickTest =
        SandboxQuickTest.singleFunctionText(functionText, version, functionId, sourceName, defaultPlayerName, unsupportedFeatureMode, dependencyPacks)

    /**
     * Creates a quick-test scenario for multiple synthetic function sources.
     */
    @JvmStatic
    @JvmOverloads
    fun functionSourcesScenario(
        functionSources: List<FunctionSource>,
        version: String,
        defaultFunctionId: String = SingleFunctionDatapack.DEFAULT_ID,
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        dependencyPacks: List<Path> = emptyList(),
    ): SandboxQuickTest =
        SandboxQuickTest.functions(functionSources, version, defaultFunctionId, defaultPlayerName, unsupportedFeatureMode, dependencyPacks)

    /**
     * Creates a multi-version quick-test matrix.
     */
    @JvmStatic
    @JvmOverloads
    fun scenarioMatrix(
        packsByVersion: Map<String, List<Path>>,
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    ): SandboxQuickTestMatrix =
        SandboxQuickTest.matrix(packsByVersion, defaultPlayerName, unsupportedFeatureMode)

    /**
     * Runs a single `.mcfunction` file once and returns its report.
     */
    @JvmStatic
    @JvmOverloads
    fun runFunctionFile(
        functionFile: Path,
        version: String,
        functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    ): SandboxQuickTestReport =
        singleFunctionScenario(functionFile, version, functionId).function().report()

    /**
     * Runs one in-memory `.mcfunction` string once and returns its report.
     */
    @JvmStatic
    @JvmOverloads
    fun runFunctionText(
        functionText: String,
        version: String,
        functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    ): SandboxQuickTestReport =
        singleFunctionTextScenario(functionText, version, functionId).function().report()

    /**
     * Runs the default function from multiple synthetic function sources once.
     */
    @JvmStatic
    @JvmOverloads
    fun runFunctionSources(
        functionSources: List<FunctionSource>,
        version: String,
        defaultFunctionId: String = SingleFunctionDatapack.DEFAULT_ID,
        dependencyPacks: List<Path> = emptyList(),
    ): SandboxQuickTestReport =
        functionSourcesScenario(functionSources, version, defaultFunctionId, dependencyPacks = dependencyPacks).function().report()

    /**
     * Executes a list of raw commands against a newly created scenario.
     */
    @JvmStatic
    @JvmOverloads
    fun runCommands(
        packs: List<Path>,
        commands: List<String>,
        version: String = VersionProfiles.default.id,
    ): SandboxQuickTestReport {
        val scenario = scenario(packs, version)
        commands.forEach { scenario.command(it) }
        return scenario.report()
    }
}

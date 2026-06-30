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
) : AssertionError(report.failures.joinToString(separator = "\n"))

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
) : AssertionError(report.failures.joinToString(separator = "\n"))

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
    ): SandboxQuickTestMatrix =
        assertOutput(
            OutputExpectation(
                command = command,
                channel = channel,
                target = target,
                text = text,
                contains = contains,
                count = count,
            ),
        )

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
     * id for `item_used` or an entity id for `killed_entity`.
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
     * Asserts that a storage root or path equals [expectedJson].
     *
     * @param id Storage id such as `demo:state`.
     * @param path Optional data path. Use `null` to compare the whole storage object.
     * @param expectedJson JSON/SNBT-lite value rendered in the syntax accepted by [JsonValues.parse].
     */
    fun assertStorageEquals(id: String, path: String?, expectedJson: String): SandboxQuickTest = apply {
        val storageId = ResourceLocation.parse(id)
        val expected = JsonValues.parse(expectedJson)
        val actual = sandbox.world.storages[storageId]?.let { JsonPaths.get(it, path) }
        if (actual != expected) {
            failures += "storage $storageId ${path ?: "<root>"} expected ${JsonValues.render(expected)} but was ${actual?.let(JsonValues::render) ?: "<missing>"}"
        }
    }

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
    ): SandboxQuickTest =
        assertOutput(
            OutputExpectation(
                command = command,
                channel = channel,
                target = target,
                text = text,
                contains = contains,
                count = count,
            ),
        )

    /**
     * Returns a defensive copy of all output events recorded so far.
     */
    fun outputs(): List<OutputEvent> =
        sandbox.world.outputs.toList()

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
    ): List<OutputEvent> =
        matchingOutputs(
            OutputExpectation(
                command = command,
                channel = channel,
                target = target,
                text = text,
                contains = contains,
            ),
        )

    /**
     * Builds an immutable report for the current scenario state.
     */
    fun report(): SandboxQuickTestReport =
        SandboxQuickTestReport(
            passed = failures.isEmpty(),
            failures = failures.toList(),
            outputs = sandbox.world.outputs.toList(),
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

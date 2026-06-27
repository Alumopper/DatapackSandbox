package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import java.nio.file.Path

data class SandboxQuickTestReport(
    val passed: Boolean,
    val failures: List<String>,
    val outputs: List<OutputEvent>,
    val snapshot: JsonElement,
)

class SandboxQuickTestAssertionError(
    val report: SandboxQuickTestReport,
) : AssertionError(report.failures.joinToString(separator = "\n"))

data class SandboxQuickTestMatrixReport(
    val passed: Boolean,
    val failures: List<String>,
    val reports: Map<String, SandboxQuickTestReport>,
)

class SandboxQuickTestMatrixAssertionError(
    val report: SandboxQuickTestMatrixReport,
) : AssertionError(report.failures.joinToString(separator = "\n"))

class SandboxQuickTestMatrix private constructor(
    private val scenarios: Map<String, SandboxQuickTest>,
) {
    val versions: List<String> = scenarios.keys.toList()

    fun scenario(version: String): SandboxQuickTest =
        scenarios[version]
            ?: throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "No scenario exists for version '$version'. Available versions: ${versions.joinToString()}",
                version = version,
            )

    fun load(): SandboxQuickTestMatrix = apply {
        eachScenario("load") { it.load() }
    }

    fun ticks(count: Int): SandboxQuickTestMatrix = apply {
        eachScenario("ticks") { it.ticks(count) }
    }

    fun function(id: String): SandboxQuickTestMatrix = apply {
        eachScenario("function $id") { it.function(id) }
    }

    fun command(command: String): SandboxQuickTestMatrix = apply {
        eachScenario(command) { it.command(command) }
    }

    @JvmOverloads
    fun player(name: String = "Steve"): SandboxQuickTestMatrix = apply {
        eachScenario("player $name") { it.player(name) }
    }

    @JvmOverloads
    fun keyInput(playerName: String, key: String, action: String = "press"): SandboxQuickTestMatrix = apply {
        eachScenario("key input $playerName $key") { it.keyInput(playerName, key, action) }
    }

    @JvmOverloads
    fun mouseInput(playerName: String, button: String, action: String = "click", x: Double? = null, y: Double? = null): SandboxQuickTestMatrix = apply {
        eachScenario("mouse input $playerName $button") { it.mouseInput(playerName, button, action, x, y) }
    }

    fun assertScore(target: String, objective: String, expected: Int): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertScore(target, objective, expected) }
    }

    fun assertOutputContains(text: String): SandboxQuickTestMatrix = apply {
        scenarios.values.forEach { it.assertOutputContains(text) }
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

    fun requirePassed(): SandboxQuickTestMatrixReport {
        val report = report()
        if (!report.passed) throw SandboxQuickTestMatrixAssertionError(report)
        return report
    }

    companion object {
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

class SandboxQuickTest private constructor(
    val sandbox: DatapackSandbox,
    private val defaultFunctionId: ResourceLocation? = null,
) {
    private val failures = mutableListOf<String>()

    fun load(): SandboxQuickTest = apply {
        sandbox.runLoad()
    }

    fun ticks(count: Int): SandboxQuickTest = apply {
        sandbox.runTicks(count)
    }

    fun function(id: String): SandboxQuickTest = apply {
        sandbox.runFunction(id)
    }

    fun function(): SandboxQuickTest = apply {
        val id = defaultFunctionId
            ?: throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "No default function is configured; call function(id) or create the scenario with singleFunction(...)",
                version = sandbox.profile.id,
            )
        sandbox.runFunction(id)
    }

    fun command(command: String): SandboxQuickTest = apply {
        sandbox.executeCommand(command)
    }

    @JvmOverloads
    fun player(name: String = "Steve"): SandboxQuickTest = apply {
        sandbox.createPlayer(name)
    }

    fun event(event: PlayerEvent): SandboxQuickTest = apply {
        sandbox.handlePlayerEvent(event)
    }

    @JvmOverloads
    fun event(playerName: String, type: String, id: String? = null, action: String? = null): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.shorthand(playerName, type, id, action))
    }

    @JvmOverloads
    fun keyInput(playerName: String, key: String, action: String = "press"): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.keyInput(playerName, key, action))
    }

    @JvmOverloads
    fun mouseInput(playerName: String, button: String, action: String = "click", x: Double? = null, y: Double? = null): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.mouseInput(playerName, button, action, x, y))
    }

    fun assertScore(target: String, objective: String, expected: Int): SandboxQuickTest = apply {
        val actual = sandbox.world.getScore(target, objective)
        if (actual != expected) {
            failures += "score $target $objective expected $expected but was $actual"
        }
    }

    fun assertStorageEquals(id: String, path: String?, expectedJson: String): SandboxQuickTest = apply {
        val storageId = ResourceLocation.parse(id)
        val expected = JsonValues.parse(expectedJson)
        val actual = sandbox.world.storages[storageId]?.let { JsonPaths.get(it, path) }
        if (actual != expected) {
            failures += "storage $storageId ${path ?: "<root>"} expected ${JsonValues.render(expected)} but was ${actual?.let(JsonValues::render) ?: "<missing>"}"
        }
    }

    fun assertPlayerXp(playerName: String, expected: Int): SandboxQuickTest = apply {
        val actual = sandbox.world.requirePlayer(playerName).xp
        if (actual != expected) {
            failures += "player $playerName xp expected $expected but was $actual"
        }
    }

    fun assertPlayerLastInput(playerName: String, device: String, code: String, action: String): SandboxQuickTest = apply {
        val actual = sandbox.world.requirePlayer(playerName).lastInput
        if (actual == null) {
            failures += "player $playerName last input expected $device:$code/$action but was <none>"
        } else if (actual.device != device || actual.code != code || actual.action != action) {
            failures += "player $playerName last input expected $device:$code/$action but was ${actual.device}:${actual.code}/${actual.action}"
        }
    }

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

    fun assertOutputContains(text: String): SandboxQuickTest = apply {
        if (sandbox.world.outputs.none { text in it.text }) {
            failures += "expected an output containing '$text'"
        }
    }

    fun report(): SandboxQuickTestReport =
        SandboxQuickTestReport(
            passed = failures.isEmpty(),
            failures = failures.toList(),
            outputs = sandbox.world.outputs.toList(),
            snapshot = sandbox.snapshotJson(),
        )

    fun requirePassed(): SandboxQuickTestReport {
        val report = report()
        if (!report.passed) throw SandboxQuickTestAssertionError(report)
        return report
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(
            packs: List<Path>,
            version: String = VersionProfiles.default.id,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        ): SandboxQuickTest =
            SandboxQuickTest(createSandbox(version, packs, defaultPlayerName = defaultPlayerName, unsupportedFeatureMode = unsupportedFeatureMode))

        @JvmStatic
        @JvmOverloads
        fun matrix(
            packsByVersion: Map<String, List<Path>>,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        ): SandboxQuickTestMatrix =
            SandboxQuickTestMatrix.create(packsByVersion, defaultPlayerName, unsupportedFeatureMode)

        @JvmStatic
        @JvmOverloads
        fun singleFunction(
            functionFile: Path,
            version: String,
            functionId: String = SingleFunctionDatapack.DEFAULT_ID,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        ): SandboxQuickTest {
            val id = ResourceLocation.parse(functionId)
            return SandboxQuickTest(
                sandbox = createFunctionSandbox(
                    version = version,
                    functionFile = functionFile,
                    functionId = id.toString(),
                    defaultPlayerName = defaultPlayerName,
                    unsupportedFeatureMode = unsupportedFeatureMode,
                ),
                defaultFunctionId = id,
            )
        }
    }
}

object DatapackSandboxTestApi {
    @JvmStatic
    @JvmOverloads
    fun scenario(
        packs: List<Path>,
        version: String = VersionProfiles.default.id,
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    ): SandboxQuickTest =
        SandboxQuickTest.create(packs, version, defaultPlayerName, unsupportedFeatureMode)

    @JvmStatic
    @JvmOverloads
    fun singleFunctionScenario(
        functionFile: Path,
        version: String,
        functionId: String = SingleFunctionDatapack.DEFAULT_ID,
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    ): SandboxQuickTest =
        SandboxQuickTest.singleFunction(functionFile, version, functionId, defaultPlayerName, unsupportedFeatureMode)

    @JvmStatic
    @JvmOverloads
    fun scenarioMatrix(
        packsByVersion: Map<String, List<Path>>,
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    ): SandboxQuickTestMatrix =
        SandboxQuickTest.matrix(packsByVersion, defaultPlayerName, unsupportedFeatureMode)

    @JvmStatic
    @JvmOverloads
    fun runFunctionFile(
        functionFile: Path,
        version: String,
        functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    ): SandboxQuickTestReport =
        singleFunctionScenario(functionFile, version, functionId).function().report()

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

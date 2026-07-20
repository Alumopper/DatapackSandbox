package moe.afox.dpsandbox.core

import java.nio.file.Path

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
    ): SandboxQuickTest = SandboxQuickTest.create(packs, version, defaultPlayerName, unsupportedFeatureMode)

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
        SandboxQuickTest.singleFunctionText(
            functionText,
            version,
            functionId,
            sourceName,
            defaultPlayerName,
            unsupportedFeatureMode,
            dependencyPacks,
        )

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
    ): SandboxQuickTestMatrix = SandboxQuickTest.matrix(packsByVersion, defaultPlayerName, unsupportedFeatureMode)

    /**
     * Runs a single `.mcfunction` file once and returns its report.
     */
    @JvmStatic
    @JvmOverloads
    fun runFunctionFile(
        functionFile: Path,
        version: String,
        functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    ): SandboxQuickTestReport = singleFunctionScenario(functionFile, version, functionId).function().report()

    /**
     * Runs one in-memory `.mcfunction` string once and returns its report.
     */
    @JvmStatic
    @JvmOverloads
    fun runFunctionText(
        functionText: String,
        version: String,
        functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    ): SandboxQuickTestReport = singleFunctionTextScenario(functionText, version, functionId).function().report()

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

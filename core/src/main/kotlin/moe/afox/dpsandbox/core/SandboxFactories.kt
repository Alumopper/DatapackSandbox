@file:JvmName("SandboxKt")

package moe.afox.dpsandbox.core

import java.nio.file.Path

/**
 * Creates a [DatapackSandbox] from one or more datapack paths.
 *
 * @param version Minecraft version profile id.
 * @param packs Datapack directories or zip files to load, in pack priority order.
 * @param world Mutable world instance to use. Pass a preconfigured world when
 * tests need custom fixtures or imported save data.
 * @param defaultPlayerName Name of the initial player to create when [world]
 * has no players, or `null` to start without an implicit player.
 * @param unsupportedFeatureMode Policy for vanilla commands recognized by the
 * active profile but not implemented by the sandbox.
 * @param limits Execution safety limits used to stop runaway tests.
 */
@JvmOverloads
fun createSandbox(
    version: String,
    packs: List<Path>,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.load(packs, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode, limits)
}

/**
 * Creates a [DatapackSandbox] backed by multiple synthetic `.mcfunction` sources.
 *
 * This is the recommended low-level factory when tests need several functions
 * but do not need a full datapack directory.
 *
 * @param version Minecraft version profile id.
 * @param functionSources In-memory or file-backed function sources.
 * @param world Mutable world instance to use.
 * @param defaultPlayerName Name of the initial player to create when [world]
 * has no players, or `null` to start without an implicit player.
 * @param unsupportedFeatureMode Policy for recognized but unimplemented vanilla commands.
 * @param limits Execution safety limits used to stop runaway tests.
 */
@JvmOverloads
fun createFunctionSandbox(
    version: String,
    functionSources: List<FunctionSource>,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.loadFunctionSources(functionSources, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode, limits)
}

/**
 * Creates a [DatapackSandbox] from datapack dependencies plus synthetic functions.
 *
 * Dependency packs are loaded first from [packs], then [functionSources] overlay
 * additional top-priority functions. This keeps lightweight function tests able
 * to call functions, loot tables, predicates, and advancements from real
 * datapack dependencies.
 */
@JvmOverloads
fun createFunctionSandbox(
    version: String,
    packs: List<Path>,
    functionSources: List<FunctionSource>,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.loadFunctionSources(packs, functionSources, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode, limits)
}

/**
 * Creates a [DatapackSandbox] backed by a single `.mcfunction` file.
 *
 * This factory is intended for lightweight tests that do not need a full
 * datapack directory. The file is exposed as [functionId] inside a generated
 * in-memory datapack.
 *
 * @param version Minecraft version profile id.
 * @param functionFile Path to the `.mcfunction` file.
 * @param functionId Temporary function id assigned to [functionFile].
 * @param world Mutable world instance to use.
 * @param defaultPlayerName Name of the initial player to create when [world]
 * has no players, or `null` to start without an implicit player.
 * @param unsupportedFeatureMode Policy for recognized but unimplemented vanilla commands.
 * @param limits Execution safety limits used to stop runaway tests.
 */
@JvmOverloads
fun createFunctionSandbox(
    version: String,
    functionFile: Path,
    functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        functionSources = listOf(FunctionSource.file(functionId, functionFile)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
        limits = limits,
    )

/**
 * Creates a [DatapackSandbox] backed by one in-memory `.mcfunction` string.
 *
 * @param functionText Raw `.mcfunction` content.
 * @param functionId Temporary function id assigned to [functionText].
 * @param sourceName Label used in diagnostics.
 */
@JvmOverloads
fun createFunctionSandboxFromString(
    version: String,
    functionText: String,
    functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    sourceName: String = "<string:$functionId>",
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        functionSources = listOf(FunctionSource.text(functionId, functionText, sourceName)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
        limits = limits,
    )

/**
 * Creates a [DatapackSandbox] backed by dependencies and one in-memory function string.
 */
@JvmOverloads
fun createFunctionSandboxFromString(
    version: String,
    packs: List<Path>,
    functionText: String,
    functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    sourceName: String = "<string:$functionId>",
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        packs = packs,
        functionSources = listOf(FunctionSource.text(functionId, functionText, sourceName)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
        limits = limits,
    )

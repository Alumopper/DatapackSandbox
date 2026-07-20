package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import java.nio.file.Path

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
    fun load(): SandboxQuickTestMatrix =
        apply {
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
    fun ticks(count: Int): SandboxQuickTestMatrix =
        apply {
            eachScenario("ticks") { it.ticks(count) }
        }

    /**
     * Runs the loaded datapack function [id] in every scenario.
     *
     * @param id Function resource location, for example `demo:main`.
     * @return this matrix for fluent chaining.
     */
    fun function(id: String): SandboxQuickTestMatrix =
        apply {
            eachScenario("function $id") { it.function(id) }
        }

    /**
     * Executes one raw Minecraft command in every scenario.
     *
     * The command may include a leading slash; it is normalized by the runtime.
     *
     * @return this matrix for fluent chaining.
     */
    fun command(command: String): SandboxQuickTestMatrix =
        apply {
            eachScenario(command) { it.command(command) }
        }

    /**
     * Applies an arbitrary [SandboxQuickTest] operation or assertion to every
     * version-specific scenario.
     *
     * This is the forward-compatible matrix path for QuickTest APIs that do
     * not need a dedicated convenience method here.
     */
    fun forEachScenario(configure: SandboxQuickTest.() -> Unit): SandboxQuickTestMatrix =
        apply {
            eachScenario("scenario operation") { scenario -> scenario.configure() }
        }

    /**
     * Applies an in-memory world fixture to every scenario.
     *
     * The fixture is applied immediately and validated against each scenario's
     * active [VersionProfile].
     *
     * @return this matrix for fluent chaining.
     */
    fun world(configure: SandboxWorldSetup.() -> Unit): SandboxQuickTestMatrix =
        apply {
            eachScenario("world setup") { it.world(configure) }
        }

    /**
     * Applies a reusable [SandboxWorldSetup] to every scenario.
     *
     * @return this matrix for fluent chaining.
     */
    fun setupWorld(setup: SandboxWorldSetup): SandboxQuickTestMatrix =
        apply {
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
    ): SandboxQuickTestMatrix =
        apply {
            eachScenario("save import") { it.importSave(path, chunks, dimension, includeBlocks, includeBlockEntities, includeEntities) }
        }

    /**
     * Creates or reuses a player in every scenario.
     *
     * @param name Player name to create; defaults to `Steve` for Java callers.
     * @return this matrix for fluent chaining.
     */
    @JvmOverloads
    fun player(name: String = "Steve"): SandboxQuickTestMatrix =
        apply {
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
    fun keyInput(
        playerName: String,
        key: String,
        action: String = "press",
    ): SandboxQuickTestMatrix =
        apply {
            eachScenario("key input $playerName $key") { it.keyInput(playerName, key, action) }
        }

    fun keyInput(
        playerName: String,
        key: String,
        action: PlayerInputAction,
    ): SandboxQuickTestMatrix = keyInput(playerName, key, action.id)

    /**
     * Injects a mouse input event for [playerName] in every scenario.
     *
     * Optional [x] and [y] values are recorded as input metadata only.
     *
     * @return this matrix for fluent chaining.
     */
    @JvmOverloads
    fun mouseInput(
        playerName: String,
        button: String,
        action: String = "click",
        x: Double? = null,
        y: Double? = null,
    ): SandboxQuickTestMatrix =
        apply {
            eachScenario("mouse input $playerName $button") { it.mouseInput(playerName, button, action, x, y) }
        }

    fun mouseInput(
        playerName: String,
        button: String,
        action: PlayerInputAction,
        x: Double? = null,
        y: Double? = null,
    ): SandboxQuickTestMatrix = mouseInput(playerName, button, action.id, x, y)

    /**
     * Adds a scoreboard assertion to every scenario.
     *
     * The assertion is evaluated immediately against each current world state.
     */
    fun assertScore(
        target: String,
        objective: String,
        expected: Int,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertScore(target, objective, expected) }
        }

    /**
     * Adds a lower-bound scoreboard assertion to every scenario.
     */
    fun assertScoreAtLeast(
        target: String,
        objective: String,
        minimum: Int,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertScoreAtLeast(target, objective, minimum) }
        }

    /**
     * Adds an upper-bound scoreboard assertion to every scenario.
     */
    fun assertScoreAtMost(
        target: String,
        objective: String,
        maximum: Int,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertScoreAtMost(target, objective, maximum) }
        }

    /**
     * Adds optional lower and upper scoreboard bounds to every scenario.
     */
    @JvmOverloads
    fun assertScoreRange(
        target: String,
        objective: String,
        min: Int? = null,
        max: Int? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertScoreRange(target, objective, min, max) }
        }

    /**
     * Adds a storage equality assertion to every scenario.
     */
    fun assertStorageEquals(
        id: String,
        path: String?,
        expectedJson: String,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertStorageEquals(id, path, expectedJson) }
        }

    /**
     * Adds a storage existence assertion to every scenario.
     */
    @JvmOverloads
    fun assertStorageExists(
        id: String,
        path: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertStorageExists(id, path) }
        }

    /**
     * Adds a storage missing assertion to every scenario.
     */
    @JvmOverloads
    fun assertStorageMissing(
        id: String,
        path: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertStorageMissing(id, path) }
        }

    /**
     * Applies a random sequence state assertion to every scenario.
     */
    @JvmOverloads
    fun assertRandomSequence(
        name: String,
        expected: Long? = null,
        exists: Boolean = true,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertRandomSequence(name, expected, exists) }
        }

    /**
     * Applies a forced chunk assertion to every scenario.
     */
    @JvmOverloads
    fun assertForcedChunk(
        x: Int,
        z: Int,
        exists: Boolean = true,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertForcedChunk(x, z, exists) }
        }

    /**
     * Applies a gamerule state assertion to every scenario.
     */
    @JvmOverloads
    fun assertGamerule(
        name: String,
        value: String? = null,
        exists: Boolean = true,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertGamerule(name, value, exists) }
        }

    /**
     * Applies a scheduled function queue assertion to every scenario.
     */
    @JvmOverloads
    fun assertScheduledFunction(
        id: String,
        dueTick: Long? = null,
        exists: Boolean = true,
        count: Int? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertScheduledFunction(id, dueTick, exists, count) }
        }

    /**
     * Applies a scoreboard objective metadata assertion to every scenario.
     */
    @JvmOverloads
    fun assertScoreboardObjective(
        name: String,
        exists: Boolean = true,
        criteria: String? = null,
        displayName: String? = null,
        renderType: String? = null,
        displayAutoUpdate: Boolean? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertScoreboardObjective(name, exists, criteria, displayName, renderType, displayAutoUpdate)
            }
        }

    fun assertScoreboardObjective(
        name: String,
        renderType: ScoreboardRenderType,
        exists: Boolean = true,
        criteria: String? = null,
        displayName: String? = null,
        displayAutoUpdate: Boolean? = null,
    ): SandboxQuickTestMatrix =
        assertScoreboardObjective(
            name = name,
            exists = exists,
            criteria = criteria,
            displayName = displayName,
            renderType = renderType.id,
            displayAutoUpdate = displayAutoUpdate,
        )

    /**
     * Applies a scoreboard display slot assertion to every scenario.
     */
    @JvmOverloads
    fun assertScoreboardDisplay(
        slot: String,
        objective: String? = null,
        exists: Boolean = true,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertScoreboardDisplay(slot, objective, exists) }
        }

    fun assertScoreboardDisplay(
        slot: ScoreboardDisplaySlot,
        objective: String? = null,
        exists: Boolean = true,
    ): SandboxQuickTestMatrix = assertScoreboardDisplay(slot.id, objective, exists)

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
        forcedChunkX: Int? = null,
        forcedChunkZ: Int? = null,
        biomeX: Int? = null,
        biomeY: Int? = null,
        biomeZ: Int? = null,
        biome: String? = null,
        worldSpawn: Position? = null,
        worldSpawnDimension: String? = null,
        worldSpawnAngle: Double? = null,
        worldSpawnForced: Boolean? = null,
        worldBorderCenterX: Double? = null,
        worldBorderCenterZ: Double? = null,
        worldBorderSize: Double? = null,
        worldBorderTargetSize: Double? = null,
        worldBorderLerpTimeSeconds: Long? = null,
        worldBorderDamageBuffer: Double? = null,
        worldBorderDamageAmount: Double? = null,
        worldBorderWarningDistance: Int? = null,
        worldBorderWarningTime: Int? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertWorld(
                    gameTime = gameTime,
                    dayTime = dayTime,
                    weather = weather,
                    difficulty = difficulty,
                    defaultGameMode = defaultGameMode,
                    seed = seed,
                    forcedChunkX = forcedChunkX,
                    forcedChunkZ = forcedChunkZ,
                    biomeX = biomeX,
                    biomeY = biomeY,
                    biomeZ = biomeZ,
                    biome = biome,
                    worldSpawn = worldSpawn,
                    worldSpawnDimension = worldSpawnDimension,
                    worldSpawnAngle = worldSpawnAngle,
                    worldSpawnForced = worldSpawnForced,
                    worldBorderCenterX = worldBorderCenterX,
                    worldBorderCenterZ = worldBorderCenterZ,
                    worldBorderSize = worldBorderSize,
                    worldBorderTargetSize = worldBorderTargetSize,
                    worldBorderLerpTimeSeconds = worldBorderLerpTimeSeconds,
                    worldBorderDamageBuffer = worldBorderDamageBuffer,
                    worldBorderDamageAmount = worldBorderDamageAmount,
                    worldBorderWarningDistance = worldBorderWarningDistance,
                    worldBorderWarningTime = worldBorderWarningTime,
                )
            }
        }

    fun assertWorld(
        weather: SandboxWeather,
        gameTime: Long? = null,
        dayTime: Long? = null,
        difficulty: SandboxDifficulty? = null,
        defaultGameMode: SandboxGameMode? = null,
        seed: Long? = null,
        forcedChunkX: Int? = null,
        forcedChunkZ: Int? = null,
        biomeX: Int? = null,
        biomeY: Int? = null,
        biomeZ: Int? = null,
        biome: String? = null,
        worldSpawn: Position? = null,
        worldSpawnDimension: String? = null,
        worldSpawnAngle: Double? = null,
        worldSpawnForced: Boolean? = null,
        worldBorderCenterX: Double? = null,
        worldBorderCenterZ: Double? = null,
        worldBorderSize: Double? = null,
        worldBorderTargetSize: Double? = null,
        worldBorderLerpTimeSeconds: Long? = null,
        worldBorderDamageBuffer: Double? = null,
        worldBorderDamageAmount: Double? = null,
        worldBorderWarningDistance: Int? = null,
        worldBorderWarningTime: Int? = null,
    ): SandboxQuickTestMatrix =
        assertWorld(
            gameTime = gameTime,
            dayTime = dayTime,
            weather = weather.id,
            difficulty = difficulty?.id,
            defaultGameMode = defaultGameMode?.id,
            seed = seed,
            forcedChunkX = forcedChunkX,
            forcedChunkZ = forcedChunkZ,
            biomeX = biomeX,
            biomeY = biomeY,
            biomeZ = biomeZ,
            biome = biome,
            worldSpawn = worldSpawn,
            worldSpawnDimension = worldSpawnDimension,
            worldSpawnAngle = worldSpawnAngle,
            worldSpawnForced = worldSpawnForced,
            worldBorderCenterX = worldBorderCenterX,
            worldBorderCenterZ = worldBorderCenterZ,
            worldBorderSize = worldBorderSize,
            worldBorderTargetSize = worldBorderTargetSize,
            worldBorderLerpTimeSeconds = worldBorderLerpTimeSeconds,
            worldBorderDamageBuffer = worldBorderDamageBuffer,
            worldBorderDamageAmount = worldBorderDamageAmount,
            worldBorderWarningDistance = worldBorderWarningDistance,
            worldBorderWarningTime = worldBorderWarningTime,
        )

    fun assertWorld(
        difficulty: SandboxDifficulty,
        gameTime: Long? = null,
        dayTime: Long? = null,
        defaultGameMode: SandboxGameMode? = null,
        seed: Long? = null,
        forcedChunkX: Int? = null,
        forcedChunkZ: Int? = null,
        biomeX: Int? = null,
        biomeY: Int? = null,
        biomeZ: Int? = null,
        biome: String? = null,
        worldSpawn: Position? = null,
        worldSpawnDimension: String? = null,
        worldSpawnAngle: Double? = null,
        worldSpawnForced: Boolean? = null,
        worldBorderCenterX: Double? = null,
        worldBorderCenterZ: Double? = null,
        worldBorderSize: Double? = null,
        worldBorderTargetSize: Double? = null,
        worldBorderLerpTimeSeconds: Long? = null,
        worldBorderDamageBuffer: Double? = null,
        worldBorderDamageAmount: Double? = null,
        worldBorderWarningDistance: Int? = null,
        worldBorderWarningTime: Int? = null,
    ): SandboxQuickTestMatrix =
        assertWorld(
            gameTime = gameTime,
            dayTime = dayTime,
            difficulty = difficulty.id,
            defaultGameMode = defaultGameMode?.id,
            seed = seed,
            forcedChunkX = forcedChunkX,
            forcedChunkZ = forcedChunkZ,
            biomeX = biomeX,
            biomeY = biomeY,
            biomeZ = biomeZ,
            biome = biome,
            worldSpawn = worldSpawn,
            worldSpawnDimension = worldSpawnDimension,
            worldSpawnAngle = worldSpawnAngle,
            worldSpawnForced = worldSpawnForced,
            worldBorderCenterX = worldBorderCenterX,
            worldBorderCenterZ = worldBorderCenterZ,
            worldBorderSize = worldBorderSize,
            worldBorderTargetSize = worldBorderTargetSize,
            worldBorderLerpTimeSeconds = worldBorderLerpTimeSeconds,
            worldBorderDamageBuffer = worldBorderDamageBuffer,
            worldBorderDamageAmount = worldBorderDamageAmount,
            worldBorderWarningDistance = worldBorderWarningDistance,
            worldBorderWarningTime = worldBorderWarningTime,
        )

    fun assertWorld(
        defaultGameMode: SandboxGameMode,
        gameTime: Long? = null,
        dayTime: Long? = null,
        seed: Long? = null,
        forcedChunkX: Int? = null,
        forcedChunkZ: Int? = null,
        biomeX: Int? = null,
        biomeY: Int? = null,
        biomeZ: Int? = null,
        biome: String? = null,
        worldSpawn: Position? = null,
        worldSpawnDimension: String? = null,
        worldSpawnAngle: Double? = null,
        worldSpawnForced: Boolean? = null,
        worldBorderCenterX: Double? = null,
        worldBorderCenterZ: Double? = null,
        worldBorderSize: Double? = null,
        worldBorderTargetSize: Double? = null,
        worldBorderLerpTimeSeconds: Long? = null,
        worldBorderDamageBuffer: Double? = null,
        worldBorderDamageAmount: Double? = null,
        worldBorderWarningDistance: Int? = null,
        worldBorderWarningTime: Int? = null,
    ): SandboxQuickTestMatrix =
        assertWorld(
            gameTime = gameTime,
            dayTime = dayTime,
            defaultGameMode = defaultGameMode.id,
            seed = seed,
            forcedChunkX = forcedChunkX,
            forcedChunkZ = forcedChunkZ,
            biomeX = biomeX,
            biomeY = biomeY,
            biomeZ = biomeZ,
            biome = biome,
            worldSpawn = worldSpawn,
            worldSpawnDimension = worldSpawnDimension,
            worldSpawnAngle = worldSpawnAngle,
            worldSpawnForced = worldSpawnForced,
            worldBorderCenterX = worldBorderCenterX,
            worldBorderCenterZ = worldBorderCenterZ,
            worldBorderSize = worldBorderSize,
            worldBorderTargetSize = worldBorderTargetSize,
            worldBorderLerpTimeSeconds = worldBorderLerpTimeSeconds,
            worldBorderDamageBuffer = worldBorderDamageBuffer,
            worldBorderDamageAmount = worldBorderDamageAmount,
            worldBorderWarningDistance = worldBorderWarningDistance,
            worldBorderWarningTime = worldBorderWarningTime,
        )

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
        xpLevels: Int? = null,
        health: Double? = null,
        food: Int? = null,
        selectedSlot: Int? = null,
        inventoryCount: Int? = null,
        enderItemCount: Int? = null,
        recipe: String? = null,
        effect: String? = null,
        stat: String? = null,
        statValue: Int? = null,
        spawn: Position? = null,
        spawnDimension: String? = null,
        spawnAngle: Double? = null,
        spawnForced: Boolean? = null,
        nbtPath: String? = null,
        nbtEquals: String? = null,
        nbtExists: Boolean? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertPlayer(
                    name = name,
                    exists = exists,
                    position = position,
                    dimension = dimension,
                    gameMode = gameMode,
                    xp = xp,
                    xpLevels = xpLevels,
                    health = health,
                    food = food,
                    selectedSlot = selectedSlot,
                    inventoryCount = inventoryCount,
                    enderItemCount = enderItemCount,
                    recipe = recipe,
                    effect = effect,
                    stat = stat,
                    statValue = statValue,
                    spawn = spawn,
                    spawnDimension = spawnDimension,
                    spawnAngle = spawnAngle,
                    spawnForced = spawnForced,
                    nbtPath = nbtPath,
                    nbtEquals = nbtEquals,
                    nbtExists = nbtExists,
                )
            }
        }

    fun assertPlayer(
        name: String,
        gameMode: SandboxGameMode,
        exists: Boolean = true,
        position: Position? = null,
        dimension: String? = null,
        xp: Int? = null,
        xpLevels: Int? = null,
        health: Double? = null,
        food: Int? = null,
        selectedSlot: Int? = null,
        inventoryCount: Int? = null,
        enderItemCount: Int? = null,
        recipe: String? = null,
        effect: String? = null,
        stat: String? = null,
        statValue: Int? = null,
        spawn: Position? = null,
        spawnDimension: String? = null,
        spawnAngle: Double? = null,
        spawnForced: Boolean? = null,
        nbtPath: String? = null,
        nbtEquals: String? = null,
        nbtExists: Boolean? = null,
    ): SandboxQuickTestMatrix =
        assertPlayer(
            name = name,
            exists = exists,
            position = position,
            dimension = dimension,
            gameMode = gameMode.id,
            xp = xp,
            xpLevels = xpLevels,
            health = health,
            food = food,
            selectedSlot = selectedSlot,
            inventoryCount = inventoryCount,
            enderItemCount = enderItemCount,
            recipe = recipe,
            effect = effect,
            stat = stat,
            statValue = statValue,
            spawn = spawn,
            spawnDimension = spawnDimension,
            spawnAngle = spawnAngle,
            spawnForced = spawnForced,
            nbtPath = nbtPath,
            nbtEquals = nbtEquals,
            nbtExists = nbtExists,
        )

    /**
     * Applies a player XP assertion to every scenario.
     */
    fun assertPlayerXp(
        playerName: String,
        expected: Int,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertPlayerXp(playerName, expected) }
        }

    /**
     * Applies a player XP level assertion to every scenario.
     */
    fun assertPlayerXpLevels(
        playerName: String,
        expected: Int,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertPlayerXpLevels(playerName, expected) }
        }

    /**
     * Applies a latest player input assertion to every scenario.
     */
    fun assertPlayerLastInput(
        playerName: String,
        device: String,
        code: String,
        action: String,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertPlayerLastInput(playerName, device, code, action) }
        }

    fun assertPlayerLastInput(
        playerName: String,
        device: PlayerInputDevice,
        code: String,
        action: PlayerInputAction,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertPlayerLastInput(playerName, device, code, action) }
        }

    /**
     * Applies a sparse-world block assertion to every scenario.
     */
    @JvmOverloads
    fun assertBlock(
        x: Int,
        y: Int,
        z: Int,
        id: String? = null,
        exists: Boolean = true,
        nbtPath: String? = null,
        nbtEquals: String? = null,
        nbtExists: Boolean? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertBlock(x, y, z, id, exists, nbtPath, nbtEquals, nbtExists) }
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
        dimension: String? = null,
        health: Double? = null,
        vehicle: String? = null,
        passenger: String? = null,
        passengerCount: Int? = null,
        nbtPath: String? = null,
        nbtEquals: String? = null,
        nbtExists: Boolean? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertEntity(
                    type = type,
                    tag = tag,
                    uuid = uuid,
                    position = position,
                    exists = exists,
                    count = count,
                    dimension = dimension,
                    health = health,
                    vehicle = vehicle,
                    passenger = passenger,
                    passengerCount = passengerCount,
                    nbtPath = nbtPath,
                    nbtEquals = nbtEquals,
                    nbtExists = nbtExists,
                )
            }
        }

    /**
     * Applies an entity equipment assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityEquipment(
        slot: String,
        type: String? = null,
        tag: String? = null,
        uuid: String? = null,
        position: Position? = null,
        id: String? = null,
        count: Int? = null,
        exists: Boolean = true,
        minCount: Int? = null,
        maxCount: Int? = null,
        componentsPath: String? = null,
        componentsEquals: String? = null,
        componentsExists: Boolean? = null,
        nbtPath: String? = null,
        nbtEquals: String? = null,
        nbtExists: Boolean? = null,
        dimension: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertEntityEquipment(
                    slot = slot,
                    type = type,
                    tag = tag,
                    uuid = uuid,
                    position = position,
                    dimension = dimension,
                    id = id,
                    count = count,
                    exists = exists,
                    minCount = minCount,
                    maxCount = maxCount,
                    componentsPath = componentsPath,
                    componentsEquals = componentsEquals,
                    componentsExists = componentsExists,
                    nbtPath = nbtPath,
                    nbtEquals = nbtEquals,
                    nbtExists = nbtExists,
                )
            }
        }

    fun assertEntityEquipment(
        slot: EntityEquipmentSlot,
        type: String? = null,
        tag: String? = null,
        uuid: String? = null,
        position: Position? = null,
        id: String? = null,
        count: Int? = null,
        exists: Boolean = true,
        minCount: Int? = null,
        maxCount: Int? = null,
        componentsPath: String? = null,
        componentsEquals: String? = null,
        componentsExists: Boolean? = null,
        nbtPath: String? = null,
        nbtEquals: String? = null,
        nbtExists: Boolean? = null,
        dimension: String? = null,
    ): SandboxQuickTestMatrix =
        assertEntityEquipment(
            slot = slot.id,
            type = type,
            tag = tag,
            uuid = uuid,
            position = position,
            dimension = dimension,
            id = id,
            count = count,
            exists = exists,
            minCount = minCount,
            maxCount = maxCount,
            componentsPath = componentsPath,
            componentsEquals = componentsEquals,
            componentsExists = componentsExists,
            nbtPath = nbtPath,
            nbtEquals = nbtEquals,
            nbtExists = nbtExists,
        )

    /**
     * Applies an entity active-effect assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityEffect(
        effect: String,
        type: String? = null,
        tag: String? = null,
        uuid: String? = null,
        position: Position? = null,
        exists: Boolean = true,
        durationTicks: Int? = null,
        amplifier: Int? = null,
        hideParticles: Boolean? = null,
        dimension: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertEntityEffect(
                    effect = effect,
                    type = type,
                    tag = tag,
                    uuid = uuid,
                    position = position,
                    dimension = dimension,
                    exists = exists,
                    durationTicks = durationTicks,
                    amplifier = amplifier,
                    hideParticles = hideParticles,
                )
            }
        }

    /**
     * Applies an entity attribute assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityAttribute(
        attribute: String,
        type: String? = null,
        tag: String? = null,
        uuid: String? = null,
        position: Position? = null,
        exists: Boolean = true,
        value: Double? = null,
        min: Double? = null,
        max: Double? = null,
        dimension: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertEntityAttribute(
                    attribute = attribute,
                    type = type,
                    tag = tag,
                    uuid = uuid,
                    position = position,
                    dimension = dimension,
                    exists = exists,
                    value = value,
                    min = min,
                    max = max,
                )
            }
        }

    /**
     * Applies an entity count assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityCount(
        expected: Int,
        type: String? = null,
        tag: String? = null,
        dimension: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertEntityCount(expected, type, tag, dimension) }
        }

    /**
     * Applies a lower-bound entity count assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityCountAtLeast(
        minimum: Int,
        type: String? = null,
        tag: String? = null,
        dimension: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertEntityCountAtLeast(minimum, type, tag, dimension) }
        }

    /**
     * Applies an upper-bound entity count assertion to every scenario.
     */
    @JvmOverloads
    fun assertEntityCountAtMost(
        maximum: Int,
        type: String? = null,
        tag: String? = null,
        dimension: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertEntityCountAtMost(maximum, type, tag, dimension) }
        }

    /**
     * Applies optional lower and upper entity count bounds to every scenario.
     */
    @JvmOverloads
    fun assertEntityCountRange(
        min: Int? = null,
        max: Int? = null,
        type: String? = null,
        tag: String? = null,
        dimension: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertEntityCountRange(min, max, type, tag, dimension) }
        }

    /**
     * Applies a team state assertion to every scenario.
     */
    @JvmOverloads
    fun assertTeam(
        name: String,
        exists: Boolean = true,
        displayName: String? = null,
        member: String? = null,
        memberCount: Int? = null,
        optionName: String? = null,
        optionEquals: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertTeam(
                    name = name,
                    exists = exists,
                    displayName = displayName,
                    member = member,
                    memberCount = memberCount,
                    optionName = optionName,
                    optionEquals = optionEquals,
                )
            }
        }

    fun assertTeam(
        name: String,
        optionName: TeamOption,
        optionEquals: String,
        exists: Boolean = true,
        displayName: String? = null,
        member: String? = null,
        memberCount: Int? = null,
    ): SandboxQuickTestMatrix =
        assertTeam(
            name = name,
            exists = exists,
            displayName = displayName,
            member = member,
            memberCount = memberCount,
            optionName = optionName.id,
            optionEquals = optionEquals,
        )

    /**
     * Applies a bossbar state assertion to every scenario.
     */
    @JvmOverloads
    fun assertBossbar(
        id: String,
        exists: Boolean = true,
        name: String? = null,
        value: Int? = null,
        max: Int? = null,
        color: String? = null,
        style: String? = null,
        visible: Boolean? = null,
        player: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertBossbar(
                    id = id,
                    exists = exists,
                    name = name,
                    value = value,
                    max = max,
                    color = color,
                    style = style,
                    visible = visible,
                    player = player,
                )
            }
        }

    fun assertBossbar(
        id: String,
        color: BossbarColor,
        exists: Boolean = true,
        name: String? = null,
        value: Int? = null,
        max: Int? = null,
        style: BossbarStyle? = null,
        visible: Boolean? = null,
        player: String? = null,
    ): SandboxQuickTestMatrix =
        assertBossbar(
            id = id,
            exists = exists,
            name = name,
            value = value,
            max = max,
            color = color.id,
            style = style?.id,
            visible = visible,
            player = player,
        )

    fun assertBossbar(
        id: String,
        style: BossbarStyle,
        exists: Boolean = true,
        name: String? = null,
        value: Int? = null,
        max: Int? = null,
        visible: Boolean? = null,
        player: String? = null,
    ): SandboxQuickTestMatrix =
        assertBossbar(
            id = id,
            exists = exists,
            name = name,
            value = value,
            max = max,
            style = style.id,
            visible = visible,
            player = player,
        )

    /**
     * Applies an inventory item assertion to every scenario.
     */
    @JvmOverloads
    fun assertItem(
        playerName: String,
        id: String? = null,
        count: Int? = null,
        slot: Int? = null,
        exists: Boolean = true,
        minCount: Int? = null,
        maxCount: Int? = null,
        componentsPath: String? = null,
        componentsEquals: String? = null,
        componentsExists: Boolean? = null,
        nbtPath: String? = null,
        nbtEquals: String? = null,
        nbtExists: Boolean? = null,
        container: String = "inventory",
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach {
                it.assertItem(
                    playerName = playerName,
                    id = id,
                    count = count,
                    slot = slot,
                    exists = exists,
                    minCount = minCount,
                    maxCount = maxCount,
                    componentsPath = componentsPath,
                    componentsEquals = componentsEquals,
                    componentsExists = componentsExists,
                    nbtPath = nbtPath,
                    nbtEquals = nbtEquals,
                    nbtExists = nbtExists,
                    container = container,
                )
            }
        }

    fun assertItem(
        playerName: String,
        container: ItemContainer,
        id: String? = null,
        count: Int? = null,
        slot: Int? = null,
        exists: Boolean = true,
        minCount: Int? = null,
        maxCount: Int? = null,
        componentsPath: String? = null,
        componentsEquals: String? = null,
        componentsExists: Boolean? = null,
        nbtPath: String? = null,
        nbtEquals: String? = null,
        nbtExists: Boolean? = null,
    ): SandboxQuickTestMatrix =
        assertItem(
            playerName = playerName,
            id = id,
            count = count,
            slot = slot,
            exists = exists,
            minCount = minCount,
            maxCount = maxCount,
            componentsPath = componentsPath,
            componentsEquals = componentsEquals,
            componentsExists = componentsExists,
            nbtPath = nbtPath,
            nbtEquals = nbtEquals,
            nbtExists = nbtExists,
            container = container.id,
        )

    /**
     * Applies a predicate assertion to every scenario.
     */
    @JvmOverloads
    fun assertPredicate(
        id: String,
        expected: Boolean = true,
        playerName: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertPredicate(id, expected, playerName) }
        }

    /**
     * Applies a loot generation assertion to every scenario.
     */
    @JvmOverloads
    fun assertLoot(
        table: String,
        context: String = "minecraft:empty",
        playerName: String? = null,
        seed: Long = 0,
        count: Int? = null,
        item: String? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertLoot(table, context, playerName, seed, count, item) }
        }

    fun assertLoot(
        table: String,
        context: LootContextId,
        playerName: String? = null,
        seed: Long = 0,
        count: Int? = null,
        item: String? = null,
    ): SandboxQuickTestMatrix = assertLoot(table, context.id, playerName, seed, count, item)

    /**
     * Applies an advancement completion assertion to every scenario.
     */
    @JvmOverloads
    fun assertAdvancementDone(
        playerName: String,
        id: String,
        expected: Boolean = true,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertAdvancementDone(playerName, id, expected) }
        }

    /**
     * Asserts that every scenario recorded at least one output event containing [text].
     */
    fun assertOutputContains(text: String): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertOutputContains(text) }
        }

    /**
     * Applies a structured output assertion to every scenario.
     */
    fun assertOutput(expectation: OutputExpectation): SandboxQuickTestMatrix =
        apply {
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
        textMatches: String? = null,
        count: Int? = null,
        order: Int? = null,
        normalizedText: String? = null,
        normalizedContains: String? = null,
        normalizedMatches: String? = null,
        rawText: String? = null,
        rawContains: String? = null,
        rawTextMatches: String? = null,
        normalizedRawText: String? = null,
        normalizedRawContains: String? = null,
        normalizedRawMatches: String? = null,
        payloadPath: String? = null,
        payloadEquals: JsonElement? = null,
    ): SandboxQuickTestMatrix =
        assertOutput(
            OutputExpectation(
                command = command,
                channel = channel,
                target = target,
                text = text,
                contains = contains,
                textMatches = textMatches,
                count = count,
                order = order,
                normalizedText = normalizedText,
                normalizedContains = normalizedContains,
                normalizedMatches = normalizedMatches,
                rawText = rawText,
                rawContains = rawContains,
                rawTextMatches = rawTextMatches,
                normalizedRawText = normalizedRawText,
                normalizedRawContains = normalizedRawContains,
                normalizedRawMatches = normalizedRawMatches,
                payloadPath = payloadPath,
                payloadEquals = payloadEquals,
            ),
        )

    fun assertOutput(
        channel: OutputChannel,
        command: String? = null,
        target: String? = null,
        text: String? = null,
        contains: String? = null,
        textMatches: String? = null,
        count: Int? = null,
        order: Int? = null,
        normalizedText: String? = null,
        normalizedContains: String? = null,
        normalizedMatches: String? = null,
        rawText: String? = null,
        rawContains: String? = null,
        rawTextMatches: String? = null,
        normalizedRawText: String? = null,
        normalizedRawContains: String? = null,
        normalizedRawMatches: String? = null,
        payloadPath: String? = null,
        payloadEquals: JsonElement? = null,
    ): SandboxQuickTestMatrix =
        assertOutput(
            command = command,
            channel = channel.id,
            target = target,
            text = text,
            contains = contains,
            textMatches = textMatches,
            count = count,
            order = order,
            normalizedText = normalizedText,
            normalizedContains = normalizedContains,
            normalizedMatches = normalizedMatches,
            rawText = rawText,
            rawContains = rawContains,
            rawTextMatches = rawTextMatches,
            normalizedRawText = normalizedRawText,
            normalizedRawContains = normalizedRawContains,
            normalizedRawMatches = normalizedRawMatches,
            payloadPath = payloadPath,
            payloadEquals = payloadEquals,
        )

    /**
     * Applies a structured trace assertion to every scenario.
     */
    fun assertTrace(expectation: TraceExpectation): SandboxQuickTestMatrix =
        apply {
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
        outputs: Int? = null,
        outputContains: String? = null,
        outputTarget: String? = null,
        hasDiff: Boolean? = null,
        diffPath: String? = null,
        diffKind: SnapshotDiffKind? = null,
        diffContains: String? = null,
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
                outputs = outputs,
                outputContains = outputContains,
                outputTarget = outputTarget,
                hasDiff = hasDiff,
                diffPath = diffPath,
                diffKind = diffKind,
                diffContains = diffContains,
            ),
        )

    fun assertTrace(
        root: CommandRoot,
        command: String? = null,
        contains: String? = null,
        success: Boolean? = null,
        fileContains: String? = null,
        function: String? = null,
        count: Int? = null,
        outputs: Int? = null,
        outputContains: String? = null,
        outputTarget: String? = null,
        hasDiff: Boolean? = null,
        diffPath: String? = null,
        diffKind: SnapshotDiffKind? = null,
        diffContains: String? = null,
    ): SandboxQuickTestMatrix =
        assertTrace(
            command = command,
            root = root.id,
            contains = contains,
            success = success,
            fileContains = fileContains,
            function = function,
            count = count,
            outputs = outputs,
            outputContains = outputContains,
            outputTarget = outputTarget,
            hasDiff = hasDiff,
            diffPath = diffPath,
            diffKind = diffKind,
            diffContains = diffContains,
        )

    /**
     * Applies a structured player event trace assertion to every scenario.
     */
    fun assertPlayerEventTrace(expectation: PlayerEventTraceExpectation): SandboxQuickTestMatrix =
        apply {
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
        failedAdvancement: String? = null,
        failedCriterion: String? = null,
        failureContains: String? = null,
        item: String? = null,
        entity: String? = null,
        block: String? = null,
        blockX: Int? = null,
        blockY: Int? = null,
        blockZ: Int? = null,
        recipe: String? = null,
        fromDimension: String? = null,
        toDimension: String? = null,
        damageSource: String? = null,
        damageAmount: Double? = null,
        inputDevice: String? = null,
        inputCode: String? = null,
        inputAction: String? = null,
        target: String? = null,
        targetUuid: String? = null,
        interactionResponse: Boolean? = null,
    ): SandboxQuickTestMatrix =
        assertPlayerEventTrace(
            playerEventTraceExpectation(
                player = player,
                type = type,
                success = success,
                advancement = advancement,
                criterion = criterion,
                count = count,
                failedAdvancement = failedAdvancement,
                failedCriterion = failedCriterion,
                failureContains = failureContains,
                item = item,
                entity = entity,
                block = block,
                blockX = blockX,
                blockY = blockY,
                blockZ = blockZ,
                recipe = recipe,
                fromDimension = fromDimension,
                toDimension = toDimension,
                damageSource = damageSource,
                damageAmount = damageAmount,
                inputDevice = inputDevice,
                inputCode = inputCode,
                inputAction = inputAction,
                target = target,
                targetUuid = targetUuid,
                interactionResponse = interactionResponse,
            ),
        )

    fun assertPlayerEventTrace(
        type: PlayerEventType,
        player: String? = null,
        success: Boolean? = null,
        advancement: String? = null,
        criterion: String? = null,
        count: Int? = null,
        failedAdvancement: String? = null,
        failedCriterion: String? = null,
        failureContains: String? = null,
        item: String? = null,
        entity: String? = null,
        block: String? = null,
        blockX: Int? = null,
        blockY: Int? = null,
        blockZ: Int? = null,
        recipe: String? = null,
        fromDimension: String? = null,
        toDimension: String? = null,
        damageSource: String? = null,
        damageAmount: Double? = null,
        inputDevice: PlayerInputDevice? = null,
        inputCode: String? = null,
        inputAction: PlayerInputAction? = null,
        target: String? = null,
        targetUuid: String? = null,
        interactionResponse: Boolean? = null,
    ): SandboxQuickTestMatrix =
        assertPlayerEventTrace(
            player = player,
            type = type.id,
            success = success,
            advancement = advancement,
            criterion = criterion,
            count = count,
            failedAdvancement = failedAdvancement,
            failedCriterion = failedCriterion,
            failureContains = failureContains,
            item = item,
            entity = entity,
            block = block,
            blockX = blockX,
            blockY = blockY,
            blockZ = blockZ,
            recipe = recipe,
            fromDimension = fromDimension,
            toDimension = toDimension,
            damageSource = damageSource,
            damageAmount = damageAmount,
            inputDevice = inputDevice?.id,
            inputCode = inputCode,
            inputAction = inputAction?.id,
            target = target,
            targetUuid = targetUuid,
            interactionResponse = interactionResponse,
        )

    /**
     * Asserts a before/after snapshot diff entry in every scenario.
     */
    @JvmOverloads
    fun assertSnapshotDiff(
        path: String? = null,
        kind: SnapshotDiffKind? = null,
        contains: String? = null,
        count: Int? = null,
    ): SandboxQuickTestMatrix =
        apply {
            scenarios.values.forEach { it.assertSnapshotDiff(path, kind, contains, count) }
        }

    private fun eachScenario(
        operation: String,
        block: (SandboxQuickTest) -> Unit,
    ) {
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
        val failures =
            reports.flatMap { (version, report) ->
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

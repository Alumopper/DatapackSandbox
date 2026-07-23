package moe.afox.dpsandbox.core

/** Creates an isolated deep copy suitable for a reusable in-memory checkpoint. */
internal fun SandboxWorld.checkpointCopy(): SandboxWorld = SandboxWorld().also { it.replaceStateFrom(this) }

/** Replaces all modeled state while preserving this world's object identity. */
internal fun SandboxWorld.restoreCheckpointState(source: SandboxWorld) {
    replaceStateFrom(source.checkpointCopy())
}

private fun SandboxWorld.replaceStateFrom(source: SandboxWorld) {
    setGameTime(source.gameTime)
    setDayTime(source.dayTime)
    weather = source.weather
    weatherDuration = source.weatherDuration
    difficulty = source.difficulty
    defaultGameMode = source.defaultGameMode
    seed = source.seed
    worldSpawn = source.worldSpawn.checkpointCopy()
    tickRate = source.tickRate
    tickFrozen = source.tickFrozen

    objectives.replaceWith(source.objectives)
    scoreboardObjectiveMetadata.replaceWith(source.scoreboardObjectiveMetadata.mapValues { (_, value) -> value.copy() })
    scoreboardDisplays.replaceWith(source.scoreboardDisplays)
    scores.replaceWith(source.scores)
    storages.replaceWith(source.storages.mapValues { (_, value) -> value.deepCopy() })

    val copiedEntities = source.entities.map(::copyEntity)
    entities.replaceWith(copiedEntities)
    players.clear()
    source.players.forEach { (name, player) ->
        players[name] = (copiedEntities.firstOrNull { it.uuid == player.uuid } ?: copyEntity(player)) as SandboxPlayer
    }

    blocks.replaceWith(
        source.blocks.mapValues { (_, block) ->
            SandboxBlock(block.id, block.properties.toMutableMap(), block.nbt.deepCopy())
        },
    )
    scheduledFunctions.replaceWith(source.scheduledFunctions)
    outputs.replaceWith(source.outputs.map(::copyOutput))
    traces.replaceWith(source.traces.map(::copyTrace))
    playerEventTraces.replaceWith(
        source.playerEventTraces.map { event ->
            event.copy(
                advancements = event.advancements.toList(),
                advancementFailures = event.advancementFailures.toList(),
            )
        },
    )
    bossbars.replaceWith(source.bossbars.mapValues { (_, value) -> value.copy(players = value.players.toSortedSet()) })
    gamerules.replaceWith(source.gamerules)
    teams.replaceWith(
        source.teams.mapValues { (_, value) ->
            value.copy(members = value.members.toSortedSet(), options = value.options.toMutableMap())
        },
    )
    randomSequences.replaceWith(source.randomSequences)
    forcedChunks.replaceWith(source.forcedChunks)
    biomes.replaceWith(source.biomes)
    worldBorder.copyValuesFrom(source.worldBorder)
    currentCommandSource = null
}

private fun copyEntity(source: SandboxEntity): SandboxEntity {
    val target =
        if (source is SandboxPlayer) {
            SandboxPlayer(
                name = source.name,
                uuid = source.uuid,
                position = source.position,
                dimension = source.dimension,
                gameMode = source.gameMode,
                xp = source.xp,
                xpLevels = source.xpLevels,
                health = source.health,
                food = source.food,
            ).also { player ->
                player.inventory.replaceWith(source.inventory.map(::copyItem))
                player.enderItems.replaceWith(source.enderItems.map(::copyItem))
                player.selectedSlot = source.selectedSlot
                player.effects.replaceWith(source.effects)
                player.effectDetails.replaceWith(source.effectDetails.mapValues { (_, value) -> value.copy() })
                player.recipes.replaceWith(source.recipes)
                player.advancementProgress.replaceWith(
                    source.advancementProgress.mapValues { (_, value) -> AdvancementProgress(value.criteria.toMutableMap()) },
                )
                player.stats.replaceWith(source.stats)
                player.inputEvents.replaceWith(source.inputEvents)
                player.lastInput = source.lastInput
                player.spawnPoint = source.spawnPoint?.checkpointCopy()
            }
        } else {
            SandboxEntity(
                uuid = source.uuid,
                type = source.type,
                position = source.position,
                tags = source.tags.toSortedSet(),
                nbt = source.nbt.deepCopy(),
                yaw = source.yaw,
                pitch = source.pitch,
                vehicle = source.vehicle,
                passengers = source.passengers.toSortedSet(),
                dimension = source.dimension,
            )
        }

    if (source is SandboxPlayer) {
        target.tags.replaceWith(source.tags)
        source.nbt.entrySet().forEach { (key, value) -> target.nbt.add(key, value.deepCopy()) }
        target.yaw = source.yaw
        target.pitch = source.pitch
        target.vehicle = source.vehicle
        target.passengers.replaceWith(source.passengers)
    }
    target.ageTicks = source.ageTicks
    target.displayRuntime = source.displayRuntime?.checkpointCopy()
    target.attributes.replaceWith(source.attributes)
    target.attributeModifiers.replaceWith(source.attributeModifiers.mapValues { (_, values) -> values.toMutableMap() })
    target.equipment.replaceWith(source.equipment.mapValues { (_, item) -> copyItem(item) })
    target.activeEffects.replaceWith(source.activeEffects.mapValues { (_, value) -> value.copy() })
    return target
}

private fun DisplayRuntimeState.checkpointCopy(): DisplayRuntimeState =
    copy(target = target.checkpointCopy(), previous = previous.checkpointCopy(), teleport = teleport?.copy())

private fun DisplayInterpolatedValues.checkpointCopy(): DisplayInterpolatedValues =
    copy(
        transformation =
            when (val value = transformation) {
                is DisplayTransformation.Decomposed -> value.copy()
                is DisplayTransformation.Matrix -> value.copy(values = value.values.toList())
            },
    )

private fun copyItem(source: ItemStack): ItemStack =
    ItemStack(source.id, source.count, source.components.deepCopy(), source.nbt.deepCopy())

private fun copyOutput(source: OutputEvent): OutputEvent =
    source.copy(
        targets = source.targets.toList(),
        payload = source.payload?.deepCopy(),
        segments = source.segments.toList(),
        source = source.source?.copy(functionStack = source.source.functionStack.toList()),
    )

private fun copyTrace(source: CommandTraceEvent): CommandTraceEvent =
    source.copy(
        source = source.source?.copy(functionStack = source.source.functionStack.toList()),
        outputEvents = source.outputEvents.map(::copyOutput),
        snapshotDiffs =
            source.snapshotDiffs.map { diff ->
                diff.copy(before = diff.before?.deepCopy(), after = diff.after?.deepCopy())
            },
    )

private fun SpawnPoint.checkpointCopy(): SpawnPoint = copy(position = position.copy())

private fun SandboxWorldBorder.copyValuesFrom(source: SandboxWorldBorder) {
    centerX = source.centerX
    centerZ = source.centerZ
    size = source.size
    targetSize = source.targetSize
    lerpTimeSeconds = source.lerpTimeSeconds
    damageBuffer = source.damageBuffer
    damageAmount = source.damageAmount
    warningDistance = source.warningDistance
    warningTime = source.warningTime
}

private fun <K, V> MutableMap<K, V>.replaceWith(values: Map<K, V>) {
    clear()
    putAll(values)
}

private fun <T> MutableCollection<T>.replaceWith(values: Iterable<T>) {
    clear()
    addAll(values)
}

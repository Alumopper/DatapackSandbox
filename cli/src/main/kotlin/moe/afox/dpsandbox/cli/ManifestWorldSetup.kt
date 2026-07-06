package moe.afox.dpsandbox.cli

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.ChunkPos
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.ItemStack
import moe.afox.dpsandbox.core.PlayerEffect
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxWorldSetup
import moe.afox.dpsandbox.core.chunksInBlockRange
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object ManifestWorldSetup {
    fun apply(world: JsonObject?, sandbox: DatapackSandbox, base: Path) {
        if (world == null) return
        applyWorld(world, sandbox, base, linkedSetOf())
    }

    private fun applyWorld(world: JsonObject, sandbox: DatapackSandbox, base: Path, stack: MutableSet<Path>) {
        referencedWorldFixtures(world, base).forEach { fixture ->
            applyReferencedWorldFixture(fixture, sandbox, stack)
        }

        val setup = SandboxWorldSetup()

        world.get("gameTime")?.let { setup.gameTime(it.asLong) }
        world.get("time")?.let { setup.dayTime(it.asLong) }
        world.get("dayTime")?.let { setup.dayTime(it.asLong) }
        world.get("seed")?.let { setup.seed(it.asLong) }
        world.manifestString("difficulty")?.let { setup.difficulty(it) }
        (world.manifestString("defaultGameMode") ?: world.manifestString("defaultGamemode"))?.let { setup.defaultGameMode(it) }
        world.manifestString("weather")?.let { setup.weather(it, world.get("weatherDuration")?.asInt ?: 0) }
        world.getAsJsonObject("worldSpawn")?.let { setupWorldSpawn(setup, it) }
        world.getAsJsonObject("worldBorder")?.let { setupWorldBorder(setup, it) }

        world.getAsJsonObject("gamerules")?.entrySet()?.forEach { (name, value) ->
            setup.gamerule(name, manifestPrimitiveString(value))
        }

        parseManifestChunks(world.getAsJsonArray("forcedChunks") ?: JsonArray(), "world.forcedChunks").forEach { setup.forcedChunk(it.x, it.z) }
        world.manifestArray("biomes", "world.biomes").forEach { setupBiome(setup, it) }
        world.manifestArray("teams", "world.teams").forEach { setupTeam(setup, it) }
        world.manifestArray("bossbars", "world.bossbars").forEach { setupBossbar(setup, it) }
        parseSaves(world, base).forEach { save -> setup.importSave(save.path, save.chunks, save.dimension, save.includeBlocks, save.includeBlockEntities, save.includeEntities) }
        parseScores(world).forEach { score -> setup.score(score.target, score.objective, score.value, score.criteria) }
        parseStorages(world).forEach { storage -> setup.storage(storage.id, storage.value) }
        world.manifestArray("blocks", "world.blocks").forEach { setupBlock(setup, it) }
        world.manifestArray("entities", "world.entities").forEach { setupEntity(setup, it) }
        world.manifestArray("players", "world.players").forEach { setupPlayer(setup, it) }

        setup.applyTo(sandbox.world, sandbox.profile)
    }

    private fun applyReferencedWorldFixture(path: Path, sandbox: DatapackSandbox, stack: MutableSet<Path>) {
        val normalized = path.toAbsolutePath().normalize()
        if (!stack.add(normalized)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "World fixture cycle detected: $normalized")
        }
        try {
            val root = try {
                JsonParser.parseString(Files.readString(normalized, StandardCharsets.UTF_8)).asJsonObject
            } catch (error: Exception) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid world fixture JSON: $normalized", cause = error)
            }
            val world = when {
                !root.has("world") -> root
                root.get("world").isJsonObject -> root.getAsJsonObject("world")
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "World fixture '$normalized' contains non-object world")
            }
            applyWorld(world, sandbox, normalized.parent ?: Path.of("."), stack)
        } finally {
            stack.remove(normalized)
        }
    }

    private fun referencedWorldFixtures(world: JsonObject, base: Path): List<Path> =
        buildList {
            listOf("extends", "fixture", "fixtures").forEach { name ->
                world.get(name)?.let { addAll(parseFixturePaths(name, it, base)) }
            }
        }

    private fun parseFixturePaths(name: String, value: JsonElement, base: Path): List<Path> =
        when {
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(base.resolve(value.asString).normalize())
            value.isJsonArray -> value.asJsonArray.mapIndexed { index, element ->
                if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.$name[$index] must be a string")
                }
                base.resolve(element.asString).normalize()
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.$name must be a string or array of strings")
        }

    private fun setupBlock(setup: SandboxWorldSetup, element: JsonElement) {
        if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.blocks entries must be objects")
        val block = element.asJsonObject
        val pos = parseManifestBlockPos(block.getAsJsonArray("pos") ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world block requires pos"))
        val properties = linkedMapOf<String, String>()
        block.getAsJsonObject("properties")?.entrySet()?.forEach { (key, value) -> properties[key] = manifestPrimitiveString(value) }
        setup.block(pos, ResourceLocation.parse(block.requiredManifestString("id")), properties, parseManifestNbtObject(block.get("nbt"), "world block nbt"))
    }

    private fun setupEntity(setup: SandboxWorldSetup, element: JsonElement) {
        if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.entities entries must be objects")
        val entity = element.asJsonObject
        setup.entity(
            type = ResourceLocation.parse(entity.requiredManifestString("type")),
            position = entity.getAsJsonArray("pos")?.let { parseManifestPosition(it) } ?: Position.zero,
            tags = entity.manifestStringArray("tags", "world entity tags"),
            nbt = parseManifestNbtObject(entity.get("nbt"), "world entity nbt"),
            yaw = entity.get("yaw")?.asDouble ?: 0.0,
            pitch = entity.get("pitch")?.asDouble ?: 0.0,
            equipment = parseEntityEquipment(entity),
            effects = entity.manifestArray("effects", "world entity effects").map { parseManifestEffect(it, "world entity effects") },
            attributes = parseEntityAttributes(entity),
            dimension = ResourceLocation.parse(entity.manifestString("dimension") ?: "minecraft:overworld"),
        )
    }

    private fun setupPlayer(setup: SandboxWorldSetup, element: JsonElement) {
        if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.players entries must be objects")
        val player = element.asJsonObject
        val position = player.getAsJsonArray("pos")?.let { parseManifestPosition(it) }
            ?: player.getAsJsonArray("position")?.let { parseManifestPosition(it) }
            ?: Position.zero
        setup.player(
            name = player.requiredManifestString("name"),
            x = position.x,
            y = position.y,
            z = position.z,
            dimension = player.manifestString("dimension") ?: "minecraft:overworld",
            gameMode = player.manifestString("gameMode") ?: player.manifestString("gamemode") ?: "survival",
            xp = player.get("xp")?.asInt ?: 0,
            health = player.get("health")?.asDouble ?: 20.0,
            food = player.get("food")?.asInt ?: 20,
            selectedSlot = player.get("selectedSlot")?.asInt ?: 0,
            inventory = player.manifestArray("inventory", "world player inventory").map {
                if (!it.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world player inventory entries must be objects")
                parseManifestItem(it.asJsonObject)
            },
        )
        player.manifestStringArray("recipes", "world player recipes").forEach { setup.playerRecipe(player.requiredManifestString("name"), it) }
        player.getAsJsonObject("stats")?.entrySet()?.forEach { (id, value) -> setup.playerStat(player.requiredManifestString("name"), id, value.asInt) }
        player.manifestArray("effects", "world player effects").forEach { setupPlayerEffect(setup, player.requiredManifestString("name"), it) }
        player.getAsJsonObject("spawn")?.let { setupPlayerSpawn(setup, player.requiredManifestString("name"), it) }
    }

    private fun setupWorldSpawn(setup: SandboxWorldSetup, spawn: JsonObject) {
        val position = spawn.getAsJsonArray("pos")?.let { parseManifestPosition(it) }
            ?: spawn.getAsJsonArray("position")?.let { parseManifestPosition(it) }
            ?: Position.zero
        setup.worldSpawn(
            x = position.x,
            y = position.y,
            z = position.z,
            dimension = spawn.manifestString("dimension") ?: "minecraft:overworld",
            angle = spawn.get("angle")?.asDouble,
            forced = spawn.get("forced")?.asBoolean ?: false,
        )
    }

    private fun setupWorldBorder(setup: SandboxWorldSetup, border: JsonObject) {
        setup.worldBorder(
            centerX = border.get("centerX")?.asDouble,
            centerZ = border.get("centerZ")?.asDouble,
            size = border.get("size")?.asDouble,
            targetSize = border.get("targetSize")?.asDouble,
            lerpTimeSeconds = border.get("lerpTimeSeconds")?.asLong,
            damageBuffer = border.get("damageBuffer")?.asDouble,
            damageAmount = border.get("damageAmount")?.asDouble,
            warningDistance = border.get("warningDistance")?.asInt,
            warningTime = border.get("warningTime")?.asInt,
        )
    }

    private fun setupBiome(setup: SandboxWorldSetup, element: JsonElement) {
        if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.biomes entries must be objects")
        val biome = element.asJsonObject
        val pos = parseManifestBlockPos(biome.getAsJsonArray("pos") ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world biome requires pos"))
        setup.biome(pos.x, pos.y, pos.z, biome.requiredManifestString("id"))
    }

    private fun setupTeam(setup: SandboxWorldSetup, element: JsonElement) {
        if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.teams entries must be objects")
        val team = element.asJsonObject
        val options = linkedMapOf<String, String>()
        team.getAsJsonObject("options")?.entrySet()?.forEach { (key, value) -> options[key] = manifestPrimitiveString(value) }
        setup.team(
            name = team.requiredManifestString("name"),
            displayName = team.manifestString("displayName") ?: team.requiredManifestString("name"),
            members = team.manifestStringArray("members", "world team members"),
            options = options,
        )
    }

    private fun setupBossbar(setup: SandboxWorldSetup, element: JsonElement) {
        if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.bossbars entries must be objects")
        val bossbar = element.asJsonObject
        setup.bossbar(
            id = bossbar.requiredManifestString("id"),
            name = bossbar.manifestString("name") ?: bossbar.requiredManifestString("id"),
            value = bossbar.get("value")?.asInt ?: 0,
            max = bossbar.get("max")?.asInt ?: 100,
            color = bossbar.manifestString("color") ?: "white",
            style = bossbar.manifestString("style") ?: "progress",
            visible = bossbar.get("visible")?.asBoolean ?: true,
            players = bossbar.manifestStringArray("players", "world bossbar players"),
        )
    }

    private fun setupPlayerEffect(setup: SandboxWorldSetup, name: String, element: JsonElement) {
        val effect = parseManifestEffect(element, "world player effects")
        setup.playerEffect(
            name = name,
            effect = effect.id.toString(),
            durationTicks = effect.durationTicks,
            amplifier = effect.amplifier,
            hideParticles = effect.hideParticles,
        )
    }

    private fun parseManifestEffect(element: JsonElement, label: String): PlayerEffect =
        when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                PlayerEffect(ResourceLocation.parse(element.asString))
            element.isJsonObject -> {
                val effect = element.asJsonObject
                PlayerEffect(
                    id = ResourceLocation.parse(effect.requiredManifestString("id")),
                    durationTicks = effect.get("durationTicks")?.asInt ?: effect.get("duration")?.asInt ?: -1,
                    amplifier = effect.get("amplifier")?.asInt ?: 0,
                    hideParticles = effect.get("hideParticles")?.asBoolean ?: false,
                )
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entries must be strings or objects")
        }

    private fun parseEntityEquipment(entity: JsonObject): Map<String, ItemStack> {
        val equipment = entity.getAsJsonObject("equipment") ?: return emptyMap()
        return equipment.entrySet().associate { (slot, value) ->
            if (!value.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world entity equipment '$slot' must be an item object")
            }
            slot to parseManifestItem(value.asJsonObject)
        }
    }

    private fun parseEntityAttributes(entity: JsonObject): Map<String, Double> {
        val attributes = entity.getAsJsonObject("attributes") ?: return emptyMap()
        return attributes.entrySet().associate { (id, value) -> id to value.asDouble }
    }

    private fun setupPlayerSpawn(setup: SandboxWorldSetup, name: String, spawn: JsonObject) {
        val position = spawn.getAsJsonArray("pos")?.let { parseManifestPosition(it) }
            ?: spawn.getAsJsonArray("position")?.let { parseManifestPosition(it) }
            ?: Position.zero
        setup.playerSpawn(
            name = name,
            x = position.x,
            y = position.y,
            z = position.z,
            dimension = spawn.manifestString("dimension") ?: "minecraft:overworld",
            angle = spawn.get("angle")?.asDouble,
            forced = spawn.get("forced")?.asBoolean ?: false,
        )
    }

    private fun parseSaves(world: JsonObject, base: Path): List<ManifestSaveImport> {
        val saves = mutableListOf<JsonObject>()
        world.getAsJsonObject("save")?.let { saves += it }
        world.manifestArray("saves", "world.saves").forEach {
            if (!it.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.saves entries must be objects")
            saves += it.asJsonObject
        }
        return saves.map { save ->
            val path = base.resolve(save.requiredManifestString("path")).normalize()
            val dimension = save.manifestString("dimension") ?: "minecraft:overworld"
            val chunks = when {
                save.has("chunks") -> parseManifestChunks(save.getAsJsonArray("chunks"), "world.save.chunks")
                save.has("chunk") -> listOf(parseManifestChunk(save.getAsJsonArray("chunk"), "world.save.chunk"))
                save.has("from") && save.has("to") -> chunksInBlockRange(
                    parseManifestBlockPos(save.getAsJsonArray("from"), "world.save.from"),
                    parseManifestBlockPos(save.getAsJsonArray("to"), "world.save.to"),
                )
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.save requires chunks, chunk, or from/to")
            }
            ManifestSaveImport(
                path = path,
                dimension = dimension,
                chunks = chunks,
                includeBlocks = save.get("includeBlocks")?.asBoolean ?: true,
                includeBlockEntities = save.get("includeBlockEntities")?.asBoolean ?: true,
                includeEntities = save.get("includeEntities")?.asBoolean ?: true,
            )
        }
    }

    private fun parseScores(world: JsonObject): List<ManifestScoreSetup> =
        world.manifestArray("scores", "world.scores").map { element ->
            if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.scores entries must be objects")
            val score = element.asJsonObject
            ManifestScoreSetup(
                target = score.requiredManifestString("target"),
                objective = score.requiredManifestString("objective"),
                value = score.requiredManifestInt("value"),
                criteria = score.manifestString("criteria") ?: "dummy",
            )
        }

    private fun parseStorages(world: JsonObject): List<ManifestStorageSetup> {
        val result = mutableListOf<ManifestStorageSetup>()
        world.manifestArray("storages", "world.storages").forEach { element ->
            if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world.storages entries must be objects")
            val storage = element.asJsonObject
            result += ManifestStorageSetup(
                id = ResourceLocation.parse(storage.requiredManifestString("id")),
                value = storage.get("value") ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "world storage requires value"),
            )
        }
        world.getAsJsonObject("storage")?.entrySet()?.forEach { (id, value) ->
            result += ManifestStorageSetup(ResourceLocation.parse(id), value)
        }
        return result
    }

    private data class ManifestSaveImport(
        val path: Path,
        val dimension: String,
        val chunks: List<ChunkPos>,
        val includeBlocks: Boolean,
        val includeBlockEntities: Boolean,
        val includeEntities: Boolean,
    )

    private data class ManifestScoreSetup(
        val target: String,
        val objective: String,
        val value: Int,
        val criteria: String,
    )

    private data class ManifestStorageSetup(
        val id: ResourceLocation,
        val value: JsonElement,
    )
}

package moe.afox.dpsandbox.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.ItemStack
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.PlayerInput
import moe.afox.dpsandbox.core.PredicateContext
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.TraceAssertions
import moe.afox.dpsandbox.core.TraceExpectation
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class ManifestResult(
    val path: Path,
    val passed: Boolean,
    val messages: List<String>,
    val outputs: List<OutputEvent> = emptyList(),
    val attempts: List<ManifestAttemptResult> = emptyList(),
)

data class ManifestAttemptResult(
    val version: String,
    val packs: List<Path>,
    val passed: Boolean,
    val messages: List<String>,
    val outputs: List<OutputEvent> = emptyList(),
)

data class ManifestOptions(
    val seed: Long = 0,
    val verbose: Boolean = false,
    val snapshotOnFail: Boolean = false,
    val snapshotDiffOnFail: Boolean = false,
    val unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
)

object ManifestRunner {
    private data class ManifestRunConfig(
        val version: String,
        val packs: List<Path>,
    )

    fun discover(inputs: List<Path>): List<Path> {
        val manifests = mutableListOf<Path>()
        inputs.forEach { input ->
            val path = input.toAbsolutePath().normalize()
            when {
                path.isRegularFile() -> manifests.add(path)
                path.isDirectory() -> Files.walk(path).use { walk ->
                    walk.filter { it.isRegularFile() && it.name.endsWith(".dps.json") }
                        .sorted()
                        .forEach { manifests.add(it.toAbsolutePath().normalize()) }
                }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Input is not a file or directory: $path")
            }
        }
        if (manifests.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "No .dps.json manifests found")
        }
        return manifests
    }

    fun run(path: Path, options: ManifestOptions = ManifestOptions()): ManifestResult {
        val json = try {
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid manifest JSON: ${path.toAbsolutePath().normalize()}", cause = error)
        }

        val base = path.parent ?: Path.of(".")
        val configs = runConfigs(json, base, path)
        val unsupportedMode = json.manifestString("unsupported")?.let(::unsupportedFeatureMode) ?: options.unsupportedFeatureMode
        val attempts = configs.map { config ->
            runOne(path, json, config, unsupportedMode, options)
        }

        val multiVersion = attempts.size > 1
        val messages = attempts.flatMap { attempt ->
            attempt.messages.map { message ->
                if (multiVersion) "[${attempt.version}] $message" else message
            }
        }
        return ManifestResult(
            path = path,
            passed = attempts.all { it.passed },
            messages = messages,
            outputs = attempts.flatMap { it.outputs },
            attempts = attempts,
        )
    }

    internal fun evaluateAssertions(assertions: List<JsonObject>, sandbox: DatapackSandbox): List<String> =
        assertions.flatMapIndexed { index, assertion ->
            evaluateAssertion(assertion, sandbox).map { "assertion ${index + 1}: $it" }
        }

    private fun runOne(
        path: Path,
        json: JsonObject,
        config: ManifestRunConfig,
        unsupportedMode: UnsupportedFeatureMode,
        options: ManifestOptions,
    ): ManifestAttemptResult {
        val sandbox = createSandbox(config.version, config.packs, unsupportedFeatureMode = unsupportedMode)
        ManifestWorldSetup.apply(json.getAsJsonObject("world"), sandbox, path.parent ?: Path.of("."))
        val beforeSnapshot = sandbox.snapshotJson()
        json.manifestArray("steps").forEachIndexed { index, step ->
            if (!step.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest steps must be objects: $path")
            }
            try {
                runStep(step.asJsonObject, sandbox, options, path.parent ?: Path.of("."))
            } catch (error: SandboxException) {
                throw SandboxException(error.code, "Step ${index + 1} failed for ${config.version}: ${error.message}", error.location, error.version, error.command, error)
            }
        }

        val failures = mutableListOf<String>()
        json.manifestArray("assertions").forEach { assertion ->
            if (!assertion.isJsonObject) {
                failures += "Assertion must be an object"
            } else {
                failures += evaluateAssertion(assertion.asJsonObject, sandbox)
            }
        }
        if (failures.isNotEmpty() && options.snapshotOnFail) {
            failures += "snapshot: ${sandbox.snapshotString()}"
        }
        if (failures.isNotEmpty() && options.snapshotDiffOnFail) {
            failures += "snapshot diff:${System.lineSeparator()}${SnapshotDiff.render(SnapshotDiff.diff(beforeSnapshot, sandbox.snapshotJson()))}"
        }

        return ManifestAttemptResult(
            version = config.version,
            packs = config.packs,
            passed = failures.isEmpty(),
            messages = failures,
            outputs = sandbox.world.outputs.toList(),
        )
    }

    private fun runConfigs(json: JsonObject, base: Path, path: Path): List<ManifestRunConfig> {
        if (json.has("version") && json.has("versions")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must use either 'version' or 'versions', not both: $path")
        }

        val versions = when {
            json.has("versions") -> json.manifestStringArray("versions")
            json.has("version") -> listOf(json.requiredManifestString("version"))
            else -> listOf(VersionProfiles.default.id)
        }.distinct()

        if (versions.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain at least one version: $path")
        }

        val packsElement = json.get("packs")
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain packs: $path")
        return versions.map { version ->
            ManifestRunConfig(
                version = version,
                packs = parsePacksForVersion(packsElement, version, base, path),
            )
        }
    }

    private fun parsePacksForVersion(packsElement: JsonElement, version: String, base: Path, path: Path): List<Path> {
        val packEntries = when {
            packsElement.isJsonArray -> packsElement.asJsonArray.toList()
            packsElement.isJsonObject -> {
                val packsObject = packsElement.asJsonObject
                val value = packsObject.get(version) ?: packsObject.get("default")
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs object is missing entry for version '$version': $path")
                if (!value.isJsonArray) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs for version '$version' must be an array: $path")
                }
                value.asJsonArray.toList()
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs must be an array or object: $path")
        }

        val packs = packEntries.map { entry ->
            if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest pack entries must be strings: $path")
            }
            base.resolve(entry.asString).normalize()
        }
        if (packs.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain at least one pack for version '$version': $path")
        }
        return packs
    }

    private fun runStep(step: JsonObject, sandbox: DatapackSandbox, options: ManifestOptions, base: Path) {
        when {
            step.has("load") && step.get("load").asBoolean -> sandbox.runLoad()
            step.has("ticks") -> sandbox.runTicks(step.get("ticks").asInt)
            step.has("function") -> sandbox.runFunction(step.get("function").asString)
            step.has("command") -> sandbox.executeCommand(step.get("command").asString)
            step.has("commands") -> runCommandLines(
                step.manifestStringArray("commands", "Manifest step 'commands'"),
                sandbox,
                step.manifestString("source") ?: "<manifest:commands>",
            )
            step.has("functionText") -> runCommandLines(
                step.requiredManifestString("functionText").lines(),
                sandbox,
                step.manifestString("source") ?: "<manifest:functionText>",
            )
            step.has("mcfunction") -> {
                val file = base.resolve(step.requiredManifestString("mcfunction")).normalize()
                runCommandLines(Files.readAllLines(file, StandardCharsets.UTF_8), sandbox, file.toString())
            }
            step.has("player") -> runPlayerStep(step.getAsJsonObject("player"), sandbox)
            step.has("block") -> runBlockStep(step.getAsJsonObject("block"), sandbox)
            step.has("event") -> sandbox.handlePlayerEvent(parseEvent(step.getAsJsonObject("event"), sandbox))
            step.has("loot") -> {
                val loot = step.getAsJsonObject("loot")
                sandbox.generateLoot(
                    ResourceLocation.parse(loot.requiredManifestString("table")),
                    ResourceLocation.parse(loot.manifestString("context") ?: "minecraft:empty"),
                    loot.manifestString("player")?.let { sandbox.world.requirePlayer(it) },
                    loot.get("seed")?.asLong ?: options.seed,
                )
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Step must contain load, ticks, function, command, commands, functionText, mcfunction, player, block, event, or loot")
        }
    }

    private fun runCommandLines(lines: List<String>, sandbox: DatapackSandbox, sourceName: String) {
        lines.mapIndexedNotNull { index, raw ->
            val command = raw.removePrefix("\uFEFF").trim()
            if (command.isBlank() || command.startsWith("#")) {
                null
            } else {
                val normalized = command.removePrefix("/")
                normalized to SourceLocation(file = sourceName, line = index + 1, command = normalized)
            }
        }.forEach { (command, location) ->
            sandbox.executeCommand(command, location)
        }
    }

    private fun runPlayerStep(player: JsonObject, sandbox: DatapackSandbox) {
        val name = player.requiredManifestString("name")
        val sandboxPlayer = sandbox.createPlayer(name)
        player.manifestArray("inventory").forEach { entry ->
            if (!entry.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Inventory entries must be objects")
            sandboxPlayer.inventory += parseManifestItem(entry.asJsonObject)
        }
        player.getAsJsonArray("position")?.let { sandboxPlayer.position = parseManifestPosition(it) }
        player.manifestString("dimension")?.let { sandboxPlayer.dimension = ResourceLocation.parse(it) }
        player.get("xp")?.let { sandboxPlayer.xp = it.asInt }
    }

    private fun runBlockStep(block: JsonObject, sandbox: DatapackSandbox) {
        val pos = parseManifestBlockPos(block.getAsJsonArray("pos") ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Block step requires pos"))
        val id = ResourceLocation.parse(block.requiredManifestString("id"))
        val properties = linkedMapOf<String, String>()
        block.getAsJsonObject("properties")?.entrySet()?.forEach { (key, value) -> properties[key] = value.asString }
        val nbt = parseManifestNbtObject(block.get("nbt"), "block nbt") ?: JsonObject()
        val sandboxBlock = SandboxBlock(id, properties)
        if (nbt.entrySet().isNotEmpty()) {
            val updated = sandboxBlock.fullNbt(pos, sandbox.profile)
            JsonPaths.merge(updated, null, nbt)
            sandboxBlock.writeFullNbt(pos, sandbox.profile, updated)
        }
        sandbox.world.setBlock(pos, sandboxBlock)
    }

    private fun evaluateAssertion(assertion: JsonObject, sandbox: DatapackSandbox): List<String> {
        val failures = mutableListOf<String>()
        when {
            assertion.has("score") -> {
                val score = assertion.getAsJsonObject("score")
                val target = score.requiredManifestString("target")
                val objective = score.requiredManifestString("objective")
                val expected = score.get("equals").asInt
                val actual = sandbox.world.getScore(target, objective)
                if (actual != expected) {
                    failures += "score $target $objective expected $expected but was $actual"
                }
            }
            assertion.has("storage") -> {
                val storage = assertion.getAsJsonObject("storage")
                val id = ResourceLocation.parse(storage.requiredManifestString("id"))
                val path = storage.requiredManifestString("path")
                val expected = storage.get("equals")
                val actual = sandbox.world.storages[id]?.let { JsonPaths.get(it, path) }
                if (actual != expected) {
                    failures += "storage $id $path expected ${JsonValues.render(expected)} but was ${actual?.let(JsonValues::render) ?: "<missing>"}"
                }
            }
            assertion.has("entityCount") -> {
                val entity = assertion.getAsJsonObject("entityCount")
                val expected = entity.get("equals").asInt
                val actual = sandbox.world.entities.count { sandboxEntity ->
                    val typeOk = entity.manifestString("type")?.let { sandboxEntity.type == ResourceLocation.parse(it) } ?: true
                    val tagOk = entity.manifestString("tag")?.let { it in sandboxEntity.tags } ?: true
                    typeOk && tagOk
                }
                if (actual != expected) {
                    failures += "entityCount expected $expected but was $actual"
                }
            }
            assertion.has("world") -> {
                failures += evaluateWorldAssertion(assertion.getAsJsonObject("world"), sandbox)
            }
            assertion.has("player") -> {
                val player = assertion.getAsJsonObject("player")
                val actual = sandbox.world.players[player.requiredManifestString("name")]
                if (actual == null) {
                    failures += "player ${player.requiredManifestString("name")} expected to exist"
                } else {
                    player.get("xp")?.let { if (actual.xp != it.asInt) failures += "player ${actual.name} xp expected ${it.asInt} but was ${actual.xp}" }
                    player.get("inventoryCount")?.let { if (actual.inventory.size != it.asInt) failures += "player ${actual.name} inventoryCount expected ${it.asInt} but was ${actual.inventory.size}" }
                    player.manifestString("dimension")?.let { if (actual.dimension != ResourceLocation.parse(it)) failures += "player ${actual.name} dimension expected $it but was ${actual.dimension}" }
                    player.manifestString("gameMode")?.let { if (actual.gameMode != it) failures += "player ${actual.name} gameMode expected $it but was ${actual.gameMode}" }
                    player.manifestString("gamemode")?.let { if (actual.gameMode != it) failures += "player ${actual.name} gameMode expected $it but was ${actual.gameMode}" }
                    player.get("health")?.let { if (actual.health != it.asDouble) failures += "player ${actual.name} health expected ${it.asDouble} but was ${actual.health}" }
                    player.get("food")?.let { if (actual.food != it.asInt) failures += "player ${actual.name} food expected ${it.asInt} but was ${actual.food}" }
                    player.get("selectedSlot")?.let { if (actual.selectedSlot != it.asInt) failures += "player ${actual.name} selectedSlot expected ${it.asInt} but was ${actual.selectedSlot}" }
                    player.manifestString("recipe")?.let { if (ResourceLocation.parse(it) !in actual.recipes) failures += "player ${actual.name} expected recipe $it" }
                    player.manifestString("effect")?.let { if (ResourceLocation.parse(it) !in actual.effects) failures += "player ${actual.name} expected effect $it" }
                    player.getAsJsonObject("stat")?.let { stat ->
                        val id = ResourceLocation.parse(stat.requiredManifestString("id"))
                        val expected = stat.get("equals").asInt
                        val actualValue = actual.stats[id] ?: 0
                        if (actualValue != expected) failures += "player ${actual.name} stat $id expected $expected but was $actualValue"
                    }
                    player.getAsJsonArray("position")?.let {
                        val expected = parseManifestPosition(it)
                        if (actual.position != expected) failures += "player ${actual.name} position expected $expected but was ${actual.position}"
                    }
                    player.getAsJsonObject("lastInput")?.let { expected ->
                        val input = actual.lastInput
                        if (input == null) {
                            failures += "player ${actual.name} lastInput expected ${JsonValues.render(expected)} but was <none>"
                        } else {
                            expected.manifestString("device")?.let { if (input.device != it) failures += "player ${actual.name} lastInput device expected $it but was ${input.device}" }
                            expected.manifestString("code")?.let { if (input.code != it) failures += "player ${actual.name} lastInput code expected $it but was ${input.code}" }
                            expected.manifestString("action")?.let { if (input.action != it) failures += "player ${actual.name} lastInput action expected $it but was ${input.action}" }
                        }
                    }
                    player.getAsJsonObject("spawn")?.let { spawn ->
                        val expected = spawn.getAsJsonArray("pos")?.let { parseManifestPosition(it) }
                            ?: spawn.getAsJsonArray("position")?.let { parseManifestPosition(it) }
                        if (expected != null && actual.spawnPoint?.position != expected) failures += "player ${actual.name} spawn position expected $expected but was ${actual.spawnPoint?.position}"
                        spawn.manifestString("dimension")?.let { if (actual.spawnPoint?.dimension != ResourceLocation.parse(it)) failures += "player ${actual.name} spawn dimension expected $it but was ${actual.spawnPoint?.dimension}" }
                    }
                }
            }
            assertion.has("team") -> {
                failures += evaluateTeamAssertion(assertion.getAsJsonObject("team"), sandbox)
            }
            assertion.has("bossbar") -> {
                failures += evaluateBossbarAssertion(assertion.getAsJsonObject("bossbar"), sandbox)
            }
            assertion.has("block") -> {
                val block = assertion.getAsJsonObject("block")
                val posArray = block.getAsJsonArray("pos")
                if (posArray == null) {
                    failures += "block assertion requires pos"
                } else {
                    val pos = parseManifestBlockPos(posArray)
                    val actual = sandbox.world.block(pos)
                    block.get("exists")?.let { expected ->
                        if ((actual != null) != expected.asBoolean) failures += "block $pos exists expected ${expected.asBoolean} but was ${actual != null}"
                    }
                    block.manifestString("id")?.let { expected ->
                        if (actual?.id != ResourceLocation.parse(expected)) failures += "block $pos id expected $expected but was ${actual?.id ?: "void"}"
                    }
                    block.getAsJsonObject("nbt")?.let { expected ->
                        val path = expected.manifestString("path")
                        val expectedValue = expected.get("equals")
                        val actualValue = actual?.fullNbt(pos, sandbox.profile)?.let { JsonPaths.get(it, path) }
                        if (expectedValue != null && actualValue != expectedValue) {
                            failures += "block $pos nbt ${path ?: "<root>"} expected ${JsonValues.render(expectedValue)} but was ${actualValue?.let(JsonValues::render) ?: "<missing>"}"
                        }
                    }
                }
            }
            assertion.has("advancement") -> {
                val advancement = assertion.getAsJsonObject("advancement")
                val player = sandbox.world.requirePlayer(advancement.requiredManifestString("player"))
                val id = ResourceLocation.parse(advancement.requiredManifestString("id"))
                val definition = sandbox.datapack.advancements[id]
                val progress = player.advancementProgress[id]
                val done = definition != null && progress?.isDone(definition.requirements) == true
                advancement.get("done")?.let { if (done != it.asBoolean) failures += "advancement $id done expected ${it.asBoolean} but was $done" }
                advancement.manifestString("criterion")?.let { criterion ->
                    val criterionDone = progress?.criteria?.get(criterion) == true
                    advancement.get("criterionDone")?.let { expected ->
                        if (criterionDone != expected.asBoolean) failures += "advancement $id criterion $criterion expected ${expected.asBoolean} but was $criterionDone"
                    }
                }
            }
            assertion.has("predicate") -> {
                val predicate = assertion.getAsJsonObject("predicate")
                val result = sandbox.predicates.test(ResourceLocation.parse(predicate.requiredManifestString("id")), PredicateContext(world = sandbox.world, player = predicate.manifestString("player")?.let { sandbox.world.requirePlayer(it) }))
                val expected = predicate.get("equals")?.asBoolean ?: true
                if (result != expected) failures += "predicate ${predicate.requiredManifestString("id")} expected $expected but was $result"
            }
            assertion.has("loot") -> {
                val loot = assertion.getAsJsonObject("loot")
                val result = sandbox.generateLoot(
                    ResourceLocation.parse(loot.requiredManifestString("table")),
                    ResourceLocation.parse(loot.manifestString("context") ?: "minecraft:empty"),
                    loot.manifestString("player")?.let { sandbox.world.requirePlayer(it) },
                    loot.get("seed")?.asLong ?: 0,
                )
                loot.get("count")?.let { if (result.items.size != it.asInt) failures += "loot count expected ${it.asInt} but was ${result.items.size}" }
                loot.manifestString("item")?.let { expected ->
                    if (result.items.none { it.id == ResourceLocation.parse(expected) }) failures += "loot expected item $expected but got ${result.items.map { it.id }}"
                }
            }
            assertion.has("item") -> {
                failures += evaluateItemAssertion(assertion.getAsJsonObject("item"), sandbox)
            }
            assertion.has("trace") -> {
                failures += evaluateTraceAssertion(assertion.getAsJsonObject("trace"), sandbox)
            }
            assertion.has("eventTrace") -> {
                failures += evaluateEventTraceAssertion(assertion.getAsJsonObject("eventTrace"), sandbox)
            }
            assertion.has("output") -> {
                failures += ManifestOutputAssertions.evaluate(assertion.getAsJsonObject("output"), sandbox)
            }
            else -> failures += "Unknown assertion kind: ${assertion.keySet().joinToString()}"
        }
        return failures
    }

    private fun evaluateWorldAssertion(world: JsonObject, sandbox: DatapackSandbox): List<String> {
        val failures = mutableListOf<String>()
        world.get("gameTime")?.let { if (sandbox.world.gameTime != it.asLong) failures += "world gameTime expected ${it.asLong} but was ${sandbox.world.gameTime}" }
        world.get("dayTime")?.let { if (sandbox.world.dayTime != it.asLong) failures += "world dayTime expected ${it.asLong} but was ${sandbox.world.dayTime}" }
        world.get("time")?.let { if (sandbox.world.dayTime != it.asLong) failures += "world dayTime expected ${it.asLong} but was ${sandbox.world.dayTime}" }
        world.get("seed")?.let { if (sandbox.world.seed != it.asLong) failures += "world seed expected ${it.asLong} but was ${sandbox.world.seed}" }
        world.manifestString("weather")?.let { if (sandbox.world.weather != it) failures += "world weather expected $it but was ${sandbox.world.weather}" }
        world.get("weatherDuration")?.let { if (sandbox.world.weatherDuration != it.asInt) failures += "world weatherDuration expected ${it.asInt} but was ${sandbox.world.weatherDuration}" }
        world.manifestString("difficulty")?.let { if (sandbox.world.difficulty != it) failures += "world difficulty expected $it but was ${sandbox.world.difficulty}" }
        (world.manifestString("defaultGameMode") ?: world.manifestString("defaultGamemode"))?.let {
            if (sandbox.world.defaultGameMode != it) failures += "world defaultGameMode expected $it but was ${sandbox.world.defaultGameMode}"
        }
        world.getAsJsonArray("forcedChunk")?.let {
            val chunk = parseManifestChunk(it, "world assertion forcedChunk")
            if (chunk !in sandbox.world.forcedChunks) failures += "world expected forced chunk ${chunk.x},${chunk.z}"
        }
        world.getAsJsonObject("biome")?.let { biome ->
            val pos = parseManifestBlockPos(biome.getAsJsonArray("pos"), "world assertion biome pos")
            val expected = ResourceLocation.parse(biome.requiredManifestString("id"))
            val actual = sandbox.world.biomes[pos]
            if (actual != expected) failures += "world biome $pos expected $expected but was ${actual ?: "<missing>"}"
        }
        world.getAsJsonObject("worldSpawn")?.let { spawn ->
            val expected = spawn.getAsJsonArray("pos")?.let { parseManifestPosition(it) }
                ?: spawn.getAsJsonArray("position")?.let { parseManifestPosition(it) }
            if (expected != null && sandbox.world.worldSpawn.position != expected) failures += "world spawn position expected $expected but was ${sandbox.world.worldSpawn.position}"
            spawn.manifestString("dimension")?.let { if (sandbox.world.worldSpawn.dimension != ResourceLocation.parse(it)) failures += "world spawn dimension expected $it but was ${sandbox.world.worldSpawn.dimension}" }
        }
        return failures
    }

    private fun evaluateTeamAssertion(team: JsonObject, sandbox: DatapackSandbox): List<String> {
        val name = team.requiredManifestString("name")
        val actual = sandbox.world.teams[name]
        val exists = team.get("exists")?.asBoolean ?: true
        if (!exists) return if (actual == null) emptyList() else listOf("team $name expected missing but exists")
        if (actual == null) return listOf("team $name expected to exist")
        val failures = mutableListOf<String>()
        team.manifestString("displayName")?.let { if (actual.displayName != it) failures += "team $name displayName expected $it but was ${actual.displayName}" }
        team.manifestString("member")?.let { if (it !in actual.members) failures += "team $name expected member $it" }
        team.get("memberCount")?.let { if (actual.members.size != it.asInt) failures += "team $name memberCount expected ${it.asInt} but was ${actual.members.size}" }
        team.getAsJsonObject("option")?.let { option ->
            val key = option.requiredManifestString("name")
            val expected = option.requiredManifestString("equals")
            val actualValue = actual.options[key]
            if (actualValue != expected) failures += "team $name option $key expected $expected but was ${actualValue ?: "<missing>"}"
        }
        return failures
    }

    private fun evaluateBossbarAssertion(bossbar: JsonObject, sandbox: DatapackSandbox): List<String> {
        val id = ResourceLocation.parse(bossbar.requiredManifestString("id"))
        val actual = sandbox.world.bossbars[id]
        val exists = bossbar.get("exists")?.asBoolean ?: true
        if (!exists) return if (actual == null) emptyList() else listOf("bossbar $id expected missing but exists")
        if (actual == null) return listOf("bossbar $id expected to exist")
        val failures = mutableListOf<String>()
        bossbar.manifestString("name")?.let { if (actual.name != it) failures += "bossbar $id name expected $it but was ${actual.name}" }
        bossbar.get("value")?.let { if (actual.value != it.asInt) failures += "bossbar $id value expected ${it.asInt} but was ${actual.value}" }
        bossbar.get("max")?.let { if (actual.max != it.asInt) failures += "bossbar $id max expected ${it.asInt} but was ${actual.max}" }
        bossbar.manifestString("color")?.let { if (actual.color != it) failures += "bossbar $id color expected $it but was ${actual.color}" }
        bossbar.manifestString("style")?.let { if (actual.style != it) failures += "bossbar $id style expected $it but was ${actual.style}" }
        bossbar.get("visible")?.let { if (actual.visible != it.asBoolean) failures += "bossbar $id visible expected ${it.asBoolean} but was ${actual.visible}" }
        bossbar.manifestString("player")?.let { if (it !in actual.players) failures += "bossbar $id expected player $it" }
        return failures
    }

    private fun evaluateItemAssertion(item: JsonObject, sandbox: DatapackSandbox): List<String> {
        val player = sandbox.world.requirePlayer(item.requiredManifestString("player"))
        val slot = item.get("slot")?.asInt
        val candidates = slot?.let { player.inventory.getOrNull(it)?.let(::listOf) ?: emptyList() } ?: player.inventory
        val expectedId = item.manifestString("id")?.let(ResourceLocation::parse)
        val expectedCount = item.get("count")?.asInt
        val exists = item.get("exists")?.asBoolean ?: true
        val matches = candidates.filter { stack ->
            (expectedId == null || stack.id == expectedId) &&
                (expectedCount == null || stack.count == expectedCount) &&
                itemPathMatches(stack.components, item.getAsJsonObject("components")) &&
                itemPathMatches(stack.nbt, item.getAsJsonObject("nbt"))
        }
        if (exists && matches.isEmpty()) {
            return listOf("item for player ${player.name} expected ${describeItemExpectation(item)} but inventory was ${player.inventory.map { "${it.id}x${it.count}" }}")
        }
        if (!exists && matches.isNotEmpty()) {
            return listOf("item for player ${player.name} expected missing ${describeItemExpectation(item)} but found ${matches.map { "${it.id}x${it.count}" }}")
        }
        return emptyList()
    }

    private fun itemPathMatches(root: JsonObject, expectation: JsonObject?): Boolean {
        if (expectation == null) return true
        val path = expectation.manifestString("path")
        val actual = JsonPaths.get(root, path)
        val expected = expectation.get("equals")
        return when {
            expectation.get("exists")?.asBoolean == true -> actual != null
            expectation.get("exists")?.asBoolean == false -> actual == null
            expected != null -> actual == expected
            else -> actual != null
        }
    }

    private fun describeItemExpectation(item: JsonObject): String =
        listOfNotNull(
            item.manifestString("id")?.let { "id=$it" },
            item.get("count")?.let { "count=${it.asInt}" },
            item.get("slot")?.let { "slot=${it.asInt}" },
        ).ifEmpty { listOf("<any item>") }.joinToString(", ")

    private fun evaluateTraceAssertion(trace: JsonObject, sandbox: DatapackSandbox): List<String> {
        return TraceAssertions.failures(
            traces = sandbox.world.traces,
            expectation = TraceExpectation(
                command = trace.manifestString("command"),
                root = trace.manifestString("root"),
                contains = trace.manifestString("contains"),
                success = trace.get("success")?.asBoolean,
                fileContains = trace.manifestString("fileContains"),
                function = trace.manifestString("function"),
                count = trace.get("count")?.asInt,
            ),
        )
    }

    private fun evaluateEventTraceAssertion(trace: JsonObject, sandbox: DatapackSandbox): List<String> {
        val expectedPlayer = trace.manifestString("player")
        val expectedType = trace.manifestString("type")?.replace('-', '_')
        val expectedAdvancement = trace.manifestString("advancement")?.let(ResourceLocation::parse)
        val expectedCriterion = trace.manifestString("criterion")
        val expectedSuccess = trace.get("success")?.asBoolean
        val matches = sandbox.world.playerEventTraces.filter { event ->
            (expectedPlayer == null || event.playerName == expectedPlayer) &&
                (expectedType == null || event.type == expectedType) &&
                (expectedAdvancement == null || event.advancements.any { it.advancement == expectedAdvancement }) &&
                (expectedCriterion == null || event.advancements.any { it.criterion == expectedCriterion }) &&
                (expectedSuccess == null || event.success == expectedSuccess)
        }
        trace.get("count")?.let { expected ->
            if (matches.size != expected.asInt) {
                return listOf("eventTrace ${describeEventTraceExpectation(trace)} expected count ${expected.asInt} but was ${matches.size}")
            }
            return emptyList()
        }
        return if (matches.isEmpty()) {
            listOf("eventTrace ${describeEventTraceExpectation(trace)} did not match any player event trace")
        } else {
            emptyList()
        }
    }

    private fun describeEventTraceExpectation(trace: JsonObject): String =
        listOfNotNull(
            trace.manifestString("player")?.let { "player=$it" },
            trace.manifestString("type")?.let { "type=$it" },
            trace.get("success")?.let { "success=${it.asBoolean}" },
            trace.manifestString("advancement")?.let { "advancement=$it" },
            trace.manifestString("criterion")?.let { "criterion=$it" },
        ).ifEmpty { listOf("<any event trace>") }.joinToString(", ")

    private fun parseEvent(event: JsonObject, sandbox: DatapackSandbox): PlayerEvent {
        val playerName = event.requiredManifestString("player")
        sandbox.createPlayer(playerName)
        val id = event.manifestString("item")?.let { ResourceLocation.parse(it) }
            ?: event.manifestString("entity")?.let { ResourceLocation.parse(it) }
            ?: event.manifestString("block")?.let { ResourceLocation.parse(it) }
            ?: event.manifestString("recipe")?.let { ResourceLocation.parse(it) }
        val damageSource = event.manifestString("damageSource")
            ?: event.manifestString("damageType")
        val damageAmount = event.get("amount")?.asDouble
            ?: event.get("damage")?.takeIf { it.isJsonPrimitive }?.asDouble
        return PlayerEvent(
            playerName = playerName,
            type = event.requiredManifestString("type").replace('-', '_'),
            item = event.manifestString("item")?.let { parseManifestItem(event) } ?: id?.takeIf { event.manifestString("item") != null }?.let { ItemStack(it) },
            entity = event.manifestString("entity")?.let { SandboxEntity(type = ResourceLocation.parse(it)) },
            damageAmount = damageAmount,
            damageSource = damageSource?.let { ResourceLocation.parse(it) },
            block = event.manifestString("block")?.let { ResourceLocation.parse(it) },
            fromDimension = event.manifestString("from")?.let { ResourceLocation.parse(it) },
            toDimension = event.manifestString("to")?.let { ResourceLocation.parse(it) },
            recipe = event.manifestString("recipe")?.let { ResourceLocation.parse(it) },
            input = parseInput(event),
        )
    }

    private fun parseInput(event: JsonObject): PlayerInput? {
        val key = event.manifestString("key")
        if (key != null) {
            return PlayerInput(device = "keyboard", code = key, action = event.manifestString("action") ?: "press")
        }
        val button = event.manifestString("mouseButton") ?: event.manifestString("button")
        if (button != null) {
            return PlayerInput(
                device = "mouse",
                code = button,
                action = event.manifestString("action") ?: "click",
                x = event.get("x")?.asDouble,
                y = event.get("y")?.asDouble,
            )
        }
        return null
    }
}

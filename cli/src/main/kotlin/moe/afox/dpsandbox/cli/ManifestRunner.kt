package moe.afox.dpsandbox.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.BlockPos
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
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
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
)

data class ManifestOptions(
    val seed: Long = 0,
    val verbose: Boolean = false,
    val snapshotOnFail: Boolean = false,
    val unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
)

object ManifestRunner {
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
        val version = json.string("version") ?: "26.1.2"
        val unsupportedMode = json.string("unsupported")?.let(::unsupportedFeatureMode) ?: options.unsupportedFeatureMode
        val packs = json.array("packs").map { base.resolve(it.asString).normalize() }
        if (packs.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain at least one pack: $path")
        }

        val sandbox = createSandbox(version, packs, unsupportedFeatureMode = unsupportedMode)
        json.array("steps").forEachIndexed { index, step ->
            if (!step.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest steps must be objects: $path")
            }
            try {
                runStep(step.asJsonObject, sandbox, options)
            } catch (error: SandboxException) {
                throw SandboxException(error.code, "Step ${index + 1} failed: ${error.message}", error.location, error.version, error.command, error)
            }
        }

        val failures = mutableListOf<String>()
        json.array("assertions").forEach { assertion ->
            if (!assertion.isJsonObject) {
                failures += "Assertion must be an object"
            } else {
                failures += evaluateAssertion(assertion.asJsonObject, sandbox)
            }
        }
        if (failures.isNotEmpty() && options.snapshotOnFail) {
            failures += "snapshot: ${sandbox.snapshotString()}"
        }

        return ManifestResult(path, failures.isEmpty(), failures, sandbox.world.outputs.toList())
    }

    private fun runStep(step: JsonObject, sandbox: DatapackSandbox, options: ManifestOptions) {
        when {
            step.has("load") && step.get("load").asBoolean -> sandbox.runLoad()
            step.has("ticks") -> sandbox.runTicks(step.get("ticks").asInt)
            step.has("function") -> sandbox.runFunction(step.get("function").asString)
            step.has("command") -> sandbox.executeCommand(step.get("command").asString)
            step.has("player") -> runPlayerStep(step.getAsJsonObject("player"), sandbox)
            step.has("block") -> runBlockStep(step.getAsJsonObject("block"), sandbox)
            step.has("event") -> sandbox.handlePlayerEvent(parseEvent(step.getAsJsonObject("event"), sandbox))
            step.has("loot") -> {
                val loot = step.getAsJsonObject("loot")
                sandbox.generateLoot(
                    ResourceLocation.parse(loot.requiredString("table")),
                    ResourceLocation.parse(loot.string("context") ?: "minecraft:empty"),
                    loot.string("player")?.let { sandbox.world.requirePlayer(it) },
                    loot.get("seed")?.asLong ?: options.seed,
                )
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Step must contain load, ticks, function, command, player, block, event, or loot")
        }
    }

    private fun runPlayerStep(player: JsonObject, sandbox: DatapackSandbox) {
        val name = player.requiredString("name")
        val sandboxPlayer = sandbox.createPlayer(name)
        player.array("inventory").forEach { entry ->
            if (!entry.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Inventory entries must be objects")
            sandboxPlayer.inventory += parseItem(entry.asJsonObject)
        }
        player.getAsJsonArray("position")?.let { sandboxPlayer.position = parsePosition(it) }
        player.string("dimension")?.let { sandboxPlayer.dimension = ResourceLocation.parse(it) }
        player.get("xp")?.let { sandboxPlayer.xp = it.asInt }
    }

    private fun runBlockStep(block: JsonObject, sandbox: DatapackSandbox) {
        val pos = parseBlockPos(block.getAsJsonArray("pos") ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Block step requires pos"))
        val id = ResourceLocation.parse(block.requiredString("id"))
        val properties = linkedMapOf<String, String>()
        block.getAsJsonObject("properties")?.entrySet()?.forEach { (key, value) -> properties[key] = value.asString }
        val nbt = block.get("nbt")?.let { if (it.isJsonObject) it.asJsonObject else JsonValues.parse(it.asString).asJsonObject } ?: JsonObject()
        val sandboxBlock = SandboxBlock(id, properties)
        if (nbt.entrySet().isNotEmpty()) {
            val updated = sandboxBlock.fullNbt(pos)
            JsonPaths.merge(updated, null, nbt)
            sandboxBlock.writeFullNbt(pos, updated)
        }
        sandbox.world.setBlock(pos, sandboxBlock)
    }

    private fun evaluateAssertion(assertion: JsonObject, sandbox: DatapackSandbox): List<String> {
        val failures = mutableListOf<String>()
        when {
            assertion.has("score") -> {
                val score = assertion.getAsJsonObject("score")
                val target = score.requiredString("target")
                val objective = score.requiredString("objective")
                val expected = score.get("equals").asInt
                val actual = sandbox.world.getScore(target, objective)
                if (actual != expected) {
                    failures += "score $target $objective expected $expected but was $actual"
                }
            }
            assertion.has("storage") -> {
                val storage = assertion.getAsJsonObject("storage")
                val id = ResourceLocation.parse(storage.requiredString("id"))
                val path = storage.requiredString("path")
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
                    val typeOk = entity.string("type")?.let { sandboxEntity.type == ResourceLocation.parse(it) } ?: true
                    val tagOk = entity.string("tag")?.let { it in sandboxEntity.tags } ?: true
                    typeOk && tagOk
                }
                if (actual != expected) {
                    failures += "entityCount expected $expected but was $actual"
                }
            }
            assertion.has("player") -> {
                val player = assertion.getAsJsonObject("player")
                val actual = sandbox.world.players[player.requiredString("name")]
                if (actual == null) {
                    failures += "player ${player.requiredString("name")} expected to exist"
                } else {
                    player.get("xp")?.let { if (actual.xp != it.asInt) failures += "player ${actual.name} xp expected ${it.asInt} but was ${actual.xp}" }
                    player.get("inventoryCount")?.let { if (actual.inventory.size != it.asInt) failures += "player ${actual.name} inventoryCount expected ${it.asInt} but was ${actual.inventory.size}" }
                    player.getAsJsonArray("position")?.let {
                        val expected = parsePosition(it)
                        if (actual.position != expected) failures += "player ${actual.name} position expected $expected but was ${actual.position}"
                    }
                    player.getAsJsonObject("lastInput")?.let { expected ->
                        val input = actual.lastInput
                        if (input == null) {
                            failures += "player ${actual.name} lastInput expected ${JsonValues.render(expected)} but was <none>"
                        } else {
                            expected.string("device")?.let { if (input.device != it) failures += "player ${actual.name} lastInput device expected $it but was ${input.device}" }
                            expected.string("code")?.let { if (input.code != it) failures += "player ${actual.name} lastInput code expected $it but was ${input.code}" }
                            expected.string("action")?.let { if (input.action != it) failures += "player ${actual.name} lastInput action expected $it but was ${input.action}" }
                        }
                    }
                }
            }
            assertion.has("block") -> {
                val block = assertion.getAsJsonObject("block")
                val posArray = block.getAsJsonArray("pos")
                if (posArray == null) {
                    failures += "block assertion requires pos"
                } else {
                    val pos = parseBlockPos(posArray)
                    val actual = sandbox.world.block(pos)
                    block.get("exists")?.let { expected ->
                        if ((actual != null) != expected.asBoolean) failures += "block $pos exists expected ${expected.asBoolean} but was ${actual != null}"
                    }
                    block.string("id")?.let { expected ->
                        if (actual?.id != ResourceLocation.parse(expected)) failures += "block $pos id expected $expected but was ${actual?.id ?: "void"}"
                    }
                    block.getAsJsonObject("nbt")?.let { expected ->
                        val path = expected.string("path")
                        val expectedValue = expected.get("equals")
                        val actualValue = actual?.fullNbt(pos)?.let { JsonPaths.get(it, path) }
                        if (expectedValue != null && actualValue != expectedValue) {
                            failures += "block $pos nbt ${path ?: "<root>"} expected ${JsonValues.render(expectedValue)} but was ${actualValue?.let(JsonValues::render) ?: "<missing>"}"
                        }
                    }
                }
            }
            assertion.has("advancement") -> {
                val advancement = assertion.getAsJsonObject("advancement")
                val player = sandbox.world.requirePlayer(advancement.requiredString("player"))
                val id = ResourceLocation.parse(advancement.requiredString("id"))
                val definition = sandbox.datapack.advancements[id]
                val progress = player.advancementProgress[id]
                val done = definition != null && progress?.isDone(definition.requirements) == true
                advancement.get("done")?.let { if (done != it.asBoolean) failures += "advancement $id done expected ${it.asBoolean} but was $done" }
                advancement.string("criterion")?.let { criterion ->
                    val criterionDone = progress?.criteria?.get(criterion) == true
                    advancement.get("criterionDone")?.let { expected ->
                        if (criterionDone != expected.asBoolean) failures += "advancement $id criterion $criterion expected ${expected.asBoolean} but was $criterionDone"
                    }
                }
            }
            assertion.has("predicate") -> {
                val predicate = assertion.getAsJsonObject("predicate")
                val result = sandbox.predicates.test(ResourceLocation.parse(predicate.requiredString("id")), PredicateContext(world = sandbox.world, player = predicate.string("player")?.let { sandbox.world.requirePlayer(it) }))
                val expected = predicate.get("equals")?.asBoolean ?: true
                if (result != expected) failures += "predicate ${predicate.requiredString("id")} expected $expected but was $result"
            }
            assertion.has("loot") -> {
                val loot = assertion.getAsJsonObject("loot")
                val result = sandbox.generateLoot(
                    ResourceLocation.parse(loot.requiredString("table")),
                    ResourceLocation.parse(loot.string("context") ?: "minecraft:empty"),
                    loot.string("player")?.let { sandbox.world.requirePlayer(it) },
                    loot.get("seed")?.asLong ?: 0,
                )
                loot.get("count")?.let { if (result.items.size != it.asInt) failures += "loot count expected ${it.asInt} but was ${result.items.size}" }
                loot.string("item")?.let { expected ->
                    if (result.items.none { it.id == ResourceLocation.parse(expected) }) failures += "loot expected item $expected but got ${result.items.map { it.id }}"
                }
            }
            else -> failures += "Unknown assertion kind: ${assertion.keySet().joinToString()}"
        }
        return failures
    }

    private fun parseEvent(event: JsonObject, sandbox: DatapackSandbox): PlayerEvent {
        val playerName = event.requiredString("player")
        sandbox.createPlayer(playerName)
        val id = event.string("item")?.let { ResourceLocation.parse(it) }
            ?: event.string("entity")?.let { ResourceLocation.parse(it) }
            ?: event.string("block")?.let { ResourceLocation.parse(it) }
            ?: event.string("recipe")?.let { ResourceLocation.parse(it) }
        return PlayerEvent(
            playerName = playerName,
            type = event.requiredString("type").replace('-', '_'),
            item = event.string("item")?.let { parseItem(event) } ?: id?.takeIf { event.string("item") != null }?.let { ItemStack(it) },
            entity = event.string("entity")?.let { SandboxEntity(type = ResourceLocation.parse(it)) },
            block = event.string("block")?.let { ResourceLocation.parse(it) },
            fromDimension = event.string("from")?.let { ResourceLocation.parse(it) },
            toDimension = event.string("to")?.let { ResourceLocation.parse(it) },
            recipe = event.string("recipe")?.let { ResourceLocation.parse(it) },
            input = parseInput(event),
        )
    }

    private fun parseInput(event: JsonObject): PlayerInput? {
        val key = event.string("key")
        if (key != null) {
            return PlayerInput(device = "keyboard", code = key, action = event.string("action") ?: "press")
        }
        val button = event.string("mouseButton") ?: event.string("button")
        if (button != null) {
            return PlayerInput(
                device = "mouse",
                code = button,
                action = event.string("action") ?: "click",
                x = event.get("x")?.asDouble,
                y = event.get("y")?.asDouble,
            )
        }
        return null
    }

    private fun parseItem(json: JsonObject): ItemStack =
        ItemStack(
            id = ResourceLocation.parse(json.string("id") ?: json.string("item") ?: "minecraft:air"),
            count = json.get("count")?.asInt ?: 1,
            components = json.getAsJsonObject("components") ?: JsonObject(),
            nbt = json.get("nbt")?.let { if (it.isJsonObject) it.asJsonObject else JsonValues.parse(it.asString).asJsonObject } ?: JsonObject(),
        )

    private fun parseBlockPos(array: com.google.gson.JsonArray): BlockPos {
        if (array.size() != 3) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Block position must contain three numbers")
        return BlockPos(array[0].asInt, array[1].asInt, array[2].asInt)
    }

    private fun parsePosition(array: com.google.gson.JsonArray): Position {
        if (array.size() != 3) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Position must contain three numbers")
        return Position(array[0].asDouble, array[1].asDouble, array[2].asDouble)
    }

    private fun JsonObject.array(name: String): List<JsonElement> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest '$name' must be an array")
        }
        return value.asJsonArray.toList()
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.requiredString(name: String): String =
        string(name) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Missing required string '$name'")
}

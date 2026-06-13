package net.datapacksandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlin.random.Random

data class PredicateContext(
    val world: SandboxWorld,
    val origin: Position? = null,
    val thisEntity: SandboxEntity? = null,
    val directEntity: SandboxEntity? = null,
    val attacker: SandboxEntity? = null,
    val attackingPlayer: SandboxPlayer? = null,
    val targetEntity: SandboxEntity? = null,
    val interactingEntity: SandboxEntity? = null,
    val killer: SandboxEntity? = null,
    val player: SandboxPlayer? = null,
    val tool: ItemStack? = null,
    val block: ResourceLocation? = null,
    val damageSource: ResourceLocation? = null,
    val random: Random = Random(0),
    val weather: WeatherState = WeatherState(),
    val looseMissingContext: Boolean = false,
)

data class WeatherState(val raining: Boolean = false, val thundering: Boolean = false)

class MissingPredicateContext(message: String) : SandboxException(DiagnosticCode.MISSING_CONTEXT, message)

class PredicateEngine(private val datapack: Datapack) {
    fun test(id: ResourceLocation, context: PredicateContext): Boolean =
        testElement(datapack.predicate(id).root, context)

    fun testElement(element: JsonElement?, context: PredicateContext): Boolean {
        if (element == null || element.isJsonNull) return true
        return when {
            element.isJsonArray -> element.asJsonArray.all { testElement(it, context) }
            element.isJsonObject -> testObject(element.asJsonObject, context)
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Predicate must be an object, array, or boolean")
        }
    }

    fun testConditions(conditions: JsonElement?, context: PredicateContext): Boolean =
        when {
            conditions == null || conditions.isJsonNull -> true
            conditions.isJsonArray -> conditions.asJsonArray.all { testElement(it, context) }
            conditions.isJsonObject -> testElement(conditions, context)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Conditions must be an object or array")
        }

    private fun testObject(root: JsonObject, context: PredicateContext): Boolean {
        val type = root.string("condition") ?: root.string("predicate") ?: return testEntityPredicate(context.thisEntity ?: context.player, root, context)
        return when (canonical(type)) {
            "all_of" -> root.terms().all { testElement(it, context) }
            "any_of", "alternative" -> root.terms().any { testElement(it, context) }
            "inverted" -> !testElement(root.get("term") ?: root.get("predicate"), context)
            "reference" -> test(ResourceLocation.parse(root.requiredString("name")), context)
            "random_chance" -> context.random.nextDouble() < root.number("chance", 0.0)
            "random_chance_with_enchanted_bonus" -> context.random.nextDouble() < root.number("unenchanted_chance", root.number("chance", 0.0))
            "survives_explosion" -> true
            "time_check" -> testRange(context.world.gameTime, root.get("value") ?: root.get("period"))
            "weather_check" -> {
                (root.optionalBoolean("raining")?.let { it == context.weather.raining } ?: true) &&
                    (root.optionalBoolean("thundering")?.let { it == context.weather.thundering } ?: true)
            }
            "entity_properties" -> {
                val entity = resolveEntity(root.string("entity") ?: "this", context)
                testEntityPredicate(entity, root.getAsJsonObject("predicate") ?: JsonObject(), context)
            }
            "entity_scores" -> {
                val entity = resolveEntity(root.string("entity") ?: "this", context)
                val scores = root.getAsJsonObject("scores") ?: return true
                scores.entrySet().all { (objective, range) ->
                    context.world.getScore(entity.scoreHolder, objective).let { testRange(it.toLong(), range) }
                }
            }
            "location_check" -> testLocation(root.getAsJsonObject("predicate") ?: JsonObject(), root.getAsJsonObject("offset"), context)
            "match_tool" -> testItemPredicate(requireContext(context.tool, "match_tool requires tool context"), root.getAsJsonObject("predicate") ?: root)
            "damage_source_properties" -> {
                val source = requireContext(context.damageSource, "damage_source_properties requires damage source context")
                root.getAsJsonObject("predicate")?.string("type")?.let { ResourceLocation.parse(it) == source } ?: true
            }
            "block_state_property" -> {
                val block = requireContext(context.block, "block_state_property requires block context")
                root.string("block")?.let { ResourceLocation.parse(it) == block } ?: true
            }
            else -> throw SandboxException(DiagnosticCode.UNSUPPORTED_FEATURE, "Predicate condition '$type' is not implemented")
        }
    }

    private fun testEntityPredicate(entity: SandboxEntity?, predicate: JsonObject, context: PredicateContext): Boolean {
        val actual = requireContext(entity, "Entity predicate requires an entity context")
        predicate.string("type")?.let {
            if (ResourceLocation.parse(it) != actual.type) return false
        }
        predicate.get("nbt")?.let {
            val expected = if (it.isJsonPrimitive) JsonValues.parse(it.asString) else it
            if (expected.isJsonObject && !containsAll(actual.nbt, expected.asJsonObject)) return false
        }
        predicate.getAsJsonObject("location")?.let {
            if (!testLocation(it, null, context.copy(origin = actual.position))) return false
        }
        predicate.getAsJsonObject("distance")?.let { distance ->
            val origin = requireContext(context.origin, "Distance predicate requires origin context")
            val dx = actual.position.x - origin.x
            val dy = actual.position.y - origin.y
            val dz = actual.position.z - origin.z
            if (!testRange(dx * dx + dy * dy + dz * dz, distance.get("absolute") ?: distance.get("horizontal"))) return false
        }
        (predicate.getAsJsonObject("player") ?: predicate.getAsJsonObject("type_specific")?.takeIf {
            it.string("type") == "minecraft:player" || it.string("type") == "player"
        })?.let { playerPredicate ->
            val player = actual as? SandboxPlayer ?: return false
            if (!testPlayerPredicate(player, playerPredicate, context)) return false
        }
        predicate.getAsJsonObject("equipment")?.let {
            val player = actual as? SandboxPlayer ?: return false
            val mainhand = it.getAsJsonObject("mainhand")
            if (mainhand != null && !testItemPredicate(player.selectedItem ?: return false, mainhand)) return false
        }
        return true
    }

    private fun testPlayerPredicate(player: SandboxPlayer, predicate: JsonObject, context: PredicateContext): Boolean {
        predicate.getAsJsonObject("recipes")?.entrySet()?.forEach { (id, value) ->
            if ((ResourceLocation.parse(id) in player.recipes) != value.asBoolean) return false
        }
        predicate.getAsJsonObject("advancements")?.entrySet()?.forEach { (id, value) ->
            val advancementId = ResourceLocation.parse(id)
            val advancement = datapack.advancements[advancementId] ?: return false
            val progress = player.advancementProgress[advancementId]
            val done = progress?.isDone(advancement.requirements) == true
            if (value.isJsonPrimitive && value.asBoolean != done) return false
        }
        predicate.getAsJsonObject("stats")?.entrySet()?.forEach { (id, range) ->
            if (!testRange((player.stats[ResourceLocation.parse(id)] ?: 0).toLong(), range)) return false
        }
        predicate.getAsJsonObject("looking_at")?.let {
            if (!testEntityPredicate(context.thisEntity, it, context)) return false
        }
        return true
    }

    fun testItemPredicate(item: ItemStack, predicate: JsonObject): Boolean {
        val ids = predicate.get("items") ?: predicate.get("item")
        if (ids != null && !matchesIdList(item.id, ids)) return false
        predicate.get("count")?.let { if (!testRange(item.count.toLong(), it)) return false }
        predicate.get("nbt")?.let {
            val expected = if (it.isJsonPrimitive) JsonValues.parse(it.asString) else it
            if (expected.isJsonObject && !containsAll(item.nbt, expected.asJsonObject)) return false
        }
        predicate.getAsJsonObject("components")?.let {
            if (!containsAll(item.components, it)) return false
        }
        return true
    }

    private fun testLocation(predicate: JsonObject, offset: JsonObject?, context: PredicateContext): Boolean {
        val origin = requireContext(context.origin ?: context.player?.position ?: context.thisEntity?.position, "Location predicate requires origin context")
        val x = origin.x + (offset?.number("x", 0.0) ?: 0.0)
        val y = origin.y + (offset?.number("y", 0.0) ?: 0.0)
        val z = origin.z + (offset?.number("z", 0.0) ?: 0.0)
        predicate.get("position")?.asJsonObject?.let { position ->
            if (!testRange(x, position.get("x")) || !testRange(y, position.get("y")) || !testRange(z, position.get("z"))) return false
        }
        predicate.string("dimension")?.let {
            val player = context.player ?: context.thisEntity as? SandboxPlayer
            if (player == null || player.dimension != ResourceLocation.parse(it)) return false
        }
        predicate.string("biome")?.let {
            // Biome is not simulated; require exact explicit context in a future world model.
            throw MissingPredicateContext("Location biome predicate requires biome context")
        }
        return true
    }

    private fun resolveEntity(name: String, context: PredicateContext): SandboxEntity =
        when (name) {
            "this" -> context.thisEntity ?: context.player ?: missing(context, "Predicate entity 'this' is missing")
            "direct_attacker" -> context.directEntity ?: missing(context, "Predicate direct attacker entity is missing")
            "attacker" -> context.attacker ?: missing(context, "Predicate attacker entity is missing")
            "attacking_player" -> context.attackingPlayer ?: context.player ?: (context.attacker as? SandboxPlayer)
                ?: missing(context, "Predicate attacking player entity is missing")
            "target_entity" -> context.targetEntity ?: missing(context, "Predicate target entity is missing")
            "interacting_entity" -> context.interactingEntity ?: missing(context, "Predicate interacting entity is missing")
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Unknown predicate entity target '$name'. Expected one of: this, attacker, direct_attacker, attacking_player, target_entity, interacting_entity",
            )
        }

    private fun <T : Any> requireContext(value: T?, message: String): T =
        value ?: throw MissingPredicateContext(message)

    private fun missing(context: PredicateContext, message: String): Nothing {
        if (context.looseMissingContext) throw MissingPredicateContext(message)
        throw MissingPredicateContext(message)
    }

    private fun matchesIdList(id: ResourceLocation, element: JsonElement): Boolean =
        when {
            element.isJsonPrimitive -> ResourceLocation.parse(element.asString) == id
            element.isJsonArray -> element.asJsonArray.any { matchesIdList(id, it) }
            else -> false
        }

    private fun testRange(actual: Long, range: JsonElement?): Boolean = testRange(actual.toDouble(), range)

    private fun testRange(actual: Double, range: JsonElement?): Boolean {
        if (range == null || range.isJsonNull) return true
        if (range.isJsonPrimitive) return actual == range.asDouble
        if (!range.isJsonObject) return false
        val objectRange = range.asJsonObject
        objectRange.get("min")?.asDouble?.let { if (actual < it) return false }
        objectRange.get("max")?.asDouble?.let { if (actual > it) return false }
        return true
    }

    private fun containsAll(actual: JsonObject, expected: JsonObject): Boolean =
        expected.entrySet().all { (key, expectedValue) ->
            val actualValue = actual.get(key) ?: return@all false
            when {
                expectedValue.isJsonObject && actualValue.isJsonObject -> containsAll(actualValue.asJsonObject, expectedValue.asJsonObject)
                expectedValue.isJsonArray && actualValue.isJsonArray -> expectedValue.asJsonArray.all { expectedItem ->
                    actualValue.asJsonArray.any { actualItem -> actualItem == expectedItem }
                }
                else -> actualValue == expectedValue
            }
        }

    private fun canonical(raw: String): String = ResourceLocation.parse(raw).path

    private fun JsonObject.terms(): JsonArray =
        getAsJsonArray("terms") ?: getAsJsonArray("conditions") ?: JsonArray()

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.requiredString(name: String): String =
        string(name) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Missing required string '$name'")

    private fun JsonObject.number(name: String, default: Double): Double =
        get(name)?.takeIf { it.isJsonPrimitive }?.asDouble ?: default

    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean
}

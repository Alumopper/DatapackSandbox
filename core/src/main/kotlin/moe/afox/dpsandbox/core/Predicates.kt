package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlin.random.Random

/**
 * Context available to predicate and loot-condition evaluation.
 *
 * Callers should provide the entities, player, origin, tool, block, and damage
 * source that a predicate may reference. Missing required context throws
 * [MissingPredicateContext] by default.
 */
data class PredicateContext(
    /** Mutable world state used for scores, time, storage-visible entity data, and player state. */
    val world: SandboxWorld,
    /** Origin position for distance and location predicates. */
    val origin: Position? = null,
    /** Execution or event dimension for location predicates. */
    val dimension: ResourceLocation? = null,
    /** `this` entity target in predicate JSON. */
    val thisEntity: SandboxEntity? = null,
    /** Direct attacker entity context. */
    val directEntity: SandboxEntity? = null,
    /** Attacker entity context. */
    val attacker: SandboxEntity? = null,
    /** Attacking player context. */
    val attackingPlayer: SandboxPlayer? = null,
    /** Target entity context. */
    val targetEntity: SandboxEntity? = null,
    /** Interacting entity context. */
    val interactingEntity: SandboxEntity? = null,
    /** Killer entity context. */
    val killer: SandboxEntity? = null,
    /** Player context for player-specific predicates. */
    val player: SandboxPlayer? = null,
    /** Tool/item context for `match_tool` and related loot behavior. */
    val tool: ItemStack? = null,
    /** Block id context for block-state predicates. */
    val block: ResourceLocation? = null,
    /** Damage source id context for damage-source predicates. */
    val damageSource: ResourceLocation? = null,
    /** Deterministic random source for random-chance predicates. */
    val random: Random = Random(0),
    /** Weather context used by weather predicates. */
    val weather: WeatherState = WeatherState(),
    /** Reserved switch for future permissive predicate modes. Current behavior remains strict. */
    val looseMissingContext: Boolean = false,
)

/**
 * Minimal weather state exposed to predicate evaluation.
 */
data class WeatherState(val raining: Boolean = false, val thundering: Boolean = false)

/**
 * Exception thrown when a predicate references context that the sandbox caller
 * did not provide.
 */
class MissingPredicateContext(message: String) : SandboxException(DiagnosticCode.MISSING_CONTEXT, message)

/**
 * Predicate evaluator for loaded datapack predicate resources and inline loot conditions.
 */
class PredicateEngine(
    private val datapack: Datapack,
    private val profile: VersionProfile = VersionProfiles.default,
) {
    /**
     * Evaluates a loaded predicate resource by id.
     *
     * @throws SandboxException when the predicate is missing, malformed, needs
     * missing context, or uses an unsupported condition type.
     */
    fun test(id: ResourceLocation, context: PredicateContext): Boolean =
        testElement(datapack.predicate(id).root, context)

    /**
     * Evaluates an inline predicate element.
     *
     * Supported roots are object, array, boolean, null. Array roots require all
     * contained predicates to pass.
     */
    fun testElement(element: JsonElement?, context: PredicateContext): Boolean {
        if (element == null || element.isJsonNull) return true
        return when {
            element.isJsonArray -> element.asJsonArray.all { testElement(it, context) }
            element.isJsonObject -> testObject(element.asJsonObject, context)
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Predicate must be an object, array, or boolean")
        }
    }

    /**
     * Evaluates a loot-condition style condition list or object.
     */
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
            "random_chance_with_enchanted_bonus", "random_chance_with_looting" -> testRandomChanceWithEnchantedBonus(root, context)
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

    private fun testRandomChanceWithEnchantedBonus(root: JsonObject, context: PredicateContext): Boolean {
        val unenchantedChance = root.number("unenchanted_chance", root.number("chance", 0.0))
        val level = predicateEnchantment(root)?.let { toolEnchantmentLevel(context.tool, it) } ?: 0
        val chance = if (level <= 0) {
            unenchantedChance
        } else {
            root.get("enchanted_chance")?.let { enchantmentLevelValue(it, level) }
                ?: legacyEnchantedChance(root, unenchantedChance, level)
        }
        return context.random.nextDouble() < chance.coerceIn(0.0, 1.0)
    }

    private fun predicateEnchantment(root: JsonObject): ResourceLocation? =
        root.string("enchantment")?.let { ResourceLocation.parse(it) }
            ?: ResourceLocation.parse("minecraft:looting").takeIf {
                root.get("looting_multiplier") != null || canonical(root.string("condition") ?: "") == "random_chance_with_looting"
            }

    private fun legacyEnchantedChance(root: JsonObject, unenchantedChance: Double, level: Int): Double {
        val baseChance = root.number("chance", unenchantedChance)
        val multiplier = root.numberOrNull("looting_multiplier")
            ?: root.numberOrNull("enchanted_bonus_multiplier")
            ?: root.numberOrNull("bonus_multiplier")
            ?: root.numberOrNull("enchanted_bonus")
            ?: return unenchantedChance
        return baseChance + level * multiplier
    }

    private fun enchantmentLevelValue(element: JsonElement?, level: Int): Double {
        if (element == null || element.isJsonNull) return 0.0
        if (element.isJsonPrimitive) return element.asDouble
        if (!element.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Enchanted chance value must be a number or object")
        }
        val root = element.asJsonObject
        return when (root.string("type")?.let(::canonical)) {
            null -> root.get("value")?.let { enchantmentLevelValue(it, level) }
                ?: root.numberOrNull("chance")
                ?: root.numberOrNull("base")
                ?: 0.0
            "constant" -> root.number("value", 0.0)
            "linear" -> root.number("base", 0.0) +
                root.number("per_level_above_first", root.number("perLevelAboveFirst", 0.0)) * (level - 1).coerceAtLeast(0)
            "clamped" -> {
                val value = enchantmentLevelValue(root.get("value"), level)
                value.coerceIn(root.number("min", Double.NEGATIVE_INFINITY), root.number("max", Double.POSITIVE_INFINITY))
            }
            "fraction" -> {
                val denominator = enchantmentLevelValue(root.get("denominator"), level)
                if (denominator == 0.0) 0.0 else enchantmentLevelValue(root.get("numerator"), level) / denominator
            }
            "levels_squared" -> level.toDouble() * level + root.number("added", 0.0)
            "lookup" -> {
                val values = root.getAsJsonArray("values")
                val indexed = if (values != null && level > 0 && level <= values.size()) values[level - 1] else null
                indexed?.let { enchantmentLevelValue(it, level) }
                    ?: root.get("fallback")?.let { enchantmentLevelValue(it, level) }
                    ?: 0.0
            }
            else -> throw SandboxException(DiagnosticCode.UNSUPPORTED_FEATURE, "Enchanted chance value type '${root.string("type")}' is not implemented")
        }
    }

    private fun toolEnchantmentLevel(tool: ItemStack?, enchantment: ResourceLocation): Int {
        val enchantments = tool?.components?.getAsJsonObject("minecraft:enchantments") ?: return 0
        return enchantmentLevel(enchantments, enchantment)
            ?: enchantments.getAsJsonObject("levels")?.let { enchantmentLevel(it, enchantment) }
            ?: 0
    }

    private fun enchantmentLevel(enchantments: JsonObject, enchantment: ResourceLocation): Int? =
        enchantmentLevelElement(enchantments.get(enchantment.toString()))
            ?: enchantmentLevelElement(enchantments.get(enchantment.path))

    private fun enchantmentLevelElement(element: JsonElement?): Int? =
        when {
            element == null || element.isJsonNull -> null
            element.isJsonPrimitive -> element.asInt
            element.isJsonObject -> element.asJsonObject.get("level")?.takeIf { it.isJsonPrimitive }?.asInt
            else -> null
        }

    private fun testEntityPredicate(entity: SandboxEntity?, predicate: JsonObject, context: PredicateContext): Boolean {
        val actual = requireContext(entity, "Entity predicate requires an entity context")
        predicate.string("type")?.let {
            if (ResourceLocation.parse(it) != actual.type) return false
        }
        predicate.get("nbt")?.let {
            val expected = if (it.isJsonPrimitive) JsonValues.parse(it.asString) else it
            if (expected.isJsonObject && !containsAll(actual.fullNbt(profile), expected.asJsonObject)) return false
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
            if (!testEquipmentPredicate(actual, it)) return false
        }
        predicate.getAsJsonObject("effects")?.let {
            if (!testEffectsPredicate(actual, it)) return false
        }
        return true
    }

    private fun testEffectsPredicate(entity: SandboxEntity, predicate: JsonObject): Boolean {
        predicate.entrySet().forEach { (id, value) ->
            val effect = entityEffect(entity, ResourceLocation.parse(id))
            if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                if ((effect != null) != value.asBoolean) return false
                return@forEach
            }
            if (effect == null || !value.isJsonObject) return false
            val effectPredicate = value.asJsonObject
            effectPredicate.get("amplifier")?.let { if (!testRange(effect.amplifier.toLong(), it)) return false }
            effectPredicate.get("duration")?.let { if (!testRange(effect.durationTicks.toLong(), it)) return false }
            effectPredicate.get("visible")?.takeIf { it.isJsonPrimitive }?.let {
                if (it.asBoolean == effect.hideParticles) return false
            }
            effectPredicate.get("show_particles")?.takeIf { it.isJsonPrimitive }?.let {
                if (it.asBoolean == effect.hideParticles) return false
            }
            effectPredicate.get("hide_particles")?.takeIf { it.isJsonPrimitive }?.let {
                if (it.asBoolean != effect.hideParticles) return false
            }
        }
        return true
    }

    private fun entityEffect(entity: SandboxEntity, id: ResourceLocation): PlayerEffect? =
        if (entity is SandboxPlayer) {
            entity.effectDetails[id] ?: id.takeIf { it in entity.effects }?.let { PlayerEffect(it) }
        } else {
            entity.activeEffects[id]
        }

    private fun testEquipmentPredicate(entity: SandboxEntity, predicate: JsonObject): Boolean {
        predicate.entrySet().forEach { (key, value) ->
            if (!value.isJsonObject) return false
            val item = equipmentItem(entity, key) ?: return false
            if (!testItemPredicate(item, value.asJsonObject)) return false
        }
        return true
    }

    private fun equipmentItem(entity: SandboxEntity, key: String): ItemStack? {
        if (entity is SandboxPlayer) {
            return when (key) {
                "mainhand", EquipmentSlots.MAINHAND -> entity.selectedItem
                "offhand", EquipmentSlots.OFFHAND -> entity.inventory.getOrNull(40)
                "feet", EquipmentSlots.FEET -> entity.inventory.getOrNull(36)
                "legs", EquipmentSlots.LEGS -> entity.inventory.getOrNull(37)
                "chest", EquipmentSlots.CHEST -> entity.inventory.getOrNull(38)
                "head", EquipmentSlots.HEAD -> entity.inventory.getOrNull(39)
                else -> null
            }
        }
        val slot = when (key) {
            "mainhand", EquipmentSlots.MAINHAND -> EquipmentSlots.MAINHAND
            "offhand", EquipmentSlots.OFFHAND -> EquipmentSlots.OFFHAND
            "feet", EquipmentSlots.FEET -> EquipmentSlots.FEET
            "legs", EquipmentSlots.LEGS -> EquipmentSlots.LEGS
            "chest", EquipmentSlots.CHEST -> EquipmentSlots.CHEST
            "head", EquipmentSlots.HEAD -> EquipmentSlots.HEAD
            else -> null
        }
        return slot?.let { entity.equipment[it] }
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

    /**
     * Evaluates an item predicate against [item].
     */
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
        predicate.getAsJsonObject("enchantments")?.let {
            if (!testEnchantments(item, "minecraft:enchantments", it)) return false
        }
        predicate.getAsJsonObject("stored_enchantments")?.let {
            if (!testEnchantments(item, "minecraft:stored_enchantments", it)) return false
        }
        return true
    }

    private fun testEnchantments(item: ItemStack, component: String, predicate: JsonObject): Boolean {
        val actual = item.components.getAsJsonObject(component) ?: return false
        predicate.entrySet().forEach { (id, range) ->
            val level = actual.get(id)?.takeIf { it.isJsonPrimitive } ?: return false
            if (!testRange(level.asLong, range)) return false
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
            val dimension = context.dimension ?: player?.dimension
            if (dimension != ResourceLocation.parse(it)) return false
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

    private fun JsonObject.numberOrNull(name: String): Double? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asDouble

    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean
}

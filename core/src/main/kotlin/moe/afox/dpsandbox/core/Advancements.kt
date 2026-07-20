package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class AdvancementUpdate(
    val advancement: ResourceLocation,
    val criterion: String,
    val completed: Boolean,
)

data class AdvancementCriterionFailure(
    val advancement: ResourceLocation,
    val criterion: String,
    val reason: String,
)

data class AdvancementEventResult(
    val updates: List<AdvancementUpdate>,
    val failures: List<AdvancementCriterionFailure>,
)

class AdvancementRuntime(
    private val sandbox: DatapackSandbox,
) {
    private val predicates = PredicateEngine(sandbox.datapack, sandbox.profile)
    private val loot = LootEngine(sandbox.datapack, sandbox.profile.registryView, sandbox.profile)

    fun grant(
        player: SandboxPlayer,
        id: ResourceLocation,
        criterion: String? = null,
    ): List<AdvancementUpdate> {
        val advancement = sandbox.datapack.advancement(id)
        val progress = progressFor(player, advancement)
        val wasDone = progress.isDone(advancement.requirements)
        val criteriaToGrant = criterion?.let { listOf(it) } ?: advancement.criteria.keys.toList()
        val updates =
            criteriaToGrant.mapNotNull {
                if (progress.criteria[it] == true) return@mapNotNull null
                progress.criteria[it] = true
                AdvancementUpdate(id, it, progress.isDone(advancement.requirements))
            }
        if (!wasDone && progress.isDone(advancement.requirements)) applyRewards(player, advancement)
        return updates
    }

    fun revoke(
        player: SandboxPlayer,
        id: ResourceLocation,
        criterion: String? = null,
    ): List<AdvancementUpdate> {
        val advancement = sandbox.datapack.advancement(id)
        val progress = progressFor(player, advancement)
        val criteriaToRevoke = criterion?.let { listOf(it) } ?: advancement.criteria.keys.toList()
        return criteriaToRevoke.mapNotNull {
            if (progress.criteria[it] != true) return@mapNotNull null
            progress.criteria[it] = false
            AdvancementUpdate(id, it, progress.isDone(advancement.requirements))
        }
    }

    fun progress(
        player: SandboxPlayer,
        id: ResourceLocation,
    ): AdvancementProgress = progressFor(player, sandbox.datapack.advancement(id))

    fun handle(rawEvent: PlayerEvent): List<AdvancementUpdate> = handleWithDebug(rawEvent).updates

    fun handleWithDebug(rawEvent: PlayerEvent): AdvancementEventResult {
        val event = rawEvent.normalized()
        val player = sandbox.world.requirePlayer(event.playerName)
        val updates = mutableListOf<AdvancementUpdate>()
        val failures = mutableListOf<AdvancementCriterionFailure>()
        sandbox.datapack.advancements.values.forEach { advancement ->
            val progress = progressFor(player, advancement)
            advancement.criteria.values.forEach { criterion ->
                if (progress.criteria[criterion.name] == true) return@forEach
                val evaluation = evaluateCriterion(player, event, criterion)
                if (evaluation.matched) {
                    progress.criteria[criterion.name] = true
                    val done = progress.isDone(advancement.requirements)
                    updates += AdvancementUpdate(advancement.id, criterion.name, done)
                    if (done) applyRewards(player, advancement)
                } else if (evaluation.reason != null) {
                    failures += AdvancementCriterionFailure(advancement.id, criterion.name, evaluation.reason)
                }
            }
        }
        return AdvancementEventResult(updates, failures)
    }

    private data class CriterionEvaluation(
        val matched: Boolean,
        val reason: String? = null,
    )

    private fun evaluateCriterion(
        player: SandboxPlayer,
        event: PlayerEvent,
        criterion: Criterion,
    ): CriterionEvaluation {
        val trigger = criterion.trigger.path
        if (!triggerMatchesEvent(trigger, event.type)) return CriterionEvaluation(false)
        val conditions = criterion.conditions ?: return CriterionEvaluation(true)
        val incomingDamageEvent = event.type in setOf("damage", "death", "entity_killed_player", "entity_hurt_player")
        val playerAttackEvent =
            event.type in
                setOf(
                    "killed_entity",
                    "entity_killed",
                    "player_killed_entity",
                    "player_hurt_entity",
                    "entity_attacked",
                    "player_attacked_entity",
                    "attack_entity",
                )
        val attacker =
            when {
                incomingDamageEvent -> event.entity
                playerAttackEvent -> player
                else -> event.entity
            }
        val predicateContext =
            PredicateContext(
                world = sandbox.world,
                origin = player.position,
                thisEntity = player,
                player = player,
                directEntity = attacker,
                attacker = attacker,
                attackingPlayer =
                    when {
                        playerAttackEvent -> player
                        else -> event.entity as? SandboxPlayer
                    },
                targetEntity = if (incomingDamageEvent) player else event.entity,
                interactingEntity = player,
                killer = if (event.type in setOf("death", "entity_killed_player")) event.entity else attacker,
                tool = event.item ?: player.selectedItem,
                block = event.block,
                damageSource = event.damageSource,
            )
        conditions.getAsJsonObject("player")?.let {
            if (!predicates.testElement(entityCondition("this", it), predicateContext.copy(thisEntity = player))) {
                return CriterionEvaluation(false, "player predicate did not match")
            }
        }
        conditions.getAsJsonObject("entity")?.let {
            if (event.entity == null) return CriterionEvaluation(false, "entity context missing")
            if (!predicates.testElement(entityCondition("this", it), predicateContext.copy(thisEntity = event.entity))) {
                return CriterionEvaluation(false, "entity predicate did not match")
            }
        }
        conditions.getAsJsonObject("item")?.let {
            if (event.item == null) return CriterionEvaluation(false, "item context missing")
            if (!predicates.testItemPredicate(event.item, it)) {
                return CriterionEvaluation(false, "item predicate did not match item ${event.item.id}")
            }
        }
        conditions.getAsJsonArray("items")?.let { items ->
            val matched =
                player.inventory.any { stack ->
                    items.any { it.isJsonObject && predicates.testItemPredicate(stack, it.asJsonObject) }
                }
            if (!matched) return CriterionEvaluation(false, "player inventory did not match any items predicate")
        }
        conditions.string("recipe")?.let {
            val expected = ResourceLocation.parse(it)
            if (event.recipe != expected) {
                return CriterionEvaluation(false, "recipe expected $expected but was ${event.recipe ?: "<missing>"}")
            }
        }
        conditions.string("block")?.let {
            val expected = ResourceLocation.parse(it)
            if (event.block != expected) {
                return CriterionEvaluation(false, "block expected $expected but was ${event.block ?: "<missing>"}")
            }
        }
        conditions.string("from")?.let {
            val expected = ResourceLocation.parse(it)
            if (event.fromDimension != expected) {
                return CriterionEvaluation(false, "from dimension expected $expected but was ${event.fromDimension ?: "<missing>"}")
            }
        }
        conditions.string("to")?.let {
            val expected = ResourceLocation.parse(it)
            if (event.toDimension != expected) {
                return CriterionEvaluation(false, "to dimension expected $expected but was ${event.toDimension ?: "<missing>"}")
            }
        }
        conditions.string("damage_source")?.let {
            val expected = ResourceLocation.parse(it)
            if (event.damageSource != expected) {
                return CriterionEvaluation(false, "damage source expected $expected but was ${event.damageSource ?: "<missing>"}")
            }
        }
        conditions.string("damageType")?.let {
            val expected = ResourceLocation.parse(it)
            if (event.damageSource != expected) {
                return CriterionEvaluation(false, "damage source expected $expected but was ${event.damageSource ?: "<missing>"}")
            }
        }
        conditions.getAsJsonObject("damage")?.let {
            damageMismatch(event, it, predicateContext)?.let { reason -> return CriterionEvaluation(false, reason) }
        }
        conditions.string("key")?.let {
            if (event.input?.device != "keyboard" || event.input.code != it) {
                return CriterionEvaluation(false, "keyboard input expected $it but was ${event.input?.code ?: "<missing>"}")
            }
        }
        conditions.string("button")?.let {
            if (event.input?.device != "mouse" || event.input.code != it) {
                return CriterionEvaluation(false, "mouse input expected $it but was ${event.input?.code ?: "<missing>"}")
            }
        }
        conditions.string("input")?.let {
            if (event.input?.code != it) {
                return CriterionEvaluation(false, "input expected $it but was ${event.input?.code ?: "<missing>"}")
            }
        }
        conditions.string("action")?.let {
            if (event.input?.action != it) {
                return CriterionEvaluation(false, "input action expected $it but was ${event.input?.action ?: "<missing>"}")
            }
        }
        conditions.getAsJsonObject("location")?.let {
            if (!predicates.testElement(locationCondition(it), predicateContext)) {
                return CriterionEvaluation(false, "location predicate did not match")
            }
        }
        return CriterionEvaluation(true)
    }

    private fun triggerMatchesEvent(
        trigger: String,
        eventType: String,
    ): Boolean =
        when (trigger) {
            "tick" -> eventType == "tick"
            "inventory_changed" -> eventType in setOf("inventory_changed", "item_picked_up", "item_added")
            "using_item", "item_used_on_block" -> eventType in setOf("item_used", "using_item", "item_used_on_block")
            "consume_item" -> eventType in setOf("item_consumed", "consume_item")
            "player_interacted_with_entity" -> eventType in setOf("entity_interacted", "player_interacted_with_entity")
            "damage" -> eventType == "damage"
            "death" -> eventType == "death"
            "entity_hurt_player" -> eventType in setOf("damage", "entity_hurt_player")
            "player_hurt_entity" -> eventType in setOf("player_hurt_entity", "entity_attacked", "player_attacked_entity", "attack_entity")
            "player_killed_entity" -> eventType in setOf("killed_entity", "entity_killed", "player_killed_entity")
            "entity_killed_player" -> eventType == "entity_killed_player"
            "location" -> eventType in setOf("location", "moved")
            "changed_dimension" -> eventType == "changed_dimension"
            "placed_block" -> eventType in setOf("placed_block", "block_placed")
            "bee_nest_destroyed" -> eventType in setOf("broke_block", "block_broken", "broken_block")
            "recipe_unlocked" -> eventType == "recipe_unlocked"
            "effects_changed" -> eventType == "effects_changed"
            "key_input", "keyboard_input" -> eventType in setOf("key_input", "keyboard_input", "key_pressed", "key_released")
            "mouse_input" -> eventType in setOf("mouse_input", "mouse_button", "mouse_clicked", "mouse_released", "mouse_moved")
            "impossible" -> false
            else -> false
        }

    private fun applyRewards(
        player: SandboxPlayer,
        advancement: AdvancementDefinition,
    ) {
        val rewards = advancement.rewards
        player.xp += rewards.experience
        rewards.recipes.forEach { player.recipes += it }
        rewards.function?.let { sandbox.runFunction(it, ExecutionContext(entity = player, position = player.position)) }
        val lootItems = mutableListOf<ItemStack>()
        rewards.loot.forEach { table ->
            val context =
                LootContext(
                    type = ResourceLocation("minecraft", "advancement_reward"),
                    predicateContext =
                        PredicateContext(
                            world = sandbox.world,
                            player = player,
                            thisEntity = player,
                            origin = player.position,
                        ),
                    seed = sandbox.world.gameTime,
                )
            val generated = loot.generate(table, context).items
            player.inventory += generated
            lootItems += generated
        }
        recordRewardOutput(player, advancement, rewards, lootItems)
    }

    private fun recordRewardOutput(
        player: SandboxPlayer,
        advancement: AdvancementDefinition,
        rewards: AdvancementReward,
        lootItems: List<ItemStack>,
    ) {
        if (rewards.experience == 0 && rewards.recipes.isEmpty() && rewards.function == null && rewards.loot.isEmpty()) return
        sandbox.world.recordOutput(
            "advancement reward",
            "data",
            targets = listOf(player.name),
            text = advancement.id.toString(),
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("player", player.name)
                    payload.addProperty("advancement", advancement.id.toString())
                    payload.addProperty("experience", rewards.experience)
                    rewards.function?.let { payload.addProperty("function", it.toString()) }
                    payload.add(
                        "recipes",
                        JsonArray().also { array ->
                            rewards.recipes.sorted().forEach { array.add(it.toString()) }
                        },
                    )
                    payload.add(
                        "lootTables",
                        JsonArray().also { array ->
                            rewards.loot.sorted().forEach { array.add(it.toString()) }
                        },
                    )
                    payload.addProperty("itemCount", lootItems.sumOf { it.count })
                    payload.add(
                        "items",
                        JsonArray().also { array ->
                            lootItems.forEach { array.add(it.toJson()) }
                        },
                    )
                },
        )
    }

    private fun progressFor(
        player: SandboxPlayer,
        advancement: AdvancementDefinition,
    ): AdvancementProgress =
        player.advancementProgress.getOrPut(advancement.id) {
            AdvancementProgress(
                advancement.criteria.keys
                    .associateWith { false }
                    .toMutableMap(),
            )
        }

    private fun entityCondition(
        entity: String,
        predicate: JsonObject,
    ): JsonObject {
        val condition = JsonObject()
        condition.addProperty("condition", "minecraft:entity_properties")
        condition.addProperty("entity", entity)
        condition.add("predicate", predicate)
        return condition
    }

    private fun locationCondition(predicate: JsonObject): JsonObject {
        val condition = JsonObject()
        condition.addProperty("condition", "minecraft:location_check")
        condition.add("predicate", predicate)
        return condition
    }

    private fun damageMismatch(
        event: PlayerEvent,
        predicate: JsonObject,
        context: PredicateContext,
    ): String? {
        predicate.get("amount")?.let {
            rangeMismatch("damage amount", event.damageAmount, it)?.let { reason -> return reason }
        }
        predicate.get("dealt")?.let {
            rangeMismatch("damage dealt", event.damageAmount, it)?.let { reason -> return reason }
        }
        predicate.get("taken")?.let {
            rangeMismatch("damage taken", event.damageAmount, it)?.let { reason -> return reason }
        }
        predicate.get("type")?.let { typePredicate ->
            when {
                typePredicate.isJsonPrimitive -> {
                    val expected = ResourceLocation.parse(typePredicate.asString)
                    if (event.damageSource != expected) {
                        return "damage source expected $expected but was ${event.damageSource ?: "<missing>"}"
                    }
                }
                typePredicate.isJsonObject ->
                    typePredicate.asJsonObject.string("type")?.let {
                        val expected = ResourceLocation.parse(it)
                        if (event.damageSource != expected) {
                            return "damage source expected $expected but was ${event.damageSource ?: "<missing>"}"
                        }
                    }
                else -> return "damage type predicate must be a string or object"
            }
        }
        predicate.getAsJsonObject("source_entity")?.let {
            if (event.entity == null) return "damage source entity context missing"
            if (!predicates.testElement(entityCondition("this", it), context.copy(thisEntity = event.entity))) {
                return "damage source entity predicate did not match"
            }
        }
        predicate.getAsJsonObject("direct_entity")?.let {
            if (event.entity == null) return "damage direct entity context missing"
            if (!predicates.testElement(entityCondition("this", it), context.copy(thisEntity = event.entity))) {
                return "damage direct entity predicate did not match"
            }
        }
        return null
    }

    private fun rangeMismatch(
        label: String,
        actual: Double?,
        expected: JsonElement,
    ): String? {
        val value = actual ?: return "$label missing"
        if (expected.isJsonPrimitive) {
            val expectedValue = expected.asDouble
            return if (value == expectedValue) null else "$label expected $expectedValue but was $value"
        }
        val range =
            expected.takeIf { it.isJsonObject }?.asJsonObject
                ?: return "$label predicate must be a number or range object"
        range.get("min")?.let {
            if (value < it.asDouble) return "$label expected at least ${it.asDouble} but was $value"
        }
        range.get("max")?.let {
            if (value > it.asDouble) return "$label expected at most ${it.asDouble} but was $value"
        }
        return null
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}

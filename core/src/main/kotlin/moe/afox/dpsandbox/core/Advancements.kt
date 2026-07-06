package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class AdvancementUpdate(
    val advancement: ResourceLocation,
    val criterion: String,
    val completed: Boolean,
)

class AdvancementRuntime(private val sandbox: DatapackSandbox) {
    private val predicates = PredicateEngine(sandbox.datapack, sandbox.profile)
    private val loot = LootEngine(sandbox.datapack, sandbox.profile.registryView, sandbox.profile)

    fun grant(player: SandboxPlayer, id: ResourceLocation, criterion: String? = null): List<AdvancementUpdate> {
        val advancement = sandbox.datapack.advancement(id)
        val progress = progressFor(player, advancement)
        val criteriaToGrant = criterion?.let { listOf(it) } ?: advancement.criteria.keys.toList()
        val updates = criteriaToGrant.map {
            progress.criteria[it] = true
            AdvancementUpdate(id, it, progress.isDone(advancement.requirements))
        }
        if (progress.isDone(advancement.requirements)) applyRewards(player, advancement)
        return updates
    }

    fun revoke(player: SandboxPlayer, id: ResourceLocation, criterion: String? = null): List<AdvancementUpdate> {
        val advancement = sandbox.datapack.advancement(id)
        val progress = progressFor(player, advancement)
        val criteriaToRevoke = criterion?.let { listOf(it) } ?: advancement.criteria.keys.toList()
        return criteriaToRevoke.map {
            progress.criteria[it] = false
            AdvancementUpdate(id, it, progress.isDone(advancement.requirements))
        }
    }

    fun progress(player: SandboxPlayer, id: ResourceLocation): AdvancementProgress =
        progressFor(player, sandbox.datapack.advancement(id))

    fun handle(rawEvent: PlayerEvent): List<AdvancementUpdate> {
        val event = rawEvent.normalized()
        val player = sandbox.world.requirePlayer(event.playerName)
        val updates = mutableListOf<AdvancementUpdate>()
        sandbox.datapack.advancements.values.forEach { advancement ->
            val progress = progressFor(player, advancement)
            advancement.criteria.values.forEach { criterion ->
                if (progress.criteria[criterion.name] == true) return@forEach
                if (matchesCriterion(player, event, criterion)) {
                    progress.criteria[criterion.name] = true
                    val done = progress.isDone(advancement.requirements)
                    updates += AdvancementUpdate(advancement.id, criterion.name, done)
                    if (done) applyRewards(player, advancement)
                }
            }
        }
        return updates
    }

    private fun matchesCriterion(player: SandboxPlayer, event: PlayerEvent, criterion: Criterion): Boolean {
        val trigger = criterion.trigger.path
        if (!triggerMatchesEvent(trigger, event.type)) return false
        val conditions = criterion.conditions ?: return true
        val incomingDamageEvent = event.type in setOf("damage", "death", "entity_killed_player", "entity_hurt_player")
        val playerAttackEvent = event.type in setOf("killed_entity", "entity_killed", "player_killed_entity", "player_hurt_entity")
        val attacker = when {
            incomingDamageEvent -> event.entity
            playerAttackEvent -> player
            else -> event.entity
        }
        val predicateContext = PredicateContext(
            world = sandbox.world,
            origin = player.position,
            thisEntity = player,
            player = player,
            directEntity = attacker,
            attacker = attacker,
            attackingPlayer = when {
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
            if (!predicates.testElement(entityCondition("this", it), predicateContext.copy(thisEntity = player))) return false
        }
        conditions.getAsJsonObject("entity")?.let {
            if (!predicates.testElement(entityCondition("this", it), predicateContext.copy(thisEntity = event.entity))) return false
        }
        conditions.getAsJsonObject("item")?.let {
            if (event.item == null || !predicates.testItemPredicate(event.item, it)) return false
        }
        conditions.getAsJsonArray("items")?.let { items ->
            val matched = player.inventory.any { stack -> items.any { it.isJsonObject && predicates.testItemPredicate(stack, it.asJsonObject) } }
            if (!matched) return false
        }
        conditions.string("recipe")?.let { if (event.recipe != ResourceLocation.parse(it)) return false }
        conditions.string("block")?.let { if (event.block != ResourceLocation.parse(it)) return false }
        conditions.string("from")?.let { if (event.fromDimension != ResourceLocation.parse(it)) return false }
        conditions.string("to")?.let { if (event.toDimension != ResourceLocation.parse(it)) return false }
        conditions.string("damage_source")?.let { if (event.damageSource != ResourceLocation.parse(it)) return false }
        conditions.string("damageType")?.let { if (event.damageSource != ResourceLocation.parse(it)) return false }
        conditions.getAsJsonObject("damage")?.let { if (!damageMatches(event, it, predicateContext)) return false }
        conditions.string("key")?.let { if (event.input?.device != "keyboard" || event.input.code != it) return false }
        conditions.string("button")?.let { if (event.input?.device != "mouse" || event.input.code != it) return false }
        conditions.string("input")?.let { if (event.input?.code != it) return false }
        conditions.string("action")?.let { if (event.input?.action != it) return false }
        conditions.getAsJsonObject("location")?.let {
            if (!predicates.testElement(locationCondition(it), predicateContext)) return false
        }
        return true
    }

    private fun triggerMatchesEvent(trigger: String, eventType: String): Boolean =
        when (trigger) {
            "tick" -> eventType == "tick"
            "inventory_changed" -> eventType in setOf("inventory_changed", "item_picked_up", "item_added")
            "using_item", "item_used_on_block" -> eventType in setOf("item_used", "using_item", "item_used_on_block")
            "consume_item" -> eventType in setOf("item_consumed", "consume_item")
            "player_interacted_with_entity" -> eventType in setOf("entity_interacted", "player_interacted_with_entity")
            "damage" -> eventType == "damage"
            "death" -> eventType == "death"
            "entity_hurt_player" -> eventType in setOf("damage", "entity_hurt_player")
            "player_hurt_entity" -> eventType == "player_hurt_entity"
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

    private fun applyRewards(player: SandboxPlayer, advancement: AdvancementDefinition) {
        val rewards = advancement.rewards
        player.xp += rewards.experience
        rewards.recipes.forEach { player.recipes += it }
        rewards.function?.let { sandbox.runFunction(it, ExecutionContext(entity = player, position = player.position)) }
        rewards.loot.forEach { table ->
            val context = LootContext(
                type = ResourceLocation("minecraft", "advancement_reward"),
                predicateContext = PredicateContext(world = sandbox.world, player = player, thisEntity = player, origin = player.position),
                seed = sandbox.world.gameTime,
            )
            player.inventory += loot.generate(table, context).items
        }
    }

    private fun progressFor(player: SandboxPlayer, advancement: AdvancementDefinition): AdvancementProgress =
        player.advancementProgress.getOrPut(advancement.id) {
            AdvancementProgress(advancement.criteria.keys.associateWith { false }.toMutableMap())
        }

    private fun entityCondition(entity: String, predicate: JsonObject): JsonObject {
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

    private fun damageMatches(event: PlayerEvent, predicate: JsonObject, context: PredicateContext): Boolean {
        predicate.get("amount")?.let { if (!rangeMatches(event.damageAmount, it)) return false }
        predicate.get("dealt")?.let { if (!rangeMatches(event.damageAmount, it)) return false }
        predicate.get("taken")?.let { if (!rangeMatches(event.damageAmount, it)) return false }
        predicate.get("type")?.let { typePredicate ->
            when {
                typePredicate.isJsonPrimitive -> if (event.damageSource != ResourceLocation.parse(typePredicate.asString)) return false
                typePredicate.isJsonObject -> typePredicate.asJsonObject.string("type")?.let {
                    if (event.damageSource != ResourceLocation.parse(it)) return false
                }
                else -> return false
            }
        }
        predicate.getAsJsonObject("source_entity")?.let {
            if (!predicates.testElement(entityCondition("this", it), context.copy(thisEntity = event.entity))) return false
        }
        predicate.getAsJsonObject("direct_entity")?.let {
            if (!predicates.testElement(entityCondition("this", it), context.copy(thisEntity = event.entity))) return false
        }
        return true
    }

    private fun rangeMatches(actual: Double?, expected: JsonElement): Boolean {
        val value = actual ?: return false
        if (expected.isJsonPrimitive) return value == expected.asDouble
        val range = expected.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        range.get("min")?.let { if (value < it.asDouble) return false }
        range.get("max")?.let { if (value > it.asDouble) return false }
        return true
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString
}

package moe.afox.dpsandbox.core.command
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.EntitySelectors
import moe.afox.dpsandbox.core.EquipmentSlots
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.ItemStack
import moe.afox.dpsandbox.core.PlayerEffect
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.toJson
import moe.afox.dpsandbox.core.unsupportedFeature

internal fun DatapackSandbox.executeClear(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    val targets = tokens.getOrNull(1)?.text?.let { resolvePlayers(it, location, context) } ?: world.players.values.toList()
    val item = tokens.getOrNull(2)?.text?.let { parseItemStackArgument(it, 1, location) }
    val maxCount = tokens.getOrNull(3)?.text?.let { parseInt(it, "clear max count", location) } ?: Int.MAX_VALUE
    if (maxCount < 0) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "clear max count must be non-negative", location)
    }
    val queryOnly = maxCount == 0
    var matched = 0
    var removed = 0
    targets.forEach { player ->
        var remaining = maxCount
        val iterator = player.inventory.listIterator()
        while (iterator.hasNext()) {
            val stack = iterator.next()
            if (item == null || stack.matchesClearItem(item)) {
                matched += stack.count
                if (!queryOnly && remaining > 0) {
                    val removedFromStack = minOf(stack.count, remaining)
                    stack.count -= removedFromStack
                    remaining -= removedFromStack
                    removed += removedFromStack
                    if (stack.count <= 0) iterator.remove()
                }
            }
        }
    }
    val result = if (queryOnly) matched else removed
    world.recordOutput(
        "clear",
        "data",
        text = result.toString(),
        payload =
            JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> targets.forEach { array.add(it.name) } })
                item?.let { payload.addProperty("item", it.id.toString()) }
                payload.addProperty("maxCount", maxCount)
                payload.addProperty("query", queryOnly)
                payload.addProperty("matched", matched)
                payload.addProperty("removed", removed)
            },
    )
}

internal fun DatapackSandbox.executeGive(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 3, "give <targets> <item> [count]", location)
    val count = tokens.getOrNull(3)?.text?.let { parseInt(it, "item count", location) } ?: 1
    val item = parseItemStackArgument(tokens[2].text, count.coerceAtLeast(0), location)
    val players = resolvePlayers(tokens[1].text, location, context)
    players.forEach { player ->
        player.inventory += item.copyStack()
        advancements.handle(PlayerEvent(player.name, "inventory_changed", item = item.copyStack()))
    }
    world.recordOutput(
        "give",
        "data",
        targets = players.map { it.name },
        text = (item.count * players.size).toString(),
        payload =
            JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> players.forEach { array.add(it.name) } })
                payload.add("item", itemStackOutput(item))
                payload.addProperty("count", item.count)
                payload.addProperty("totalCount", item.count * players.size)
            },
    )
}

internal fun DatapackSandbox.executeEffect(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "effect <give|clear> ...", location)
    when (tokens[1].text) {
        "give" -> {
            requireSize(tokens, 4, "effect give <targets> <effect> [seconds] [amplifier] [hideParticles]", location)
            val effect = ResourceLocation.parse(tokens[3].text)
            val duration = tokens.getOrNull(4)?.text?.let { parseInt(it, "effect seconds", location) * 20 } ?: 600
            val amplifier = tokens.getOrNull(5)?.text?.let { parseInt(it, "effect amplifier", location) } ?: 0
            val hide = tokens.getOrNull(6)?.text?.let { parseBoolean(it, "hide particles", location) } ?: false
            val entities = EntitySelectors.select(world, tokens[2].text, context, location)
            val effectState = PlayerEffect(effect, duration, amplifier, hide)
            entities.forEach { entity ->
                applyEffect(entity, effectState)
            }
            recordEffectOutput("effect give", entities, effectState)
        }
        "clear" -> {
            requireSize(tokens, 3, "effect clear <targets> [effect]", location)
            val effect = tokens.getOrNull(3)?.text?.let(ResourceLocation::parse)
            val entities = EntitySelectors.select(world, tokens[2].text, context, location)
            entities.forEach { entity ->
                clearEffect(entity, effect)
            }
            recordEffectClearOutput(entities, effect)
        }
        else -> unsupportedFeature("Unsupported effect action '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.applyEffect(
    entity: SandboxEntity,
    effect: PlayerEffect,
) {
    if (entity is SandboxPlayer) {
        entity.effects += effect.id
        entity.effectDetails[effect.id] = effect
        advancements.handle(PlayerEvent(entity.name, "effects_changed"))
    } else {
        entity.activeEffects[effect.id] = effect
    }
}

internal fun DatapackSandbox.clearEffect(
    entity: SandboxEntity,
    effect: ResourceLocation?,
) {
    if (entity is SandboxPlayer) {
        if (effect == null) {
            entity.effects.clear()
            entity.effectDetails.clear()
        } else {
            entity.effects -= effect
            entity.effectDetails.remove(effect)
        }
        advancements.handle(PlayerEvent(entity.name, "effects_changed"))
    } else if (effect == null) {
        entity.activeEffects.clear()
    } else {
        entity.activeEffects.remove(effect)
    }
}

internal fun DatapackSandbox.recordEffectOutput(
    command: String,
    entities: List<SandboxEntity>,
    effect: PlayerEffect,
) {
    world.recordOutput(
        command,
        "data",
        targets = entities.map { it.scoreHolder },
        text = entities.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> entities.forEach { array.add(it.scoreHolder) } })
                payload.add("effect", effect.toJson())
                payload.addProperty("count", entities.size)
            },
    )
}

internal fun DatapackSandbox.recordEffectClearOutput(
    entities: List<SandboxEntity>,
    effect: ResourceLocation?,
) {
    world.recordOutput(
        "effect clear",
        "data",
        targets = entities.map { it.scoreHolder },
        text = entities.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> entities.forEach { array.add(it.scoreHolder) } })
                effect?.let { payload.addProperty("effect", it.toString()) }
                payload.addProperty("all", effect == null)
                payload.addProperty("count", entities.size)
            },
    )
}

internal fun DatapackSandbox.executeEnchant(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 3, "enchant <targets> <enchantment> [level]", location)
    val enchantment = ResourceLocation.parse(tokens[2].text)
    val level = tokens.getOrNull(3)?.text?.let { parseInt(it, "enchantment level", location) } ?: 1
    val enchanted = mutableListOf<Pair<String, ItemStack>>()
    EntitySelectors.select(world, tokens[1].text, context, location).forEach { entity ->
        val item =
            if (entity is SandboxPlayer) {
                playerSelectedItemForEnchant(entity)
            } else {
                entity.equipment[EquipmentSlots.MAINHAND] ?: return@forEach
            }
        enchantItem(item, enchantment, level)
        enchanted += entity.scoreHolder to item.copyStack()
    }
    recordEnchantOutput(enchantment, level, enchanted, location)
}

internal fun DatapackSandbox.playerSelectedItemForEnchant(player: SandboxPlayer): ItemStack {
    while (player.inventory.size <= player.selectedSlot) {
        player.inventory += ItemStack(ResourceLocation("minecraft", "air"))
    }
    return player.inventory[player.selectedSlot]
}

internal fun DatapackSandbox.enchantItem(
    item: ItemStack,
    enchantment: ResourceLocation,
    level: Int,
) {
    val enchantments =
        item.components.getAsJsonObject("minecraft:enchantments") ?: JsonObject().also {
            item.components.add("minecraft:enchantments", it)
        }
    enchantments.addProperty(enchantment.toString(), level)
}

internal fun DatapackSandbox.recordEnchantOutput(
    enchantment: ResourceLocation,
    level: Int,
    enchanted: List<Pair<String, ItemStack>>,
    location: SourceLocation?,
) {
    world.recordOutput(
        "enchant",
        "data",
        targets = enchanted.map { it.first },
        text = enchanted.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("enchantment", enchantment.toString())
                enchantmentPayload(enchantment, location)?.let { payload.add("enchantmentResource", it) }
                payload.addProperty("level", level)
                payload.addProperty("modified", enchanted.size)
                payload.add(
                    "items",
                    JsonArray().also { items ->
                        enchanted.forEach { (target, item) ->
                            items.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("target", target)
                                    entry.add("item", itemStackOutput(item))
                                },
                            )
                        }
                    },
                )
            },
    )
}

internal fun DatapackSandbox.enchantmentPayload(
    id: ResourceLocation,
    location: SourceLocation?,
): JsonObject? {
    val resource = datapack.rawResources["enchantment"]?.get(id) ?: return null
    if (!resource.root.isJsonObject) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Enchantment resource '$id' must be a JSON object", location)
    }
    return JsonObject().also { payload ->
        payload.addProperty("id", id.toString())
        payload.addProperty("resource", resource.file)
        payload.addProperty("version", resource.version ?: profile.id)
        payload.add("definition", resource.root.deepCopy())
    }
}

internal fun DatapackSandbox.executeExperience(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "${tokens[0].text} <add|set|query> ...", location)
    when (tokens[1].text) {
        "add" -> {
            requireSize(tokens, 4, "${tokens[0].text} add <targets> <amount> [points|levels]", location)
            val amount = parseInt(tokens[3].text, "experience amount", location)
            val kind = experienceKind(tokens.getOrNull(4)?.text ?: "points", tokens[0].text, location)
            val players = resolvePlayers(tokens[2].text, location, context)
            players.forEach { player ->
                when (kind) {
                    "points" -> player.xp += amount
                    "levels" -> player.xpLevels += amount
                }
            }
            recordExperienceOutput("${tokens[0].text} add", players, kind, amount)
        }
        "set" -> {
            requireSize(tokens, 4, "${tokens[0].text} set <targets> <amount> [points|levels]", location)
            val amount = parseInt(tokens[3].text, "experience amount", location)
            val kind = experienceKind(tokens.getOrNull(4)?.text ?: "points", tokens[0].text, location)
            val players = resolvePlayers(tokens[2].text, location, context)
            players.forEach { player ->
                when (kind) {
                    "points" -> player.xp = amount
                    "levels" -> player.xpLevels = amount
                }
            }
            recordExperienceOutput("${tokens[0].text} set", players, kind, amount)
        }
        "query" -> {
            requireSize(tokens, 4, "${tokens[0].text} query <target> <points|levels>", location)
            val player = resolvePlayers(tokens[2].text, location, context).first()
            val kind = experienceKind(tokens[3].text, tokens[0].text, location)
            val value = experienceValue(player, kind)
            world.recordOutput(
                "${tokens[0].text} query",
                "data",
                text = value.toString(),
                payload =
                    JsonObject().also { payload ->
                        payload.addProperty("player", player.name)
                        payload.addProperty("kind", kind)
                        payload.addProperty("value", value)
                        payload.addProperty("points", player.xp)
                        payload.addProperty("levels", player.xpLevels)
                    },
            )
        }
        else -> unsupportedFeature("Unsupported ${tokens[0].text} action '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.experienceKind(
    raw: String,
    command: String,
    location: SourceLocation?,
): String =
    when (raw) {
        "points", "levels" -> raw
        else -> unsupportedFeature("Unsupported $command experience type '$raw'", profile.id, location)
    }

internal fun DatapackSandbox.experienceValue(
    player: SandboxPlayer,
    kind: String,
): Int =
    when (kind) {
        "points" -> player.xp
        "levels" -> player.xpLevels
        else -> player.xp
    }

internal fun DatapackSandbox.recordExperienceOutput(
    command: String,
    players: List<SandboxPlayer>,
    kind: String,
    amount: Int,
) {
    world.recordOutput(
        command,
        "data",
        targets = players.map { it.name },
        text = amount.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("kind", kind)
                payload.addProperty("amount", amount)
                payload.add(
                    "players",
                    JsonArray().also { array ->
                        players.sortedBy { it.name }.forEach { player ->
                            array.add(
                                JsonObject().also { item ->
                                    item.addProperty("name", player.name)
                                    item.addProperty("points", player.xp)
                                    item.addProperty("levels", player.xpLevels)
                                },
                            )
                        }
                    },
                )
            },
    )
}

internal fun DatapackSandbox.executeRecipe(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 4, "recipe <give|take> <targets> <recipe|*>", location)
    val targets = resolvePlayers(tokens[2].text, location, context)
    val recipe = tokens[3].text
    val recipeIds = if (recipe == "*") datapack.recipes.keys.sorted() else listOf(ResourceLocation.parse(recipe))
    var changed = 0
    val changedRecipes = sortedSetOf<ResourceLocation>()
    targets.forEach { player ->
        when (tokens[1].text) {
            "give" ->
                recipeIds.forEach { id ->
                    if (player.recipes.add(id)) {
                        changed += 1
                        changedRecipes += id
                        advancements.handle(PlayerEvent(player.name, "recipe_unlocked", recipe = id))
                    }
                }
            "take" -> {
                if (recipe == "*") {
                    val removed = player.recipes.toList()
                    changed += removed.size
                    changedRecipes += removed
                    player.recipes.clear()
                } else if (player.recipes.remove(recipeIds.single())) {
                    changed += 1
                    changedRecipes += recipeIds.single()
                }
            }
            else -> unsupportedFeature("Unsupported recipe action '${tokens[1].text}'", profile.id, location)
        }
    }
    world.recordOutput(
        "recipe ${tokens[1].text}",
        "data",
        text = changed.toString(),
        payload =
            JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> targets.forEach { array.add(it.name) } })
                payload.addProperty("recipe", recipe)
                payload.addProperty("changed", changed)
                payload.add(
                    "changedRecipes",
                    JsonArray().also { array ->
                        changedRecipes.forEach { array.add(it.toString()) }
                    },
                )
            },
    )
}

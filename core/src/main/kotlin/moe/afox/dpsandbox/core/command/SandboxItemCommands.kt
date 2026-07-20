package moe.afox.dpsandbox.core.command
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.AdvancementRuntime
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.Datapack
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.EntitySelectors
import moe.afox.dpsandbox.core.EquipmentSlots
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.ItemStack
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.PredicateContext
import moe.afox.dpsandbox.core.PredicateEngine
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SandboxWorld
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.SpecialEntitySupport
import moe.afox.dpsandbox.core.VersionProfile
import moe.afox.dpsandbox.core.WeatherState
import moe.afox.dpsandbox.core.itemStackFromNbtJson
import moe.afox.dpsandbox.core.unsupportedFeature
import kotlin.math.max

internal class SandboxItemCommands(
    private val sandbox: DatapackSandbox,
) {
    private val world: SandboxWorld get() = sandbox.world
    private val datapack: Datapack get() = sandbox.datapack
    private val profile: VersionProfile get() = sandbox.profile
    private val predicates: PredicateEngine get() = sandbox.predicates
    private val advancements: AdvancementRuntime get() = sandbox.advancements

    private fun requireSize(tokens: List<CommandToken>, size: Int, usage: String, location: SourceLocation?) =
        sandbox.requireSize(tokens, size, usage, location)

    private fun requireSizeFrom(tokens: List<CommandToken>, index: Int, count: Int, usage: String, location: SourceLocation?) =
        sandbox.requireSizeFrom(tokens, index, count, usage, location)

    private fun requireIndex(tokens: List<CommandToken>, index: Int, usage: String, location: SourceLocation?) =
        sandbox.requireIndex(tokens, index, usage, location)

    private fun parseInt(raw: String, label: String, location: SourceLocation?): Int = sandbox.parseInt(raw, label, location)

    private fun parseDouble(raw: String, label: String, location: SourceLocation?): Double = sandbox.parseDouble(raw, label, location)

    private fun parseBlockPos(
        tokens: List<CommandToken>,
        index: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): BlockPos = sandbox.parseBlockPos(tokens, index, context, location)

    private fun inventorySlot(raw: String): Int = sandbox.inventorySlot(raw)

    private fun replaceBlockItem(pos: BlockPos, slot: Int, item: ItemStack?, location: SourceLocation?) =
        sandbox.replaceBlockItem(pos, slot, item, location)

    private fun currentWeatherState(): WeatherState = sandbox.currentWeatherState()

    private fun itemStackOutput(item: ItemStack): JsonObject = sandbox.itemStackOutput(item)

    fun execute(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        requireSize(tokens, 2, "item <replace|modify> ...", location)
        when (tokens[1].text) {
            "replace" -> executeItemReplace(tokens, location, context)
            "modify" -> executeItemModify(tokens, location, context)
            else -> unsupportedFeature("Unsupported item action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeItemModify(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        requireSize(tokens, 3, "item modify <entity|block> ...", location)
        when (tokens[2].text) {
            "entity" -> {
                requireSize(tokens, 6, "item modify entity <targets> <slot> <modifier>", location)
                val modifierId = ResourceLocation.parse(tokens[5].text)
                val modifier = datapack.itemModifier(modifierId)
                val modified = mutableListOf<Pair<String, ItemStack>>()
                EntitySelectors.select(world, tokens[3].text, context, location).forEach { entity ->
                    val access = entityItemAccess(entity, tokens[4].text, location)
                    val item = access.get() ?: return@forEach
                    if (item.id == ResourceLocation("minecraft", "air") || item.count <= 0) return@forEach
                    val updated = applyItemModifier(item, modifier.root, { stack -> itemModifierPredicateContext(entity, stack) }, location)
                    access.set(updated)
                    modified += entity.scoreHolder to updated.copyStack()
                    if (entity is SandboxPlayer) {
                        advancements.handle(PlayerEvent(entity.name, "inventory_changed", item = updated))
                    }
                }
                recordItemModifyOutput("entity", tokens[4].text, modifierId, modified)
            }
            "block" -> {
                requireSize(tokens, 8, "item modify block <pos> <slot> <modifier>", location)
                val pos = parseBlockPos(tokens, 3, context, location)
                val slot = inventorySlot(tokens[6].text)
                val modifierId = ResourceLocation.parse(tokens[7].text)
                val modifier = datapack.itemModifier(modifierId)
                val item = blockItem(pos, slot, location)
                val modified =
                    if (item != null && item.id != ResourceLocation("minecraft", "air") && item.count > 0) {
                        val updated =
                            applyItemModifier(item, modifier.root, { stack -> itemModifierPredicateContext(pos, stack) }, location)
                        replaceBlockItem(pos, slot, updated, location)
                        listOf(pos.toString() to updated.copyStack())
                    } else {
                        emptyList()
                    }
                recordItemModifyOutput("block", tokens[6].text, modifierId, modified)
            }
            else -> unsupportedFeature("Unsupported item modify target '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun recordItemModifyOutput(
        targetKind: String,
        slot: String,
        modifier: ResourceLocation,
        modified: List<Pair<String, ItemStack>>,
    ) {
        world.recordOutput(
            "item modify",
            "data",
            targets = modified.map { it.first },
            text = modified.size.toString(),
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("targetKind", targetKind)
                    payload.addProperty("slot", slot)
                    payload.addProperty("modifier", modifier.toString())
                    payload.addProperty("modified", modified.size)
                    payload.add(
                        "items",
                        JsonArray().also { items ->
                            modified.forEach { (target, item) ->
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

    private fun executeItemReplace(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        requireSize(tokens, 3, "item replace <entity|block> ...", location)
        when (tokens[2].text) {
            "entity" -> {
                requireSize(tokens, 6, "item replace entity <targets> <slot> <with|from> ...", location)
                val item =
                    when (tokens[5].text) {
                        "with" -> {
                            requireSize(tokens, 7, "item replace entity <targets> <slot> with <item> [count]", location)
                            val count = tokens.getOrNull(7)?.text?.let { parseInt(it, "item count", location) } ?: 1
                            parseItemStackArgument(tokens[6].text, count, location)
                        }
                        "from" -> readItemSource(tokens, 6, context, location)
                        else -> unsupportedFeature("Expected 'with' or 'from' in item replace entity", profile.id, location)
                    }
                val entities = EntitySelectors.select(world, tokens[3].text, context, location)
                entities.forEach { entity ->
                    entityItemAccess(entity, tokens[4].text, location).set(item)
                }
                recordItemReplaceOutput("entity", entities.map { it.scoreHolder }, tokens[4].text, item)
            }
            "block" -> {
                requireSize(tokens, 8, "item replace block <pos> <slot> <with|from> ...", location)
                val pos = parseBlockPos(tokens, 3, context, location)
                val slot = inventorySlot(tokens[6].text)
                val item =
                    when (tokens[7].text) {
                        "with" -> {
                            requireSize(tokens, 9, "item replace block <pos> <slot> with <item> [count]", location)
                            val count = tokens.getOrNull(9)?.text?.let { parseInt(it, "item count", location) } ?: 1
                            parseItemStackArgument(tokens[8].text, count, location)
                        }
                        "from" -> readItemSource(tokens, 8, context, location)
                        else -> unsupportedFeature("Expected 'with' or 'from' in item replace block", profile.id, location)
                    }
                replaceBlockItem(pos, slot, item, location)
                recordItemReplaceOutput("block", listOf(pos.toString()), tokens[6].text, item)
            }
            else -> unsupportedFeature("Unsupported item replace target '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun recordItemReplaceOutput(
        targetKind: String,
        targets: List<String>,
        slot: String,
        item: ItemStack?,
    ) {
        world.recordOutput(
            "item replace",
            "data",
            targets = targets,
            text = targets.size.toString(),
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("targetKind", targetKind)
                    payload.add("targets", JsonArray().also { array -> targets.forEach { array.add(it) } })
                    payload.addProperty("slot", slot)
                    item?.let { payload.add("item", itemStackOutput(it)) }
                },
        )
    }

    private enum class PlayerItemContainer {
        INVENTORY,
        ENDER_ITEMS,
    }

    private data class PlayerItemSlot(
        val container: PlayerItemContainer,
        val index: Int,
    )

    private fun replacePlayerItem(
        player: SandboxPlayer,
        slot: PlayerItemSlot,
        item: ItemStack?,
    ) {
        val items = playerItems(player, slot.container)
        while (items.size <= slot.index) items += ItemStack(ResourceLocation("minecraft", "air"), 0)
        items[slot.index] = item?.copyStack() ?: ItemStack(ResourceLocation("minecraft", "air"), 0)
    }

    private fun playerItem(
        player: SandboxPlayer,
        slot: PlayerItemSlot,
    ): ItemStack? = playerItems(player, slot.container).getOrNull(slot.index)?.copyStack()

    private fun playerItems(
        player: SandboxPlayer,
        container: PlayerItemContainer,
    ): MutableList<ItemStack> =
        when (container) {
            PlayerItemContainer.INVENTORY -> player.inventory
            PlayerItemContainer.ENDER_ITEMS -> player.enderItems
        }

    private fun playerItemSlot(
        player: SandboxPlayer,
        rawSlot: String,
    ): PlayerItemSlot =
        if (isEnderItemSlot(rawSlot)) {
            PlayerItemSlot(
                PlayerItemContainer.ENDER_ITEMS,
                rawSlot.substringAfter('.').toIntOrNull()?.coerceAtLeast(0) ?: 0,
            )
        } else {
            PlayerItemSlot(PlayerItemContainer.INVENTORY, playerInventorySlot(player, rawSlot))
        }

    private fun playerInventorySlot(
        player: SandboxPlayer,
        rawSlot: String,
    ): Int =
        when (rawSlot) {
            "weapon.mainhand", "hotbar.selected" -> player.selectedSlot.coerceIn(0, 8)
            else -> inventorySlot(rawSlot)
        }

    private fun isEnderItemSlot(rawSlot: String): Boolean =
        rawSlot.startsWith("enderchest.") ||
            rawSlot.startsWith("ender_chest.") ||
            rawSlot.startsWith("enderChest.") ||
            rawSlot.startsWith("enderItems.") ||
            rawSlot.startsWith("ender_items.") ||
            rawSlot.startsWith("ender.")

    data class EntityItemAccess(
        val get: () -> ItemStack?,
        val set: (ItemStack?) -> Unit,
    )

    fun entityItemAccess(
        entity: SandboxEntity,
        rawSlot: String,
        location: SourceLocation?,
    ): EntityItemAccess {
        if (entity is SandboxPlayer) {
            val slot = playerItemSlot(entity, rawSlot)
            return EntityItemAccess(
                get = { playerItem(entity, slot) },
                set = { item -> replacePlayerItem(entity, slot, item) },
            )
        }

        if (SpecialEntitySupport.isItemDisplay(entity.type)) {
            if (rawSlot !in setOf("inventory.0", "contents")) {
                unsupportedFeature("Item display only exposes slot inventory.0", profile.id, location)
            }
            return EntityItemAccess(
                get = { SpecialEntitySupport.itemDisplayItem(entity)?.copyStack() },
                set = { item -> SpecialEntitySupport.setItemDisplayItem(entity, item) },
            )
        }
        if (SpecialEntitySupport.isNonLivingSpecial(entity.type)) {
            unsupportedFeature("Entity ${entity.type} does not expose item or equipment slots", profile.id, location)
        }

        val slot =
            EquipmentSlots.canonical(rawSlot)
                ?: unsupportedFeature("Entity slot '$rawSlot' is only supported for players or equipment slots", profile.id, location)
        return EntityItemAccess(
            get = { entity.equipment[slot]?.copyStack() },
            set = { item -> replaceEntityEquipmentItem(entity, slot, item) },
        )
    }

    private fun replaceEntityEquipmentItem(
        entity: SandboxEntity,
        slot: String,
        item: ItemStack?,
    ) {
        if (item != null && item.count > 0 && item.id != ResourceLocation("minecraft", "air")) {
            entity.equipment[slot] = item.copyStack()
        } else {
            entity.equipment.remove(slot)
        }
    }

    private fun readItemSource(
        tokens: List<CommandToken>,
        index: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): ItemStack? {
        requireIndex(tokens, index, "item source <entity|block>", location)
        return when (tokens[index].text) {
            "entity" -> {
                requireSizeFrom(tokens, index, 3, "item source entity <source> <slot>", location)
                val source =
                    EntitySelectors.select(world, tokens[index + 1].text, context, location).firstOrNull()
                        ?: throw SandboxException(
                            DiagnosticCode.COMMAND_ERROR,
                            "Item source entity '${tokens[index + 1].text}' did not match an entity",
                            location,
                        )
                entityItemAccess(source, tokens[index + 2].text, location).get()
            }
            "block" -> {
                requireSizeFrom(tokens, index, 5, "item source block <pos> <slot>", location)
                blockItem(parseBlockPos(tokens, index + 1, context, location), inventorySlot(tokens[index + 4].text), location)?.copyStack()
            }
            else -> unsupportedFeature("Unsupported item source '${tokens[index].text}'", profile.id, location)
        }
    }

    fun parseItemStackArgument(
        raw: String,
        count: Int,
        location: SourceLocation?,
    ): ItemStack {
        val firstPayload = raw.indexOfFirst { it == '[' || it == '{' }
        val idText = if (firstPayload >= 0) raw.substring(0, firstPayload) else raw
        if (idText.isBlank()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item id is missing in '$raw'", location)
        }

        val components = JsonObject()
        val nbt = JsonObject()
        var index = firstPayload.takeIf { it >= 0 } ?: raw.length
        while (index < raw.length) {
            when (raw[index]) {
                '[' -> {
                    val end = matchingItemPayloadEnd(raw, index, '[', ']', location)
                    parseItemComponents(raw.substring(index + 1, end), components, nbt, location)
                    index = end + 1
                }
                '{' -> {
                    val end = matchingItemPayloadEnd(raw, index, '{', '}', location)
                    val parsed = JsonValues.parse(raw.substring(index, end + 1), location)
                    if (!parsed.isJsonObject) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item NBT must be an object", location)
                    }
                    JsonPaths.merge(nbt, null, parsed.asJsonObject)
                    index = end + 1
                }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item argument '$raw'", location)
            }
        }
        return ItemStack(ResourceLocation.parse(idText), count, components, nbt)
    }

    private fun parseItemComponents(
        raw: String,
        components: JsonObject,
        nbt: JsonObject,
        location: SourceLocation?,
    ) {
        splitItemPayload(raw, location)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") }
            .forEach { entry ->
                val equalsIndex = topLevelEquals(entry, location)
                if (equalsIndex <= 0) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component '$entry'", location)
                }
                val key = canonicalItemComponent(entry.substring(0, equalsIndex).trim())
                val value = JsonValues.parse(entry.substring(equalsIndex + 1).trim(), location)
                components.add(key, value.deepCopy())
                if (key == "minecraft:custom_data") {
                    if (!value.isJsonObject) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item custom_data component must be an object", location)
                    }
                    JsonPaths.merge(nbt, null, value)
                }
            }
    }

    private fun canonicalItemComponent(raw: String): String {
        if (raw.isBlank()) return raw
        return if (':' in raw) raw else "minecraft:$raw"
    }

    private fun splitItemPayload(
        raw: String,
        location: SourceLocation?,
    ): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var objectDepth = 0
        var arrayDepth = 0
        var quote: Char? = null
        var escaped = false
        raw.forEachIndexed { index, char ->
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                return@forEachIndexed
            }
            when (char) {
                '"', '\'' -> quote = char
                '{' -> objectDepth++
                '}' -> objectDepth--
                '[' -> arrayDepth++
                ']' -> arrayDepth--
                ',' ->
                    if (objectDepth == 0 && arrayDepth == 0) {
                        parts += raw.substring(start, index)
                        start = index + 1
                    }
            }
            if (objectDepth < 0 || arrayDepth < 0) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component payload '$raw'", location)
            }
        }
        if (quote != null || objectDepth != 0 || arrayDepth != 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component payload '$raw'", location)
        }
        parts += raw.substring(start)
        return parts
    }

    private fun topLevelEquals(
        raw: String,
        location: SourceLocation?,
    ): Int {
        var objectDepth = 0
        var arrayDepth = 0
        var quote: Char? = null
        var escaped = false
        raw.forEachIndexed { index, char ->
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                return@forEachIndexed
            }
            when (char) {
                '"', '\'' -> quote = char
                '{' -> objectDepth++
                '}' -> objectDepth--
                '[' -> arrayDepth++
                ']' -> arrayDepth--
                '=' -> if (objectDepth == 0 && arrayDepth == 0) return index
            }
            if (objectDepth < 0 || arrayDepth < 0) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component '$raw'", location)
            }
        }
        if (quote != null || objectDepth != 0 || arrayDepth != 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component '$raw'", location)
        }
        return -1
    }

    private fun matchingItemPayloadEnd(
        raw: String,
        start: Int,
        open: Char,
        close: Char,
        location: SourceLocation?,
    ): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                continue
            }
            when (char) {
                '"', '\'' -> quote = char
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item argument '$raw'", location)
    }

    fun matchesClearItem(item: ItemStack, expected: ItemStack): Boolean =
        item.id == expected.id &&
            jsonContainsAll(item.components, expected.components) &&
            jsonContainsAll(item.nbt, expected.nbt)

    private fun jsonContainsAll(
        actual: JsonObject,
        expected: JsonObject,
    ): Boolean =
        expected.entrySet().all { (key, expectedValue) ->
            val actualValue = actual.get(key) ?: return@all false
            when {
                expectedValue.isJsonObject && actualValue.isJsonObject ->
                    jsonContainsAll(
                        actualValue.asJsonObject,
                        expectedValue.asJsonObject,
                    )
                expectedValue.isJsonArray && actualValue.isJsonArray -> actualValue == expectedValue
                else -> actualValue == expectedValue
            }
        }

    fun copyStackValue(item: ItemStack): ItemStack =
        item.copy(components = item.components.deepCopy(), nbt = item.nbt.deepCopy())

    private fun ItemStack.copyStack(): ItemStack = copyStackValue(this)

    private fun blockItem(
        pos: BlockPos,
        slot: Int,
        location: SourceLocation?,
    ): ItemStack? {
        val block = world.requireBlock(pos)
        val items = block.fullNbt(pos, profile, location).getAsJsonArray("Items") ?: return null
        val itemJson =
            items
                .firstOrNull {
                    it.isJsonObject && it.asJsonObject.get("Slot")?.asInt == slot
                }?.asJsonObject ?: return null
        return itemStackFromJson(itemJson)
    }

    private fun itemStackFromJson(json: JsonObject): ItemStack? = itemStackFromNbtJson(json)

    private fun applyItemModifier(
        item: ItemStack,
        root: JsonElement,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
    ): ItemStack = applyItemModifier(item, root, predicateContext, location, mutableSetOf())

    private fun applyItemModifier(
        item: ItemStack,
        root: JsonElement,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
        activeReferences: MutableSet<ResourceLocation>,
    ): ItemStack {
        var stack = item.copy(components = item.components.deepCopy(), nbt = item.nbt.deepCopy())
        val functions =
            when {
                root.isJsonArray -> root.asJsonArray.toList()
                root.isJsonObject -> listOf(root)
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier must be an object or array", location)
            }
        functions.forEach { element ->
            if (!element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier entries must be objects", location)
            }
            val function = element.asJsonObject
            val conditions = function.get("conditions")
            if (conditions != null && !predicates.testConditions(conditions, predicateContext(stack))) {
                return@forEach
            }
            when (val type = itemModifierType(function, location)) {
                "set_components" -> {
                    function.getAsJsonObject("components")?.entrySet()?.forEach { (key, value) ->
                        stack.components.add(key, value.deepCopy())
                    }
                }
                "set_custom_data", "set_nbt" -> {
                    val tag = function.get("tag") ?: function.get("nbt")
                    val parsed =
                        when {
                            tag == null -> JsonObject()
                            tag.isJsonPrimitive && tag.asJsonPrimitive.isString -> JsonValues.parse(tag.asString, location)
                            else -> tag
                        }
                    if (!parsed.isJsonObject) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier custom data must be an object", location)
                    }
                    JsonPaths.merge(stack.nbt, null, parsed)
                }
                "set_count" -> stack = stack.copy(count = itemModifierCount(function.get("count"), stack.count).coerceAtLeast(0))
                "limit_count" -> stack = stack.copy(count = itemModifierLimit(stack.count, function.get("limit")).coerceAtLeast(0))
                "set_item" -> stack = stack.copy(id = itemModifierResource(function, "item", type, location))
                "discard" -> stack = ItemStack(ResourceLocation("minecraft", "air"), 0)
                "set_damage" -> stack.components.addProperty("minecraft:damage", itemModifierNumber(function.get("damage"), 0.0))
                "set_name" -> stack.components.add("minecraft:custom_name", itemModifierText(function, "name", type, location))
                "set_lore" -> stack.components.add("minecraft:lore", itemModifierLore(function, location))
                "copy_nbt" -> copyItemModifierNbt(stack, function, predicateContext(stack), location)
                "copy_components" -> copyItemModifierComponents(stack, function, predicateContext(stack), location)
                "reference" -> {
                    val id = itemModifierResource(function, "name", type, location)
                    stack = applyReferencedItemModifier(stack, id, predicateContext, location, activeReferences)
                }
                "filtered" -> {
                    val itemFilter = itemModifierObject(function, "item_filter", type, location)
                    val branchName = if (predicates.testItemPredicate(stack, itemFilter)) "on_pass" else "on_fail"
                    function.get(branchName)?.takeUnless { it.isJsonNull }?.let { branch ->
                        stack = applyItemModifierBranch(stack, branch, predicateContext, location, activeReferences, branchName)
                    }
                }
                "sequence" -> {
                    val nested =
                        function.get("functions")
                            ?: throw SandboxException(
                                DiagnosticCode.INPUT_FORMAT,
                                "Item modifier 'sequence' requires 'functions'",
                                location,
                            )
                    stack = applyItemModifier(stack, nested, predicateContext, location, activeReferences)
                }
                else -> unsupportedFeature("Item modifier function '$type' is not implemented", profile.id, location)
            }
        }
        return stack
    }

    private fun applyItemModifierBranch(
        item: ItemStack,
        branch: JsonElement,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
        activeReferences: MutableSet<ResourceLocation>,
        branchName: String,
    ): ItemStack =
        when {
            branch.isJsonPrimitive && branch.asJsonPrimitive.isString ->
                applyReferencedItemModifier(item, ResourceLocation.parse(branch.asString), predicateContext, location, activeReferences)
            branch.isJsonObject || branch.isJsonArray ->
                applyItemModifier(item, branch, predicateContext, location, activeReferences)
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Item modifier branch '$branchName' must be an id, object, or array",
                location,
            )
        }

    private fun applyReferencedItemModifier(
        item: ItemStack,
        id: ResourceLocation,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
        activeReferences: MutableSet<ResourceLocation>,
    ): ItemStack {
        if (!activeReferences.add(id)) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Recursive item modifier reference: $id", location)
        }
        try {
            return applyItemModifier(item, datapack.itemModifier(id).root, predicateContext, location, activeReferences)
        } finally {
            activeReferences.remove(id)
        }
    }

    private fun itemModifierPredicateContext(
        pos: BlockPos,
        stack: ItemStack,
    ): PredicateContext =
        PredicateContext(
            world = world,
            origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
            dimension = ResourceLocation("minecraft", "overworld"),
            tool = stack,
            block = world.block(pos)?.id,
            blockState = world.block(pos),
            weather = currentWeatherState(),
        )

    private fun itemModifierPredicateContext(
        player: SandboxPlayer,
        stack: ItemStack,
    ): PredicateContext =
        PredicateContext(
            world = world,
            player = player,
            thisEntity = player,
            origin = player.position,
            dimension = player.dimension,
            tool = stack,
            weather = currentWeatherState(),
        )

    private fun itemModifierPredicateContext(
        entity: SandboxEntity,
        stack: ItemStack,
    ): PredicateContext =
        if (entity is SandboxPlayer) {
            itemModifierPredicateContext(entity, stack)
        } else {
            PredicateContext(
                world = world,
                thisEntity = entity,
                origin = entity.position,
                dimension = entity.dimension,
                tool = stack,
                weather = currentWeatherState(),
            )
        }

    private fun itemModifierType(
        function: JsonObject,
        location: SourceLocation?,
    ): String {
        val raw =
            function.get("function")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier function is missing", location)
        return ResourceLocation.parse(raw).path
    }

    private fun itemModifierResource(
        function: JsonObject,
        key: String,
        type: String,
        location: SourceLocation?,
    ): ResourceLocation {
        val raw =
            function.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier '$type' requires string '$key'", location)
        return ResourceLocation.parse(raw)
    }

    private fun itemModifierObject(
        function: JsonObject,
        key: String,
        type: String,
        location: SourceLocation?,
    ): JsonObject =
        function.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier '$type' requires object '$key'", location)

    private fun itemModifierCount(
        element: JsonElement?,
        fallback: Int,
    ): Int =
        when {
            element == null || element.isJsonNull -> fallback
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
            element.isJsonObject -> element.asJsonObject.get("min")?.asInt ?: element.asJsonObject.get("base")?.asInt ?: fallback
            else -> fallback
        }

    private fun itemModifierNumber(
        element: JsonElement?,
        fallback: Double,
    ): Double =
        when {
            element == null || element.isJsonNull -> fallback
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asDouble
            element.isJsonObject -> element.asJsonObject.get("min")?.asDouble ?: element.asJsonObject.get("base")?.asDouble ?: fallback
            else -> fallback
        }

    private fun itemModifierLimit(
        value: Int,
        element: JsonElement?,
    ): Int {
        if (element == null || !element.isJsonObject) return value
        val root = element.asJsonObject
        val min = root.get("min")?.asInt ?: Int.MIN_VALUE
        val max = root.get("max")?.asInt ?: Int.MAX_VALUE
        return value.coerceIn(min, max)
    }

    private fun itemModifierText(
        function: JsonObject,
        key: String,
        type: String,
        location: SourceLocation?,
    ): JsonElement =
        function.get(key)?.deepCopy()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier '$type' requires '$key'", location)

    private fun itemModifierLore(
        function: JsonObject,
        location: SourceLocation?,
    ): JsonArray {
        val lore =
            function.get("lore")
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier 'set_lore' requires 'lore'", location)
        if (!lore.isJsonArray) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier 'set_lore' lore must be an array", location)
        }
        return lore.deepCopy().asJsonArray
    }

    private fun copyItemModifierNbt(
        stack: ItemStack,
        function: JsonObject,
        context: PredicateContext,
        location: SourceLocation?,
    ) {
        val source = function.get("source")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: "tool"
        val sourceNbt = itemModifierNbtSource(source, stack, context, location)
        val ops =
            function.getAsJsonArray("ops")
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier 'copy_nbt' requires 'ops'", location)
        ops.forEachIndexed { index, element ->
            if (!element.isJsonObject) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Item modifier copy_nbt ops entries must be objects at index $index",
                    location,
                )
            }
            val op = element.asJsonObject
            val value = JsonPaths.get(sourceNbt, op.requiredItemModifierString("source", "copy_nbt", location)) ?: return@forEachIndexed
            JsonPaths.set(stack.nbt, op.requiredItemModifierString("target", "copy_nbt", location), value)
        }
    }

    private fun itemModifierNbtSource(
        source: String,
        stack: ItemStack,
        context: PredicateContext,
        location: SourceLocation?,
    ): JsonObject {
        val nbt =
            when (source) {
                "tool" -> context.tool?.nbt ?: stack.nbt
                "this" -> context.thisEntity?.nbt ?: context.player?.nbt ?: stack.nbt
                "attacker" -> context.attacker?.nbt
                "direct_attacker" -> context.directEntity?.nbt
                "killer" -> context.killer?.nbt
                else -> null
            }
        return nbt ?: throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "copy_nbt requires source context '$source'", location)
    }

    private fun copyItemModifierComponents(
        stack: ItemStack,
        function: JsonObject,
        context: PredicateContext,
        location: SourceLocation?,
    ) {
        val source = function.get("source")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: "tool"
        val sourceComponents = itemModifierComponentSource(source, stack, context, location)
        val include = itemModifierComponentList(function.get("include"), "include", location)
        val exclude = itemModifierComponentList(function.get("exclude"), "exclude", location).orEmpty().toSet()
        val names =
            (include ?: sourceComponents.entrySet().map { it.key }.sorted())
                .distinct()
                .filterNot { it in exclude }
        names.forEach { name ->
            sourceComponents.get(name)?.let { stack.components.add(name, it.deepCopy()) }
        }
    }

    private fun itemModifierComponentSource(
        source: String,
        stack: ItemStack,
        context: PredicateContext,
        location: SourceLocation?,
    ): JsonObject {
        val item =
            when (source) {
                "tool" -> context.tool ?: stack
                "this" -> (context.thisEntity as? SandboxPlayer)?.selectedItem ?: context.player?.selectedItem ?: stack
                "attacker" -> (context.attacker as? SandboxPlayer)?.selectedItem
                "direct_attacker" -> (context.directEntity as? SandboxPlayer)?.selectedItem
                "killer" -> (context.killer as? SandboxPlayer)?.selectedItem
                else -> null
            }
        return item?.components
            ?: throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "copy_components requires item component source '$source'", location)
    }

    private fun itemModifierComponentList(
        element: JsonElement?,
        key: String,
        location: SourceLocation?,
    ): List<String>? {
        if (element == null || element.isJsonNull) return null
        return when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                listOf(ResourceLocation.parse(element.asString).toString())
            element.isJsonArray ->
                element.asJsonArray.mapIndexed { index, value ->
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "Item modifier copy_components '$key' entries must be strings at index $index",
                            location,
                        )
                    }
                    ResourceLocation.parse(value.asString).toString()
                }
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Item modifier copy_components '$key' must be a string or array of strings",
                location,
            )
        }
    }

    private fun JsonObject.requiredItemModifierString(
        name: String,
        type: String,
        location: SourceLocation?,
    ): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier '$type' requires string '$name'", location)
}

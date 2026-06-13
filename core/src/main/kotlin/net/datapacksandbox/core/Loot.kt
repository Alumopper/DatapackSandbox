package net.datapacksandbox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.floor
import kotlin.random.Random

data class LootContext(
    val type: ResourceLocation = ResourceLocation("minecraft", "empty"),
    val predicateContext: PredicateContext,
    val seed: Long = 0,
    val tool: ItemStack? = predicateContext.tool,
    val luck: Double = 0.0,
)

data class LootResult(val items: List<ItemStack>)

class LootEngine(
    private val datapack: Datapack,
    private val registry: RegistryView = RegistryView.vanilla2612,
) {
    private val predicates = PredicateEngine(datapack)

    fun generate(id: ResourceLocation, context: LootContext): LootResult {
        val table = datapack.lootTable(id)
        return generateTable(table, context, mutableSetOf())
    }

    private fun generateTable(table: LootTable, context: LootContext, stack: MutableSet<ResourceLocation>): LootResult {
        if (!stack.add(table.id)) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Recursive loot table reference: ${table.id}")
        }
        try {
            table.root.string("type")?.let { expected ->
                val expectedId = ResourceLocation.parse(expected)
                if (expectedId != context.type) {
                    throw SandboxException(
                        DiagnosticCode.MISSING_CONTEXT,
                        "Loot table '${table.id}' expects context '$expectedId' but got '${context.type}'",
                        location = SourceLocation(file = table.file),
                    )
                }
            }

            val random = Random(context.seed xor table.id.toString().hashCode().toLong())
            val tableFunctions = table.root.arrayObjects("functions").map { LootFunction(canonical(it.requiredString("function")), it) }
            val results = mutableListOf<ItemStack>()
            table.root.getAsJsonArray("pools")?.forEach { poolElement ->
                if (!poolElement.isJsonObject) throw tableError(table, "Loot pool must be an object")
                val pool = poolElement.asJsonObject
                if (!testConditions(pool.get("conditions"), context)) return@forEach
                val rolls = rollCount(pool.get("rolls"), random) + (pool.get("bonus_rolls")?.let { rollCount(it, random) } ?: 0)
                repeat(rolls.coerceAtLeast(0)) {
                    val entry = chooseEntry(pool.arrayObjects("entries"), context, random, stack)
                    if (entry != null) {
                        val poolFunctions = pool.arrayObjects("functions").map { LootFunction(canonical(it.requiredString("function")), it) }
                        results += applyFunctions(entry, poolFunctions, context, random)
                    }
                }
            }
            return LootResult(applyFunctions(results, tableFunctions, context, random))
        } finally {
            stack.remove(table.id)
        }
    }

    private fun chooseEntry(entries: List<JsonObject>, context: LootContext, random: Random, stack: MutableSet<ResourceLocation>): List<ItemStack>? {
        val candidates = entries.filter { testConditions(it.get("conditions"), context) }
        if (candidates.isEmpty()) return null
        val weighted = candidates.map { it to (it.get("weight")?.asInt ?: 1).coerceAtLeast(0) }
        val total = weighted.sumOf { it.second }
        val chosen = if (total <= 0) candidates.first() else {
            var cursor = random.nextInt(total)
            weighted.first { (_, weight) ->
                cursor -= weight
                cursor < 0
            }.first
        }
        return expandEntry(chosen, context, random, stack)
    }

    private fun expandEntry(entry: JsonObject, context: LootContext, random: Random, stack: MutableSet<ResourceLocation>): List<ItemStack> {
        val type = canonical(entry.requiredString("type"))
        val functions = entry.arrayObjects("functions").map { LootFunction(canonical(it.requiredString("function")), it) }
        val base = when (type) {
            "empty" -> emptyList()
            "item" -> listOf(ItemStack(ResourceLocation.parse(entry.requiredString("name"))))
            "loot_table" -> generateTable(datapack.lootTable(ResourceLocation.parse(entry.requiredString("value"))), context, stack).items
            "group" -> entry.arrayObjects("children").flatMap { expandEntry(it, context, random, stack) }
            "alternatives" -> {
                val child = entry.arrayObjects("children").firstOrNull { testConditions(it.get("conditions"), context) }
                child?.let { expandEntry(it, context, random, stack) } ?: emptyList()
            }
            "sequence" -> {
                val output = mutableListOf<ItemStack>()
                for (child in entry.arrayObjects("children")) {
                    if (!testConditions(child.get("conditions"), context)) break
                    output += expandEntry(child, context, random, stack)
                }
                output
            }
            "dynamic" -> emptyList()
            "tag" -> throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "Loot tag entries require item tag registry expansion: ${entry.string("name")}")
            else -> throw SandboxException(DiagnosticCode.UNSUPPORTED_FEATURE, "Loot entry type '${entry.requiredString("type")}' is not implemented")
        }
        return applyFunctions(base, functions, context, random)
    }

    private fun testConditions(conditions: JsonElement?, context: LootContext): Boolean =
        predicates.testConditions(conditions, context.predicateContext)

    private fun applyFunctions(items: List<ItemStack>, functions: List<LootFunction>, context: LootContext, random: Random): List<ItemStack> =
        items.flatMap { item -> applyFunctions(item, functions, context, random) }

    private fun applyFunctions(item: ItemStack, functions: List<LootFunction>, context: LootContext, random: Random): List<ItemStack> {
        var stack = item.copy(components = item.components.deepCopy(), nbt = item.nbt.deepCopy())
        functions.forEach { function ->
            if (!testConditions(function.root.get("conditions"), context)) return@forEach
            when (function.type) {
                "set_count" -> stack.count = rollCount(function.root.get("count"), random).coerceAtLeast(0)
                "limit_count" -> stack.count = limit(stack.count, function.root.get("limit"))
                "set_components" -> function.root.getAsJsonObject("components")?.entrySet()?.forEach { (key, value) ->
                    stack.components.add(key, value.deepCopy())
                }
                "set_custom_data", "set_nbt" -> function.root.string("tag")?.let {
                    val parsed = JsonValues.parse(it)
                    if (parsed.isJsonObject) JsonPaths.merge(stack.nbt, null, parsed)
                }
                "set_damage" -> stack.components.addProperty("minecraft:damage", rollDouble(function.root.get("damage"), random))
                "copy_nbt", "copy_components" -> copyFromContext(stack, function, context)
                "explosion_decay" -> stack.count = if (context.predicateContext.random.nextBoolean()) stack.count else 0
                "apply_bonus" -> Unit
                "enchant_randomly" -> stack.components.addProperty("minecraft:enchantments", "random")
                "enchant_with_levels" -> stack.components.addProperty("minecraft:enchantment_level", rollCount(function.root.get("levels"), random))
                "filtered" -> {
                    val itemFilter = function.root.getAsJsonObject("item_filter") ?: function.root.getAsJsonObject("filter")
                    if (itemFilter != null && !predicates.testItemPredicate(stack, itemFilter)) return emptyList()
                    val inner = function.root.getAsJsonObject("function")
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "filtered loot function requires nested function")
                    stack = applyFunctions(stack, listOf(LootFunction(canonical(inner.requiredString("function")), inner)), context, random).firstOrNull()
                        ?: return emptyList()
                }
                "sequence" -> {
                    val nested = function.root.arrayObjects("functions").map { LootFunction(canonical(it.requiredString("function")), it) }
                    stack = applyFunctions(stack, nested, context, random).firstOrNull() ?: return emptyList()
                }
                else -> throw SandboxException(DiagnosticCode.UNSUPPORTED_FEATURE, "Loot function '${function.type}' is not implemented")
            }
        }
        return if (stack.count <= 0) emptyList() else listOf(stack)
    }

    private fun copyFromContext(stack: ItemStack, function: LootFunction, context: LootContext) {
        val source = function.root.string("source") ?: "this"
        val entity = when (source) {
            "this" -> context.predicateContext.thisEntity
            "attacker" -> context.predicateContext.attacker
            "killer" -> context.predicateContext.killer
            "tool" -> null
            else -> null
        }
        if (function.type == "copy_nbt") {
            val sourceNbt = when (source) {
                "tool" -> context.tool?.nbt
                else -> entity?.nbt
            } ?: throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "copy_nbt requires source context '$source'")
            function.root.getAsJsonArray("ops")?.forEach { op ->
                val objectOp = op.asJsonObject
                val value = JsonPaths.get(sourceNbt, objectOp.requiredString("source")) ?: return@forEach
                JsonPaths.set(stack.nbt, objectOp.requiredString("target"), value)
            }
        }
    }

    private fun rollCount(element: JsonElement?, random: Random): Int =
        floor(rollDouble(element, random)).toInt()

    private fun rollDouble(element: JsonElement?, random: Random): Double =
        when {
            element == null || element.isJsonNull -> 1.0
            element.isJsonPrimitive -> element.asDouble
            element.isJsonObject -> {
                val objectElement = element.asJsonObject
                val min = objectElement.get("min")?.asDouble ?: objectElement.get("base")?.asDouble ?: 0.0
                val max = objectElement.get("max")?.asDouble ?: min
                if (max <= min) min else min + random.nextDouble() * (max - min)
            }
            else -> 1.0
        }

    private fun limit(value: Int, limit: JsonElement?): Int {
        if (limit == null || !limit.isJsonObject) return value
        val root = limit.asJsonObject
        val min = root.get("min")?.asInt ?: Int.MIN_VALUE
        val max = root.get("max")?.asInt ?: Int.MAX_VALUE
        return value.coerceIn(min, max)
    }

    private fun canonical(raw: String): String = ResourceLocation.parse(raw).path

    private fun tableError(table: LootTable, message: String): SandboxException =
        SandboxException(DiagnosticCode.INPUT_FORMAT, message, SourceLocation(file = table.file))

    private fun JsonObject.arrayObjects(name: String): List<JsonObject> =
        getAsJsonArray(name)?.map {
            if (!it.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Array '$name' must contain objects")
            it.asJsonObject
        } ?: emptyList()

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.requiredString(name: String): String =
        string(name) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Missing required string '$name'")
}

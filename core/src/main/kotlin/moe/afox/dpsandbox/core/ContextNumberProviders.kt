package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.truncate

/** Minecraft 26.3 context providers. Float intermediates are rounded to IEEE 754 binary32. */
internal class ContextNumberProviders(
    private val datapack: Datapack,
    private val profile: VersionProfile,
) {
    fun integer(element: JsonElement?, context: PredicateContext): Int = evaluate(element, context, true).toInt()

    fun floating(element: JsonElement?, context: PredicateContext): Float = evaluate(element, context, false).toFloat()

    private fun evaluate(element: JsonElement?, context: PredicateContext, integral: Boolean, depth: Int = 0): Double {
        if (profile.id != "26.3") fail("Context number providers require Minecraft 26.3")
        if (depth > 64) fail("Number provider nesting exceeds 64")

        fun eval(value: JsonElement?, integer: Boolean = integral) = evaluate(value, context, integer, depth + 1)
        val result: Double =
            when {
                element == null || element.isJsonNull -> 0.0
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asDouble
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                    val kind = if (integral) "context_int_provider" else "context_float_provider"
                    val id = ResourceLocation.parse(element.asString)
                    eval(
                        datapack.rawResources[kind]?.get(id)?.root
                            ?: throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Missing $kind '$id'"),
                    )
                }
                element.isJsonObject -> {
                    val root = element.asJsonObject

                    fun value(key: String, integer: Boolean = integral) = eval(root.get(key), integer)
                    val id = ResourceLocation.parse(root.get("type")?.asString ?: fail("Number provider requires type"))
                    if (id.namespace != "minecraft") throw SandboxException(DiagnosticCode.UNSUPPORTED_FEATURE, "Number provider '$id' is not implemented")
                    val type = id.path
                    val common = setOf("abs", "add", "avg", "conditional", "constant", "div", "environment_attribute", "max", "min", "mod", "mul", "negate", "number_dispatcher", "pow", "storage", "sub", "uniform", "weighted_list")
                    val specific =
                        if (integral) {
                            setOf("binomial", "floor_div", "floor_mod", "from_float", "score")
                        } else {
                            setOf("ceil", "cos", "enchantment_level", "floor", "from_int", "length", "round", "sin", "sqrt", "truncate")
                        }
                    if (type !in common + specific) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Number provider '$id' is not valid for ${if (integral) "integers" else "floats"}")
                    when (type) {
                        "constant" -> root.get("value")?.asDouble ?: fail("Constant requires value")
                        "storage" -> {
                            val storage = context.world.storages[ResourceLocation.parse(root.get("storage").asString)]
                            val found =
                                storage
                                    ?.let { JsonPaths.getAll(it, root.get("path").asString) }
                                    ?.singleOrNull()
                                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                            if (found == null) {
                                value("fallback")
                            } else if (integral) {
                                found.asInt.toDouble()
                            } else {
                                found.asFloat.toDouble()
                            }
                        }
                        "score" -> {
                            val target = root.get("target") ?: fail("Score provider requires target")
                            val targetType =
                                if (target.isJsonPrimitive) {
                                    "context"
                                } else {
                                    target.asJsonObject
                                        .get("type")
                                        ?.asString
                                        ?.removePrefix("minecraft:")
                                }
                            val holder =
                                when (targetType) {
                                    "fixed" -> target.asJsonObject.get("name").asString
                                    "context" -> {
                                        val name = if (target.isJsonPrimitive) target.asString else target.asJsonObject.get("target")?.asString ?: "this"
                                        val entity =
                                            when (name) {
                                                "this" -> context.thisEntity ?: context.player
                                                "target_entity" -> context.targetEntity
                                                "attacker" -> context.attacker
                                                "direct_attacker" -> context.directEntity
                                                "attacking_player" -> context.attackingPlayer
                                                "interacting_entity" -> context.interactingEntity
                                                else -> null
                                            }
                                        entity?.scoreHolder ?: throw MissingPredicateContext("Score provider requires '$name' context")
                                    }
                                    else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid score provider target")
                                }
                            context.world.scores[ScoreKey(holder, root.get("score").asString)]?.toDouble() ?: value("fallback")
                        }
                        "negate" -> -value("input")
                        "abs" -> abs(value("input"))
                        "sub" -> value("left") - value("right")
                        "add", "mul", "avg", "min", "max", "length" -> {
                            val values = root.getAsJsonArray("inputs")?.map { eval(it) }.orEmpty()
                            if (values.isEmpty() && type !in setOf("add", "mul", "length")) fail("$type requires inputs")
                            if (integral && type in setOf("add", "mul", "avg")) {
                                val total =
                                    values.fold(if (type == "mul") BigInteger.ONE else BigInteger.ZERO) { acc, n ->
                                        if (type == "mul") acc * BigInteger.valueOf(n.toLong()) else acc + BigInteger.valueOf(n.toLong())
                                    }
                                val number = if (type == "avg") total / BigInteger.valueOf(values.size.toLong()) else total
                                try {
                                    number.intValueExact().toDouble()
                                } catch (_: ArithmeticException) {
                                    fail("Integer calculation overflow")
                                }
                            } else {
                                when (type) {
                                    "add" -> values.fold(0f) { acc, n -> acc + n.toFloat() }.toDouble()
                                    "mul" -> values.fold(1f) { acc, n -> acc * n.toFloat() }.toDouble()
                                    "avg" -> (values.fold(0f) { acc, n -> acc + n.toFloat() } / values.size).toDouble()
                                    "min" -> values.min()
                                    "max" -> values.max()
                                    else -> sqrt(values.fold(0f) { acc, n -> acc + n.toFloat() * n.toFloat() }).toDouble()
                                }
                            }
                        }
                        "div", "mod", "floor_div", "floor_mod" -> {
                            val a = value("left")
                            val b = value("right")
                            if (b == 0.0) fail("Number provider division by zero")
                            if (integral) {
                                when (type) {
                                    "div" -> (a.toInt() / b.toInt()).toDouble()
                                    "mod" -> (a.toInt() % b.toInt()).toDouble()
                                    "floor_div" -> Math.floorDiv(a.toLong(), b.toLong()).toDouble()
                                    else -> Math.floorMod(a.toLong(), b.toLong()).toDouble()
                                }
                            } else if (type == "mod") {
                                (a.toFloat() % b.toFloat()).toDouble()
                            } else {
                                (a.toFloat() / b.toFloat()).toDouble()
                            }
                        }
                        "from_int" -> value("input", true)
                        "from_float" -> {
                            val number = value("input", false)
                            if (!number.isFinite() || number < Int.MIN_VALUE || number > Int.MAX_VALUE) fail("Integer conversion overflow")
                            number.toInt().toDouble()
                        }
                        "pow" -> {
                            val base = value("base")
                            val exponent = value("exponent")
                            if (integral && (exponent < 0 || base == 0.0 && exponent == 0.0)) fail("Invalid integer exponent")
                            base.pow(exponent)
                        }
                        "ceil" -> ceil(value("input"))
                        "floor" -> floor(value("input"))
                        "round" -> floor(value("input") + 0.5)
                        "truncate" -> truncate(value("input"))
                        "sin" -> sin(value("input"))
                        "cos" -> cos(value("input"))
                        "sqrt" -> sqrt(value("input"))
                        "conditional" -> value(if (PredicateEngine(datapack, profile).testElement(root.get("condition"), context)) "on_true" else "on_false")
                        "number_dispatcher" -> {
                            val chosen = root.getAsJsonArray("cases")?.firstOrNull { PredicateEngine(datapack, profile).testElement(it.asJsonObject.get("condition"), context) }
                            eval(chosen?.asJsonObject?.get("value") ?: root.get("default"))
                        }
                        "uniform" -> {
                            val min = value("min")
                            val max = value("max")
                            if (max < min) fail("Uniform provider maximum is below minimum")
                            if (integral) {
                                context.random.nextLong(min.toLong(), max.toLong() + 1).toDouble()
                            } else {
                                (min.toFloat() + context.random.nextFloat() * (max.toFloat() - min.toFloat())).toDouble()
                            }
                        }
                        "binomial" -> {
                            val n = value("n", true).toInt()
                            val p = value("p", false)
                            if (n !in 0..1_000_000 || p !in 0.0..1.0) fail("Invalid binomial parameters")
                            (0 until n).count { context.random.nextFloat() < p }.toDouble()
                        }
                        "weighted_list" -> {
                            val entries = root.getAsJsonArray("distribution")?.map { it.asJsonObject }.orEmpty()
                            val weights = entries.map { it.get("weight")?.asInt ?: 1 }
                            if (entries.isEmpty() || weights.any { it <= 0 }) fail("Weighted provider requires positive weights")
                            var cursor = context.random.nextLong(weights.sumOf { it.toLong() })
                            val selected =
                                weights.indices.first { index ->
                                    cursor -= weights[index]
                                    cursor < 0
                                }
                            eval(entries[selected].get("data"))
                        }
                        "enchantment_level" -> throw MissingPredicateContext("Enchantment level number provider requires enchantment level context")
                        "environment_attribute" -> throw SandboxException(DiagnosticCode.UNSUPPORTED_FEATURE, "Environment attribute number providers are not simulated")
                        else -> throw SandboxException(DiagnosticCode.UNSUPPORTED_FEATURE, "Number provider '$type' is not implemented")
                    }
                }
                else -> fail("Number provider must be a number, reference or object")
            }
        if (!result.isFinite()) fail("Number provider result is not finite")
        if (integral) {
            if (result < Int.MIN_VALUE || result > Int.MAX_VALUE || result != truncate(result)) fail("Integer calculation overflow or fractional value")
            return result
        }
        if (!result.toFloat().isFinite()) fail("Float calculation overflow")
        return result.toFloat().toDouble()
    }

    private fun fail(message: String): Nothing = throw SandboxException(DiagnosticCode.COMMAND_ERROR, message)
}

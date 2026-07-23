package moe.afox.dpsandbox.engine

import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

sealed interface EngineDataValue {
    fun json(): String

    fun deepCopy(): EngineDataValue
}

data class EngineDataObject(
    val values: MutableMap<String, EngineDataValue> = linkedMapOf(),
) : EngineDataValue {
    override fun json(): String = JsonText.obj(*values.entries.map { (key, value) -> key to value.json() }.toTypedArray())

    override fun deepCopy(): EngineDataObject = EngineDataObject(values.mapValuesTo(linkedMapOf()) { (_, value) -> value.deepCopy() })

    fun merge(source: EngineDataObject) {
        source.values.forEach { (key, value) ->
            val existing = values[key]
            if (existing is EngineDataObject && value is EngineDataObject) existing.merge(value) else values[key] = value.deepCopy()
        }
    }
}

data class EngineDataArray(
    val values: MutableList<EngineDataValue> = mutableListOf(),
) : EngineDataValue {
    override fun json(): String = JsonText.array(values.map(EngineDataValue::json))

    override fun deepCopy(): EngineDataArray = EngineDataArray(values.mapTo(mutableListOf()) { it.deepCopy() })
}

data class EngineDataString(
    val value: String,
) : EngineDataValue {
    override fun json(): String = JsonText.quote(value)

    override fun deepCopy(): EngineDataString = this
}

data class EngineDataNumber(
    val value: Double,
    val integral: Boolean,
) : EngineDataValue {
    override fun json(): String = if (integral) value.toLong().toString() else value.toString()

    override fun deepCopy(): EngineDataNumber = this
}

data class EngineDataBoolean(
    val value: Boolean,
) : EngineDataValue {
    override fun json(): String = value.toString()

    override fun deepCopy(): EngineDataBoolean = this
}

internal class EngineSnbtParser(
    private val input: String,
) {
    private var index = 0

    fun parseObject(): EngineDataObject {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        require(index == input.length) { "Unexpected trailing SNBT content at offset $index" }
        return value as? EngineDataObject ?: error("Display entity NBT must be an object")
    }

    fun parseValue(): EngineDataValue {
        skipWhitespace()
        require(index < input.length) { "Unexpected end of SNBT" }
        return when (input[index]) {
            '{' -> parseCompound()
            '[' -> parseArray()
            '"', '\'' -> EngineDataString(parseQuoted())
            else -> parseBare()
        }
    }

    private fun parseCompound(): EngineDataObject {
        expect('{')
        val result = EngineDataObject()
        skipWhitespace()
        if (peek('}')) {
            index += 1
            return result
        }
        while (true) {
            skipWhitespace()
            val key = if (peek('"') || peek('\'')) parseQuoted() else parseBareToken()
            expect(':')
            result.values[key] = parseValue()
            skipWhitespace()
            when {
                peek(',') -> index += 1
                peek('}') -> {
                    index += 1
                    return result
                }
                else -> error("Expected ',' or '}' at offset $index")
            }
        }
    }

    private fun parseArray(): EngineDataArray {
        expect('[')
        if (index + 1 < input.length && input[index + 1] == ';' && input[index].uppercaseChar() in setOf('B', 'I', 'L')) index += 2
        val result = EngineDataArray()
        skipWhitespace()
        if (peek(']')) {
            index += 1
            return result
        }
        while (true) {
            result.values += parseValue()
            skipWhitespace()
            when {
                peek(',') -> index += 1
                peek(']') -> {
                    index += 1
                    return result
                }
                else -> error("Expected ',' or ']' at offset $index")
            }
        }
    }

    private fun parseBare(): EngineDataValue {
        val token = parseBareToken()
        return when {
            token.equals("true", true) -> EngineDataBoolean(true)
            token.equals("false", true) -> EngineDataBoolean(false)
            token.matches(Regex("[-+]?\\d+[bBsSlL]?")) -> EngineDataNumber(token.trimNumberSuffix().toLong().toDouble(), true)
            token.matches(Regex("[-+]?(\\d+\\.\\d*|\\d*\\.\\d+|\\d+)([fFdD])?")) ->
                EngineDataNumber(token.trimNumberSuffix().toDouble(), false)
            else -> EngineDataString(token)
        }
    }

    private fun parseBareToken(): String {
        skipWhitespace()
        val start = index
        while (index < input.length && !input[index].isWhitespace() && input[index] !in charArrayOf(',', ':', '}', ']', '[')) index += 1
        require(start != index) { "Expected SNBT token at offset $index" }
        return input.substring(start, index)
    }

    private fun parseQuoted(): String {
        val quote = input[index++]
        val result = StringBuilder()
        var escaped = false
        while (index < input.length) {
            val char = input[index++]
            when {
                escaped -> {
                    result.append(
                        when (char) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> char
                        },
                    )
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == quote -> return result.toString()
                else -> result.append(char)
            }
        }
        error("Unterminated SNBT string")
    }

    private fun String.trimNumberSuffix(): String = if (lastOrNull()?.lowercaseChar() in setOf('b', 's', 'l', 'f', 'd')) dropLast(1) else this

    private fun expect(char: Char) {
        skipWhitespace()
        require(peek(char)) { "Expected '$char' at offset $index" }
        index += 1
    }

    private fun peek(char: Char): Boolean = index < input.length && input[index] == char

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index += 1
    }
}

data class EngineDisplayState(
    val transformation: List<Double> = IDENTITY_MATRIX,
    val transformationComponents: TransformationComponents? = null,
    val billboard: String = "fixed",
    val brightnessSky: Int? = null,
    val brightnessBlock: Int? = null,
    val viewRange: Double = 1.0,
    val shadowRadius: Double = 0.0,
    val shadowStrength: Double = 1.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
    val glowColor: Int = 0,
    val blockId: String? = null,
    val blockProperties: Map<String, String> = emptyMap(),
    val itemId: String? = null,
    val itemDisplay: String = "none",
    val text: String = "",
    val lineWidth: Int = 200,
    val background: Int = 0x40000000,
    val textOpacity: Int = 255,
    val textShadow: Boolean = false,
    val seeThrough: Boolean = false,
    val defaultBackground: Boolean = false,
    val alignment: String = "center",
    val textColor: Int = 0xffffffff.toInt(),
) {
    data class TransformationComponents(
        val translation: List<Double>,
        val leftRotation: List<Double>,
        val scale: List<Double>,
        val rightRotation: List<Double>,
    ) {
        fun interpolate(
            target: TransformationComponents,
            progress: Double,
        ): TransformationComponents =
            TransformationComponents(
                translation.zip(target.translation) { from, to -> lerp(from, to, progress) },
                leftRotation = slerp(leftRotation, target.leftRotation, progress),
                scale = scale.zip(target.scale) { from, to -> lerp(from, to, progress) },
                rightRotation = slerp(rightRotation, target.rightRotation, progress),
            )

        fun toMatrix(): List<Double> =
            multiply(
                multiply(multiply(translationMatrix(translation), quaternionMatrix(leftRotation)), scaleMatrix(scale)),
                quaternionMatrix(rightRotation),
            )
    }

    fun interpolate(
        target: EngineDisplayState,
        progress: Double,
    ): EngineDisplayState {
        val amount = progress.coerceIn(0.0, 1.0)
        val components = transformationComponents?.let { source -> target.transformationComponents?.let { source.interpolate(it, amount) } }
        return target.copy(
            transformation = components?.toMatrix() ?: transformation.zip(target.transformation) { from, to -> lerp(from, to, amount) },
            transformationComponents = components,
            shadowRadius = lerp(shadowRadius, target.shadowRadius, amount),
            shadowStrength = lerp(shadowStrength, target.shadowStrength, amount),
            background = interpolateArgb(background, target.background, amount),
            textOpacity = lerp(textOpacity.toDouble(), target.textOpacity.toDouble(), amount).roundToInt(),
        )
    }

    fun specialJson(
        x: Double,
        y: Double,
        z: Double,
        yaw: Double,
        pitch: Double,
        kind: String,
    ): String =
        JsonText.obj(
            "kind" to JsonText.quote(kind.removeSuffix("_display")),
            "renderTransformation" to JsonText.array(transformation.map(Double::toString)),
            "shadowRadius" to shadowRadius.toString(),
            "shadowStrength" to shadowStrength.toString(),
            "textOpacity" to textOpacity.toString(),
            "background" to background.toString(),
            "renderPosition" to
                JsonText.obj(
                    "x" to x.toString(),
                    "y" to y.toString(),
                    "z" to z.toString(),
                    "yaw" to yaw.toString(),
                    "pitch" to pitch.toString(),
                ),
            "content" to contentJson(kind),
        )

    private fun contentJson(kind: String): String =
        when (kind.removePrefix("minecraft:")) {
            "block_display" ->
                JsonText.obj(
                    "blockState" to
                        JsonText.obj(
                            "Name" to JsonText.quote(blockId ?: "minecraft:air"),
                            "Properties" to JsonText.obj(*blockProperties.entries.map { (key, value) -> key to JsonText.quote(value) }.toTypedArray()),
                        ),
                )
            "item_display" ->
                JsonText.obj(
                    "item" to JsonText.obj("id" to JsonText.quote(itemId ?: "minecraft:air"), "count" to "1"),
                    "itemDisplay" to JsonText.quote(itemDisplay),
                )
            else -> JsonText.obj("text" to JsonText.quote(text))
        }

    companion object {
        val IDENTITY_MATRIX =
            listOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            )

        fun from(
            type: String,
            data: EngineDataObject,
        ): EngineDisplayState? {
            val path = type.substringAfter(':')
            if (path !in setOf("block_display", "item_display", "text_display")) return null
            val brightness = data.obj("brightness")
            val blockState = data.obj("block_state")
            val item = data.obj("item")
            val textValue = data.values["text"]
            val parsedTransformation = transformation(data.values["transformation"])
            return EngineDisplayState(
                transformation = parsedTransformation.first,
                transformationComponents = parsedTransformation.second,
                billboard = data.string("billboard")?.takeIf { it in BILLBOARDS } ?: "fixed",
                brightnessSky = brightness?.int("sky")?.coerceIn(0, 15),
                brightnessBlock = brightness?.int("block")?.coerceIn(0, 15),
                viewRange = data.number("view_range")?.coerceAtLeast(0.0) ?: 1.0,
                shadowRadius = data.number("shadow_radius")?.coerceAtLeast(0.0) ?: 0.0,
                shadowStrength = data.number("shadow_strength")?.coerceAtLeast(0.0) ?: 1.0,
                width = data.number("width")?.coerceAtLeast(0.0) ?: 0.0,
                height = data.number("height")?.coerceAtLeast(0.0) ?: 0.0,
                glowColor = data.int("glow_color_override") ?: 0,
                blockId = blockState?.string("Name") ?: blockState?.string("name") ?: if (path == "block_display") "minecraft:air" else null,
                blockProperties =
                    (blockState?.obj("Properties") ?: blockState?.obj("properties"))
                        ?.values
                        ?.mapValues { (_, value) -> value.stringValue() }
                        .orEmpty(),
                itemId = item?.string("id") ?: item?.string("Id") ?: if (path == "item_display") "minecraft:air" else null,
                itemDisplay = data.string("item_display")?.takeIf { it in ITEM_DISPLAYS } ?: "none",
                text = plainText(textValue),
                lineWidth = data.int("line_width") ?: 200,
                background = data.int("background") ?: 0x40000000,
                textOpacity = data.int("text_opacity") ?: 255,
                textShadow = data.boolean("shadow") ?: false,
                seeThrough = data.boolean("see_through") ?: false,
                defaultBackground = data.boolean("default_background") ?: false,
                alignment = data.string("alignment")?.takeIf { it in setOf("left", "center", "right") } ?: "center",
                textColor = textColor(textValue),
            )
        }

        private fun transformation(value: EngineDataValue?): Pair<List<Double>, TransformationComponents?> {
            if (value is EngineDataArray && value.values.size == 16) {
                return value.values.map(EngineDataValue::numberValue) to null
            }
            val root = value as? EngineDataObject
            if (root == null) {
                val identity = TransformationComponents(
                    translation = listOf(0.0, 0.0, 0.0),
                    leftRotation = listOf(0.0, 0.0, 0.0, 1.0),
                    scale = listOf(1.0, 1.0, 1.0),
                    rightRotation = listOf(0.0, 0.0, 0.0, 1.0),
                )
                return identity.toMatrix() to identity
            }
            val translation = root.vector("translation", listOf(0.0, 0.0, 0.0))
            val scale = root.vector("scale", listOf(1.0, 1.0, 1.0))
            val left = root.vector("left_rotation", listOf(0.0, 0.0, 0.0, 1.0))
            val right = root.vector("right_rotation", listOf(0.0, 0.0, 0.0, 1.0))
            val components = TransformationComponents(translation, normalizedQuaternion(left), scale, normalizedQuaternion(right))
            return components.toMatrix() to components
        }

        private fun plainText(value: EngineDataValue?): String =
            when (value) {
                null -> ""
                is EngineDataString -> {
                    val trimmed = value.value.trim()
                    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
                        runCatching { plainText(EngineSnbtParser(trimmed).parseValue()) }.getOrDefault(value.value)
                    } else {
                        value.value
                    }
                }
                is EngineDataArray -> value.values.joinToString("") { plainText(it) }
                is EngineDataObject ->
                    buildString {
                        append(value.string("text").orEmpty())
                        if (isEmpty()) append(value.string("translate").orEmpty())
                        value.values["extra"]?.let { append(plainText(it)) }
                    }
                else -> value.stringValue()
            }

        private fun textColor(value: EngineDataValue?): Int {
            val resolved =
                if (value is EngineDataString) {
                    val trimmed = value.value.trim()
                    if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
                        runCatching { EngineSnbtParser(trimmed).parseValue() }.getOrNull()
                    } else {
                        value
                    }
                } else {
                    value
                }
            val color = (resolved as? EngineDataObject)?.string("color") ?: return 0xffffffff.toInt()
            return NAMED_COLORS[color.lowercase()] ?: color.removePrefix("#").toIntOrNull(16)?.let { 0xff000000.toInt() or it } ?: 0xffffffff.toInt()
        }

        private fun translationMatrix(value: List<Double>): List<Double> =
            listOf(
                1.0, 0.0, 0.0, value[0],
                0.0, 1.0, 0.0, value[1],
                0.0, 0.0, 1.0, value[2],
                0.0, 0.0, 0.0, 1.0,
            )

        private fun scaleMatrix(value: List<Double>): List<Double> =
            listOf(
                value[0], 0.0, 0.0, 0.0,
                0.0, value[1], 0.0, 0.0,
                0.0, 0.0, value[2], 0.0,
                0.0, 0.0, 0.0, 1.0,
            )

        private fun quaternionMatrix(raw: List<Double>): List<Double> {
            val q = normalizedQuaternion(raw)
            val (x, y, z, w) = q
            return listOf(
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y), 0.0,
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x), 0.0,
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y), 0.0,
                0.0, 0.0, 0.0, 1.0,
            )
        }

        private fun normalizedQuaternion(raw: List<Double>): List<Double> {
            val length = sqrt(raw.sumOf { it * it })
            return if (length <= 1e-12) listOf(0.0, 0.0, 0.0, 1.0) else raw.map { it / length }
        }

        private fun slerp(
            source: List<Double>,
            rawTarget: List<Double>,
            progress: Double,
        ): List<Double> {
            val from = normalizedQuaternion(source)
            var target = normalizedQuaternion(rawTarget)
            var dot = from.zip(target).sumOf { (left, right) -> left * right }
            if (dot < 0.0) {
                target = target.map { -it }
                dot = -dot
            }
            if (dot > 0.9995) {
                return normalizedQuaternion(from.zip(target) { left, right -> lerp(left, right, progress) })
            }
            val theta = acos(dot.coerceIn(-1.0, 1.0))
            val denominator = sin(theta)
            if (denominator == 0.0) return from
            val fromWeight = sin((1.0 - progress) * theta) / denominator
            val targetWeight = sin(progress * theta) / denominator
            return normalizedQuaternion(from.zip(target) { left, right -> left * fromWeight + right * targetWeight })
        }

        private fun multiply(
            left: List<Double>,
            right: List<Double>,
        ): List<Double> =
            List(16) { index ->
                val row = index / 4
                val column = index % 4
                (0..3).sumOf { offset -> left[row * 4 + offset] * right[offset * 4 + column] }
            }

        private val BILLBOARDS = setOf("fixed", "vertical", "horizontal", "center")
        private val ITEM_DISPLAYS =
            setOf(
                "none", "thirdperson_lefthand", "thirdperson_righthand", "firstperson_lefthand",
                "firstperson_righthand", "head", "gui", "ground", "fixed",
            )
        private val NAMED_COLORS =
            mapOf(
                "black" to 0xff000000.toInt(), "dark_blue" to 0xff0000aa.toInt(), "dark_green" to 0xff00aa00.toInt(),
                "dark_aqua" to 0xff00aaaa.toInt(), "dark_red" to 0xffaa0000.toInt(), "dark_purple" to 0xffaa00aa.toInt(),
                "gold" to 0xffffaa00.toInt(), "gray" to 0xffaaaaaa.toInt(), "dark_gray" to 0xff555555.toInt(),
                "blue" to 0xff5555ff.toInt(), "green" to 0xff55ff55.toInt(), "aqua" to 0xff55ffff.toInt(),
                "red" to 0xffff5555.toInt(), "light_purple" to 0xffff55ff.toInt(), "yellow" to 0xffffff55.toInt(),
                "white" to 0xffffffff.toInt(),
            )

        private fun lerp(
            from: Double,
            to: Double,
            progress: Double,
        ): Double = from + (to - from) * progress

        private fun interpolateArgb(
            from: Int,
            to: Int,
            progress: Double,
        ): Int {
            fun channel(shift: Int): Int =
                lerp((from ushr shift and 0xff).toDouble(), (to ushr shift and 0xff).toDouble(), progress)
                    .roundToInt()
                    .coerceIn(0, 255)
            return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

private fun EngineDataObject.string(name: String): String? = values[name]?.let(EngineDataValue::stringValue)

private fun EngineDataObject.number(name: String): Double? = (values[name] as? EngineDataNumber)?.value

private fun EngineDataObject.int(name: String): Int? = number(name)?.toInt()

private fun EngineDataObject.boolean(name: String): Boolean? =
    when (val value = values[name]) {
        is EngineDataBoolean -> value.value
        is EngineDataNumber -> value.value != 0.0
        is EngineDataString -> value.value.toBooleanStrictOrNull()
        else -> null
    }

private fun EngineDataObject.obj(name: String): EngineDataObject? = values[name] as? EngineDataObject

private fun EngineDataObject.vector(
    name: String,
    fallback: List<Double>,
): List<Double> {
    val values = (this.values[name] as? EngineDataArray)?.values ?: return fallback
    return if (values.size == fallback.size) values.map(EngineDataValue::numberValue) else fallback
}

internal fun EngineDataValue.stringValue(): String =
    when (this) {
        is EngineDataString -> value
        is EngineDataNumber -> if (integral) value.toLong().toString() else value.toString()
        is EngineDataBoolean -> value.toString()
        else -> json()
    }

internal fun EngineDataValue.numberValue(): Double =
    when (this) {
        is EngineDataNumber -> value
        is EngineDataString -> value.toDoubleOrNull() ?: 0.0
        is EngineDataBoolean -> if (value) 1.0 else 0.0
        else -> 0.0
    }

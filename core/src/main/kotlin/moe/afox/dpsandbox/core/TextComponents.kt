package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class ResolvedTextComponent(
    val plain: String,
    val segments: List<OutputTextSegment>,
)

private data class TextStyle(
    val color: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val strikethrough: Boolean = false,
    val obfuscated: Boolean = false,
) {
    fun apply(root: JsonObject): TextStyle {
        val reset = root.string("color") == "reset"
        val base = if (reset) TextStyle() else this
        return base.copy(
            color = root.string("color")?.takeUnless { it == "reset" } ?: base.color,
            bold = root.boolean("bold") ?: base.bold,
            italic = root.boolean("italic") ?: base.italic,
            underlined = root.boolean("underlined") ?: base.underlined,
            strikethrough = root.boolean("strikethrough") ?: base.strikethrough,
            obfuscated = root.boolean("obfuscated") ?: base.obfuscated,
        )
    }

    fun segment(text: String): OutputTextSegment =
        OutputTextSegment(
            text = text,
            color = color,
            bold = bold,
            italic = italic,
            underlined = underlined,
            strikethrough = strikethrough,
            obfuscated = obfuscated,
        )
}

object TextComponents {
    fun parse(raw: String, location: SourceLocation? = null): JsonElement =
        try {
            JsonParser.parseString(raw)
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid text component JSON", location, cause = error)
        }

    fun resolve(
        component: JsonElement,
        world: SandboxWorld,
        context: ExecutionContext = ExecutionContext(),
        viewer: SandboxPlayer? = null,
        location: SourceLocation? = null,
    ): ResolvedTextComponent =
        Resolver(world, context, viewer, location).resolve(component)

    private class Resolver(
        private val world: SandboxWorld,
        private val context: ExecutionContext,
        private val viewer: SandboxPlayer?,
        private val location: SourceLocation?,
    ) {
        fun resolve(component: JsonElement): ResolvedTextComponent =
            resolveElement(component, TextStyle()).merged()

        private fun resolveElement(component: JsonElement, inherited: TextStyle): List<OutputTextSegment> =
            when {
                component.isJsonNull -> emptyList()
                component.isJsonPrimitive -> listOf(inherited.segment(component.asJsonPrimitive.asString))
                component.isJsonArray -> component.asJsonArray.flatMap { resolveElement(it, inherited) }
                component.isJsonObject -> resolveObject(component.asJsonObject, inherited)
                else -> listOf(inherited.segment(JsonValues.render(component)))
            }

        private fun resolveObject(root: JsonObject, inherited: TextStyle): List<OutputTextSegment> {
            val style = inherited.apply(root)
            val segments = mutableListOf<OutputTextSegment>()
            val content = when {
                root.has("text") -> listOf(style.segment(root.primitiveText("text")))
                root.has("translate") -> resolveTranslate(root, style)
                root.has("score") -> listOf(style.segment(resolveScore(root.getAsJsonObject("score"))))
                root.has("selector") -> resolveSelector(root, style)
                root.has("nbt") -> resolveNbt(root, style)
                root.has("keybind") -> listOf(style.segment(root.primitiveText("keybind")))
                else -> emptyList()
            }
            segments += content
            root.getAsJsonArray("extra")?.forEach { segments += resolveElement(it, style) }
            return segments
        }

        private fun resolveTranslate(root: JsonObject, style: TextStyle): List<OutputTextSegment> {
            val key = root.primitiveText("translate")
            val args = root.getAsJsonArray("with")
                ?.map { resolveElement(it, style).merged().plain }
                ?: emptyList()
            val text = applyTranslateArguments(key, args)
            return listOf(style.segment(text))
        }

        private fun resolveScore(score: JsonObject): String {
            score.string("value")?.let { return it }
            val objective = score.requiredString("objective")
            val names = resolveScoreNames(score.requiredString("name"))
            return names.joinToString(separator = ", ") { name ->
                world.getScore(name, objective).toString()
            }
        }

        private fun resolveScoreNames(rawName: String): List<String> =
            when {
                rawName == "*" -> listOfNotNull(viewer?.name, (context.entity as? SandboxPlayer)?.name).ifEmpty {
                    throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "Score text component name '*' requires a viewer or player execution context", location)
                }
                EntitySelectors.isSelector(rawName) -> EntitySelectors.select(world, rawName, context.copy(entity = viewer ?: context.entity), location)
                    .map { if (it is SandboxPlayer) it.name else it.scoreHolder }
                else -> listOf(rawName)
            }

        private fun resolveSelector(root: JsonObject, style: TextStyle): List<OutputTextSegment> {
            val selected = EntitySelectors.select(world, root.requiredString("selector"), context.copy(entity = viewer ?: context.entity), location)
                .map { if (it is SandboxPlayer) it.name else it.uuid }
            val separator = root.get("separator")?.let { resolveElement(it, style).ifEmpty { listOf(style.segment(", ")) } }
                ?: listOf(style.segment(", "))
            return selected.flatMapIndexed { index, name ->
                if (index == 0) listOf(style.segment(name)) else separator + style.segment(name)
            }
        }

        private fun resolveNbt(root: JsonObject, style: TextStyle): List<OutputTextSegment> {
            val path = root.requiredString("nbt")
            val values = when {
                root.has("storage") -> {
                    val storage = world.storages[ResourceLocation.parse(root.requiredString("storage"))]
                    listOfNotNull(storage?.let { JsonPaths.get(it, path) })
                }
                root.has("entity") -> EntitySelectors.select(world, root.requiredString("entity"), context.copy(entity = viewer ?: context.entity), location)
                    .mapNotNull { JsonPaths.get(it.nbt, path) }
                root.has("block") -> throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "Block NBT text components require a block state model", location)
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT text component requires storage, entity, or block", location)
            }
            val separator = root.get("separator")?.let { resolveElement(it, style).merged().plain } ?: ", "
            val plain = values.joinToString(separator = separator) { value ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else JsonValues.render(value)
            }
            if (root.boolean("interpret") == true && plain.isNotBlank()) {
                return resolveElement(parse(plain, location), style)
            }
            return listOf(style.segment(plain))
        }

        private fun applyTranslateArguments(pattern: String, args: List<String>): String {
            var automatic = 0
            return Regex("%(?:(\\d+)\\$)?s").replace(pattern) { match ->
                val explicit = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.toIntOrNull()?.minus(1)
                val index = explicit ?: automatic++
                args.getOrNull(index) ?: match.value
            }.let { text ->
                if (text == pattern && args.isNotEmpty()) "$pattern ${args.joinToString(" ")}" else text
            }
        }
    }
}

private fun List<OutputTextSegment>.merged(): ResolvedTextComponent {
    val compact = mutableListOf<OutputTextSegment>()
    forEach { segment ->
        val previous = compact.lastOrNull()
        if (previous != null &&
            previous.color == segment.color &&
            previous.bold == segment.bold &&
            previous.italic == segment.italic &&
            previous.underlined == segment.underlined &&
            previous.strikethrough == segment.strikethrough &&
            previous.obfuscated == segment.obfuscated
        ) {
            compact[compact.lastIndex] = previous.copy(text = previous.text + segment.text)
        } else {
            compact += segment
        }
    }
    return ResolvedTextComponent(compact.joinToString(separator = "") { it.text }, compact)
}

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.primitiveText(name: String): String =
    string(name) ?: get(name)?.let(JsonValues::render).orEmpty()

private fun JsonObject.requiredString(name: String): String =
    string(name) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Text component requires string '$name'")

private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean

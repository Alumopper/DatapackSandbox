package moe.afox.dpsandbox.cli

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import moe.afox.dpsandbox.core.JsonValues
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

object ManifestSchemaValidator {
    private val schema: JsonObject by lazy {
        JsonParser.parseString(ManifestSchema.readText()).asJsonObject
    }

    fun validateFile(path: Path): List<String> {
        val root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
        return validate(root).map { "${path.toAbsolutePath().normalize()}: $it" }
    }

    fun validateFileTree(path: Path): List<String> = validateFileTree(path.toAbsolutePath().normalize(), linkedSetOf(), mutableSetOf())

    fun validate(root: JsonElement): List<String> = validateElement(schema, root, "$")

    private fun validateFileTree(
        path: Path,
        stack: MutableSet<Path>,
        visited: MutableSet<Path>,
    ): List<String> {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized in stack) return listOf("$normalized: manifest include cycle detected")
        if (!visited.add(normalized)) return emptyList()

        val root =
            try {
                JsonParser.parseString(Files.readString(normalized, StandardCharsets.UTF_8))
            } catch (_: NoSuchFileException) {
                return listOf("$normalized: included manifest is missing")
            } catch (error: Exception) {
                return listOf("$normalized: invalid JSON (${error.message ?: error::class.simpleName})")
            }

        val failures = validate(root).mapTo(mutableListOf()) { "$normalized: $it" }
        if (!root.isJsonObject) return failures

        stack.add(normalized)
        try {
            val base = normalized.parent ?: Path.of(".")
            includePaths(root.asJsonObject, base).forEach { include ->
                failures += validateFileTree(include, stack, visited)
            }
        } finally {
            stack.remove(normalized)
        }
        return failures
    }

    private fun validateElement(
        schemaElement: JsonElement,
        value: JsonElement,
        path: String,
    ): List<String> {
        if (schemaElement.isJsonPrimitive && schemaElement.asJsonPrimitive.isBoolean) {
            return if (schemaElement.asBoolean) emptyList() else listOf("$path is not allowed")
        }
        if (!schemaElement.isJsonObject) return emptyList()
        val schemaObject = schemaElement.asJsonObject

        schemaObject.getString("\$ref")?.let { ref ->
            return validateElement(resolveRef(ref), value, path)
        }

        val failures = mutableListOf<String>()
        schemaObject.getString("type")?.let { type ->
            if (!value.matchesType(type)) failures += "$path expected $type but was ${value.typeName()}"
        }
        schemaObject.getAsJsonArray("enum")?.let { allowed ->
            if (allowed.none { it == value }) {
                failures += "$path expected one of ${allowed.joinToString { JsonValues.render(it) }} but was ${JsonValues.render(value)}"
            }
        }

        schemaObject.getAsJsonArray("oneOf")?.let { variants ->
            val matches = variants.count { validateElement(it, value, path).isEmpty() }
            if (matches != 1) failures += "$path must match exactly one schema variant but matched $matches"
        }
        schemaObject.getAsJsonArray("anyOf")?.let { variants ->
            if (variants.none { validateElement(it, value, path).isEmpty() }) {
                failures += "$path must match at least one schema variant"
            }
        }
        schemaObject.get("not")?.let { forbidden ->
            if (validateElement(forbidden, value, path).isEmpty()) failures += "$path matched a forbidden schema variant"
        }

        if (value.isJsonObject) {
            failures += validateObject(schemaObject, value.asJsonObject, path)
        }
        if (value.isJsonArray) {
            failures += validateArray(schemaObject, value.asJsonArray, path)
        }
        if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            schemaObject.get("minimum")?.let { minimum ->
                if (value.asBigDecimal < minimum.asBigDecimal) failures += "$path must be >= ${minimum.asBigDecimal}"
            }
        }
        return failures
    }

    private fun validateObject(
        schemaObject: JsonObject,
        value: JsonObject,
        path: String,
    ): List<String> {
        val failures = mutableListOf<String>()
        schemaObject.getAsJsonArray("required")?.forEach { required ->
            val name = required.asString
            if (!value.has(name)) failures += "$path missing required property '$name'"
        }

        val properties = schemaObject.getAsJsonObject("properties") ?: JsonObject()
        value.entrySet().forEach { (name, child) ->
            val propertyPath = "$path/${escapePointer(name)}"
            val propertySchema = properties.get(name)
            when {
                propertySchema != null -> failures += validateElement(propertySchema, child, propertyPath)
                schemaObject.has("additionalProperties") -> {
                    val additional = schemaObject.get("additionalProperties")
                    if (additional.isJsonPrimitive && additional.asJsonPrimitive.isBoolean && !additional.asBoolean) {
                        failures += "$propertyPath is not allowed"
                    } else {
                        failures += validateElement(additional, child, propertyPath)
                    }
                }
            }
        }
        return failures
    }

    private fun validateArray(
        schemaObject: JsonObject,
        value: JsonArray,
        path: String,
    ): List<String> {
        val failures = mutableListOf<String>()
        schemaObject.get("minItems")?.let { if (value.size() < it.asInt) failures += "$path must contain at least ${it.asInt} item(s)" }
        schemaObject.get("maxItems")?.let { if (value.size() > it.asInt) failures += "$path must contain at most ${it.asInt} item(s)" }
        if (schemaObject.get("uniqueItems")?.asBoolean == true) {
            val rendered = value.map(JsonValues::render)
            if (rendered.toSet().size != rendered.size) failures += "$path must contain unique items"
        }

        val prefixItems = schemaObject.getAsJsonArray("prefixItems")
        if (prefixItems != null) {
            prefixItems.forEachIndexed { index, itemSchema ->
                if (index < value.size()) failures += validateElement(itemSchema, value[index], "$path/$index")
            }
        }
        val items = schemaObject.get("items")
        when {
            items == null -> Unit
            items.isJsonPrimitive && items.asJsonPrimitive.isBoolean && !items.asBoolean -> {
                val allowed = prefixItems?.size() ?: 0
                if (value.size() > allowed) failures += "$path must not contain more than $allowed item(s)"
            }
            else -> {
                val start = prefixItems?.size() ?: 0
                (start until value.size()).forEach { index ->
                    failures += validateElement(items, value[index], "$path/$index")
                }
            }
        }
        return failures
    }

    private fun resolveRef(ref: String): JsonElement {
        require(ref.startsWith("#/")) { "Only local manifest schema refs are supported: $ref" }
        return ref
            .removePrefix("#/")
            .split('/')
            .fold(schema as JsonElement) { current, raw ->
                current.asJsonObject.get(raw.replace("~1", "/").replace("~0", "~"))
            }
    }

    private fun includePaths(
        root: JsonObject,
        base: Path,
    ): List<Path> {
        val include = root.get("include") ?: return emptyList()
        val entries =
            when {
                include.isJsonPrimitive && include.asJsonPrimitive.isString -> listOf(include.asString)
                include.isJsonArray ->
                    include.asJsonArray
                        .filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        .map { it.asString }
                else -> emptyList()
            }
        return entries.map { base.resolve(it).normalize() }
    }

    private fun JsonElement.matchesType(type: String): Boolean =
        when (type) {
            "object" -> isJsonObject
            "array" -> isJsonArray
            "string" -> isJsonPrimitive && asJsonPrimitive.isString
            "integer" -> isJsonPrimitive && asJsonPrimitive.isNumber && asBigDecimal.stripTrailingZeros().scale() <= 0
            "number" -> isJsonPrimitive && asJsonPrimitive.isNumber
            "boolean" -> isJsonPrimitive && asJsonPrimitive.isBoolean
            else -> true
        }

    private fun JsonElement.typeName(): String =
        when {
            isJsonObject -> "object"
            isJsonArray -> "array"
            isJsonNull -> "null"
            isJsonPrimitive -> asJsonPrimitive.primitiveTypeName()
            else -> "value"
        }

    private fun JsonPrimitive.primitiveTypeName(): String =
        when {
            isString -> "string"
            isBoolean -> "boolean"
            isNumber -> "number"
            else -> "primitive"
        }

    private fun JsonObject.getString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun escapePointer(value: String): String = value.replace("~", "~0").replace("/", "~1")
}

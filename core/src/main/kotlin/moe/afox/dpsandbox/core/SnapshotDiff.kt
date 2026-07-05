package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Kind of change found between two deterministic sandbox snapshots.
 */
enum class SnapshotDiffKind {
    ADDED,
    REMOVED,
    CHANGED,
}

/**
 * One JSON-level difference between two snapshots.
 *
 * Paths use JSON Pointer syntax. The root path is `/`.
 */
data class SnapshotDiffEntry(
    val path: String,
    val kind: SnapshotDiffKind,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
) {
    /**
     * Serializes this diff entry into deterministic JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("path", path.ifBlank { "/" })
            json.addProperty("kind", kind.name.lowercase())
            before?.let { json.add("before", it.deepCopy()) }
            after?.let { json.add("after", it.deepCopy()) }
        }

    /**
     * Renders this entry as one compact human-readable line.
     */
    fun render(): String {
        val pathText = path.ifBlank { "/" }
        return when (kind) {
            SnapshotDiffKind.ADDED -> "+ $pathText = ${after?.let(JsonValues::render) ?: "null"}"
            SnapshotDiffKind.REMOVED -> "- $pathText was ${before?.let(JsonValues::render) ?: "null"}"
            SnapshotDiffKind.CHANGED -> "~ $pathText: ${before?.let(JsonValues::render) ?: "null"} -> ${after?.let(JsonValues::render) ?: "null"}"
        }
    }
}

/**
 * Computes stable JSON diffs for sandbox snapshots and other deterministic JSON values.
 */
object SnapshotDiff {
    /**
     * Returns every JSON-level difference between [before] and [after].
     */
    @JvmStatic
    fun diff(before: JsonElement, after: JsonElement): List<SnapshotDiffEntry> {
        val entries = mutableListOf<SnapshotDiffEntry>()
        diffValue("", before, after, entries)
        return entries.sortedWith(compareBy<SnapshotDiffEntry> { it.path }.thenBy { it.kind.name })
    }

    /**
     * Serializes a diff list into a JSON array.
     */
    @JvmStatic
    fun toJson(entries: List<SnapshotDiffEntry>): JsonArray =
        JsonArray().also { array -> entries.forEach { array.add(it.toJson()) } }

    /**
     * Renders a diff list as line-oriented text.
     */
    @JvmStatic
    fun render(entries: List<SnapshotDiffEntry>): String =
        if (entries.isEmpty()) {
            "<no snapshot changes>"
        } else {
            entries.joinToString(separator = System.lineSeparator()) { it.render() }
        }

    private fun diffValue(path: String, before: JsonElement?, after: JsonElement?, entries: MutableList<SnapshotDiffEntry>) {
        when {
            before == null && after == null -> return
            before == null -> entries += SnapshotDiffEntry(path, SnapshotDiffKind.ADDED, after = after?.normalized())
            after == null -> entries += SnapshotDiffEntry(path, SnapshotDiffKind.REMOVED, before = before.normalized())
            before == after -> return
            before.isJsonObject && after.isJsonObject -> diffObjects(path, before.asJsonObject, after.asJsonObject, entries)
            before.isJsonArray && after.isJsonArray -> diffArrays(path, before.asJsonArray, after.asJsonArray, entries)
            else -> entries += SnapshotDiffEntry(path, SnapshotDiffKind.CHANGED, before.normalized(), after.normalized())
        }
    }

    private fun diffObjects(path: String, before: JsonObject, after: JsonObject, entries: MutableList<SnapshotDiffEntry>) {
        val keys = (before.keySet() + after.keySet()).sorted()
        keys.forEach { key ->
            diffValue(joinPath(path, key), before.get(key), after.get(key), entries)
        }
    }

    private fun diffArrays(path: String, before: JsonArray, after: JsonArray, entries: MutableList<SnapshotDiffEntry>) {
        val max = maxOf(before.size(), after.size())
        for (index in 0 until max) {
            diffValue(
                joinPath(path, index.toString()),
                before.getOrNull(index),
                after.getOrNull(index),
                entries,
            )
        }
    }

    private fun JsonArray.getOrNull(index: Int): JsonElement? =
        if (index in 0 until size()) get(index) else null

    private fun JsonElement.normalized(): JsonElement =
        if (this is JsonNull) JsonNull.INSTANCE else deepCopy()

    private fun joinPath(path: String, token: String): String =
        "${path}/${escape(token)}"

    private fun escape(token: String): String =
        token.replace("~", "~0").replace("/", "~1")
}

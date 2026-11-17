package moe.afox.dpsandbox.core

import com.google.gson.JsonElement

/** Compound filters match a subset of fields. List filters match elements in any order. */
internal fun matchesNbt(actual: JsonElement, expected: JsonElement): Boolean =
    when {
        expected.isJsonObject ->
            actual.isJsonObject &&
                expected.asJsonObject.entrySet().all { (key, pattern) ->
                    actual.asJsonObject.get(key)?.let { matchesNbt(it, pattern) } == true
                }
        expected.isJsonArray ->
            actual.isJsonArray &&
                if (expected.asJsonArray.isEmpty) {
                    actual.asJsonArray.isEmpty
                } else {
                    expected.asJsonArray.all { pattern ->
                        actual.asJsonArray.any { matchesNbt(it, pattern) }
                    }
                }
        else -> actual == expected
    }

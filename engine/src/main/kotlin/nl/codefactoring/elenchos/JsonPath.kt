// SPDX-FileCopyrightText: 2026 CodeFactoring B.V.
// SPDX-License-Identifier: Apache-2.0

package nl.codefactoring.elenchos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tiny JSONPath-style navigator supporting `.key` and `[n]`, e.g.
 * `[0].status`, `data[0].id`, `owner`. Deliberately small — enough to
 * assert on REST responses without pulling in a JSONPath dependency.
 */
object JsonPath {

    private val token = Regex("""\[[0-9]+]|[A-Za-z_][A-Za-z0-9_]*""")

    fun parse(path: String): List<Any> =
        token.findAll(path).map { m ->
            val t = m.value
            if (t.startsWith("[")) t.substring(1, t.length - 1).toInt() else t
        }.toList()

    /** Navigate; returns the element at [path] or null if any segment is missing. */
    fun get(root: JsonElement?, path: String): JsonElement? {
        var cur: JsonElement? = root
        for (seg in parse(path)) {
            cur = when {
                cur is JsonArray && seg is Int -> cur.getOrNull(seg)
                cur is JsonObject && seg is String -> cur[seg]
                else -> return null
            }
        }
        return cur
    }

    /** Render a leaf for comparison/display: unquoted string, number, bool, null. */
    fun render(el: JsonElement?): String = when (el) {
        null -> "<missing>"
        is JsonNull -> "null"
        is JsonPrimitive -> el.content
        else -> el.toString()
    }
}

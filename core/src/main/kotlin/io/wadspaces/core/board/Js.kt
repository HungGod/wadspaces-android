package io.wadspaces.core.board

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// JSON as wad-core and wad-board read it (serde_json Values, JS semantics).

/** A field as text, "" when it isn't text. */
internal fun s(v: JsonElement?, k: String): String =
    ((v as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

/** A field's text, or null when it isn't text. */
internal fun sOrNull(v: JsonElement?, k: String): String? =
    ((v as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A field's strings (the rest of its items left out). */
internal fun strs(v: JsonElement?, k: String): List<String> =
    ((v as? JsonObject)?.get(k) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

internal fun field(v: JsonElement?, k: String): JsonElement? = (v as? JsonObject)?.get(k)

internal fun arrOf(v: JsonElement?, k: String): List<JsonElement> = (field(v, k) as? JsonArray) ?: emptyList()

/** JS truthiness. */
internal fun truthy(v: JsonElement?): Boolean = when (v) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        v.isString -> v.content.isNotEmpty()
        v.booleanOrNull != null -> v.booleanOrNull!!
        else -> v.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
    }
    else -> true
}

internal fun str(x: String) = JsonPrimitive(x)
internal fun strList(xs: List<String>) = JsonArray(xs.map(::JsonPrimitive))

/** An object from pairs (null values left out). */
internal fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(linkedMapOf<String, JsonElement>().apply { for ((k, v) in pairs) if (v != null) put(k, v) })

internal fun JsonObject.with(k: String, v: JsonElement): JsonObject = JsonObject(LinkedHashMap(this).apply { put(k, v) })
internal fun JsonObject.without(k: String): JsonObject = JsonObject(LinkedHashMap(this).apply { remove(k) })

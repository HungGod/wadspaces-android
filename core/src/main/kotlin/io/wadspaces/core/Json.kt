package io.wadspaces.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How the core reads and writes JSON: unknown keys kept out of the way. */
val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** A field as text ("" when it isn't text), as wad-core's `s()`. */
fun JsonElement?.str(key: String): String =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""

/** A field, or JsonNull. */
operator fun JsonElement?.get(key: String): JsonElement = (this as? JsonObject)?.get(key) ?: JsonNull

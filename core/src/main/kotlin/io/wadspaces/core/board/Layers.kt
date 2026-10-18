package io.wadspaces.core.board

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** wad-core's layers (crates/wad-core/src/layers.rs): what the phone needs of them. */
object Layers {
    /** How deep templates can stack. */
    const val MAX_CHAIN = 8

    fun emptySetup(templateId: String): JsonObject = obj(
        "templateId" to str(templateId),
        "browserMode" to str("browser"),
        "webapps" to JsonArray(emptyList()),
        "extensions" to JsonArray(emptyList()),
        "tools" to JsonArray(emptyList()),
        "autostart" to JsonArray(emptyList()),
    )

    /** Just the setup part of an Extensions layer. */
    fun setupOf(c: JsonElement): JsonObject {
        fun orList(k: String) = (field(c, k) as? JsonArray) ?: JsonArray(emptyList())
        return obj(
            "templateId" to str(s(c, "templateId")),
            "browserMode" to str(if (s(c, "browserMode") == "focus") "focus" else "browser"),
            "webapps" to orList("webapps"),
            "extensions" to orList("extensions"),
            "tools" to orList("tools"),
            "autostart" to orList("autostart"),
        )
    }

    /** A web app's id from its name: "Google Cloud" → "google-cloud". */
    fun webappId(name: String): String {
        val lower = name.lowercase()
        val b = StringBuilder()
        var run = false
        for (c in lower) {
            if (c in 'a'..'z' || c in '0'..'9') {
                b.append(c)
                run = false
            } else if (!run) {
                b.append('-')
                run = true
            }
        }
        val id = b.toString().trim('-').take(60).trim('-')
        return id.ifEmpty { "app" }
    }

    /** A template and what it builds on, bottom first (walking `parent`). */
    fun chainOf(lib: JsonElement, templateId: String): List<JsonObject> {
        fun find(id: String) = arrOf(lib, "templates").firstOrNull { s(it, "id") == id } as? JsonObject
        val chain = mutableListOf<JsonObject>()
        var next = find(templateId)
        while (next != null) {
            val t = next
            if (chain.size > MAX_CHAIN || chain.any { s(it, "id") == s(t, "id") }) break
            next = s(t, "parent").takeIf { it.isNotEmpty() }?.let(::find)
            chain += t
        }
        return chain.reversed()
    }

    /** What a version 3 wadspace is built from: `{chain, template, setup, extensions?}`, or null. */
    fun resolveStack(lib: JsonElement, w: JsonElement): JsonElement {
        val chain = chainOf(lib, s(w, "template"))
        val t = chain.lastOrNull() ?: return JsonNull
        val tid = s(t, "id")
        val ext = arrOf(lib, "extensions").firstOrNull { s(it, "id") == s(w, "extensions") && s(it, "templateId") == tid }
        return if (ext != null) {
            obj("chain" to JsonArray(chain), "template" to t, "setup" to setupOf(ext), "extensions" to ext)
        } else {
            obj("chain" to JsonArray(chain), "template" to t, "setup" to emptySetup(tid))
        }
    }

    /** The image a version 3 wadspace runs: its Extensions layer's, else its template's. */
    fun stackImageKey(w: JsonElement): String {
        val e = s(w, "extensions")
        val t = s(w, "template")
        return when {
            e.isNotEmpty() -> "custom:$e"
            t.isNotEmpty() -> "template:$t"
            else -> ""
        }
    }

    /** Why a stack can't be built yet (the web app's notReady), or null. */
    fun notReady(w: JsonElement, lib: JsonElement): String? = when {
        s(w, "template").isEmpty() -> "It needs a template with apps."
        resolveStack(lib, w) == JsonNull -> "Its template is gone."
        field(w, "customization") !is JsonObject -> "It needs a customization."
        s(field(w, "customization"), "name").isBlank() -> "Its customization needs a name."
        else -> null
    }
}

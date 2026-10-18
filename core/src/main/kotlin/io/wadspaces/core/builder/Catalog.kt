package io.wadspaces.core.builder

import io.wadspaces.core.json
import io.wadspaces.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** An app a template can have (wad-core's catalog.json). */
data class App(val id: String, val name: String, val summary: String, val category: String, val opensFolders: Boolean)

/** A VS Code extension, or a tool (command-line kit), an Extensions layer can add. */
data class Item(val id: String, val name: String, val description: String)

/** A starter template (starters.json). */
data class Starter(val id: String, val name: String, val description: String, val apps: List<String>)

/** A wallpaper the Builder offers (presets.json): its JSON, and its picture's file. */
data class Preset(val name: String, val wallpaper: JsonObject, val thumb: String)

/** What the Builder picks from: the catalog, the starters and the wallpapers. */
class Catalog(val apps: List<App>, val extensions: List<Item>, val tools: List<Item>, val starters: List<Starter>, val presets: List<Preset>) {
    fun app(id: String) = apps.firstOrNull { it.id == id }

    /** The apps' names, as a template's line says them. */
    fun appNames(ids: List<String>) = ids.joinToString(", ") { app(it)?.name ?: it }

    companion object {
        fun parse(catalog: String, starters: String, presets: String): Catalog {
            val c = json.parseToJsonElement(catalog).jsonObject
            fun items(k: String) = (c[k] as? JsonArray)?.map { Item(it.str("id"), it.str("name"), it.str("description")) } ?: emptyList()
            val apps = (c["apps"] as? JsonArray)?.map {
                App(
                    it.str("id"), it.str("name"), it.str("summary"), it.str("category"),
                    ((it as JsonObject)["opensFolders"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            } ?: emptyList()
            val st = (json.parseToJsonElement(starters) as? JsonArray)?.map {
                Starter(it.str("id"), it.str("name"), it.str("description"), strings((it as JsonObject)["apps"]))
            } ?: emptyList()
            val ps = (json.parseToJsonElement(presets) as? JsonArray)?.map {
                Preset(it.str("name"), (it as JsonObject)["wallpaper"] as JsonObject, it.str("thumb"))
            } ?: emptyList()
            return Catalog(apps, items("extensions"), items("tools"), st, ps)
        }

        private fun strings(v: JsonElement?) = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    }
}

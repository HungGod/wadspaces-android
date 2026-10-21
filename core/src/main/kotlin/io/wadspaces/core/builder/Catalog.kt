package io.wadspaces.core.builder

import io.wadspaces.core.json
import io.wadspaces.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** An app a template can have (wad-core's catalog.json). */
data class App(val id: String, val name: String, val summary: String, val category: String, val opensFolders: Boolean)

/** A VS Code extension, or a tool (command-line kit): what VS Code and the command line take. */
data class Item(val id: String, val name: String, val description: String)

/** What the Builder picks from (wad-core's catalog.json): apps, extensions and tools. */
class Catalog(val apps: List<App>, val extensions: List<Item>, val tools: List<Item>) {
    companion object {
        fun parse(catalog: String): Catalog {
            val c = json.parseToJsonElement(catalog).jsonObject
            fun items(k: String) = (c[k] as? JsonArray)?.map { Item(it.str("id"), it.str("name"), it.str("description")) } ?: emptyList()
            val apps = (c["apps"] as? JsonArray)?.map {
                App(
                    it.str("id"), it.str("name"), it.str("summary"), it.str("category"),
                    ((it as JsonObject)["opensFolders"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            } ?: emptyList()
            return Catalog(apps, items("extensions"), items("tools"))
        }
    }
}

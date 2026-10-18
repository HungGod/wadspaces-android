package io.wadspaces.core.builder

import io.wadspaces.core.board.Layers
import io.wadspaces.core.board.Sync
import io.wadspaces.core.str
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A web app an Extensions layer opens in WadBrowser. */
data class WebApp(val id: String, val name: String, val url: String)

/** A GitHub repo cloned into the wadspace, at ~/Desktop/<folder>. */
data class Project(val id: String, val repo: String, val folder: String)

/** The template: one of yours (and what it builds on), a starter, or a new one. */
sealed interface TemplatePick {
    data class Yours(val id: String) : TemplatePick
    data class FromStarter(val starter: Starter) : TemplatePick
    /** New, from catalog apps, built on one of yours or on nothing. */
    data class New(val name: String, val apps: List<String>, val parent: String? = null, val description: String = "") : TemplatePick
}

/** Its Extensions: none, yours, or new. */
sealed interface ExtensionsPick {
    data object None : ExtensionsPick
    data class Yours(val id: String) : ExtensionsPick
    data class New(
        val name: String,
        val focus: Boolean = false,
        val webapps: List<WebApp> = emptyList(),
        val extensions: List<String> = emptyList(),
        val tools: List<String> = emptyList(),
    ) : ExtensionsPick
}

/** What the wadspace is to the person: its name, look and projects. */
data class Customization(
    val name: String,
    val description: String = "",
    val wallpaper: JsonObject = Sync.blankWallpaper(),
    val projects: List<Project> = emptyList(),
    val titleBars: Boolean = false,
)

/**
 * The phone's builder: its picks as one board stack (the modules in
 * wad-board's shapes), for [Sync.planNew] to save.
 */
object Builder {
    private val seq = AtomicLong()

    /** An id like the board's (wad-board's uid): `<prefix>-<ms in base 36>-<n>`. */
    fun uid(prefix: String, now: Long = System.currentTimeMillis()): String =
        "$prefix-${now.toString(36)}-${seq.getAndIncrement().toString(36)}"

    /** A folder name free among `projects` (as the client's free_as). */
    fun freeFolder(want: String, projects: List<Project>): String {
        val b = StringBuilder()
        var dash = false
        for (c in want) {
            if ((c.isLetterOrDigit() && c.code < 128) || c in "._-") {
                b.append(c)
                dash = false
            } else if (!dash) {
                b.append('-')
                dash = true
            }
        }
        val clean = b.toString().trim('-').ifEmpty { "Project" }
        var name = clean
        var n = 2
        while (projects.any { it.folder.equals(name, ignoreCase = true) }) name = "$clean-${n++}"
        return name
    }

    /** A GitHub repo added (once) to the projects. */
    fun addRepo(projects: List<Project>, repo: String): List<Project> {
        if (projects.any { it.repo.equals(repo, ignoreCase = true) }) return projects
        return projects + Project(uid("project"), repo, freeFolder(repo.substringAfterLast('/'), projects))
    }

    private fun templateModule(stack: String, doc: JsonElement?, name: String, description: String, apps: List<String>, key: String) =
        buildJsonObject {
            put("layer", "template")
            put("id", "$key@$stack")
            doc?.let { put("ref", it.str("id")) }
            put("name", name)
            put("description", description)
            put("apps", JsonArray(apps.map(::JsonPrimitive)))
        }

    /** The template modules, bottom first. */
    fun templates(stack: String, pick: TemplatePick, lib: JsonObject): List<JsonObject> {
        fun yours(id: String) = Layers.chainOf(lib, id).map { t ->
            templateModule(stack, t, t.str("name"), t.str("description"), strings(t["apps"]), t.str("id"))
        }
        return when (pick) {
            is TemplatePick.Yours -> yours(pick.id)
            is TemplatePick.FromStarter -> listOf(
                templateModule(stack, null, pick.starter.name, pick.starter.description, pick.starter.apps, "new-template"),
            )
            is TemplatePick.New -> (pick.parent?.let(::yours) ?: emptyList()) +
                templateModule(stack, null, pick.name, pick.description, pick.apps, "new-template")
        }
    }

    /** The Extensions module, or null. */
    fun extensions(stack: String, pick: ExtensionsPick, lib: JsonObject): JsonObject? = when (pick) {
        ExtensionsPick.None -> null
        is ExtensionsPick.Yours -> {
            val doc = (lib["extensions"] as? JsonArray)?.firstOrNull { it.str("id") == pick.id } ?: error("no Extensions ${pick.id}")
            // The board's own reading of the document.
            val docs = buildJsonObject {
                put("templates", JsonArray(emptyList()))
                put("extensions", JsonArray(listOf(doc)))
                put("wadspaces", JsonArray(listOf(buildJsonObject {
                    put("id", stack)
                    put("extensions", pick.id)
                })))
            }
            (Sync.toBoard(docs)[0] as JsonObject)["extensions"] as JsonObject
        }
        is ExtensionsPick.New -> {
            val base = "draft@$stack"
            val subs = mutableListOf<JsonElement>()
            if (pick.webapps.isNotEmpty() || pick.focus) {
                subs += buildJsonObject {
                    put("key", "wadbrowser")
                    put("id", "$base:wadbrowser")
                    put("origin", pick.name)
                    put("mode", if (pick.focus) "focus" else "browser")
                    put("webapps", JsonArray(pick.webapps.map { w ->
                        buildJsonObject {
                            put("id", w.id)
                            put("name", w.name)
                            put("url", w.url)
                        }
                    }))
                }
            }
            if (pick.extensions.isNotEmpty()) {
                subs += buildJsonObject {
                    put("key", "vscode")
                    put("id", "$base:vscode")
                    put("origin", pick.name)
                    put("extensions", JsonArray(pick.extensions.map(::JsonPrimitive)))
                }
            }
            if (pick.tools.isNotEmpty()) {
                subs += buildJsonObject {
                    put("key", "tools")
                    put("id", "$base:tools")
                    put("origin", pick.name)
                    put("tools", JsonArray(pick.tools.map(::JsonPrimitive)))
                }
            }
            buildJsonObject {
                put("layer", "extensions")
                put("id", "draft-extensions@$stack")
                put("name", pick.name)
                put("subs", JsonArray(subs))
                put("autostart", JsonArray(emptyList()))
            }
        }
    }

    /** The customization module. */
    fun customization(stack: String, c: Customization): JsonObject {
        val id = "customization@$stack"
        return buildJsonObject {
            put("layer", "customization")
            put("id", id)
            put("subs", JsonArray(listOf(
                buildJsonObject {
                    put("key", "look")
                    put("id", "$id:look")
                    put("origin", c.name)
                    put("name", c.name)
                    put("description", c.description)
                    put("wallpaper", c.wallpaper)
                    if (c.titleBars) put("titleBars", true)
                },
                buildJsonObject {
                    put("key", "folders")
                    put("id", "$id:folders")
                    put("origin", c.name)
                    put("mounts", JsonArray(c.projects.map { p ->
                        buildJsonObject {
                            put("id", p.id)
                            put("kind", "github")
                            put("repo", p.repo)
                            put("as", p.folder)
                        }
                    }))
                },
                buildJsonObject {
                    put("key", "secrets")
                    put("id", "$id:secrets")
                    put("origin", c.name)
                    put("bySlot", JsonObject(emptyMap()))
                },
            )))
        }
    }

    /** The whole stack. */
    fun stack(id: String, t: TemplatePick, e: ExtensionsPick, c: Customization, lib: JsonObject): JsonObject = buildJsonObject {
        put("id", id)
        put("templates", JsonArray(templates(id, t, lib)))
        extensions(id, e, lib)?.let { put("extensions", it) }
        put("customization", customization(id, c))
    }

    /** What's missing before it can be saved, in words (none: it can). */
    fun problems(t: TemplatePick, e: ExtensionsPick, c: Customization, lib: JsonObject, catalog: Catalog): List<String> {
        val out = mutableListOf<String>()
        if (c.name.isBlank()) out += "Give it a name."
        val apps = when (t) {
            is TemplatePick.Yours -> Layers.chainOf(lib, t.id).lastOrNull()?.let { strings(it["apps"]) }
            is TemplatePick.FromStarter -> t.starter.apps
            is TemplatePick.New -> t.apps
        }
        if (apps == null) out += "That template is gone."
        if (t is TemplatePick.New) {
            if (t.apps.isEmpty()) out += "Pick at least one app."
            if (t.name.isBlank()) out += "Name the template."
            t.parent?.let { p ->
                val under = Layers.chainOf(lib, p).lastOrNull()?.let { strings(it["apps"]) } ?: emptyList()
                val missing = under.filter { it !in t.apps }
                if (missing.isNotEmpty()) out += "It builds on a template with ${catalog.appNames(missing)}: keep those."
                if (t.apps.size <= under.size) out += "Add an app to what it builds on."
            }
        }
        if (e is ExtensionsPick.New) {
            if (e.name.isBlank()) out += "Name the Extensions."
            val has = apps ?: emptyList()
            if ((e.webapps.isNotEmpty() || e.focus) && "wadbrowser" !in has) out += "Web apps need WadBrowser in the template."
            if (e.extensions.isNotEmpty() && "vscode" !in has) out += "VS Code extensions need VS Code in the template."
        }
        return out
    }

    private fun strings(v: JsonElement?) = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
}

package io.wadspaces.core.board

import io.wadspaces.core.json
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * What the phone needs to name a wadspace's packages as wadd does, and its
 * templates as the board does (wadspaces' `cargo xtask packages graph`,
 * synced as packages.json): each catalog app's package (null: the base has
 * it) and name, the catalog's tools, the features in install order, and
 * what each package needs.
 */
class Packages(
    /** Each catalog app's package (null: the base has it). */
    val apps: Map<String, String?>,
    val names: Map<String, String>,
    val tools: Set<String>,
    val features: List<String>,
    val needs: Map<String, List<String>>,
) {
    /** A catalog app's package; none for one the base has. */
    fun ofApp(app: String): String? = apps[app]

    /** An app's name (its id, when the catalog hasn't it). */
    fun appName(app: String): String = names[app] ?: app

    /** A command-line tool's package (tools are features). */
    fun ofTool(tool: String): String? = tool.takeIf { it in features }

    /** A VS Code extension's package. */
    fun ofExtension(ext: String): String? = if (V4.isExtensionId(ext)) "ext-${ext.lowercase()}" else null

    /** The packages `id` needs under it. */
    fun needsOf(id: String): List<String> = if (id.startsWith("ext-")) listOf("vscode") else needs[id] ?: emptyList()

    private data class Rank(val group: Int, val at: Int, val id: String) : Comparable<Rank> {
        override fun compareTo(other: Rank) = compareValuesBy(this, other, Rank::group, Rank::at, Rank::id)
    }

    /** Features in install order, then Debian apps, then extensions, each by id. */
    private fun rank(id: String): Rank {
        val i = features.indexOf(id)
        return when {
            i >= 0 -> Rank(0, i, id)
            id.startsWith("apt-") -> Rank(1, 0, id)
            id.startsWith("ext-") -> Rank(2, 0, id)
            else -> Rank(3, 0, id)
        }
    }

    /** `ids` and everything they need, in WadSpaces' order. */
    fun closure(ids: List<String>): List<String> {
        val out = mutableListOf<String>()
        val todo = ids.toMutableList()
        while (todo.isNotEmpty()) {
            val id = todo.removeAt(todo.size - 1)
            if (id in out) continue
            todo += needsOf(id)
            out += id
        }
        return out.sortedBy(::rank)
    }

    companion object {
        fun parse(text: String): Packages {
            val g = json.parseToJsonElement(text).jsonObject
            fun list(v: JsonElement?) = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
            return Packages(
                apps = (g["apps"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content } ?: emptyMap(),
                names = (g["names"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" } ?: emptyMap(),
                tools = list(g["tools"]).toSet(),
                features = list(g["features"]),
                needs = (g["needs"] as? JsonObject)?.mapValues { (_, v) -> list(v) } ?: emptyMap(),
            )
        }
    }
}

/**
 * Version 4 wadspaces (wad-core's v4.rs): design B's Builder. A wadspace
 * lives on one machine and is its own templates (each its apps, each app
 * with its extras) and its customization (version 3's). Its image is a set:
 * the base with its packages, named by its packages alone.
 */
object V4 {
    const val VERSION = 4L
    const val MAX_TEMPLATES = 20
    const val MAX_APPS = 40
    const val TERMINAL = "terminal"
    private const val SET_PREFIX = "localhost/wadspaces-set-"

    /** The backgrounds wad-wallpaper draws (each dark and light). */
    val TEXTURES = listOf("carbon", "marble", "wood", "contour", "grid", "terrazzo", "honeycomb", "seigaiha", "brushed", "camo", "stars", "halftone")

    fun isV4(w: JsonElement?): Boolean = ((field(w, "version") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull) == VERSION

    /** A machine's id, as the account names it. */
    fun isMachineId(m: String) = m.isNotEmpty() && m.length <= 64 && m.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    /** A lowercase id (wad-core's spec::is_id). */
    fun isId(s: String): Boolean =
        s.isNotEmpty() && s.length <= 63 && (s[0] in 'a'..'z' || s[0] in '0'..'9') && s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    /** A marketplace id: publisher.name (wad-core's layers::is_extension_id). */
    fun isExtensionId(id: String): Boolean {
        fun ok(p: String) = p.isNotEmpty() && p.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
        val dot = id.indexOf('.')
        return id.length <= 120 && dot >= 0 && ok(id.substring(0, dot)) && id.substring(dot + 1).split('.').all(::ok)
    }

    /** Rust's `{:?}` of text: quoted, its quotes and backslashes escaped. */
    private fun dbg(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** JS's trim (wad-core's js::trim). */
    private fun jsTrim(s: String) = s.trim { it.isWhitespace() || it == '﻿' }

    private fun nameOk(what: String, name: String): String? {
        val n = jsTrim(name).codePointCount(0, jsTrim(name).length)
        return if (n in 1..80) null else "$what needs a name of 1 to 80 characters"
    }

    /** An http(s) address with a host (wad-core's url::host). */
    private fun webUrl(u: String): Boolean {
        val lower = u.take(8).lowercase()
        val rest = when {
            lower.startsWith("https://") -> u.substring(8)
            lower.startsWith("http://") -> u.substring(7)
            else -> return false
        }
        val auth = rest.split('/', '?', '#', '\\').first()
        val host = auth.substringAfterLast('@').substringBefore(':')
        return host.isNotEmpty()
    }

    /** Whether `x` is something `app` takes, or why not. */
    private fun extraOk(app: String, x: JsonElement, g: Packages): String? {
        val id = s(x, "id")
        return when (app) {
            "vscode" -> if (isExtensionId(id)) null else "${dbg(id)} isn't a VS Code extension's id"
            TERMINAL -> if (id in g.tools) null else "there's no command-line tool ${dbg(id)}"
            "wadbrowser" -> when {
                !isId(id) -> "a web app's id ${dbg(id)} isn't one"
                !webUrl(s(x, "url")) -> "the web app $id needs an http(s) address"
                else -> nameOk("a web app", s(x, "name"))
            }
            else -> "nothing is added to $app"
        }
    }

    /** What's wrong with a version 4 wadspace, or null. */
    fun check(w: JsonElement, g: Packages): String? {
        if (!isV4(w)) return "it isn't a version 4 wadspace"
        if (!isId(s(w, "id"))) return "${dbg(s(w, "id"))} isn't an id"
        if (!isMachineId(s(w, "machine"))) return "it isn't on a machine"
        val templates = arrOf(w, "templates")
        if (templates.size > MAX_TEMPLATES) return "at most $MAX_TEMPLATES templates"
        val ids = mutableListOf<String>()
        for (t in templates) {
            val tid = s(t, "id")
            if (!isId(tid) || tid in ids) return "a template's id ${dbg(tid)} is missing or used twice"
            ids += tid
            nameOk("a template", s(t, "name"))?.let { return it }
            val apps = arrOf(t, "apps")
            if (apps.size > MAX_APPS) return "at most $MAX_APPS apps in a template"
            val seen = mutableListOf<String>()
            for (e in apps) {
                val app = s(e, "app")
                if (app !in g.apps) return "there's no app ${dbg(app)}"
                if (app in seen) return "$app is in ${s(t, "name")} twice"
                seen += app
                val m = field(e, "mode")
                if (m != null && (app != "wadbrowser" || (m as? JsonPrimitive)?.takeIf { it.isString }?.content !in listOf("browser", "focus"))) {
                    return "$app has no mode $m"
                }
                for (x in arrOf(e, "extras")) extraOk(app, x, g)?.let { return it }
            }
        }
        val c = field(w, "customization")
        nameOk("a wadspace", s(c, "name"))?.let { return it }
        val wp = field(c, "wallpaper")
        if (wp != null && wp != JsonNull && !(s(wp, "type") == "texture" && s(wp, "value") in TEXTURES)) {
            return "its background is one of WadSpaces' textures, not $wp"
        }
        return null
    }

    /** Every app in it once, where it first is, with all the extras its templates give it. */
    fun appsOf(w: JsonElement): List<JsonObject> {
        data class A(val app: String, var mode: String?, val extras: MutableList<JsonElement>)
        val apps = mutableListOf<A>()
        for (t in arrOf(w, "templates")) for (e in arrOf(t, "apps")) {
            val app = s(e, "app")
            if (app.isEmpty()) continue
            val a = apps.firstOrNull { it.app == app } ?: A(app, null, mutableListOf()).also { apps += it }
            if (a.mode == null) a.mode = sOrNull(e, "mode")
            for (x in arrOf(e, "extras")) if (a.extras.none { s(it, "id") == s(x, "id") }) a.extras += x
        }
        return apps.map { obj("app" to str(it.app), "mode" to it.mode?.let(::str), "extras" to JsonArray(it.extras)) }
    }

    /** The packages its image is made of, with what they need, in WadSpaces' order. */
    fun packagesOf(w: JsonElement, g: Packages): List<String> {
        val ids = mutableListOf<String>()
        for (e in appsOf(w)) {
            val app = s(e, "app")
            g.ofApp(app)?.let { ids += it }
            for (x in arrOf(e, "extras")) {
                when (app) {
                    "vscode" -> g.ofExtension(s(x, "id"))
                    TERMINAL -> g.ofTool(s(x, "id"))
                    else -> null
                }?.let { ids += it }
            }
        }
        return g.closure(ids)
    }

    /** A set's name: its packages', whatever base it's built on. */
    fun setKey(packages: List<String>): String {
        val h = MessageDigest.getInstance("SHA-256")
        h.update("wadspaces-set 1\n".toByteArray())
        for (p in packages) h.update("$p\n".toByteArray())
        return h.digest().take(6).joinToString("") { "%02x".format(it) }
    }

    /** "set:<key>", or "base" for a wadspace with no packages. */
    fun imageKey(w: JsonElement, g: Packages): String = packagesOf(w, g).let { if (it.isEmpty()) "base" else "set:${setKey(it)}" }

    /** The image an image key names (`base`: the machine's base). */
    fun imageRef(key: String, base: String): String = if (key.startsWith("set:")) "$SET_PREFIX${key.removePrefix("set:")}:latest" else base

    /** Version 3's (template, setup) for it. */
    fun asTemplateSetup(w: JsonElement): Pair<JsonObject, JsonObject> {
        val id = s(w, "id")
        val apps = appsOf(w)
        fun extras(app: String) = apps.firstOrNull { s(it, "app") == app }?.let { arrOf(it, "extras") } ?: emptyList()
        fun ids(app: String) = JsonArray(extras(app).map { str(s(it, "id")) })
        val browser = apps.firstOrNull { s(it, "app") == "wadbrowser" }
        val mode = if (browser != null && s(browser, "mode") == "focus") "focus" else "browser"
        val webapps = extras("wadbrowser").map { x ->
            obj("id" to str(s(x, "id")), "name" to str(s(x, "name")), "url" to str(s(x, "url")), "iconUrl" to s(x, "iconUrl").takeIf { it.isNotEmpty() }?.let(::str))
        }
        val template = obj("id" to str(id), "name" to str(s(field(w, "customization"), "name")), "apps" to JsonArray(apps.map { str(s(it, "app")) }))
        val autostart = field(field(w, "customization"), "autostart") as? JsonArray ?: JsonArray(emptyList())
        val setup = obj(
            "templateId" to str(id),
            "browserMode" to str(mode),
            "webapps" to JsonArray(webapps),
            "extensions" to ids("vscode"),
            "tools" to ids(TERMINAL),
            "autostart" to autostart,
        )
        return template to setup
    }

    /** Whether its image is on a machine, from the machine's image rows: "never", "update" or "built". */
    fun state(w: JsonElement, images: List<JsonElement>, g: Packages): String {
        val key = imageKey(w, g)
        val base = images.firstOrNull { s(it, "layer") == "base" }
        val row = images.firstOrNull { s(it, "layer") == key } ?: return "never"
        return when {
            key == "base" -> "built"
            base != null && s(base, "id") != s(row, "from") -> "update"
            else -> "built"
        }
    }

    /** What the phone shows of a wadspace. */
    fun view(w: JsonElement, g: Packages): JsonObject {
        val c = field(w, "customization")
        return obj(
            "id" to str(s(w, "id")),
            "machine" to str(s(w, "machine")),
            "name" to str(s(c, "name")),
            "description" to str(s(c, "description")),
            "order" to (field(w, "order") ?: JsonPrimitive(0)),
            "deleted" to JsonPrimitive(truthy(field(w, "deletedAt"))),
            "apps" to JsonArray(appsOf(w).map { str(s(it, "app")) }),
            "packages" to strList(packagesOf(w, g)),
            "imageKey" to str(imageKey(w, g)),
            "wallpaper" to (field(c, "wallpaper") ?: JsonNull),
        )
    }

}

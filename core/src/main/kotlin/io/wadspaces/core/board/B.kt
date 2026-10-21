package io.wadspaces.core.board

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

// Design B's board (wad-board's b/mod.rs): the Builder's version 4
// wadspaces, each on one machine, with its Templates bucket (its own
// templates, each apps with their extras) and its Customizations bucket
// (background, projects, keys). Every edit is an Op, as JSON: the goldens
// (wadspaces' fixtures/board/b.json) replay them here as there.

private const val UNDO_STEPS = 40

/** A field as a number, or null. */
private fun num(v: JsonElement?, k: String): Long? = (field(v, k) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/** A field as JSON, null when it's missing or JSON null. */
private fun opt(v: JsonElement?, k: String): JsonElement? = field(v, k)?.takeIf { it != JsonNull }

class BExtra(val id: String, val name: String? = null, val url: String? = null, val iconUrl: String? = null) {
    fun json() = obj("id" to str(id), "name" to name?.let(::str), "url" to url?.let(::str), "iconUrl" to iconUrl?.let(::str))

    companion object {
        fun of(v: JsonElement?) = BExtra(s(v, "id"), sOrNull(v, "name"), sOrNull(v, "url"), sOrNull(v, "iconUrl"))
    }
}

/** An app in a template, with what it takes. */
class BEntry(val app: String, var mode: String? = null, val extras: MutableList<BExtra> = mutableListOf()) {
    fun json() = obj("app" to str(app), "mode" to mode?.let(::str), "extras" to JsonArray(extras.map { it.json() }))
    fun copy() = BEntry(app, mode, extras.toMutableList())

    companion object {
        fun of(v: JsonElement?) = BEntry(s(v, "app"), sOrNull(v, "mode"), arrOf(v, "extras").map(BExtra::of).toMutableList())
    }
}

class BTemplate(var id: String, var name: String, val apps: MutableList<BEntry> = mutableListOf()) {
    fun json() = obj("id" to str(id), "name" to str(name), "apps" to JsonArray(apps.map { it.json() }))
    fun copy() = BTemplate(id, name, apps.map { it.copy() }.toMutableList())

    companion object {
        fun of(v: JsonElement?) = BTemplate(s(v, "id"), s(v, "name"), arrOf(v, "apps").map(BEntry::of).toMutableList())
    }
}

/** A version 4 wadspace document (its Firestore fields). */
class BWadspace(
    val version: Long = V4.VERSION,
    val id: String,
    var owner: String = "",
    val sharedWith: List<String> = emptyList(),
    val machine: String,
    var order: Long = 0,
    val templates: MutableList<BTemplate> = mutableListOf(),
    /** Version 3's: name, description, wallpaper, titleBars, autostart, projects, secrets. */
    val customization: MutableMap<String, JsonElement> = linkedMapOf(),
    var createdAt: String? = null,
    var updatedAt: String? = null,
    var deletedAt: String? = null,
) {
    val name get() = (customization["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    val deleted get() = !deletedAt.isNullOrEmpty()

    fun json() = obj(
        "version" to JsonPrimitive(version),
        "id" to str(id),
        "owner" to owner.takeIf { it.isNotEmpty() }?.let(::str),
        "sharedWith" to strList(sharedWith),
        "machine" to str(machine),
        "order" to JsonPrimitive(order),
        "templates" to JsonArray(templates.map { it.json() }),
        "customization" to JsonObject(customization),
        "createdAt" to createdAt?.let(::str),
        "updatedAt" to updatedAt?.let(::str),
        "deletedAt" to deletedAt?.let(::str),
    )

    fun copy() = BWadspace(version, id, owner, sharedWith, machine, order, templates.map { it.copy() }.toMutableList(),
        LinkedHashMap(customization), createdAt, updatedAt, deletedAt)

    /** Its secrets, made an object when they aren't one. */
    fun secrets(): MutableMap<String, JsonElement> {
        val sec = customization["secrets"] as? JsonObject
            ?: obj("bySlot" to JsonObject(emptyMap()), "groups" to JsonArray(emptyList())).also { customization["secrets"] = it }
        return LinkedHashMap(sec)
    }

    fun putSecrets(m: Map<String, JsonElement>) {
        customization["secrets"] = JsonObject(m)
    }

    companion object {
        fun of(v: JsonElement?) = BWadspace(
            version = num(v, "version") ?: V4.VERSION,
            id = s(v, "id"),
            owner = s(v, "owner"),
            sharedWith = strs(v, "sharedWith"),
            machine = s(v, "machine"),
            order = num(v, "order") ?: 0,
            templates = arrOf(v, "templates").map(BTemplate::of).toMutableList(),
            customization = LinkedHashMap((field(v, "customization") as? JsonObject) ?: emptyMap()),
            createdAt = sOrNull(v, "createdAt"),
            updatedAt = sOrNull(v, "updatedAt"),
            deletedAt = sOrNull(v, "deletedAt"),
        )
    }
}

data class BMachine(val id: String, val name: String = "")

enum class Which { Templates, Customs }

/** What a drag carries. */
sealed interface BPayload {
    data class Wadspace(val ws: String) : BPayload
    data class Templates(val ws: String, val ids: List<String>) : BPayload
    data class Bucket(val ws: String, val which: Which, val pile: Boolean = false) : BPayload
    data class Project(val ws: String, val id: String) : BPayload
    data class Background(val ws: String) : BPayload
    data class App(val ws: String, val template: String, val app: String) : BPayload
    data class Extra(val ws: String, val template: String, val app: String, val id: String) : BPayload
    data class CatalogApp(val app: String) : BPayload
    data class CatalogExtra(val app: String, val extra: BExtra) : BPayload
    data class Texture(val value: String) : BPayload
    data class CatalogProject(val project: JsonElement) : BPayload

    /** The wadspace it came out of (null: the sidebar's). */
    val source: String?
        get() = when (this) {
            is Wadspace -> ws
            is Templates -> ws
            is Bucket -> ws
            is Project -> ws
            is Background -> ws
            is App -> ws
            is Extra -> ws
            else -> null
        }

    companion object {
        fun of(v: JsonElement?): BPayload = when (val k = s(v, "kind")) {
            "wadspace" -> Wadspace(s(v, "ws"))
            "templates" -> Templates(s(v, "ws"), strs(v, "ids"))
            "bucket" -> Bucket(s(v, "ws"), if (s(v, "which") == "customs") Which.Customs else Which.Templates,
                (field(v, "pile") as? JsonPrimitive)?.booleanOrNull == true)
            "project" -> Project(s(v, "ws"), s(v, "id"))
            "background" -> Background(s(v, "ws"))
            "app" -> App(s(v, "ws"), s(v, "template"), s(v, "app"))
            "extra" -> Extra(s(v, "ws"), s(v, "template"), s(v, "app"), s(v, "id"))
            "catalogApp" -> CatalogApp(s(v, "app"))
            "catalogExtra" -> CatalogExtra(s(v, "app"), BExtra.of(field(v, "extra")))
            "texture" -> Texture(s(v, "value"))
            "catalogProject" -> CatalogProject(field(v, "project") ?: JsonNull)
            else -> throw IllegalArgumentException("no payload kind $k")
        }
    }
}

/** Where in a wadspace a drop lands. */
data class Where(val at: Int? = null, val template: String? = null, val newTemplate: Boolean = false) {
    companion object {
        fun of(v: JsonElement?) = Where(num(v, "at")?.toInt(), sOrNull(v, "template"),
            (field(v, "newTemplate") as? JsonPrimitive)?.booleanOrNull == true)
    }
}

/** Something picked, for Delete selected. */
sealed interface Pick {
    data class Wadspace(val ws: String) : Pick
    data class Template(val ws: String, val id: String) : Pick
    data class Project(val ws: String, val id: String) : Pick
    data class Background(val ws: String) : Pick

    companion object {
        fun of(v: JsonElement?): Pick = when (val k = s(v, "kind")) {
            "wadspace" -> Wadspace(s(v, "ws"))
            "template" -> Template(s(v, "ws"), s(v, "id"))
            "project" -> Project(s(v, "ws"), s(v, "id"))
            "background" -> Background(s(v, "ws"))
            else -> throw IllegalArgumentException("no pick kind $k")
        }
    }
}

/** An edit to the board. */
sealed interface Op {
    data class Drop(val payload: BPayload, val into: String, val at: Where = Where()) : Op
    data class Fresh(val payload: BPayload, val machine: String, val before: String? = null) : Op
    data class CopyTo(val ids: List<String>, val machine: String) : Op
    data class Delete(val picks: List<Pick>) : Op
    data class Restore(val ws: String) : Op
    data class Rename(val ws: String, val name: String) : Op
    data class Describe(val ws: String, val description: String) : Op
    data class RenameTemplate(val ws: String, val template: String, val name: String) : Op
    data class RemoveApp(val ws: String, val template: String, val app: String) : Op
    data class RemoveExtra(val ws: String, val template: String, val app: String, val id: String) : Op
    data class SetMode(val ws: String, val mode: String) : Op
    data class SetBackground(val ws: String, val texture: String?) : Op
    data class ToggleProject(val ws: String, val project: JsonElement) : Op
    data class SetKey(val ws: String, val slot: String, val key: JsonElement?) : Op
    data class SetGroupKey(val ws: String, val group: String, val key: JsonElement?) : Op
    data class MoveMember(val ws: String, val member: String, val to: String?) : Op
    data class NewGroup(val ws: String) : Op
    data class RemoveGroup(val ws: String, val group: String) : Op
    data class RenameKey(val id: String, val name: String) : Op
    data object Undo : Op

    companion object {
        fun of(v: JsonElement?): Op = when (val k = s(v, "op")) {
            "drop" -> Drop(BPayload.of(field(v, "payload")), s(v, "into"), Where.of(field(v, "where")))
            "fresh" -> Fresh(BPayload.of(field(v, "payload")), s(v, "machine"), sOrNull(v, "before"))
            "copyTo" -> CopyTo(strs(v, "ids"), s(v, "machine"))
            "delete" -> Delete(arrOf(v, "picks").map(Pick::of))
            "restore" -> Restore(s(v, "ws"))
            "rename" -> Rename(s(v, "ws"), s(v, "name"))
            "describe" -> Describe(s(v, "ws"), s(v, "description"))
            "renameTemplate" -> RenameTemplate(s(v, "ws"), s(v, "template"), s(v, "name"))
            "removeApp" -> RemoveApp(s(v, "ws"), s(v, "template"), s(v, "app"))
            "removeExtra" -> RemoveExtra(s(v, "ws"), s(v, "template"), s(v, "app"), s(v, "id"))
            "setMode" -> SetMode(s(v, "ws"), s(v, "mode"))
            "setBackground" -> SetBackground(s(v, "ws"), sOrNull(v, "texture"))
            "toggleProject" -> ToggleProject(s(v, "ws"), field(v, "project") ?: JsonNull)
            "setKey" -> SetKey(s(v, "ws"), s(v, "slot"), opt(v, "key"))
            "setGroupKey" -> SetGroupKey(s(v, "ws"), s(v, "group"), opt(v, "key"))
            "moveMember" -> MoveMember(s(v, "ws"), s(v, "member"), sOrNull(v, "to"))
            "newGroup" -> NewGroup(s(v, "ws"))
            "removeGroup" -> RemoveGroup(s(v, "ws"), s(v, "group"))
            "renameKey" -> RenameKey(s(v, "id"), s(v, "name"))
            "undo" -> Undo
            else -> throw IllegalArgumentException("no op $k")
        }
    }
}

/** An edit that couldn't be made: why. */
class BoardError(message: String) : Exception(message)

private fun fail(why: String): Nothing = throw BoardError(why)

/** What a drag brings to where it lands. */
private class Got(
    var templates: List<BTemplate>? = null,
    var background: JsonElement? = null,
    var projects: List<JsonElement> = emptyList(),
    var secrets: JsonElement? = null,
    var description: String? = null,
    var entry: BEntry? = null,
)

/** wad-core's spec::slugify: lowercase, runs of anything else one `-`, 63 at most. */
fun slugify(text: String): String {
    val b = StringBuilder()
    var run = false
    for (c in text.lowercase()) {
        if (c in 'a'..'z' || c in '0'..'9') {
            b.append(c)
            run = false
        } else if (!run) {
            b.append('-')
            run = true
        }
    }
    return b.toString().trim('-').take(63)
}

/** wad-board's copy_name: the original's name with " - n", n counting the copies. */
fun copyName(name: String, taken: List<String>): String {
    val base = Regex("^(.*) - (\\d+)$").find(name)?.groupValues?.get(1) ?: name
    if (base.isBlank()) return name
    val prefix = "$base - "
    val n = taken.mapNotNull { t -> t.removePrefix(prefix).takeIf { t.startsWith(prefix) && it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull() }
        .maxOrNull() ?: 0
    return "$base - ${n + 1}"
}

/** `base`, or base-2, base-3… if that's taken. */
private fun free(base: String, taken: Collection<String>): String {
    val b = base.ifEmpty { "x" }
    if (b !in taken) return b
    return generateSequence(2) { it + 1 }.map { "$b-$it" }.first { it !in taken }
}

/** The first group id g1, g2… not taken. */
private fun freeGroup(taken: Collection<String>) = generateSequence(1) { it + 1 }.map { "g$it" }.first { it !in taken }

/** An app's extras, with these added (once each, by id). */
private fun addTo(t: BTemplate, app: String, extras: List<BExtra>) {
    val e = t.apps.firstOrNull { it.app == app } ?: BEntry(app).also { t.apps += it }
    for (x in extras) if (e.extras.none { it.id == x.id }) e.extras += x
}

/** The board: the account's machines and version 4 wadspaces (deleted ones too), in board order. */
class Board(
    var machines: List<BMachine> = emptyList(),
    val wadspaces: MutableList<BWadspace> = mutableListOf(),
    /** What a delete stamps (the caller's clock). */
    var now: String = "",
    /** Ids other documents in the collection have (version 3's): a new wadspace never takes one. */
    var reserved: List<String> = emptyList(),
) {
    private val past = mutableListOf<List<BWadspace>>()

    val canUndo get() = past.isNotEmpty()

    /** The wadspaces as read again; Undo keeps its steps when `keep`. */
    fun load(ws: List<BWadspace>, keep: Boolean) {
        wadspaces.clear()
        wadspaces += ws
        if (!keep) past.clear()
    }

    fun get(id: String): BWadspace? = wadspaces.firstOrNull { it.id == id }
    private fun must(id: String): BWadspace = get(id) ?: fail("no wadspace $id")

    /** A machine's row: its wadspaces that aren't deleted, in board order. */
    fun row(machine: String) = wadspaces.filter { it.machine == machine && !it.deleted }

    /** Recently deleted, newest first. */
    fun deleted() = wadspaces.filter { it.deleted }.sortedByDescending { it.deletedAt }

    private fun snapshot() = wadspaces.map { it.copy() }
    private fun same(a: List<BWadspace>, b: List<BWadspace>) = a.map { it.json() } == b.map { it.json() }

    /** Applies an edit (an edit that can't be made changes nothing): its error, or null. */
    fun apply(op: Op, g: Packages): String? {
        if (op is Op.Undo) {
            val last = past.removeLastOrNull() ?: return "nothing to undo"
            wadspaces.clear()
            wadspaces += last
            return null
        }
        return applyAll(listOf(op), g)
    }

    /** Edits made together, one step to undo; if one can't be made, none is. */
    fun applyAll(ops: List<Op>, g: Packages): String? {
        val before = snapshot()
        for (op in ops) {
            val why = if (op is Op.Undo) "undo goes on its own" else try {
                edit(op, g)
                null
            } catch (e: BoardError) {
                e.message
            }
            if (why != null) {
                wadspaces.clear()
                wadspaces += before
                return why
            }
        }
        if (!same(wadspaces, before)) {
            past += before
            if (past.size > UNDO_STEPS) past.removeAt(0)
        }
        return null
    }

    private fun template(w: BWadspace, id: String) = w.templates.firstOrNull { it.id == id } ?: fail("no such template")

    private fun edit(op: Op, g: Packages) {
        when (op) {
            is Op.Drop -> drop(op.payload, op.into, op.at, g)
            is Op.Fresh -> fresh(op.payload, op.machine, op.before, g)
            is Op.CopyTo -> op.ids.forEach { fresh(BPayload.Wadspace(it), op.machine, null, g) }
            is Op.Delete -> delete(op.picks)
            is Op.Restore -> must(op.ws).deletedAt = null
            is Op.Rename -> must(op.ws).customization["name"] = str(op.name)
            is Op.Describe -> must(op.ws).customization["description"] = str(op.description)
            is Op.RenameTemplate -> template(must(op.ws), op.template).name = op.name
            is Op.RemoveApp -> template(must(op.ws), op.template).apps.removeAll { it.app == op.app }
            is Op.RemoveExtra -> template(must(op.ws), op.template).apps.firstOrNull { it.app == op.app }?.extras?.removeAll { it.id == op.id }
            is Op.SetMode -> {
                if (op.mode != "browser" && op.mode != "focus") fail("WadBrowser has no mode ${op.mode}")
                for (t in must(op.ws).templates) for (e in t.apps) if (e.app == "wadbrowser") e.mode = op.mode.takeIf { it == "focus" }
            }
            is Op.SetBackground -> {
                op.texture?.let { if (it !in V4.TEXTURES) fail("there's no texture $it") }
                val w = must(op.ws)
                if (op.texture != null) w.customization["wallpaper"] = obj("type" to str("texture"), "value" to str(op.texture))
                else w.customization.remove("wallpaper")
            }
            is Op.ToggleProject -> {
                val w = must(op.ws)
                val list = (w.customization.getOrPut("projects") { JsonArray(emptyList()) } as? JsonArray)?.toMutableList()
                    ?: fail("projects isn't a list")
                val i = list.indexOfFirst { s(it, "id") == s(op.project, "id") }
                if (i >= 0) list.removeAt(i) else list += op.project
                w.customization["projects"] = JsonArray(list)
            }
            is Op.SetKey -> {
                val w = must(op.ws)
                val sec = w.secrets()
                val by = LinkedHashMap((sec.getOrPut("bySlot") { JsonObject(emptyMap()) } as? JsonObject) ?: fail("bySlot isn't an object"))
                if (op.key != null) by[op.slot] = op.key else by.remove(op.slot)
                sec["bySlot"] = JsonObject(by)
                w.putSecrets(sec)
            }
            is Op.SetGroupKey -> setGroups(op.ws) { gs ->
                for (i in gs.indices) if (s(gs[i], "id") == op.group) {
                    val o = gs[i] as? JsonObject ?: continue
                    gs[i] = if (op.key != null) o.with("key", op.key) else o.without("key")
                }
            }
            is Op.MoveMember -> {
                val ids = groupsOf(op.ws).map { s(it, "id") }
                val fresh = freeGroup(ids)
                setGroups(op.ws) { gs ->
                    val was = gs.map { (field(it, "members") as? JsonArray)?.isEmpty() == true }
                    for (i in gs.indices) {
                        val m = field(gs[i], "members") as? JsonArray ?: continue
                        gs[i] = (gs[i] as JsonObject).with("members", JsonArray(m.filter { (it as? JsonPrimitive)?.content != op.member }))
                    }
                    val to = op.to?.let { t -> gs.indexOfFirst { s(it, "id") == t } }?.takeIf { it >= 0 }
                    if (to != null) {
                        val m = (field(gs[to], "members") as? JsonArray) ?: JsonArray(emptyList())
                        gs[to] = (gs[to] as JsonObject).with("members", JsonArray(m + str(op.member)))
                    } else {
                        gs += obj("id" to str(fresh), "members" to strList(listOf(op.member)))
                    }
                    // A group its last app left goes; one made empty with New group stays.
                    val kept = gs.filterIndexed { i, x -> (field(x, "members") as? JsonArray)?.isEmpty() != true || was.getOrElse(i) { true } }
                    gs.clear()
                    gs += kept
                }
            }
            is Op.NewGroup -> {
                val id = freeGroup(groupsOf(op.ws).map { s(it, "id") })
                setGroups(op.ws) { it += obj("id" to str(id), "members" to JsonArray(emptyList())) }
            }
            is Op.RemoveGroup -> setGroups(op.ws) { gs -> gs.removeAll { s(it, "id") == op.group } }
            is Op.RenameKey -> for (w in wadspaces) {
                val sec = w.secrets()
                (sec["bySlot"] as? JsonObject)?.let { by ->
                    sec["bySlot"] = JsonObject(by.mapValues { (_, k) -> if (s(k, "id") == op.id) (k as JsonObject).with("name", str(op.name)) else k })
                }
                (sec["groups"] as? JsonArray)?.let { gs ->
                    sec["groups"] = JsonArray(gs.map { gr ->
                        val k = field(gr, "key")
                        if (k != null && s(k, "id") == op.id) (gr as JsonObject).with("key", (k as JsonObject).with("name", str(op.name))) else gr
                    })
                }
                w.putSecrets(sec)
            }
            Op.Undo -> error("undo is handled by apply")
        }
    }

    /** What `p` brings into wadspace `into`: moved out of its own wadspace when that's `into`, else copied. */
    private fun carry(p: BPayload, into: String): Got {
        fun src(ws: String) = must(ws).copy()
        fun moving(ws: String) = ws == into
        fun whole(w: BWadspace) = Got(
            templates = w.templates.map { it.copy() },
            background = w.customization["wallpaper"],
            projects = (w.customization["projects"] as? JsonArray)?.toList() ?: emptyList(),
            secrets = w.customization["secrets"],
            description = (w.customization["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
        return when (p) {
            is BPayload.Wadspace -> whole(src(p.ws))
            is BPayload.Templates -> {
                val w = src(p.ws)
                val tpls = w.templates.filter { it.id in p.ids }
                if (moving(p.ws)) must(p.ws).templates.removeAll { it.id in p.ids }
                Got(templates = tpls)
            }
            is BPayload.Bucket -> {
                val w = src(p.ws)
                when {
                    p.which == Which.Templates -> Got(templates = w.templates)
                    p.pile -> whole(w)
                    else -> whole(w).apply { templates = null; description = null }
                }
            }
            is BPayload.Project -> {
                val list = (src(p.ws).customization["projects"] as? JsonArray)?.toList() ?: emptyList()
                Got(projects = list.filter { s(it, "id") == p.id })
            }
            is BPayload.Background -> Got(background = src(p.ws).customization["wallpaper"])
            is BPayload.App, is BPayload.Extra -> {
                val (ws, tid, app) = when (p) {
                    is BPayload.App -> Triple(p.ws, p.template, p.app)
                    is BPayload.Extra -> Triple(p.ws, p.template, p.app)
                    else -> error("an app or an extra")
                }
                val w = src(ws)
                val t = w.templates.firstOrNull { it.id == tid } ?: fail("no such template")
                val e = t.apps.firstOrNull { it.app == app } ?: fail("no such app")
                val extras = if (p is BPayload.Extra) e.extras.filter { it.id == p.id } else e.extras.toList()
                if (moving(ws)) {
                    val mt = must(ws).templates.first { it.id == tid }
                    if (p is BPayload.Extra) mt.apps.firstOrNull { it.app == app }?.extras?.removeAll { it.id == p.id }
                    else mt.apps.removeAll { it.app == app }
                }
                Got(entry = BEntry(e.app, e.mode, extras.toMutableList()))
            }
            is BPayload.CatalogApp -> Got(entry = BEntry(p.app))
            is BPayload.CatalogExtra -> Got(entry = BEntry(p.app, null, mutableListOf(p.extra)))
            is BPayload.Texture -> Got(background = obj("type" to str("texture"), "value" to str(p.value)))
            is BPayload.CatalogProject -> Got(projects = listOf(p.project))
        }
    }

    /** Templates in, their ids made their own in `w`. */
    private fun insertTemplates(w: BWadspace, at: Int, tpls: List<BTemplate>) {
        val taken = w.templates.map { it.id }.toMutableList()
        val made = tpls.map { t ->
            t.copy().also {
                it.id = free(it.id, taken)
                taken += it.id
            }
        }
        w.templates.addAll(minOf(at, w.templates.size), made)
    }

    /** A new template in `w` for an app, named as the app. */
    private fun newTemplate(w: BWadspace, app: String, g: Packages): Int {
        val name = g.appName(app)
        val id = free(slugify(name), w.templates.map { it.id })
        w.templates += BTemplate(id, name)
        return w.templates.size - 1
    }

    private fun take(w: BWadspace, got: Got, at: Int?, onto: Where, g: Packages) {
        got.description?.let { w.customization["description"] = str(it) }
        got.background?.let { w.customization["wallpaper"] = it }
        if (got.projects.isNotEmpty()) {
            val now = w.customization.getOrPut("projects") { JsonArray(emptyList()) }
            if (now is JsonArray) {
                val list = now.toMutableList()
                for (p in got.projects) if (list.none { s(it, "id") == s(p, "id") }) list += p
                w.customization["projects"] = JsonArray(list)
            }
        }
        got.secrets?.let { w.customization["secrets"] = it }
        got.templates?.let { insertTemplates(w, at ?: w.templates.size, it) }
        val e = got.entry ?: return
        var i: Int? = if (onto.newTemplate) null else onto.template?.let { t -> w.templates.indexOfFirst { it.id == t }.takeIf { it >= 0 } }
        // A click in the sidebar: an extension goes where its app is.
        if (i == null && !onto.newTemplate && e.extras.isNotEmpty()) {
            i = w.templates.indexOfFirst { t -> t.apps.any { it.app == e.app } }.takeIf { it >= 0 }
        }
        val at2 = i ?: newTemplate(w, e.app, g)
        addTo(w.templates[at2], e.app, e.extras)
        if (e.mode != null) w.templates[at2].apps.firstOrNull { it.app == e.app && it.mode == null }?.mode = e.mode
    }

    private fun drop(p: BPayload, into: String, onto: Where, g: Packages) {
        val w0 = must(into)
        // Templates moving within their own bucket: where they land once they're out.
        var at = onto.at
        if (p is BPayload.Templates && at != null && p.ws == into) {
            at = w0.templates.take(at).count { it.id !in p.ids }
        }
        val got = carry(p, into)
        take(must(into), got, at, onto, g)
    }

    /** A new wadspace on `machine` from what's dragged; its id. */
    private fun fresh(p: BPayload, machine: String, before: String?, g: Packages): String {
        val from = p.source?.let { must(it).copy() }
        val whole = p is BPayload.Wadspace || (p is BPayload.Bucket && p.pile)
        val name = when {
            from == null -> "New wadspace"
            whole && from.machine != machine -> from.name
            else -> copyName(from.name, row(machine).map { it.name })
        }
        val id = free(slugify(name), wadspaces.map { it.id } + reserved)
        val at = before?.let { b -> wadspaces.indexOfFirst { it.id == b }.takeIf { it >= 0 } }
            ?: (wadspaces.indexOfLast { it.machine == machine }.takeIf { it >= 0 }?.plus(1) ?: wadspaces.size)
        wadspaces.add(at, BWadspace(id = id, owner = from?.owner ?: "", machine = machine, customization = linkedMapOf("name" to str(name))))
        val got = carry(p, id)
        take(must(id), got, null, Where(newTemplate = true), g)
        return id
    }

    private fun delete(picks: List<Pick>) {
        for (pick in picks) when (pick) {
            is Pick.Wadspace -> must(pick.ws).deletedAt = now
            is Pick.Template -> must(pick.ws).templates.removeAll { it.id == pick.id }
            is Pick.Project -> {
                val w = must(pick.ws)
                (w.customization["projects"] as? JsonArray)?.let { l -> w.customization["projects"] = JsonArray(l.filter { s(it, "id") != pick.id }) }
            }
            is Pick.Background -> must(pick.ws).customization.remove("wallpaper")
        }
    }

    // ------------------------------------------------------------ keys

    /** WadBrowser's apps in their groups, every one in exactly one (none without WadBrowser). */
    fun groupsOf(ws: String): List<JsonObject> {
        val w = get(ws) ?: return emptyList()
        val browser = V4.appsOf(w.json()).firstOrNull { s(it, "app") == "wadbrowser" } ?: return emptyList()
        val members = listOf("browser") + arrOf(browser, "extras").map { "web:${s(it, "id")}" }
        val stored = field(w.customization["secrets"], "groups") as? JsonArray
        val groups = stored?.map { gr ->
            val kept = ((field(gr, "members") as? JsonArray) ?: JsonArray(emptyList()))
                .filter { m -> (m as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { it in members } == true }
            ((gr as? JsonObject) ?: JsonObject(emptyMap())).with("members", JsonArray(kept))
        }?.toMutableList() ?: mutableListOf()
        val loose = members.filter { m -> groups.none { gr -> (field(gr, "members") as? JsonArray)?.any { (it as? JsonPrimitive)?.content == m } == true } }
        if (loose.isNotEmpty()) {
            if (groups.isNotEmpty()) {
                val first = groups[0]
                groups[0] = first.with("members", JsonArray((field(first, "members") as? JsonArray ?: JsonArray(emptyList())) + strList(loose)))
            } else {
                groups += obj("id" to str("g1"), "members" to strList(loose))
            }
        }
        return groups
    }

    private fun setGroups(ws: String, f: (MutableList<JsonElement>) -> Unit) {
        val gs: MutableList<JsonElement> = groupsOf(ws).toMutableList()
        f(gs)
        val w = must(ws)
        val sec = w.secrets()
        sec["groups"] = JsonArray(gs)
        w.putSecrets(sec)
    }

    /** A new key `{id, name, kind}`: named `name` unless that's taken (then name-2…), its id from it, unique among the board's keys. */
    fun newKey(name: String, kind: String): JsonObject {
        val names = mutableListOf<String>()
        val ids = mutableListOf<String>()
        for (w in wadspaces) {
            val sec = w.customization["secrets"]
            val refs = ((field(sec, "bySlot") as? JsonObject)?.values ?: emptyList()) +
                ((field(sec, "groups") as? JsonArray)?.mapNotNull { field(it, "key") } ?: emptyList())
            for (k in refs) {
                names += s(k, "name")
                ids += s(k, "id")
            }
        }
        val n = free(name, names)
        return obj("id" to str(free(slugify(n), ids)), "name" to str(n), "kind" to str(kind))
    }

    // ------------------------------------------------------------ saving

    /** The documents to save (new, changed, deleted) with `owner` and times set, each machine's row ordered 1, 2, 3… */
    fun plan(saved: List<BWadspace>, owner: String, now: String): List<JsonObject> {
        val order = mutableMapOf<String, Long>()
        val out = mutableListOf<JsonObject>()
        for (w0 in wadspaces) {
            val w = w0.copy()
            if (!w.deleted) {
                val n = (order[w.machine] ?: 0) + 1
                order[w.machine] = n
                w.order = n
            }
            if (w.owner.isEmpty()) w.owner = owner
            val was = saved.firstOrNull { it.id == w.id }
            val same = was != null && was.copy().also { it.updatedAt = w.updatedAt; it.createdAt = w.createdAt }.json() == w.json()
            if (same) continue
            w.createdAt = was?.createdAt ?: now
            w.updatedAt = now
            out += w.json()
        }
        return out
    }

    fun json() = obj(
        "machines" to JsonArray(machines.map { obj("id" to str(it.id), "name" to str(it.name)) }),
        "wadspaces" to JsonArray(wadspaces.map { it.json() }),
        "now" to str(now),
        "reserved" to strList(reserved),
    )

    companion object {
        fun of(v: JsonElement?) = Board(
            machines = arrOf(v, "machines").map { BMachine(s(it, "id"), s(it, "name")) },
            wadspaces = arrOf(v, "wadspaces").map(BWadspace::of).toMutableList(),
            now = s(v, "now"),
            reserved = strs(v, "reserved"),
        )

        /** Edits applied to a board, in turn: `{board, ops, saved?, owner?, now?}` → `{wadspaces, plan, errors}`. */
        fun run(input: JsonElement, g: Packages): JsonObject {
            val b = of(field(input, "board"))
            val ops = arrOf(input, "ops").map(Op::of)
            val saved = arrOf(input, "saved").map(BWadspace::of)
            val errors = ops.map { op -> b.apply(op, g)?.let(::str) ?: JsonNull }
            val now = sOrNull(input, "now") ?: "2026-10-21T00:00:00Z"
            val owner = sOrNull(input, "owner") ?: "u1"
            return obj(
                "wadspaces" to JsonArray(b.wadspaces.map { it.json() }),
                "plan" to JsonArray(b.plan(saved, owner, now)),
                "errors" to JsonArray(errors),
            )
        }
    }
}

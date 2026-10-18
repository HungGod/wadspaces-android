package io.wadspaces.core.board

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The board and the documents behind it: wad-board's sync.rs, as JSON (the
 * stacks and modules in the shapes wad-board serializes them, so the
 * goldens compare as they are).
 *
 * - [toBoard]: the documents as stacks, one per wadspace in board order.
 * - [plan]: the writes that make the documents what the stacks show.
 * - [planNew]: the writes that add one new stack and touch nothing else
 *   (what the phone's builder saves).
 */
object Sync {
    const val BLANK_WALLPAPER = "linear-gradient(160deg, #17112a 0%, #0e0919 100%)"

    fun blankWallpaper(): JsonObject = obj("type" to str("gradient"), "value" to str(BLANK_WALLPAPER))

    // -------------------------------------------------------- documents → board

    private fun templateModule(t: JsonElement, stackId: String) = obj(
        "layer" to str("template"),
        "id" to str("${s(t, "id")}@$stackId"),
        "ref" to str(s(t, "id")),
        "name" to str(s(t, "name")),
        "description" to str(s(t, "description")),
        "apps" to strList(strs(t, "apps")),
    )

    private fun webappsOf(v: JsonElement?): JsonArray = JsonArray(
        arrOf(v, "webapps").map { obj("id" to str(s(it, "id")), "name" to str(s(it, "name")), "url" to str(s(it, "url"))) },
    )

    /** An Extensions document's setups as submodules. */
    private fun appSubs(e: JsonElement, origin: String, base: String): JsonArray {
        val subs = mutableListOf<JsonElement>()
        val webapps = webappsOf(e)
        val mode = s(e, "browserMode").ifEmpty { "browser" }
        if (webapps.isNotEmpty() || mode == "focus") {
            subs += obj(
                "key" to str("wadbrowser"), "id" to str("$base:wadbrowser"), "origin" to str(origin),
                "mode" to str(mode), "webapps" to webapps,
            )
        }
        val extensions = strs(e, "extensions")
        if (extensions.isNotEmpty()) {
            subs += obj("key" to str("vscode"), "id" to str("$base:vscode"), "origin" to str(origin), "extensions" to strList(extensions))
        }
        val tools = strs(e, "tools")
        if (tools.isNotEmpty()) {
            subs += obj("key" to str("tools"), "id" to str("$base:tools"), "origin" to str(origin), "tools" to strList(tools))
        }
        return JsonArray(subs)
    }

    private fun extensionsModule(e: JsonElement, stackId: String, doc: String?) = obj(
        "layer" to str("extensions"),
        "id" to str("${doc ?: "draft-extensions"}@$stackId"),
        "ref" to doc?.let(::str),
        "name" to str(s(e, "name")),
        "subs" to appSubs(e, s(e, "name"), "${doc ?: "draft"}@$stackId"),
        "autostart" to strList(strs(e, "autostart")),
    )

    /** Key groups as wad-board keeps them (no empty id, no null key); null when they don't read. */
    private fun keyGroups(v: JsonElement?): JsonArray? {
        val list = v as? JsonArray ?: return null
        val out = mutableListOf<JsonElement>()
        for (g in list) {
            val o = g as? JsonObject ?: return null
            val members = o["members"] as? JsonArray ?: return null
            if (members.any { (it as? JsonPrimitive)?.isString != true }) return null
            // serde reads a missing id as "", any other non-text (null too) as an error.
            val id = o["id"]
            if (id != null && (id as? JsonPrimitive)?.isString != true) return null
            out += obj(
                "id" to s(o, "id").takeIf { it.isNotEmpty() }?.let(::str),
                "members" to members,
                "key" to o["key"]?.takeIf { it != JsonNull },
            )
        }
        return JsonArray(out)
    }

    private fun customizationModule(c: JsonElement, stackId: String): JsonObject {
        val id = "customization@$stackId"
        val name = s(c, "name")
        val secrets = field(c, "secrets")
        val groups = field(secrets, "groups")?.takeIf(::truthy)?.let(::keyGroups)
        val titleBars = (field(c, "titleBars") as? JsonPrimitive)?.booleanOrNull == true
        return obj(
            "layer" to str("customization"),
            "id" to str(id),
            "subs" to JsonArray(listOf(
                obj(
                    "key" to str("look"), "id" to str("$id:look"), "origin" to str(name), "name" to str(name),
                    "description" to str(s(c, "description")), "wallpaper" to (field(c, "wallpaper") ?: JsonNull),
                    "titleBars" to if (titleBars) JsonPrimitive(true) else null,
                ),
                obj("key" to str("folders"), "id" to str("$id:folders"), "origin" to str(name), "mounts" to ((field(c, "projects") as? JsonArray) ?: JsonArray(emptyList()))),
                obj(
                    "key" to str("secrets"), "id" to str("$id:secrets"), "origin" to str(name),
                    "bySlot" to ((field(secrets, "bySlot") as? JsonObject) ?: JsonObject(emptyMap())),
                    "groups" to groups,
                ),
            )),
        )
    }

    /** The template documents a template builds on, bottom first (itself last). */
    private fun chain(templates: List<JsonElement>, id: String?): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        fun find(id: String?) = if (id == null) null else templates.firstOrNull { sOrNull(it, "id") == id }
        var t = find(id)
        while (t != null) {
            val x: JsonElement = t
            if (out.size >= 9 || out.any { it === x }) break
            out.add(0, x)
            t = find(sOrNull(x, "parent"))
        }
        return out
    }

    private fun order(w: JsonElement): Double = (field(w, "order") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0

    /** The documents as the board shows them: a stack per wadspace (not deleted ones), in order. */
    fun toBoard(docs: JsonElement): JsonArray {
        val list = arrOf(docs, "wadspaces").filter(::alive).sortedWith(compareBy<JsonElement> { order(it) }.thenBy { s(it, "createdAt") })
        return JsonArray(list.map { w ->
            val id = s(w, "id")
            val tpl = s(w, "template").ifEmpty { null }
            val templates = chain(arrOf(docs, "templates"), tpl).map { templateModule(it, id) }.toMutableList<JsonElement>()
            field(w, "draftTemplate")?.takeIf(::truthy)?.let { d ->
                templates += obj(
                    "layer" to str("template"), "id" to str("draft-template@$id"), "name" to str(s(d, "name")),
                    "description" to str(s(d, "description")), "apps" to JsonArray(emptyList()),
                )
            }
            val extId = s(w, "extensions").ifEmpty { null }
            val e = extId?.let { eid -> arrOf(docs, "extensions").firstOrNull { s(it, "id") == eid } }
            val extensions = when {
                e != null -> extensionsModule(e, id, s(e, "id"))
                truthy(field(w, "draftExtensions")) -> extensionsModule(field(w, "draftExtensions")!!, id, null)
                else -> null
            }
            val customization = field(w, "customization")?.takeIf(::truthy)?.let { customizationModule(it, id) }
            obj("id" to str(id), "templates" to JsonArray(templates), "extensions" to extensions, "customization" to customization)
        })
    }

    // -------------------------------------------------------- board → documents

    private fun subOf(m: JsonElement?, key: String): JsonElement? = arrOf(m, "subs").firstOrNull { s(it, "key") == key }

    /** An Extensions module's setups as a document has them. */
    fun setupOfModule(m: JsonElement): JsonObject {
        val browser = subOf(m, "wadbrowser")
        return obj(
            "browserMode" to str(browser?.let { s(it, "mode") } ?: "browser"),
            "webapps" to (browser?.let(::webappsOf) ?: JsonArray(emptyList())),
            "extensions" to strList(strs(subOf(m, "vscode"), "extensions")),
            "tools" to strList(strs(subOf(m, "tools"), "tools")),
            "autostart" to strList(strs(m, "autostart")),
        )
    }

    /** A customization module as the wadspace keeps it. */
    fun customizationOf(m: JsonElement): JsonObject {
        val look = subOf(m, "look")
        val keys = subOf(m, "secrets")
        val groups = keys?.let { field(it, "groups") }?.takeIf { it != JsonNull }?.let(::keyGroups)
        val secrets = obj(
            "bySlot" to ((field(keys, "bySlot") as? JsonObject) ?: JsonObject(emptyMap())),
            "groups" to groups,
        )
        val titleBars = (field(look, "titleBars") as? JsonPrimitive)?.booleanOrNull == true
        return obj(
            "name" to str(look?.let { s(it, "name") } ?: ""),
            "description" to str(look?.let { s(it, "description") } ?: ""),
            "wallpaper" to (if (look != null) (field(look, "wallpaper") ?: JsonNull) else blankWallpaper()),
            "projects" to ((field(subOf(m, "folders"), "mounts") as? JsonArray) ?: JsonArray(emptyList())),
            "secrets" to secrets,
            "titleBars" to if (titleBars) JsonPrimitive(true) else null,
        )
    }

    private fun sameSet(a: List<String>, b: List<String>) = a.size == b.size && a.all { it in b }

    /** The setups that make an Extensions layer what it is (one missing reads as null). */
    private fun setupKey(e: JsonElement?): JsonArray =
        JsonArray(listOf("browserMode", "webapps", "extensions", "tools", "autostart").map { field(e, it) ?: JsonNull })

    /** The web app's slug: lowercase, runs of anything else as one dash, no end dashes, at most 40, else "item". */
    fun slug(name: String): String {
        val b = StringBuilder()
        var dash = false
        for (c in name.lowercase()) {
            if (c in 'a'..'z' || c in '0'..'9') {
                b.append(c)
                dash = false
            } else if (!dash) {
                b.append('-')
                dash = true
            }
        }
        var out = b.toString()
        out = out.removePrefix("-").removeSuffix("-")
        return out.take(40).ifEmpty { "item" }
    }

    /** An id from a name, free among `taken` (deleted ones too): the web app's freeId. */
    fun freeId(name: String, taken: Collection<String>): String {
        val base = slug(name)
        var id = base
        var n = 2
        while (id in taken) {
            id = "$base-$n"
            n++
        }
        return id
    }

    /** Documents by id, in the order they came (a JS Map's). */
    private class Ordered(docs: List<JsonElement>) {
        val list = mutableListOf<Pair<String, JsonObject>>()

        init {
            docs.forEach { set(it as JsonObject) }
        }

        fun get(id: String) = list.firstOrNull { it.first == id }?.second

        fun set(d: JsonObject) {
            val id = s(d, "id")
            val i = list.indexOfFirst { it.first == id }
            if (i >= 0) list[i] = id to d else list += id to d
        }

        fun values() = list.map { it.second }
    }

    private fun put(list: MutableList<JsonElement>, d: JsonObject) {
        val id = s(d, "id")
        list.removeAll { s(it, "id") == id }
        list += d
    }

    private fun docOf(m: JsonElement?) = sOrNull(m, "ref")

    /** What to write so the documents are what `stacks` shows; `owner` and `now` stamp new and changed ones. */
    fun plan(stacks: List<JsonElement>, docs: JsonElement, owner: String, now: String): JsonObject {
        val templates = Ordered(arrOf(docs, "templates"))
        val extensions = Ordered(arrOf(docs, "extensions"))
        val outTemplates = mutableListOf<JsonElement>()
        val outExtensions = mutableListOf<JsonElement>()
        val outWadspaces = mutableListOf<JsonElement>()
        val refs = linkedMapOf<String, JsonElement>()
        // How many stacks (but `stackId`) have a module that's this document.
        fun othersWith(doc: String, stackId: String, template: Boolean) = stacks.count { st ->
            s(st, "id") != stackId && if (template) {
                arrOf(st, "templates").any { docOf(it) == doc }
            } else {
                docOf(field(st, "extensions")) == doc
            }
        }
        fun nameOr(n: String) = n.ifEmpty { "layer" }
        val nowP = str(now)

        for ((i, st) in stacks.withIndex()) {
            val stId = s(st, "id")
            // The template chain, bottom up; a blank one on top waits as a draft.
            var parent: String? = null
            val mods = arrOf(st, "templates")
            val draftTemplate = mods.lastOrNull()?.takeIf { strs(it, "apps").isEmpty() }?.let {
                obj("name" to str(s(it, "name")), "description" to str(s(it, "description")))
            }
            for (m in mods.filter { strs(it, "apps").isNotEmpty() }) {
                val mName = s(m, "name")
                val mDesc = s(m, "description")
                val mApps = strs(m, "apps")
                var want = obj("name" to str(mName), "description" to str(mDesc), "apps" to strList(mApps))
                if (parent != null) want = want.with("parent", str(parent))
                val under = parent
                fun isIt(t: JsonElement) = alive(t) && sameSet(strs(t, "apps"), mApps) && sOrNull(t, "parent")?.ifEmpty { null } == under
                val cur = docOf(m)?.let { templates.get(it) }
                val twin = templates.values().firstOrNull(::isIt)
                val id: String
                if (cur != null && isIt(cur)) {
                    id = s(cur, "id")
                    if (s(cur, "name") != mName || s(cur, "description") != mDesc || strs(cur, "apps") != mApps) {
                        val d = merge(cur, want).with("updatedAt", nowP)
                        templates.set(d)
                        put(outTemplates, d)
                    }
                } else if (twin != null) {
                    id = s(twin, "id")
                    if (s(twin, "name") != mName || s(twin, "description") != mDesc) {
                        val d = twin.with("name", str(mName)).with("description", str(mDesc)).with("updatedAt", nowP)
                        templates.set(d)
                        put(outTemplates, d)
                    }
                } else if (cur != null && alive(cur) && othersWith(s(cur, "id"), stId, true) == 0) {
                    id = s(cur, "id")
                    val d = merge(cur.without("parent"), want).with("updatedAt", nowP)
                    templates.set(d)
                    put(outTemplates, d)
                } else {
                    id = freeId(nameOr(mName), templates.values().map { s(it, "id") })
                    val d = merge(obj("id" to str(id), "owner" to str(owner)), want).with("createdAt", nowP).with("updatedAt", nowP)
                    templates.set(d)
                    put(outTemplates, d)
                }
                refs[s(m, "id")] = str(id)
                parent = id
            }

            // Its Extensions, made on the top template; with no template yet, kept in the stack.
            var extId: String? = null
            var draftExtensions: JsonObject? = null
            val em = field(st, "extensions")?.takeIf { it != JsonNull }
            if (em != null) {
                val setup = setupOfModule(em)
                val emName = s(em, "name")
                val p = parent
                if (p == null) {
                    draftExtensions = merge(obj("name" to str(emName)), setup)
                } else {
                    fun isIt(e: JsonElement) = alive(e) && s(e, "templateId") == p && setupKey(e) == setupKey(setup)
                    val cur = docOf(em)?.let { extensions.get(it) }
                    val twin = extensions.values().firstOrNull(::isIt)
                    val id: String
                    if (cur != null && isIt(cur)) {
                        id = s(cur, "id")
                        if (s(cur, "name") != emName) {
                            val d = cur.with("name", str(emName)).with("updatedAt", nowP)
                            extensions.set(d)
                            put(outExtensions, d)
                        }
                    } else if (twin != null) {
                        id = s(twin, "id")
                        if (s(twin, "name") != emName) {
                            val d = twin.with("name", str(emName)).with("updatedAt", nowP)
                            extensions.set(d)
                            put(outExtensions, d)
                        }
                    } else if (cur != null && alive(cur) && othersWith(s(cur, "id"), stId, false) == 0) {
                        id = s(cur, "id")
                        val d = merge(merge(cur, obj("name" to str(emName), "templateId" to str(p))), setup).with("updatedAt", nowP)
                        extensions.set(d)
                        put(outExtensions, d)
                    } else {
                        id = freeId(nameOr(emName), extensions.values().map { s(it, "id") })
                        val base = obj("id" to str(id), "owner" to str(owner), "name" to str(emName), "description" to str(""), "templateId" to str(p))
                        val d = merge(base, setup).with("createdAt", nowP).with("updatedAt", nowP)
                        extensions.set(d)
                        put(outExtensions, d)
                    }
                    refs[s(em, "id")] = str(id)
                    extId = id
                }
            }

            // The wadspace: the stack itself.
            val was = arrOf(docs, "wadspaces").firstOrNull { s(it, "id") == stId }
            val next = linkedMapOf<String, JsonElement>()
            next["version"] = JsonPrimitive(3)
            next["id"] = str(stId)
            next["owner"] = field(was, "owner") ?: str(owner)
            field(was, "sharedWith")?.takeIf(::truthy)?.let { next["sharedWith"] = it }
            next["order"] = JsonPrimitive(i + 1)
            parent?.let { next["template"] = str(it) }
            draftTemplate?.let { next["draftTemplate"] = it }
            extId?.let { next["extensions"] = str(it) }
            draftExtensions?.let { next["draftExtensions"] = it }
            field(st, "customization")?.takeIf { it != JsonNull }?.let { next["customization"] = customizationOf(it) }
            next["createdAt"] = field(was, "createdAt") ?: nowP
            next["updatedAt"] = field(was, "updatedAt") ?: nowP
            val a = JsonObject(next.filterKeys { it != "updatedAt" })
            val changed = if (was == null) true else {
                val b = JsonObject((was as JsonObject).filterKeys { it != "updatedAt" && it != "deletedAt" })
                !alive(was) || a != b
            }
            if (changed) {
                next["updatedAt"] = nowP
                outWadspaces += JsonObject(next)
            }
        }

        val onBoard = stacks.map { s(it, "id") }
        val deleted = arrOf(docs, "wadspaces").filter { alive(it) && s(it, "id") !in onBoard }.map { s(it, "id") }
        val wadspaces = Ordered(arrOf(docs, "wadspaces"))
        outWadspaces.forEach { wadspaces.set(it as JsonObject) }
        for (id in deleted) wadspaces.get(id)?.let { wadspaces.set(it.with("deletedAt", nowP)) }
        return obj(
            "templates" to JsonArray(outTemplates),
            "extensions" to JsonArray(outExtensions),
            "wadspaces" to JsonArray(outWadspaces),
            "deleted" to strList(deleted),
            "refs" to JsonObject(refs),
            "docs" to obj(
                "templates" to JsonArray(templates.values()),
                "extensions" to JsonArray(extensions.values()),
                "wadspaces" to JsonArray(wadspaces.values()),
            ),
        )
    }

    /**
     * The writes that add one new stack (not on the board yet), and nothing
     * else: [plan] over the board with it last, kept to its own writes. A new
     * module with a live twin first takes that document as it is (name, apps'
     * order and all), so saving changes nothing of it; the stack goes after
     * the last one.
     */
    fun planNew(stack: JsonElement, docs: JsonElement, owner: String, now: String): JsonObject {
        var st = stack as JsonObject
        // Twins, bottom up: a module that's a document (or a twin's) is its
        // parent's for the one above; past a new one, nothing has a twin.
        var parent: String? = null
        var known = true
        val mods = arrOf(st, "templates").map { m ->
            if (!known || strs(m, "apps").isEmpty()) return@map m
            var mm = m as JsonObject
            if (docOf(mm) == null) {
                val under = parent
                val t = arrOf(docs, "templates").firstOrNull { t ->
                    alive(t) && sameSet(strs(t, "apps"), strs(mm, "apps")) && sOrNull(t, "parent")?.ifEmpty { null } == under
                }
                if (t != null) {
                    mm = mm.with("ref", str(s(t, "id"))).with("name", str(s(t, "name")))
                        .with("description", str(s(t, "description"))).with("apps", strList(strs(t, "apps")))
                }
            }
            val d = docOf(mm)
            if (d == null) known = false else parent = d
            mm
        }
        st = st.with("templates", JsonArray(mods))
        val em = field(st, "extensions") as? JsonObject
        val tid = parent
        if (known && tid != null && em != null && docOf(em) == null) {
            val want = setupKey(setupOfModule(em))
            arrOf(docs, "extensions").firstOrNull { alive(it) && s(it, "templateId") == tid && setupKey(it) == want }?.let { e ->
                st = st.with("extensions", em.with("ref", str(s(e, "id"))).with("name", str(s(e, "name"))))
            }
        }

        val stId = s(st, "id")
        val stacks = toBoard(docs).filter { s(it, "id") != stId } + st
        val all = plan(stacks, docs, owner, now)

        // Only this stack's.
        val mine = arrOf(st, "templates").map { s(it, "id") } + listOfNotNull(field(st, "extensions")?.takeIf { it != JsonNull }?.let { s(it, "id") })
        val refs = (all["refs"] as JsonObject).filterKeys { it in mine }
        val ids = refs.values.mapNotNull { (it as? JsonPrimitive)?.content }
        val order = arrOf(docs, "wadspaces").filter { alive(it) && s(it, "id") != stId }
            .mapNotNull { (field(it, "order") as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull }
            .fold(0.0, ::maxOf).toLong() + 1
        val templates = arrOf(all, "templates").filter { s(it, "id") in ids }
        val extensions = arrOf(all, "extensions").filter { s(it, "id") in ids }
        val wadspaces = arrOf(all, "wadspaces").filter { s(it, "id") == stId }.map { (it as JsonObject).with("order", JsonPrimitive(order)) }
        val dTemplates = arrOf(docs, "templates").toMutableList()
        val dExtensions = arrOf(docs, "extensions").toMutableList()
        val dWadspaces = arrOf(docs, "wadspaces").toMutableList()
        templates.forEach { put(dTemplates, it as JsonObject) }
        extensions.forEach { put(dExtensions, it as JsonObject) }
        wadspaces.forEach { put(dWadspaces, it) }
        return obj(
            "templates" to JsonArray(templates),
            "extensions" to JsonArray(extensions),
            "wadspaces" to JsonArray(wadspaces),
            "deleted" to JsonArray(emptyList()),
            "refs" to JsonObject(refs),
            "docs" to obj(
                "templates" to JsonArray(dTemplates),
                "extensions" to JsonArray(dExtensions),
                "wadspaces" to JsonArray(dWadspaces),
            ),
        )
    }

    /** The stacks with each module's document noted. */
    fun withRefs(stacks: List<JsonElement>, refs: Map<String, String>): JsonArray = JsonArray(stacks.map { st ->
        var o = st as JsonObject
        o = o.with("templates", JsonArray(arrOf(o, "templates").map { t ->
            refs[s(t, "id")]?.let { (t as JsonObject).with("ref", str(it)) } ?: t
        }))
        (field(o, "extensions") as? JsonObject)?.let { e -> refs[s(e, "id")]?.let { o = o.with("extensions", e.with("ref", str(it))) } }
        o
    })
}

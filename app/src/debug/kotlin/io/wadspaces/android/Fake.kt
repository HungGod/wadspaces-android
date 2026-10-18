package io.wadspaces.android

import android.content.Context
import io.wadspaces.core.firebase.Http
import io.wadspaces.core.firebase.HttpRequest
import io.wadspaces.core.firebase.HttpResponse
import io.wadspaces.core.firebase.SessionStore
import io.wadspaces.core.firebase.Values
import io.wadspaces.core.json
import io.wadspaces.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A made-up account and machine (debug builds, `--ez fake true`): the app's
 * screens with something in them, on an emulator with no tailnet. The real
 * code runs; only its HTTP is answered here (Firestore's REST API and a
 * wadd's remote API, from memory).
 */
object Fake {
    fun graph(context: Context): Graph = Graph(context, FakeServer(), object : SessionStore {
        var text: String? = """{"uid": "fake", "login": "me", "refreshToken": "rt", "idToken": "tok", "expiresAt": ${Long.MAX_VALUE}}"""
        override fun load() = text
        override fun save(text: String?) {
            this.text = text
        }
    })
}

private class FakeServer : Http {
    private val docs = linkedMapOf<String, JsonObject>()
    private var polls = 0
    private val streamed = mutableSetOf<String>()
    private val surface = "100.101.102.103"

    init {
        fun put(path: String, v: String) {
            docs[path] = json.parseToJsonElement(v).jsonObject
        }
        val aurora = "radial-gradient(120% 90% at 12% 8%, rgb(198 255 31 / 0.38), transparent 55%), radial-gradient(110% 85% at 88% 92%, rgb(255 61 129 / 0.5), transparent 60%), linear-gradient(160deg, #0a0614, #1b132d)"
        val nebula = "radial-gradient(75% 70% at 72% 30%, rgb(255 61 129 / 0.55), transparent 62%), radial-gradient(90% 90% at 18% 82%, rgb(127 211 255 / 0.38), transparent 62%), linear-gradient(200deg, #140d22, #07040e)"
        put("users/fake/templates/writing", """{"owner": "fake", "name": "Writing", "description": "Obsidian, for notes and long-form writing.", "apps": ["obsidian"], "starter": true, "createdAt": "2026-09-01T00:00:00.000Z", "updatedAt": "2026-09-01T00:00:00.000Z"}""")
        put("users/fake/templates/dev", """{"owner": "fake", "name": "Dev", "description": "WadBrowser and VS Code, for building things.", "apps": ["wadbrowser", "vscode"], "starter": true, "createdAt": "2026-09-01T00:00:00.000Z", "updatedAt": "2026-09-01T00:00:00.000Z"}""")
        put("users/fake/extensions/vanua-academy-dev", """{"owner": "fake", "name": "VanuaAcademy Dev", "description": "", "templateId": "dev", "browserMode": "browser", "webapps": [{"id": "github", "name": "GitHub", "url": "https://github.com"}, {"id": "claude", "name": "Claude", "url": "https://claude.ai"}], "extensions": ["anthropic.claude-code"], "tools": ["firebase"], "autostart": [], "createdAt": "2026-09-19T00:00:00.000Z", "updatedAt": "2026-09-19T00:00:00.000Z"}""")
        put("wadspaces/cosmic-bodybuilding", """{"version": 3, "owner": "fake", "sharedWith": [], "order": 1, "template": "writing", "customization": {"name": "Cosmic Bodybuilding", "description": "Writing Cosmic Bodybuilding.", "wallpaper": {"type": "gradient", "value": "$nebula"}, "projects": [{"id": "writing", "kind": "github", "repo": "HungGod/Writing", "as": "Writing"}], "secrets": {"bySlot": {}}}, "createdAt": "2026-09-22T00:00:00.000Z", "updatedAt": "2026-09-22T00:00:00.000Z"}""")
        put("wadspaces/vanua-academy", """{"version": 3, "owner": "fake", "sharedWith": [], "order": 2, "template": "dev", "extensions": "vanua-academy-dev", "customization": {"name": "VanuaAcademy", "description": "Building Vanua Academy.", "wallpaper": {"type": "gradient", "value": "$aurora"}, "projects": [], "secrets": {"bySlot": {}}}, "createdAt": "2026-09-22T00:00:00.000Z", "updatedAt": "2026-09-22T00:00:00.000Z"}""")
        put("users/fake/github/repos", """{"login": "HungGod", "repos": [{"fullName": "HungGod/Writing", "name": "Writing", "private": true, "url": "", "defaultBranch": "main", "description": "Notes and drafts"}, {"fullName": "HungGod/VanuaAcademy", "name": "VanuaAcademy", "private": false, "url": "", "defaultBranch": "main", "description": "The academy's site"}, {"fullName": "HungGod/WadSpaces", "name": "WadSpaces", "private": false, "url": "", "defaultBranch": "main", "description": ""}]}""")
        val now = Values.rfc3339(System.currentTimeMillis())
        put("users/fake/machines/surface", """{"name": "Surface", "hostname": "wadspaces", "lastSeen": "$now", "tailnet": {"state": "running", "ips": ["$surface", "fd7a:115c:a1e0::a01:2b03"], "dnsName": "wadspaces-surface.tail1234.ts.net", "port": 8087, "listening": true}, "images": [{"layer": "base"}, {"layer": "template:writing"}, {"layer": "template:dev"}, {"layer": "custom:vanua-academy-dev"}]}""")
        put("users/fake/machines/desk", """{"name": "Desk", "hostname": "desk", "lastSeen": "2026-10-15T08:00:00.000Z"}""")
    }

    private fun ok(v: JsonElement) = HttpResponse(200, v.toString())
    private fun rest(path: String, d: JsonObject) = buildJsonObject {
        put("name", "projects/fake/databases/(default)/documents/$path")
        put("fields", Values.toFields(d))
    }

    override suspend fun send(r: HttpRequest): HttpResponse {
        val url = r.url
        if (url.contains("/documents")) return firestore(r)
        if (url.startsWith("http://$surface:8087")) return wadd(r, url.substringAfter(":8087").substringBefore('?'))
        // The made-up stream's link: up, asking for its login.
        if (url.startsWith("https://wadspaces.com/")) return HttpResponse(401, "")
        return HttpResponse(404, """{"error": {"message": "not in the fake"}}""")
    }

    private fun firestore(r: HttpRequest): HttpResponse {
        val path = r.url.substringAfter("/documents").removePrefix("/").substringBefore('?')
        return when {
            path == ":runQuery" -> ok(JsonArray(docs.filterKeys { it.startsWith("wadspaces/") }.map { (p, d) -> buildJsonObject { put("document", rest(p, d)) } }))
            path == ":commit" -> {
                for (w in json.parseToJsonElement(r.body!!).jsonObject["writes"]!!.jsonArray) {
                    val u = w.jsonObject["update"]!!.jsonObject
                    docs[u.str("name").substringAfter("documents/")] = Values.fromFields(u["fields"]!!)
                }
                ok(JsonObject(emptyMap()))
            }
            path.split('/').size % 2 == 1 -> ok(buildJsonObject {
                put("documents", JsonArray(docs.filterKeys { it.startsWith("$path/") && '/' !in it.removePrefix("$path/") }.map { (p, d) -> rest(p, d) }))
            })
            else -> docs[path]?.let { ok(rest(path, it)) } ?: HttpResponse(404, "{}")
        }
    }

    private fun wadd(r: HttpRequest, path: String): HttpResponse {
        fun built(id: String) = docs["built/$id"] != null
        return when {
            path == "/v1/remote/hello" -> ok(json.parseToJsonElement("""{"machine": "Surface", "machineId": "surface", "version": "0.1.0", "device": "Pixel 9", "trust": "trusted"}"""))
            path == "/v1/remote/wadspaces" -> ok(JsonArray(listOf("cosmic-bodybuilding" to "Cosmic Bodybuilding", "vanua-academy" to "VanuaAcademy").map { (id, name) ->
                buildJsonObject {
                    put("id", id)
                    put("name", name)
                    put("layer", "template:$id")
                    put("built", id == "cosmic-bodybuilding" || built(id))
                    put("container", if (id in streamed) "running" else "exited")
                    if (id in streamed) {
                        put("streamed", true)
                        put("live", true)
                        put("phase", "ready")
                    }
                }
            }))
            path.endsWith("/build") -> {
                val id = path.removePrefix("/v1/remote/wadspaces/").removeSuffix("/build")
                polls = 0
                ok(buildJsonObject {
                    put("workspace", id)
                    put("layer", "template:$id")
                    if (id == "cosmic-bodybuilding" || built(id)) put("built", true) else {
                        put("built", false)
                        put("buildId", "b-$id")
                    }
                })
            }
            path.startsWith("/v1/remote/builds/") -> {
                val id = path.removePrefix("/v1/remote/builds/b-")
                polls++
                val lines = listOf(
                    "» Building the Dev template first", "STEP 1/6: FROM localhost/wadspaces-base:trixie",
                    "STEP 2/6: RUN install-apps wadbrowser vscode", "STEP 3/6: COPY root/ /", "» Building VanuaAcademy Dev",
                    "STEP 4/6: RUN code --install-extension anthropic.claude-code", "STEP 5/6: RUN install-tools firebase", "STEP 6/6: LABEL io.wadspaces.layer",
                    "--> 0123abcd", "Successfully built",
                ).take(polls * 2)
                val done = polls >= 5
                if (done) docs["built/$id"] = JsonObject(emptyMap())
                ok(buildJsonObject {
                    put("build", buildJsonObject {
                        put("id", "b-$id")
                        put("status", if (done) "done" else "building")
                        put("progress", polls / 5.0)
                    })
                    put("from", 0)
                    put("lines", JsonArray(lines.map(::JsonPrimitive)))
                })
            }
            path.endsWith("/stream") && r.method == "POST" -> {
                streamed += path.removePrefix("/v1/remote/wadspaces/").removeSuffix("/stream")
                ok(json.parseToJsonElement(
                    """{"workspace": "x", "link": "https://wadspaces.com/", "openLink": "https://wadspaces.com/", "user": "wad-fake", "password": "not-real"}""",
                ))
            }
            path.endsWith("/stream") && r.method == "DELETE" -> {
                streamed -= path.removePrefix("/v1/remote/wadspaces/").removeSuffix("/stream")
                HttpResponse(204, "")
            }
            else -> HttpResponse(204, "")
        }
    }
}

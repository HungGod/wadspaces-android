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
        put("users/fake/github/repos", """{"login": "HungGod", "repos": [{"fullName": "HungGod/Writing", "name": "Writing", "private": true, "url": "", "defaultBranch": "main", "description": "Notes and drafts"}, {"fullName": "HungGod/VanuaAcademy", "name": "VanuaAcademy", "private": false, "url": "", "defaultBranch": "main", "description": "The academy's site"}, {"fullName": "HungGod/WadSpaces", "name": "WadSpaces", "private": false, "url": "", "defaultBranch": "main", "description": ""}]}""")
        // Design B's: IntelligenceQuest on the Surface (built there), Desk Lab on the Desk.
        put("wadspaces/intelligencequest", """{"version": 4, "owner": "fake", "sharedWith": [], "machine": "surface", "order": 1,
            "templates": [
              {"id": "dev", "name": "Dev", "apps": [
                {"app": "wadbrowser", "mode": "focus", "extras": [{"id": "github", "name": "GitHub", "url": "https://github.com"}, {"id": "docs-rs", "name": "Docs.rs", "url": "https://docs.rs"}]},
                {"app": "vscode", "extras": [{"id": "anthropic.claude-code"}]}]},
              {"id": "rust", "name": "C++ to Rust", "apps": [
                {"app": "vscode", "extras": [{"id": "rust-lang.rust-analyzer"}]},
                {"app": "terminal", "extras": [{"id": "cpp"}, {"id": "rust"}]}]},
              {"id": "art", "name": "Game art", "apps": [{"app": "libresprite"}, {"app": "tiled"}]}],
            "customization": {"name": "IntelligenceQuest", "description": "Moving the game from C++ to Rust.", "wallpaper": {"type": "texture", "value": "stars"},
              "projects": [{"id": "iq", "kind": "github", "repo": "HungGod/IntelligenceQuest", "as": "IntelligenceQuest"}], "secrets": {"bySlot": {}, "groups": []}},
            "createdAt": "2026-10-21T00:00:00.000Z", "updatedAt": "2026-10-21T00:00:00.000Z"}""")
        put("wadspaces/desk-lab", """{"version": 4, "owner": "fake", "sharedWith": [], "machine": "desk", "order": 1,
            "templates": [{"id": "lab", "name": "Lab", "apps": [{"app": "obsidian"}]}],
            "customization": {"name": "Desk Lab", "description": "Lives on the desk.", "wallpaper": {"type": "texture", "value": "marble"}, "projects": [], "secrets": {"bySlot": {}, "groups": []}},
            "createdAt": "2026-10-21T00:00:00.000Z", "updatedAt": "2026-10-21T00:00:00.000Z"}""")
        val now = Values.rfc3339(System.currentTimeMillis())
        put("users/fake/machines/surface", """{"name": "Surface", "hostname": "wadspaces", "lastSeen": "$now", "tailnet": {"state": "running", "ips": ["$surface", "fd7a:115c:a1e0::a01:2b03"], "dnsName": "wadspaces-surface.tail1234.ts.net", "port": 8087, "listening": true}, "images": [{"layer": "base", "id": "b1"}, {"layer": "set:9ee4c0aa0079", "id": "s1", "from": "b1"}]}""")
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
            path == "/v1/remote/wadspaces" -> ok(JsonArray(listOf("intelligencequest" to "IntelligenceQuest").map { (id, name) ->
                buildJsonObject {
                    put("id", id)
                    put("name", name)
                    put("layer", "set:9ee4c0aa0079")
                    put("built", true)
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
                    put("layer", "set:$id")
                    if (id == "intelligencequest" || built(id)) put("built", true) else {
                        put("built", false)
                        put("buildId", "b-$id")
                    }
                })
            }
            path.startsWith("/v1/remote/builds/") -> {
                val id = path.removePrefix("/v1/remote/builds/b-")
                polls++
                val lines = listOf(
                    "» Packages: obsidian (here)", "» Stacking obsidian on the base", "STEP 1/3: FROM localhost/wadspaces-base:trixie",
                    "STEP 2/3: COPY --from=pkg-obsidian / /", "STEP 3/3: LABEL io.wadspaces.layer", "» Assembling the set",
                    "wadspaces-pkg assemble: 1 package", "--> 0123abcd", "Successfully built", "» Ready",
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

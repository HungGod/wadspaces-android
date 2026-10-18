package io.wadspaces.core.firebase

import io.wadspaces.core.json
import io.wadspaces.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * One write of a commit: a document set to these fields (`$time` values as
 * in [Values]), with fields set to the time the server commits, and a
 * precondition if wanted (false: only if it isn't there yet).
 */
data class Write(
    val path: String,
    val data: JsonObject,
    val serverTime: List<String> = emptyList(),
    val exists: Boolean? = null,
)

/** Firestore's REST API, as the person signed in (the rules decide). */
class Firestore(
    private val config: FirebaseConfig,
    private val http: Http,
    private val token: suspend () -> String,
    private val base: String = "https://firestore.googleapis.com",
) {
    private val root get() = "$base/v1/projects/${config.projectId}/databases/(default)/documents"

    private fun check(path: String, document: Boolean) {
        val parts = path.split('/')
        val ok = (parts.size % 2 == 0) == document && parts.all { p ->
            p.isNotEmpty() && p.length <= 128 && p.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
        }
        require(ok) { "not a document path: $path" }
    }

    private suspend fun call(method: String, url: String, body: JsonElement? = null): JsonElement {
        val r = try {
            http.send(HttpRequest(method, url, mapOf("Authorization" to "Bearer ${token()}"), body?.toString()))
        } catch (e: AuthFailure) {
            throw e
        } catch (e: Exception) {
            throw HttpFailure(0, "Couldn't reach the account: ${e.message}")
        }
        if (r.status !in 200..299) {
            val b = runCatching { json.parseToJsonElement(r.body) }.getOrDefault(JsonNull)
            val why = ((b as? JsonObject)?.get("error") as? JsonObject).str("message").ifEmpty { r.body.take(200) }
            throw HttpFailure(r.status, if (r.status == 403) "The account refused that (its rules): $why" else "The account answered ${r.status}: $why")
        }
        return if (r.body.isBlank()) JsonNull else json.parseToJsonElement(r.body)
    }

    /** A document (plain, with its id), or null when it isn't there. */
    suspend fun get(path: String): JsonObject? {
        check(path, true)
        return try {
            Values.plain(call("GET", "$root/$path"))
        } catch (e: HttpFailure) {
            if (e.status == 404) null else throw e
        }
    }

    /** Every document of a collection (plain), page by page. */
    suspend fun list(collection: String): List<JsonObject> {
        check(collection, false)
        val out = mutableListOf<JsonObject>()
        var page: String? = null
        do {
            val q = "pageSize=300" + (page?.let { "&pageToken=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val b = call("GET", "$root/$collection?$q").jsonObject
            (b["documents"] as? JsonArray)?.forEach { out += Values.plain(it) }
            page = b.str("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    /** A structured query over a top-level collection: the documents (plain). */
    suspend fun queryRoot(structured: JsonObject): List<JsonObject> {
        val rows = call("POST", "$root:runQuery", buildJsonObject { put("structuredQuery", structured) })
        return (rows as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("document") }?.map(Values::plain) ?: emptyList()
    }

    /** All of `writes` or none. */
    suspend fun commit(writes: List<Write>) {
        writes.forEach { check(it.path, true) }
        val body = buildJsonObject {
            put("writes", JsonArray(writes.map { w ->
                buildJsonObject {
                    put("update", buildJsonObject {
                        put("name", "projects/${config.projectId}/databases/(default)/documents/${w.path}")
                        put("fields", Values.toFields(w.data))
                    })
                    if (w.serverTime.isNotEmpty()) {
                        put("updateTransforms", JsonArray(w.serverTime.map { f ->
                            buildJsonObject {
                                put("fieldPath", f)
                                put("setToServerValue", "REQUEST_TIME")
                            }
                        }))
                    }
                    w.exists?.let { e -> put("currentDocument", buildJsonObject { put("exists", JsonPrimitive(e)) }) }
                }
            }))
        }
        call("POST", "$root:commit", body)
    }
}

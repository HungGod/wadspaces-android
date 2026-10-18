package io.wadspaces.core.library

import io.wadspaces.core.firebase.Auth
import io.wadspaces.core.firebase.Firestore
import io.wadspaces.core.firebase.Values
import io.wadspaces.core.firebase.Write
import io.wadspaces.core.get
import io.wadspaces.core.str
import java.security.SecureRandom
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A GitHub repository, as the account keeps the owner's list. */
data class Repo(val fullName: String, val name: String, val private: Boolean, val description: String)

/**
 * The person's library from their account (wad-account's library.rs): the
 * Builder's documents, their wadspaces (deleted ones too: new ids skip
 * them), their machines' heartbeats, and their GitHub repos.
 */
data class Library(
    val templates: List<JsonObject> = emptyList(),
    val extensions: List<JsonObject> = emptyList(),
    /** Extensions layers from before they had that name. */
    val customizations: List<JsonObject> = emptyList(),
    val wadspaces: List<JsonObject> = emptyList(),
    val machines: List<JsonObject> = emptyList(),
    val repos: List<Repo> = emptyList(),
) {
    /** Extensions, the old customizations counted too. */
    val allExtensions: List<JsonObject>
        get() = extensions + customizations.filter { c -> extensions.none { it.str("id") == c.str("id") } }

    /** What wad-core's layers functions take: `{templates, extensions}`. */
    fun lib(): JsonObject = buildJsonObject {
        put("templates", JsonArray(templates))
        put("extensions", JsonArray(allExtensions))
    }

    /** What the Builder's planNew takes: `{templates, extensions, wadspaces}`. */
    fun docs(): JsonObject = buildJsonObject {
        put("templates", JsonArray(templates))
        put("extensions", JsonArray(allExtensions))
        put("wadspaces", JsonArray(wadspaces))
    }

    /** The wadspaces that aren't deleted, in the board's order. */
    fun live(): List<JsonObject> = wadspaces
        .filter(::alive)
        .sortedWith(compareBy({ (it["order"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0 }, { it.str("createdAt") }))
}

/** Not deleted: no `deletedAt`, or a falsy one (as the board reads it). */
fun alive(d: JsonObject): Boolean = when (val x = d["deletedAt"] ?: JsonNull) {
    JsonNull -> true
    is JsonPrimitive -> x.content.isEmpty() || x.content == "false" || x.content == "0"
    else -> false
}

/** The account, as the person signed in. */
class Account(val auth: Auth, val firestore: Firestore) {
    private fun uid() = auth.uid ?: error("signed out")

    /** Everything, read now. */
    suspend fun library(): Library = coroutineScope {
        val uid = uid()
        val owned = buildJsonObject {
            put("from", JsonArray(listOf(buildJsonObject { put("collectionId", "wadspaces") })))
            put("where", buildJsonObject {
                put("fieldFilter", buildJsonObject {
                    put("field", buildJsonObject { put("fieldPath", "owner") })
                    put("op", "EQUAL")
                    put("value", buildJsonObject { put("stringValue", uid) })
                })
            })
        }
        val templates = async { firestore.list("users/$uid/templates") }
        val extensions = async { firestore.list("users/$uid/extensions") }
        val customizations = async { firestore.list("users/$uid/customizations") }
        val machines = async { firestore.list("users/$uid/machines") }
        val wadspaces = async { firestore.queryRoot(owned) }
        val repos = async { runCatching { firestore.get("users/$uid/github/repos") }.getOrNull() }
        Library(
            templates = templates.await(),
            extensions = extensions.await(),
            customizations = customizations.await(),
            wadspaces = wadspaces.await(),
            machines = machines.await(),
            repos = repos.await()?.let(::reposOf) ?: emptyList(),
        )
    }

    /** The machines' heartbeats alone. */
    suspend fun machines(): List<JsonObject> = firestore.list("users/${uid()}/machines")

    /**
     * A plan's writes (planNew's: templates, Extensions, the wadspace), all
     * or none: documents as the rules have them (no `id`; a wadspace the
     * owner's, shared with no one).
     */
    suspend fun save(plan: JsonElement) {
        val uid = uid()
        fun strip(d: JsonElement) = JsonObject((d as JsonObject) - "id")
        val writes = mutableListOf<Write>()
        (plan["templates"] as? JsonArray)?.forEach { writes += Write("users/$uid/templates/${it.str("id")}", strip(it)) }
        (plan["extensions"] as? JsonArray)?.forEach { writes += Write("users/$uid/extensions/${it.str("id")}", strip(it)) }
        (plan["wadspaces"] as? JsonArray)?.forEach {
            val data = strip(it) + mapOf("owner" to JsonPrimitive(uid), "sharedWith" to (it["sharedWith"] as? JsonArray ?: JsonArray(emptyList())))
            writes += Write("wadspaces/${it.str("id")}", JsonObject(data))
        }
        if (writes.isNotEmpty()) firestore.commit(writes)
    }

    /** A one-time code for linking a machine (wadd's /v1/cloud/link takes it). */
    suspend fun createEnrollCode(machineName: String): String {
        val code = randomCode()
        val data = buildJsonObject {
            put("uid", uid())
            put("used", false)
            put("machineName", machineName)
        }
        firestore.commit(listOf(Write("enrollCodes/$code", data, serverTime = listOf("createdAt"))))
        return code
    }

    companion object {
        private val random = SecureRandom()

        /** Eight characters with no 0/O or 1/I (the web app's newEnrollCode). */
        fun randomCode(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return (1..8).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }

        fun reposOf(doc: JsonObject): List<Repo> = (doc["repos"] as? JsonArray)?.map {
            Repo(it.str("fullName"), it.str("name"), (it["private"] as? JsonPrimitive)?.content == "true", it.str("description"))
        } ?: emptyList()

        /** A heartbeat's age in ms (`lastSeen`), Long.MAX_VALUE when it has none. */
        fun ageOf(machine: JsonObject, now: Long): Long {
            val seen = Values.toMs(machine["lastSeen"] ?: JsonNull)
            return if (seen == 0L) Long.MAX_VALUE else now - seen
        }
    }
}

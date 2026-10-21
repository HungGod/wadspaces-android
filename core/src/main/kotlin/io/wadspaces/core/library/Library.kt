package io.wadspaces.core.library

import io.wadspaces.core.board.BMachine
import io.wadspaces.core.board.BWadspace
import io.wadspaces.core.board.Board
import io.wadspaces.core.board.V4
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
 * The person's library from their account (wad-account's library.rs):
 * their wadspaces (version 4, design B's; deleted ones too: new ids skip
 * them), their machines' heartbeats, and their GitHub repos.
 */
data class Library(
    /** Wadspaces: each its own templates, on one machine. */
    val v4: List<JsonObject> = emptyList(),
    /** Ids of the collection's other documents (from before version 4): a new wadspace never takes one. */
    val reserved: List<String> = emptyList(),
    val machines: List<JsonObject> = emptyList(),
    val repos: List<Repo> = emptyList(),
) {
    /** Design B's board: the machines and wadspaces, each machine's row in order. */
    fun board(machines: List<BMachine>, now: String): Board = Board(
        machines = machines,
        wadspaces = v4.map(BWadspace::of).sortedWith(compareBy({ it.order }, { it.id })).toMutableList(),
        now = now,
        reserved = reserved,
    )
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
        val machines = async { firestore.list("users/$uid/machines") }
        val wadspaces = async { firestore.queryRoot(owned) }
        val repos = async { runCatching { firestore.get("users/$uid/github/repos") }.getOrNull() }
        val (v4, others) = wadspaces.await().partition(V4::isV4)
        Library(
            v4 = v4,
            reserved = others.map { it.str("id") },
            machines = machines.await(),
            repos = repos.await()?.let(::reposOf) ?: emptyList(),
        )
    }

    /** The machines' heartbeats alone. */
    suspend fun machines(): List<JsonObject> = firestore.list("users/${uid()}/machines")

    /**
     * Design B's board's documents (its plan), all or none: each the owner's,
     * shared with no one.
     */
    suspend fun saveWadspaces(docs: List<JsonObject>) {
        val uid = uid()
        val writes = docs.map {
            val data = JsonObject(it - "id") + mapOf("owner" to JsonPrimitive(uid), "sharedWith" to (it["sharedWith"] as? JsonArray ?: JsonArray(emptyList())))
            Write("wadspaces/${it.str("id")}", JsonObject(data))
        }
        if (writes.isNotEmpty()) firestore.commit(writes)
    }

    /**
     * A command for one of their machines (its relay runs it within a few
     * seconds of being on): building a wadspace there, say.
     */
    suspend fun command(machine: String, type: String, wsId: String) {
        val data = buildJsonObject {
            put("type", type)
            put("status", "pending")
            put("wsId", wsId)
        }
        val id = (1..20).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")
        firestore.commit(listOf(Write("users/${uid()}/machines/$machine/commands/$id", data, serverTime = listOf("createdAt"))))
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

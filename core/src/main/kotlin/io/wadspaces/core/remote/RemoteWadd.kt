package io.wadspaces.core.remote

import io.wadspaces.core.firebase.Http
import io.wadspaces.core.firebase.HttpRequest
import io.wadspaces.core.firebase.Values
import io.wadspaces.core.json
import io.wadspaces.core.str
import java.net.InetAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** wadd's answers on its remote API (wad-proto's v1 Remote* types). */
@Serializable
data class RemoteHello(val machine: String, val machineId: String? = null, val version: String, val device: String, val trust: String)

@Serializable
data class RemoteWadspace(
    val id: String,
    val name: String,
    val layer: String,
    val built: Boolean,
    val building: String? = null,
    val container: String = "unknown",
    val phase: String? = null,
    val onScreen: Boolean = false,
    val streamed: Boolean = false,
    val live: Boolean = false,
)

@Serializable
data class RemoteBuild(
    val workspace: String,
    val layer: String,
    val buildId: String? = null,
    val built: Boolean,
    val restartRequired: Boolean = false,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class Build(val id: String, val status: String, val progress: Double = 0.0, val error: String? = null) {
    val finished get() = status == "done" || status == "error" || status == "cancelled"
}

@Serializable
data class BuildLog(val build: Build, val from: Long = 0, val lines: List<String> = emptyList())

@Serializable
data class StreamShare(val workspace: String, val link: String, val openLink: String = "", val user: String = "", val password: String = "") {
    override fun toString() = "StreamShare($workspace)"
}

/** wadd refused, or couldn't be reached (status 0): its code and words. */
class RemoteFailure(val status: Int, val code: String, message: String) : Exception(message) {
    val notTrusted get() = status == 403 && message?.startsWith("Allow ") == true
}

/** Where a machine's remote API is, from its heartbeat's `tailnet`. */
data class Target(val host: String, val port: Int) {
    val base get() = if (':' in host) "http://[$host]:$port" else "http://$host:$port"
}

/** One of the owner's machines, from its heartbeat. */
data class Machine(
    val id: String,
    val name: String,
    val online: Boolean,
    /** "running", "needsLogin", ... ("off" or none: not on the tailnet). */
    val tailnet: String,
    val target: Target?,
) {
    companion object {
        /** Heartbeats within this long: online (the client's ONLINE_WITHIN). */
        const val ONLINE_WITHIN_MS = 90_000L

        fun of(heartbeat: JsonObject, now: Long): Machine {
            val t = heartbeat["tailnet"] as? JsonObject
            val port = (t?.get("port") as? JsonPrimitive)?.content?.toIntOrNull() ?: 8087
            val ip = (t?.get("ips") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.filter(RemoteWadd::onTailnet)?.sortedBy { ':' in it }?.firstOrNull()
            val seen = Values.toMs(heartbeat["lastSeen"] ?: JsonNull)
            return Machine(
                id = heartbeat.str("id"),
                name = heartbeat.str("name").ifEmpty { heartbeat.str("hostname") }.ifEmpty { "A machine" },
                online = seen > 0 && now - seen < ONLINE_WITHIN_MS,
                tailnet = t.str("state").ifEmpty { "off" },
                target = ip?.takeIf { t.str("state") == "running" }?.let { Target(it, port) },
            )
        }
    }
}

/**
 * wadd's remote API on one of the owner's machines (host/TAILNET.md):
 * plain HTTP inside the tailnet, the owner's ID token on every call but
 * hello. Only tailnet addresses are ever called.
 */
class RemoteWadd(private val http: Http, private val token: suspend () -> String) {
    companion object {
        /** 100.64.0.0/10 or fd7a:115c:a1e0::/48: Tailscale's ranges. */
        fun onTailnet(ip: String): Boolean {
            // Literal addresses only, so nothing is ever looked up by name.
            val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
            val v6 = Regex("""^[0-9A-Fa-f:]+$""")
            if (!v4.matches(ip) && !(v6.matches(ip) && ':' in ip)) return false
            val a = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
            return when (a.size) {
                4 -> (a[0].toInt() and 0xff) == 100 && (a[1].toInt() and 0xc0) == 64
                16 -> a[0] == 0xfd.toByte() && a[1] == 0x7a.toByte() && a[2] == 0x11.toByte() && a[3] == 0x5c.toByte() &&
                    a[4] == 0xa1.toByte() && a[5] == 0xe0.toByte()
                else -> false
            }
        }
    }

    private suspend fun call(t: Target, method: String, path: String, body: JsonElement? = null, signed: Boolean = true, timeoutMs: Int = 30_000): String {
        if (!onTailnet(t.host)) throw RemoteFailure(0, "not_tailnet", "${t.host} isn't on the tailnet.")
        val headers = if (signed) mapOf("Authorization" to "Bearer ${token()}") else emptyMap()
        val r = try {
            http.send(HttpRequest(method, t.base + path, headers, body?.toString() ?: if (method == "POST") "{}" else null, timeoutMs))
        } catch (e: RemoteFailure) {
            throw e
        } catch (e: Exception) {
            throw RemoteFailure(0, "unreachable", "Couldn't reach the machine over the tailnet (is Tailscale on?)")
        }
        if (r.status in 200..299) return r.body
        val err = runCatching { json.parseToJsonElement(r.body) }.getOrDefault(JsonNull)
        throw RemoteFailure(r.status, err.str("code"), err.str("message").ifEmpty { "The machine answered ${r.status}." })
    }

    private inline fun <reified T> read(text: String): T = json.decodeFromString(text)

    suspend fun hello(t: Target): RemoteHello = read(call(t, "GET", "/v1/remote/hello", signed = false, timeoutMs = 10_000))
    suspend fun wadspaces(t: Target): List<RemoteWadspace> = read(call(t, "GET", "/v1/remote/wadspaces", timeoutMs = 15_000))

    /** Installs it from the account and builds its image (or says it's built). */
    suspend fun build(t: Target, id: String): RemoteBuild = read(call(t, "POST", "/v1/remote/wadspaces/$id/build", timeoutMs = 120_000))
    suspend fun buildLog(t: Target, buildId: String, since: Long): BuildLog =
        read(call(t, "GET", "/v1/remote/builds/$buildId?since=$since", timeoutMs = 15_000))

    suspend fun open(t: Target, id: String) {
        call(t, "POST", "/v1/remote/wadspaces/$id/open")
    }

    suspend fun stop(t: Target, id: String) {
        call(t, "POST", "/v1/remote/wadspaces/$id/stop", timeoutMs = 60_000)
    }

    /** Streams it: its sign-in link. `takeover`: off the machine's screen if it's there. */
    suspend fun stream(t: Target, id: String, takeover: Boolean): StreamShare =
        read(call(t, "POST", "/v1/remote/wadspaces/$id/stream", buildJsonObject { put("takeover", takeover) }, timeoutMs = 120_000))

    suspend fun stopStream(t: Target, id: String) {
        call(t, "DELETE", "/v1/remote/wadspaces/$id/stream", timeoutMs = 60_000)
    }
}

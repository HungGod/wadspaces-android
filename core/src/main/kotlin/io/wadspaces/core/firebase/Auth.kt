package io.wadspaces.core.firebase

import io.wadspaces.core.json
import io.wadspaces.core.str
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The account's Firebase project (the web app's config). */
@Serializable
data class FirebaseConfig(val apiKey: String, val projectId: String, val authDomain: String) {
    /** The page a GitHub sign-in runs in (the WadSpaces Client's too). */
    fun signInPage(nonce: String, scopes: String) =
        "https://$authDomain/machine-signin.html?n=${enc(nonce)}&scopes=${enc(scopes)}"
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Who's signed in, and their tokens (kept between starts, app-private). */
@Serializable
data class Session(
    val uid: String,
    val login: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val refreshToken: String,
    val idToken: String = "",
    /** When the ID token stops working (epoch ms). */
    val expiresAt: Long = 0,
)

/** Where the session is kept (a private file on the phone). */
interface SessionStore {
    fun load(): String?
    fun save(text: String?)
}

/** Firebase Auth's refusal, in words. */
class AuthFailure(message: String) : Exception(message)

/**
 * Signing in to the account with a GitHub access token (the sign-in page's),
 * and keeping the ID token fresh: wad-account's, over Firebase's REST API.
 */
class Auth(
    val config: FirebaseConfig,
    private val http: Http,
    private val store: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val identity: String = "https://identitytoolkit.googleapis.com/v1",
    private val securetoken: String = "https://securetoken.googleapis.com/v1",
) {
    private val lock = Mutex()

    @Volatile
    var session: Session? = store.load()?.let { runCatching { json.decodeFromString<Session>(it) }.getOrNull() }
        private set

    val uid: String? get() = session?.uid

    private fun keep(s: Session?) {
        session = s
        store.save(s?.let { json.encodeToString(Session.serializer(), it) })
    }

    private suspend fun post(url: String, body: String, form: Boolean = false): JsonObject {
        val headers = if (form) mapOf("Content-Type" to "application/x-www-form-urlencoded") else emptyMap()
        val r = try {
            http.send(HttpRequest("POST", url, headers, body))
        } catch (e: Exception) {
            throw HttpFailure(0, "Couldn't reach the account: ${e.message}")
        }
        val b = runCatching { json.parseToJsonElement(r.body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        if (r.status != 200) {
            val why = (b["error"] as? JsonObject).str("message").ifEmpty { "answered ${r.status}" }
            throw AuthFailure(
                when {
                    why.startsWith("ADMIN_ONLY_OPERATION") -> "New accounts are closed: sign in with the account you have."
                    why.startsWith("TOKEN_EXPIRED") || why.startsWith("INVALID_REFRESH_TOKEN") ||
                        why.startsWith("USER_NOT_FOUND") || why.startsWith("USER_DISABLED") -> "Signed out: sign in again."
                    else -> "Sign-in refused: $why"
                },
            )
        }
        return b
    }

    /** Signs in (the account must be there already) with a GitHub access token. */
    suspend fun signInWithGithub(accessToken: String): Session = lock.withLock {
        val body = buildJsonObject {
            put("postBody", "access_token=${enc(accessToken)}&providerId=github.com")
            put("requestUri", "http://localhost")
            put("returnIdpCredential", true)
            put("returnSecureToken", true)
        }
        val b = post("$identity/accounts:signInWithIdp?key=${enc(config.apiKey)}", body.toString())
        val s = Session(
            uid = b.str("localId"),
            login = b.str("screenName").ifEmpty { null },
            displayName = b.str("displayName").ifEmpty { null },
            photoUrl = b.str("photoUrl").ifEmpty { null },
            refreshToken = b.str("refreshToken"),
            idToken = b.str("idToken"),
            expiresAt = now() + (b.str("expiresIn").toLongOrNull() ?: 3600) * 1000,
        )
        if (s.uid.isEmpty() || s.idToken.isEmpty()) throw AuthFailure("The account didn't sign you in.")
        keep(s)
        s
    }

    /** The ID token, refreshed when it has under five minutes left. */
    suspend fun idToken(): String = lock.withLock {
        val s = session ?: throw AuthFailure("Signed out: sign in again.")
        if (s.idToken.isNotEmpty() && s.expiresAt - now() > 5 * 60_000) return s.idToken
        val b = try {
            post("$securetoken/token?key=${enc(config.apiKey)}", "grant_type=refresh_token&refresh_token=${enc(s.refreshToken)}", form = true)
        } catch (e: AuthFailure) {
            keep(null)
            throw e
        }
        val fresh = s.copy(
            idToken = b.str("id_token"),
            refreshToken = b.str("refresh_token").ifEmpty { s.refreshToken },
            expiresAt = now() + (b.str("expires_in").toLongOrNull() ?: 3600) * 1000,
        )
        keep(fresh)
        fresh.idToken
    }

    fun signOut() = keep(null)
}

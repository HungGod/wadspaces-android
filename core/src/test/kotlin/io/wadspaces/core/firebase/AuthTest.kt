package io.wadspaces.core.firebase

import io.wadspaces.core.json
import io.wadspaces.core.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AuthTest {
    @Test
    fun signsInWithGithubAndRefreshes() = runBlocking<Unit> {
        var clock = 1_000_000L
        val http = FakeHttp { r ->
            when {
                r.url.contains("accounts:signInWithIdp") -> {
                    assertTrue(r.url.endsWith("?key=key"))
                    val b = json.parseToJsonElement(r.body!!)
                    assertEquals("access_token=gho_abc&providerId=github.com", b.str("postBody"))
                    HttpResponse(200, """{"localId": "u1", "idToken": "id1", "refreshToken": "rt1", "expiresIn": "3600", "screenName": "HungGod"}""")
                }
                r.url.contains("/token") -> {
                    assertEquals("grant_type=refresh_token&refresh_token=rt1", r.body)
                    HttpResponse(200, """{"id_token": "id2", "refresh_token": "rt2", "expires_in": "3600"}""")
                }
                else -> error(r.url)
            }
        }
        val store = MemoryStore()
        val auth = Auth(config, http, store, now = { clock })
        val s = auth.signInWithGithub("gho_abc")
        assertEquals(Triple("u1", "HungGod", "id1"), Triple(s.uid, s.login, s.idToken))
        assertEquals("id1", auth.idToken())
        // Kept: a new start is still signed in.
        assertEquals("u1", Auth(config, http, store, now = { clock }).uid)
        // Under five minutes left: refreshed.
        clock += 56 * 60_000
        assertEquals("id2", auth.idToken())
        assertEquals("rt2", auth.session!!.refreshToken)
        assertEquals(2, http.asked.size)
    }

    @Test
    fun refusalsInWords() = runBlocking<Unit> {
        val http = FakeHttp { HttpResponse(400, """{"error": {"message": "ADMIN_ONLY_OPERATION"}}""") }
        val auth = Auth(config, http, MemoryStore())
        val e = assertFailsWith<AuthFailure> { auth.signInWithGithub("gho_x") }
        assertTrue(e.message!!.startsWith("New accounts are closed"))
        assertFailsWith<AuthFailure> { auth.idToken() }
    }

    @Test
    fun aRefreshThatDoesntHoldSignsOut() = runBlocking<Unit> {
        val store = MemoryStore("""{"uid": "u1", "refreshToken": "old"}""")
        val auth = Auth(config, FakeHttp { HttpResponse(400, """{"error": {"message": "TOKEN_EXPIRED"}}""") }, store)
        assertEquals("u1", auth.uid)
        assertFailsWith<AuthFailure> { auth.idToken() }
        assertNull(auth.session)
        assertNull(store.text)
    }

    @Test
    fun theSignInPage() {
        assertEquals(
            "https://demo.firebaseapp.com/machine-signin.html?n=abc&scopes=read%3Auser",
            config.signInPage("abc", "read:user"),
        )
    }
}

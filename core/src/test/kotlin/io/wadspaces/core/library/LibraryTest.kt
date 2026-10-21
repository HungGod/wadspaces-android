package io.wadspaces.core.library

import io.wadspaces.core.board.BMachine
import io.wadspaces.core.firebase.Auth
import io.wadspaces.core.firebase.FakeHttp
import io.wadspaces.core.firebase.Firestore
import io.wadspaces.core.firebase.HttpResponse
import io.wadspaces.core.firebase.MemoryStore
import io.wadspaces.core.firebase.config
import io.wadspaces.core.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class LibraryTest {
    private fun account(http: FakeHttp): Account {
        val auth = Auth(config, http, MemoryStore("""{"uid": "u1", "refreshToken": "rt", "idToken": "tok", "expiresAt": ${Long.MAX_VALUE}}"""))
        return Account(auth, Firestore(config, http, auth::idToken, base = "http://fs"))
    }

    @Test
    fun linkCodes() = runBlocking<Unit> {
        val http = FakeHttp { HttpResponse(200, "{}") }
        val code = account(http).createEnrollCode("Laptop")
        assertTrue(Regex("^[A-HJ-NP-Z2-9]{8}$").matches(code), code)
        val w = json.parseToJsonElement(http.asked[0].body!!).jsonObject["writes"]!!.jsonArray[0].jsonObject
        assertTrue(w["update"].toString().contains("enrollCodes/$code"))
        assertEquals("createdAt", (w["updateTransforms"] as JsonArray)[0].jsonObject["fieldPath"].toString().trim('"'))
    }

    @Test
    fun designBsBoardFromTheLibraryAndItsPlanSaved() = runBlocking<Unit> {
        fun doc(text: String) = json.parseToJsonElement(text).jsonObject
        val lib = Library(
            reserved = listOf("old"),
            v4 = listOf(
                doc("""{"version": 4, "id": "b", "machine": "m1", "order": 2, "customization": {"name": "B"}}"""),
                doc("""{"version": 4, "id": "a", "machine": "m1", "order": 1, "customization": {"name": "A"}}"""),
            ),
        )
        val board = lib.board(listOf(BMachine("m1", "Surface")), "t")
        assertEquals(listOf("a", "b"), board.row("m1").map { it.id })
        assertEquals(listOf("old"), board.reserved)
        val http = FakeHttp { HttpResponse(200, "{}") }
        account(http).saveWadspaces(listOf(board.wadspaces[0].json()))
        val w = json.parseToJsonElement(http.asked[0].body!!).jsonObject["writes"]!!.jsonArray[0].jsonObject["update"]!!.jsonObject
        assertTrue(w["name"].toString().endsWith("wadspaces/a\""), w["name"].toString())
        val fields = w["fields"]!!.jsonObject
        assertTrue("id" !in fields)
        assertEquals(json.parseToJsonElement("""{"stringValue": "u1"}"""), fields["owner"])
    }

    @Test
    fun aBuildForAnotherMachineIsACommandItsRelayRuns() = runBlocking<Unit> {
        val http = FakeHttp { HttpResponse(200, "{}") }
        account(http).command("m-desk", "build", "iq")
        val w = json.parseToJsonElement(http.asked[0].body!!).jsonObject["writes"]!!.jsonArray[0].jsonObject
        assertTrue(w["update"]!!.jsonObject["name"].toString().contains("users/u1/machines/m-desk/commands/"))
        val fields = w["update"]!!.jsonObject["fields"]!!.jsonObject
        assertEquals(json.parseToJsonElement("""{"stringValue": "build"}"""), fields["type"])
        assertEquals(json.parseToJsonElement("""{"stringValue": "iq"}"""), fields["wsId"])
    }

    @Test
    fun deletedOnesArentAlive() {
        fun doc(text: String) = json.parseToJsonElement(text).jsonObject
        assertTrue(alive(doc("""{"id": "a", "deletedAt": null}""")))
        assertTrue(alive(doc("""{"id": "b"}""")))
        assertTrue(!alive(doc("""{"id": "gone", "deletedAt": "2026-10-01T00:00:00Z"}""")))
    }
}

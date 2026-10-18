package io.wadspaces.core.firebase

import io.wadspaces.core.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** wad-firebase's values.rs tests, the same cases. */
class ValuesTest {
    private fun j(s: String) = json.parseToJsonElement(s)

    @Test
    fun valuesBothWays() {
        val v = j("""{"name": "Surface", "n": 3, "x": 1.5, "ok": true, "none": null, "list": ["a", 2], "map": {"k": "v"}}""").jsonObject
        val fields = Values.toFields(JsonObject(v + ("seen" to Values.time(1_790_931_762_197))))
        assertEquals(j("""{"integerValue": "3"}"""), fields["n"])
        assertEquals(j("""{"doubleValue": 1.5}"""), fields["x"])
        assertEquals(j("""{"timestampValue": "2026-10-02T09:02:42.197Z"}"""), fields["seen"])
        assertEquals(j("""{"mapValue": {"fields": {"k": {"stringValue": "v"}}}}"""), fields["map"])
        assertEquals(j("""{"nullValue": null}"""), fields["none"])
        val back = Values.fromFields(fields)
        assertEquals(j("""["a", 2]"""), back["list"])
        assertEquals(JsonPrimitive("2026-10-02T09:02:42.197Z"), back["seen"])
        assertEquals(1_790_931_762_197, Values.toMs(back["seen"]!!))
        assertEquals(j("[]"), Values.fromValue(j("""{"arrayValue": {}}""")))
        assertEquals(JsonObject(emptyMap()), Values.fromFields(j("null")))
    }

    @Test
    fun times() {
        assertEquals("1970-01-01T00:00:00.000Z", Values.rfc3339(0))
        assertEquals("2000-02-29T00:00:00.000Z", Values.rfc3339(951_782_400_000))
        assertEquals(951_782_400_000, Values.toMs(JsonPrimitive("2000-02-29T00:00:00Z")))
        assertEquals(
            Values.toMs(JsonPrimitive("2026-10-02T21:02:43.123Z")),
            Values.toMs(JsonPrimitive("2026-10-02T21:02:43.123456789Z")),
        )
        assertEquals(Values.toMs(JsonPrimitive("2026-10-02T09:02:43Z")), Values.toMs(JsonPrimitive("2026-10-02T21:02:43+12:00")))
        assertEquals(1_700_000_000_000, Values.toMs(JsonPrimitive(1_700_000_000_000)))
        assertEquals(0, Values.toMs(JsonPrimitive("nope")))
        for (ms in listOf(0L, 1, 999, 86_399_999, 1_790_931_762_197, -1000)) {
            assertEquals(ms, Values.toMs(JsonPrimitive(Values.rfc3339(ms))))
        }
    }

    @Test
    fun aDocumentPlain() {
        val d = j("""{"name": "projects/p/databases/(default)/documents/users/u/templates/dev", "fields": {"apps": {"arrayValue": {"values": [{"stringValue": "vscode"}]}}}}""")
        assertEquals(j("""{"apps": ["vscode"], "id": "dev"}"""), Values.plain(d))
    }
}

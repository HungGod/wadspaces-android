package io.wadspaces.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

class JsonTest {
    @Test
    fun readsFieldsLikeTheCore() {
        val v = json.parseToJsonElement("""{"id": "dev", "n": 1, "o": {"x": "y"}}""")
        assertEquals("dev", v.str("id"))
        assertEquals("", v.str("n"))
        assertEquals("", v.str("missing"))
        assertEquals("y", v["o"].str("x"))
        assertEquals(JsonNull, v["nope"]["deeper"])
        assertEquals(3, v.jsonObject.size)
    }
}

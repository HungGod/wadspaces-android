package io.wadspaces.core.board

import io.wadspaces.core.json
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The phone's board and layers return what wadspaces' do: every case of
 * the functions ported here, from the goldens scripts/sync-wadspaces.sh
 * copies (the board's, wad-core's, and the phone's own planNew cases).
 */
class GoldensTest {
    private fun cases(file: String): List<JsonObject> {
        val text = javaClass.getResource("/fixtures/$file")?.readText() ?: error("no $file: scripts/sync-wadspaces.sh")
        return json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    }

    private fun text(v: JsonElement) = v.jsonPrimitive.content
    private fun list(v: JsonElement) = v.jsonArray.toList()

    /** A ported function by its golden name, or null when the phone doesn't have it. */
    private fun call(name: String, a: List<JsonElement>): JsonElement? = when (name) {
        "toBoard" -> Sync.toBoard(a[0])
        "plan" -> Sync.plan(list(a[0]), a[1], text(a[2]), text(a[3]))
        "planNew" -> Sync.planNew(a[0], a[1], text(a[2]), text(a[3]))
        "withRefs" -> Sync.withRefs(list(a[0]), a[1].jsonObject.mapValues { text(it.value) })
        "setupOfModule" -> Sync.setupOfModule(a[0])
        "customizationOf" -> Sync.customizationOf(a[0])
        "chainOf" -> JsonArray(Layers.chainOf(a[0], text(a[1])))
        "resolveStack" -> Layers.resolveStack(a[0], a[1])
        "stackImageKey" -> JsonPrimitive(Layers.stackImageKey(a[0]))
        "webappId" -> JsonPrimitive(Layers.webappId(text(a[0])))
        "setupOf" -> Layers.setupOf(a[0])
        "emptySetup" -> Layers.emptySetup(text(a[0]))
        else -> null
    }

    private fun check(file: String, atLeast: Int) {
        var ran = 0
        val failures = mutableListOf<String>()
        for ((i, c) in cases(file).withIndex()) {
            val name = text(c["fn"]!!)
            val want = c["out"] ?: continue
            val got = try {
                call(name, c["args"]!!.jsonArray) ?: continue
            } catch (e: Exception) {
                failures += "#$i $name threw $e"
                continue
            }
            ran++
            if (got != want) failures += "#$i $name\n  want: ${want.toString().take(1500)}\n  got:  ${got.toString().take(1500)}"
        }
        assertTrue(failures.isEmpty(), "${failures.size} of $ran $file cases differ:\n${failures.joinToString("\n")}")
        assertTrue(ran >= atLeast, "only $ran $file cases ran")
    }

    @Test
    fun theBoardsCases() = check("board-goldens.json", 30)

    @Test
    fun wadCoresCases() = check("core-goldens.json", 30)

    @Test
    fun thePhonesPlanNewCases() = check("phone.json", 13)
}

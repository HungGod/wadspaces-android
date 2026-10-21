package io.wadspaces.core.board

import io.wadspaces.core.json
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The phone's board and layers return what wadspaces' do: every case of
 * the functions ported here, from the goldens scripts/sync-wadspaces.sh
 * copies (the board's, design B's board's, wad-core's, and the phone's own
 * planNew cases).
 */
class GoldensTest {
    private fun resource(file: String) = javaClass.getResource("/fixtures/$file")?.readText() ?: error("no $file: scripts/sync-wadspaces.sh")

    private fun cases(file: String): List<JsonObject> = json.parseToJsonElement(resource(file)).jsonArray.map { it.jsonObject }

    private val graph by lazy { Packages.parse(resource("packages.json")) }

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
        // Design B's board, and version 4 wadspaces.
        "bRun" -> Board.run(a[0], graph)
        "bGroups" -> JsonArray(Board.of(a[0]).groupsOf(text(a[1])))
        "bNewKey" -> Board.of(a[0]).newKey(text(a[1]), text(a[2]))
        "textures" -> JsonArray(V4.TEXTURES.map(::JsonPrimitive))
        "v4Check" -> V4.check(a[0], graph)?.let(::JsonPrimitive) ?: JsonNull
        "v4Apps" -> JsonArray(V4.appsOf(a[0]))
        "v4Packages" -> JsonArray(V4.packagesOf(a[0], graph).map(::JsonPrimitive))
        "v4ImageKey" -> JsonPrimitive(V4.imageKey(a[0], graph))
        "v4TemplateSetup" -> V4.asTemplateSetup(a[0]).let { (t, s) -> JsonArray(listOf(t, s)) }
        "v4State" -> JsonPrimitive(V4.state(a[0], list(a[1]), graph))
        "v4View" -> V4.view(a[0], graph)
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
    fun wadCoresCases() = check("core-goldens.json", 55)

    @Test
    fun designBsBoardCases() = check("b-goldens.json", 13)

    @Test
    fun thePhonesPlanNewCases() = check("phone.json", 13)
}

package io.wadspaces.core.builder

import io.wadspaces.core.board.Layers
import io.wadspaces.core.board.Sync
import io.wadspaces.core.get
import io.wadspaces.core.json
import io.wadspaces.core.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BuilderTest {
    private val now = "2026-10-18T09:00:00.000Z"
    private val catalog = Catalog(
        apps = listOf(App("wadbrowser", "WadBrowser", "", "", false), App("vscode", "VS Code", "", "", true), App("obsidian", "Obsidian", "", "", true)),
        extensions = emptyList(), tools = emptyList(),
        starters = listOf(Starter("dev", "Dev", "WadBrowser and VS Code, for building things.", listOf("wadbrowser", "vscode"))),
        presets = emptyList(),
    )
    private val dev = json.parseToJsonElement(
        """{"id": "dev", "owner": "u1", "name": "Dev", "description": "", "apps": ["wadbrowser", "vscode"], "createdAt": "t", "updatedAt": "t"}""",
    ).jsonObject
    private val lib = JsonObject(mapOf("templates" to JsonArray(listOf(dev)), "extensions" to JsonArray(emptyList())))
    private val docs = JsonObject(lib + ("wadspaces" to JsonArray(emptyList())))

    @Test
    fun aStarterOnAnEmptyLibrary() {
        val st = Builder.stack("stack-a", TemplatePick.FromStarter(catalog.starters[0]), ExtensionsPick.None, Customization("Side Project"), JsonObject(emptyMap()))
        val p = Sync.planNew(st, JsonObject(emptyMap()), "u1", now)
        assertEquals(listOf("dev"), p["templates"]!!.jsonArray.map { it.str("id") })
        val w = p["wadspaces"]!!.jsonArray[0]
        assertEquals("dev", w.str("template"))
        assertEquals("Side Project", w["customization"].str("name"))
        assertEquals(Sync.blankWallpaper(), w["customization"]["wallpaper"])
    }

    @Test
    fun yoursWithNewExtensionsAndProjects() {
        val projects = Builder.addRepo(Builder.addRepo(emptyList(), "HungGod/Writing"), "hunggod/writing")
        assertEquals(1, projects.size)
        val two = Builder.addRepo(projects, "Other/Writing")
        assertEquals(listOf("Writing", "Writing-2"), two.map { it.folder })
        val e = ExtensionsPick.New(
            "Study tools", focus = true,
            webapps = listOf(WebApp(Layers.webappId("Claude"), "Claude", "https://claude.ai")),
            extensions = listOf("ms-python.python"), tools = listOf("python"),
        )
        val c = Customization("Study", wallpaper = catalogPreset(), projects = two, titleBars = true)
        val st = Builder.stack("stack-b", TemplatePick.Yours("dev"), e, c, lib)
        assertTrue(Builder.problems(TemplatePick.Yours("dev"), e, c, lib, catalog).isEmpty())
        val p = Sync.planNew(st, docs, "u1", now)
        assertEquals(JsonArray(emptyList()), p["templates"])
        val ext = p["extensions"]!!.jsonArray.single()
        assertEquals(listOf("study-tools", "dev", "focus"), listOf(ext.str("id"), ext.str("templateId"), ext.str("browserMode")))
        val w = p["wadspaces"]!!.jsonArray.single()
        assertEquals(listOf("dev", "study-tools"), listOf(w.str("template"), w.str("extensions")))
        val mounts = w["customization"]["projects"].jsonArray
        assertEquals(listOf("HungGod/Writing", "Other/Writing"), mounts.map { it.str("repo") })
        assertEquals("true", w["customization"]["titleBars"].toString())
    }

    @Test
    fun aNewTemplateOnYours() {
        val t = TemplatePick.New("Dev + Notes", listOf("wadbrowser", "vscode", "obsidian"), parent = "dev")
        val st = Builder.stack("stack-c", t, ExtensionsPick.None, Customization("Notes"), lib)
        assertEquals(listOf("dev@stack-c", "new-template@stack-c"), st["templates"]!!.jsonArray.map { it.str("id") })
        val p = Sync.planNew(st, docs, "u1", now)
        val made = p["templates"]!!.jsonArray.single()
        assertEquals(listOf("dev-notes", "dev"), listOf(made.str("id"), made.str("parent")))
    }

    @Test
    fun whatsMissingIsSaid() {
        val bad = TemplatePick.New("", emptyList(), parent = "dev")
        val e = ExtensionsPick.New("X", extensions = listOf("ms-python.python"))
        val got = Builder.problems(bad, e, Customization(" "), lib, catalog)
        assertTrue("Give it a name." in got && "Pick at least one app." in got && "Name the template." in got, "$got")
        assertTrue(got.any { it.startsWith("It builds on a template with WadBrowser, VS Code") }, "$got")
        assertTrue("VS Code extensions need VS Code in the template." in got, "$got")
        assertTrue(Builder.problems(TemplatePick.Yours("gone"), ExtensionsPick.None, Customization("A"), lib, catalog) == listOf("That template is gone."))
    }

    @Test
    fun idsLikeTheBoards() {
        assertTrue(Regex("^stack-[0-9a-z]+-[0-9a-z]+$").matches(Builder.uid("stack")))
        // The time in base 36, as wad-board's radix36 writes it.
        assertEquals("m2r1bz7k", Builder.uid("stack", 1_730_000_000_000).split('-')[1])
    }

    private fun catalogPreset() = json.parseToJsonElement("""{"type": "gradient", "value": "linear-gradient(180deg, #0a0614, #142640)"}""").jsonObject
}

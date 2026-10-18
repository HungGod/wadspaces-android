package io.wadspaces.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.wadspaces.android.AppModel
import io.wadspaces.android.LibState
import io.wadspaces.core.board.Layers
import io.wadspaces.core.board.Sync
import io.wadspaces.core.builder.Builder
import io.wadspaces.core.builder.Customization
import io.wadspaces.core.builder.ExtensionsPick
import io.wadspaces.core.builder.TemplatePick
import io.wadspaces.core.builder.WebApp
import io.wadspaces.core.library.Library
import io.wadspaces.core.library.alive
import io.wadspaces.core.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private val STEPS = listOf("Template", "Extensions", "Look", "Review")

/** The template's apps, whichever way it's picked (null: it's gone). */
private fun appsOf(t: TemplatePick, lib: Library): List<String>? = when (t) {
    is TemplatePick.Yours -> Layers.chainOf(lib.lib(), t.id).lastOrNull()?.let { strings(it["apps"]) }
    is TemplatePick.FromStarter -> t.starter.apps
    is TemplatePick.New -> t.apps
}

private fun strings(v: kotlinx.serialization.json.JsonElement?) = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

/**
 * The phone's builder: one new wadspace, a step at a time. Its template
 * (yours, a starter, or new from catalog apps), its Extensions (none, yours,
 * or new), its look and projects; saved as the board would save it.
 */
@Composable
fun BuilderScreen(model: AppModel, saved: (String) -> Unit) {
    val state by model.library.collectAsState()
    val lib = (state as? LibState.Ready)?.lib ?: return Centered("Your library isn't here yet.")
    val catalog = model.g.catalog
    var step by remember { mutableStateOf(0) }
    var template by remember { mutableStateOf<TemplatePick>(TemplatePick.FromStarter(catalog.starters.first())) }
    var extensions by remember { mutableStateOf<ExtensionsPick>(ExtensionsPick.None) }
    var look by remember { mutableStateOf(Customization("", wallpaper = catalog.presets.getOrNull(1)?.wallpaper ?: Sync.blankWallpaper())) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val problems = Builder.problems(template, extensions, look, lib.lib(), catalog)

    Column(Modifier.fillMaxSize()) {
        PageHead("New wadspace", "Step ${step + 1} of 4: ${STEPS[step]}")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEPS.indices.forEach { i ->
                val on = i <= step
                Card(
                    Modifier.weight(1f).height(4.dp),
                    shape = RoundedCornerShape(2.dp),
                    colors = CardDefaults.cardColors(containerColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                ) {}
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            when (step) {
                0 -> TemplateStep(model, lib, template) {
                    template = it
                    extensions = ExtensionsPick.None
                }
                1 -> ExtensionsStep(model, lib, template, extensions) { extensions = it }
                2 -> LookStep(model, lib, look) { look = it }
                else -> ReviewStep(model, lib, template, extensions, look, problems)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (step < 3) {
                Button(onClick = { step++ }) { Text("Next") }
            } else if (saving) {
                CircularProgressIndicator()
            } else {
                Button(enabled = problems.isEmpty(), onClick = {
                    error = null
                    saving = true
                    scope.launch {
                        try {
                            saved(model.save(template, extensions, look))
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't save it."
                        }
                        saving = false
                    }
                }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Section(title: String, sub: String? = null) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun Choice(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Column(Modifier.padding(start = 4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun <T> Tabs(options: List<Pair<T, String>>, picked: T, pick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(picked == v, { pick(v) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateStep(model: AppModel, lib: Library, picked: TemplatePick, pick: (TemplatePick) -> Unit) {
    val catalog = model.g.catalog
    val yours = lib.templates.filter(::alive)
    val kind = when (picked) {
        is TemplatePick.Yours -> "yours"
        is TemplatePick.FromStarter -> "starter"
        is TemplatePick.New -> "new"
    }
    Text("The apps it has.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Tabs(listOf("yours" to "Yours", "starter" to "Starters", "new" to "New"), kind) {
        pick(
            when (it) {
                "yours" -> yours.firstOrNull()?.let { t -> TemplatePick.Yours(t.str("id")) } ?: picked
                "starter" -> TemplatePick.FromStarter(catalog.starters.first())
                else -> TemplatePick.New("", emptyList())
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    when (picked) {
        is TemplatePick.Yours -> yours.forEach { t ->
            Choice(picked.id == t.str("id"), t.str("name"), catalog.appNames(strings(t["apps"]))) { pick(TemplatePick.Yours(t.str("id"))) }
        }
        is TemplatePick.FromStarter -> catalog.starters.forEach { s ->
            Choice(picked.starter.id == s.id, s.name, s.description) { pick(TemplatePick.FromStarter(s)) }
        }
        is TemplatePick.New -> {
            OutlinedTextField(picked.name, { pick(picked.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Its name") }, singleLine = true)
            Section("Builds on", "One of yours whose apps it all has, or nothing.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(picked.parent == null, { pick(picked.copy(parent = null)) }, { Text("Nothing") })
                yours.forEach { t ->
                    FilterChip(picked.parent == t.str("id"), {
                        val apps = (picked.apps + strings(t["apps"])).distinct()
                        pick(picked.copy(parent = t.str("id"), apps = apps))
                    }, { Text(t.str("name")) })
                }
            }
            Section("Apps")
            catalog.apps.forEach { a ->
                Row(Modifier.fillMaxWidth().clickable {
                    pick(picked.copy(apps = if (a.id in picked.apps) picked.apps - a.id else picked.apps + a.id))
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(a.id in picked.apps, null)
                    Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                        Text(a.name)
                        Text(a.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionsStep(model: AppModel, lib: Library, template: TemplatePick, picked: ExtensionsPick, pick: (ExtensionsPick) -> Unit) {
    val catalog = model.g.catalog
    val apps = appsOf(template, lib) ?: emptyList()
    // Extensions of yours made on this very template (an existing one only).
    val top = (template as? TemplatePick.Yours)?.let { Layers.chainOf(lib.lib(), it.id).lastOrNull()?.str("id") }
    val yours = lib.allExtensions.filter { alive(it) && top != null && it.str("templateId") == top }
    val kind = when (picked) {
        ExtensionsPick.None -> "none"
        is ExtensionsPick.Yours -> "yours"
        is ExtensionsPick.New -> "new"
    }
    Text("How its apps are set up: WadBrowser's web apps, VS Code's extensions, command-line tools. Optional.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    val options = listOfNotNull("none" to "None", ("yours" to "Yours").takeIf { yours.isNotEmpty() }, "new" to "New")
    Tabs(options, kind) {
        pick(
            when (it) {
                "yours" -> ExtensionsPick.Yours(yours.first().str("id"))
                "new" -> ExtensionsPick.New("")
                else -> ExtensionsPick.None
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    when (picked) {
        ExtensionsPick.None -> Text("The apps as they come.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        is ExtensionsPick.Yours -> yours.forEach { e ->
            val what = "${(e["webapps"] as? JsonArray)?.size ?: 0} web apps · " +
                "${strings(e["extensions"]).size} extensions · ${strings(e["tools"]).size} tools"
            Choice(picked.id == e.str("id"), e.str("name"), what) { pick(ExtensionsPick.Yours(e.str("id"))) }
        }
        is ExtensionsPick.New -> NewExtensions(model, apps, picked, pick)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewExtensions(model: AppModel, apps: List<String>, e: ExtensionsPick.New, pick: (ExtensionsPick) -> Unit) {
    val catalog = model.g.catalog
    OutlinedTextField(e.name, { pick(e.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Its name") }, singleLine = true)
    if ("wadbrowser" in apps) {
        Section("WadBrowser", "Web apps open in their own windows.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Focus (no address bar)", Modifier.weight(1f))
            Switch(e.focus, { pick(e.copy(focus = it)) })
        }
        e.webapps.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.name)
                    Text(w.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { pick(e.copy(webapps = e.webapps - w)) }) { Text("Remove") }
            }
        }
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Web app's name") }, singleLine = true)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Its address") }, singleLine = true)
        OutlinedButton(
            enabled = name.isNotBlank() && url.startsWith("https://") && url.length > 9,
            onClick = {
                val id = Layers.webappId(name)
                if (e.webapps.none { it.id == id }) pick(e.copy(webapps = e.webapps + WebApp(id, name.trim(), url.trim())))
                name = ""
                url = "https://"
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Add web app") }
    }
    if ("vscode" in apps) {
        Section("VS Code extensions")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            catalog.extensions.forEach { x ->
                FilterChip(x.id in e.extensions, {
                    pick(e.copy(extensions = if (x.id in e.extensions) e.extensions - x.id else e.extensions + x.id))
                }, { Text(x.name) })
            }
        }
    }
    Section("Tools", "Command-line kits.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        catalog.tools.forEach { x ->
            FilterChip(x.id in e.tools, { pick(e.copy(tools = if (x.id in e.tools) e.tools - x.id else e.tools + x.id)) }, { Text(x.name) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookStep(model: AppModel, lib: Library, c: Customization, pick: (Customization) -> Unit) {
    val catalog = model.g.catalog
    OutlinedTextField(c.name, { pick(c.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Its name") }, singleLine = true)
    OutlinedTextField(c.description, { pick(c.copy(description = it)) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("What it's for (optional)") })
    Section("Wallpaper")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        catalog.presets.forEach { p ->
            val on = p.wallpaper == c.wallpaper
            Card(
                Modifier.width(96.dp).aspectRatio(16f / 9f).clickable { pick(c.copy(wallpaper = p.wallpaper)) },
                border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) { Wallpaper(p.wallpaper, catalog, Modifier.fillMaxSize()) }
        }
    }
    Section("Projects", "GitHub repos cloned into it, each on its desktop.")
    if (lib.repos.isEmpty()) {
        Text("No repos in your account yet: sign in to GitHub on one of your machines.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    lib.repos.forEach { r ->
        val on = c.projects.any { it.repo.equals(r.fullName, ignoreCase = true) }
        Row(Modifier.fillMaxWidth().clickable {
            pick(c.copy(projects = if (on) c.projects.filterNot { it.repo.equals(r.fullName, true) } else Builder.addRepo(c.projects, r.fullName)))
        }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, null)
            Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                Text(r.fullName)
                if (r.description.isNotEmpty()) Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Window title bars", Modifier.weight(1f))
        Switch(c.titleBars, { pick(c.copy(titleBars = it)) })
    }
}

@Composable
private fun ReviewStep(model: AppModel, lib: Library, t: TemplatePick, e: ExtensionsPick, c: Customization, problems: List<String>) {
    val catalog = model.g.catalog
    Wallpaper(c.wallpaper, catalog, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    Spacer(Modifier.height(12.dp))
    Text(c.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (c.description.isNotBlank()) Text(c.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    val tname = when (t) {
        is TemplatePick.Yours -> Layers.chainOf(lib.lib(), t.id).lastOrNull()?.str("name") ?: "?"
        is TemplatePick.FromStarter -> t.starter.name
        is TemplatePick.New -> t.name.ifBlank { "New template" }
    }
    Fact("Template", "$tname: ${catalog.appNames(appsOf(t, lib) ?: emptyList())}")
    Fact("Extensions", when (e) {
        ExtensionsPick.None -> "None"
        is ExtensionsPick.Yours -> lib.allExtensions.firstOrNull { it.str("id") == e.id }?.str("name") ?: "?"
        is ExtensionsPick.New -> "${e.name.ifBlank { "New" }}: ${e.webapps.size} web apps, ${e.extensions.size} extensions, ${e.tools.size} tools"
    })
    Fact("Projects", c.projects.joinToString(", ") { it.repo }.ifEmpty { "None" })
    if (problems.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
    } else {
        Spacer(Modifier.height(12.dp))
        Text("Saved to your account. Then build it on one of your machines.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

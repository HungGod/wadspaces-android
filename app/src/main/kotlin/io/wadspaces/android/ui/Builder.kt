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
import io.wadspaces.android.MachineView
import io.wadspaces.core.board.Layers
import io.wadspaces.core.board.Sync
import io.wadspaces.core.builder.Builder
import io.wadspaces.core.builder.Catalog
import io.wadspaces.core.builder.Customization
import io.wadspaces.core.builder.ExtensionsPick
import io.wadspaces.core.builder.Setup
import io.wadspaces.core.builder.TemplatePick
import io.wadspaces.core.builder.WebApp
import io.wadspaces.core.library.Library
import io.wadspaces.core.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private val STEPS = listOf("Template", "Extensions", "Look", "Review")

private fun strings(v: JsonElement?) = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

/** The template picked: its id and its apps (none picked: null). */
private fun topOf(t: TemplatePick?, lib: Library): Pair<String, List<String>>? {
    val id = (t as? TemplatePick.Yours)?.id ?: return null
    val top = Layers.chainOf(lib.lib(), id).lastOrNull() ?: return null
    return top.str("id") to strings(top["apps"])
}

/** A setup's contents, in a line: its web apps, VS Code extensions and tools by name. */
private fun contents(s: Setup, catalog: Catalog): String {
    val parts = listOfNotNull(
        s.webapps.takeIf { it.isNotEmpty() }?.let { "WadBrowser: " + it.joinToString(", ") { w -> w.name } },
        "Focus".takeIf { s.focus && s.webapps.isEmpty() },
        s.extensions.takeIf { it.isNotEmpty() }?.let { "VS Code: " + it.joinToString(", ") { x -> catalog.extensions.firstOrNull { e -> e.id == x }?.name ?: x } },
        s.tools.takeIf { it.isNotEmpty() }?.let { "Tools: " + it.joinToString(", ") { x -> catalog.tools.firstOrNull { t -> t.id == x }?.name ?: x } },
    )
    return parts.joinToString(" · ").ifEmpty { "Nothing set up" }
}

/**
 * The phone's builder: one new wadspace, a step at a time, for one of your
 * machines. Its template is one already built there; its Extensions none,
 * one of your setups (as it is, or copied onto this template), or set up
 * by hand; then its look and projects. Saved as the board would save it,
 * then built on that machine.
 */
@Composable
fun BuilderScreen(model: AppModel, saved: (id: String, machine: String?) -> Unit) {
    val state by model.library.collectAsState()
    val machines by model.machines.collectAsState()
    val lib = (state as? LibState.Ready)?.lib ?: return Centered("Your library isn't here yet.")
    val catalog = model.g.catalog
    var step by remember { mutableStateOf(0) }
    // Machines whose heartbeat lists their images; the ones that trust this phone first.
    val targets = machines.filter { it.machine.layers.isNotEmpty() }.sortedBy { !it.ready }
    var machineId by remember { mutableStateOf<String?>(null) }
    val target: MachineView? = targets.firstOrNull { it.machine.id == machineId } ?: targets.firstOrNull()
    var template by remember { mutableStateOf<TemplatePick?>(null) }
    var extensions by remember { mutableStateOf<ExtensionsPick>(ExtensionsPick.None) }
    // Which of your setups it came from ("hand": set up by hand).
    var source by remember { mutableStateOf<String?>(null) }
    var look by remember { mutableStateOf(Customization("", wallpaper = catalog.presets.getOrNull(1)?.wallpaper ?: Sync.blankWallpaper())) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val problems = template?.let { Builder.problems(it, extensions, look, lib.lib(), catalog) } ?: listOf("Pick a template.")
    val canNext = when (step) {
        0 -> template != null
        else -> true
    }

    Column(Modifier.fillMaxSize()) {
        PageHead("New wadspace", "Step ${step + 1} of 4: ${STEPS[step]}")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEPS.indices.forEach { i ->
                Card(
                    Modifier.weight(1f).height(4.dp),
                    shape = RoundedCornerShape(2.dp),
                    colors = CardDefaults.cardColors(containerColor = if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                ) {}
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            when (step) {
                0 -> TemplateStep(model, lib, targets, target, template, pickMachine = {
                    machineId = it
                    template = null
                    extensions = ExtensionsPick.None
                    source = null
                }) {
                    template = it
                    extensions = ExtensionsPick.None
                    source = null
                }
                1 -> ExtensionsStep(model, lib, template, extensions, source) { pick, from ->
                    extensions = pick
                    source = from
                }
                2 -> LookStep(model, lib, look) { look = it }
                else -> ReviewStep(model, lib, template, extensions, look, problems, target)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (step < 3) {
                Button(enabled = canNext, onClick = { step++ }) { Text("Next") }
            } else if (saving) {
                CircularProgressIndicator()
            } else {
                val build = target?.takeIf { it.ready && it.machine.online }
                Button(enabled = problems.isEmpty(), onClick = {
                    error = null
                    saving = true
                    scope.launch {
                        try {
                            val id = model.save(template!!, extensions, look)
                            saved(id, build?.machine?.id)
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't save it."
                        }
                        saving = false
                    }
                }) { Text(if (build != null) "Save and build on ${build.machine.name}" else "Save") }
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
private fun Choice(selected: Boolean, title: String, sub: String, note: String? = null, onClick: () -> Unit) {
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
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateStep(
    model: AppModel,
    lib: Library,
    targets: List<MachineView>,
    target: MachineView?,
    picked: TemplatePick?,
    pickMachine: (String) -> Unit,
    pick: (TemplatePick) -> Unit,
) {
    val catalog = model.g.catalog
    if (target == null) {
        Text(
            "None of your machines has anything built yet. Build a wadspace on one from WadSpaces there first: its templates are what this builds on.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text("For", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        targets.forEach { v -> FilterChip(v.machine.id == target.machine.id, { pickMachine(v.machine.id) }, { Text(v.machine.name) }) }
    }
    if (!target.ready) {
        Text(
            "${target.machine.name} doesn't trust this phone yet: you can save it now and build it there later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Section("Template", "The ones built on ${target.machine.name}: their apps are what it has.")
    val built = Builder.templatesOn(lib, target.machine.layers)
    if (built.isEmpty()) {
        Text("Nothing of yours is built on ${target.machine.name} yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    built.forEach { t ->
        val id = t.str("id")
        Choice((picked as? TemplatePick.Yours)?.id == id, t.str("name"), catalog.appNames(strings(t["apps"]))) { pick(TemplatePick.Yours(id)) }
    }
}

@Composable
private fun ExtensionsStep(
    model: AppModel,
    lib: Library,
    template: TemplatePick?,
    picked: ExtensionsPick,
    source: String?,
    pick: (ExtensionsPick, String?) -> Unit,
) {
    val catalog = model.g.catalog
    val (tid, apps) = topOf(template, lib) ?: return Text("Pick a template first.")
    val setups = Builder.setups(lib, tid).filterNot { it.empty }
    Text("How its apps are set up: WadBrowser's web apps, VS Code's extensions, command-line tools.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Choice(picked == ExtensionsPick.None, "None", "The apps as they come.") { pick(ExtensionsPick.None, null) }
    if (setups.isNotEmpty()) Section("From your wadspaces")
    setups.forEach { s ->
        val left = Builder.leftOut(s, apps)
        val note = when {
            s.templateId == tid -> "Made for this template: used as it is"
            left.isNotEmpty() -> "Copied onto this template, without its ${left.joinToString(" and ")}"
            else -> "Copied onto this template"
        }
        val used = if (s.usedBy.isEmpty()) "" else "Used by ${s.usedBy.joinToString(", ")} · "
        Choice(source == s.id, s.name, used + contents(s, catalog), note) { pick(Builder.pickFrom(s, tid, apps), s.id) }
    }
    Section("Or")
    Choice(source == "hand", "Set up by hand", "Pick web apps, extensions and tools yourself.") { pick(ExtensionsPick.New(""), "hand") }
    when (picked) {
        is ExtensionsPick.Yours -> TextButton(onClick = {
            val s = setups.firstOrNull { it.id == picked.id } ?: return@TextButton
            pick(Builder.copyOnto(s, apps), "hand")
        }) { Text("Change a copy of it") }
        is ExtensionsPick.New -> {
            Spacer(Modifier.height(8.dp))
            NewExtensions(model, apps, setups, picked) { pick(it, source ?: "hand") }
        }
        ExtensionsPick.None -> {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewExtensions(model: AppModel, apps: List<String>, setups: List<Setup>, e: ExtensionsPick.New, pick: (ExtensionsPick.New) -> Unit) {
    val catalog = model.g.catalog
    OutlinedTextField(e.name, { pick(e.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Its name") }, singleLine = true)
    if ("wadbrowser" in apps) {
        Section("WadBrowser", "Web apps open in their own windows.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Focus (no address bar)", Modifier.weight(1f))
            Switch(e.focus, { pick(e.copy(focus = it)) })
        }
        val withApps = setups.filter { it.webapps.isNotEmpty() }
        if (withApps.isNotEmpty()) {
            Text("Add the web apps of:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                withApps.forEach { s ->
                    val all = s.webapps.all { w -> e.webapps.any { it.id == w.id } }
                    FilterChip(all, { pick(e.copy(webapps = (e.webapps + s.webapps).distinctBy { it.id })) }, { Text(s.name) })
                }
            }
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
        val withExt = setups.filter { it.extensions.isNotEmpty() }
        if (withExt.isNotEmpty()) {
            Text("Add the extensions of:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                withExt.forEach { s ->
                    FilterChip(e.extensions.containsAll(s.extensions), { pick(e.copy(extensions = (e.extensions + s.extensions).distinct())) }, { Text(s.name) })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            catalog.extensions.forEach { x ->
                FilterChip(x.id in e.extensions, {
                    pick(e.copy(extensions = if (x.id in e.extensions) e.extensions - x.id else e.extensions + x.id))
                }, { Text(x.name) })
            }
        }
    }
    Section("Tools", "Command-line kits.")
    val withTools = setups.filter { it.tools.isNotEmpty() }
    if (withTools.isNotEmpty()) {
        Text("Add the tools of:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            withTools.forEach { s ->
                FilterChip(e.tools.containsAll(s.tools), { pick(e.copy(tools = (e.tools + s.tools).distinct())) }, { Text(s.name) })
            }
        }
    }
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
private fun ReviewStep(
    model: AppModel,
    lib: Library,
    t: TemplatePick?,
    e: ExtensionsPick,
    c: Customization,
    problems: List<String>,
    target: MachineView?,
) {
    val catalog = model.g.catalog
    Wallpaper(c.wallpaper, catalog, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    Spacer(Modifier.height(12.dp))
    Text(c.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (c.description.isNotBlank()) Text(c.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    val top = (t as? TemplatePick.Yours)?.let { Layers.chainOf(lib.lib(), it.id).lastOrNull() }
    Fact("Template", top?.let { "${it.str("name")}: ${catalog.appNames(strings(it["apps"]))}" } ?: "None")
    Fact("Extensions", when (e) {
        ExtensionsPick.None -> "None"
        is ExtensionsPick.Yours -> lib.allExtensions.firstOrNull { it.str("id") == e.id }?.str("name") ?: "?"
        is ExtensionsPick.New -> "${e.name.ifBlank { "New" }}: ${e.webapps.size} web apps, ${e.extensions.size} extensions, ${e.tools.size} tools"
    })
    Fact("Projects", c.projects.joinToString(", ") { it.repo }.ifEmpty { "None" })
    target?.let { Fact("For", it.machine.name) }
    Spacer(Modifier.height(12.dp))
    if (problems.isNotEmpty()) {
        problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
    } else if (target?.ready == true && target.machine.online) {
        Text("Saved to your account, then built on ${target.machine.name}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("Saved to your account; build it once a machine trusts this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

package io.wadspaces.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.wadspaces.android.AppModel
import io.wadspaces.core.board.BPayload
import io.wadspaces.core.board.BTemplate
import io.wadspaces.core.board.BWadspace
import io.wadspaces.core.board.Board
import io.wadspaces.core.board.Op
import io.wadspaces.core.board.Pick
import io.wadspaces.core.board.Where
import io.wadspaces.core.board.Which
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// The editor's Wadspaces tab (design B's buckets, full screen): this
// wadspace's Customizations and Templates to edit, and above them the other
// wadspaces' stacks, every part of which comes in here: a tap adds it, a
// long press drags it to its place (a template onto a template goes before
// it, an app into it).

/** What dragging or tapping `p` brings in, in words. */
private fun labelOf(model: AppModel, board: Board, p: BPayload): String {
    val w = p.source?.let(board::get)
    val name = w?.name?.ifEmpty { "Untitled" } ?: ""
    return when (p) {
        is BPayload.Wadspace -> "all of $name"
        is BPayload.Templates -> w?.templates?.filter { it.id in p.ids }?.joinToString { it.name }?.ifEmpty { null } ?: "the templates"
        is BPayload.Bucket -> if (p.which == Which.Customs) "$name's customizations" else "$name's templates"
        is BPayload.Project -> w?.let { projectsOf(it).firstOrNull { o -> text(o, "id") == p.id } }?.let { text(it, "as") } ?: "the project"
        is BPayload.Background -> "the background"
        is BPayload.App -> model.g.packages.appName(p.app)
        is BPayload.Extra -> w?.templates?.firstOrNull { it.id == p.template }?.apps?.firstOrNull { it.app == p.app }
            ?.extras?.firstOrNull { it.id == p.id }?.let { extraName(model, p.app, it) } ?: p.id
        is BPayload.CatalogApp -> model.g.packages.appName(p.app)
        is BPayload.CatalogExtra -> extraName(model, p.app, p.extra)
        is BPayload.Texture -> TEXTURE_NAMES[p.value] ?: p.value
        is BPayload.CatalogProject -> "the project"
    }
}

private fun keyCount(w: BWadspace): Int {
    val sec = w.customization["secrets"] as? JsonObject ?: return 0
    return ((sec["bySlot"] as? JsonObject)?.size ?: 0) + ((sec["groups"] as? JsonArray)?.count { (it as? JsonObject)?.get("key") != null } ?: 0)
}

@Composable
internal fun StackTab(model: AppModel, board: Board, rev: Int, w: BWadspace, ed: Edits, modifier: Modifier = Modifier) {
    var sources by rememberSaveable { mutableStateOf(true) }
    // Its machine's first, then the others', each in board order.
    val others = board.wadspaces.filter { !it.deleted && it.id != w.id }.sortedBy { if (it.machine == w.machine) 0 else 1 }
    fun pull(p: BPayload, at: Where = Where()) {
        val moved = p.source == w.id
        ed.done("${if (moved) "Moved" else "Added"} ${labelOf(model, board, p)}", Op.Drop(p, w.id, at))
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { sources = !sources }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("FROM YOUR WADSPACES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sources) {
                    Text(
                        "Tap a part to add it here · long-press to drag it into place",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(if (sources) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (sources) "Hide them" else "Show them")
        }
        if (sources) {
            if (others.isEmpty()) {
                Text(
                    "Your other wadspaces show here, to bring their parts into this one.",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(
                    Modifier.fillMaxWidth().height(210.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(others, key = { it.id }) { o -> SourceStack(model, board, rev, o) { p -> pull(p) } }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 10.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CustomsBucket(rev, w, ed) { p -> pull(p) }
            TemplatesBucket(model, rev, w, ed, ::pull)
        }
    }
}

/** A part of another wadspace: a tap brings it in, a long press drags it. */
@Composable
private fun Modifier.part(label: String, payload: BPayload, pull: (BPayload) -> Unit): Modifier =
    boardSource(label, payload = { payload }, onTap = { pull(payload) })

/** The + a part that comes in by a tap wears. */
@Composable
private fun AddMark(tint: Color, size: Dp = 16.dp) = Icon(Icons.Filled.Add, null, Modifier.size(size), tint = tint)

/** Another wadspace's stack: all of it, its customizations or templates, each template, app, background and project. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceStack(model: AppModel, board: Board, @Suppress("UNUSED_PARAMETER") rev: Int, o: BWadspace, pull: (BPayload) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val blue = MaterialTheme.colorScheme.tertiary
    val texture = textureOf(o)
    val bmp = texture?.let { textureBitmap(it) }
    val name = o.name.ifEmpty { "Untitled" }
    Card(Modifier.width(220.dp).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(
            Modifier.fillMaxWidth().height(46.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .part(name, BPayload.Wadspace(o.id), pull),
        ) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "on ${board.machines.firstOrNull { it.id == o.machine }?.name?.ifEmpty { null } ?: o.machine} · all of it",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AddMark(Color.White)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceBucket("Customizations", accent, BPayload.Bucket(o.id, Which.Customs), pull) {
                val projects = projectsOf(o)
                if (texture == null && projects.isEmpty()) {
                    Text("Nothing yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (texture != null) {
                        PartChip(TEXTURE_NAMES[texture] ?: texture, accent, Modifier.part("the background", BPayload.Background(o.id), pull)) {
                            if (bmp != null) Image(bmp, null, Modifier.size(width = 18.dp, height = 12.dp).clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.Crop)
                        }
                    }
                    projects.forEach { p ->
                        PartChip(text(p, "as"), accent, Modifier.part(text(p, "as"), BPayload.Project(o.id, text(p, "id")), pull)) {
                            iconBitmap("folder")?.let { Image(it, null, Modifier.size(14.dp)) }
                        }
                    }
                }
            }
            SourceBucket("Templates", accent, BPayload.Bucket(o.id, Which.Templates), pull) {
                if (o.templates.isEmpty()) Text("None yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                o.templates.forEach { t ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(blue.copy(alpha = 0.08f))
                            .border(1.dp, blue.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().part(t.name, BPayload.Templates(o.id, listOf(t.id)), pull), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, Modifier.weight(1f), color = blue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AddMark(blue)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            t.apps.forEach { e ->
                                val app = model.g.packages.appName(e.app)
                                PartChip(app + if (e.extras.isNotEmpty()) " +${e.extras.size}" else "", blue, Modifier.part(app, BPayload.App(o.id, t.id, e.app), pull)) {
                                    iconBitmap("app-${e.app}")?.let { Image(it, null, Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A bucket of another wadspace's: its name (a tap brings all of it in) over its parts. */
@Composable
private fun SourceBucket(title: String, color: Color, payload: BPayload, pull: (BPayload) -> Unit, parts: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().part(title, payload, pull), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), Modifier.weight(1f), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            AddMark(color, 14.dp)
        }
        parts()
    }
}

@Composable
private fun PartChip(label: String, color: Color, modifier: Modifier, picture: @Composable () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        picture()
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AddMark(color, 12.dp)
    }
}

/** This wadspace's Customizations: its background, projects and keys; takes another's (or parts of them) dropped on it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomsBucket(@Suppress("UNUSED_PARAMETER") rev: Int, w: BWadspace, ed: Edits, pull: (BPayload) -> Unit) {
    var hot by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    val texture = textureOf(w)
    val projects = projectsOf(w)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (hot) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.5.dp, accent.copy(alpha = 0.6f), shape)
            .boardTarget(
                accepts = { p -> p.source != w.id && (p is BPayload.Background || p is BPayload.Project || (p is BPayload.Bucket && p.which == Which.Customs)) },
                onHot = { hot = it },
                drop = pull,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("CUSTOMIZATIONS", color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bmp = texture?.let { textureBitmap(it) }
            Box(Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Background", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(texture?.let { TEXTURE_NAMES[it] ?: it } ?: "None: pick one in Customization", style = MaterialTheme.typography.bodyMedium)
            }
            if (texture != null) {
                IconButton(onClick = { ed.done("Took the background out", Op.Delete(listOf(Pick.Background(w.id)))) }) {
                    Icon(Icons.Filled.Close, "Take the background out")
                }
            }
        }
        Text("Projects", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (projects.isEmpty()) {
            Text("None: add GitHub repositories in Customization", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            projects.forEach { p ->
                val name = text(p, "as")
                InputChip(
                    selected = false,
                    onClick = { ed.done("Took $name out", Op.Delete(listOf(Pick.Project(w.id, text(p, "id"))))) },
                    label = { Text(name) },
                    leadingIcon = { iconBitmap("folder")?.let { Image(it, null, Modifier.size(18.dp)) } },
                    trailingIcon = { Icon(Icons.Filled.Close, "Take $name out", Modifier.size(14.dp)) },
                )
            }
        }
        val keys = keyCount(w)
        Text(
            when (keys) {
                0 -> "No keys yet: they're set on the machine"
                1 -> "1 key, set on the machine"
                else -> "$keys keys, set on the machine"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** This wadspace's Templates: each to rename, take out, or drag to another place; apps and extras to take out; another's dropped in. */
@Composable
private fun TemplatesBucket(model: AppModel, rev: Int, w: BWadspace, ed: Edits, pull: (BPayload, Where) -> Unit) {
    var hot by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BTemplate?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (hot) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.5.dp, accent.copy(alpha = 0.6f), shape)
            .boardTarget(
                accepts = { p -> p is BPayload.Templates || (p is BPayload.Bucket && p.which == Which.Templates && p.source != w.id) },
                onHot = { hot = it },
            ) { p -> pull(p, Where()) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("TEMPLATES", color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        w.templates.forEachIndexed { i, t -> TemplateEditCard(model, rev, w, t, i, ed, pull, rename = { renaming = t }) }
        NewTemplateSlot(w, pull)
    }
    renaming?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename the template") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && name != t.name) ed.edit(Op.RenameTemplate(w.id, t.id, name.trim()))
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

/** A template of this wadspace: a long press on its name drags it; an app or a template dropped on it goes into it, or before it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditCard(
    model: AppModel,
    @Suppress("UNUSED_PARAMETER") rev: Int,
    w: BWadspace,
    t: BTemplate,
    i: Int,
    ed: Edits,
    pull: (BPayload, Where) -> Unit,
    rename: () -> Unit,
) {
    var hot by remember { mutableStateOf(false) }
    val blue = MaterialTheme.colorScheme.tertiary
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (hot) blue.copy(alpha = 0.2f) else blue.copy(alpha = 0.08f))
            .border(1.dp, blue.copy(alpha = 0.45f), shape)
            .boardTarget(
                accepts = { p ->
                    when (p) {
                        is BPayload.App, is BPayload.Extra -> !(p.source == w.id && templateOf(p) == t.id)
                        is BPayload.Templates -> !(p.source == w.id && p.ids == listOf(t.id))
                        else -> false
                    }
                },
                onHot = { hot = it },
            ) { p -> pull(p, if (p is BPayload.Templates) Where(at = i) else Where(template = t.id)) }
            .padding(start = 10.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().boardSource(t.name, payload = { BPayload.Templates(w.id, listOf(t.id)) }, onTap = rename),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.name, Modifier.weight(1f), color = blue, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = rename, Modifier.size(36.dp)) { Icon(Icons.Filled.Edit, "Rename ${t.name}", Modifier.size(18.dp)) }
            IconButton(onClick = { ed.done("Took ${t.name} out", Op.Delete(listOf(Pick.Template(w.id, t.id)))) }, Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, "Take ${t.name} out", Modifier.size(20.dp))
            }
        }
        if (t.apps.isEmpty()) {
            Text(
                "No apps: add them from Apps, or drop one here",
                Modifier.padding(end = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        t.apps.forEach { e ->
            val name = model.g.packages.appName(e.app)
            Row(
                Modifier.fillMaxWidth().boardSource(name, payload = { BPayload.App(w.id, t.id, e.app) }, onTap = {}),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp)) { iconBitmap("app-${e.app}")?.let { Image(it, null, Modifier.fillMaxSize()) } }
                Text(name, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Medium)
                if (e.app == "wadbrowser" && e.mode == "focus") {
                    Text("Focus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { ed.done("Took $name out", Op.RemoveApp(w.id, t.id, e.app)) }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Take $name out", Modifier.size(18.dp))
                }
            }
            if (e.extras.isNotEmpty()) {
                FlowRow(Modifier.padding(start = 32.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    e.extras.forEach { x ->
                        val xn = extraName(model, e.app, x)
                        InputChip(
                            selected = false,
                            onClick = { ed.done("Took $xn out", Op.RemoveExtra(w.id, t.id, e.app, x.id)) },
                            label = { Text(xn) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Take $xn out", Modifier.size(14.dp)) },
                        )
                    }
                }
            }
        }
    }
}

/** Where an app dropped gets a template of its own. */
@Composable
private fun NewTemplateSlot(w: BWadspace, pull: (BPayload, Where) -> Unit) {
    var hot by remember { mutableStateOf(false) }
    val blue = MaterialTheme.colorScheme.tertiary
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(if (hot) blue.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, blue.copy(alpha = if (hot) 0.8f else 0.3f), shape)
            .boardTarget(
                accepts = { p -> p is BPayload.App || p is BPayload.Extra },
                onHot = { hot = it },
            ) { p -> pull(p, Where(newTemplate = true)) }
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (w.templates.isEmpty()) "Drop an app here, or add one from Apps" else "Drop an app here for a template of its own",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

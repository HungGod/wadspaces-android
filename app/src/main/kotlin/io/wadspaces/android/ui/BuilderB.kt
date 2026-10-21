package io.wadspaces.android.ui

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wadspaces.android.AppModel
import io.wadspaces.android.LibState
import io.wadspaces.android.MachineView
import io.wadspaces.core.board.BExtra
import io.wadspaces.core.board.BPayload
import io.wadspaces.core.board.BTemplate
import io.wadspaces.core.board.BWadspace
import io.wadspaces.core.board.Board
import io.wadspaces.core.board.Layers
import io.wadspaces.core.board.NEW_NAME
import io.wadspaces.core.board.Op
import io.wadspaces.core.board.Pick
import io.wadspaces.core.board.V4
import io.wadspaces.core.board.Where
import io.wadspaces.core.builder.Builder
import io.wadspaces.core.library.Library
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Design B's Builder on the phone (wadspaces' ui-mockup-pieces B-Buckets,
// as the WadSpaces Client has it): one machine's version 4 wadspaces, the
// machines along the top. A long press drags a wadspace (onto a machine:
// a copy there) or a template and those under it ("Just this one": only
// it) onto another wadspace (a copy) or its own (moved to the end). A tap
// opens the wadspace's sheet: what's in it, the apps to add, and its
// customization. Select picks wadspaces, for Build and Delete.

/** Web apps worth offering even when no wadspace has them (the client's). */
private val COMMON = listOf(
    BExtra("github", "GitHub", "https://github.com"),
    BExtra("gmail", "Gmail", "https://mail.google.com"),
    BExtra("google-drive", "Google Drive", "https://drive.google.com"),
    BExtra("claude", "Claude", "https://claude.ai"),
    BExtra("youtube", "YouTube", "https://youtube.com"),
)

private val TEXTURE_NAMES = mapOf(
    "carbon" to "Carbon", "marble" to "Marble", "wood" to "Woodgrain", "contour" to "Contour", "grid" to "Grid",
    "terrazzo" to "Terrazzo", "honeycomb" to "Honeycomb", "seigaiha" to "Seigaiha", "brushed" to "Brushed metal",
    "camo" to "Camo", "stars" to "Stars", "halftone" to "Halftone",
)

private fun textureOf(w: BWadspace): String? =
    (w.customization["wallpaper"] as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.content == "texture" }
        ?.let { (it["value"] as? JsonPrimitive)?.content }

private fun projectsOf(w: BWadspace): List<JsonObject> = (w.customization["projects"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun text(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

/** A drag on the board carries core's payload as its local state. */
private fun transfer(p: BPayload, label: String) = DragAndDropTransferData(ClipData.newPlainText("wadspaces", label), localState = p)

private fun payloadOf(e: DragAndDropEvent): BPayload? = e.toAndroidDragEvent().localState as? BPayload

/** Long-pressed, it's dragged (what `payload` gives); tapped, `onTap`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.boardSource(label: String, payload: () -> BPayload?, onTap: () -> Unit): Modifier {
    val p by rememberUpdatedState(payload)
    val tap by rememberUpdatedState(onTap)
    val l by rememberUpdatedState(label)
    return dragAndDropSource {
        detectTapGestures(
            onTap = { tap() },
            onLongPress = { p()?.let { startTransfer(transfer(it, l)) } },
        )
    }
}

/** Takes what `accepts` says it does; `hot` while something it takes is over it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.boardTarget(accepts: (BPayload) -> Boolean, onHot: (Boolean) -> Unit, drop: (BPayload) -> Unit): Modifier {
    val a by rememberUpdatedState(accepts)
    val d by rememberUpdatedState(drop)
    val h by rememberUpdatedState(onHot)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                h(false)
                val p = payloadOf(event)?.takeIf(a) ?: return false
                d(p)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) = h(true)
            override fun onExited(event: DragAndDropEvent) = h(false)
            override fun onEnded(event: DragAndDropEvent) = h(false)
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { e -> payloadOf(e)?.let(a) == true }, target = target)
}

/** How a wadspace stands on its machine, in a word. */
@Composable
private fun Tag(standing: String) {
    val (word, color) = when (standing) {
        "built" -> "Ready" to MaterialTheme.colorScheme.primary
        "update" -> "Update" to MaterialTheme.colorScheme.tertiary
        "building" -> "Building" to MaterialTheme.colorScheme.primary
        "never" -> "Not built" to MaterialTheme.colorScheme.secondary
        "blank" -> "Not made yet" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Not seen" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        word,
        Modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuilderBScreen(model: AppModel, open: (String) -> Unit) {
    val st by model.board.collectAsState()
    val machines by model.machines.collectAsState()
    val runs by model.runs.collectAsState()
    val libState by model.library.collectAsState()
    val state = st ?: return Centered(if (libState is LibState.Failed) "Couldn't read your library." else "Reading your wadspaces…")
    val board = state.board
    // Builds starting and ending change how wadspaces stand: read, so it's drawn again.
    @Suppress("UNUSED_VARIABLE") val drawn = state.rev + runs.size
    var shown by remember { mutableStateOf<String?>(null) }
    val here = shown?.takeIf { s -> board.machines.any { it.id == s } } ?: board.machines.firstOrNull()?.id
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var justOne by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun say(text: String, undo: Boolean = false, show: String? = null) {
        scope.launch {
            val r = snack.showSnackbar(text, actionLabel = if (undo) "Undo" else if (show != null) "Show" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) {
                if (undo) model.edit(Op.Undo) else shown = show
            }
        }
    }

    fun act(vararg ops: Op): Boolean {
        val why = model.edit(*ops)
        if (why != null) say(why)
        return why == null
    }

    /** A blank wadspace made on the machine shown (an empty template to fill in), and opened. */
    fun create() {
        val m = here ?: return
        val id = board.freeId(NEW_NAME)
        if (act(Op.Create(m))) editing = id
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Builder", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { model.edit(Op.Undo) }, enabled = board.canUndo) { Text("Undo") }
                TextButton(onClick = {
                    selecting = !selecting
                    picked = emptySet()
                }) { Text(if (selecting) "Done" else "Select") }
            }
            state.error?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = model::saveBoard) { Text("Retry") }
                }
            }
            MachineStrip(model, board, machines, here, picked) { m -> shown = m }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(justOne, { justOne = !justOne }, { Text("Just this one") })
                Text(
                    if (selecting) "Tap wadspaces to pick them" else "Long-press to drag · tap to edit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val row = here?.let(board::row) ?: emptyList()
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = if (selecting) 96.dp else 24.dp)) {
                items(row, key = { it.id }) { w ->
                    WadspaceCardB(
                        model, w, model.standing(w), selecting, w.id in picked, justOne,
                        tap = {
                            if (selecting) picked = if (w.id in picked) picked - w.id else picked + w.id else editing = w.id
                        },
                        dropped = { p ->
                            val into = w.name.ifEmpty { "it" }
                            if (act(Op.Drop(p, w.id))) say(if (p.source == w.id) "Moved in $into" else "Copied to $into", undo = true)
                        },
                    )
                }
                // A machine with none: a blank one to fill in, made when it's tapped.
                if (row.isEmpty() && here != null) item { BlankCard(::create) }
                if (row.isNotEmpty()) item {
                    OutlinedButton(onClick = ::create, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = here != null) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("New wadspace")
                    }
                }
            }
        }
        if (selecting && picked.isNotEmpty()) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picked.forEach(model::buildOnItsMachine) }, Modifier.weight(1f)) { Text("Build (${picked.size})") }
                    OutlinedButton(onClick = {
                        val n = picked.size
                        if (act(Op.Delete(picked.map { Pick.Wadspace(it) }))) say(if (n == 1) "Deleted it" else "Deleted $n wadspaces", undo = true)
                        picked = emptySet()
                    }, Modifier.weight(1f)) { Text("Delete (${picked.size})") }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = if (selecting && picked.isNotEmpty()) 80.dp else 8.dp))
    }

    editing?.takeIf { board.get(it)?.deleted == false }?.let { id ->
        WadspaceSheet(model, id, close = { editing = null }, open = open, say = { say(it, undo = true) })
    }
}

/** A blank wadspace (an empty template, an empty customization): made, and saved, when it's tapped. */
@Composable
private fun BlankCard(tap: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val blue = MaterialTheme.colorScheme.tertiary
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = tap),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.fillMaxWidth().height(76.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Text("Its background, projects and keys", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(blue.copy(alpha = 0.08f))
                    .border(1.dp, blue.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("Template", color = blue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                Text("No apps yet: tap to pick them", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(NEW_NAME, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Tag("blank")
            }
        }
    }
}

/** The machines along the top: tap one for its wadspaces; drop a wadspace on one to copy it there (picked ones too), anything else for a new one there. */
@Composable
private fun MachineStrip(model: AppModel, board: Board, views: List<MachineView>, here: String?, picked: Set<String>, show: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(board.machines, key = { it.id }) { m ->
            var hot by remember { mutableStateOf(false) }
            val v = views.firstOrNull { it.machine.id == m.id }
            val row = board.row(m.id)
            val copies = picked.filter { id -> board.get(id)?.machine != m.id }
            val whole = { p: BPayload -> p is BPayload.Wadspace }
            Card(
                Modifier.width(210.dp)
                    .boardTarget(
                        accepts = { p -> p.source != null && !(whole(p) && board.get(p.source!!)?.machine == m.id) },
                        onHot = { hot = it },
                    ) { p ->
                        if (whole(p)) {
                            val ws = p.source!!
                            val ids = (if (ws in picked) picked.toList() else listOf(ws)).filter { board.get(it)?.machine != m.id }
                            model.edit(Op.CopyTo(ids, m.id))
                        } else {
                            model.edit(Op.Fresh(p, m.id))
                        }
                        show(m.id)
                    }
                    .clickable { show(m.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (hot) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                border = if (m.id == here) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name.ifEmpty { m.id }, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${row.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (v?.machine?.online == true) "on" else "off")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                hot -> "Let go to copy it here"
                                v == null -> "Not seen"
                                v.machine.online -> v.status
                                else -> "Offline"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (copies.isNotEmpty() && m.id != here) {
                        TextButton(onClick = {
                            model.edit(Op.CopyTo(copies, m.id))
                            show(m.id)
                        }) { Text(if (copies.size == 1) "Copy ${board.get(copies[0])?.name} here" else "Copy ${copies.size} here", maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** A wadspace on the board: its texture with its name and how it stands, and its templates. */
@Composable
private fun WadspaceCardB(
    model: AppModel,
    w: BWadspace,
    standing: String,
    selecting: Boolean,
    picked: Boolean,
    justOne: Boolean,
    tap: () -> Unit,
    dropped: (BPayload) -> Unit,
) {
    var hot by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .boardTarget(
                // Its own apps go onto its templates; another's anywhere on it.
                accepts = { p -> p is BPayload.Templates || ((p is BPayload.App || p is BPayload.Extra || p is BPayload.Project || p is BPayload.Background) && p.source != w.id) },
                onHot = { hot = it },
                drop = dropped,
            ),
        colors = CardDefaults.cardColors(containerColor = if (hot) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(if (picked) 2.dp else 1.dp, if (picked) MaterialTheme.colorScheme.error else accent.copy(alpha = 0.45f)),
    ) {
        val texture = textureOf(w)
        val bmp = texture?.let { textureBitmap(it) }
        Box(
            Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(if (bmp == null) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                .boardSource(w.name, payload = { if (selecting) null else BPayload.Wadspace(w.id) }, onTap = tap),
        ) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(w.name.ifEmpty { "Untitled" }, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tag(standing)
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            w.templates.forEachIndexed { i, t ->
                TemplateRowB(model, w, t, i, justOne, selecting, tap)
            }
            if (w.templates.isEmpty()) {
                Text("No templates: tap it, then add apps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val projects = projectsOf(w)
            if (projects.isNotEmpty() || texture != null) {
                Text(
                    listOfNotNull(texture?.let { TEXTURE_NAMES[it] ?: it }).plus(projects.map { text(it, "as") }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A template: long-press drags it and those under it (or just it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateRowB(model: AppModel, w: BWadspace, t: BTemplate, i: Int, justOne: Boolean, selecting: Boolean, tap: () -> Unit) {
    var hot by remember { mutableStateOf(false) }
    val blue = MaterialTheme.colorScheme.tertiary
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (hot) blue.copy(alpha = 0.2f) else blue.copy(alpha = 0.08f))
            .border(1.dp, blue.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .boardTarget(
                accepts = { p -> (p is BPayload.App || p is BPayload.Extra) && !(p.source == w.id && templateOf(p) == t.id) },
                onHot = { hot = it },
            ) { p -> model.edit(Op.Drop(p, w.id, Where(template = t.id))) }
            .boardSource(t.name, payload = {
                if (selecting) null else BPayload.Templates(w.id, if (justOne) listOf(t.id) else w.templates.drop(i).map { it.id })
            }, onTap = tap)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(t.name, color = blue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        Text(
            t.apps.joinToString(", ") { e ->
                model.g.packages.appName(e.app) + if (e.extras.isNotEmpty()) " +${e.extras.size}" else ""
            }.ifEmpty { "No apps yet" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun templateOf(p: BPayload): String? = when (p) {
    is BPayload.App -> p.template
    is BPayload.Extra -> p.template
    else -> null
}

/** A wadspace open: what's in it, the apps to add, its customization; Build, Open, Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WadspaceSheet(model: AppModel, id: String, close: () -> Unit, open: (String) -> Unit, say: (String) -> Unit) {
    val st by model.board.collectAsState()
    val runs by model.runs.collectAsState()
    val libState by model.library.collectAsState()
    val board = st?.board ?: return
    val w = board.get(id) ?: return
    var tab by remember { mutableStateOf(0) }
    val run = runs[id]
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.name.ifEmpty { "Untitled" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tag(model.standing(w))
            }
            Text("On ${board.machines.firstOrNull { it.id == w.machine }?.name ?: w.machine}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.buildOnItsMachine(id) }, enabled = run == null || run.finished) { Text("Build") }
                OutlinedButton(onClick = {
                    close()
                    open(id)
                }) { Text("Open") }
                TextButton(onClick = {
                    if (model.edit(Op.Delete(listOf(Pick.Wadspace(id)))) == null) {
                        close()
                        say("Deleted ${w.name}")
                    }
                }) { Text("Delete") }
            }
            run?.let { r ->
                Text(
                    r.error ?: r.lines.lastOrNull() ?: r.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TabRow(selectedTabIndex = tab) {
                listOf("In it", "Apps", "Customization").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                when (tab) {
                    0 -> InIt(model, w)
                    1 -> AppsTab(model, board, w)
                    else -> CustomTab(model, w, (libState as? LibState.Ready)?.lib)
                }
            }
        }
    }
}

/** Its templates: rename, take out, and each app's and extra's ✕. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InIt(model: AppModel, w: BWadspace) {
    var renaming by remember { mutableStateOf<BTemplate?>(null) }
    if (w.templates.isEmpty()) Text("Nothing yet: add apps from Apps.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    w.templates.forEach { t ->
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { renaming = t }) { Icon(Icons.Filled.Edit, "Rename ${t.name}") }
                IconButton(onClick = { model.edit(Op.Delete(listOf(Pick.Template(w.id, t.id)))) }) { Icon(Icons.Filled.Close, "Take ${t.name} out") }
            }
            t.apps.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.g.packages.appName(e.app), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    TextButton(onClick = { model.edit(Op.RemoveApp(w.id, t.id, e.app)) }) { Text("Remove") }
                }
                if (e.extras.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        e.extras.forEach { x ->
                            AssistChip(
                                onClick = { model.edit(Op.RemoveExtra(w.id, t.id, e.app, x.id)) },
                                label = { Text(extraName(model, e.app, x)) },
                                trailingIcon = { Icon(Icons.Filled.Close, "Take it out", Modifier.size(14.dp)) },
                            )
                        }
                    }
                }
            }
        }
    }
    renaming?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename the template") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && name != t.name) model.edit(Op.RenameTemplate(w.id, t.id, name.trim()))
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

private fun extraName(model: AppModel, app: String, x: BExtra): String = when (app) {
    "vscode" -> model.g.catalog.extensions.firstOrNull { it.id == x.id }?.name ?: x.id
    V4.TERMINAL -> model.g.catalog.tools.firstOrNull { it.id == x.id }?.name ?: x.id
    else -> x.name ?: x.id
}

/** The catalog's apps, each with what it takes: tap to put it in (an app in a template of its own, an extra where its app is). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsTab(model: AppModel, board: Board, w: BWadspace) {
    val has = V4.appsOf(w.json()).associate { text(it, "app") to (it["extras"] as? JsonArray)?.map { x -> text(x as? JsonObject, "id") }.orEmpty() }
    var site by remember { mutableStateOf("") }
    val webApps = (board.wadspaces.flatMap { it.templates }.flatMap { it.apps }.filter { it.app == "wadbrowser" }.flatMap { it.extras } + COMMON).distinctBy { it.id }
    val cats = model.g.catalog.apps.map { it.category }.distinct()
    fun add(p: BPayload) = model.edit(Op.Drop(p, w.id))
    cats.forEach { cat ->
        Text(cat.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        model.g.catalog.apps.filter { it.category == cat }.forEach { a ->
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp)) {
                Row(Modifier.fillMaxWidth().clickable { add(BPayload.CatalogApp(a.id)) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (a.id in has) Text("In it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) else Icon(Icons.Filled.Add, "Add ${a.name}")
                }
                val offered: List<Pair<BExtra, String>> = when (a.id) {
                    "vscode" -> model.g.catalog.extensions.map { BExtra(it.id) to it.name }
                    V4.TERMINAL -> model.g.catalog.tools.map { BExtra(it.id) to it.name }
                    "wadbrowser" -> webApps.map { it to (it.name ?: it.id) }
                    else -> emptyList()
                }
                if (offered.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        offered.forEach { (x, name) ->
                            val inIt = has[a.id]?.contains(x.id) == true
                            FilterChip(inIt, { if (!inIt) add(BPayload.CatalogExtra(a.id, x)) }, { Text(name) })
                        }
                    }
                }
                if (a.id == "wadbrowser") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            site, { site = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a site: notion.so") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                siteWebApp(site)?.let { add(BPayload.CatalogExtra("wadbrowser", it)) }
                                site = ""
                            }),
                        )
                        IconButton(onClick = {
                            siteWebApp(site)?.let { add(BPayload.CatalogExtra("wadbrowser", it)) }
                            site = ""
                        }) { Icon(Icons.Filled.Add, "Add the site") }
                    }
                    if ("wadbrowser" in has) {
                        val focus = w.templates.flatMap { it.apps }.any { it.app == "wadbrowser" && it.mode == "focus" }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Focus mode: its web apps, no address bar", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(focus, { on -> model.edit(Op.SetMode(w.id, if (on) "focus" else "browser")) })
                        }
                    }
                }
            }
        }
    }
}

/** A site typed in ("notion.so"): a web app named after it (the client's site_webapp). */
private fun siteWebApp(text: String): BExtra? {
    val t = text.trim()
    val url = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    val host = url.substringAfter("://").split('/', '?', '#').first().substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.")
    if (host.isEmpty() || host.any { it.isWhitespace() } || '.' !in host) return null
    val first = host.substringBefore('.')
    val name = first.replaceFirstChar { it.uppercase() }
    return BExtra(Layers.webappId(name), name, url)
}

/** Its title, description, background (a texture), projects. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomTab(model: AppModel, w: BWadspace, lib: Library?) {
    var title by remember(w.id) { mutableStateOf(w.name) }
    var desc by remember(w.id) { mutableStateOf(text(JsonObject(w.customization), "description")) }
    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank() && title != w.name) model.edit(Op.Rename(w.id, title.trim())) }))
    OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Description") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { model.edit(Op.Describe(w.id, desc.trim())) }))
    val changed = (title.isNotBlank() && title.trim() != w.name) || desc.trim() != text(JsonObject(w.customization), "description")
    if (changed) {
        TextButton(onClick = {
            val ops = buildList {
                if (title.isNotBlank() && title.trim() != w.name) add(Op.Rename(w.id, title.trim()))
                if (desc.trim() != text(JsonObject(w.customization), "description")) add(Op.Describe(w.id, desc.trim()))
            }
            model.edit(*ops.toTypedArray())
        }) { Text("Save the name") }
    }

    Text("BACKGROUND", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    val now = textureOf(w)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        V4.TEXTURES.forEach { id ->
            val bmp = textureBitmap(id)
            Box(
                Modifier.size(width = 100.dp, height = 56.dp).clip(RoundedCornerShape(10.dp))
                    .border(if (id == now) 3.dp else 1.dp, if (id == now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .clickable { model.edit(Op.SetBackground(w.id, id)) },
            ) {
                if (bmp != null) Image(bmp, TEXTURE_NAMES[id], Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Text(
                    TEXTURE_NAMES[id] ?: id,
                    Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    Text("PROJECTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    val projects = projectsOf(w)
    projects.filter { text(it, "kind") != "github" }.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text(p, "as"), Modifier.weight(1f))
            TextButton(onClick = { model.edit(Op.ToggleProject(w.id, p)) }) { Text("Take out") }
        }
    }
    val repos = lib?.repos ?: emptyList()
    if (repos.isEmpty()) Text("Your GitHub repositories show here once a machine has read them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repos.forEach { r ->
            val have = projects.firstOrNull { text(it, "kind") == "github" && text(it, "repo").equals(r.fullName, ignoreCase = true) }
            FilterChip(have != null, {
                val p = have ?: JsonObject(mapOf(
                    "id" to JsonPrimitive(Builder.uid("project")), "kind" to JsonPrimitive("github"),
                    "repo" to JsonPrimitive(r.fullName), "as" to JsonPrimitive(r.name),
                ))
                model.edit(Op.ToggleProject(w.id, p))
            }, { Text(r.name) })
        }
    }
    Text(
        "Keys are set on the machine: WadSpaces there, Builder, Customization.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
}

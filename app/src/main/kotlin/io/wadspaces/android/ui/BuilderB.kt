package io.wadspaces.android.ui

import android.content.ClipData
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.wadspaces.android.BuildRun
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
// opens the wadspace full screen: its stack and the others' to bring parts
// in from (BuilderStack.kt), the apps to add and its customization, those
// two over its desktop as it will look (MockDesktop.kt). Select picks
// wadspaces, for Build and Delete.
//
// The board changes in place (the same wadspaces, edited), so what draws a
// part of it takes the board's `rev` too and reads it (Compose skips a part
// whose parameters haven't changed, and doesn't look at ones it never reads):
// a new one draws it again.

/** Web apps worth offering even when no wadspace has them (the client's). */
private val COMMON = listOf(
    BExtra("github", "GitHub", "https://github.com"),
    BExtra("gmail", "Gmail", "https://mail.google.com"),
    BExtra("google-drive", "Google Drive", "https://drive.google.com"),
    BExtra("claude", "Claude", "https://claude.ai"),
    BExtra("youtube", "YouTube", "https://youtube.com"),
)

internal val TEXTURE_NAMES = mapOf(
    "carbon" to "Carbon", "marble" to "Marble", "wood" to "Woodgrain", "contour" to "Contour", "grid" to "Grid",
    "terrazzo" to "Terrazzo", "honeycomb" to "Honeycomb", "seigaiha" to "Seigaiha", "brushed" to "Brushed metal",
    "camo" to "Camo", "stars" to "Stars", "halftone" to "Halftone",
)

internal fun textureOf(w: BWadspace): String? =
    (w.customization["wallpaper"] as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.content == "texture" }
        ?.let { (it["value"] as? JsonPrimitive)?.content }

internal fun projectsOf(w: BWadspace): List<JsonObject> = (w.customization["projects"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

internal fun text(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

/** A drag on the board carries core's payload as its local state. */
private fun transfer(p: BPayload, label: String) = DragAndDropTransferData(ClipData.newPlainText("wadspaces", label), localState = p)

private fun payloadOf(e: DragAndDropEvent): BPayload? = e.toAndroidDragEvent().localState as? BPayload

/** Long-pressed, it's dragged (what `payload` gives); tapped, `onTap`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.boardSource(label: String, payload: () -> BPayload?, onTap: () -> Unit): Modifier {
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
internal fun Modifier.boardTarget(accepts: (BPayload) -> Boolean, onHot: (Boolean) -> Unit, drop: (BPayload) -> Unit): Modifier {
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

/** Edits made from the open wadspace: why one couldn't be is said; `done` says what was, with Undo. */
internal class Edits(private val model: AppModel, private val say: (String, Boolean) -> Unit) {
    fun edit(vararg ops: Op): Boolean {
        val why = model.edit(*ops)
        if (why != null) say(why, false)
        return why == null
    }

    fun done(text: String, vararg ops: Op): Boolean {
        val made = edit(*ops)
        if (made) say(text, true)
        return made
    }
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
    val rev = state.rev
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

    val ed = remember { Edits(model) { text, undo -> say(text, undo) } }
    val opened = editing?.let(board::get)?.takeIf { !it.deleted }

    Box(Modifier.fillMaxSize()) {
        if (opened != null) {
            BackHandler { editing = null }
            WadspaceEditor(model, board, rev, opened, runs[opened.id], ed, close = { editing = null }, open = open)
        } else Column(Modifier.fillMaxSize()) {
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
            MachineStrip(model, board, rev, machines, here, picked) { m -> shown = m }
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
                        model, w, rev, model.standing(w), selecting, w.id in picked, justOne,
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
        if (opened == null && selecting && picked.isNotEmpty()) {
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
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = if (opened == null && selecting && picked.isNotEmpty()) 80.dp else 8.dp))
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
private fun MachineStrip(model: AppModel, board: Board, rev: Int, views: List<MachineView>, here: String?, picked: Set<String>, show: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(board.machines, key = { it.id }) { m ->
            var hot by remember { mutableStateOf(false) }
            val v = views.firstOrNull { it.machine.id == m.id }
            // Read again with the board (`rev`): the same, edited, board isn't news to the list.
            val row = remember(rev, m.id) { board.row(m.id) }
            val copies = remember(rev, m.id, picked) { picked.filter { id -> board.get(id)?.machine != m.id } }
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
    rev: Int,
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
                TemplateRowB(model, w, rev, t, i, justOne, selecting, tap)
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
private fun TemplateRowB(model: AppModel, w: BWadspace, rev: Int, t: BTemplate, i: Int, justOne: Boolean, selecting: Boolean, tap: () -> Unit) {
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
            remember(rev, t.id) {
                t.apps.joinToString(", ") { e -> model.g.packages.appName(e.app) + if (e.extras.isNotEmpty()) " +${e.extras.size}" else "" }
            }.ifEmpty { "No apps yet" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun templateOf(p: BPayload): String? = when (p) {
    is BPayload.App -> p.template
    is BPayload.Extra -> p.template
    else -> null
}

/** A wadspace open, full screen: its stack and the others' (Wadspaces), the apps to add, its customization; Build, Open, Delete. */
@Composable
private fun WadspaceEditor(
    model: AppModel,
    board: Board,
    rev: Int,
    w: BWadspace,
    run: BuildRun?,
    ed: Edits,
    close: () -> Unit,
    open: (String) -> Unit,
) {
    val libState by model.library.collectAsState()
    var tab by rememberSaveable(w.id) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the board") }
            Column(Modifier.weight(1f)) {
                Text(w.name.ifEmpty { "Untitled" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(model.standing(w))
                    Text(
                        "on ${board.machines.firstOrNull { it.id == w.machine }?.name?.ifEmpty { null } ?: w.machine}",
                        Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { model.edit(Op.Undo) }, enabled = board.canUndo) { Text("Undo") }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.buildOnItsMachine(w.id) }, enabled = run == null || run.finished) { Text("Build") }
            OutlinedButton(onClick = {
                close()
                open(w.id)
            }) { Text("Open") }
            TextButton(onClick = { if (ed.done("Deleted ${w.name}", Op.Delete(listOf(Pick.Wadspace(w.id))))) close() }) { Text("Delete") }
        }
        run?.let { r ->
            Text(
                r.error ?: r.lines.lastOrNull() ?: r.status,
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Wadspaces", "Apps", "Customization").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
        }
        if (tab == 0) {
            StackTab(model, board, rev, w, ed, Modifier.weight(1f))
        } else {
            MockDesktop(model, w, rev, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                if (tab == 1) AppsTab(model, board, rev, w, ed) else CustomTab(model, rev, w, (libState as? LibState.Ready)?.lib)
            }
        }
    }
}

internal fun extraName(model: AppModel, app: String, x: BExtra): String = when (app) {
    "vscode" -> model.g.catalog.extensions.firstOrNull { it.id == x.id }?.name ?: x.id
    V4.TERMINAL -> model.g.catalog.tools.firstOrNull { it.id == x.id }?.name ?: x.id
    else -> x.name ?: x.id
}

/**
 * The catalog's apps, each with what it takes. A tap puts an app in (into
 * the template picked above them: the empty one if there is, else a
 * template of its own) and its + turns to a check; a tap on the check
 * takes it out. An extra goes where its app is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsTab(model: AppModel, board: Board, rev: Int, w: BWadspace, ed: Edits) {
    val has = remember(w.id, rev) {
        V4.appsOf(w.json()).associate { text(it, "app") to (it["extras"] as? JsonArray)?.map { x -> text(x as? JsonObject, "id") }.orEmpty() }
    }
    var site by remember { mutableStateOf("") }
    // null: the empty template if there's one, else one of its own; "": one of its own; else that template.
    var choice by remember(w.id) { mutableStateOf<String?>(null) }
    val into: String? = when (val c = choice) {
        null -> w.templates.firstOrNull { it.apps.isEmpty() }?.id
        "" -> null
        else -> c.takeIf { w.templates.any { t -> t.id == c } }
    }
    val webApps = (board.wadspaces.flatMap { it.templates }.flatMap { it.apps }.filter { it.app == "wadbrowser" }.flatMap { it.extras } + COMMON).distinctBy { it.id }
    val cats = model.g.catalog.apps.map { it.category }.distinct()
    val where = if (into != null) Where(template = into) else Where(newTemplate = true)
    fun addApp(app: String) = ed.edit(Op.Drop(BPayload.CatalogApp(app), w.id, where))
    fun addExtra(app: String, x: BExtra) = ed.edit(Op.Drop(BPayload.CatalogExtra(app, x), w.id, if (app in has) Where() else where))
    fun takeOut(app: String) {
        val ops = w.templates.filter { t -> t.apps.any { it.app == app } }.map { Op.RemoveApp(w.id, it.id, app) }
        ed.done("Took ${model.g.packages.appName(app)} out", *ops.toTypedArray())
    }
    fun takeOut(app: String, x: BExtra, name: String) {
        val ops = w.templates.filter { t -> t.apps.any { e -> e.app == app && e.extras.any { it.id == x.id } } }.map { Op.RemoveExtra(w.id, it.id, app, x.id) }
        ed.done("Took $name out", *ops.toTypedArray())
    }

    Text("ADD TO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        w.templates.forEach { t -> FilterChip(into == t.id, { choice = t.id }, { Text(t.name) }) }
        FilterChip(into == null, { choice = "" }, { Text("A template of its own") })
    }
    cats.forEach { cat ->
        Text(cat.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        model.g.catalog.apps.filter { it.category == cat }.forEach { a ->
            val inIt = a.id in has
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (inIt) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(10.dp),
            ) {
                Row(Modifier.fillMaxWidth().clickable { if (inIt) takeOut(a.id) else addApp(a.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp)) { iconBitmap("app-${a.id}")?.let { Image(it, null, Modifier.fillMaxSize()) } }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold)
                        Text(a.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (inIt) {
                        Icon(
                            Icons.Filled.Check,
                            "In it: tap to take ${a.name} out",
                            Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(4.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Add,
                            "Add ${a.name}",
                            Modifier.size(28.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(4.dp),
                        )
                    }
                }
                val offered: List<Pair<BExtra, String>> = when (a.id) {
                    "vscode" -> model.g.catalog.extensions.map { BExtra(it.id) to it.name }
                    V4.TERMINAL -> model.g.catalog.tools.map { BExtra(it.id) to it.name }
                    "wadbrowser" -> webApps.map { it to (it.name ?: it.id) }
                    else -> emptyList()
                }
                if (offered.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        offered.forEach { (x, name) ->
                            val on = has[a.id]?.contains(x.id) == true
                            FilterChip(
                                on,
                                { if (on) takeOut(a.id, x, name) else addExtra(a.id, x) },
                                { Text(name) },
                                leadingIcon = if (on) {
                                    { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                                } else null,
                            )
                        }
                    }
                }
                if (a.id == "wadbrowser") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            site, { site = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a site: notion.so") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                siteWebApp(site)?.let { addExtra("wadbrowser", it) }
                                site = ""
                            }),
                        )
                        IconButton(onClick = {
                            siteWebApp(site)?.let { addExtra("wadbrowser", it) }
                            site = ""
                        }) { Icon(Icons.Filled.Add, "Add the site") }
                    }
                    if (inIt) {
                        // The top template with WadBrowser decides: the switch shows and sets its.
                        val withIt = w.templates.filter { t -> t.apps.any { it.app == "wadbrowser" } }
                        val top = withIt.firstOrNull()
                        val focus = top?.apps?.firstOrNull { it.app == "wadbrowser" }?.mode == "focus"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Focus mode: its web apps, no address bar", style = MaterialTheme.typography.bodySmall)
                                if (top != null && withIt.size > 1) {
                                    Text(
                                        "Set in ${top.name}, the highest template with WadBrowser",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(focus, { on -> ed.edit(Op.SetMode(w.id, if (on) "focus" else "browser", top?.id)) })
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
private fun CustomTab(model: AppModel, rev: Int, w: BWadspace, lib: Library?) {
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
    val now = remember(rev, w.id) { textureOf(w) }
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
    val projects = remember(rev, w.id) { projectsOf(w) }
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

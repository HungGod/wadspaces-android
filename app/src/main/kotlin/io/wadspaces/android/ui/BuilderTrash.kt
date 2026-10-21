package io.wadspaces.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wadspaces.android.AppModel
import io.wadspaces.core.board.BEntry
import io.wadspaces.core.board.BTemplate
import io.wadspaces.core.board.BWadspace
import io.wadspaces.core.board.Board
import io.wadspaces.core.board.Op
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject

// Recently deleted (the WadSpaces Client's, b/trash.rs): what was taken out
// of each wadspace (a template, an app off one, a project, the background),
// each to put back where it was or forget; and the deleted wadspaces, each
// to restore. A deleted wadspace's parts come back on their own from any
// wadspace's Wadspaces tab, where it's among the others. Undo takes back
// any of it.

/** How long ago an RFC 3339 time was. */
private fun ago(at: String): String {
    val t = runCatching { Instant.parse(at) }.getOrNull() ?: return ""
    val s = Duration.between(t, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

/** A kept piece in words: what it is, and a line under it. */
private fun pieceText(model: AppModel, w: BWadspace, k: JsonObject): Pair<String, String> = when (text(k, "kind")) {
    "template" -> BTemplate.of(k["template"]).let { t -> t.name to t.apps.joinToString(", ") { model.g.packages.appName(it.app) } }
    "app" -> BEntry.of(k["entry"]).let { e ->
        val from = w.templates.firstOrNull { it.id == text(k, "template") }?.name ?: text(k, "template")
        model.g.packages.appName(e.app) to "off $from" + if (e.extras.isNotEmpty()) " · ${e.extras.size} inside" else ""
    }
    "project" -> (k["project"] as? JsonObject).let { p -> text(p, "as") to (p?.let(::projectText) ?: "") }
    "background" -> "Background" to ((k["wallpaper"] as? JsonObject)?.let { TEXTURE_NAMES[text(it, "value")] ?: text(it, "value") } ?: "")
    else -> text(k, "kind") to ""
}

/** The picture a kept piece shows. */
@Composable
private fun PiecePicture(k: JsonObject) {
    val name = when (text(k, "kind")) {
        "app" -> "app-${text(k["entry"] as? JsonObject, "app")}"
        "template" -> BTemplate.of(k["template"]).apps.firstOrNull()?.let { "app-${it.app}" }
        "project" -> "folder"
        else -> null
    }
    val texture = if (text(k, "kind") == "background") (k["wallpaper"] as? JsonObject)?.let { text(it, "value") } else null
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val bmp = name?.let { iconBitmap(it) } ?: texture?.let { textureBitmap(it) }
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun TrashScreen(model: AppModel, board: Board, rev: Int, ed: Edits, close: () -> Unit) {
    val machine = { id: String -> board.machines.firstOrNull { it.id == id }?.name?.ifEmpty { null } ?: id }
    // As of `rev` (the board's edited in place): living ones with something taken out, newest first, and the deleted.
    val living = remember(rev) {
        board.wadspaces.filter { !it.deleted && it.trash.isNotEmpty() }
            .sortedByDescending { w -> w.trash.lastOrNull()?.let { text(it, "at") } ?: "" }
            .map { it.copy() }
    }
    val deleted = remember(rev) { board.deleted().map { it.copy() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the board") }
            Text("Recently deleted", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { model.edit(Op.Undo) }, enabled = board.canUndo) { Text("Undo") }
        }
        Text(
            "Put back returns a piece to where it was. A deleted wadspace's parts come back on their own from any wadspace's Wadspaces tab, where it's listed with the others.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (living.isEmpty() && deleted.isEmpty()) {
                item { Text("Nothing deleted lately.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (living.isNotEmpty()) item { Heading("TAKEN OUT") }
            items(living, key = { "w:${it.id}" }) { w ->
                Card {
                    Text("From ${w.name} · on ${machine(w.machine)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    w.trash.asReversed().forEach { k -> KeptRow(model, w, k, ed) }
                }
            }
            if (deleted.isNotEmpty()) item { Heading("DELETED WADSPACES") }
            items(deleted, key = { "d:${it.id}" }) { w ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name.ifEmpty { "Untitled" }, fontWeight = FontWeight.SemiBold)
                            Text(
                                "on ${machine(w.machine)} · deleted ${w.deletedAt?.let(::ago) ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { ed.done("Restored ${w.name}", Op.Restore(w.id)) }) { Text("Restore") }
                    }
                    val parts = buildList {
                        textureOf(w)?.let { add(TEXTURE_NAMES[it] ?: it) }
                        projectsOf(w).forEach { add(text(it, "as")) }
                        w.templates.forEach { add(it.name) }
                    }
                    if (parts.isNotEmpty()) {
                        Text(
                            "In it: ${parts.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (w.trash.isNotEmpty()) {
                        Text("Taken out of it before (back in it when it's restored):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        w.trash.asReversed().forEach { k -> KeptRow(model, w, k, ed) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** A kept piece: Put back (where it was), or forget it. */
@Composable
private fun KeptRow(model: AppModel, w: BWadspace, k: JsonObject, ed: Edits) {
    val (title, detail) = pieceText(model, w, k)
    Row(verticalAlignment = Alignment.CenterVertically) {
        PiecePicture(k)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(detail, ago(text(k, "at"))).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { ed.done("Put $title back", Op.PutBack(w.id, text(k, "id"))) }) { Text("Put back") }
        IconButton(onClick = { ed.done("Forgot $title", Op.Forget(w.id, text(k, "id"))) }) {
            Icon(Icons.Filled.Close, "Forget $title", Modifier.size(18.dp))
        }
    }
}

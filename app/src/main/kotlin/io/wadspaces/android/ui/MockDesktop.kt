package io.wadspaces.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.wadspaces.android.AppModel
import io.wadspaces.android.R
import io.wadspaces.core.board.BWadspace
import io.wadspaces.core.board.V4
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// A wadspace's desktop as it will look (design C's DesktopView, after
// wad-core's desktop_for): its background, its apps where its templates
// first have them (VS Code's extensions and the command line's tools
// counted on theirs), WadBrowser's web apps, its projects as folders (faded
// when no app opens folders), and the taskbar. What's added grows in.

private val CELL_W = 66.dp
private val CELL_H = 60.dp
private val BAR = 24.dp

/** One icon on it: a picture from icons/ (`picture`), else a tile with `label`'s first letter. */
private class DeskIcon(val key: String, val label: String, val picture: String?, val badge: Int = 0, val note: String? = null, val faded: Boolean = false)

private fun deskIcons(model: AppModel, w: BWadspace): List<DeskIcon> {
    val apps = V4.appsOf(w.json())
    val out = mutableListOf<DeskIcon>()
    for (a in apps) {
        val id = text(a, "app")
        val extras = (a["extras"] as? JsonArray)?.size ?: 0
        val badge = if (id == "vscode" || id == V4.TERMINAL) extras else 0
        val note = if (id == "wadbrowser" && text(a, "mode") == "focus") "focus" else null
        out += DeskIcon("app:$id", model.g.packages.appName(id), "app-$id", badge, note)
    }
    apps.firstOrNull { text(it, "app") == "wadbrowser" }?.let { b ->
        for (x in (b["extras"] as? JsonArray).orEmpty()) {
            val o = x as? JsonObject ?: continue
            out += DeskIcon("web:${text(o, "id")}", text(o, "name").ifEmpty { text(o, "id") }, null)
        }
    }
    val opens = apps.any { a -> model.g.catalog.apps.any { it.id == text(a, "app") && it.opensFolders } }
    for (p in projectsOf(w)) out += DeskIcon("project:${text(p, "id")}", text(p, "as"), "folder", faded = !opens)
    return out
}

/** The desktop `w` gets: drawn again with the board (`rev`). */
@Composable
fun MockDesktop(model: AppModel, w: BWadspace, rev: Int, modifier: Modifier = Modifier) {
    val icons = remember(w.id, rev) { deskIcons(model, w) }
    // What was there when it opened stays put; what's added after grows in.
    val first = remember(w.id) { icons.map { it.key }.toSet() }
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color.White else Color.Black
    val bmp = textureOf(w)?.let { textureBitmap(it) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().aspectRatio(1.75f).clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(if (dark) Wad.bg else Color(0xFFF4F4F4)),
    ) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = BAR).padding(4.dp)) {
            val rows = (maxHeight / CELL_H).toInt().coerceAtLeast(1)
            val room = rows * (maxWidth / CELL_W).toInt().coerceAtLeast(1)
            val shown = if (icons.size <= room) icons else icons.take(room - 1) + DeskIcon("more", "${icons.size - room + 1} more", null)
            Row {
                shown.chunked(rows).forEach { col ->
                    Column { col.forEach { DeskTile(it, ink, grow = it.key !in first) } }
                }
            }
            if (icons.isEmpty()) {
                Text(
                    "An empty desktop: add apps",
                    Modifier.align(Alignment.Center),
                    color = ink.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        Taskbar(model, w, rev, ink, dark, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DeskTile(i: DeskIcon, ink: Color, grow: Boolean) {
    val seen = remember { MutableTransitionState(!grow).apply { targetState = true } }
    AnimatedVisibility(seen, enter = scaleIn(initialScale = 0.3f) + fadeIn()) {
        Column(Modifier.size(CELL_W, CELL_H).alpha(if (i.faded) 0.45f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 5.dp).size(30.dp)) {
                DeskPicture(i, 30)
                if (i.badge > 0) {
                    Text(
                        "${i.badge}",
                        Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-4).dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary).padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 8.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                i.note?.let {
                    Text(
                        it,
                        Modifier.align(Alignment.BottomCenter).offset(y = 5.dp).clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary).padding(horizontal = 3.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 7.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                i.label,
                Modifier.padding(top = 3.dp, start = 1.dp, end = 1.dp),
                color = ink,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                style = TextStyle(shadow = Shadow(if (ink == Color.White) Color.Black else Color.White, blurRadius = 4f)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Its picture, else a grey tile with its first letter (web apps, and what has no icon). */
@Composable
private fun DeskPicture(i: DeskIcon, size: Int) {
    val bmp = i.picture?.let { iconBitmap(it) }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size.dp))
    } else {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (i.key == "more") "+" else i.label.take(1).uppercase(),
                color = MaterialTheme.colorScheme.surface,
                fontSize = (size / 2).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** WadSpaces' mark, the apps, the clock. */
@Composable
private fun Taskbar(model: AppModel, w: BWadspace, rev: Int, ink: Color, dark: Boolean, modifier: Modifier) {
    val apps = remember(w.id, rev) { V4.appsOf(w.json()).map { text(it, "app") } }
    Row(
        modifier.fillMaxWidth().height(BAR).background(if (dark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(22.dp).scale(1.6f))
        Box(Modifier.width(1.dp).height(12.dp).background(ink.copy(alpha = 0.3f)))
        apps.forEach { a -> DeskPicture(DeskIcon("app:$a", model.g.packages.appName(a), "app-$a"), 14) }
        Spacer(Modifier.weight(1f))
        Text("9:41", color = ink.copy(alpha = 0.7f), fontSize = 9.sp)
    }
}

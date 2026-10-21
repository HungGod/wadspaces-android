package io.wadspaces.android.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.wadspaces.core.builder.Catalog
import io.wadspaces.core.str
import kotlinx.serialization.json.JsonElement

/** A texture's picture (synced from wadspaces: wallpapers/texture-<id>-<tone>.png), in the theme's tone. */
@Composable
fun textureBitmap(id: String): ImageBitmap? {
    val ctx = LocalContext.current
    val tone = if (isSystemInDarkTheme()) "dark" else "light"
    return remember(id, tone) {
        runCatching { ctx.assets.open("wallpapers/texture-$id-$tone.png").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
    }
}

private val iconBitmaps = HashMap<String, ImageBitmap?>()

/** An icon synced from wadspaces (icons/<name>.png: app-<id>, folder, ext-<id>), decoded once; null if there's none. */
@Composable
fun iconBitmap(name: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(name) {
        synchronized(iconBitmaps) {
            iconBitmaps.getOrPut(name) {
                runCatching { ctx.assets.open("icons/$name.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
            }
        }
    }
}

/**
 * A wadspace's wallpaper: a texture (version 4's, in the theme's tone), a
 * preset's picture, a colour, a picture of its own, else the brand's colours
 * (black or white, for a version 4 wadspace with none).
 */
@Composable
fun Wallpaper(wallpaper: JsonElement?, catalog: Catalog, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val type = wallpaper.str("type")
    val value = wallpaper.str("value")
    val texture = if (type == "texture") textureBitmap(value) else null
    val image: ImageBitmap? = texture ?: remember(type, value) {
        val preset = catalog.presets.firstOrNull { it.wallpaper.str("value") == value }
        when {
            preset != null -> runCatching {
                ctx.assets.open("wallpapers/${preset.thumb}").use { BitmapFactory.decodeStream(it).asImageBitmap() }
            }.getOrNull()
            type == "image" && value.startsWith("data:") -> runCatching {
                val bytes = Base64.decode(value.substringAfter(";base64,"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
            }.getOrNull()
            else -> null
        }
    }
    val color = if (type == "color") parseColor(value) else null
    Box(modifier.clip(RoundedCornerShape(14.dp))) {
        when {
            image != null -> Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            color != null -> Box(Modifier.matchParentSize().background(color))
            else -> Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(listOf(Wad.surface3, Wad.bg, Color(0xFF2A1747))),
                ),
            )
        }
    }
}

/** #rgb, #rrggbb or #rrggbbaa. */
fun parseColor(css: String): Color? {
    val h = css.trim().removePrefix("#")
    val full = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("") + "ff"
        6 -> h + "ff"
        8 -> h
        else -> return null
    }
    val v = full.toLongOrNull(16) ?: return null
    return Color(((v and 0xff) shl 24 or (v shr 8)).toInt())
}

/** A small dot: on (accent), busy, or off. */
@Composable
fun Dot(state: String) {
    val c = when (state) {
        "on" -> Wad.accent
        "busy" -> Wad.template
        "error" -> Wad.danger
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
}

/** A page's title and what it's for. */
@Composable
fun PageHead(title: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (sub != null) {
            Spacer(Modifier.size(4.dp))
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row's label and its value. */
@Composable
fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.size(width = 96.dp, height = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

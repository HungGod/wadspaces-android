package io.wadspaces.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The site's palettes (the WadSpaces Client's style.css). */
object Wad {
    val bg = Color(0xFF0A0614)
    val bg2 = Color(0xFF0E0919)
    val surface = Color(0xFF140D22)
    val surface2 = Color(0xFF1B132D)
    val surface3 = Color(0xFF241A39)
    val fg = Color(0xFFF5F2FA)
    val muted = Color(0x9EF5F2FA)
    val accent = Color(0xFFC6FF1F)
    val onAccent = Color(0xFF0A0614)
    val danger = Color(0xFFFF5470)

    /** The Builder's layers: template, Extensions, customization. */
    val template = Color(0xFF7FD3FF)
    val extensions = Color(0xFFC6FF1F)
    val customization = Color(0xFFFF3D81)
}

private val dark = darkColorScheme(
    primary = Wad.accent,
    onPrimary = Wad.onAccent,
    secondary = Wad.customization,
    tertiary = Wad.template,
    background = Wad.bg,
    onBackground = Wad.fg,
    surface = Wad.surface,
    onSurface = Wad.fg,
    surfaceVariant = Wad.surface2,
    onSurfaceVariant = Wad.muted,
    surfaceContainer = Wad.surface,
    surfaceContainerHigh = Wad.surface2,
    surfaceContainerHighest = Wad.surface3,
    outline = Color(0x2EF0EAFA),
    outlineVariant = Color(0x17F0EAFA),
    error = Wad.danger,
)

// Strictly greyscale (as the WadSpaces Client's light theme).
private val light = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color(0xFF0A0A0A),
    secondary = Color(0xFF7A7A7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF0A0A0A),
    tertiary = Color(0xFF3D3D3D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBEBEB),
    onTertiaryContainer = Color(0xFF0A0A0A),
    background = Color(0xFFF4F4F4),
    onBackground = Color(0xFF0A0A0A),
    surface = Color.White,
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFF6F6F6),
    onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceContainer = Color(0xFFF6F6F6),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    outline = Color(0x2E000000),
    outlineVariant = Color(0x17000000),
    error = Color(0xFF3A3A3A),
)

@Composable
fun WadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}

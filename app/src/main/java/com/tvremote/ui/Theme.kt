package com.tvremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PowerRed = Color(0xFFE5484D)
val Accent = Color(0xFF4F8CFF)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF8AA4CF),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF101318),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFFD5DBE7),
    surfaceContainer = Color(0xFF171B23),
    surfaceContainerHigh = Color(0xFF1E2430),
    surfaceContainerHighest = Color(0xFF262D3B),
    outline = Color(0xFF3A4252),
    error = PowerRed,
)

@Composable
fun TvRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

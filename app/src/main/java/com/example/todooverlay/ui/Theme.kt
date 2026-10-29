package com.example.todooverlay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF8AB4F8)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF10131A),
    background = Color(0xFF10131A),
    surface = Color(0xFF181C24),
    surfaceVariant = Color(0xFF232935),
    onSurface = Color(0xFFECEDEF),
    onSurfaceVariant = Color(0xFFB6BCC8)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A5FB4),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F5)
)

@Composable
fun TodoOverlayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

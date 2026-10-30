package com.alpha.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF7652DF), onPrimary = Color.White,
    primaryContainer = Color(0xFFF1EDFF), onPrimaryContainer = Color(0xFF7652DF),
    background = Color.White, onBackground = Color(0xFF242429),
    surface = Color.White, onSurface = Color(0xFF242429),
    surfaceVariant = Color(0xFFF3F3F5), onSurfaceVariant = Color(0xFF7C7B85),
    outlineVariant = Color(0xFFEDEDF0), error = Color(0xFFA33842)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFBCA5FF), onPrimary = Color(0xFF2B1756),
    primaryContainer = Color(0xFF342B49), onPrimaryContainer = Color(0xFFBCA5FF),
    background = Color(0xFF17171B), onBackground = Color(0xFFF1F0F5),
    surface = Color(0xFF202025), onSurface = Color(0xFFF1F0F5),
    surfaceVariant = Color(0xFF2A292F), onSurfaceVariant = Color(0xFFAAA7B5),
    outlineVariant = Color(0xFF36343E), error = Color(0xFFFFB0B5)
)

@Composable
fun AlphaTheme(theme: String = "system", content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (theme == "dark" || (theme == "system" && isSystemInDarkTheme())) Dark else Light, content = content)
}

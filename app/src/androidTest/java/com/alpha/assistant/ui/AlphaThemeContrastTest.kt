package com.alpha.assistant.ui

import android.util.Log
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaThemeContrastTest {
    @Test fun everySchemeKeepsBodyContrast() {
        val schemes = mapOf("light" to alphaColorScheme(false), "dark" to alphaColorScheme(true))
        for ((name, s) in schemes) for ((pair, colors) in mapOf(
            "onSurface/surface" to (s.onSurface to s.surface),
            "onSurface/surfaceContainer" to (s.onSurface to s.surfaceContainer),
            "onSurface/surfaceContainerLow" to (s.onSurface to s.surfaceContainerLow),
            "primary/surfaceContainer" to (s.primary to s.surfaceContainer),
            "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
            "onSurfaceVariant/surfaceContainer" to (s.onSurfaceVariant to s.surfaceContainer),
            "onSurfaceVariant/surfaceContainerHigh" to (s.onSurfaceVariant to s.surfaceContainerHigh),
            "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
            "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
            "primary/surfaceVariant" to (s.primary to s.surfaceVariant),
            "primary/primaryContainer" to (s.primary to s.primaryContainer),
            "onPrimary/primary" to (s.onPrimary to s.primary),
            "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            "onSecondary/secondary" to (s.onSecondary to s.secondary),
            "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
            "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "onError/error" to (s.onError to s.error),
            "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
            "error/surface" to (s.error to s.surface),
            "error/surfaceContainer" to (s.error to s.surfaceContainer),
            "primary/surface" to (s.primary to s.surface),
            "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface))) {
            val ratio = ColorUtils.calculateContrast(colors.first.toArgb(), colors.second.toArgb())
            Log.i("AlphaThemeContrast", "$name $pair $ratio")
            assertTrue("$name $pair is $ratio:1", ratio >= 4.5)
        }
        for ((name, s) in schemes) {
            for (surface in listOf(s.background, s.surfaceContainerLow, s.secondaryContainer)) {
                val ratio = ColorUtils.calculateContrast(s.outline.toArgb(), surface.toArgb())
                assertTrue("$name outline on $surface is $ratio:1", ratio >= 3)
            }
        }
    }

    @Test fun schemesKeepTheGraphitePalette() {
        for (dark in listOf(false, true)) {
            val scheme = alphaColorScheme(dark)
            val canvas = if (dark) Color(0xFF171717) else Color(0xFFFAFAFA)
            val drawer = if (dark) Color(0xFF1D1D1D) else Color.White
            val text = if (dark) Color(0xFFF2F2F2) else Color(0xFF242424)
            val selection = if (dark) Color(0xFF303030) else Color(0xFFEFEFEF)
            assertEquals(canvas, scheme.background)
            assertEquals(canvas, scheme.surfaceColorAtElevation(6.dp))
            assertEquals(text, scheme.primary)
            assertEquals(drawer, scheme.onPrimary)
            assertEquals(text, scheme.onSurface)
            assertEquals(selection, scheme.primaryContainer)
            assertTrue(ColorUtils.calculateContrast(drawerSelectionMark(dark).toArgb(), selection.toArgb()) >= 3)
        }
    }
}

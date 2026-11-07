package com.alpha.assistant.ui

import android.os.Build
import android.util.Log
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaThemeContrastTest {
    @Test fun everySchemeKeepsBodyContrast() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val schemes = mapOf("fixed light" to AlphaLightColors, "fixed dark" to AlphaDarkColors,
            "device light" to alphaColorScheme(context, false), "device dark" to alphaColorScheme(context, true))
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

    // A wallpaper must change accents without tinting reading surfaces, including elevated ones.
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    @Test fun deviceSchemesKeepNeutralSurfacesAndDynamicAccents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((dark, dynamic) in listOf(false to dynamicLightColorScheme(context), true to dynamicDarkColorScheme(context))) {
            val scheme = alphaColorScheme(context, dark)
            val canvas = if (dark) Color(0xFF171717) else Color(0xFFFAFAFA)
            val card = if (dark) Color(0xFF232323) else Color(0xFFF1F1F1)
            val bubble = if (dark) Color(0xFF2B2B2B) else Color(0xFFEDEDED)
            val text = if (dark) Color(0xFFEEEEEE) else Color(0xFF1D1D1F)
            Log.i("AlphaThemeContrast", "dark=$dark primary=${scheme.primary.toArgb().toUInt().toString(16)}")
            assertEquals("dark=$dark primary", dynamic.primary, scheme.primary)
            assertEquals("dark=$dark onPrimary", dynamic.onPrimary, scheme.onPrimary)
            val wallpaperContrast = ColorUtils.calculateContrast(dynamic.primary.toArgb(), dynamic.primaryContainer.toArgb())
            if (wallpaperContrast >= 4.5) {
                assertEquals("dark=$dark primaryContainer", dynamic.primaryContainer, scheme.primaryContainer)
                assertEquals("dark=$dark onPrimaryContainer", dynamic.onPrimaryContainer, scheme.onPrimaryContainer)
            } else {
                assertEquals("dark=$dark low-contrast wallpaper container", card, scheme.primaryContainer)
                assertEquals("dark=$dark accessible selection text", dynamic.primary, scheme.onPrimaryContainer)
            }
            assertEquals("dark=$dark background", canvas, scheme.background)
            assertEquals("dark=$dark surface", canvas, scheme.surface)
            assertEquals("dark=$dark elevated surface", canvas, scheme.surfaceColorAtElevation(6.dp))
            assertEquals("dark=$dark reading card", card, scheme.surfaceContainerLow)
            assertEquals("dark=$dark user bubble", bubble, scheme.secondaryContainer)
            assertEquals("dark=$dark main text", text, scheme.onSurface)
            assertEquals("dark=$dark bubble text", text, scheme.onSecondaryContainer)
        }
    }
}

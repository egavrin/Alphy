package com.alpha.assistant.ui

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
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
            "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "onError/error" to (s.onError to s.error),
            "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
            "primary/surface" to (s.primary to s.surface),
            "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface))) {
            val ratio = ColorUtils.calculateContrast(colors.first.toArgb(), colors.second.toArgb())
            Log.i("AlphaThemeContrast", "$name $pair $ratio")
            assertTrue("$name $pair is $ratio:1", ratio >= 4.5)
        }
        assertEquals(Color(0xFF63568F), AlphaLightColors.primary)
        assertEquals(Color(0xFFCDBDFF), AlphaDarkColors.primary)
    }
}

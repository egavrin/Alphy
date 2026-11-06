package com.alpha.assistant.ui

import android.os.Build
import android.util.Log
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.MiniAppsDarkColors
import com.alpha.assistant.tools.MiniAppsLightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaThemeContrastTest {
    @Test fun everySchemeKeepsBodyContrast() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val schemes = mapOf("fixed light" to AlphaLightColors, "fixed dark" to AlphaDarkColors,
            "device light" to alphaColorScheme(context, false), "device dark" to alphaColorScheme(context, true),
            "mini apps light" to MiniAppsLightColors, "mini apps dark" to MiniAppsDarkColors)
        for ((name, s) in schemes) for ((pair, colors) in mapOf(
            "onSurface/surface" to (s.onSurface to s.surface),
            "onSurface/surfaceContainer" to (s.onSurface to s.surfaceContainer),
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
        assertEquals(Color(0xFF63568F), AlphaLightColors.primary)
        assertEquals(Color(0xFFCDBDFF), AlphaDarkColors.primary)
    }

    // Failure case: the shell uses the fixed palette, so it ignores the wallpaper colors.
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    @Test fun deviceSchemesUseDynamicColor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((dark, dynamic) in listOf(false to dynamicLightColorScheme(context), true to dynamicDarkColorScheme(context))) {
            val scheme = alphaColorScheme(context, dark)
            assertEquals("dark=$dark primary", dynamic.primary, scheme.primary)
            assertEquals("dark=$dark background", dynamic.background, scheme.background)
            assertEquals("dark=$dark surface", dynamic.surface, scheme.surface)
        }
    }
}

package com.alpha.assistant.surfaces

import android.os.Build
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.alpha.assistant.R
import com.alpha.assistant.ui.alphaColorScheme

internal data class WidgetColors(
    val surface: ColorProvider,
    val onSurface: ColorProvider,
    val primary: ColorProvider,
    val onPrimary: ColorProvider,
    val secondaryContainer: ColorProvider,
    val onSecondaryContainer: ColorProvider,
)

internal fun widgetColors(): WidgetColors {
    val light = alphaColorScheme(false)
    val dark = alphaColorScheme(true)
    return WidgetColors(
        ColorProvider(light.surface,dark.surface),
        ColorProvider(light.onSurface,dark.onSurface),
        ColorProvider(light.primary,dark.primary),
        ColorProvider(light.onPrimary,dark.onPrimary),
        ColorProvider(light.secondaryContainer,dark.secondaryContainer),
        ColorProvider(light.onSecondaryContainer,dark.onSecondaryContainer),
    )
}

internal fun GlanceModifier.widgetBackground(colors: WidgetColors): GlanceModifier =
    if (Build.VERSION.SDK_INT >= 31) background(colors.surface).cornerRadius(24.dp)
    else background(ImageProvider(R.drawable.widget_background))

internal fun GlanceModifier.widgetControlShape(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= 31) cornerRadius(20.dp) else this

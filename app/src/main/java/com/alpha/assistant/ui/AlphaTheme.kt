package com.alpha.assistant.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

// Fixed accents for static rendering and previews.
internal val AlphaLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF63568F), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFE8DEFF), onPrimaryContainer = Color(0xFF4B3E76), inversePrimary = Color(0xFFCDBDFF),
    secondary = Color(0xFF615B71), onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF7D5261), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFFFD9E4), onTertiaryContainer = Color(0xFF633B49),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    primaryFixed = Color(0xFFE8DEFF), primaryFixedDim = Color(0xFFCDBDFF), onPrimaryFixed = Color(0xFF1F1048), onPrimaryFixedVariant = Color(0xFF4B3E76),
    secondaryFixed = Color(0xFFE7DEF8), secondaryFixedDim = Color(0xFFCBC3DC), onSecondaryFixed = Color(0xFF1D192B), onSecondaryFixedVariant = Color(0xFF494458),
    tertiaryFixed = Color(0xFFFFD9E4), tertiaryFixedDim = Color(0xFFEEB8C9), onTertiaryFixed = Color(0xFF31111E), onTertiaryFixedVariant = Color(0xFF633B49),
).withNeutralSurfaces(dark = false)
internal val AlphaDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFCDBDFF), onPrimary = Color(0xFF34275E), primaryContainer = Color(0xFF4B3E76), onPrimaryContainer = Color(0xFFE8DEFF), inversePrimary = Color(0xFF63568F),
    secondary = Color(0xFFCBC3DC), onSecondary = Color(0xFF322E41),
    tertiary = Color(0xFFEEB8C9), onTertiary = Color(0xFF492533), tertiaryContainer = Color(0xFF633B49), onTertiaryContainer = Color(0xFFFFD9E4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    primaryFixed = Color(0xFFE8DEFF), primaryFixedDim = Color(0xFFCDBDFF), onPrimaryFixed = Color(0xFF1F1048), onPrimaryFixedVariant = Color(0xFF4B3E76),
    secondaryFixed = Color(0xFFE7DEF8), secondaryFixedDim = Color(0xFFCBC3DC), onSecondaryFixed = Color(0xFF1D192B), onSecondaryFixedVariant = Color(0xFF494458),
    tertiaryFixed = Color(0xFFFFD9E4), tertiaryFixedDim = Color(0xFFEEB8C9), onTertiaryFixed = Color(0xFF31111E), onTertiaryFixedVariant = Color(0xFF633B49),
).withNeutralSurfaces(dark = true)

private fun ColorScheme.withNeutralSurfaces(dark: Boolean): ColorScheme {
    val canvas = if (dark) Color(0xFF171717) else Color(0xFFFAFAFA)
    val card = if (dark) Color(0xFF232323) else Color(0xFFF1F1F1)
    val bubble = if (dark) Color(0xFF2B2B2B) else Color(0xFFEDEDED)
    val raised = if (dark) Color(0xFF333333) else Color(0xFFE6E6E6)
    val text = if (dark) Color(0xFFEEEEEE) else Color(0xFF1D1D1F)
    // Some wallpaper palettes have too little contrast for primary labels on primaryContainer.
    val readableContainer = ColorUtils.calculateContrast(primary.toArgb(), primaryContainer.toArgb()) >= 4.5
    return copy(
        primaryContainer = if (readableContainer) primaryContainer else card,
        onPrimaryContainer = if (readableContainer) onPrimaryContainer else primary,
        background = canvas, onBackground = text,
        surface = canvas, onSurface = text, surfaceTint = canvas,
        surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = bubble, surfaceContainerHighest = raised,
        surfaceContainerLowest = if (dark) Color(0xFF111111) else Color.White,
        surfaceDim = if (dark) canvas else raised,
        surfaceBright = if (dark) raised else canvas,
        surfaceVariant = raised,
        onSurfaceVariant = if (dark) Color(0xFFB8B8BD) else Color(0xFF56565C),
        secondaryContainer = bubble, onSecondaryContainer = text,
        inverseSurface = if (dark) Color(0xFFEDEDED) else Color(0xFF2B2B2B),
        inverseOnSurface = if (dark) Color(0xFF1D1D1F) else Color(0xFFEEEEEE),
        outline = if (dark) Color(0xFF7B7B83) else Color(0xFF85858B),
        outlineVariant = if (dark) Color(0xFF39393E) else Color(0xFFDCDCE0),
    )
}

internal fun alphaColorScheme(context: Context, dark: Boolean): ColorScheme {
    val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return dynamic.withNeutralSurfaces(dark)
}

private val AlphaShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp))

private val AlphaTypography = Typography().run {
    fun semiBold(style: TextStyle) = style.copy(fontWeight = FontWeight.SemiBold)
    copy(displayLarge = semiBold(displayLarge), displayMedium = semiBold(displayMedium), displaySmall = semiBold(displaySmall),
        headlineLarge = semiBold(headlineLarge), headlineMedium = semiBold(headlineMedium), headlineSmall = semiBold(headlineSmall),
        titleLarge = semiBold(titleLarge), titleMedium = semiBold(titleMedium), titleSmall = semiBold(titleSmall))
}

// For a word that does not fit, as at a large font scale. Without hyphenation, Android breaks the word anywhere and adds
// no hyphen, and it does not break after the hyphen of «Мини-». AlphyActivity keeps the device locale as the default,
// so the Russian patterns need the locale here.
internal val RussianHyphenation = TextStyle(hyphens = Hyphens.Auto, localeList = LocaleList("ru"))

/** The activity configuration reflects Alphy's appearance preference. */
@Composable
fun AlphaTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = alphaColorScheme(LocalContext.current, isSystemInDarkTheme()), motionScheme = MotionScheme.expressive(),
        shapes = AlphaShapes, typography = AlphaTypography, content = content)
}

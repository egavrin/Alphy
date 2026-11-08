package com.alpha.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp

internal val AlphaLightColors: ColorScheme = lightColorScheme(
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
).withGraphiteColors(dark = false)

internal val AlphaDarkColors: ColorScheme = darkColorScheme(
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
).withGraphiteColors(dark = true)

private fun ColorScheme.withGraphiteColors(dark: Boolean): ColorScheme {
    val canvas = if (dark) Color(0xFF171717) else Color(0xFFFAFAFA)
    val card = if (dark) Color(0xFF232323) else Color(0xFFF1F1F1)
    val bubble = if (dark) Color(0xFF2B2B2B) else Color(0xFFEDEDED)
    val raised = if (dark) Color(0xFF333333) else Color(0xFFE6E6E6)
    val text = if (dark) Color(0xFFF2F2F2) else Color(0xFF242424)
    val muted = if (dark) Color(0xFFBDBDBD) else Color(0xFF575757)
    val onAccent = if (dark) Color(0xFF1D1D1D) else Color.White
    val selection = if (dark) Color(0xFF303030) else Color(0xFFEFEFEF)
    return copy(
        primary = text, onPrimary = onAccent,
        primaryContainer = selection, onPrimaryContainer = text,
        inversePrimary = if (dark) Color(0xFF242424) else Color(0xFFF2F2F2),
        secondary = muted, onSecondary = onAccent,
        secondaryContainer = bubble, onSecondaryContainer = text,
        tertiary = muted, onTertiary = onAccent,
        tertiaryContainer = bubble, onTertiaryContainer = text,
        primaryFixed = Color(0xFFEFEFEF), primaryFixedDim = Color(0xFFD5D5D5),
        onPrimaryFixed = Color(0xFF242424), onPrimaryFixedVariant = Color(0xFF575757),
        secondaryFixed = Color(0xFFEFEFEF), secondaryFixedDim = Color(0xFFD5D5D5),
        onSecondaryFixed = Color(0xFF242424), onSecondaryFixedVariant = Color(0xFF575757),
        tertiaryFixed = Color(0xFFEFEFEF), tertiaryFixedDim = Color(0xFFD5D5D5),
        onTertiaryFixed = Color(0xFF242424), onTertiaryFixedVariant = Color(0xFF575757),
        background = canvas, onBackground = text,
        surface = canvas, onSurface = text, surfaceTint = canvas,
        surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = bubble, surfaceContainerHighest = raised,
        surfaceContainerLowest = if (dark) Color(0xFF111111) else Color.White,
        surfaceDim = if (dark) canvas else raised,
        surfaceBright = if (dark) raised else canvas,
        surfaceVariant = raised, onSurfaceVariant = muted,
        inverseSurface = if (dark) Color(0xFFEDEDED) else Color(0xFF2B2B2B),
        inverseOnSurface = if (dark) Color(0xFF242424) else Color(0xFFF2F2F2),
        outline = if (dark) Color(0xFF818181) else Color(0xFF767676),
        outlineVariant = if (dark) Color(0xFF383838) else Color(0xFFE1E1E1),
    )
}

internal fun alphaColorScheme(dark: Boolean): ColorScheme = if (dark) AlphaDarkColors else AlphaLightColors

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
    MaterialExpressiveTheme(colorScheme = alphaColorScheme(isSystemInDarkTheme()), motionScheme = MotionScheme.expressive(),
        shapes = AlphaShapes, typography = AlphaTypography, content = content)
}

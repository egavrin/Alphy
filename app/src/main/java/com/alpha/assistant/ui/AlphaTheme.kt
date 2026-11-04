package com.alpha.assistant.ui

import android.content.Context
import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The TonalSpot scheme from the seed #7652DF, for API 29 and 30, which have no dynamic color.
internal val AlphaLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF63568F), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFE8DEFF), onPrimaryContainer = Color(0xFF4B3E76), inversePrimary = Color(0xFFCDBDFF),
    secondary = Color(0xFF615B71), onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFE7DEF8), onSecondaryContainer = Color(0xFF494458),
    tertiary = Color(0xFF7D5261), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFFFD9E4), onTertiaryContainer = Color(0xFF633B49),
    background = Color(0xFFFDF7FF), onBackground = Color(0xFF1C1B20), surface = Color(0xFFFDF7FF), onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE6E0EC), onSurfaceVariant = Color(0xFF48454E), surfaceTint = Color(0xFF63568F),
    inverseSurface = Color(0xFF322F35), inverseOnSurface = Color(0xFFF4EFF7),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF79757F), outlineVariant = Color(0xFFCAC4CF), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFDF7FF), surfaceContainer = Color(0xFFF2ECF4), surfaceContainerHigh = Color(0xFFECE6EE),
    surfaceContainerHighest = Color(0xFFE6E1E9), surfaceContainerLow = Color(0xFFF7F2FA), surfaceContainerLowest = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDDD8E0),
    primaryFixed = Color(0xFFE8DEFF), primaryFixedDim = Color(0xFFCDBDFF), onPrimaryFixed = Color(0xFF1F1048), onPrimaryFixedVariant = Color(0xFF4B3E76),
    secondaryFixed = Color(0xFFE7DEF8), secondaryFixedDim = Color(0xFFCBC3DC), onSecondaryFixed = Color(0xFF1D192B), onSecondaryFixedVariant = Color(0xFF494458),
    tertiaryFixed = Color(0xFFFFD9E4), tertiaryFixedDim = Color(0xFFEEB8C9), onTertiaryFixed = Color(0xFF31111E), onTertiaryFixedVariant = Color(0xFF633B49),
)
internal val AlphaDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFCDBDFF), onPrimary = Color(0xFF34275E), primaryContainer = Color(0xFF4B3E76), onPrimaryContainer = Color(0xFFE8DEFF), inversePrimary = Color(0xFF63568F),
    secondary = Color(0xFFCBC3DC), onSecondary = Color(0xFF322E41), secondaryContainer = Color(0xFF494458), onSecondaryContainer = Color(0xFFE7DEF8),
    tertiary = Color(0xFFEEB8C9), onTertiary = Color(0xFF492533), tertiaryContainer = Color(0xFF633B49), onTertiaryContainer = Color(0xFFFFD9E4),
    background = Color(0xFF141318), onBackground = Color(0xFFE6E1E9), surface = Color(0xFF141318), onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF48454E), onSurfaceVariant = Color(0xFFCAC4CF), surfaceTint = Color(0xFFCDBDFF),
    inverseSurface = Color(0xFFE6E1E9), inverseOnSurface = Color(0xFF322F35),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF938F99), outlineVariant = Color(0xFF48454E), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A383E), surfaceContainer = Color(0xFF201F24), surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF36343A), surfaceContainerLow = Color(0xFF1C1B20), surfaceContainerLowest = Color(0xFF0F0D13), surfaceDim = Color(0xFF141318),
    primaryFixed = Color(0xFFE8DEFF), primaryFixedDim = Color(0xFFCDBDFF), onPrimaryFixed = Color(0xFF1F1048), onPrimaryFixedVariant = Color(0xFF4B3E76),
    secondaryFixed = Color(0xFFE7DEF8), secondaryFixedDim = Color(0xFFCBC3DC), onSecondaryFixed = Color(0xFF1D192B), onSecondaryFixedVariant = Color(0xFF494458),
    tertiaryFixed = Color(0xFFFFD9E4), tertiaryFixedDim = Color(0xFFEEB8C9), onTertiaryFixed = Color(0xFF31111E), onTertiaryFixedVariant = Color(0xFF633B49),
)

internal fun alphaColorScheme(context: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> AlphaDarkColors
    else -> AlphaLightColors
}

private val AlphaShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp))

private val AlphaTypography = Typography().run {
    fun semiBold(style: TextStyle) = style.copy(fontWeight = FontWeight.SemiBold)
    copy(displayLarge = semiBold(displayLarge), displayMedium = semiBold(displayMedium), displaySmall = semiBold(displaySmall),
        headlineLarge = semiBold(headlineLarge), headlineMedium = semiBold(headlineMedium), headlineSmall = semiBold(headlineSmall),
        titleLarge = semiBold(titleLarge), titleMedium = semiBold(titleMedium), titleSmall = semiBold(titleSmall))
}

/** Light and dark follow the system. */
@Composable
fun AlphaTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = alphaColorScheme(LocalContext.current, isSystemInDarkTheme()), motionScheme = MotionScheme.expressive(),
        shapes = AlphaShapes, typography = AlphaTypography, content = content)
}

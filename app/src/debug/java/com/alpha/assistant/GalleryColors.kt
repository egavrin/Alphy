package com.alpha.assistant

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Solid wallpaper seeds #4285F4 and #3D845F, converted with Material Color Utilities 0.3.0.
// SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0) supplies each ColorScheme role.
internal fun galleryWallpaperColors(name: String, dark: Boolean): ColorScheme = when (name to dark) {
    ("blue" to false) -> lightColorScheme(
        primary = Color(0xFF445E91), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFD8E2FF),
        onPrimaryContainer = Color(0xFF2B4678), inversePrimary = Color(0xFFADC6FF), secondary = Color(0xFF575E71),
        onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFDBE2F9), onSecondaryContainer = Color(0xFF3F4759),
        tertiary = Color(0xFF715573), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFFBD7FC),
        onTertiaryContainer = Color(0xFF583E5B), background = Color(0xFFF9F9FF), onBackground = Color(0xFF1A1B20),
        surface = Color(0xFFF9F9FF), onSurface = Color(0xFF1A1B20), surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44474F), inverseSurface = Color(0xFF2F3036), inverseOnSurface = Color(0xFFF0F0F7),
        error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A), outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
        scrim = Color(0xFF000000), surfaceBright = Color(0xFFF9F9FF), surfaceContainer = Color(0xFFEDEDF4),
        surfaceContainerHigh = Color(0xFFE8E7EE), surfaceContainerHighest = Color(0xFFE2E2E9), surfaceContainerLow = Color(0xFFF3F3FA),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceDim = Color(0xFFD9D9E0), primaryFixed = Color(0xFFD8E2FF),
        primaryFixedDim = Color(0xFFADC6FF), onPrimaryFixed = Color(0xFF001A41), onPrimaryFixedVariant = Color(0xFF2B4678),
        secondaryFixed = Color(0xFFDBE2F9), secondaryFixedDim = Color(0xFFBFC6DC), onSecondaryFixed = Color(0xFF141B2C),
        onSecondaryFixedVariant = Color(0xFF3F4759), tertiaryFixed = Color(0xFFFBD7FC), tertiaryFixedDim = Color(0xFFDEBCDF),
        onTertiaryFixed = Color(0xFF29132D), onTertiaryFixedVariant = Color(0xFF583E5B),
        surfaceTint = Color(0xFF445E91),
    )
    ("blue" to true) -> darkColorScheme(
        primary = Color(0xFFADC6FF), onPrimary = Color(0xFF102F60), primaryContainer = Color(0xFF2B4678),
        onPrimaryContainer = Color(0xFFD8E2FF), inversePrimary = Color(0xFF445E91), secondary = Color(0xFFBFC6DC),
        onSecondary = Color(0xFF293041), secondaryContainer = Color(0xFF3F4759), onSecondaryContainer = Color(0xFFDBE2F9),
        tertiary = Color(0xFFDEBCDF), onTertiary = Color(0xFF402843), tertiaryContainer = Color(0xFF583E5B),
        onTertiaryContainer = Color(0xFFFBD7FC), background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
        surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9), surfaceVariant = Color(0xFF44474F),
        onSurfaceVariant = Color(0xFFC4C6D0), inverseSurface = Color(0xFFE2E2E9), inverseOnSurface = Color(0xFF2F3036),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6), outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474F),
        scrim = Color(0xFF000000), surfaceBright = Color(0xFF37393E), surfaceContainer = Color(0xFF1E1F25),
        surfaceContainerHigh = Color(0xFF282A2F), surfaceContainerHighest = Color(0xFF33353A), surfaceContainerLow = Color(0xFF1A1B20),
        surfaceContainerLowest = Color(0xFF0C0E13), surfaceDim = Color(0xFF111318), primaryFixed = Color(0xFFD8E2FF),
        primaryFixedDim = Color(0xFFADC6FF), onPrimaryFixed = Color(0xFF001A41), onPrimaryFixedVariant = Color(0xFF2B4678),
        secondaryFixed = Color(0xFFDBE2F9), secondaryFixedDim = Color(0xFFBFC6DC), onSecondaryFixed = Color(0xFF141B2C),
        onSecondaryFixedVariant = Color(0xFF3F4759), tertiaryFixed = Color(0xFFFBD7FC), tertiaryFixedDim = Color(0xFFDEBCDF),
        onTertiaryFixed = Color(0xFF29132D), onTertiaryFixedVariant = Color(0xFF583E5B),
        surfaceTint = Color(0xFFADC6FF),
    )
    ("green" to false) -> lightColorScheme(
        primary = Color(0xFF276A49), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFACF2C7),
        onPrimaryContainer = Color(0xFF045233), inversePrimary = Color(0xFF91D5AC), secondary = Color(0xFF4E6355),
        onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFD0E8D7), onSecondaryContainer = Color(0xFF374B3E),
        tertiary = Color(0xFF3C6471), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFBFE9F9),
        onTertiaryContainer = Color(0xFF224C59), background = Color(0xFFF6FBF4), onBackground = Color(0xFF171D19),
        surface = Color(0xFFF6FBF4), onSurface = Color(0xFF171D19), surfaceVariant = Color(0xFFDCE5DC),
        onSurfaceVariant = Color(0xFF404942), inverseSurface = Color(0xFF2C322D), inverseOnSurface = Color(0xFFEDF2EB),
        error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A), outline = Color(0xFF707972), outlineVariant = Color(0xFFC0C9C0),
        scrim = Color(0xFF000000), surfaceBright = Color(0xFFF6FBF4), surfaceContainer = Color(0xFFEAEFE9),
        surfaceContainerHigh = Color(0xFFE4EAE3), surfaceContainerHighest = Color(0xFFDFE4DD), surfaceContainerLow = Color(0xFFF0F5EE),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceDim = Color(0xFFD6DBD5), primaryFixed = Color(0xFFACF2C7),
        primaryFixedDim = Color(0xFF91D5AC), onPrimaryFixed = Color(0xFF002112), onPrimaryFixedVariant = Color(0xFF045233),
        secondaryFixed = Color(0xFFD0E8D7), secondaryFixedDim = Color(0xFFB5CCBB), onSecondaryFixed = Color(0xFF0B1F15),
        onSecondaryFixedVariant = Color(0xFF374B3E), tertiaryFixed = Color(0xFFBFE9F9), tertiaryFixedDim = Color(0xFFA4CDDC),
        onTertiaryFixed = Color(0xFF001F27), onTertiaryFixedVariant = Color(0xFF224C59),
        surfaceTint = Color(0xFF276A49),
    )
    ("green" to true) -> darkColorScheme(
        primary = Color(0xFF91D5AC), onPrimary = Color(0xFF003921), primaryContainer = Color(0xFF045233),
        onPrimaryContainer = Color(0xFFACF2C7), inversePrimary = Color(0xFF276A49), secondary = Color(0xFFB5CCBB),
        onSecondary = Color(0xFF203529), secondaryContainer = Color(0xFF374B3E), onSecondaryContainer = Color(0xFFD0E8D7),
        tertiary = Color(0xFFA4CDDC), onTertiary = Color(0xFF043542), tertiaryContainer = Color(0xFF224C59),
        onTertiaryContainer = Color(0xFFBFE9F9), background = Color(0xFF0F1511), onBackground = Color(0xFFDFE4DD),
        surface = Color(0xFF0F1511), onSurface = Color(0xFFDFE4DD), surfaceVariant = Color(0xFF404942),
        onSurfaceVariant = Color(0xFFC0C9C0), inverseSurface = Color(0xFFDFE4DD), inverseOnSurface = Color(0xFF2C322D),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6), outline = Color(0xFF8A938B), outlineVariant = Color(0xFF404942),
        scrim = Color(0xFF000000), surfaceBright = Color(0xFF353B36), surfaceContainer = Color(0xFF1B211D),
        surfaceContainerHigh = Color(0xFF262B27), surfaceContainerHighest = Color(0xFF303632), surfaceContainerLow = Color(0xFF171D19),
        surfaceContainerLowest = Color(0xFF0A0F0C), surfaceDim = Color(0xFF0F1511), primaryFixed = Color(0xFFACF2C7),
        primaryFixedDim = Color(0xFF91D5AC), onPrimaryFixed = Color(0xFF002112), onPrimaryFixedVariant = Color(0xFF045233),
        secondaryFixed = Color(0xFFD0E8D7), secondaryFixedDim = Color(0xFFB5CCBB), onSecondaryFixed = Color(0xFF0B1F15),
        onSecondaryFixedVariant = Color(0xFF374B3E), tertiaryFixed = Color(0xFFBFE9F9), tertiaryFixedDim = Color(0xFFA4CDDC),
        onTertiaryFixed = Color(0xFF001F27), onTertiaryFixedVariant = Color(0xFF224C59),
        surfaceTint = Color(0xFF91D5AC),
    )
    else -> error("Unknown gallery wallpaper")
}

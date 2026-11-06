package com.alpha.assistant.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alpha.assistant.ui.AlphaDarkColors
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.AlphaLightColors
import com.alpha.assistant.ui.Glyph

internal val MiniAppsLightColors = AlphaLightColors.copy(
    primary = Color(0xFF3F4CC7), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E7FB), onPrimaryContainer = Color(0xFF161B31),
    secondary = Color(0xFF3657BE), secondaryContainer = Color(0xFFDDE8FE), onSecondaryContainer = Color(0xFF243D8E),
    tertiary = Color(0xFF6237B9), tertiaryContainer = Color(0xFFEDE3FB), onTertiaryContainer = Color(0xFF5125A6),
    background = Color(0xFFF6F7FC), onBackground = Color(0xFF171C2B),
    surface = Color(0xFFF6F7FC), onSurface = Color(0xFF171C2B),
    surfaceVariant = Color(0xFFE8EAF5), onSurfaceVariant = Color(0xFF505870),
    outline = Color(0xFF757D98), outlineVariant = Color(0xFFD8DBE9), surfaceTint = Color(0xFF3F4CC7),
    surfaceBright = Color(0xFFF6F7FC), surfaceDim = Color(0xFFDADDEB),
    surfaceContainerLow = Color(0xFFF0F1F9), surfaceContainer = Color(0xFFECEDF8),
    surfaceContainerHigh = Color(0xFFE6E8F7), surfaceContainerHighest = Color(0xFFDFE2F3),
)

internal val MiniAppsDarkColors = AlphaDarkColors.copy(
    primary = Color(0xFFBDC3FF), onPrimary = Color(0xFF202559),
    primaryContainer = Color(0xFF2B3059), onPrimaryContainer = Color(0xFFECEEFF),
    secondary = Color(0xFFB7CBFF), secondaryContainer = Color(0xFF253657), onSecondaryContainer = Color(0xFFD5E2FF),
    tertiary = Color(0xFFD7B9FF), tertiaryContainer = Color(0xFF3B2B54), onTertiaryContainer = Color(0xFFEAD7FF),
    background = Color(0xFF141624), onBackground = Color(0xFFECEEFF),
    surface = Color(0xFF141624), onSurface = Color(0xFFECEEFF),
    surfaceVariant = Color(0xFF282D46), onSurfaceVariant = Color(0xFFC2C7E2),
    outline = Color(0xFF929AB7), outlineVariant = Color(0xFF414762), surfaceTint = Color(0xFFBDC3FF),
    surfaceBright = Color(0xFF373D58), surfaceDim = Color(0xFF141624),
    surfaceContainerLow = Color(0xFF1C2033), surfaceContainer = Color(0xFF22263B),
    surfaceContainerHigh = Color(0xFF292E46), surfaceContainerHighest = Color(0xFF323751),
)

private enum class AppBadge(val glyph: Glyph, vararg words: String) {
    Calculator(Glyph.Calculator, "калькулятор", "calculator"),
    Shopping(Glyph.Cart, "покупки", "покупок", "shopping", "groceries", "grocery", "cart"),
    Travel(Glyph.Suitcase, "поездка", "поездки", "путешествие", "путешествия", "travel", "trip"),
    Habits(Glyph.Check, "привычки", "привычек", "habit", "habits"),
    Timer(Glyph.Timer, "таймер", "таймеры", "timer", "timers", "stopwatch", "pomodoro", "помодоро");

    val keywords = words.toSet()
}

private val titleWords = Regex("\\p{L}+")

@Composable internal fun MiniAppBadge(title: String?, size: Dp = 44.dp, draft: Boolean = false) {
    val category = remember(title) {
        val words = titleWords.findAll(title.orEmpty().lowercase()).map { it.value }.toSet()
        AppBadge.entries.singleOrNull { badge -> words.any { it in badge.keywords } }
    }
    val colors = MaterialTheme.colorScheme
    val (well, ink) = when (category) {
        AppBadge.Shopping -> colors.secondaryContainer to colors.secondary
        AppBadge.Timer -> colors.tertiaryContainer to colors.tertiary
        AppBadge.Habits -> colors.primaryContainer to colors.primary
        else -> colors.surfaceContainerHighest to colors.primary
    }
    Surface(Modifier.size(size), shape = RoundedCornerShape(12.dp), color = well) {
        Box(contentAlignment = Alignment.Center) {
            AlphaIcon(category?.glyph ?: if (draft) Glyph.Compose else Glyph.Grid,
                Modifier.size(if (size > 44.dp) 32.dp else 28.dp), color = ink)
        }
    }
}

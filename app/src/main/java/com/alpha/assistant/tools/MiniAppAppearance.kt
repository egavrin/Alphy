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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph

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

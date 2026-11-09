package com.alpha.assistant.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph

internal enum class AppBadge(val id: String, val glyph: Glyph) {
    Calculator("calculator", Glyph.Calculator),
    Shopping("shopping", Glyph.Cart),
    Travel("travel", Glyph.Suitcase),
    Habits("habits", Glyph.Check),
    Timer("timer", Glyph.Timer);
}

@Composable internal fun MiniAppBadge(iconId: String?, size: Dp = 44.dp, draft: Boolean = false) {
    val category = AppBadge.entries.firstOrNull { it.id == iconId }
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

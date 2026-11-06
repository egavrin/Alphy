package com.alpha.assistant.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.alpha.assistant.R

internal data class ContextMenuAnchor(val button: IntRect, val row: IntRect)

internal data class ContextSourceAction(@StringRes val label: Int, val icon: Glyph, val onClick: () -> Unit)

@Composable internal fun AnchoredContextSourceMenu(
    anchor: ContextMenuAnchor,
    top: Int,
    dismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val width = with(density) { anchor.row.width.toDp().coerceAtMost(360.dp) }
    val height = with(density) { (anchor.button.top - top - gap).toDp() }
    val position = object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
        ): IntOffset {
            val start = if (layoutDirection == LayoutDirection.Ltr) anchor.button.left else anchor.button.right - popupContentSize.width
            val maxWindowX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
            val minX = anchor.row.left.coerceIn(0, maxWindowX)
            val maxX = (anchor.row.right - popupContentSize.width).coerceIn(minX, maxWindowX)
            return IntOffset(start.coerceIn(minX, maxX), (anchor.button.top - gap - popupContentSize.height).coerceAtLeast(top))
        }
    }
    Popup(position, onDismissRequest = dismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            modifier = Modifier.width(width).heightIn(max = height),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
        }
    }
}

@Composable internal fun ContextSourceMenu(
    tiles: List<ContextSourceAction>,
    rows: List<ContextSourceAction>,
    enabled: Boolean,
    canClose: Boolean,
    close: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.context_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            val cancel = stringResource(R.string.cancel)
            FilledTonalIconButton(
                onClick = close,
                modifier = Modifier.semantics { contentDescription = cancel },
                enabled = canClose,
            ) {
                AlphaIcon(Glyph.Close, color = LocalContentColor.current)
            }
        }
        Text(stringResource(R.string.context_draft_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { action ->
                Surface(
                    onClick = action.onClick,
                    modifier = Modifier.weight(1f).fillMaxHeight().semantics { this.role = Role.Button },
                    enabled = enabled,
                    shape = MaterialTheme.shapes.medium,
                    color = colors.secondaryContainer,
                ) {
                    Column(
                        Modifier.heightIn(min = 76.dp).padding(horizontal = 4.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    ) {
                        AlphaIcon(action.icon, accent = enabled)
                        Text(stringResource(action.label), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Column {
            rows.forEach { action ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .clickable(enabled = enabled, role = Role.Button, onClick = action.onClick)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlphaIcon(action.icon, accent = enabled)
                    Text(stringResource(action.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    AlphaIcon(Glyph.Chevron)
                }
            }
        }
    }
}

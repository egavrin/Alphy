package com.alpha.assistant.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import java.text.DateFormat
import java.util.Date

internal data class DesktopTileActions(
    val open: () -> Unit,
    val remove: (() -> Unit)?,
    val moveEarlier: (() -> Unit)?,
    val moveLater: (() -> Unit)?,
)

@Composable
internal fun DesktopToolTile(tool: ToolRecord, editing: Boolean, actions: DesktopTileActions) {
    val available = remember(tool.spec) { ToolSpec.availablePersisted(tool.spec) != null }
    DesktopTile(ToolSpec.storedTitle(tool.spec), editing, actions, appIcon = true) {
        if (!available) {
            Text(stringResource(R.string.tool_closed_format), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun DesktopAutomationTile(item: DesktopAutomation, editing: Boolean, actions: DesktopTileActions,
    switchEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    val rule = item.rule
    val title = rule.definition.name
    val active = rule.enabled && rule.approvedRevision == rule.revision && rule.attention.isEmpty()
    val locale = LocalConfiguration.current.locales[0]
    DesktopTile(title, editing, actions) {
        if (!editing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (active) R.string.desktop_automation_active else R.string.desktop_automation_paused),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                val switchLabel = stringResource(R.string.desktop_automation_switch, title)
                Switch(active, onToggle, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                    contentDescription = switchLabel
                }, enabled = switchEnabled)
            }
            val last = item.lastRun
            Text(if (last == null) stringResource(R.string.desktop_automation_no_run) else
                stringResource(R.string.desktop_automation_last_run,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(last.time)), last.message),
                maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (rule.attention.isNotBlank()) {
                Text(rule.attention, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (item.needsAttention) {
                Text(stringResource(R.string.desktop_now_automation_attention), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun DesktopTile(title: String, editing: Boolean, actions: DesktopTileActions, appIcon: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val openLabel = stringResource(R.string.desktop_open_tile, title)
    val removeLabel = stringResource(R.string.desktop_remove_tile, title)
    val opener = if (editing) Modifier else Modifier.combinedClickable(role = Role.Button, onClick = actions.open,
        onLongClick = actions.remove, onLongClickLabel = removeLabel).semantics { contentDescription = openLabel }
    Card(Modifier.fillMaxWidth().then(if (appIcon) opener else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (appIcon) Modifier else opener),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (appIcon) MiniAppIcon(title)
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium.merge(RussianHyphenation))
                if (!editing) AlphaIcon(Glyph.Chevron)
            }
            content()
            if (editing) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DesktopEditButton(Glyph.Arrow, stringResource(R.string.desktop_move_earlier, title), actions.moveEarlier)
                DesktopEditButton(Glyph.Arrow, stringResource(R.string.desktop_move_later, title), actions.moveLater, rotation = 180f)
                DesktopEditButton(Glyph.Close, removeLabel, actions.remove)
            }
        }
    }
}

@Composable internal fun MiniAppIcon(title: String) {
    val initial = title.firstOrNull(Char::isLetterOrDigit)?.uppercase()
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (Math.floorMod(title.hashCode(), 3)) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Surface(Modifier.size(44.dp).clearAndSetSemantics { hideFromAccessibility() },
        shape = MaterialTheme.shapes.medium, color = background, contentColor = foreground) {
        Box(contentAlignment = Alignment.Center) {
            if (initial == null) AlphaIcon(Glyph.Grid) else Text(initial, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun DesktopEditButton(glyph: Glyph, label: String, action: (() -> Unit)?, rotation: Float = 0f) {
    IconButton(onClick = { action?.invoke() }, enabled = action != null,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        AlphaIcon(glyph, Modifier.rotate(rotation), color = if (action == null)
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

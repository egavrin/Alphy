package com.alpha.assistant.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import com.alpha.assistant.surfaces.WidgetProjection
import com.alpha.assistant.surfaces.projectWidget
import com.alpha.assistant.surfaces.widgetActionId
import com.alpha.assistant.tools.ToolContent
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private val desktopTilePadding = 12.dp

internal data class DesktopTileActions(
    val open: () -> Unit,
    val remove: (() -> Unit)?,
    val moveEarlier: (() -> Unit)?,
    val moveLater: (() -> Unit)?,
)

@Composable
internal fun DesktopToolTile(tool: ToolRecord, editing: Boolean, actions: DesktopTileActions,
    visible: Boolean = false, onAction: ((String, Any?, () -> Boolean) -> Unit)? = null) {
    val available = remember(tool.spec) { ToolSpec.availablePersisted(tool.spec) != null }
    val title = remember(tool.spec) { ToolSpec.storedTitle(tool.spec) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = !editing && LocalDensity.current.fontScale < 1.5f && maxWidth - desktopTilePadding * 2 >= 200.dp
        val active = remember(tool.id, compact, visible) { mutableStateOf(compact && visible) }
        DisposableEffect(active) { onDispose { active.value = false } }
        val projection = if (available && compact) remember(tool) { runCatching { projectWidget(tool) }.getOrNull() } else null
        val content = remember(projection) { projection?.desktopContent() }
        DesktopTile(title, editing, actions, appIcon = true, openFromHeader = content != null) {
            when {
                !available -> Text(stringResource(R.string.tool_closed_format), style = MaterialTheme.typography.bodySmall)
                content != null -> ToolContent(content, tool.state, enabled = active.value && onAction != null, heading = title,
                    embedded = true, background = MaterialTheme.colorScheme.surfaceVariant) { action, value ->
                    onAction?.invoke(action, value) { active.value }
                }
                projection != null -> Text(stringResource(R.string.widget_surface), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun WidgetProjection.desktopContent(): String? {
    val action = nodes.firstOrNull { it.widgetActionId() != null }
    val rows = nodes.filter { node ->
        // The widget's synthetic summaries have no enabled flag. Use the localized tile fallback for them.
        node.has("enabled") && when (node.getString("type")) {
            "text" -> !node.has("spans")
            "metric", "fact", "progress", "progressRing" -> true
            "timer" -> !node.has("foreground")
            else -> false
        }
    }.sortedByDescending { it.has("bind") }.take(if (action == null) 3 else 2) + listOfNotNull(action)
    if (rows.isEmpty()) return null
    val children = rows.map { node ->
        when (node.getString("type")) {
            "text", "metric", "fact" -> JSONObject().put("type", "text")
                .put("text", ToolUiCatalog.widgetLabel(node, state)).put("maxLines", 2).put("overflow", "ellipsis")
            else -> JSONObject(node.toString()).apply {
                remove("layout")
                remove("style")
                remove("semantics")
            }
        }
    }
    return JSONObject().put("type", "column").put("style", JSONObject().put("gap", "small"))
        .put("children", JSONArray(children)).toString()
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
    openFromHeader: Boolean = !appIcon,
    content: @Composable ColumnScope.() -> Unit) {
    val openLabel = stringResource(R.string.desktop_open_tile, title)
    val removeLabel = stringResource(R.string.desktop_remove_tile, title)
    val opener = if (editing) Modifier else Modifier.combinedClickable(role = Role.Button, onClick = actions.open,
        onLongClick = actions.remove, onLongClickLabel = removeLabel).semantics { contentDescription = openLabel }
    Card(Modifier.fillMaxWidth().then(if (openFromHeader) Modifier else opener),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(desktopTilePadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (openFromHeader) opener else Modifier),
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

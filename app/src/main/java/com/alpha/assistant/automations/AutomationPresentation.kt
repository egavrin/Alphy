package com.alpha.assistant.automations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.ui.GlyphButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val automationTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

@Composable internal fun automationRunTime(at: Long): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val today = LocalDate.now(time.zone)
    val clock = time.format(automationTimeFormat)
    return when (time.toLocalDate()) {
        today -> stringResource(R.string.automation_time_today, clock)
        today.minusDays(1) -> stringResource(R.string.automation_time_yesterday, clock)
        else -> date(at)
    }
}

@Composable internal fun automationTriggerSummary(definition: AutomationDefinition): String {
    val trigger = definition.trigger
    return when (definition.type) {
        "schedule" -> {
            val repeat = when (trigger.getString("repeat")) {
                "daily" -> stringResource(R.string.automation_editor_daily)
                "once" -> trigger.getString("date")
                else -> {
                    val names = stringArrayResource(R.array.automation_editor_weekdays)
                    val days = trigger.getJSONArray("days")
                    (0 until days.length()).joinToString(", ") { names[days.getInt(it) - 1] }
                }
            }
            stringResource(R.string.automation_schedule_summary, repeat, trigger.getString("time"))
        }
        "interval" -> stringResource(R.string.automation_interval_summary, trigger.getInt("minutes"))
        "notification" -> stringResource(R.string.automation_editor_notification)
        "geofence" -> stringResource(if (trigger.getString("transition") == "enter") {
            R.string.automation_editor_enter
        } else R.string.automation_editor_exit)
        else -> triggerText(definition)
    }
}

@Composable internal fun AutomationRuleList(
    rules: List<AutomationRule>,
    retired: List<AutomationStore.RetiredRule>,
    histories: Map<String,List<AutomationRun>>,
    loaded: Boolean,
    selectedId: String?,
    state: LazyListState,
    error: String?,
    enabled: Boolean,
    create: () -> Unit,
    select: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight().testTag("automation-list")) {
        if (error != null) AutomationError(error, Modifier.padding(16.dp))
        if (rules.isEmpty() && retired.isEmpty() && loaded && error == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(76.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    AlphaIcon(Glyph.Bolt, Modifier.size(42.dp), accent = true)
                }
                Text(stringResource(R.string.automation_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(stringResource(R.string.automation_empty_chat), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                items(rules, key = { it.id }) { rule ->
                    val selected = rule.id == selectedId
                    val label = stringResource(R.string.tool_open_automation, rule.definition.name)
                    val next = rule.nextAt?.takeIf { rule.enabled }
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .selectable(selected, enabled = enabled, role = Role.Button, onClick = { select(rule.id) })
                            .semantics { contentDescription = label }
                            .heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlphaIcon(when (rule.definition.type) {
                            "schedule" -> Glyph.Alarm
                            "interval" -> Glyph.Repeat
                            "charging" -> Glyph.Bolt
                            "geofence" -> Glyph.Pin
                            "notification" -> Glyph.Bell
                            else -> if (rule.definition.actions.any { it.capability == "maps.open" }) Glyph.Pin else Glyph.Calendar
                        })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(rule.definition.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                AutomationStatus(rule.enabled)
                            }
                            Text(
                                if (next == null) automationTriggerSummary(rule.definition)
                                else stringResource(R.string.automation_next_run_value, automationRunTime(next)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AlphaIcon(Glyph.Chevron, Modifier.size(16.dp))
                    }
                    if (!selected) HorizontalDivider(Modifier.padding(horizontal = 8.dp))
                }
                items(retired, key = { it.id }) { rule ->
                    RetiredAutomationRule(rule,histories[rule.id].orEmpty())
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        Button(
            onClick = create, enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            AlphaIcon(Glyph.Chat, Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.automation_create_chat))
        }
    }
}

@Composable internal fun RetiredAutomationRule(rule: AutomationStore.RetiredRule, history: List<AutomationRun>) {
    var expanded by remember(rule.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(rule.name, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.automation_calendar_sync_retired), style = MaterialTheme.typography.bodySmall)
        if (history.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.automation_retired_history)) }
            if (expanded) history.forEach { AutomationHistoryRow(it, canContinue = false) {} }
        }
    }
}

@Composable internal fun AutomationHeading(title: String, back: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) GlyphButton(Glyph.Back, stringResource(R.string.automation_back), back)
        Text(title, Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable internal fun AutomationStatus(enabled: Boolean) {
    val dark = isSystemInDarkTheme()
    val fill = if (enabled) {
        if (dark) Color(0xFF1A462B) else Color(0xFFCFEFD8)
    } else if (dark) Color(0xFF4C3314) else Color(0xFFFFE9BF)
    val text = if (enabled) {
        if (dark) Color(0xFFCFEFD8) else Color(0xFF174E2D)
    } else if (dark) Color(0xFFFFE9BF) else Color(0xFF693B10)
    Text(
        stringResource(if (enabled) R.string.automation_enabled else R.string.automation_paused),
        Modifier.background(fill, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        color = text, style = MaterialTheme.typography.labelMedium,
    )
}

@Composable internal fun AutomationSummaryRow(
    glyph: Glyph, label: String, value: String,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interaction = if (onClick == null) Modifier else Modifier
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }
    Row(
        Modifier.fillMaxWidth().then(interaction).heightIn(min = 48.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlphaIcon(glyph, Modifier.size(19.dp))
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.titleSmall)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
    HorizontalDivider()
}

internal val AutomationEditorSection.glyph: Glyph
    get() = when (this) {
        AutomationEditorSection.WHEN -> Glyph.Calendar
        AutomationEditorSection.IF -> Glyph.Filter
        AutomationEditorSection.ACTIONS -> Glyph.Bolt
    }

@Composable internal fun AutomationError(error: String, modifier: Modifier = Modifier) {
    Text(error, modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small).padding(12.dp),
        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
}

@Composable internal fun AutomationNumber(number: Int) {
    Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(number.toString(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable internal fun AutomationRuleDetail(
    rule: AutomationRule,
    history: List<AutomationRun>,
    sections: List<Pair<AutomationEditorSection, String>>,
    busy: Boolean,
    primaryEnabled: Boolean,
    testEnabled: Boolean,
    error: String?,
    back: (() -> Unit)?,
    showAll: (() -> Unit)?,
    toggle: () -> Unit,
    edit: (AutomationEditorSection) -> Unit,
    test: () -> Unit,
    dryRun: () -> Unit,
    run: () -> Unit,
    delete: () -> Unit,
    continueRun: (AutomationRun) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            AutomationHeading(rule.definition.name, back)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AutomationStatus(rule.enabled)
                Text(stringResource(R.string.automation_revision, rule.revision), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (showAll != null) TextButton(onClick = showAll) { Text(stringResource(R.string.automation_all)) }
            AutomationRuleActions(rule, busy, primaryEnabled, testEnabled, toggle, { edit(AutomationEditorSection.WHEN) }, test, dryRun, run, delete)
            if (rule.attention.isNotEmpty()) {
                AutomationError(rule.attention, Modifier.padding(bottom = 8.dp))
            }
            if (error != null && error != rule.attention) {
                AutomationError(error, Modifier.padding(bottom = 8.dp))
            }
            HorizontalDivider()
            sections.forEach { (section, summary) ->
                AutomationSummaryRow(section.glyph, stringResource(section.title), summary, { edit(section) }, enabled = !busy)
            }
            val next = rule.nextAt?.takeIf { rule.enabled }
            val last = history.firstOrNull()
            if (next != null || last != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                if (next != null) AutomationSummaryRow(Glyph.Clock, stringResource(R.string.automation_next_run), automationRunTime(next))
                if (last != null) AutomationSummaryRow(Glyph.History, stringResource(R.string.automation_last_run), automationRunTime(last.time))
            }
            Text(
                stringResource(R.string.automation_history),
                Modifier.padding(top = 20.dp, bottom = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            HorizontalDivider()
            if (history.isEmpty()) {
                Text(stringResource(R.string.automation_history_empty), Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(history, key = { it.id }) { run ->
            AutomationHistoryRow(run, !busy && rule.enabled && rule.revision == run.revision) { continueRun(run) }
        }
    }
}

@Composable private fun AutomationRuleActions(
    rule: AutomationRule, busy: Boolean, primaryEnabled: Boolean, testEnabled: Boolean,
    toggle: () -> Unit, edit: () -> Unit, test: () -> Unit, dryRun: () -> Unit, run: () -> Unit, delete: () -> Unit,
) {
    val primary = stringResource(when {
        rule.enabled -> R.string.automation_pause
        rule.approvedRevision != rule.revision || rule.attention.isNotEmpty() -> R.string.automation_review_enable
        else -> R.string.automation_enable
    })
    var menuOpen by remember { mutableStateOf(false) }
    val actionPadding = PaddingValues(horizontal = 8.dp)
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(
            onClick = toggle, enabled = primaryEnabled,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small, contentPadding = actionPadding,
        ) {
            AlphaIcon(if (rule.enabled) Glyph.Pause else Glyph.Play, Modifier.size(16.dp), color = LocalContentColor.current)
            Spacer(Modifier.width(4.dp))
            Text(primary)
        }
        OutlinedButton(
            onClick = edit, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small, contentPadding = actionPadding,
        ) {
            AlphaIcon(Glyph.Compose, Modifier.size(16.dp), color = LocalContentColor.current)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.automation_edit))
        }
        Box {
            val label = stringResource(R.string.automation_rule_options)
            OutlinedIconButton(
                onClick = { menuOpen = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = label },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) { AlphaIcon(Glyph.MoreVertical) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (rule.enabled) {
                    AutomationMenuItem(R.string.automation_run, Glyph.Play, !busy) { menuOpen = false; run() }
                } else {
                    AutomationMenuItem(R.string.automation_test_once, Glyph.Play, testEnabled) { menuOpen = false; test() }
                }
                AutomationMenuItem(R.string.automation_dry_run, Glyph.Flask, !busy) { menuOpen = false; dryRun() }
                AutomationMenuItem(R.string.delete, Glyph.Trash, !busy) { menuOpen = false; delete() }
            }
        }
    }
}

@Composable private fun AutomationMenuItem(label: Int, glyph: Glyph, enabled: Boolean, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = action,
        enabled = enabled,
        leadingIcon = { AlphaIcon(glyph, Modifier.size(18.dp), color = LocalContentColor.current) },
    )
}

@Composable private fun AutomationHistoryRow(run: AutomationRun, canContinue: Boolean, continueRun: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (glyph, tint) = when (run.status) {
        "succeeded", "launched" -> Glyph.Check to if (isSystemInDarkTheme()) Color(0xFF8ADEA4) else Color(0xFF19743C)
        "dryRun" -> Glyph.Flask to colors.primary
        "cancelled", "skipped", "missed", "awaitingUser" -> Glyph.Clock to colors.onSurfaceVariant
        else -> Glyph.Close to colors.error
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp).background(tint.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            AlphaIcon(glyph, Modifier.size(16.dp), color = tint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (run.status == "succeeded") stringResource(R.string.automation_run_succeeded) else status(run.status),
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.automation_run_metadata, automationRunTime(run.time), run.revision),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (run.message.isNotBlank()) Text(run.message, style = MaterialTheme.typography.bodySmall)
            val details = org.json.JSONArray(run.details)
            repeat(details.length()) { index ->
                val data = details.getJSONObject(index).optJSONObject("data")
                data?.optString("preview")?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                data?.optJSONArray("events")?.let { events ->
                    repeat(events.length()) { eventIndex ->
                        val event = events.getJSONObject(eventIndex)
                        Text("${event.optString("title")} · ${date(event.optLong("start"))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (run.status == "awaitingUser") {
                Text("Alphy откроет только указанную цель. Действие внутри приложения выполняешь ты.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = canContinue, onClick = continueRun) { Text("Продолжить в приложении") }
            }
        }
    }
    HorizontalDivider()
}

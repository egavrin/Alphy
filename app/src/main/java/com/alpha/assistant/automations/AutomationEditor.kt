package com.alpha.assistant.automations

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal enum class AutomationEditorSection(@StringRes val title: Int) {
    WHEN(R.string.automation_editor_when),
    IF(R.string.automation_editor_if),
    ACTIONS(R.string.automation_editor_do)
}

private val triggerLabels=linkedMapOf(
    "manual" to R.string.automation_editor_manual,
    "schedule" to R.string.automation_editor_schedule,
    "interval" to R.string.automation_editor_interval,
    "charging" to R.string.automation_editor_charging,
    "geofence" to R.string.automation_editor_geofence,
    "notification" to R.string.automation_editor_notification,
)

private fun newTrigger(type: String): JSONObject = JSONObject().put("type",type).apply {
    when(type) {
        "schedule" -> {
            val next=LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0)
            put("repeat","once").put("time",next.toLocalTime().toString()).put("date",next.toLocalDate().toString())
            put("zone","device").put("exact",false)
        }
        "interval" -> put("minutes",15).put("anchor",System.currentTimeMillis()+15*60_000L)
        "charging" -> put("connection","connected")
        "geofence" -> put("latitude","").put("longitude","").put("radius",200).put("transition","enter")
        "notification" -> put("packages",JSONArray())
    }
}

private fun triggerForSave(draft: JSONObject,original: JSONObject?): JSONObject = JSONObject(draft.toString()).apply {
    when(getString("type")) {
        "interval" -> {
            val minutes=get("minutes").toString().toInt()
            put("minutes",minutes)
            if(original?.optString("type")=="interval" && original.getInt("minutes")==minutes) put("anchor",original.getLong("anchor"))
            else if(optLong("anchor")==0L || original?.optString("type")=="interval") {
                put("anchor",System.currentTimeMillis()+minutes*60_000L)
            }
        }
        "geofence" -> listOf("latitude","longitude","radius").forEach { key ->
            (opt(key) as? String)?.let {put(key,it.toDouble())}
        }
        "schedule" -> {
            val repeat=getString("repeat")
            if(repeat!="once") remove("date")
            if(repeat!="weekly") remove("days")
        }
    }
}

internal data class AutomationEditTarget(val id: String, val revision: Int, val definition: AutomationDefinition)

internal val automationEditTargetSaver = listSaver<AutomationEditTarget?, Any>(
    save = { target -> target?.let { listOf(it.id, it.revision, it.definition.json) }.orEmpty() },
    restore = { AutomationEditTarget(it[0] as String, it[1] as Int, AutomationDefinition.parse(JSONObject(it[2] as String))) },
)

private val automationJsonSaver = Saver<JSONObject, String>(save = { it.toString() }, restore = ::JSONObject)

@Composable internal fun AutomationEditor(
    rule: AutomationEditTarget?, tools: ToolController, proposal: AutomationDefinition?,
    initialSection: AutomationEditorSection, appLabels: Map<String,String>, busy: Boolean,
    storeFailure: String?, back: Boolean, cancel: () -> Unit, testAgent: (AutomationDefinition) -> Unit, save: (JSONObject) -> Unit
) {
    val old=rule?.definition
    val defaultName=stringResource(R.string.automation_editor_default_name)
    val notice=stringResource(R.string.automation_editor_action_notice)
    val invalid=stringResource(R.string.automation_editor_invalid)
    val staleTarget=stringResource(R.string.automation_editor_pick_current_target)
    var name by rememberSaveable { mutableStateOf(old?.name ?: proposal?.name ?: defaultName) }
    var trigger by rememberSaveable(stateSaver = automationJsonSaver) {
        mutableStateOf(old?.trigger ?: proposal?.trigger ?: newTrigger("manual"))
    }
    var conditions by rememberSaveable(stateSaver = automationJsonSaver) { mutableStateOf(old?.conditions ?: proposal?.conditions ?: JSONObject()) }
    var actions by rememberSaveable(stateSaver = automationActionDraftsSaver) {
        mutableStateOf((old ?: proposal)?.actions?.map { AutomationActionDraft(it) }
            ?: listOf(newAutomationAction("notification.post",notice)))
    }
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun validatedDraft(use: (AutomationDefinition) -> Unit) {
        runCatching {
            val input = JSONObject().put("name", name).put("trigger", triggerForSave(trigger, (old ?: proposal)?.trigger))
                .put("conditions", conditions).put("actions", JSONArray(actions.map { it.output(tools.tools, staleTarget) }))
            AutomationDefinition.parse(input)
        }.onSuccess {
            failure = null
            use(it)
        }.onFailure {
            failure = it.message?.takeIf { message -> message.any { c -> c in 'А'..'я' || c == 'ё' } } ?: invalid
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutomationHeading(
                stringResource(if (rule == null) R.string.automation_editor_create else R.string.automation_editor_edit),
                cancel.takeIf { back && !busy },
            )
            if (proposal != null) {
                Text(stringResource(R.string.automation_agent_proposal), style = MaterialTheme.typography.bodySmall)
            }
            AutomationField(stringResource(R.string.automation_editor_name), name) { name = it }
            SecondaryTabRow(selectedTabIndex = section.ordinal, containerColor = Color.Transparent) {
                AutomationEditorSection.entries.forEach { group ->
                    Tab(
                        selected = section == group, onClick = { section = group },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(stringResource(group.title), style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            when (section) {
                AutomationEditorSection.WHEN -> AutomationTriggerFields(trigger, appLabels) { trigger = it }
                AutomationEditorSection.IF -> AutomationConditionFields(conditions) { conditions = it }
                AutomationEditorSection.ACTIONS -> AutomationActions(actions, tools, notice) { actions = it }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            (failure ?: storeFailure)?.let { message ->
                AutomationError(message, Modifier.padding(bottom = 8.dp))
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(enabled = !busy, onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
                if (actions.singleOrNull()?.capability == AGENT_ACTION) {
                    TextButton(enabled = !busy, onClick = { validatedDraft(testAgent) }) {
                        Text(stringResource(R.string.automation_test_run))
                    }
                }
                Button(enabled = !busy, onClick = { validatedDraft { save(JSONObject(it.json)) } }, modifier = Modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.automation_editor_save))
                }
            }
        }
    }
}

@Composable private fun AutomationTriggerFields(trigger: JSONObject,appLabels: Map<String,String>,changed: (JSONObject)->Unit) {
    val type=trigger.getString("type")
    fun edit(block: JSONObject.()->Unit) {changed(JSONObject(trigger.toString()).apply(block))}
    AutomationChoice(type, triggerLabels.mapValues { stringResource(it.value) },
        label = stringResource(R.string.automation_editor_trigger)) {
        if(it!=type) changed(newTrigger(it))
    }
    when(type) {
        "interval" -> AutomationField(stringResource(R.string.automation_editor_interval_minutes),trigger.get("minutes").toString()) {
            edit {put("minutes",it);put("anchor",0L)}
        }
        "charging" -> AutomationChoice(trigger.getString("connection"),mapOf(
            "connected" to stringResource(R.string.automation_editor_connected),
            "disconnected" to stringResource(R.string.automation_editor_disconnected)
        )) {edit {put("connection",it)}}
        "schedule" -> {
            val repeat=trigger.getString("repeat")
            AutomationChoice(repeat,mapOf(
                "once" to stringResource(R.string.automation_editor_once),
                "daily" to stringResource(R.string.automation_editor_daily),
                "weekly" to stringResource(R.string.automation_editor_weekly)
            ), label = stringResource(R.string.automation_editor_frequency)) { next -> edit {
                put("repeat",next)
                if(next=="once" && !has("date")) put("date",LocalDate.now().toString())
                if(next=="weekly" && !has("days")) put("days",JSONArray(listOf(1,2,3,4,5)))
            } }
            AutomationTime(stringResource(R.string.automation_editor_time),LocalTime.parse(trigger.getString("time"))) {
                edit {put("time",it.toString())}
            }
            if(repeat=="once") AutomationDate(stringResource(R.string.automation_editor_date),LocalDate.parse(trigger.getString("date"))) {
                edit {put("date",it.toString())}
            }
            AutomationZone(trigger.getString("zone")) {edit {put("zone",it)}}
            if(repeat=="weekly") {
                val days=trigger.getJSONArray("days").let { a -> (0 until a.length()).map(a::getInt).toSet() }
                stringArrayResource(R.array.automation_editor_weekdays).forEachIndexed { index,day ->
                    val number=index+1
                    AutomationToggle(day,number in days) { checked ->
                        val next=if(checked) days+number else days-number
                        edit {put("days",JSONArray(next.sorted()))}
                    }
                }
            }
            AutomationSwitch(
                Glyph.Clock, stringResource(R.string.automation_editor_exact), trigger.getBoolean("exact"),
                supportingText = stringResource(R.string.automation_editor_exact_help),
            ) { edit { put("exact", it) } }
        }
        "geofence" -> {
            mapOf("latitude" to R.string.automation_editor_latitude,"longitude" to R.string.automation_editor_longitude,
                "radius" to R.string.automation_editor_radius).forEach { (key,label) ->
                AutomationField(stringResource(label),trigger.get(key).toString()) {edit {put(key,it)}}
            }
            AutomationChoice(trigger.getString("transition"),mapOf(
                "enter" to stringResource(R.string.automation_editor_enter),
                "exit" to stringResource(R.string.automation_editor_exit)
            )) {edit {put("transition",it)}}
        }
        "notification" -> {
            val packages=trigger.getJSONArray("packages").let { a -> (0 until a.length()).map(a::getString) }
            Text(stringResource(R.string.automation_editor_app_sources))
            appLabels.toMutableMap().apply { packages.forEach { putIfAbsent(it,it) } }.toList()
                .sortedBy { it.second }.forEach { (id,label) ->
                    AutomationToggle(label,id in packages,id in packages || packages.size<10) { checked ->
                        edit {put("packages",JSONArray(if(checked) packages+id else packages-id))}
                    }
                }
        }
    }
}

@Composable private fun AutomationConditionFields(conditions: JSONObject,changed: (JSONObject)->Unit) {
    fun edit(block: JSONObject.()->Unit) {changed(JSONObject(conditions.toString()).apply(block))}
    val power = if (conditions.has("charging")) conditions.getBoolean("charging") else null
    val hasWindow = conditions.has("fromMinute")
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Column {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                AutomationSwitch(Glyph.Bolt, stringResource(R.string.automation_editor_only_charging), power == true) { enabled ->
                    edit { if (enabled) put("charging", true) else remove("charging") }
                }
                AutomationChoice(power?.toString() ?: "any", mapOf(
                    "any" to stringResource(R.string.automation_editor_any_power),
                    "false" to stringResource(R.string.automation_editor_disconnected),
                    "true" to stringResource(R.string.automation_editor_connected),
                ), label = stringResource(R.string.automation_editor_power)) { next ->
                    edit { if (next == "any") remove("charging") else put("charging", next.toBoolean()) }
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AutomationSwitch(Glyph.Clock, stringResource(R.string.automation_editor_time_window), hasWindow) { enabled ->
                    edit {
                        if (enabled) put("fromMinute", 8 * 60).put("toMinute", 20 * 60)
                        else { remove("fromMinute"); remove("toMinute") }
                    }
                }
                if (hasWindow) {
                    val start = conditions.getInt("fromMinute")
                    val end = conditions.getInt("toMinute")
                    AutomationTime(stringResource(R.string.automation_editor_from_time), LocalTime.of(start / 60, start % 60)) {
                        edit { put("fromMinute", it.hour * 60 + it.minute) }
                    }
                    AutomationTime(stringResource(R.string.automation_editor_to_time), LocalTime.of(end / 60, end % 60)) {
                        edit { put("toMinute", it.hour * 60 + it.minute) }
                    }
                }
            }
        }
    }
}

@Composable private fun AutomationToggle(label: String,checked: Boolean,enabled: Boolean=true,changed: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=changed),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Checkbox(checked=checked,onCheckedChange=null,enabled=enabled)
        Text(label)
    }
}


@Composable private fun AutomationSwitch(
    glyph: Glyph, label: String, checked: Boolean, supportingText: String? = null,
    changed: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = changed)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AlphaIcon(glyph)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (supportingText != null) Text(supportingText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

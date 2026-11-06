package com.alpha.assistant.automations

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.AndroidCapabilities
import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class AutomationActionDraft(val request: CapabilityRequest, val stateValue: String? = null, val targetChanged: Boolean = false) {
    val id get() = request.id
    val capability get() = request.capability
    val args get() = request.args
    fun edit(block: JSONObject.()->Unit) = copy(request=request.copy(args=JSONObject(args.toString()).apply(block)))

    fun output(tools: List<ToolRecord>, staleTargetMessage: String): JSONObject {
        val next=JSONObject(args.toString())
        when(capability) {
            "calendar.read.rolling" -> (next.opt("days") as? String)?.let { next.put("days",it.toInt()) }
            "tool.state.set","tool.records.calendar" -> if(targetChanged || stateValue!=null) {
                val target=tools.find { it.id==next.optString("toolId") && it.revision==next.optInt("revision") && !it.archived }
                requireNotNull(target) { staleTargetMessage }
                val spec=ToolSpec.parsePersisted(target.spec)
                val field=next.getString("field")
                if(capability=="tool.records.calendar") AutomationDataflow.validateCollection(spec,field)
                else {
                    val input=checkNotNull(stateValue)
                    val value=when(spec.schema.get(field)) {
                        "boolean" -> input.toBooleanStrict()
                        "number" -> input.toDouble()
                        "string" -> input
                        else -> error(staleTargetMessage)
                    }
                    spec.validateState(JSONObject(target.state).put(field,value).toString())
                    next.put("value",value)
                }
            }
        }
        return request.copy(args=next).json()
    }
}

internal fun newAutomationAction(capability: String, notice: String,
    id: String = "a" + UUID.randomUUID().toString().replace("-","")): AutomationActionDraft {
    val now=System.currentTimeMillis()
    val args=when(capability) {
        "notification.post" -> JSONObject().put("title",notice).put("text",notice)
        "calendar.read" -> JSONObject().put("from",now).put("to",now+86_400_000L)
        "calendar.read.rolling" -> JSONObject().put("days",1).put("zone","device")
        "calendar.create" -> JSONObject().put("start",now).put("end",now+3_600_000L)
        "app.open" -> JSONObject().put("app","settings")
        else -> JSONObject()
    }
    return AutomationActionDraft(CapabilityRequest(id,capability,args))
}

internal fun newDayActions(notice: String): List<AutomationActionDraft> {
    val read=newAutomationAction("calendar.read.rolling",notice)
    return listOf(read,newAutomationAction("tool.records.calendar",notice).edit { put("source",read.id) })
}

private val actionLabels=linkedMapOf(
    "notification.post" to R.string.automation_editor_action_notice,
    "calendar.read" to R.string.automation_editor_action_read,
    "calendar.read.rolling" to R.string.automation_editor_action_rolling,
    "tool.records.calendar" to R.string.automation_editor_action_sync,
    "tool.state.set" to R.string.automation_editor_action_state,
    "app.open" to R.string.automation_editor_action_app,
    "link.open" to R.string.automation_editor_action_link,
    "maps.open" to R.string.automation_editor_action_maps,
    "calendar.create" to R.string.automation_editor_action_calendar_form,
)

@Composable internal fun AutomationActions(
    actions: List<AutomationActionDraft>, tools: ToolController, notice: String,
    changed: (List<AutomationActionDraft>)->Unit
) {
    val labels=actionLabels.mapValues { stringResource(it.value) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    fun move(index: Int,direction: Int) {
        changed(actions.toMutableList().apply { add(index+direction,removeAt(index)) })
    }
    actions.forEachIndexed { index, action ->
        key(action.id) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.automation_editor_action_number,index+1),style=MaterialTheme.typography.titleSmall)
                    TextButton(onClick={selectedId=if(selectedId==action.id) null else action.id},modifier=Modifier.heightIn(min=48.dp)) {
                        Text(labels.getValue(action.capability))
                    }
                    if(selectedId==action.id) {
                        val update: (AutomationActionDraft)->Unit = { next ->
                            changed(actions.map { if(it.id==next.id) next else it })
                        }
                        AutomationChoice(action.capability,labels) { capability ->
                            if(capability!=action.capability) update(newAutomationAction(capability,notice,action.id))
                        }
                        AutomationActionFields(action,actions.take(index),tools,update)
                    }
                    if(index>0) TextButton(onClick={move(index,-1)},modifier=Modifier.heightIn(min=48.dp)) {
                        Text(stringResource(R.string.automation_editor_move_up,index+1))
                    }
                    if(index<actions.lastIndex) TextButton(onClick={move(index,1)},modifier=Modifier.heightIn(min=48.dp)) {
                        Text(stringResource(R.string.automation_editor_move_down,index+1))
                    }
                    if(actions.size>1) TextButton(onClick={changed(actions.filter {it.id!=action.id})},modifier=Modifier.heightIn(min=48.dp)) {
                        Text(stringResource(R.string.automation_editor_remove_action,index+1))
                    }
                }
            }
        }
    }
    OutlinedButton(enabled=actions.size<3,onClick={
        val next=newAutomationAction("notification.post",notice)
        selectedId=next.id
        changed(actions+next)
    },modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.automation_editor_add_action)) }
    if(actions.size==1) TextButton(onClick={changed(actions+newDayActions(notice))},modifier=Modifier.heightIn(min=48.dp)) {
        Text(stringResource(R.string.automation_editor_day_template))
    }
}

@Composable private fun AutomationActionFields(
    draft: AutomationActionDraft, previous: List<AutomationActionDraft>, tools: ToolController,
    changed: (AutomationActionDraft)->Unit
) {
    val context=LocalContext.current
    val args=draft.args
    fun set(key: String,value: Any) { changed(draft.edit { put(key,value) }) }
    when(draft.capability) {
        "notification.post" -> {
            AutomationField(stringResource(R.string.automation_editor_notice_title),args.optString("title")) {set("title",it)}
            AutomationField(stringResource(R.string.automation_editor_notice_text),args.optString("text")) {set("text",it)}
        }
        "calendar.read","calendar.read.rolling" -> {
            val calendars by produceState<Map<String,String>?>(null,context) {
                value=withContext(Dispatchers.IO) {
                    try { AndroidCapabilities(context).calendars().associate {it.first.toString() to it.second} }
                    catch(_: SecurityException) {emptyMap()}
                }
            }
            Text(stringResource(R.string.automation_editor_calendar))
            AutomationChoice(args.optString("calendarId"),calendars.orEmpty()) {set("calendarId",it.toLong())}
            if(calendars?.isEmpty()==true) Text(stringResource(R.string.automation_editor_calendar_permission))
            if(draft.capability=="calendar.read") {
                AutomationInstant(stringResource(R.string.automation_editor_calendar_from),args.getLong("from")) {set("from",it)}
                AutomationInstant(stringResource(R.string.automation_editor_calendar_to),args.getLong("to")) {set("to",it)}
            } else {
                AutomationField(stringResource(R.string.automation_editor_rolling_days),args.optString("days")) {set("days",it)}
                AutomationZone(args.getString("zone")) {set("zone",it)}
            }
        }
        "tool.state.set","tool.records.calendar" -> AutomationTargetFields(draft,previous,tools,changed)
        "app.open" -> AutomationChoice(args.getString("app"),mapOf(
            "settings" to stringResource(R.string.automation_editor_app_settings),
            "browser" to stringResource(R.string.automation_editor_app_browser),
            "calendar" to stringResource(R.string.automation_editor_app_calendar),
            "camera" to stringResource(R.string.automation_editor_app_camera),
        )) {set("app",it)}
        "link.open" -> AutomationField(stringResource(R.string.automation_editor_url),args.optString("url")) {set("url",it)}
        "maps.open" -> AutomationField(stringResource(R.string.automation_editor_map_query),args.optString("query")) {set("query",it)}
        "calendar.create" -> {
            AutomationField(stringResource(R.string.automation_editor_event_title),args.optString("title")) {set("title",it)}
            AutomationInstant(stringResource(R.string.automation_editor_event_start),args.getLong("start")) {set("start",it)}
            AutomationInstant(stringResource(R.string.automation_editor_event_end),args.getLong("end")) {set("end",it)}
            AutomationField(stringResource(R.string.automation_editor_event_location),args.optString("location")) {
                changed(draft.edit { if(it.isBlank()) remove("location") else put("location",it) })
            }
            Text(stringResource(R.string.automation_editor_calendar_form_help),style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun AutomationInstant(label: String,millis: Long,changed: (Long)->Unit) {
    val dateTime=Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    Text(label,style=MaterialTheme.typography.labelLarge)
    AutomationDate(stringResource(R.string.automation_editor_date),dateTime.toLocalDate()) { date ->
        changed(date.atTime(dateTime.toLocalTime()).atZone(dateTime.zone).toInstant().toEpochMilli())
    }
    AutomationTime(stringResource(R.string.automation_editor_time),dateTime.toLocalTime()) { time ->
        changed(dateTime.toLocalDate().atTime(time).atZone(dateTime.zone).toInstant().toEpochMilli())
    }
}

@Composable private fun AutomationTargetFields(
    draft: AutomationActionDraft,previous: List<AutomationActionDraft>,tools: ToolController,
    changed: (AutomationActionDraft)->Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val currentDraft by rememberUpdatedState(draft)
    val onChanged by rememberUpdatedState(changed)
    val args=draft.args
    val sync=draft.capability=="tool.records.calendar"
    val targets=tools.tools.filter { !it.archived && ToolSpec.availablePersisted(it.spec)!=null }
    val current=targets.find { it.id==args.optString("toolId") && it.revision==args.optInt("revision") }
    val spec=current?.let {ToolSpec.availablePersisted(it.spec)}
    val value=draft.stateValue ?: args.optString("value")
    Text(stringResource(R.string.automation_editor_target))
    AutomationChoice(args.optString("toolId"),targets.associate { it.id to ToolSpec.storedTitle(it.spec) }) { id ->
        val next=targets.first {it.id==id}
        if(next.id!=args.optString("toolId") || next.revision!=args.optInt("revision")) {
            changed(draft.edit {
                put("toolId",next.id)
                put("revision",next.revision)
                if(next.id!=args.optString("toolId")) remove("field")
            }.copy(stateValue=if(sync) null else value,targetChanged=true))
        }
    }
    if(args.has("revision")) Text(stringResource(R.string.automation_editor_target_version,args.getInt("revision")))
    Text(stringResource(if(sync) R.string.automation_editor_target_list else R.string.automation_editor_target_field))
    val fields=spec?.schema?.keys()?.asSequence()?.filter { field ->
        if(sync) runCatching { AutomationDataflow.validateCollection(spec,field) }.isSuccess
        else spec.schema.get(field) is String
    }?.associateWith { it }.orEmpty()
    AutomationChoice(args.optString("field"),fields) { field ->
        if(field!=args.optString("field")) changed(draft.edit { put("field",field) }.copy(stateValue=if(sync) null else value,targetChanged=true))
    }
    if(sync) {
        Text(stringResource(R.string.automation_editor_calendar_source))
        val sources=previous.filter {it.capability in setOf("calendar.read","calendar.read.rolling")}
        AutomationChoice(args.optString("source"),sources.associate { it.id to it.id }) {
            changed(draft.edit { put("source",it) })
        }
        if(sources.isEmpty()) Text(stringResource(R.string.automation_editor_no_calendar_source))
        var creating by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf(false) }
        TextButton(enabled=!creating,onClick={
            creating=true
            failure=false
            scope.launch {
                try {
                    val target=tools.createAutomationDayTool()
                    tools.reload()
                    onChanged(currentDraft.edit {put("toolId",target.id);put("revision",target.revision);put("field","events")}
                        .copy(targetChanged=true))
                } catch(cancel: CancellationException) {
                    throw cancel
                } catch(_: Exception) {
                    failure=true
                } finally {
                    creating=false
                }
            }
        },modifier=Modifier.heightIn(min=48.dp)) { Text(stringResource(R.string.automation_editor_day_tool)) }
        if(failure) Text(stringResource(R.string.automation_editor_day_tool_error),color=MaterialTheme.colorScheme.error)
    } else AutomationField(stringResource(R.string.automation_editor_target_value),value) {changed(draft.copy(stateValue=it))}
}

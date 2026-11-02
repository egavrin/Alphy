package com.alpha.assistant.automations

import android.content.Context
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.*
import org.json.JSONObject
import java.util.UUID

object AutomationTools {
    fun renderer(context: Context): suspend (ToolRecord,String) -> ToolPreview {
        val offline=object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { error("Автоматизации не обращаются к облаку") }
        }
        val generation=ToolGenerationService(offline,SandboxClient(context))
        return { tool,state -> generation.evaluate(ToolSpec.parse(tool.spec),tool.id,tool.revision,state,JSONObject().put("type","render"),tool.ui) }
    }
    suspend fun execute(tools: ToolStore, definition: AutomationDefinition, action: CapabilityRequest, step: AutomationStep,
        render: suspend (ToolRecord,String) -> ToolPreview, stillApproved: () -> Boolean): CapabilityResult {
        val old=checkNotNull(tools.tool(action.args.getString("toolId"))) { "Инструмент недоступен" }
        check(!old.archived) { "Инструмент в архиве" }
        check(old.revision == action.args.getInt("revision")) { "Версия инструмента изменилась" }
        val spec=ToolSpec.parse(old.spec)
        val state=if(action.capability == "tool.state.set") spec.validateState(JSONObject(old.state).put(action.args.getString("field"),action.args.get("value")).toString()).toString()
            else AutomationDataflow.calendarState(spec,old.state,definition,action,step.previous)
        val rendered=render(old,state)
        require(rendered.effects.isEmpty() && JSONObject(rendered.state).toString() == JSONObject(state).toString()) { "Отображение изменило данные или запросило системное действие" }
        spec.validateState(rendered.state); spec.validateUi(JSONObject(rendered.ui),JSONObject(rendered.state))
        if(!step.dry) {
            check(stillApproved()) { "Сценарий выключен или изменён" }
            tools.commitTool(old.copy(state=rendered.state,ui=rendered.ui,stateVersion=old.stateVersion+1),old,false)
        }
        val preview=if(action.capability == "tool.records.calendar") {
            val list=JSONObject(state).getJSONArray(action.args.getString("field"))
            "Список «${action.args.getString("field")}»: ${list.length()} записей.\n"+(0 until list.length()).joinToString("\n") { list.getJSONObject(it).getString("text") }
        } else "${action.args.getString("field")} = ${action.args.get("value")}"
        return CapabilityResult("succeeded",if(step.dry) "Изменения рассчитаны без сохранения." else "Данные и интерфейс инструмента обновлены.",JSONObject().put("preview",preview).put("state",JSONObject(state)).put("ui",JSONObject(rendered.ui)))
    }
    fun createDayTool(store: ToolStore): ToolRecord {
        val spec=ToolSpec.parse("""{"formatVersion":2,"title":"Мой день","description":"Локальные события выбранного календаря","capabilities":[],"stateSchema":{"title":"string","events":{"type":"list","maxItems":100,"fields":{"id":"string","text":"string","done":"boolean","start":"number","end":"number"}}},"initialState":{"title":"Мой день","events":[]},"ui":{"type":"column","children":[{"type":"text","text":"Мой день","bind":"title"},{"type":"text","text":"События: 0"},{"type":"list","text":"События календаря","bind":"events"}]},"source":"return {state:input.state,ui:{type:'column',children:[{type:'text',text:'Мой день',bind:'title'},{type:'text',text:'События: '+input.state.events.length},{type:'list',text:'События календаря',bind:'events'}]}};"}""")
        return ToolRecord(UUID.randomUUID().toString(),1,1,spec.json,spec.initialState,spec.ui.toString(),null,true).also { store.commitTool(it,null,true) }
    }
}

data class AutomationChatProposal(val name: String, val trigger: JSONObject, val request: String)
object AutomationDrafts {
    fun fromChat(request: String): AutomationChatProposal {
        require(request.isNotBlank() && request.length <= 16000)
        require(request.contains("мой день",true) || request.contains("календар",true)) { "Поддерживается локальный сценарий «Мой день» из календаря. Выбери календарь и инструмент на следующем экране." }
        val scheduleError="Поддерживаются одно время ЧЧ:ММ ежедневно или интервал «каждые N минут». Проверь расписание."
        val intervalPattern=Regex("""(?<![\p{L}\p{N}_])каждые\s+([0-9]+)\s+(?:минут(?:а|у|ы)?|мин)(?![\p{L}\p{N}_])""",RegexOption.IGNORE_CASE)
        val dailyPattern=Regex("""(?<![\p{L}\p{N}_])(?:каждый\s+день|ежедневно)(?![\p{L}\p{N}_])""",RegexOption.IGNORE_CASE)
        val intervals=intervalPattern.findAll(request).toList()
        val daily=dailyPattern.findAll(request).toList()
        val clocks=Regex("""(?<![\p{L}\p{N}_.+\-−])[0-9]+:[0-9]+(?::[0-9]+)*(?![\p{L}\p{N}_:]|\.[0-9])""").findAll(request).toList()
        val clockSyntax=Regex("""\p{N}\s*[:：]|[:：]\s*\p{N}""").findAll(request).count()
        require(intervals.size <= 1 && daily.size <= 1 && clocks.size <= 1 && clockSyntax == clocks.size) {scheduleError}
        val clock=clocks.singleOrNull()
        val time=clock?.value?.let { raw ->
            require(raw.matches(Regex("""[0-9]{1,2}:[0-9]{2}"""))) {scheduleError}
            val (hour,minute)=raw.split(':').map {it.toInt()}
            require(hour in 0..23 && minute in 0..59) {scheduleError}
            "%02d:%02d".format(hour,minute)
        }
        require(intervals.isEmpty() || clock == null && daily.isEmpty()) {scheduleError}
        val remaining=(clock?.let {request.replaceRange(it.range," ")} ?: request)
            .replace(intervalPattern," ").replace(dailyPattern," ")
        val unsupported=Regex("""(?<![\p{L}\p{N}_])(?:кажд\p{L}*|еже\p{L}*|через|раз|завтра|сегодня|послезавтра|утр\p{L}*|вечер\p{L}*|ноч\p{L}*|час\p{L}*|минут\p{L}*|секунд\p{L}*|недел\p{L}*|месяц\p{L}*|понедельник\p{L}*|вторник\p{L}*|сред[ауы]|четверг\p{L}*|пятниц\p{L}*|суббот\p{L}*|воскресень\p{L}*|utc|gmt|msk|мск)(?![\p{L}\p{N}_])|(?<!\p{L})(?:в|к)\s+[0-9]|(?:[0-9]+[./-]){2}[0-9]+|[A-Za-z]+/[A-Za-z_]+""",RegexOption.IGNORE_CASE)
        require(!unsupported.containsMatchIn(remaining)) {scheduleError}
        val interval=intervals.singleOrNull()?.groupValues?.get(1)?.let {requireNotNull(it.toIntOrNull()) {scheduleError}}
        val trigger=if(interval != null) {
            require(interval in 15..10080) { "Интервал — от 15 минут до 7 дней" }
            JSONObject().put("type","interval").put("minutes",interval).put("anchor",System.currentTimeMillis()+interval*60000L)
        } else JSONObject().put("type","schedule").put("repeat","daily").put("time",time ?: "08:00").put("zone","device").put("exact",false)
        return AutomationChatProposal("Мой день",trigger,request)
    }
}

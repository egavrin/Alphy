package com.alpha.assistant.automations

import com.alpha.assistant.capabilities.*
import com.alpha.assistant.tools.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Only these explicit mappings can move local calendar results into tool records. */
object AutomationDataflow {
    fun describe(action: CapabilityRequest): String = when(action.capability) {
        "notification.post" -> "Уведомление не отправляется: ${action.args.getString("title")} · ${action.args.getString("text")}"
        "calendar.read.rolling" -> "Прочитать выбранный календарь от начала текущего дня на ${action.args.getInt("days")} дн. Данные останутся на устройстве."
        "calendar.read" -> "Прочитать выбранный календарь за утверждённый период. Данные останутся на устройстве."
        "tool.records.calendar" -> "Обновить импортированные записи календаря в списке «${action.args.getString("field") }». Сохранить ручные записи."
        "tool.state.set" -> "Изменить поле «${action.args.getString("field")}»: ${action.args.get("value")}"
        "app.open" -> "Открыть приложение «${action.args.getString("app")}» по отдельной кнопке пользователя."
        "link.open" -> "Открыть ссылку ${action.args.getString("url")} по отдельной кнопке пользователя."
        "maps.open" -> "Открыть поиск «${action.args.getString("query")}» на карте по отдельной кнопке пользователя."
        else -> "План системного действия без выполнения."
    }
    fun resolveRead(action: CapabilityRequest, now: Long): CapabilityRequest {
        if(action.capability == "calendar.read") return action
        require(action.capability == "calendar.read.rolling")
        val a=action.args
        val zone=if(a.getString("zone") == "device") ZoneId.systemDefault() else ZoneId.of(a.getString("zone"))
        val day=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from=day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to=day.plusDays(a.getLong("days")).atStartOfDay(zone).toInstant().toEpochMilli()
        return CapabilityCatalog.request(CapabilityRequest(action.id,"calendar.read",JSONObject().put("calendarId",a.getLong("calendarId")).put("from",from).put("to",to)).json())
    }
    fun validateCollection(spec: ToolSpec, field: String) {
        val list=spec.schema.getJSONObject(field)
        require(list.getString("type") == "list") { "Calendar import requires a legacy stable-ID list." }
        val fields=list.getJSONObject("fields")
        require(fields.optString("id") == "string" && fields.optString("text") == "string") { "Нужен список с полями id и text" }
        val types=mapOf("id" to "string","text" to "string","done" to "boolean","start" to "number","end" to "number")
        require(fields.keys().asSequence().all { types[it] == fields.get(it) }) { "Этот список не поддерживает записи календаря" }
    }
    fun calendarState(spec: ToolSpec, state: String, definition: AutomationDefinition, action: CapabilityRequest, previous: Map<String,CapabilityResult>): String {
        val field=action.args.getString("field"); validateCollection(spec,field)
        val source=definition.actions.single { it.id == action.args.getString("source") }
        require(source.capability in setOf("calendar.read","calendar.read.rolling"))
        val result=checkNotNull(previous[source.id]) { "Нет результата предыдущего чтения календаря" }
        require(result.status == "succeeded") { "Чтение календаря не завершено" }
        val events=result.data.getJSONArray("events"); require(events.length() <= 50)
        val namespace="alpha_calendar_${source.args.getLong("calendarId")}_"
        val json=spec.validateState(state)
        val old=json.getJSONArray(field)
        val existing=(0 until old.length()).associate { old.getJSONObject(it).getString("id") to old.getJSONObject(it) }
        val records=JSONArray()
        existing.filterKeys { !it.startsWith(namespace) }.values.forEach { records.put(it) }
        val fields=spec.schema.getJSONObject(field).getJSONObject("fields")
        val ids=mutableSetOf<String>()
        repeat(events.length()) { index ->
            val event=events.getJSONObject(index)
            require(event.get("id") is Number && event.get("start") is Number && event.get("end") is Number && event.get("title") is String)
            val start=event.getLong("start"); val end=event.getLong("end"); require(start >= 0 && end >= start)
            val id="$namespace${event.getLong("id")}_$start"
            require(ids.add(id)) { "Календарь вернул повторяющуюся запись" }
            val zone=if(source.args.optString("zone","device") == "device") ZoneId.systemDefault() else ZoneId.of(source.args.getString("zone"))
            val time=Instant.ofEpochMilli(start).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
            val record=JSONObject().put("id",id).put("text","$time · ${event.getString("title").take(500).ifBlank { "Без названия" }}")
            if(fields.has("done")) record.put("done",existing[id]?.optBoolean("done") ?: false)
            if(fields.has("start")) record.put("start",start/1000)
            if(fields.has("end")) record.put("end",end/1000)
            records.put(record)
        }
        return spec.validateState(json.put(field,records).toString()).toString()
    }
}

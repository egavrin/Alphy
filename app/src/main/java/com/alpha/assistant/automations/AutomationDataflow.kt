package com.alpha.assistant.automations

import com.alpha.assistant.capabilities.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

object AutomationDataflow {
    fun describe(action: CapabilityRequest): String = when(action.capability) {
        "notification.post" -> "Уведомление не отправляется: ${action.args.getString("title")} · ${action.args.getString("text")}"
        "calendar.read.rolling" -> "Прочитать выбранный календарь от начала текущего дня на ${action.args.getInt("days")} дн. Данные останутся на устройстве."
        "calendar.read" -> "Прочитать выбранный календарь за утверждённый период. Данные останутся на устройстве."
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
}

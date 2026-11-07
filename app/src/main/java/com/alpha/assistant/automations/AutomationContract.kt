package com.alpha.assistant.automations

import com.alpha.assistant.capabilities.CapabilityCatalog
import com.alpha.assistant.capabilities.CapabilityRequest
import org.json.JSONObject
import org.json.JSONArray
import java.time.*

/** Native, local definitions. Trigger contents never enter a model request. */
data class AutomationDefinition(val json: String, val name: String, val trigger: JSONObject,
    val conditions: JSONObject, val actions: List<CapabilityRequest>) {
    val type get() = trigger.getString("type")
    val scheduled get() = type in setOf("schedule","interval")
    fun occurrenceToken(at: Long): String {
        if(type == "interval") return "interval:$at"
        val zone=if(trigger.optString("zone","device") == "device") ZoneId.systemDefault() else ZoneId.of(trigger.getString("zone"))
        val date=Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return "schedule:${date}T${LocalTime.parse(trigger.getString("time"))}"
    }
    fun nextAfter(now: Long): Long? {
        if(type == "interval") {
            val duration=trigger.getLong("minutes")*60000
            val anchor=trigger.getLong("anchor")
            return if(now < anchor) anchor else anchor+((now-anchor)/duration+1)*duration
        }
        if(type != "schedule") return null
        val zone = if(trigger.optString("zone","device") == "device") ZoneId.systemDefault() else ZoneId.of(trigger.getString("zone"))
        val time = LocalTime.parse(trigger.getString("time"))
        if(trigger.getString("repeat") == "once") return LocalDate.parse(trigger.getString("date")).atTime(time).atZone(zone).toInstant().toEpochMilli().takeIf { it > now }
        val local = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = trigger.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
        // A DST gap moves forward. An overlap uses the earlier offset once per local date.
        for(offset in 0L..8L) {
            val date = local.plusDays(offset)
            if(trigger.getString("repeat") == "weekly" && date.dayOfWeek.value !in days) continue
            val candidate = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if(candidate > now) return candidate
        }
        return null
    }
    fun matches(event: AutomationEvent): Boolean = event.type == "manual" || when(type) {
        "schedule","interval" -> event.type == "schedule"
        "charging" -> event.type == "charging" && event.source == trigger.getString("connection")
        "notification" -> event.type == "notification" && event.source in notificationPackages()
        "geofence" -> event.type == "geofence" && event.source == trigger.getString("transition")
        else -> false
    }
    fun notificationPackages() = trigger.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
    fun conditionFailure(charging: Boolean?, now: Long): String? {
        if(conditions.has("charging") && charging != conditions.getBoolean("charging")) return "Условие подключения питания не выполнено или недоступно."
        if(conditions.has("fromMinute")) {
            val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
            val minute = t.hour*60+t.minute
            val from = conditions.getInt("fromMinute"); val to = conditions.getInt("toMinute")
            if(!(if(from <= to) minute in from until to else minute >= from || minute < to)) return "Сейчас вне разрешённого времени."
        }
        return null
    }
    companion object {
        val actionIds = setOf("notification.post","calendar.read","calendar.read.rolling","tool.records.calendar","tool.state.set","app.open","link.open","maps.open","calendar.create")
        fun parse(input: JSONObject): AutomationDefinition {
            require(input.toString().toByteArray().size <= 20000) { "Сценарий слишком большой" }
            require(input.keys().asSequence().toSet() == setOf("name","trigger","conditions","actions"))
            val j = JSONObject(input.toString())
            val name = j.getString("name"); require(name.isNotBlank() && name.length <= 100)
            val t = j.getJSONObject("trigger"); val type = t.getString("type")
            val fields = when(type) {
                "manual" -> setOf("type")
                "charging" -> { require(t.getString("connection") in setOf("connected","disconnected")); setOf("type","connection") }
                "notification" -> {
                    val a = t.getJSONArray("packages"); require(a.length() in 1..10)
                    val sources = (0 until a.length()).map { a.getString(it) }
                    require(sources.distinct().size == sources.size && sources.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && it != "com.alpha.assistant" }) { "Выбери приложения, кроме Alphy" }
                    setOf("type","packages")
                }
                "geofence" -> {
                    require(listOf("latitude","longitude","radius").all { t.get(it) is Number })
                    require(t.getDouble("latitude").isFinite() && t.getDouble("latitude") in -90.0..90.0)
                    require(t.getDouble("longitude").isFinite() && t.getDouble("longitude") in -180.0..180.0)
                    require(t.getDouble("radius").isFinite() && t.getDouble("radius") in 100.0..10000.0)
                    require(t.getString("transition") in setOf("enter","exit"))
                    setOf("type","latitude","longitude","radius","transition")
                }
                "interval" -> {
                    require(t.get("minutes") is Int && t.getInt("minutes") in 15..10080) { "Интервал — от 15 минут до 7 дней" }
                    require(t.get("anchor") is Number && t.getLong("anchor") in 1..4102444800000L && t.getDouble("anchor") == t.getLong("anchor").toDouble())
                    setOf("type","minutes","anchor")
                }
                "schedule" -> {
                    require(t.getString("repeat") in setOf("once","daily","weekly")); LocalTime.parse(t.getString("time"))
                    require(t.get("exact") is Boolean)
                    if(t.getString("zone") != "device") ZoneId.of(t.getString("zone"))
                    if(t.getString("repeat") == "once") LocalDate.parse(t.getString("date"))
                    if(t.getString("repeat") == "weekly") {
                        val a = t.getJSONArray("days"); require(a.length() in 1..7)
                        require((0 until a.length()).all { a.get(it) is Int && a.getInt(it) in 1..7 })
                    }
                    setOf("type","repeat","time","zone","exact") + when(t.getString("repeat")) { "once" -> setOf("date"); "weekly" -> setOf("days"); else -> emptySet() }
                }
                else -> error("Неизвестный триггер")
            }
            require(t.keys().asSequence().toSet() == fields) { "Неверные поля триггера" }
            val c = j.getJSONObject("conditions")
            require(c.keys().asSequence().all { it in setOf("charging","fromMinute","toMinute") })
            if(c.has("charging")) require(c.get("charging") is Boolean)
            if(c.has("fromMinute") || c.has("toMinute")) {
                require(c.get("fromMinute") is Int && c.getInt("fromMinute") in 0..1439)
                require(c.get("toMinute") is Int && c.getInt("toMinute") in 0..1439 && c.getInt("fromMinute") != c.getInt("toMinute"))
            }
            val a = j.getJSONArray("actions"); require(a.length() in 1..3)
            val actions = (0 until a.length()).map { i ->
                val raw = a.getJSONObject(i); val capability = raw.getString("capability")
                require(capability in actionIds)
                if(capability !in setOf("tool.state.set","tool.records.calendar","calendar.read.rolling")) CapabilityCatalog.request(raw)
                else {
                    require(raw.keys().asSequence().toSet() == setOf("id","capability","args"))
                    require(raw.getString("id").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
                    val args = raw.getJSONObject("args")
                    if(capability == "calendar.read.rolling") {
                        require(args.keys().asSequence().toSet() == setOf("calendarId","days","zone"))
                        require(args.get("calendarId") is Number && args.getLong("calendarId") in 0..4102444800000L && args.getDouble("calendarId") == args.getLong("calendarId").toDouble())
                        require(args.get("days") is Int && args.getInt("days") in 1..30)
                        if(args.getString("zone") != "device") ZoneId.of(args.getString("zone"))
                    } else {
                        val fields=if(capability == "tool.state.set") setOf("toolId","revision","field","value") else setOf("toolId","revision","field","source")
                        require(args.keys().asSequence().toSet() == fields)
                        require(args.getString("toolId").length in 1..128 && args.get("revision") is Int && args.getInt("revision") > 0)
                        require(args.getString("field").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
                        if(capability == "tool.state.set") {
                            val value = args.get("value")
                            require(value is Boolean || value is String && value.length <= 2048 || value is Number && value.toDouble().isFinite() && kotlin.math.abs(value.toDouble()) <= 1e12)
                        } else require(args.getString("source").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
                    }
                    CapabilityRequest(raw.getString("id"),capability,args)
                }
            }
            require(actions.map { it.id }.distinct().size == actions.size)
            actions.forEachIndexed { index,action -> if(action.capability == "tool.records.calendar") {
                require(actions.take(index).any { it.id == action.args.getString("source") && it.capability in setOf("calendar.read","calendar.read.rolling") }) { "Записи должны получать результат предыдущего чтения календаря" }
            } }
            if(actions.any { it.capability in setOf("app.open","link.open","maps.open","calendar.create") }) require(actions.size == 1) { "Открытие приложения должно быть отдельным сценарием" }
            if(type == "schedule" && t.getBoolean("exact")) require(actions.all { it.capability == "notification.post" }) { "Точное время доступно только для уведомлений Alphy. Остальные действия выполняются приблизительно." }
            return AutomationDefinition(j.toString(),name,t,c,actions)
        }
    }
}
data class AutomationEvent(val type: String, val token: String, val source: String = "", val scheduledAt: Long? = null)
data class AutomationRule(val id: String, val revision: Int, val approvedRevision: Int, val enabled: Boolean,
    val definition: AutomationDefinition, val nextAt: Long?, val attention: String)
data class AutomationRun(val id: String, val ruleId: String, val revision: Int, val token: String,
    val time: Long, val status: String, val message: String, val details: String)

data class AutomationStep(val key: String, val now: Long, val previous: Map<String,com.alpha.assistant.capabilities.CapabilityResult>, val dry: Boolean)

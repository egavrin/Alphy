package com.alpha.assistant.capabilities

import android.Manifest
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Only this catalog defines effects. Model output cannot define grants or adapters. */
data class CapabilityDefinition(val id: String, val title: String, val arguments: String, val permission: String? = null)
data class CapabilityRequest(val id: String, val capability: String, val args: JSONObject) {
    fun json() = JSONObject().put("id",id).put("capability",capability).put("args",args)
}
data class CapabilityResult(val status: String, val message: String, val data: JSONObject = JSONObject()) {
    init { require(status in setOf("succeeded","denied","revoked","unavailable","failed","cancelled","awaitingUser","unknownOutcome","launched")) }
    fun json() = JSONObject().put("status",status).put("message",message).put("data",data)
    companion object {
        fun parse(raw: String): CapabilityResult = JSONObject(raw).let { CapabilityResult(it.getString("status"),it.getString("message"),it.getJSONObject("data")) }
    }
}
data class CapabilityEffect(val key: String, val owner: String, val revision: Int, val stateVersion: Int,
    val request: CapabilityRequest, val result: CapabilityResult)

object CapabilityCatalog {
    val definitions = listOf(
        CapabilityDefinition("notification.post","Уведомления Alpha","{title:string,text:string}",if(Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null),
        CapabilityDefinition("calendar.read","Чтение выбранного календаря","{calendarId:integer,from:epochMillis,to:epochMillis}; maximum 31 days and 50 events",Manifest.permission.READ_CALENDAR),
        CapabilityDefinition("calendar.create","Открытие формы события","{title:string,start:epochMillis,end:epochMillis,location?:string}; user saves in calendar app"),
        CapabilityDefinition("link.open","Открытие ссылки HTTPS","{url:string}; public HTTPS host only"),
        CapabilityDefinition("app.open","Открытие приложения","{app:'settings'|'browser'|'calendar'|'camera'}"),
        CapabilityDefinition("maps.open","Поиск на карте","{query:string}"),
        CapabilityDefinition("vibration.play","Вибрация","{milliseconds:integer}; 1 to 2000"),
        CapabilityDefinition("flashlight.set","Фонарик","{enabled:boolean}",Manifest.permission.CAMERA),
        CapabilityDefinition("photo.pick","Выбор фотографии","{}; system picker, selected content only"),
        CapabilityDefinition("file.pick","Выбор файла","{mime:string}; system document picker"),
        CapabilityDefinition("camera.capture","Снимок с камеры","{}; system camera thumbnail, user reviews before keeping",Manifest.permission.CAMERA)
    )
    val ids = definitions.map { it.id }.toSet()
    val unavailable = mapOf(
        "screen.read" to "Для контекста экрана нужен поддерживаемый системный вызов ассистента. Можно поделиться текстом или выбрать снимок экрана.",
        "location.once" to "Получение геопозиции пока не подключено. Для поиска на карте укажи место.",
        "notification.read" to "Чтение уведомлений других приложений требует отдельного подключения. В этой версии оно недоступно.",
        "accessibility.control" to "Полное управление другими приложениями недоступно. Поддерживаются только их открытые системные действия.",
        "calendar.write" to "Прямая запись в календарь недоступна. Можно открыть системную форму события и сохранить его вручную."
    )
    fun definition(id: String) = definitions.single { it.id == id }
    fun request(json: JSONObject): CapabilityRequest {
        require(json.keys().asSequence().toSet() == setOf("id","capability","args")) { "Неверные поля системного действия" }
        val id = json.getString("id")
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
        val capability = json.getString("capability")
        require(capability in ids) { "Недоступная функция: $capability" }
        val a = json.getJSONObject("args")
        require(a.toString().toByteArray().size <= 4096)
        fun fields(required: Set<String>, optional: Set<String> = emptySet()) {
            val keys = a.keys().asSequence().toSet()
            require(keys.containsAll(required) && keys.all { it in required || it in optional }) { "Неверные параметры системной функции" }
        }
        fun string(key: String, max: Int = 500): String {
            require(a.get(key) is String)
            return a.getString(key).also { require(it.isNotBlank() && it.length <= max && !it.contains('\u0000')) }
        }
        fun number(key: String): Long {
            val value = a.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble())
            return value.toLong().also { require(it in 0..4102444800000L) }
        }
        when(capability) {
            "notification.post" -> { fields(setOf("title","text")); string("title",100); string("text",1000) }
            "calendar.read" -> { fields(setOf("calendarId","from","to")); number("calendarId"); val from = number("from"); require(number("to") - from in 1..2678400000L) }
            "calendar.create" -> { fields(setOf("title","start","end"),setOf("location")); string("title",200); val start = number("start"); require(number("end") - start in 1..2678400000L); if(a.has("location")) string("location",300) }
            "link.open" -> {
                fields(setOf("url")); val uri = URI(string("url",2048)); val host = uri.host?.lowercase().orEmpty()
                require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1,443) && host.contains('.') &&
                    !host.endsWith('.') && !host.matches(Regex("[0-9.]+")) && !host.contains(':') &&
                    listOf("localhost","local","internal","test","invalid","home","lan").none { host == it || host.endsWith(".$it") }) { "Разрешены только ссылки HTTPS на публичные сайты" }
            }
            "app.open" -> { fields(setOf("app")); require(string("app") in setOf("settings","browser","calendar","camera")) }
            "maps.open" -> { fields(setOf("query")); string("query",300) }
            "vibration.play" -> { fields(setOf("milliseconds")); require(number("milliseconds") in 1..2000) }
            "flashlight.set" -> { fields(setOf("enabled")); require(a.get("enabled") is Boolean) }
            "file.pick" -> { fields(setOf("mime")); require(string("mime",100).matches(Regex("[a-zA-Z0-9.+*-]+/[a-zA-Z0-9.+*-]+"))) }
            "photo.pick", "camera.capture" -> fields(emptySet())
        }
        return CapabilityRequest(id,capability,JSONObject(a.toString()))
    }
    fun parseEffects(json: JSONArray, declared: Set<String>, event: String): List<CapabilityRequest> {
        require(json.length() <= 3) { "За одно действие можно запросить не более трёх системных операций" }
        require(event == "action" || json.length() == 0) { "Предпросмотр и отображение не могут запрашивать системные операции" }
        val ids = mutableSetOf<String>()
        return (0 until json.length()).map { request(json.getJSONObject(it)).also { r ->
            require(r.capability in declared) { "Инструмент не объявил эту системную функцию" }
            require(ids.add(r.id)) { "Повторяется идентификатор системной операции" }
        } }
    }
}

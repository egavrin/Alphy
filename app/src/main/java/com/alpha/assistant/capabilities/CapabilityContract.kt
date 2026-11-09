package com.alpha.assistant.capabilities

import android.Manifest
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Exhaustive native bindings keep validators and executors aligned. */
enum class CapabilityAction { NOTIFICATION_POST, CALENDAR_READ, CALENDAR_CREATE, CALENDAR_OWNED, MEDICINE, LINK_OPEN, APP_OPEN, MAPS_OPEN, MAPS_MARK_REACHED, MAPS_ROUTE, VIBRATION_PLAY, FLASHLIGHT_SET, PHOTO_PICK, FILE_PICK, CAMERA_CAPTURE, DEVICE_READ, AUDIO_PICK, AUDIO_PLAYBACK, NETWORK_REQUEST, VIDEO_PICK, VIDEO_PLAYBACK, AI_ASK }
data class CapabilityArgument(val type: String, val optional: Boolean = false, val maxLength: Int = 500,
    val minimum: Long = 0, val maximum: Long = 4102444800000L, val choices: Set<String> = emptySet(), val format: String? = null, val allowEmpty: Boolean = false) {
    fun schema() = JSONObject().put("type",type).apply {
        if(type == "string") { put("minLength",if(allowEmpty)0 else 1); put("maxLength",maxLength); put("nonBlank",!allowEmpty); put("noNul",true) }
        if(type == "integer") { put("minimum",minimum); put("maximum",maximum) }
        if(choices.isNotEmpty()) put("enum",JSONArray(choices.toList()))
        if(format != null) put("format",format)
    }
    fun validate(value: Any) {
        when(type) {
            "string" -> {
                require(value is String && (value.isNotBlank() || allowEmpty && value.isEmpty()) && value.length <= maxLength && !value.contains('\u0000'))
                require(choices.isEmpty() || value in choices)
                if(format == "stop-id") require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")))
                if(format == "reached-ref" || format == "opaque-owner-handle") require(value.matches(Regex("[0-9a-f]{64}")))
                if(format == "mime") require(value.matches(Regex("[a-zA-Z0-9.+*-]+/[a-zA-Z0-9.+*-]+")))
                if(format == "public-https") {
                    val uri = URI(value); val host = uri.host?.lowercase().orEmpty()
                    require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1,443) && host.contains('.') &&
                        !host.endsWith('.') && !host.matches(Regex("[0-9.]+")) && !host.contains(':') &&
                        listOf("localhost","local","internal","test","invalid","home","lan").none { host == it || host.endsWith(".$it") }) { "Разрешены только ссылки HTTPS на публичные сайты" }
                }
            }
            "integer" -> require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in minimum..maximum)
            "boolean" -> require(value is Boolean)
            else -> error("Unknown catalog argument type")
        }
    }
}
data class CapabilityDefinition(val id: String, val title: String, val action: CapabilityAction,
    val parameters: Map<String,CapabilityArgument>, val explanation: String, val resultStatus: String,
    val resultDescription: String, val permission: String? = null, val feature: String? = null,
    val foreground: Boolean = false, val selection: Boolean = false, val minApi: Int = 29,
    val resultData: String = "{}") {
    val argumentSchema get() = if(id==NetworkEffectContract.ID) NetworkEffectContract.argumentSchema() else JSONObject().put("type","object").put("additionalProperties",false)
        .put("required",JSONArray(parameters.filterValues { !it.optional }.keys.toList()))
        .put("properties",JSONObject().apply { parameters.forEach { (name,argument) -> put(name,argument.schema()) } })
    fun json() = JSONObject().put("id",id).put("title",title).put("implementation","implemented").put("callable",true)
        .put("argumentSchema",argumentSchema).put("description",explanation).put("minApi",minApi)
        .put("access",JSONObject().put("toolGrant",id==NetworkEffectContract.ID).put("confirmation",false).put("androidPermission",permission ?: JSONObject.NULL)
            .put("manifestPermissions",JSONArray(when {
                id == DeviceReadContract.STATUS -> listOf(Manifest.permission.ACCESS_NETWORK_STATE)
                id == NetworkEffectContract.ID -> listOf(Manifest.permission.INTERNET,Manifest.permission.ACCESS_LOCAL_NETWORK)
                action == CapabilityAction.VIBRATION_PLAY -> listOf(Manifest.permission.VIBRATE)
                id in OwnedCalendarContract.ids -> listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
                id in MedicineContract.reminderIds -> listOfNotNull(Manifest.permission.SCHEDULE_EXACT_ALARM,permission)
                else -> listOfNotNull(permission)
            }))
            .put("foreground",foreground).put("unlocked",foreground).put("hardwareFeature",feature ?: JSONObject.NULL)
            .put("hardware",if(action == CapabilityAction.VIBRATION_PLAY) "vibratorService" else feature ?: JSONObject.NULL)
            .put("activityHandler",if(foreground) "checkedAtExecution" else "notRequired").apply {if(id in MedicineContract.reminderIds) put("specialAndroidAccess","exactAlarms")})
        .put("result",JSONObject().put("primaryStatus",resultStatus).put("description",resultDescription)
            .put("statuses",JSONArray(when(id) {
                in DeviceReadContract.ids -> DeviceReadContract.statuses
                in AudioContract.playbackIds -> AudioContract.playbackStatuses
                VideoContract.PLAY -> VideoContract.statuses
                NetworkEffectContract.ID -> NetworkEffectContract.statuses
                else -> CapabilityResult.statuses
            }.toList()))
            .put("deliveryLimits",JSONObject().put("dataBytes",CapabilityResultEvent.MAX_DATA_BYTES).put("batchBytes",CapabilityResultEvent.MAX_EVENT_BYTES))
            .put("errorData",JSONObject().put("type","object").put("additionalProperties",false).put("required",JSONArray(listOf("code","nativeStatus")))
                .put("properties",JSONObject().put("code",JSONObject().put("type","string").put("const",CapabilityResultEvent.BATCH_TOO_LARGE))
                    .put("nativeStatus",JSONObject().put("type","string").put("enum",JSONArray((CapabilityResult.statuses - "awaitingUser").toList())))))
            .put("data",when(id) {
                in OwnedCalendarContract.ids -> OwnedCalendarContract.resultDataSchema(id)
                in DeviceReadContract.ids -> DeviceReadContract.resultSchema(id)
                NetworkEffectContract.ID -> NetworkEffectContract.resultSchema()
                else -> JSONObject().put("type","object").put("additionalProperties",false).put("properties",JSONObject(resultData))
            }))
    fun validate(args: JSONObject) {
        if(action==CapabilityAction.NETWORK_REQUEST) {NetworkEffectContract.request(args);return}
        if(id==TorchSequence.ID) {TorchSequence.parse(args);return}
        val keys = args.keys().asSequence().toSet()
        require(keys.containsAll(parameters.filterValues { !it.optional }.keys) && keys.all { it in parameters }) { "Неверные параметры системной функции" }
        keys.forEach { parameters.getValue(it).validate(args.get(it)) }
        when(action) {
            CapabilityAction.MAPS_MARK_REACHED, CapabilityAction.MAPS_ROUTE -> require(args.getLong("version") == 1L)
            CapabilityAction.CALENDAR_READ -> require(args.getLong("to") - args.getLong("from") in 1..2678400000L)
            CapabilityAction.CALENDAR_OWNED -> OwnedCalendarContract.validate(id,args)
            CapabilityAction.MEDICINE -> MedicineContract.validate(id,args)
            CapabilityAction.CALENDAR_CREATE -> require(args.getLong("end") - args.getLong("start") in 1..2678400000L)
            CapabilityAction.NOTIFICATION_POST, CapabilityAction.LINK_OPEN, CapabilityAction.APP_OPEN,
            CapabilityAction.MAPS_OPEN, CapabilityAction.VIBRATION_PLAY, CapabilityAction.FLASHLIGHT_SET,
            CapabilityAction.PHOTO_PICK, CapabilityAction.FILE_PICK, CapabilityAction.CAMERA_CAPTURE, CapabilityAction.DEVICE_READ,
            CapabilityAction.AUDIO_PICK, CapabilityAction.AUDIO_PLAYBACK, CapabilityAction.NETWORK_REQUEST,
            CapabilityAction.VIDEO_PICK, CapabilityAction.VIDEO_PLAYBACK, CapabilityAction.AI_ASK -> Unit
        }
    }
}
data class PlannedCapability(val id: String, val title: String, val constraint: String) {
    fun json() = JSONObject().put("id",id).put("title",title).put("implementation","planned").put("callable",false).put("constraint",constraint)
}
data class CapabilityRequest(val id: String, val capability: String, val args: JSONObject) {
    fun json() = JSONObject().put("id",id).put("capability",capability).put("args",args)
}
data class CapabilityResult(val status: String, val message: String, val data: JSONObject = JSONObject()) {
    init { require(status in statuses) }
    fun json() = JSONObject().put("status",status).put("message",message).put("data",data)
    companion object {
        val statuses = setOf("succeeded","denied","revoked","unavailable","failed","cancelled","awaitingUser","unknownOutcome","launched")
        fun parse(raw: String): CapabilityResult = JSONObject(raw).let { CapabilityResult(it.getString("status"),it.getString("message"),it.getJSONObject("data")) }
    }
}
data class CapabilityEffect(val key: String, val owner: String, val revision: Int, val stateVersion: Int,
    val request: CapabilityRequest, val result: CapabilityResult, val phase: String = "terminal")

object CapabilityCatalog {
    private fun text(max: Int = 500, optional: Boolean = false, format: String? = null) = CapabilityArgument("string",optional,maxLength = max,format = format)
    private fun integer(min: Long = 0, max: Long = 4102444800000L) = CapabilityArgument("integer",minimum = min,maximum = max)
    private fun navigationStopArguments() = mapOf("version" to integer(1,1),"stopId" to text(64,format = "stop-id"),
        "label" to text(160),"latE6" to integer(-90000000,90000000),"lonE6" to integer(-180000000,180000000),
        "coordinateSystem" to CapabilityArgument("string",choices = setOf("wgs84","gcj02")))
    val definitions = listOf(
        CapabilityDefinition("notification.post","Уведомления Alphy",CapabilityAction.NOTIFICATION_POST,
            mapOf("title" to text(100),"text" to text(1000)),"Post an Alphy notification in its own channel.","succeeded",
            "Success confirms an active notification, not display or reading. An accepted but unverified request returns unknownOutcome.",
            if(Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null),
        CapabilityDefinition("calendar.read","Чтение выбранного календаря",CapabilityAction.CALENDAR_READ,
            mapOf("calendarId" to integer(),"from" to integer(),"to" to integer()),"Read one explicitly selected calendar. Use epoch milliseconds, at most 31 days and 50 event instances.","succeeded",
            "Only local event instances in the selected range. No automatic model upload.",Manifest.permission.READ_CALENDAR,
            resultData = """{"events":{"type":"array","maxItems":50,"items":{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"integer","format":"epochMillis"},"end":{"type":"integer","format":"epochMillis"}}}},"limit":{"type":"integer","const":50}}"""),
        CapabilityDefinition("calendar.create","Открытие формы события",CapabilityAction.CALENDAR_CREATE,
            mapOf("title" to text(200),"start" to integer(),"end" to integer(),"location" to text(300,true)),
            "Open the calendar event form. Use epoch milliseconds and a duration from 1 millisecond to 31 days.","launched",
            "The user saves the event in the calendar app. No saved event ID or write confirmation.",foreground = true),
        CapabilityDefinition("calendar.select","Select writable calendar",CapabilityAction.CALENDAR_OWNED,OwnedCalendarContract.version,
            "Version 1. Select one writable calendar through the native picker. Both Android calendar permissions are required. Provider IDs and accounts remain native.","succeeded",
            "Returns only an opaque app-scoped calendarRef. Display names and accounts remain native.",Manifest.permission.READ_CALENDAR,foreground=true,resultData=OwnedCalendarContract.resultSchema),
        CapabilityDefinition("calendar.createOwned","Create owned calendar event",CapabilityAction.CALENDAR_OWNED,OwnedCalendarContract.create,
            "Version 1. One timed event, optionally repeatDaily:true, with one alert reminder 0..10080 minutes before. Daily events use whole-second duration and recur at the selected local time in the explicit IANA timeZone. Use a selected calendarRef and stable eventKey. Start and end are explicit epoch milliseconds with current deviceTimeZone. Do not invent dates, times, medical schedules, or doses. The native control displays exact details. A repeated eventKey only reads the original operation and never repeats an uncertain insert.","succeeded",
            "Success requires exact provider readback of the owned event and reminder. Delivery remains unverified. Partial or interrupted writes return unknownOutcome.",Manifest.permission.READ_CALENDAR,foreground=true,resultData=OwnedCalendarContract.resultSchema),
        CapabilityDefinition("calendar.readOwned","Read owned calendar event",CapabilityAction.CALENDAR_OWNED,OwnedCalendarContract.reference,
            "Version 1. Read only an eventRef returned for this app. No arbitrary event or calendar scan.","succeeded",
            "Returns exact bounded owned event data after ownership and reminder verification.",Manifest.permission.READ_CALENDAR,foreground=true,resultData=OwnedCalendarContract.resultSchema),
        CapabilityDefinition("calendar.deleteOwned","Delete owned calendar event",CapabilityAction.CALENDAR_OWNED,OwnedCalendarContract.delete,
            "Version 1. Delete only this app's eventRef with the exact original title, start, end, timeZone, deviceTimeZone, location and reminderMinutes displayed by a native calendar control. Preserve all unrelated events.","succeeded",
            "Success requires ownership checks and provider readback of deletion. Modified markers or uncertain writes return unknownOutcome.",Manifest.permission.READ_CALENDAR,foreground=true,resultData=OwnedCalendarContract.resultSchema),
        CapabilityDefinition("calendar.deleteAllOwned","Delete all app-owned calendar events",CapabilityAction.CALENDAR_OWNED,OwnedCalendarContract.version,
            "Version 1. One native calendar button deletes every event in its displayed native owner-scoped snapshot. The host freezes the complete snapshot before generated code. No script-supplied IDs or later additions enter that operation. Each event uses exact ownership, snapshot and reminder checks. Interrupted or partial work never resumes automatically.","succeeded",
            "Returns total, deleted, unknown and remaining counts. Partial outcomes return unknownOutcome with the same bounded counts. Foreign events remain untouched.",Manifest.permission.READ_CALENDAR,foreground=true),
        CapabilityDefinition("calendar.open","Open calendar",CapabilityAction.CALENDAR_OWNED,OwnedCalendarContract.version,
            "Version 1. Open the installed calendar app from an explicit foreground native calendar control.","launched",
            "Only calendar app launch is confirmed. No event save or reminder delivery is claimed.",Manifest.permission.READ_CALENDAR,foreground=true),
        CapabilityDefinition("link.open","Открытие ссылки HTTPS",CapabilityAction.LINK_OPEN,mapOf("url" to text(2048,format = "public-https")),
            "Open a public HTTPS URL. Credentials, local hosts, literal IP addresses and nonstandard ports are rejected.","launched","Only activity launch is confirmed.",foreground = true),
        CapabilityDefinition("app.open","Открытие приложения",CapabilityAction.APP_OPEN,
            mapOf("app" to CapabilityArgument("string",choices = setOf("settings","browser","calendar","camera"))),
            "Open settings, browser, calendar or camera through a public Android action.","launched","Only activity launch is confirmed.",foreground = true),
        CapabilityDefinition("maps.open","Поиск на карте",CapabilityAction.MAPS_OPEN,mapOf("query" to text(300)),
            "Search an explicit place in a map app. No device location, coordinates or navigation origin are read.","launched","Only map activity launch is confirmed.",foreground = true),
        CapabilityDefinition("maps.markReached","Mark a stop reached",CapabilityAction.MAPS_MARK_REACHED,
            navigationStopArguments(),"Version 1 only. Record the stop that the user explicitly marks reached with the native foreground control. Never infer arrival or mark skipped stops.","succeeded",
            "Returns an opaque reachedRef for the latest native owner/revision-scoped marker. New markers, archiving and revision changes invalidate older references. No location access.",foreground = true,
            resultData = NavigationContract.markResultSchema),
        CapabilityDefinition("maps.route","Route from the last reached stop",CapabilityAction.MAPS_ROUTE,
            navigationStopArguments() + mapOf("reachedRef" to text(64,format = "reached-ref"),
                "mode" to CapabilityArgument("string",choices = setOf("walking","driving","transit","bicycling"))),
            "Version 1 only. Destination uses stopId, label, integer latE6/lonE6 and coordinateSystem. Origin comes only from the latest native reachedRef. Use the native foreground route control. Never use a previous planned stop.","launched",
            "Launch confirms only the installed map activity opened with explicit origin and destination. routeVerified is always false. Google Maps accepts WGS84, AMap accepts GCJ-02. Mixed systems and missing handlers are unavailable.",foreground = true,
            resultData = NavigationContract.routeResultSchema),
        CapabilityDefinition("vibration.play","Вибрация",CapabilityAction.VIBRATION_PLAY,mapOf("milliseconds" to integer(1,2000)),
            "Make the phone vibrate or buzz for 1 to 2000 milliseconds. Requires the manifest VIBRATE permission and vibrator hardware.","unknownOutcome","Android accepted the request. Physical vibration is unverified."),
        CapabilityDefinition("flashlight.set","Фонарик",CapabilityAction.FLASHLIGHT_SET,mapOf("enabled" to CapabilityArgument("boolean")),
            "Request the flashlight state. Camera permission and an available flash are required.","unknownOutcome","Android accepted the request. Physical state is unverified. Busy hardware returns unavailable.",
            Manifest.permission.CAMERA,android.content.pm.PackageManager.FEATURE_CAMERA_FLASH),
        CapabilityDefinition("photo.pick","Выбор фотографии",CapabilityAction.PHOTO_PICK,emptyMap(),
            "Open the system photo picker on API 33+, or the document picker on API 29–32. Only selected content is accessible.","awaitingUser",
            "Launch awaits a result. A selected item returns an owner-scoped resource handle when bounded metadata and access are available. Cancellation returns cancelled.",foreground = true,selection = true,
            resultData = """{"resourceId":{"type":"string","format":"opaque-owner-handle"},"kind":{"type":"string"},"mimeType":{"type":"string"},"sizeBytes":{"type":"integer","maximum":10485760}}"""),
        CapabilityDefinition("file.pick","Выбор файла",CapabilityAction.FILE_PICK,mapOf("mime" to text(100,format = "mime")),
            "Open the system document picker for one MIME type. Persistable read access depends on the provider.","awaitingUser",
            "Launch awaits a result. A selected item returns an owner-scoped resource handle when bounded metadata and access are available. Cancellation returns cancelled.",foreground = true,selection = true,
            resultData = """{"resourceId":{"type":"string","format":"opaque-owner-handle"},"kind":{"type":"string"},"mimeType":{"type":"string"},"sizeBytes":{"type":"integer","maximum":10485760}}"""),
        CapabilityDefinition(MiniAppAi.ID,"Запрос к DeepSeek",CapabilityAction.AI_ASK,mapOf("prompt" to text(MiniAppAi.MAX_PROMPT)),
            "Send only the supplied text to DeepSeek from an explicit foreground action. Uses the host's existing key consent independently of the mini-app network grant. Set capabilityResults to true. No search or additional context.","succeeded",
            "A bounded plain-text answer. Missing consent or key returns denied. Results cannot request another call.",foreground = true,
            resultData = """{"text":{"type":"string","maxLength":${MiniAppAi.MAX_ANSWER}}}"""),
        CapabilityDefinition("camera.capture","Снимок с камеры",CapabilityAction.CAMERA_CAPTURE,emptyMap(),
            "Open the system camera for a thumbnail. The user reviews it before private storage. No full-resolution capture.","awaitingUser",
            "Launch awaits a result. Success confirms a reviewed thumbnail through an owner-scoped resource handle, not a full-resolution photo.",Manifest.permission.CAMERA,
            android.content.pm.PackageManager.FEATURE_CAMERA_ANY,foreground = true,selection = true,
            resultData = """{"resourceId":{"type":"string","format":"opaque-owner-handle"},"kind":{"type":"string"},"mimeType":{"type":"string"},"sizeBytes":{"type":"integer","maximum":10485760}}""")
    ) + MedicineContract.definitions + DeviceReadContract.definitions + AudioContract.definitions + NetworkEffectContract.definition + VideoContract.definitions
    val ids = definitions.map { it.id }.toSet()
    val planned = listOf(
        PlannedCapability("source.searchFetch","Source search and fetch","Require a native network boundary, source attribution, size limits and untrusted-content handling. Chat search is not a generated-app API."),
        PlannedCapability("document.readShare","Selected documents and share","Require selected URI grants, bounded reads, MIME validation and explicit share confirmation."),
        PlannedCapability("media.control","Additional media controls","Use audio.tone, audio.pick, audio.play, video.pick and the dedicated video.play control for bounded foreground media. Additional playback controls are not available."),
        PlannedCapability("artifact.data","Artifact data","Require declared data contracts, revision checks and explicit access to local artifact data.")
    )
    val unavailable = mapOf(
        "screen.read" to "Screen context requires an Android assistant invocation and user review. Generated apps cannot capture screens.",
        "notification.read" to "Notification listener access belongs to explicit automation approval. Generated apps cannot read other notifications.",
        "accessibility.control" to "Generated apps cannot control arbitrary Android applications.",
        "calendar.write" to "Use calendar.createOwned and calendar.deleteOwned for app-owned events. Arbitrary calendar writes remain unavailable."
    ) + planned.associate { it.id to it.constraint }
    fun definition(id: String) = definitions.single { it.id == id }
    fun discovery() = JSONObject().put("platform","Android").put("catalog","Alphy")
        .put("implemented",JSONArray().apply { definitions.forEach { put(it.json()) } })
        .put("planned",JSONArray().apply { planned.forEach { put(it.json()) } })
        .put("unavailable",JSONObject(unavailable.filterKeys { key -> planned.none { it.id == key } }))
        .put("resultStatuses",JSONArray(CapabilityResult.statuses.toList()))
    fun modelGuidance(allowed: Set<String>): String = "Alphy Android callable shortlist (device availability is checked at execution): " +
        JSONArray().apply { definitions.filter { it.id in allowed }.forEach { put(it.json()) } }.toString() +
        ". Declare only these implemented IDs. Planned or unavailable IDs are not executable: " + unavailable.keys.joinToString() +
        ". An oversized result batch returns failed entries with data.code=resultBatchTooLarge and the original nativeStatus. This means result delivery failed, not that the external action failed. Never repeat an external action automatically. Medicine capabilities use dedicated native medicine controls with capability and args, without generated text. Echo input.event.medicine exactly in one effect. Record meal and actual intake only from explicit user statements. Never infer intake from reminder delivery. Use stable keys and explicit user-selected times and IANA zones. Meal offsets are 0..30 minutes. Daily DST gaps are skipped; overlap fires once. Reports require the native preview and a separate Share click. Only explicit foreground mini-app actions can request declared device operations. Android permissions and system pickers still apply. calendar.select/createOwned/readOwned/deleteOwned/deleteAllOwned/open require the dedicated native calendar control. Echo input.event.calendar exactly. Calendar selection and Android permissions remain explicit native actions. Generic buttons, inputs, previews, widgets and background tasks cannot authorize direct calendar actions. Keep calendar refs and results local. Never infer reminder delivery from a saved event. maps.markReached and maps.route require direct foreground user actions. Route origin must be the latest native reachedRef, never a planned stop. Use dedicated native navigation controls with capability and args, without text. Echo input.event.navigation exactly. Generic buttons and inputs cannot authorize direct navigation. Never mark stops from unrelated actions. Keep reached markers and route parameters local. Android permission is separate. Activity launch never proves completion."
    fun request(json: JSONObject): CapabilityRequest {
        require(json.keys().asSequence().toSet() == setOf("id","capability","args")) { "Неверные поля системного действия" }
        val id = json.getString("id")
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")))
        val capability = json.getString("capability")
        require(capability in ids) { "Недоступная функция: $capability" }
        val args = json.getJSONObject("args")
        require(args.toString().toByteArray().size <= 4096)
        definition(capability).validate(args)
        return CapabilityRequest(id,capability,JSONObject(args.toString()))
    }
    fun parseEffects(json: JSONArray, declared: Set<String>, event: String): List<CapabilityRequest> {
        require(json.length() <= 3) { "За одно действие можно запросить не более трёх системных операций" }
        require(event == "action" || json.length() == 0) { "Предпросмотр и отображение не могут запрашивать системные операции" }
        val ids = mutableSetOf<String>()
        return (0 until json.length()).map { request(json.getJSONObject(it)).also { r ->
            require(r.capability in declared) { "Инструмент не объявил эту системную функцию" }
            require(r.capability !in setOf(NetworkEffectContract.ID, MiniAppAi.ID, TorchSequence.ID) || json.length()==1) { "Запрос должен быть единственным эффектом действия." }
            require(ids.add(r.id)) { "Повторяется идентификатор системной операции" }
        } }
    }
}

package com.alpha.assistant.capabilities

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Public Android APIs only. The sandbox has no reference to this executor. */
class AndroidCapabilities internal constructor(private val context: Context,private val medicineAccess: ()->MedicineReminderAccess={MedicineRuntime.access(context)}) {
    fun permission(id: String) = CapabilityCatalog.definition(id).permission
    fun permissionGranted(id: String) = if(id in MedicineContract.reminderIds) medicineAccess().permissionGranted else if(id in OwnedCalendarContract.ids) OwnedCalendarCapabilities.hasPermissions(context) else permission(id)?.let { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true
    /** Reads only platform availability metadata. It never requests permission or launches an action. */
    fun availability(id: String): JSONObject {
        val definition = CapabilityCatalog.definition(id)
        if(id in MedicineContract.reminderIds) {
            val access=medicineAccess()
            return JSONObject().put("state",access.state).put("permissionGranted",access.permissionGranted)
                .put("prerequisites",access.json()).put("activityHandler","checkedAtExecution")
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val state = when {
            Build.VERSION.SDK_INT < definition.minApi -> "unsupportedApi"
            definition.feature != null && !context.packageManager.hasSystemFeature(definition.feature) -> "missingHardware"
            definition.action == CapabilityAction.VIBRATION_PLAY && !context.getSystemService(Vibrator::class.java).hasVibrator() -> "missingHardware"
            !permissionGranted(id) -> "permissionRequired"
            definition.action == CapabilityAction.NOTIFICATION_POST && (!manager.areNotificationsEnabled() ||
                manager.getNotificationChannel("alpha-tools")?.importance == NotificationManager.IMPORTANCE_NONE) -> "disabled"
            else -> "available"
        }
        return JSONObject().put("state",state).put("permissionGranted",permissionGranted(id))
            .put("activityHandler",if(definition.foreground) "checkedAtExecution" else "notRequired")
    }
    fun discover(): JSONObject = CapabilityCatalog.discovery().apply {
        val entries = getJSONArray("implemented")
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            entry.put("availability",availability(entry.getString("id")))
        }
    }
    fun calendars(): List<Pair<Long,String>> {
        if(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),null,null,null)?.use { c ->
            buildList { while(c.moveToNext() && size < 50) add(c.getLong(0) to c.getString(1).orEmpty()) }
        }.orEmpty()
    }
    suspend fun execute(request: CapabilityRequest, foreground: Boolean, launch: suspend (Intent) -> Unit): CapabilityResult {
        val r = CapabilityCatalog.request(request.json())
        val definition = CapabilityCatalog.definition(r.capability)
        val available = availability(r.capability).getString("state")
        if(available in setOf("unsupportedApi","missingHardware"))
            return CapabilityResult("unavailable", if (available == "unsupportedApi") "Недоступно в этой версии Android" else "Нужное оборудование отсутствует")
        if(!permissionGranted(r.capability)) return CapabilityResult("denied",context.getString(com.alpha.assistant.R.string.capability_permission_required))
        val args = r.args
        suspend fun open(intent: Intent, selection: Boolean = false): CapabilityResult {
            if(!foreground) return CapabilityResult("awaitingUser","Открой Alphy, чтобы продолжить системное действие.")
            return try {
                launch(intent)
                if(selection) CapabilityResult("awaitingUser","Открыто системное окно выбора. Данные сохранятся только после получения результата.",JSONObject().put("launched",true))
                else CapabilityResult("launched","Открыто системное окно или приложение. Выполнение действия в нём не подтверждено.")
            } catch(_: android.content.ActivityNotFoundException) { CapabilityResult("unavailable","На устройстве нет приложения, которое поддерживает это системное действие.") }
        }
        return when(definition.action) {
            CapabilityAction.NOTIFICATION_POST -> {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("alpha-tools","Инструменты Alphy",NotificationManager.IMPORTANCE_DEFAULT))
                if(!manager.areNotificationsEnabled() || manager.getNotificationChannel("alpha-tools").importance == NotificationManager.IMPORTANCE_NONE)
                    CapabilityResult("denied","Уведомления Alphy или их канал выключены в настройках Android.")
                else {
                    val notificationId = java.util.UUID.randomUUID().hashCode()
                    manager.notify(notificationId,Notification.Builder(context,"alpha-tools")
                        .setSmallIcon(com.alpha.assistant.R.drawable.ic_alpha).setContentTitle(args.getString("title"))
                        .setContentText(args.getString("text")).setAutoCancel(true).build())
                    if(manager.activeNotifications.any { it.id == notificationId && it.notification.channelId == "alpha-tools" })
                        CapabilityResult("succeeded","Уведомление активно в Android. Его показ и прочтение не подтверждены.")
                    else CapabilityResult("unknownOutcome","Android принял запрос уведомления, но его активное состояние не подтверждено.")
                }
            }
            CapabilityAction.MEDICINE -> CapabilityResult("denied","Medicine actions require a claimed native owner context.")
            CapabilityAction.CALENDAR_OWNED -> CapabilityResult("denied","Owned calendar actions require a claimed native owner context.")
            CapabilityAction.CALENDAR_READ -> {
                val events = JSONArray()
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(args.getLong("from").toString()).appendPath(args.getLong("to").toString()).build()
                val cursor = context.contentResolver.query(uri,arrayOf(CalendarContract.Instances.EVENT_ID,CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.END),
                    "${CalendarContract.Instances.CALENDAR_ID}=?",arrayOf(args.getLong("calendarId").toString()),"${CalendarContract.Instances.BEGIN} ASC")
                    ?: return CapabilityResult("unavailable","Системный календарь недоступен.")
                cursor.use { c -> while(c.moveToNext() && events.length() < 50) events.put(JSONObject().put("title",c.getString(1).orEmpty().take(500)).put("start",c.getLong(2)).put("end",c.getLong(3))) }
                CapabilityResult("succeeded","События выбранного календаря за указанный период прочитаны на устройстве. Данные не отправлялись в DeepSeek.",JSONObject().put("events",events).put("limit",50))
            }
            CapabilityAction.CALENDAR_CREATE -> open(Intent(Intent.ACTION_INSERT,CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE,args.getString("title"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,args.getLong("start"))
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,args.getLong("end"))
                .putExtra(CalendarContract.Events.EVENT_LOCATION,args.optString("location")))
            CapabilityAction.LINK_OPEN -> open(Intent(Intent.ACTION_VIEW,Uri.parse(args.getString("url"))).addCategory(Intent.CATEGORY_BROWSABLE))
            CapabilityAction.MAPS_MARK_REACHED, CapabilityAction.MAPS_ROUTE -> CapabilityResult("denied","Navigation requires a claimed native owner context.")
            CapabilityAction.MAPS_OPEN -> open(Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q=${Uri.encode(args.getString("query"))}")))
            CapabilityAction.APP_OPEN -> open(when(args.getString("app")) {
                "settings" -> Intent(Settings.ACTION_SETTINGS)
                "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                "browser" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_BROWSER)
                else -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_CALENDAR)
            })
            CapabilityAction.VIBRATION_PLAY -> {
                val vibrator = context.getSystemService(Vibrator::class.java)
                if(!vibrator.hasVibrator()) CapabilityResult("unavailable","На этом устройстве нет вибрации.")
                else { vibrator.vibrate(VibrationEffect.createOneShot(args.getLong("milliseconds"),VibrationEffect.DEFAULT_AMPLITUDE)); CapabilityResult("unknownOutcome","Android принял запрос вибрации. Её фактическое выполнение не подтверждено.") }
            }
            CapabilityAction.FLASHLIGHT_SET -> {
                val manager = context.getSystemService(CameraManager::class.java)
                val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                if(id == null) CapabilityResult("unavailable","Доступный фонарик не найден.")
                else try { manager.setTorchMode(id,args.getBoolean("enabled")); CapabilityResult("unknownOutcome","Android принял запрос фонарика. Его фактическое состояние не подтверждено.") }
                catch(_: android.hardware.camera2.CameraAccessException) { CapabilityResult("unavailable","Камера или фонарик заняты либо недоступны.") }
            }
            CapabilityAction.PHOTO_PICK -> open(if(Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),true)
            CapabilityAction.FILE_PICK -> open(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(args.getString("mime"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),true)
            CapabilityAction.CAMERA_CAPTURE -> open(Intent(MediaStore.ACTION_IMAGE_CAPTURE),true)
        }
    }
    companion object {
        fun selectionResult(resultCode: Int, intent: Intent?): CapabilityResult {
            if(resultCode == Activity.RESULT_CANCELED) return CapabilityResult("cancelled","Выбор в системном окне отменён.")
            if(resultCode != Activity.RESULT_OK) return CapabilityResult("unknownOutcome","Android не подтвердил результат выбора.")
            val uri = intent?.data
            return if(uri?.scheme == "content") CapabilityResult("succeeded","Выбранные данные остаются на устройстве. Они не отправлялись в DeepSeek.",JSONObject().put("uri",uri.toString()))
                else CapabilityResult("unknownOutcome","Android не вернул выбранные данные в поддерживаемом формате.")
        }
    }
}

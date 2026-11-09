package com.alpha.assistant.capabilities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ContextWrapper
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpha.assistant.R
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.surfaces.ToolWidgets
import kotlinx.coroutines.CancellationException

@Composable fun ToolCapabilityPanel(controller: ToolController, tool: ToolRecord, showDetails: Boolean = true) {
    if (!showDetails) return
    val spec = remember(tool.spec) { ToolSpec.availablePersisted(tool.spec) } ?: return
    if (!tool.archived && spec.capabilities.isNotEmpty()) {
        Text(stringResource(R.string.tool_device_actions), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.tool_device_actions_details))
        spec.capabilities.forEach { Text(CapabilityCatalog.definition(it).title) }
        if (spec.capabilities.any { it in MedicineContract.reminderIds }) {
            val reactivation by produceState(false, controller, tool.id, tool.revision, controller.effects) {
                value = try { controller.remindersNeedReactivation(tool.id) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { false }
            }
            if (reactivation) Text(stringResource(R.string.tool_medicine_reactivate))
        }
    }
    val effects = controller.effects.filter { it.owner == tool.id }.takeLast(30)
    if (effects.isNotEmpty()) Text(stringResource(R.string.tool_device_history), style = MaterialTheme.typography.titleMedium)
    effects.asReversed().forEach { effect ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(CapabilityCatalog.definition(effect.request.capability).title)
                Text(actionDetails(effect.request), style = MaterialTheme.typography.bodySmall)
                Text("${resultLabel(effect.result.status)}: ${effect.result.message}", style = MaterialTheme.typography.bodySmall)
                effect.result.data.optJSONArray("events")?.let { events ->
                    if (events.length() == 0) Text("За этот период событий нет", style = MaterialTheme.typography.bodySmall)
                    repeat(events.length()) { index ->
                        val event = events.getJSONObject(index)
                        Text("${event.getString("title").ifBlank { "Без названия" }} · ${eventTime(event.getLong("start"))} — ${eventTime(event.getLong("end"))}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Only the owning controller receives a platform callback. All foreground mini-app surfaces share this host. */
@Composable internal fun CapabilityPromptHost(controller: ToolController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val foreground = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !ToolWidgets.isLocked(context) }
    when (val prompt = controller.capabilityPrompt) {
        is CapabilityPrompt.Picker -> key(prompt.launch.token) {
            CapabilitySelectionActivity(prompt.launch, prompt.intent, { foreground() && controller.canLaunchPicker(prompt) },
                onResult = controller::receivePickerResult,
                onFailure = { controller.receivePickerResult(it, null) })
        }
        is CapabilityPrompt.Share -> AlertDialog(
            onDismissRequest = { controller.finishShare(prompt, false) },
            title = { Text(stringResource(R.string.tool_share_review_title)) },
            text = { Text(prompt.text, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { controller.finishShare(prompt, true) }, enabled = !controller.busy) {
                Text(stringResource(R.string.tool_share_confirm))
            } },
            dismissButton = { TextButton(onClick = { controller.finishShare(prompt, false) }, enabled = !controller.busy) {
                Text(stringResource(R.string.cancel))
            } },
        )
        is CapabilityPrompt.Calendar -> key(controller, prompt.effectKey) {
            CalendarSelection(controller, prompt.effectKey, foreground)
        }
        is CapabilityPrompt.LocationPermission -> key(controller, prompt.token) {
            RequestLocationPermission(controller, prompt, foreground)
        }
        is CapabilityPrompt.Camera -> PhotoCaptureDialog(prompt.capture)
        is CapabilityPrompt.Reading, null -> Unit
    }
    controller.devicePermissionIssue?.takeIf { controller.capabilityPrompt==null }?.let { message ->
        AlertDialog(onDismissRequest = controller::dismissDevicePermissionIssue,
            title = { Text(stringResource(R.string.tool_device_android_permissions)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = {
                if (foreground()) {
                    val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    if (runCatching { context.startActivity(settings) }.isSuccess) controller.dismissDevicePermissionIssue()
                }
            }) { Text(stringResource(R.string.tool_device_open_android_settings)) } },
            dismissButton = { TextButton(onClick = controller::dismissDevicePermissionIssue) {
                Text(stringResource(R.string.tool_dialog_close))
            } })
    }
}

@Composable private fun RequestLocationPermission(controller: ToolController, request: CapabilityPrompt.LocationPermission,
    foreground: () -> Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull() }
    val permission = Manifest.permission.ACCESS_COARSE_LOCATION
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val settingsRequired = !granted && (activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission))
        controller.receiveLocationPermission(request, LocationPermissionAnswer(granted, settingsRequired))
    }
    var launched by rememberSaveable(request.token) { mutableStateOf(false) }
    LaunchedEffect(request.token) {
        if (!launched) {
            launched = true
            if (!foreground() || !controller.canLaunchLocationPermission(request)) controller.cancelLocationPermission(request)
            else if (runCatching { launcher.launch(permission) }.isFailure)
                controller.receiveLocationPermission(request, LocationPermissionAnswer(granted = false, settingsRequired = true))
        }
    }
}

@Composable private fun CalendarSelection(controller: ToolController, effectKey: String, foreground: () -> Boolean) {
    val context = LocalContext.current
    var choices by remember { mutableStateOf<List<NativeOwnedCalendar>?>(null) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(OwnedCalendarCapabilities.hasPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = OwnedCalendarCapabilities.hasPermissions(context)
        if (!permitted) controller.cancelCalendar(effectKey, denied = true)
    }
    LaunchedEffect(permitted) {
        if (permitted) {
            choices = try { controller.writableCalendars() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyList() }
        }
        else if (!requested) {
            requested = true
            if (foreground()) {
                if (runCatching { launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }.isFailure) {
                    controller.cancelCalendar(effectKey, denied = true)
                }
            } else controller.cancelCalendar(effectKey)
        }
    }
    AlertDialog(
        onDismissRequest = { controller.cancelCalendar(effectKey) },
        title = { Text(stringResource(R.string.tool_calendar_choose_writable)) },
        text = {
            Column {
                when {
                    choices == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    choices.orEmpty().isEmpty() -> Text(stringResource(R.string.tool_calendar_no_writable))
                    else -> choices.orEmpty().forEach { calendar ->
                        TextButton(onClick = { controller.selectCalendar(effectKey, calendar, foreground) },
                            enabled = !controller.busy) {
                            Text(calendar.name.ifBlank { stringResource(R.string.tool_calendar_unnamed) })
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { controller.cancelCalendar(effectKey) }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A new token creates a separate registry entry. Saved launch state prevents rotation from relaunching Android. */
@Composable private fun CapabilitySelectionActivity(launch: CapabilitySelectionLaunch, intent: Intent, canLaunch: ()->Boolean,
    onResult: (CapabilitySelectionLaunch,ActivityResult)->Unit, onFailure: (CapabilitySelectionLaunch)->Unit) {
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returned -> onResult(launch,returned) }
    var launched by rememberSaveable(launch.token) { mutableStateOf(false) }
    LaunchedEffect(launch.token) {
        if(!launched) {
            launched=true
            try { check(canLaunch()); launcher.launch(intent) } catch(_: Exception) { onFailure(launch) }
        }
    }
}

private fun resultLabel(status: String) = when(status) {
    "succeeded" -> "Выполнено"
    "denied" -> "Доступ не разрешён"
    "revoked" -> "Доступ отозван"
    "unavailable" -> "Недоступно"
    "failed" -> "Ошибка"
    "cancelled" -> "Отменено"
    "awaitingUser" -> "Ожидает твоего действия"
    "unknownOutcome" -> "Результат не подтверждён"
    "launched" -> "Открыто"
    else -> "Неизвестное состояние"
}

private fun eventTime(value: Long) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(value))

private fun actionDetails(request: CapabilityRequest): String {
    val args = request.args
    return when(request.capability) {
        "notification.post" -> "Заголовок: ${args.getString("title")}\nТекст: ${args.getString("text")}"
        "calendar.read" -> "Календарь: ${args.getLong("calendarId")}\nС: ${eventTime(args.getLong("from"))}\nДо: ${eventTime(args.getLong("to"))}"
        "calendar.create" -> "Событие: ${args.getString("title")}\nНачало: ${eventTime(args.getLong("start"))}\nОкончание: ${eventTime(args.getLong("end"))}" + if(args.has("location")) "\nМесто: ${args.getString("location")}" else ""
        "link.open" -> "Ссылка: ${args.getString("url")}"
        "app.open" -> "Приложение: " + when(args.getString("app")) { "settings" -> "Настройки"; "browser" -> "Браузер"; "calendar" -> "Календарь"; else -> "Камера" }
        in MedicineContract.ids -> MedicineContract.label(request)
        in OwnedCalendarContract.ids -> OwnedCalendarContract.label(request)
        "maps.markReached", "maps.route" -> NavigationContract.label(request)
        "maps.open" -> "Место для поиска: ${args.getString("query")}"
        "vibration.play" -> "Длительность: ${args.getLong("milliseconds")} мс"
        "flashlight.set" -> if(args.getBoolean("enabled")) "Включить фонарик" else "Выключить фонарик"
        "file.pick" -> "Тип файла: ${args.getString("mime")}"
        VideoContract.PICK -> "Выбрать видео в Android"
        VideoContract.PLAY -> "Воспроизвести выбранное видео"
        NetworkEffectContract.ID -> "Метод запроса: ${args.getString("method")}"
        "photo.pick" -> "Выбрать одну фотографию в системном окне"
        PHOTO_CAPTURE -> "Сделать и сохранить снимок на устройстве"
        else -> "Функция недоступна"
    }
}

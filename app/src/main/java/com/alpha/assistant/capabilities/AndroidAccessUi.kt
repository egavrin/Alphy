package com.alpha.assistant.capabilities

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpha.assistant.R
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.ui.StatusLine

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable internal fun platformAccessRevision(): Int {
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return revision
}

@Composable internal fun AndroidPermissionControl(title: String, permissions: List<String>, feature: String? = null, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val revision = platformAccessRevision()
    var resultRevision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val asked = remember { context.getSharedPreferences("android-access-requests", Context.MODE_PRIVATE) }
    val available = feature == null || context.packageManager.hasSystemFeature(feature)
    val granted = remember(revision, resultRevision) { permissions.filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
    val missing = permissions - granted.toSet()
    val blocked = missing.any { asked.getBoolean(it, false) && context.activity()?.let { activity -> !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } == true }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultRevision++; onChanged() }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(when {
                !available -> "Недоступно: нужного оборудования нет."
                missing.isEmpty() -> "Разрешение Android выдано."
                granted.isNotEmpty() -> "Частичный доступ: доступна приблизительная геопозиция. Точная геопозиция не разрешена."
                blocked -> "Доступ не выдан. Если Android больше не показывает запрос, включите разрешение в настройках приложения."
                missing.any { asked.getBoolean(it, false) } -> "Доступ отклонён. Можно повторить запрос или открыть настройки."
                else -> "Разрешение Android ещё не выдано."
            })
            if (available && missing.isNotEmpty()) TextButton(onClick = {
                asked.edit().apply { permissions.forEach { putBoolean(it, true) } }.apply()
                runCatching { request.launch(permissions.toTypedArray()) }.onFailure { error = "Android не смог открыть запрос разрешения." }
            }) { Text("Запросить доступ") }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                    .onFailure { error = "Настройки приложения недоступны на этом устройстве." }
            }) { Text("Настройки приложения") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable fun AndroidAccessOverview() {
    val context = LocalContext.current
    val revision = platformAccessRevision()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val activityResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    fun open(intent: Intent) {
        runCatching { activityResult.launch(intent) }.onFailure { message = "Системное окно недоступно на этом устройстве." }
    }
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    // Reading these values on resume keeps Android as the source of truth.
    val foreground = remember(revision, refresh) { granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION) }
    val background = remember(revision, refresh) { granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val notifications = remember(revision, refresh) { notificationManager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) }
    val exact = remember(revision, refresh) { Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
    val listener = remember(revision, refresh) { notificationManager.isNotificationListenerAccessGranted(
        ComponentName(context, com.alpha.assistant.automations.AutomationNotificationListener::class.java)) }
    val roles = context.getSystemService(RoleManager::class.java)
    val roleAvailable = roles?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
    val assistant = remember(revision, refresh) { roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }
    Text("Доступ Android", style = MaterialTheme.typography.titleLarge)
    Text("Системные разрешения и доступы мини-приложений включаются отдельно. Контекст отправляется только с выбранным запросом после обычной кнопки «Отправить».")
    AndroidPermissionControl("Микрофон", listOf(Manifest.permission.RECORD_AUDIO), PackageManager.FEATURE_MICROPHONE)
    AndroidPermissionControl("Геопозиция при использовании", listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), PackageManager.FEATURE_LOCATION, onChanged = { refresh++ })
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Геопозиция в фоне", style = MaterialTheme.typography.titleMedium)
            Text(when { !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) -> "Оборудование недоступно."
                background -> "Фоновый доступ выдан."
                !foreground -> "Сначала выдайте доступ к геопозиции при использовании."
                else -> "Фоновый доступ не выдан. В настройках выберите «Разрешить в любом режиме», если этот вариант доступен." })
            if (Build.VERSION.SDK_INT == 29 && foreground && !background) {
                AndroidPermissionControl("Следующий этап: фоновый доступ", listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), onChanged = { refresh++ })
            } else TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                enabled = foreground && context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)) { Text("Настроить фоновый доступ") }
        }
    }
    AndroidPermissionControl("Календарь для контекста чата · только чтение", listOf(Manifest.permission.READ_CALENDAR))
    AndroidPermissionControl("Камера", listOf(Manifest.permission.CAMERA), PackageManager.FEATURE_CAMERA_ANY)
    if (Build.VERSION.SDK_INT >= 33) AndroidPermissionControl("Разрешение на уведомления", listOf(Manifest.permission.POST_NOTIFICATIONS), onChanged = { refresh++ })
    AccessSettingCard("Уведомления", if (notifications) "Уведомления разрешены. Отдельные каналы могут быть выключены." else "Уведомления выключены или разрешение не выдано.", "Настроить уведомления") {
        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    AccessSettingCard("Точные напоминания", if (exact) "Android разрешает точные напоминания." else "Точный будильник не разрешён.", "Настроить точные напоминания") {
        if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        else message = "В этой версии Android отдельный системный доступ не требуется."
    }
    AccessSettingCard("Чтение уведомлений для сценариев", if (listener) "Системный доступ выдан." else "Системный доступ не выдан.", "Настроить чтение уведомлений") {
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    AccessSettingCard("Системный ассистент", when { !roleAvailable -> "Выбор роли недоступен на этом устройстве."; assistant -> "Alphy выбран системным ассистентом."; else -> "Alphy не выбран системным ассистентом." }, "Выбрать ассистента") {
        if (roleAvailable) open(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) else open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    }
    Text("Выдача доступа не запускает микрофон, камеру, чтение календаря или сценарий. Файлы и фотографии выбираются в системном окне без доступа ко всей медиатеке.", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

/** The assistant role is not requestable, so the row opens the Android assistant settings and tells the user to pick Alphy there. */
@Composable fun AssistantSettingsRow() {
    val context = LocalContext.current
    val revision = platformAccessRevision()
    val selected = remember(revision) { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }
    var openFailed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.titleMedium)
        if (selected) StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(R.string.assistant_selected))
        else StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.assistant_not_selected))
        Text(stringResource(R.string.assistant_pick_hint))
        TextButton(onClick = {
            openFailed = try { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); false } catch (_: ActivityNotFoundException) { true }
        }) { Text(stringResource(R.string.assistant_open)) }
        if (openFailed) StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(R.string.assistant_open_failed))
        Text(stringResource(R.string.assistant_tile_hint))
    }
}

@Composable private fun AccessSettingCard(title: String, status: String, action: String, open: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(status)
        TextButton(onClick = open) { Text(action) }
    } }
}

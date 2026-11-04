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
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var error by remember { mutableStateOf<Int?>(null) }
    val asked = remember { context.getSharedPreferences("android-access-requests", Context.MODE_PRIVATE) }
    val available = feature == null || context.packageManager.hasSystemFeature(feature)
    val granted = remember(revision, resultRevision) { permissions.filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
    val missing = permissions - granted.toSet()
    val blocked = missing.any { asked.getBoolean(it, false) && context.activity()?.let { activity -> !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } == true }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultRevision++; onChanged() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        when {
            !available -> StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.access_no_hardware))
            missing.isEmpty() -> StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(R.string.access_granted))
            granted.isNotEmpty() -> StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.access_partial))
            blocked -> StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(R.string.access_blocked))
            missing.any { asked.getBoolean(it, false) } -> StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(R.string.access_denied))
            else -> StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.access_not_asked))
        }
        if (available && missing.isNotEmpty()) TextButton(onClick = {
            asked.edit().apply { permissions.forEach { putBoolean(it, true) } }.apply()
            runCatching { request.launch(permissions.toTypedArray()) }.onFailure { error = R.string.access_request_failed }
        }) { Text(stringResource(R.string.access_request)) }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { error = R.string.access_app_settings_failed }
        }) { Text(stringResource(R.string.access_app_settings)) }
        error?.let { StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(it)) }
    }
    HorizontalDivider()
}

/** «Доступ и функции». The top bar shows the title, so the page has none. */
@Composable fun AccessPane() {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AndroidAccessOverview()
        Text(stringResource(R.string.access_note))
        OutlinedButton(onClick = {
            openFailed = try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))); false
            } catch (_: ActivityNotFoundException) { true }
        }) { Text(stringResource(R.string.access_system_settings)) }
        if (openFailed) StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(R.string.access_app_settings_failed))
    }
}

/** The access rows, emitted into the column of the caller. */
@Composable fun AndroidAccessOverview() {
    val context = LocalContext.current
    val revision = platformAccessRevision()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Int?>(null) }
    val activityResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    fun open(intent: Intent) {
        runCatching { activityResult.launch(intent) }.onFailure { message = R.string.access_window_failed }
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
    val locationHardware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
    Text(stringResource(R.string.access_intro))
    AndroidPermissionControl(stringResource(R.string.access_microphone), listOf(Manifest.permission.RECORD_AUDIO), PackageManager.FEATURE_MICROPHONE)
    AndroidPermissionControl(stringResource(R.string.access_location), listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), PackageManager.FEATURE_LOCATION, onChanged = { refresh++ })
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.access_background_title), style = MaterialTheme.typography.titleMedium)
        // Android does not tell the app that the user denied background access, so no state shows Close.
        if (locationHardware && background) StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(R.string.access_background_granted))
        else StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(when {
            !locationHardware -> R.string.access_background_no_hardware
            !foreground -> R.string.access_background_needs_location
            else -> R.string.access_background_missing
        }))
        if (Build.VERSION.SDK_INT == 29 && foreground && !background) {
            AndroidPermissionControl(stringResource(R.string.access_background_next), listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), onChanged = { refresh++ })
        } else TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
            enabled = foreground && locationHardware) { Text(stringResource(R.string.access_background_open)) }
    }
    HorizontalDivider()
    AndroidPermissionControl(stringResource(R.string.access_calendar), listOf(Manifest.permission.READ_CALENDAR))
    AndroidPermissionControl(stringResource(R.string.access_camera), listOf(Manifest.permission.CAMERA), PackageManager.FEATURE_CAMERA_ANY)
    if (Build.VERSION.SDK_INT >= 33) AndroidPermissionControl(stringResource(R.string.access_notification_permission), listOf(Manifest.permission.POST_NOTIFICATIONS), onChanged = { refresh++ })
    AccessSettingRow(R.string.access_notifications_title, if (notifications) R.string.access_notifications_on else R.string.access_notifications_off, notifications, R.string.access_notifications_open) {
        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    AccessSettingRow(R.string.access_exact_title, if (exact) R.string.access_exact_on else R.string.access_exact_off, exact, R.string.access_exact_open) {
        if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        else message = R.string.access_exact_not_needed
    }
    AccessSettingRow(R.string.access_listener_title, if (listener) R.string.access_listener_on else R.string.access_listener_off, listener, R.string.access_listener_open) {
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    AssistantSettingsRow()
    HorizontalDivider()
    Text(stringResource(R.string.access_scope_note), style = MaterialTheme.typography.bodySmall)
    message?.let { StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(it)) }
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

/** These settings have no denied state, so a missing access shows Shield. */
@Composable private fun AccessSettingRow(@StringRes title: Int, @StringRes status: Int, granted: Boolean, @StringRes action: Int, open: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (granted) StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(status))
        else StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(status))
        TextButton(onClick = open) { Text(stringResource(action)) }
    }
    HorizontalDivider()
}

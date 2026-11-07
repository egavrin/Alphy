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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpha.assistant.R
import com.alpha.assistant.network.ui.MiniAppNetworkAccessHost
import com.alpha.assistant.network.ui.MiniAppNetworkAccessSection
import com.alpha.assistant.ui.AlphaIcon
import com.alpha.assistant.ui.Glyph
import com.alpha.assistant.ui.PoliteLiveRegion
import com.alpha.assistant.ui.StatusLine
import com.alpha.assistant.ui.SettingsDisclosure
import com.alpha.assistant.ui.SettingsHeading
import com.alpha.assistant.ui.SettingsPanel

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

@Composable internal fun AndroidPermissionControl(title: String, permissions: List<String>, feature: String? = null,
    @StringRes partial: Int = R.string.access_partial, onChanged: () -> Unit = {}, divider: Boolean = true) {
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
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        when {
            !available -> StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.access_no_hardware))
            missing.isEmpty() -> StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(R.string.access_granted))
            granted.isNotEmpty() -> StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(partial))
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
        error?.let { StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(it), PoliteLiveRegion) }
    }
    if (divider) HorizontalDivider()
}

/** Render permission groups inside the Settings detail scroll container. */
@Composable internal fun AccessSettings(network: MiniAppNetworkAccessHost?) {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    AndroidAccessOverview(grouped = true)
    SettingsPanel { MiniAppNetworkAccessSection(network, showDisclosure = true) }
    SettingsPanel {
        Text(stringResource(R.string.access_note))
        OutlinedButton(onClick = {
            openFailed = try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))); false
            } catch (_: ActivityNotFoundException) { true }
        }) { Text(stringResource(R.string.access_system_settings), textAlign = TextAlign.Center) }
        if (openFailed) StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(R.string.access_app_settings_failed), PoliteLiveRegion)
    }
}


/** The access rows, emitted into the column of the caller. */
@Composable fun AndroidAccessOverview(grouped: Boolean = false) {
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
    AccessGroup(grouped, R.string.settings_access_android) {
        AndroidPermissionControl(stringResource(R.string.access_microphone), listOf(Manifest.permission.RECORD_AUDIO), PackageManager.FEATURE_MICROPHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            AndroidPermissionControl(stringResource(R.string.access_local_network), listOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
        AndroidPermissionControl(stringResource(R.string.access_location), listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), PackageManager.FEATURE_LOCATION, onChanged = { refresh++ })
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.access_background_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            // Android does not tell the app that the user denied background access, so no state shows Close.
            if (locationHardware && background) StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(R.string.access_background_granted))
            else StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(when {
                !locationHardware -> R.string.access_background_no_hardware
                !foreground -> R.string.access_background_needs_location
                else -> R.string.access_background_missing
            }))
            if (Build.VERSION.SDK_INT == 29 && foreground && !background) {
                AndroidPermissionControl(stringResource(R.string.access_background_next), listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), onChanged = { refresh++ }, divider = false)
            } else TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                enabled = foreground && locationHardware) { Text(stringResource(R.string.access_background_open)) }
        }
        HorizontalDivider()
        AndroidPermissionControl(stringResource(R.string.access_calendar), listOf(Manifest.permission.READ_CALENDAR))
        AndroidPermissionControl(stringResource(R.string.access_owned_calendar), listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            partial = R.string.access_owned_calendar_partial)
        AndroidPermissionControl(stringResource(R.string.access_camera), listOf(Manifest.permission.CAMERA), PackageManager.FEATURE_CAMERA_ANY)
        if (Build.VERSION.SDK_INT >= 33) AndroidPermissionControl(stringResource(R.string.access_notification_permission), listOf(Manifest.permission.POST_NOTIFICATIONS), onChanged = { refresh++ })
        if (grouped) SettingsDisclosure(R.string.settings_access_scope) {
            Text(stringResource(R.string.access_scope_note), style = MaterialTheme.typography.bodySmall)
        }
    }
    AccessGroup(grouped, R.string.settings_access_automation) {
        AccessSettingRow(R.string.access_notifications_title, if (notifications) R.string.access_notifications_on else R.string.access_notifications_off, notifications, R.string.access_notifications_open) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        // Below API 31, Android gives exact alarms without a separate access, so the row has no button.
        AccessSettingRow(R.string.access_exact_title, if (exact) R.string.access_exact_on else R.string.access_exact_off, exact, if (Build.VERSION.SDK_INT >= 31) R.string.access_exact_open else null) {
            if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
        AccessSettingRow(R.string.access_listener_title, if (listener) R.string.access_listener_on else R.string.access_listener_off, listener, R.string.access_listener_open) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (grouped) com.alpha.assistant.automations.AutomationDeviceSettings()
    }
    if (grouped) SettingsPanel { AssistantSettingsRow() }
    else {
        AssistantSettingsRow()
        HorizontalDivider()
        Text(stringResource(R.string.access_scope_note), style = MaterialTheme.typography.bodySmall)
    }
    message?.let { StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(it), PoliteLiveRegion) }
}

@Composable
private fun AccessGroup(grouped: Boolean, @StringRes title: Int, content: @Composable () -> Unit) {
    if (grouped) SettingsPanel {
        SettingsHeading(title)
        content()
    } else content()
}

/** The assistant role is not requestable, so the row opens the Android assistant settings and tells the user to pick Alphy there. */
@Composable fun AssistantSettingsRow() {
    val context = LocalContext.current
    val revision = platformAccessRevision()
    val selected = remember(revision) { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }
    var openFailed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainerHigh) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { AlphaIcon(Glyph.Mic) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsHeading(R.string.assistant_title)
                if (selected) StatusLine(Glyph.Check, colors.primary, stringResource(R.string.assistant_selected))
                else Text(stringResource(R.string.assistant_not_selected), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(stringResource(R.string.assistant_invoke_hint))
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            openFailed = try { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); false } catch (_: ActivityNotFoundException) { true }
        }) { Text(stringResource(R.string.assistant_open), textAlign = TextAlign.Center) }
        if (openFailed) StatusLine(Glyph.Close, colors.error, stringResource(R.string.assistant_open_failed), PoliteLiveRegion)
        HorizontalDivider()
        Text(stringResource(R.string.assistant_pick_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.assistant_device_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** These settings have no denied state, so a missing access shows Shield. */
@Composable private fun AccessSettingRow(@StringRes title: Int, @StringRes status: Int, granted: Boolean, @StringRes action: Int?, open: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (granted) StatusLine(Glyph.Check, MaterialTheme.colorScheme.primary, stringResource(status))
        else StatusLine(Glyph.Shield, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(status))
        if (action != null) TextButton(onClick = open) { Text(stringResource(action)) }
    }
    HorizontalDivider()
}

package com.alpha.assistant.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alpha.assistant.automations.AutomationResumeResult
import com.alpha.assistant.automations.AutomationRule
import com.alpha.assistant.automations.AutomationRun
import com.alpha.assistant.automations.AutomationRuntime
import com.alpha.assistant.automations.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal data class DesktopAutomation(val rule: AutomationRule, val lastRun: AutomationRun?) {
    val needsAttention: Boolean get() = rule.attention.isNotBlank() || lastRun?.status in attentionStatuses

    private companion object {
        val attentionStatuses = setOf("failed", "unavailable", "denied", "revoked", "missed", "unknownOutcome", "awaitingUser")
    }
}

@Composable
internal fun desktopAutomations(database: String): State<Result<List<DesktopAutomation>>?> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(null, context, lifecycle, database) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            callbackFlow {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { trySend(Unit) }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(AutomationRuntime.CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
                trySend(Unit)
                awaitClose { context.unregisterReceiver(receiver) }
            }.conflate().collect {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        AutomationStore(context, database).use { store ->
                            store.rules().map { rule -> DesktopAutomation(rule, store.latestRun(rule)) }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun pauseDesktopAutomation(context: Context, database: String, expected: AutomationRule) =
    withContext(Dispatchers.IO) {
        AutomationStore(context, database).use { store ->
            check(AutomationRuntime.pause(context, store, expected))
        }
    }

internal suspend fun resumeDesktopAutomation(context: Context, database: String, expected: AutomationRule): AutomationResumeResult =
    withContext(Dispatchers.IO) {
        AutomationStore(context, database).use { store -> AutomationRuntime.resume(context, store, expected) }
    }

internal data class DesktopCalendarEvent(val title: String?, val start: Long, val allDay: Boolean)

@Composable
internal fun desktopCalendarEvent(): State<DesktopCalendarEvent?> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(null, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (isActive) {
                    val next = withContext(Dispatchers.IO) { nextCalendarEvent(context) }
                    value = next.takeIf { canReadDesktopCalendar(context) }
                    delay(60_000L)
                }
            } finally {
                value = null
            }
        }
    }
}

internal fun canReadDesktopCalendar(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

private fun nextCalendarEvent(context: Context): DesktopCalendarEvent? {
    if (!canReadDesktopCalendar(context)) return null
    val now = System.currentTimeMillis()
    val through = now + 7 * 24 * 60 * 60 * 1000L
    val uri = Instances.CONTENT_URI.buildUpon().apply {
        ContentUris.appendId(this, now)
        ContentUris.appendId(this, through)
    }.build()
    return try {
        context.contentResolver.query(uri,
            arrayOf(Instances.TITLE, Instances.BEGIN, Instances.ALL_DAY),
            "${Instances.VISIBLE}=1 AND ${Instances.BEGIN}>=? AND ${Instances.BEGIN}<? AND " +
                "(${Instances.STATUS} IS NULL OR ${Instances.STATUS}!=?)",
            arrayOf(now.toString(), through.toString(), Instances.STATUS_CANCELED.toString()), "${Instances.BEGIN} ASC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else DesktopCalendarEvent(cursor.getString(0)?.takeIf { it.isNotBlank() },
                cursor.getLong(1), cursor.getInt(2) != 0)
        }
    } catch (_: RuntimeException) {
        null
    }
}

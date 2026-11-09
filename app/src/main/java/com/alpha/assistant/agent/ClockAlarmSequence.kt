package com.alpha.assistant.agent

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Owns the Clock handler and the outstanding return for one approved alarm sequence. */
internal class ClockAlarmSequence(val plan: AgentPlan, val activity: ComponentActivity) {
    private var handler: ComponentName? = null
    private var pending: CompletableDeferred<Boolean>? = null
    private var stopRecorded: CompletableDeferred<Unit>? = null
    val stopping get() = stopRecorded != null

    fun requestStop(): Boolean {
        if (stopping) return false
        stopRecorded = CompletableDeferred()
        return true
    }

    suspend fun awaitStopRecorded() { stopRecorded?.await() }

    fun releaseWait() {
        stopRecorded?.complete(Unit)
        pending?.complete(false)
    }

    suspend fun dispatch(intent: Intent, step: Int): Boolean {
        val clock = resolveClock(intent)
        if (clock == null || (handler != null && handler != clock)) {
            throw ActivityNotFoundException("No consistent default alarm handler")
        }
        handler = clock
        val returned = CompletableDeferred<Boolean>()
        pending = returned
        try {
            return awaitClockAlarmReturn(activity.activityResultRegistry,
                "alarm-${plan.id}-${plan.revision}-$step", intent.setComponent(clock), returned)
        } finally {
            pending = null
        }
    }

    private fun resolveClock(intent: Intent): ComponentName? {
        val manager = activity.packageManager
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val handlers = manager.queryIntentActivities(intent, flags).map {
            ComponentName(it.activityInfo.packageName, it.activityInfo.name)
        }.distinct()
        // A resolver activity is not a Clock handler.
        return handlers.singleOrNull() ?: intent.resolveActivity(manager)?.takeIf { it in handlers }
    }
}

internal suspend fun awaitClockAlarmReturn(registry: ActivityResultRegistry, key: String, intent: Intent,
    returned: CompletableDeferred<Boolean>, timeoutMs: Long = 10_000L): Boolean {
    val launcher = registry.register(key, ActivityResultContracts.StartActivityForResult()) { returned.complete(true) }
    try {
        launcher.launch(intent)
        return withTimeoutOrNull(timeoutMs) { returned.await() } == true
    } finally {
        launcher.unregister()
    }
}

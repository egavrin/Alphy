package com.alpha.assistant.timer

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpha.assistant.R
import com.alpha.assistant.timer.ForegroundTimerLedger.Phase
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolRecord

internal class ForegroundTimerUiHost(val controller: ToolController, val tool: ToolRecord, val active: Boolean, val unlocked: () -> Boolean)

@Composable internal fun ForegroundTimerUi(title: String, spec: ForegroundTimerSpec, enabled: Boolean, host: ForegroundTimerUiHost?, modifier: Modifier) {
    var inWindow by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val visible = host?.active == true && inWindow
    val live by rememberUpdatedState(visible)
    val unlocked by rememberUpdatedState(host?.unlocked)
    val scope = rememberCoroutineScope()
    val driver = remember(host?.controller, host?.tool?.id, host?.tool?.revision, spec, lifecycle, view) {
        host?.controller?.foregroundTimerDriver(host.tool, spec, scope) {
            live && view.hasWindowFocus() && unlocked?.invoke() == true && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }
    if (driver != null) DisposableEffect(driver, lifecycle, visible, view) {
        fun refresh() = driver.updateEligibility(visible && view.hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
        lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            driver.updateEligibility(false)
        }
    }

    val run = driver?.state
    val phase = run?.phase
    val seconds = ((run?.remainingMillis ?: spec.durationMillis) + 999L) / 1_000L
    val countdown = stringResource(R.string.tool_timer_clock, seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
    val status = when (phase) {
        Phase.RUNNING -> stringResource(R.string.tool_timer_remaining, countdown)
        Phase.PAUSED -> stringResource(R.string.foreground_timer_paused, countdown)
        Phase.SUPPRESSED -> stringResource(R.string.foreground_timer_suppressed)
        Phase.ADMITTED, Phase.CLAIMED -> stringResource(R.string.foreground_timer_uncertain)
        Phase.FINISHED -> stringResource(when (run?.outcome) {
            "succeeded" -> R.string.tool_timer_expired
            "denied" -> R.string.foreground_timer_denied
            "unavailable", "cancelled" -> R.string.foreground_timer_unavailable
            else -> R.string.foreground_timer_uncertain
        })
        else -> stringResource(R.string.foreground_timer_ready, countdown)
    }
    Column(modifier.onGloballyPositioned { inWindow = !it.boundsInWindow().isEmpty }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.tool_timer_value, title, status))
        if (driver != null) {
            spec.alert.vibrationMillis?.let { Text(stringResource(R.string.foreground_timer_vibration, it)) }
            spec.alert.notification?.let { Text(stringResource(R.string.foreground_timer_notification, it.title, it.text)) }
            if (driver.failed) Text(stringResource(R.string.foreground_timer_failed))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phase != Phase.RUNNING) Button(driver::start, Modifier.heightIn(min = 48.dp), enabled = driver.eligible && enabled) {
                    Text(stringResource(if (phase == Phase.PAUSED) R.string.foreground_timer_resume else R.string.foreground_timer_start))
                }
                if (phase != null && phase != Phase.IDLE) TextButton(driver::reset, Modifier.heightIn(min = 48.dp), enabled = driver.eligible && enabled) {
                    Text(stringResource(R.string.foreground_timer_reset))
                }
            }
        }
    }
}

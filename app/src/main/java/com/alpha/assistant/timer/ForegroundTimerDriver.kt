package com.alpha.assistant.timer

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.capabilities.AndroidCapabilities
import com.alpha.assistant.timer.ForegroundTimerLedger.Phase
import com.alpha.assistant.timer.ForegroundTimerLedger.Run
import com.alpha.assistant.timer.ForegroundTimerLedger.Snapshot
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** The native controls and their lifecycle serialize transitions on the main thread. */
internal class ForegroundTimerDriver(
    store: ToolStore,
    private val tool: ToolRecord,
    private val spec: ForegroundTimerSpec,
    private val scope: CoroutineScope,
    private val live: () -> Boolean,
) {
    private val library = store.artifactIdentity
    private val ledger = ForegroundTimerLedger(store, tool, spec)
    private val slot = library to ledger.key
    private val android = AndroidCapabilities(store.context)
    var eligible by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var target: ForegroundTimerAuthority.Target? = null
    private var snapshot: Snapshot? = null
    private var deadline = 0L

    var state: Run? by mutableStateOf(null)
        private set
    var failed by mutableStateOf(false)
        private set

    fun updateEligibility(value: Boolean) {
        if (eligible == value) return
        eligible = value
        if (value) guarded { refresh() } else stop()
    }

    fun start() = guarded {
        if (!eligible || !live() || active.containsKey(slot)) return@guarded
        refresh()
        val previous = state
        val run = if (previous?.phase == Phase.PAUSED) previous.copy(phase = Phase.RUNNING)
        else Run(UUID.randomUUID().toString(), Phase.RUNNING, spec.durationMillis)
        val written = ledger.write(snapshot, run) ?: return@guarded fail()
        val driver = ForegroundTimerAuthority.DriverGeneration { current() }
        val target = ForegroundTimerAuthority.Target(library, tool.id, tool.revision, spec.id, driver)
        active[slot] = this
        this.target = target
        snapshot = written
        deadline = SystemClock.elapsedRealtime() + run.remainingMillis
        val handle = authority.arm(target, deadline, spec.alert)
        if (handle == null) return@guarded stop()
        state = run
        job = scope.launch { countDown(target, handle, written) }
    }

    fun reset() = guarded {
        if (!eligible || !live() || active[slot]?.let { it !== this } == true) return@guarded
        release()
        val latest = ledger.read()
        val run = Run(UUID.randomUUID().toString(), Phase.IDLE, spec.durationMillis)
        snapshot = ledger.write(latest, run) ?: return@guarded fail()
        state = run
    }

    private suspend fun countDown(target: ForegroundTimerAuthority.Target, handle: ForegroundTimerAuthority.Run, running: Snapshot) {
        try {
            while (current()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                state = running.run.copy(remainingMillis = remaining)
                delay(minOf(remaining, 250L))
            }
            if (!current()) return stop()

            val result = authority.commitTerminal(target, handle) { terminal ->
                val phase = if (terminal.decision == ForegroundTimerAuthority.Decision.ADMITTED) Phase.ADMITTED else Phase.SUPPRESSED
                val receipt = Run(terminal.runId.toString(), phase, 0L)
                val committed = ledger.write(running, receipt) ?: return@commitTerminal false
                snapshot = committed
                state = receipt
                true
            }
            when (result) {
                ForegroundTimerAuthority.Result.ADMISSION_COMMITTED -> dispatch(checkNotNull(snapshot))
                ForegroundTimerAuthority.Result.SUPPRESSION_COMMITTED -> Unit
                else -> fail()
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            fail()
        } finally {
            if (this.target == target) release(cancelJob = false)
        }
    }

    private suspend fun dispatch(receipt: Snapshot) {
        if (!current()) return
        val claimed = ledger.write(receipt, receipt.run.copy(phase = Phase.CLAIMED)) ?: return
        snapshot = claimed
        val outcomes = spec.requests().map { request ->
            if (!current() || !ledger.authorized()) "cancelled"
            else try { android.execute(request, true) { error("A timer cannot launch an activity.") }.status }
            catch (_: SecurityException) { "denied" }
        }
        val outcome = outcomes.firstOrNull { it != "succeeded" } ?: "succeeded"
        val finished = claimed.run.copy(phase = Phase.FINISHED, outcome = outcome)
        snapshot = ledger.write(claimed, finished, requireAuthorization = false) ?: return
        state = finished
    }

    private fun stop() {
        val owned = active[slot] === this
        release()
        if (!owned) return
        guarded {
            val previous = snapshot ?: return@guarded
            if (previous.run.phase == Phase.RUNNING) {
                val paused = previous.run.copy(phase = Phase.PAUSED, remainingMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                snapshot = ledger.write(previous, paused, requireAuthorization = false)
                state = paused
            }
        }
    }

    private fun release(cancelJob: Boolean = true) {
        target?.let { authority.invalidateDriver(it.driver) }
        target = null
        if (active[slot] === this) active.remove(slot)
        if (cancelJob) job?.cancel()
        job = null
    }

    private fun refresh() {
        snapshot = ledger.read()
        state = ledger.run(snapshot)?.let { if (it.phase == Phase.RUNNING) it.copy(phase = Phase.PAUSED) else it }
    }

    private fun current(): Boolean = eligible && live() && active[slot] === this

    private inline fun guarded(block: () -> Unit) {
        failed = false
        try { block() } catch (_: Exception) { fail() }
    }

    private fun fail() { failed = true }

    companion object {
        private val active = mutableMapOf<Pair<String, String>, ForegroundTimerDriver>()
        private val authority = ForegroundTimerAuthority(
            ForegroundTimerAuthority.RatePolicy(60_000, 4, 16), SystemClock::elapsedRealtime,
        ) { it.driver.eligible() }
    }
}

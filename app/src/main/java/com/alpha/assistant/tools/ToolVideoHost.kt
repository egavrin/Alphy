package com.alpha.assistant.tools

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.media.DeviceMediaPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** One mounted host. Each exact control lease owns its own player and cancellation. */
@MainThread
internal class ToolVideoHost(
    private val scope: CoroutineScope,
    private val displayed: () -> ToolRecord,
    private val foreground: () -> Boolean,
    private val playback: () -> DeviceMediaPlayback = { DeviceMediaPlayback() },
    private val execute: suspend (ToolRecord, NativeVideoControl, NativeVideoSurfaceLease, DeviceMediaPlayback,
        () -> Boolean, () -> Unit) -> CapabilityResult,
    private val canStart: () -> Boolean = { true },
) : NativeVideoUiHost {
    private class Mounted(val lease: NativeVideoSurfaceLease) {
        var status by mutableStateOf(NativeVideoStatus.Idle)
        var attempt: Attempt? = null
    }
    private class Attempt(val player: DeviceMediaPlayback) {
        val active = AtomicBoolean(true)
        lateinit var job: Job
        fun closePlayer() { if(active.getAndSet(false)) player.close() }
        fun stop() { try {closePlayer()} finally {job.cancel()} }
    }

    private val mounted = mutableStateMapOf<NativeVideoControl,Mounted>()
    private var closed = false

    override fun onSurfaceAvailable(control: NativeVideoControl, lease: NativeVideoSurfaceLease) {
        if(closed || !lease.isCurrent || mounted[control]?.lease === lease) return
        mounted.put(control,Mounted(lease))?.attempt?.stop()
    }

    override fun onSurfaceLost(control: NativeVideoControl, lease: NativeVideoSurfaceLease) {
        if(mounted[control]?.lease !== lease) return
        mounted.remove(control)?.attempt?.stop()
    }

    override fun play(control: NativeVideoControl, lease: NativeVideoSurfaceLease) {
        val entry=mounted[control] ?: return
        if(closed || entry.lease !== lease || !lease.isCurrent || !foreground() || !canStart() || entry.attempt!=null) return
        val record=displayed()
        val attempt=Attempt(playback())
        entry.attempt=attempt
        entry.status=NativeVideoStatus.Preparing
        val active={attempt.active.get() && lease.isCurrent && foreground()}
        attempt.job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=execute(record,control,lease,attempt.player,active) {
                    if(active()) entry.status=NativeVideoStatus.Playing
                }
                if(active()) entry.status=when(result.status) {
                    "succeeded", "cancelled" -> NativeVideoStatus.Idle
                    "unavailable", "denied" -> NativeVideoStatus.Unavailable
                    else -> NativeVideoStatus.Failed
                }
            } catch(cancel: CancellationException) {throw cancel}
              catch(_: Exception) {if(active()) entry.status=NativeVideoStatus.Failed}
            finally {
                attempt.closePlayer()
                if(entry.attempt===attempt) {
                    entry.attempt=null
                    if(entry.status==NativeVideoStatus.Preparing || entry.status==NativeVideoStatus.Playing) entry.status=NativeVideoStatus.Idle
                }
            }
        }
        attempt.job.start()
    }

    override fun status(control: NativeVideoControl, lease: NativeVideoSurfaceLease): NativeVideoStatus =
        mounted[control]?.takeIf {it.lease===lease}?.status ?: NativeVideoStatus.Unavailable

    fun close() {
        closed=true
        val previous=mounted.values.toList()
        mounted.clear()
        previous.forEach {it.attempt?.stop()}
    }
}

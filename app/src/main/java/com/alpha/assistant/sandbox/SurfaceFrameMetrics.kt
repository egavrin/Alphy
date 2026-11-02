package com.alpha.assistant.sandbox

import android.os.SystemClock

/** Bounded native acceptance measurements. No artifact ID, source, state, commands, or pointer data enters this trace. */
internal object SurfaceFrameMetrics {
    private const val CAPACITY=2048
    private val lock=Any()
    private var generation=0L
    private var current: Trace?=null
    data class Sample(val sequence: Long,val acceptedAtNs: Long,val latencyNs: Long)
    data class Snapshot(val sessionId: String,val generation: Long,val engineGeneration: Long,val startedAtNs: Long,
        val observedAtNs: Long,val stoppedAtNs: Long?,val status: String,val acceptedFrames: Long,val samples: List<Sample>)
    fun start(sessionId: String,engineGeneration: Long): Trace=synchronized(lock) {
        current?.stop("REPLACED")
        Trace(sessionId,++generation,engineGeneration).also {current=it}
    }
    fun snapshot(): Snapshot?=synchronized(lock) {current?.snapshot()}
    class Trace internal constructor(private val sessionId: String,private val generation: Long,private val engineGeneration: Long) {
        private val startedAtNs=SystemClock.elapsedRealtimeNanos()
        private var stoppedAtNs: Long?=null
        private var status="RUNNING"
        private var acceptedFrames=0L
        private val samples=arrayOfNulls<Sample>(CAPACITY)
        fun accepted(latencyNs: Long)=synchronized(lock) {
            if(current !== this || status!="RUNNING")return@synchronized
            val seq=++acceptedFrames
            samples[(seq%CAPACITY).toInt()]=Sample(seq,SystemClock.elapsedRealtimeNanos(),latencyNs)
        }
        fun paused(paused: Boolean)=synchronized(lock) {
            if(current === this && stoppedAtNs==null)status=if(paused)"PAUSED" else "RUNNING"
        }
        fun stop(reason: String="STOPPED")=synchronized(lock) {
            if(stoppedAtNs==null) {stoppedAtNs=SystemClock.elapsedRealtimeNanos();status=reason}
        }
        internal fun snapshot()=Snapshot(sessionId,generation,engineGeneration,startedAtNs,SystemClock.elapsedRealtimeNanos(),
            stoppedAtNs,status,acceptedFrames,samples.filterNotNull().sortedBy {it.sequence})
    }
}

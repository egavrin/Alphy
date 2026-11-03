package com.alpha.assistant.sandbox

import android.os.SystemClock

/** Bounded host evidence. Frame commits report platform buffer submission, never panel presentation. */
internal object SurfaceFrameMetrics {
    private const val CAPACITY=4096
    private val lock=Any()
    private var generation=0L
    private var current: Trace?=null
    data class Sample(val sequence: Long,val acceptedAtNs: Long,val latencyNs: Long)
    data class Commit(val sequence: Long,val committedAtNs: Long,val touchToCommitNs: Long?)
    data class Snapshot(val sessionId: String,val generation: Long,val engineGeneration: Long,val startedAtNs: Long,
        val observedAtNs: Long,val stoppedAtNs: Long?,val status: String,val acceptedFrames: Long,val samples: List<Sample>,
        val frameRequests: Long,val evaluations: Long,val validatedSnapshots: Long,val submissions: Long,val frameCommits: Long,
        val commits: List<Commit>,val presentationEvidence: String="UNAVAILABLE")
    fun start(sessionId: String,engineGeneration: Long): Trace=synchronized(lock) {
        current?.stop("REPLACED")
        Trace(sessionId,++generation,engineGeneration).also {current=it}
    }
    fun snapshot(): Snapshot?=synchronized(lock) {current?.snapshot()}
    class Trace internal constructor(private val sessionId: String,private val generation: Long,private val engineGeneration: Long) {
        private val startedAtNs=SystemClock.elapsedRealtimeNanos()
        private var stoppedAtNs: Long?=null
        private var status="RUNNING"
        private var frameRequests=0L
        private var evaluations=0L
        private var validatedSnapshots=0L
        private var submissions=0L
        private var frameCommits=0L
        private var lastValidated=Long.MIN_VALUE
        private var lastSubmitted=Long.MIN_VALUE
        private var lastCommitted=Long.MIN_VALUE
        private var evaluationLatency=0L
        private val samples=ArrayDeque<Sample>()
        private val commits=ArrayDeque<Commit>()
        private val touchTimes=linkedMapOf<Long,Long>()
        private fun active()=current === this && status=="RUNNING"
        fun requested()=synchronized(lock) {if(active())frameRequests++;Unit}
        fun evaluated(latencyNs: Long)=synchronized(lock) {
            if(active()) {evaluations++;evaluationLatency=latencyNs.coerceAtLeast(0)}
        }
        fun validated(sequence: Long,touchAtNs: Long?=null)=synchronized(lock) {
            if(!active())return@synchronized
            if(sequence>lastValidated) {
                lastValidated=sequence;validatedSnapshots++
                if(samples.size==CAPACITY)samples.removeFirst()
                samples.addLast(Sample(validatedSnapshots,SystemClock.elapsedRealtimeNanos(),evaluationLatency))
            }
            if(touchAtNs!=null) {
                touchTimes[sequence]=minOf(touchTimes[sequence] ?: touchAtNs,touchAtNs)
                while(touchTimes.size>CAPACITY)touchTimes.remove(touchTimes.keys.first())
            }
        }
        fun submitted(sequence: Long)=synchronized(lock) {
            if(active() && sequence>lastSubmitted) {lastSubmitted=sequence;submissions++}
        }
        fun committed(sequence: Long)=synchronized(lock) {
            if(!active() || sequence>lastSubmitted || sequence<=lastCommitted)return@synchronized
            lastCommitted=sequence;frameCommits++
            val now=SystemClock.elapsedRealtimeNanos()
            val touch=touchTimes.filterKeys {it<=sequence}.values.minOrNull()
            touchTimes.keys.removeAll {it<=sequence}
            if(commits.size==CAPACITY)commits.removeFirst()
            commits.addLast(Commit(sequence,now,touch?.let {(now-it).coerceAtLeast(0)}))
        }
        fun paused(paused: Boolean)=synchronized(lock) {
            if(current === this && stoppedAtNs==null)status=if(paused)"PAUSED" else "RUNNING"
        }
        fun stop(reason: String="STOPPED")=synchronized(lock) {
            if(stoppedAtNs==null) {stoppedAtNs=SystemClock.elapsedRealtimeNanos();status=reason}
        }
        internal fun snapshot()=Snapshot(sessionId,generation,engineGeneration,startedAtNs,SystemClock.elapsedRealtimeNanos(),
            stoppedAtNs,status,validatedSnapshots,samples.toList(),frameRequests,evaluations,validatedSnapshots,submissions,frameCommits,commits.toList())
    }
}

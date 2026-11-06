package com.alpha.assistant.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal interface LiveArtifactUiHost : ArtifactUiHost {
    suspend fun offerLiveInput(expected: ArtifactSnapshot,actionId: String,value: Any?,isActive: ()->Boolean): LiveInputSubmission
    suspend fun finishLiveInput(expected: ArtifactSnapshot,actionId: String,value: Any?,isActive: ()->Boolean): LiveInputSubmission
    fun liveInputFrame(frameTimeNanos: Long)
    fun cancelLiveInput()
}

internal enum class LiveInputRejection { Inactive, Conflict, Unavailable, Full, Finishing, Failed, TimedOut }

internal sealed interface LiveInputSubmission {
    data class Pending(val receipt: Deferred<LiveInputResult>): LiveInputSubmission
    data class Rejected(val reason: LiveInputRejection): LiveInputSubmission
}

internal sealed interface LiveInputResult {
    data class Accepted(val snapshot: ArtifactSnapshot): LiveInputResult
    data class Rejected(val reason: LiveInputRejection): LiveInputResult
    data object Superseded: LiveInputResult
}

internal data class LiveInputVersion(val revision: Int,val stateVersion: Int,val sequence: Long)
internal val ArtifactSnapshot.liveInputVersion get()=LiveInputVersion(tool.revision,tool.stateVersion,sequence)
internal class LiveInputLease(val generation: Long)
internal data class LiveInputKey(val lease: LiveInputLease,val actionId: String,val row: String?)

/** Pending values are bounded independently of the ordered evaluator. No user input cancels that evaluator. */
internal class ArtifactLiveInputQueue {
    class Delivery(val key: LiveInputKey,val event: String,val finished: Boolean,val active: ()->Boolean) {
        val receipt=CompletableDeferred<LiveInputResult>()
    }

    private class Edit(val initial: LiveInputVersion,var expected: LiveInputVersion)
    private val edits=linkedMapOf<LiveInputKey,Edit>()
    private val pending=linkedMapOf<LiveInputKey,Delivery>()
    @Volatile private var inFlight: Delivery?=null
    private var lastFrame=Long.MIN_VALUE
    @Volatile private var hasPending=false
    val needsFrame get()=hasPending && inFlight==null

    @Synchronized fun submit(key: LiveInputKey,event: String,shown: LiveInputVersion,current: LiveInputVersion,finished: Boolean,
        active: ()->Boolean={true}): LiveInputSubmission {
        val previous=pending[key]
        if(previous?.finished==true || inFlight?.let {it.key==key && it.finished && !it.receipt.isCompleted}==true)
            return LiveInputSubmission.Rejected(LiveInputRejection.Finishing)
        val edit=edits[key]
        if(edit==null) {
            if(shown!=current) return LiveInputSubmission.Rejected(LiveInputRejection.Conflict)
            if(edits.size>=ArtifactProtocol.QUEUE_LIMIT) return LiveInputSubmission.Rejected(LiveInputRejection.Full)
            edits[key]=Edit(current,current)
        } else {
            val sequenceDelta=shown.sequence-edit.initial.sequence
            val ownVersion=shown.revision==edit.initial.revision && shown.sequence in edit.initial.sequence..edit.expected.sequence &&
                shown.stateVersion.toLong()==edit.initial.stateVersion+sequenceDelta
            if(edit.expected!=current || !ownVersion) {
                reject(key,LiveInputRejection.Conflict)
                return LiveInputSubmission.Rejected(LiveInputRejection.Conflict)
            }
        }
        val delivery=Delivery(key,event,finished,active)
        pending[key]=delivery
        hasPending=true
        previous?.receipt?.complete(LiveInputResult.Superseded)
        return LiveInputSubmission.Pending(delivery.receipt)
    }

    @Synchronized fun takeFrame(frameTimeNanos: Long): Delivery? {
        if(inFlight!=null || frameTimeNanos<=lastFrame) return null
        val next=pending.entries.firstOrNull() ?: return null
        val delivery=checkNotNull(pending.remove(next.key))
        hasPending=pending.isNotEmpty()
        inFlight=delivery
        lastFrame=frameTimeNanos
        return delivery
    }

    @Synchronized fun expected(delivery: Delivery): LiveInputVersion? =
        edits[delivery.key]?.expected?.takeIf {inFlight===delivery && !delivery.receipt.isCompleted}

    @Synchronized fun complete(delivery: Delivery,result: LiveInputResult) {
        if(inFlight!==delivery) return
        inFlight=null
        if(delivery.receipt.isCompleted) return
        when(result) {
            is LiveInputResult.Accepted -> {
                if(delivery.finished) edits.remove(delivery.key)
                else edits[delivery.key]?.expected=result.snapshot.liveInputVersion
            }
            is LiveInputResult.Rejected -> reject(delivery.key,result.reason)
            LiveInputResult.Superseded -> error("An in-flight value cannot be superseded.")
        }
        delivery.receipt.complete(result)
    }

    @Synchronized fun cancel(lease: LiveInputLease,reason: LiveInputRejection=LiveInputRejection.Inactive) {
        edits.keys.filter {it.lease===lease}.forEach {reject(it,reason)}
    }

    @Synchronized fun finalReceipts(): List<Deferred<LiveInputResult>> = buildList {
        inFlight?.takeIf {it.finished && !it.receipt.isCompleted}?.let {add(it.receipt)}
        pending.values.filter {it.finished}.forEach {add(it.receipt)}
    }

    private fun reject(key: LiveInputKey,reason: LiveInputRejection) {
        edits.remove(key)
        val rejected=LiveInputResult.Rejected(reason)
        pending.remove(key)?.receipt?.complete(rejected)
        hasPending=pending.isNotEmpty()
        inFlight?.takeIf {it.key==key}?.receipt?.complete(rejected)
    }
}

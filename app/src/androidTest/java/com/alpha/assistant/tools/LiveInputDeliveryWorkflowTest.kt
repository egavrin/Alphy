package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LiveInputDeliveryWorkflowTest {
    private val start=LiveInputVersion(1,1,1)
    private fun host(generation: Long=1)=LiveInputLease(generation)
    private fun submit(queue: ArtifactLiveInputQueue,key: LiveInputKey,value: String,finished: Boolean=false,
        shown: LiveInputVersion=start,current: LiveInputVersion=start)=queue.submit(key,value,shown,current,finished)
    private fun receipt(result: LiveInputSubmission)=(result as LiveInputSubmission.Pending).receipt
    private fun record(version: LiveInputVersion)=ToolRecord("fixture",version.revision,version.stateVersion,"spec","{}","{}",null,true)
    private fun accepted(version: LiveInputVersion)=LiveInputResult.Accepted(ArtifactSnapshot(record(version),version.sequence))

    @Test fun onlyUnsentIntermediateIsReplacedAndFinalAcknowledgesDurableResult(): Unit=runBlocking {
        val queue=ArtifactLiveInputQueue()
        val key=LiveInputKey(host(),"edit",null)
        val first=receipt(submit(queue,key,"first"))
        val second=receipt(submit(queue,key,"second"))
        assertEquals(LiveInputResult.Superseded,first.await())
        val flight=checkNotNull(queue.takeFrame(1))
        assertEquals("second",flight.event)
        val third=receipt(submit(queue,key,"third"))
        val final=receipt(submit(queue,key,"final",finished=true))
        assertEquals(LiveInputResult.Superseded,third.await())
        assertFalse(second.isCompleted)
        assertFalse(final.isCompleted)
        assertNull(queue.takeFrame(2))
        val gate=CompletableDeferred<Unit>()
        val discrete=async {queue.finalReceipts().forEach {it.await()};gate.complete(Unit)}
        val secondVersion=LiveInputVersion(1,2,2)
        queue.complete(flight,accepted(secondVersion))
        assertEquals(secondVersion,(second.await() as LiveInputResult.Accepted).snapshot.liveInputVersion)
        assertFalse(gate.isCompleted)
        val last=checkNotNull(queue.takeFrame(3))
        assertEquals("final",last.event)
        assertEquals(secondVersion,queue.expected(last))
        val finalVersion=LiveInputVersion(1,3,3)
        queue.complete(last,accepted(finalVersion))
        assertEquals(finalVersion,(final.await() as LiveInputResult.Accepted).snapshot.liveInputVersion)
        discrete.await()
        assertNull(queue.takeFrame(4))
    }

    @Test fun otherHostsConflictAndCancelDoesNotReleaseTheInFlightSlot(): Unit=runBlocking {
        val queue=ArtifactLiveInputQueue()
        val first=LiveInputKey(host(),"edit",null)
        val second=LiveInputKey(host(2),"edit",null)
        val firstResult=receipt(submit(queue,first,"a"))
        val other=receipt(submit(queue,second,"b",finished=true))
        val inFlight=checkNotNull(queue.takeFrame(1))
        queue.cancel(first.lease,LiveInputRejection.Inactive)
        assertEquals(LiveInputResult.Rejected(LiveInputRejection.Inactive),firstResult.await())
        assertNull(queue.takeFrame(2))
        queue.complete(inFlight,accepted(LiveInputVersion(1,2,2)))
        val next=checkNotNull(queue.takeFrame(3))
        assertEquals(start,queue.expected(next))
        queue.complete(next,LiveInputResult.Rejected(LiveInputRejection.Conflict))
        assertEquals(LiveInputResult.Rejected(LiveInputRejection.Conflict),other.await())
        assertNull(queue.takeFrame(4))
    }

    @Test fun distinctRowsAreBoundedAndARejectedOfferCannotReplaceTheFinal(): Unit=runBlocking {
        val queue=ArtifactLiveInputQueue()
        val lease=host()
        repeat(ArtifactProtocol.QUEUE_LIMIT) {index ->
            assertTrue(submit(queue,LiveInputKey(lease,"edit",index.toString()),"row") is LiveInputSubmission.Pending)
        }
        assertEquals(LiveInputSubmission.Rejected(LiveInputRejection.Full),submit(queue,LiveInputKey(lease,"edit","overflow"),"overflow"))
        val key=LiveInputKey(lease,"edit","0")
        val final=receipt(submit(queue,key,"final",finished=true))
        assertEquals(LiveInputSubmission.Rejected(LiveInputRejection.Finishing),submit(queue,key,"later"))
        val delivery=checkNotNull(queue.takeFrame(1))
        assertEquals("final",delivery.event)
        queue.complete(delivery,LiveInputResult.Rejected(LiveInputRejection.Unavailable))
        assertEquals(LiveInputResult.Rejected(LiveInputRejection.Unavailable),final.await())
    }

    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val offline=object: ChatProvider {
        override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) = error("Live input is offline")
    }

    private suspend fun fixture(block: suspend (ToolStore,ToolEngine,ToolGenerationService,ToolRecord,ArtifactCoordinator)->Unit) {
        val database="live-delivery-${UUID.randomUUID()}.db"
        val generation=ToolGenerationService(offline,SandboxClient(context))
        val source="""
            const e=input.event,s=input.state;
            if(e.type==='liveInput') {
                if(e.value==='hang') while(true) {}
                if(e.value==='effects') return {state:s,effects:[]};
                globalThis.lastEdit=e.value;
                s.text=e.value;
            }
            if(e.type==='action' && e.actionId==='probe' && globalThis.lastEdit) s.text=globalThis.lastEdit;
            if(e.type==='frame') s.frames++;
            return e.type==='open' ? {state:s,frames:['board']} : {state:s};
        """.trimIndent()
        val spec=ToolSpec.parse(JSONObject("""{
            "formatVersion":3,"title":"Live fixture","description":"","capabilities":[],
            "stateSchema":{"text":"string","frames":"number"},"initialState":{"text":"","frames":0},
            "surfaces":[{"id":"board","width":64,"height":64}],
            "ui":{"type":"column","children":[
                {"type":"input","text":"Edit","bind":"text","action":"edit","inputMode":"live"},
                {"type":"input","text":"Hidden","bind":"text","action":"hidden","inputMode":"live","visible":false},
                {"type":"input","text":"Disabled","bind":"text","action":"disabled","inputMode":"live","enabled":false},
                {"type":"input","text":"Apply","bind":"text","action":"apply"},
                {"type":"button","text":"Probe","action":"probe"}
            ]}}
        """).put("source",source).toString())
        try {
            ToolStore(context,database).use {store ->
                val initial=ToolRecord("target",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
                store.saveTool(initial,true)
                val engine=ToolEngine(store,generation)
                try {block(store,engine,generation,initial,ArtifactCoordinator.get(store,initial.id))}
                finally {engine.closeArtifacts()}
            }
        } finally {context.deleteDatabase(database)}
    }

    @Test fun coordinatorCommitsOnlyTheFinalValueAndRejectsAStaleSecondHost(): Unit=runBlocking {
        fixture {store,engine,_,initial,coordinator ->
            val first=engine.openArtifact(initial)
            val second=engine.openArtifact(initial)
            val firstLease=coordinator.ordered {attachLiveInput(store,initial.id,1,first.sessionGeneration)}
            val secondLease=coordinator.ordered {attachLiveInput(store,initial.id,1,second.sessionGeneration)}
            fun submit(lease: LiveInputLease,shown: ArtifactSnapshot,action: String,value: String,final: Boolean) =
                coordinator.offerLiveInput(store,lease,shown,action,value,final) {true}
            for(id in listOf("hidden","disabled","apply","removed")) {
                assertEquals(LiveInputSubmission.Rejected(LiveInputRejection.Unavailable),submit(firstLease,first,id,"x",true))
            }
            val superseded=receipt(submit(firstLease,first,"edit","initial",false))
            val final=receipt(submit(firstLease,first,"edit","final",true))
            val conflict=receipt(submit(secondLease,second,"edit","other host",true))
            assertEquals(LiveInputResult.Superseded,superseded.await())
            coordinator.liveInputFrame(store,initial.id,1)
            val committed=withTimeout(5000) {final.await()} as LiveInputResult.Accepted
            assertEquals("final",JSONObject(committed.snapshot.tool.state).getString("text"))
            assertEquals(committed.snapshot.tool,store.tool(initial.id))
            coordinator.liveInputFrame(store,initial.id,2)
            assertEquals(LiveInputResult.Rejected(LiveInputRejection.Conflict),withTimeout(5000) {conflict.await()})
            assertTrue(store.effects(initial.id).isEmpty())
            engine.closeArtifact(initial.id,1,first.sessionGeneration)
            engine.closeArtifact(initial.id,1,second.sessionGeneration)
            val reopened=engine.openArtifact(checkNotNull(store.tool(initial.id)))
            assertEquals("final",JSONObject(reopened.tool.state).getString("text"))
        }
    }

    @Test fun foregroundLossAndUnflushedFrameChangesRejectPendingInput(): Unit=runBlocking {
        fixture {store,engine,_,initial,coordinator ->
            val first=engine.openArtifact(initial)
            val lease=coordinator.ordered {attachLiveInput(store,initial.id,1,first.sessionGeneration)}
            val active=java.util.concurrent.atomic.AtomicBoolean(true)
            val lost=receipt(coordinator.offerLiveInput(store,lease,first,"edit","unsaved",true,active::get))
            active.set(false)
            coordinator.liveInputFrame(store,initial.id,1)
            assertEquals(LiveInputResult.Rejected(LiveInputRejection.Inactive),withTimeout(5000) {lost.await()})
            active.set(true)
            val stale=receipt(coordinator.offerLiveInput(store,lease,first,"edit","stale",true,active::get))
            val frame=engine.artifactEvent(initial.id,1,GraphicsContract.event("frame",16,surfaceId="board"),first.sessionGeneration)
            assertEquals(1,JSONObject(frame.tool.state).getInt("frames"))
            coordinator.liveInputFrame(store,initial.id,2)
            assertEquals(LiveInputResult.Rejected(LiveInputRejection.Conflict),withTimeout(5000) {stale.await()})
            engine.flushArtifact(initial.id,1,first.sessionGeneration)
            assertEquals("",JSONObject(store.tool(initial.id)!!.state).getString("text"))
            assertEquals(1,JSONObject(store.tool(initial.id)!!.state).getInt("frames"))
        }
    }

    @Test fun timeoutNeverReplaysAndFallbackUsesTheRestrictedEvent(): Unit=runBlocking {
        fixture {store,engine,generation,initial,coordinator ->
            val applied=coordinator.applyLiveInput(store,generation,initial,"edit","fallback") {true} as LiveInputResult.Accepted
            assertEquals("fallback",JSONObject(applied.snapshot.tool.state).getString("text"))
            val retained=engine.openArtifact(applied.snapshot.tool)
            val lease=coordinator.ordered {attachLiveInput(store,initial.id,1,retained.sessionGeneration)}
            val hung=receipt(coordinator.offerLiveInput(store,lease,retained,"edit","hang",true) {true})
            coordinator.liveInputFrame(store,initial.id,1)
            assertEquals(LiveInputResult.Rejected(LiveInputRejection.TimedOut),withTimeout(5000) {hung.await()})
            val reopened=engine.openArtifact(checkNotNull(store.tool(initial.id)))
            assertEquals("fallback",JSONObject(reopened.tool.state).getString("text"))
            val bad=coordinator.applyLiveInput(store,generation,reopened.tool,"edit","effects") {true}
            assertTrue(bad is LiveInputResult.Rejected)
            assertEquals(applied.snapshot.tool,store.tool(initial.id))
            assertTrue(store.effects(initial.id).isEmpty())
        }
    }

    @Test fun authorityLossAfterDispatchRetiresTheRetainedGlobalsBeforeReopen(): Unit=runBlocking {
        fixture {store,engine,_,initial,coordinator ->
            val shown=engine.openArtifact(initial)
            val lease=coordinator.ordered {attachLiveInput(store,initial.id,1,shown.sessionGeneration)}
            val checks=AtomicInteger()
            // Admission, ordered admission, and dispatch gates succeed. The commit gate denies the result.
            val lost=receipt(coordinator.offerLiveInput(store,lease,shown,"edit","discard",true) {checks.incrementAndGet()<5})
            coordinator.liveInputFrame(store,initial.id,1)
            assertEquals(LiveInputResult.Rejected(LiveInputRejection.Inactive),withTimeout(5000) {lost.await()})
            assertEquals(5,checks.get())
            assertTrue(runCatching {engine.artifactSnapshot(initial.id,1,shown.sessionGeneration)}.isFailure)
            assertEquals(initial,store.tool(initial.id))
            val reopened=engine.openArtifact(initial)
            val probe=engine.interact(initial.id,1,reopened.tool.stateVersion,"probe",null)
            assertEquals("",JSONObject(probe.state).getString("text"))
        }
    }

    @Test fun twoNonGraphicsTilesCommitIndependentlyWhileAnotherAppHoldsDrawingSlot(): Unit=runBlocking {
        fixture {store,engine,generation,graphics,_ ->
            val spec=ToolSpec.parse("""{
                "formatVersion":3,"title":"Tile","description":"","capabilities":[],
                "stateSchema":{"rows":{"type":"list","maxItems":2,"fields":{"id":"string","label":"string"}}},
                "initialState":{"rows":[{"id":"one","label":"Before"}]},
                "ui":{"type":"list","text":"Rows","bind":"rows","rowTemplate":
                    {"type":"input","text":"Edit row","bind":"label","action":"rowEdit","inputMode":"live"}},
                "source":"const e=input.event,s=input.state; if(e.type==='liveInput') s.rows[0].label=e.value.value; return {state:s};"}
            """)
            val records=(1..2).map {number ->
                ToolRecord("tile-$number",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true).also {store.saveTool(it,true)}
            }
            val owners=records.map {Any()}
            val coordinators=records.map {ArtifactCoordinator.get(store,it.id)}
            val drawing=engine.openArtifact(graphics)
            suspend fun open(index: Int)=coordinators[index].ordered {
                val record=records[index]
                val shown=openNonGraphics(store,generation,record.id,record.revision,owners[index]) {true}
                shown to attachLiveInput(store,record.id,record.revision,shown.sessionGeneration)
            }
            val firstOpen=async {open(0)}
            val secondOpen=async {open(1)}
            val opened=listOf(firstOpen.await(),secondOpen.await())
            fun submit(index: Int,key: String,value: String): LiveInputSubmission {
                val (shown,lease)=opened[index]
                return coordinators[index].offerLiveInput(store,lease,shown,"rowEdit",JSONObject().put("key",key).put("value",value),true) {true}
            }
            fun label(index: Int)=JSONObject(store.tool(records[index].id)!!.state).getJSONArray("rows").getJSONObject(0).getString("label")
            try {
                assertEquals(LiveInputSubmission.Rejected(LiveInputRejection.Unavailable),submit(0,"removed","never"))
                val cancel=receipt(submit(0,"one","cancel"))
                coordinators[0].cancelLiveInput(opened[0].second)
                assertEquals(LiveInputResult.Rejected(LiveInputRejection.Inactive),cancel.await())
                val accepted=records.indices.map {receipt(submit(it,"one","Tile $it"))}
                records.indices.forEach {coordinators[it].liveInputFrame(store,records[it].id,1)}
                accepted.forEach {assertTrue(withTimeout(5000) {it.await()} is LiveInputResult.Accepted)}
                assertEquals("Tile 0",label(0))
                assertEquals("Tile 1",label(1))
                assertEquals(LiveInputSubmission.Rejected(LiveInputRejection.Conflict),submit(1,"one","stale"))
                assertEquals(drawing.tool,engine.artifactSnapshot(graphics.id,1,drawing.sessionGeneration).tool)
            } finally {
                records.indices.forEach {index -> coordinators[index].ordered {
                    closeOwned(store,1,owners[index],opened[index].first.sessionGeneration)
                }}
            }
            val (reopened,_)=open(0)
            try {assertEquals("Tile 0",JSONObject(reopened.tool.state).getJSONArray("rows").getJSONObject(0).getString("label"))}
            finally {coordinators[0].ordered {closeOwned(store,1,owners[0],reopened.sessionGeneration)}}
        }
    }
}

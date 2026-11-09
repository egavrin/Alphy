package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.SecureKeyStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ToolControllerDraftPreviewWorkflowTest {
    private fun pending(id: String="draft")=ToolDraft(id,null,0,0,"Make a tool",null,null,null,"chat")
    private fun snapshot(draftId: String,requestId: String,attempt: Int,state: String="""{"count":7}""") =
        ToolDraftPreview(ToolDraftPreviewIdentity(draftId,requestId,attempt),"candidate",state,"ui","{}")
    private fun ToolDraft.complete(preview: ToolDraftPreview) =
        copy(candidate=preview.candidateJson,previewState=preview.stateJson,previewUi=preview.uiJson)

    @Test fun currentCorrectionAndLateCallbacksKeepOnePreview(): Unit=runBlocking {
        val draft=pending()
        val state=ToolControllerDraftPreviews(this) {draft}
        val first=state.begin(draft.id,"request-one")
        val old=snapshot(draft.id,"request-one",1)
        first.onPreview(old)
        assertEquals(old,state.previewFor(draft.id))
        assertNull(state.previewFor("other"))
        first.onClear(old.identity)
        assertNull(state.previewFor(draft.id))
        first.onPreview(old)
        assertNull(state.previewFor(draft.id))

        val corrected=snapshot(draft.id,"request-one",2)
        first.onPreview(corrected)
        first.onClear(old.identity)
        assertEquals(corrected,state.previewFor(draft.id))
        val retry=state.begin(draft.id,"request-two")
        assertNull(state.previewFor(draft.id))
        val latest=snapshot(draft.id,"request-two",1)
        retry.onPreview(latest)
        first.onClear(corrected.identity)
        first.onPreview(corrected)
        assertEquals(latest,state.previewFor(draft.id))
    }

    @Test fun canceledOrReplacedRequestCannotPublishAfterSuspendedRead(): Unit=runBlocking {
        val draft=pending()
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val reads=java.util.concurrent.atomic.AtomicInteger()
        val state=ToolControllerDraftPreviews(this) {
            if(reads.incrementAndGet()==1) {
                entered.complete(Unit)
                release.await()
            }
            draft
        }
        val first=state.begin(draft.id,"old")
        val old=snapshot(draft.id,"old",1)
        val publishing=launch {first.onPreview(old)}
        entered.await()
        state.clear()
        assertNull(state.previewFor(draft.id))
        val replacement=state.begin(draft.id,"new")
        val current=snapshot(draft.id,"new",1)
        replacement.onPreview(current)
        release.complete(Unit)
        publishing.join()
        first.onClear(old.identity)
        assertEquals(current,state.previewFor(draft.id))
        state.clear()
        replacement.onPreview(current)
        assertNull(state.previewFor(draft.id))
    }

    @Test fun completedPreviewRequiresExactStateUiAndCandidateAndAnExistingDraft(): Unit=runBlocking {
        val original=pending()
        val stored=AtomicReference<ToolDraft?>(original)
        val state=ToolControllerDraftPreviews(this) {stored.get()}
        val preview=snapshot(original.id,"request",1)
        val observer=state.begin(original.id,"request")
        observer.onPreview(preview)
        val completed=original.complete(preview)
        stored.set(completed)
        state.complete("request",completed)
        assertEquals(preview,state.previewFor(original.id))
        val snapshotRequest=state.request()
        state.reconcile(snapshotRequest,listOf(completed.copy(previewUi="changed")))
        assertNull(state.previewFor(original.id))

        val mismatches=listOf(completed.copy(candidate="changed"),completed.copy(previewState="changed"),
            completed.copy(previewUi="changed"),completed.copy(baseRevision=-1))
        for((index,mismatch) in mismatches.withIndex()) {
            stored.set(original)
            val requestId="retry-$index"
            val next=state.begin(original.id,requestId)
            next.onPreview(preview.copy(identity=preview.identity.copy(requestId=requestId)))
            state.complete(requestId,mismatch)
            assertNull(state.previewFor(original.id))
        }

        stored.set(original)
        val beforeDisappear=state.begin(original.id,"removed")
        beforeDisappear.onPreview(preview.copy(identity=preview.identity.copy(requestId="removed")))
        val removedRequest=state.request()
        stored.set(null)
        state.reconcile(removedRequest,emptyList())
        assertNull(state.previewFor(original.id))
        beforeDisappear.onPreview(preview.copy(identity=preview.identity.copy(requestId="removed")))
        assertNull(state.previewFor(original.id))
    }

    @Test fun recreatedControllerReadsCompletedDraftWithoutTemporaryPreview(): Unit=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val name="draft-preview-${UUID.randomUUID()}.db"
        val keys=SecureKeyStore(app,name,name)
        val controllerScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val definition="""{"formatVersion":3,"title":"Drawing","description":"","capabilities":[],
            "stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"text","text":"Drawing"},
            "surfaces":[{"id":"board","width":64,"height":64}],
            "source":"return {state:input.state,surfaces:{board:{commands:[{type:'circle',x:8,y:16,radius:2,color:'accent'}]}}};"}"""
        val fixture=object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                if(!request.replyWithFixturePlan(emit, fixtureScene())) emit(CloudEvent.Text(JSONObject(definition).withFixtureDrawingBinding("board").toString()))
            }
        }
        val offline=object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                error("Opening a completed draft does not need a provider.")
            }
        }
        suspend fun <T> withController(provider: ChatProvider,block: suspend (ToolController)->T): T {
            val store=ToolStore(app,name)
            val database=store.writableDatabase
            val controller=withContext(Dispatchers.Main) {ToolController(app,controllerScope,provider,keys,store)}
            try {
                withTimeout(5000) {while(withContext(Dispatchers.Main) {controller.busy}) delay(10)}
                return block(controller)
            } finally {
                withContext(Dispatchers.Main) {controller.close()}
                withTimeout(5000) {while(database.isOpen) delay(10)}
            }
        }
        try {
            keys.save("fixture").getOrThrow()
            val complete=withController(fixture) {original ->
                withContext(Dispatchers.Main) {original.generate("Draw a dot.",null,"chat",emptyList())}
                withTimeout(15000) {while(withContext(Dispatchers.Main) {original.busy}) delay(10)}
                withContext(Dispatchers.Main) {
                    assertNull(original.error)
                    val draft=original.drafts.single()
                    val preview=checkNotNull(original.previewFor(draft.id))
                    assertEquals(draft.candidate,preview.candidateJson)
                    assertEquals(draft.previewState,preview.stateJson)
                    assertEquals(draft.previewUi,preview.uiJson)
                    assertTrue(original.tools.isEmpty())
                    draft
                }
            }
            withController(offline) {recreated ->
                withContext(Dispatchers.Main) {
                    assertEquals(complete,recreated.drafts.single())
                    assertNull(recreated.previewFor(complete.id))
                    assertTrue(recreated.tools.isEmpty())
                }
            }
        } finally {
            controllerScope.cancel()
            app.deleteDatabase(name)
            app.deleteDatabase("$name-automations.db")
            keys.clear()
            app.deleteSharedPreferences(name)
        }
    }
}

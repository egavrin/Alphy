package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedArtifactWorkflowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val offline=object: ChatProvider {
        override suspend fun respond(key:String,request:CloudRequest,session:CloudSession,emit:suspend(CloudEvent)->Unit)=error("Offline fixture")
    }
    private fun definition(source:String = """
        const s=input.state;
        if(input.event.type==='frame') s.count++;
        if(input.event.type==='action') s.count+=10;
        return {state:s,frames:['left','right'],surfaces:{left:{commands:[]},right:{commands:[]}}};
    """.trimIndent())=ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Shared runtime").put("description","")
        .put("capabilities",JSONArray()).put("stateSchema",JSONObject("{\"count\":\"number\"}"))
        .put("initialState",JSONObject("{\"count\":0}"))
        .put("ui",JSONObject("{\"type\":\"button\",\"text\":\"Add\",\"action\":\"add\"}"))
        .put("surfaces",JSONArray("[{\"id\":\"left\",\"width\":320,\"height\":200},{\"id\":\"right\",\"width\":320,\"height\":200}]"))
        .put("source",source).toString())
    private suspend fun fixture(block:suspend(ToolStore,ToolEngine,ToolRecord)->Unit) {
        val name="shared-runtime-${UUID.randomUUID()}.db"
        val store=ToolStore(context,name)
        val spec=definition();val initial=ToolRecord("shared",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
        store.saveTool(initial,true)
        val engine=ToolEngine(store,ToolGenerationService(offline,SandboxClient(context)))
        try {block(store,engine,initial)} finally {runCatching {engine.closeArtifact(initial.id,initial.revision)};store.close();context.deleteDatabase(name)}
    }

    @Test fun retainedHostHandoffWaitsForReleaseAndKeepsTheQueuedAction(): Unit=runBlocking {
        fixture {store,engine,first ->
            val nextSpec=definition("""
                const state = input.state;
                if (input.event.type === 'open') globalThis.opened = true;
                if (input.event.type === 'action') {
                    if (globalThis.opened !== true) throw Error('Open must precede the action');
                    state.count++;
                }
                return {state};
            """.trimIndent())
            val next=first.copy(id="next",spec=nextSpec.json)
            store.saveTool(next,true)
            val firstHost=engine.openArtifact(first)
            try {
                val cancelled=async(start=CoroutineStart.UNDISPATCHED) {engine.openArtifactHost(next.id,next.revision)}
                assertFalse("An occupied lease must suspend host admission",cancelled.isCompleted)
                cancelled.cancelAndJoin()
                val preserved=engine.interact(first.id,first.revision,first.stateVersion,"add",null)
                assertEquals(10,JSONObject(preserved.state).getInt("count"))

                val opening=async(start=CoroutineStart.UNDISPATCHED) {engine.openArtifactHost(next.id,next.revision)}
                val action=async(start=CoroutineStart.UNDISPATCHED) {engine.interact(next.id,next.revision,next.stateVersion,"add",null)}
                assertFalse(opening.isCompleted)
                assertFalse(action.isCompleted)
                assertEquals(next,store.tool(next.id))
                engine.closeArtifact(first.id,first.revision,firstHost.sessionGeneration)

                val opened=withTimeout(5000) {opening.await()}
                val changed=withTimeout(5000) {action.await()}
                assertEquals(1,JSONObject(changed.state).getInt("count"))
                assertEquals(changed,store.tool(next.id))
                assertEquals(preserved,store.tool(first.id))
                engine.closeArtifact(next.id,next.revision,opened.sessionGeneration)
            } finally {
                engine.closeArtifact(first.id,first.revision,firstHost.sessionGeneration)
                engine.closeArtifact(next.id,next.revision)
            }
        }
    }

    @Test fun widgetEvaluationCannotOverwriteNewerSavedStateWhileARetainedFrameIsPending(): Unit=runBlocking {
        fixture {store,engine,initial ->
            val evaluated=CompletableDeferred<ToolPreview>()
            val release=CompletableDeferred<Unit>()
            val foreground=ToolGenerationService(offline,SandboxClient(context))
            val widget=ToolEngine(store,foreground)
            engine.openArtifact(initial)
            var pending: Deferred<Result<ToolRecord>>?=null
            try {
                ArtifactCoordinator.get(store,initial.id).ordered {
                    val live=event(store,foreground,initial.id,initial.revision,GraphicsContract.event("frame",dt=16,surfaceId="left"))
                    assertEquals(1,JSONObject(live.tool.state).getInt("count"))
                    assertEquals(initial,store.tool(initial.id))
                    pending=async(start=CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            widget.interact(initial.id,initial.revision,initial.stateVersion,"add",null,
                                allowDeviceEffects=false,onWidgetEvaluated={ preview ->
                                    evaluated.complete(preview)
                                    release.await()
                                })
                        }
                    }
                }
                val preview=withTimeout(5000) {evaluated.await()}
                assertEquals(11,JSONObject(preview.state).getInt("count"))
                assertTrue(preview.effects.isEmpty())
                val external=initial.copy(stateVersion=initial.stateVersion+1,state="{\"count\":100}")
                store.commitTool(external,initial,false)
                release.complete(Unit)
                val failure=withTimeout(5000) {checkNotNull(pending).await()}.exceptionOrNull()
                assertEquals("Saved data changed during widget evaluation. Reopen the mini-app.",failure?.message)
                assertEquals(external,store.tool(initial.id))
                assertTrue(store.effects(initial.id).isEmpty())
            } finally {
                release.complete(Unit)
                pending?.cancelAndJoin()
            }
        }
    }

    @Test fun widgetCommitsAfterExactlyOneFlushOfItsCapturedRetainedState(): Unit=runBlocking {
        fixture {store,engine,initial ->
            val generation=ToolGenerationService(offline,SandboxClient(context))
            engine.openArtifact(initial)
            val widget=ArtifactCoordinator.get(store,initial.id).ordered {
                val live=event(store,generation,initial.id,initial.revision,GraphicsContract.event("frame",dt=16,surfaceId="left"))
                assertEquals(1,JSONObject(live.tool.state).getInt("count"))
                assertEquals(initial,store.tool(initial.id))
                async(start=CoroutineStart.UNDISPATCHED) {
                    engine.interact(initial.id,initial.revision,initial.stateVersion,"add",null,allowDeviceEffects=false)
                }
            }
            val committed=withTimeout(5000) {widget.await()}
            assertEquals(initial.stateVersion+2,committed.stateVersion)
            assertEquals(11,JSONObject(committed.state).getInt("count"))
            assertEquals(committed,store.tool(initial.id))
            assertTrue(store.effects(initial.id).isEmpty())
        }
    }

    /** Failure cases: omitted playback loses native controls, explicit playback loses authority,
     * omitted values reset an opted-in session, or reopen loses the saved pause.
     */
    @Test fun playbackOmissionAndExplicitAuthorityHaveOneSnapshotSource():Unit=runBlocking {
        fixture {store,engine,initial ->
            val source="""
                const s=input.state,e=input.event;
                if(e.type==='control')s.count++;
                const result={state:s,frames:['left']};
                if(s.count===1)result.playback='paused';
                if(s.count===3)result.playback='playing';
                return result;
            """.trimIndent()
            val tool=initial.copy(spec=definition(source).json);store.saveTool(tool,true)
            assertEquals("playing",engine.openArtifact(tool).playback)
            assertEquals("paused",engine.artifactEvent(tool.id,1,GraphicsContract.event("pause",surfaceId="left")).playback)
            assertEquals("playing",engine.artifactEvent(tool.id,1,GraphicsContract.event("restart",surfaceId="left")).playback)
            assertEquals("paused",engine.artifactEvent(tool.id,1,GraphicsContract.event("control",surfaceId="left")).playback)
            assertEquals("paused",engine.artifactEvent(tool.id,1,GraphicsContract.event("resume",surfaceId="left")).playback)
            assertEquals("paused",engine.artifactEvent(tool.id,1,GraphicsContract.event("control",surfaceId="left")).playback)
            assertEquals("paused",engine.artifactEvent(tool.id,1,GraphicsContract.event("restart",surfaceId="left")).playback)
            assertEquals("playing",engine.artifactEvent(tool.id,1,GraphicsContract.event("control",surfaceId="left")).playback)
        }
        fixture {store,engine,initial ->
            val source="""
                const s=input.state,e=input.event;
                if(e.type==='start'||e.type==='resume'||e.type==='restart')s.count=1;
                if(e.type==='pause')s.count=2;
                return {state:s,frames:['left'],playback:['ready','playing','paused'][s.count]};
            """.trimIndent()
            val tool=initial.copy(spec=definition(source).json);store.saveTool(tool,true)
            assertEquals("ready",engine.openArtifact(tool).playback)
            assertEquals("playing",engine.artifactEvent(tool.id,1,GraphicsContract.event("start",surfaceId="left")).playback)
            assertEquals("paused",engine.artifactEvent(tool.id,1,GraphicsContract.event("pause",surfaceId="left")).playback)
            engine.closeArtifact(tool.id,1)
            val reopened=engine.openArtifact(checkNotNull(store.tool(tool.id)))
            assertEquals("paused",reopened.playback)
            assertEquals(2,JSONObject(reopened.tool.state).getInt("count"))
        }
    }
    @Test fun explicitPlaybackFromFirstSurfaceStopsAutomaticStarts(): Unit=runBlocking {
        fixture {store,engine,initial ->
            val source="""
                const s=input.state,e=input.event;
                if(e.type==='start')s.count++;
                if(e.type==='resume')return {state:s,playback:'playing'};
                return s.count===0 ? {state:s} : {state:s,playback:'paused'};
            """.trimIndent()
            val tool=initial.copy(spec=definition(source).json);store.saveTool(tool,true)
            val open=engine.openArtifactHost(tool.id,tool.revision)
            val started=engine.startArtifactHostSession(tool.id,tool.revision,open.sessionGeneration)
            assertEquals("paused",started.playback)
            assertEquals(1,JSONObject(started.tool.state).getInt("count"))
            engine.artifactEvent(tool.id,tool.revision,GraphicsContract.event("resume",surfaceId="left"))
            val resumed=engine.startArtifactHostSession(tool.id,tool.revision,open.sessionGeneration)
            assertEquals("playing",resumed.playback)
            assertEquals(1,JSONObject(resumed.tool.state).getInt("count"))
        }
    }
    @Test fun surfacesAndActionsShareStateAndCloseFlushes():Unit=runBlocking {
        fixture {store,engine,initial ->
            val start=engine.openArtifact(initial)
            assertEquals(setOf("left","right"),start.frames)
            val left=engine.artifactEvent(initial.id,1,GraphicsContract.event("frame",16,surfaceId="left"))
            val right=engine.artifactEvent(initial.id,1,GraphicsContract.event("frame",16,surfaceId="right"))
            assertTrue(right.sequence>left.sequence)
            assertEquals(2,JSONObject(right.tool.state).getInt("count"))
            val action=engine.interact(initial.id,1,right.tool.stateVersion,"add",null)
            assertEquals(12,JSONObject(action.state).getInt("count"))
            engine.artifactEvent(initial.id,1,GraphicsContract.event("frame",16,surfaceId="left"))
            val flushed=engine.flushArtifact(initial.id,1)
            assertEquals(13,JSONObject(flushed.tool.state).getInt("count"))
            assertEquals(store.tool(initial.id),flushed.tool)
            engine.closeArtifact(initial.id,1)
            assertEquals(13,JSONObject(store.tool(initial.id)!!.state).getInt("count"))
            assertTrue(runCatching {engine.interact(initial.id,1,initial.stateVersion,"add",null)}.isFailure)
        }
    }
    @Test fun invalidAutonomousEffectsNeverCommit():Unit=runBlocking {
        fixture {store,engine,initial ->
            val spec=definition("if(input.event.type==='frame') return {state:{count:99},effects:[{id:'buzz',capability:'vibration.play',args:{}}]}; return {state:input.state,frames:['left']};")
            val tool=initial.copy(spec=spec.json);store.saveTool(tool,true)
            engine.openArtifact(tool)
            assertTrue(runCatching {engine.artifactEvent(tool.id,1,GraphicsContract.event("frame",16,surfaceId="left"))}.isFailure)
            assertEquals(tool,store.tool(tool.id));assertTrue(store.effects(tool.id).isEmpty())
        }
    }
    @Test fun pendingEffectsFenceContinuousStateAndDeliverOnce():Unit=runBlocking {
        fixture {store,engine,initial ->
            val json=JSONObject(initial.spec).put("capabilities",JSONArray("[\"vibration.play\"]")).put("capabilityResults",true)
                .put("source","const s=input.state; if(input.event.type==='action') return {state:s,effects:[{id:'buzz',capability:'vibration.play',args:{milliseconds:1}}]}; if(input.event.type==='capabilityResults') s.count+=100; if(input.event.type==='frame')s.count++; return {state:s,frames:['left']};")
            val spec=ToolSpec.parse(json.toString());val tool=initial.copy(spec=spec.json);store.saveTool(tool,true)
            engine.openArtifact(tool)
            val action=engine.interact(tool.id,1,1,"add",null)
            val fenced=engine.artifactEvent(tool.id,1,GraphicsContract.event("frame",16,surfaceId="left"))
            assertEquals(action.stateVersion,fenced.tool.stateVersion);assertEquals(0,JSONObject(fenced.tool.state).getInt("count"))
            val effect=store.effects(tool.id).single();assertNotNull(store.claimEffect(effect.key));store.finishEffect(effect.key,CapabilityResult("succeeded","Done"))
            engine.deliverCapabilityResults(tool.id);engine.deliverCapabilityResults(tool.id)
            assertEquals(100,JSONObject(store.tool(tool.id)!!.state).getInt("count"))
            assertEquals("committed",store.deliveries(tool.id).single().phase)
        }
    }
    @Test fun oldArtifactRowsRemainIntactAndInvalidEventsFail():Unit=runBlocking {
        fixture {store,engine,initial ->
            val old=initial.copy(spec=JSONObject(initial.spec).put("formatVersion",2).toString())
            store.writableDatabase.execSQL("UPDATE tools SET spec=? WHERE id=?",arrayOf(old.spec,old.id))
            assertEquals(old,store.tool(old.id));assertNull(ToolSpec.available(old.spec))
            assertTrue(runCatching {engine.openArtifact(old)}.isFailure);assertEquals(old,store.tool(old.id))
            assertTrue(runCatching {GraphicsContract.validateEvent(JSONObject("{\"type\":\"frame\",\"surfaceId\":\"left\",\"dt\":16,\"effects\":[]}"))}.isFailure)
        }
    }
    /** Partial maps can each fit while their retained union exceeds command or UTF-8 limits. */
    @Test fun mergedSurfaceLimitsRejectBeforePublicationStateOrEffects():Unit=runBlocking {
        val cases=listOf(
            257 to "{type:'group',children:[]}",
            150 to "{type:'rect',x:1234.1234567890123,y:1234.1234567890123,width:1234.1234567890123,height:1234.1234567890123,color:'#FFFFFFFF'}"
        )
        for((count,command) in cases)for(type in listOf("frame","action"))fixture {store,engine,initial ->
            val source="""
                const commands=Array.from({length:$count},()=>($command));
                if(input.event.type==='open')return {state:input.state,surfaces:{left:{commands}},frames:['right']};
                const result={state:{count:99},surfaces:{right:{commands}}};
                if(input.event.type==='action')result.effects=[{id:'buzz',capability:'vibration.play',args:{milliseconds:1}}];
                return result;
            """.trimIndent()
            val spec=ToolSpec.parse(JSONObject(definition(source).json).put("capabilities",JSONArray("[\"vibration.play\"]")).toString())
            val tool=initial.copy(spec=spec.json);store.saveTool(tool,true)
            val before=engine.openArtifact(tool)
            assertEquals(count,before.surfaces.getJSONObject("left").getJSONArray("commands").length())
            val rejected=runCatching {
                if(type=="action")engine.interact(tool.id,tool.revision,tool.stateVersion,"add",null)
                else engine.artifactEvent(tool.id,tool.revision,GraphicsContract.event("frame",16,surfaceId="right"))
            }
            assertTrue("$type must reject merged output with $count commands per surface",rejected.isFailure)
            val retained=ArtifactCoordinator.get(store,tool.id).ordered {snapshot(store)}
            assertEquals(before.surfaces.toString(),retained.surfaces.toString())
            assertEquals(before.tool,retained.tool)
            assertEquals(tool,store.tool(tool.id));assertTrue(store.effects(tool.id).isEmpty())
            assertFalse(store.networkGrantEnabled(tool.id))
        }
    }
    /** Old engine cleanup and stale hosts must not close or drive a successor's retained session. */
    @Test fun successorSessionSurvivesOldOwnerCleanup():Unit=runBlocking {
        fixture {store,first,initial ->
            val second=ToolEngine(store,ToolGenerationService(offline,SandboxClient(context)))
            try {
                first.openArtifact(initial)
                first.artifactEvent(initial.id,1,GraphicsContract.event("frame",16,surfaceId="left"))
                second.openArtifact(checkNotNull(store.tool(initial.id)))
                first.closeArtifacts()
                first.closeArtifact(initial.id,1)
                val continued=second.artifactEvent(initial.id,1,GraphicsContract.event("frame",16,surfaceId="right"))
                assertEquals(2,JSONObject(continued.tool.state).getInt("count"))
                assertTrue(runCatching {first.artifactEvent(initial.id,1,GraphicsContract.event("frame",16,surfaceId="left"))}.isFailure)
                second.closeArtifacts()
                assertEquals(2,JSONObject(store.tool(initial.id)!!.state).getInt("count"))
            } finally {second.closeArtifacts()}
        }
    }
    @Test fun oldControllerAndOldHostCleanupPreserveSuccessorAndFlush():Unit=runBlocking {
        fixture {store,_,initial ->
            val app=context.applicationContext as android.app.Application
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val oldStore=ToolStore(context,store.databaseName)
            val newStore=ToolStore(context,store.databaseName)
            val oldDb=oldStore.writableDatabase
            val newDb=newStore.writableDatabase
            val keys=com.alpha.assistant.data.SecureKeyStore(app,"ownership-fixture-${UUID.randomUUID()}","ownership-fixture")
            val old=withContext(Dispatchers.Main) {ToolController(app,scope,offline,keys,oldStore)}
            val next=withContext(Dispatchers.Main) {ToolController(app,scope,offline,keys,newStore)}
            try {
                withTimeout(5000) {while(old.busy || next.busy)delay(10)}
                val oldHost=old.artifactHost(initial)
                oldHost.open();oldHost.event(GraphicsContract.event("frame",16,surfaceId="left"))
                val nextHost=next.artifactHost(checkNotNull(store.tool(initial.id)))
                nextHost.open()
                withContext(Dispatchers.Main) {old.close()}
                withTimeout(5000) {while(oldDb.isOpen)delay(10)}
                oldHost.close()
                val continued=nextHost.event(GraphicsContract.event("frame",16,surfaceId="right"))
                assertEquals(2,JSONObject(continued.tool.state).getInt("count"))
                val replacement=next.artifactHost(continued.tool)
                replacement.open();nextHost.close()
                assertTrue(runCatching {nextHost.event(GraphicsContract.event("frame",16,surfaceId="left"))}.isFailure)
                val latest=replacement.event(GraphicsContract.event("frame",16,surfaceId="left"))
                assertEquals(3,JSONObject(latest.tool.state).getInt("count"))
                replacement.close()
                assertEquals(3,JSONObject(store.tool(initial.id)!!.state).getInt("count"))
            } finally {
                withContext(Dispatchers.Main) {old.close();next.close()}
                withTimeout(5000) {while(oldDb.isOpen || newDb.isOpen)delay(10)}
                scope.cancel()
            }
        }
    }
    /** Cancellation can discard an acquired snapshot before IO returns or Main publishes it.
     * Cleanup must release that generation, preserve its state, and leave a successor open.
     */
    @Test fun cancelledPublicationReleasesOnlyItsAcquiredSession():Unit=runBlocking {
        for(successor in listOf(false,true))fixture {store,engine,initial ->
            val app=context.applicationContext as android.app.Application
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val controllerStore=ToolStore(context,store.databaseName)
            val database=controllerStore.writableDatabase
            val keys=com.alpha.assistant.data.SecureKeyStore(app,"cancel-fixture-${UUID.randomUUID()}","cancel-fixture")
            val controller=withContext(Dispatchers.Main) {ToolController(app,scope,offline,keys,controllerStore)}
            val releaseMain=java.util.concurrent.CountDownLatch(1)
            var blocker: Job?=null
            var host: ArtifactUiHost?=null
            try {
                withTimeout(5000) {while(controller.busy)delay(10)}
                val spec=definition("if(input.event.type==='open'||input.event.type==='frame')input.state.count++;return {state:input.state,frames:['left']};")
                val tool=initial.copy(spec=spec.json);store.saveTool(tool,true)
                val enteredMain=CompletableDeferred<Unit>()
                blocker=scope.launch {
                    enteredMain.complete(Unit)
                    check(releaseMain.await(5,java.util.concurrent.TimeUnit.SECONDS))
                }
                enteredMain.await()
                val active=controller.artifactHost(tool);host=active
                val opening=launch(Dispatchers.IO) {active.open()}
                withTimeout(4000) {
                    while(ArtifactCoordinator.get(store,tool.id).ordered {runCatching {snapshot(store).sequence}.getOrDefault(0)}==0L)delay(1)
                }
                val replacement=if(successor)engine.openArtifact(checkNotNull(store.tool(tool.id))) else null
                opening.cancel();releaseMain.countDown();opening.join();blocker.join()
                if(replacement!=null) {
                    val continued=engine.artifactEvent(tool.id,1,GraphicsContract.event("frame",16,surfaceId="left"),replacement.sessionGeneration)
                    assertEquals(2,JSONObject(continued.tool.state).getInt("count"))
                    engine.closeArtifact(tool.id,1,replacement.sessionGeneration)
                } else {
                    val probe=com.alpha.assistant.sandbox.GraphicsSession(context)
                    try {assertEquals("Cancelled open must release the retained slot","OK",probe.open("return {state:input.state};","{}").status)}
                    finally {probe.close()}
                    assertEquals(1,JSONObject(checkNotNull(store.tool(tool.id)).state).getInt("count"))
                    val reopened=engine.openArtifact(checkNotNull(store.tool(tool.id)))
                    assertEquals(2,JSONObject(reopened.tool.state).getInt("count"))
                }
            } finally {
                releaseMain.countDown();blocker?.join()
                host?.close()
                withContext(Dispatchers.Main) {controller.close()}
                withTimeout(5000) {while(database.isOpen)delay(10)}
                scope.cancel()
            }
        }
    }
    /** A continuous timeout must not publish its state, effects, or a retried event. */
    @Test fun continuousTimeoutPreservesDurableStateAndNeverReplays():Unit=runBlocking {
        fixture {store,engine,initial ->
            val source="if(input.event.type==='frame'){input.state.count=99;while(true){}}return {state:input.state,frames:['left']};"
            val spec=definition(source);val tool=initial.copy(spec=spec.json);store.saveTool(tool,true)
            engine.openArtifact(tool)
            val failed=runCatching {engine.artifactEvent(tool.id,1,GraphicsContract.event("frame",16,surfaceId="left"))}
            assertTrue(failed.isFailure)
            assertTrue(checkNotNull(failed.exceptionOrNull()?.message).contains("deadline"))
            assertEquals(tool,store.tool(tool.id));assertTrue(store.effects(tool.id).isEmpty())
            assertTrue(runCatching {engine.artifactEvent(tool.id,1,GraphicsContract.event("frame",16,surfaceId="left"))}.isFailure)
            assertEquals(tool,store.tool(tool.id));assertTrue(store.effects(tool.id).isEmpty())
        }
    }
    @Test fun unavailablePinReportsStatusWithoutLauncherOrDataChanges():Unit=runBlocking {
        fixture {store,_,initial ->
            val old=initial.copy(spec=JSONObject(initial.spec).put("formatVersion",2).toString())
            store.writableDatabase.execSQL("UPDATE tools SET spec=? WHERE id=?",arrayOf(old.spec,old.id))
            val preferences=context.getSharedPreferences("alpha-pin-requests",android.content.Context.MODE_PRIVATE)
            val before=preferences.all
            for(widget in listOf(false,true)) {
                val result=com.alpha.assistant.surfaces.SurfaceEntry.pin(context,old,widget,com.alpha.assistant.surfaces.WidgetPreferences())
                assertEquals(context.getString(com.alpha.assistant.R.string.widget_pin_unavailable),result.second)
                assertEquals(old,store.tool(old.id));assertEquals(before,preferences.all)
            }
        }
    }
}

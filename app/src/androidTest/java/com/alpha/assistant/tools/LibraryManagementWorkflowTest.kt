package com.alpha.assistant.tools

import android.os.Bundle
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.automations.*
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.surfaces.*
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Failure cases: wrong search, stale accessibility trees, lost state/history, stale writes, pending effects, active rules, stale widget actions, recovery auto-enables rules. */
class LibraryManagementWorkflowTest {
    private val diagnostics = UiFailureDiagnostics("library")
    private val robot = ShellRobot("library")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as android.app.Application
    private val offline = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { error("Library must not call the cloud") }
    }
    private fun counter(id: String, title: String) = ToolSpec.parse("""{"formatVersion":3,"title":"$title","description":"Fixture","capabilities":["notification.post"],"stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"column","children":[{"type":"text","text":"Счёт","bind":"count"},{"type":"button","text":"Добавить","action":"add"}]},"source":"if(input.event.type==='action') input.state.count++; return {state:input.state};"}""").let {
        ToolRecord(id,1,1,it.json,"""{"count":7}""",it.ui.toString(),null,true,createdAt=1000)
    }
    private fun rule(tools: ToolRecord) = JSONObject("""{"name":"Зависимый сценарий","trigger":{"type":"manual"},"conditions":{},"actions":[{"id":"notice","capability":"notification.post","args":{"title":"Fixture","text":"Fixture"}},{"id":"set","capability":"tool.state.set","args":{"toolId":"${tools.id}","revision":${tools.revision},"field":"count","value":9}}]}""")

    @Test fun archiveRestartDependencyGuardsAndRecoveryPreserveData() = runBlocking {
        val name="library-${UUID.randomUUID()}.db"
        val rulesName="$name-automations.db"
        val widget=1700000000
        try { ToolStore(app,name).use { tools -> AutomationStore(app,rulesName).use { rules ->
            val initial=counter("counter","Мой счётчик")
            tools.commitTool(initial,null,true)
            val pending=CapabilityRequest("notice","notification.post",JSONObject().put("title","Fixture").put("text","Fixture"))
            val live=initial.copy(revision=2,stateVersion=2)
            tools.commitTool(live,initial,true,effects=listOf(pending))
            val draft=ToolDraft("draft",live.id,live.revision,live.stateVersion,"Правка",null,null,null,null)
            tools.saveDraft(draft)
            tools.seedLegacyDeviceGrant(live.id,"notification.post",true)
            val dependent=rules.save(null,rule(live)); rules.approve(dependent.id,dependent.revision)
            ToolWidgets.bind(app,widget,live.id,WidgetPreferences())
            val stale=ToolWidgets.actionIntent(app,widget,live,"add",null)
            AutomationRuntime.archiveTool(tools,rules,live)
            val archived=checkNotNull(tools.tool(live.id))
            assertTrue(archived.archived); assertEquals(live.state,archived.state)
            assertEquals(live.createdAt,archived.createdAt); assertEquals(2,tools.revisions(live.id).size)
            assertEquals(draft,tools.draft(draft.id)); assertFalse(rules.rule(dependent.id)!!.enabled)
            assertEquals("cancelled",tools.effects(live.id).single().result.status)
            assertNull(tools.claimEffect(tools.effects(live.id).single().key))
            var nativeEffects=0
            assertEquals("cancelled",CapabilityBroker(app,tools).execute(tools.effects(live.id).single().key) {nativeEffects++;CapabilityResult("succeeded","Fixture")}.status)
            assertEquals(0,nativeEffects)
            assertTrue(runCatching {tools.commitTool(live.copy(stateVersion=3),live,false)}.isFailure)
            val engine=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
            assertFalse(ToolWidgets.perform(app,stale,tools,engine,false))
            assertTrue(runCatching {engine.createDraft("Правка",live.id)}.isFailure)
            assertTrue(runCatching {engine.accept(draft.id)}.isFailure)
            assertTrue(runCatching {engine.interact(archived.id,archived.revision,archived.stateVersion,"add",null)}.isFailure)
            val archivedWidget = ToolGlanceWidget.preview(app,archived,WidgetPreferences(),DpSize(180.dp,180.dp))
            instrumentation.runOnMainSync {
                val labels = archivedWidget.apply(app,null).descendants().filterIsInstance<TextView>().map { it.text.toString() }.toList()
                assertTrue(labels.contains(app.getString(R.string.widget_archived)))
                assertFalse(labels.any { it.startsWith("Счёт:") || it == "Добавить" })
            }
            // Even a stale approval cannot authorize another effect while any target is archived.
            rules.approve(dependent.id,dependent.revision)
            var effects=0
            val executor=AutomationExecutor(rules,{null},{ owner,_ -> AutomationRuntime.dependencyFailure(tools,owner) }) {_,_,_->effects++; CapabilityResult("succeeded","Fixture")}
            assertEquals("revoked",executor.run(dependent.id,dependent.revision,AutomationEvent("manual","archived"))?.status)
            assertEquals(0,effects)
            ToolStore(app,name).use { reopened ->
                assertEquals(archived,reopened.tool(live.id)); assertEquals(draft,reopened.draft(draft.id))
                reopened.setArchived(archived,false)
            }
            val recovered=checkNotNull(tools.tool(live.id))
            assertFalse(recovered.archived); assertEquals(live.state,recovered.state)
            assertEquals(live.createdAt,recovered.createdAt); assertEquals(2,tools.revisions(live.id).size)
            assertFalse(rules.rule(dependent.id)!!.enabled); assertNull(AutomationRuntime.dependencyFailure(tools,rules.rule(dependent.id)!!))
            assertFalse(ToolWidgets.perform(app,stale,tools,engine,false))
            assertTrue(ToolWidgets.perform(app,ToolWidgets.actionIntent(app,widget,recovered,"add",null),tools,engine,false))
            assertEquals(8,JSONObject(tools.tool(live.id)!!.state).getInt("count"))
            assertEquals(live.id,ToolWidgets.toolId(app,widget))
        } } } finally {ToolWidgets.remove(app,widget);app.deleteDatabase(name);app.deleteDatabase(rulesName)}
    }

    @Test fun approvalValidatedBeforeArchiveCannotResumeAfterRecovery() = runBlocking {
        val name="library-approval-${UUID.randomUUID()}.db"
        val rulesName="$name-automations.db"
        try { ToolStore(app,name).use { tools -> AutomationStore(app,rulesName).use { rules ->
            val target=counter("approval-target","Счётчик")
            tools.commitTool(target,null,true)
            val dependent=rules.save(null,rule(target))
            rules.approve(dependent.id,dependent.revision); rules.pause(dependent.id)
            val before=checkNotNull(rules.rule(dependent.id))
            val pending=AutomationRuntime.prepareApproval(rules,dependent.id,dependent.revision)
            // Reproduce validation -> archive -> recover -> resume approval, without timing or a device service.
            assertNull(AutomationRuntime.dependencyFailure(tools,pending.rule))
            AutomationRuntime.archiveTool(tools,rules,target)
            tools.setArchived(checkNotNull(tools.tool(target.id)),false)
            assertNull(AutomationRuntime.dependencyFailure(tools,pending.rule))
            assertTrue(runCatching { AutomationRuntime.approve(rules,pending) { current ->
                check(AutomationRuntime.dependencyFailure(tools,current) == null)
            } }.isFailure)
            val paused=checkNotNull(rules.rule(dependent.id))
            assertFalse(paused.enabled); assertEquals(before.revision,paused.revision)
            assertEquals(before.approvedRevision,paused.approvedRevision)
            assertTrue(paused.attention.contains("архив"))
            // A new explicit approval after recovery retains the exact requested revision.
            val fresh=AutomationRuntime.prepareApproval(rules,dependent.id,dependent.revision)
            AutomationRuntime.approve(rules,fresh) { current -> check(AutomationRuntime.dependencyFailure(tools,current) == null) }
            assertTrue(rules.active(dependent.id,dependent.revision))
            val stale=AutomationRuntime.prepareApproval(rules,dependent.id,dependent.revision)
            val changed=rules.save(dependent.id,rule(target).put("name","Правка"),dependent.revision)
            assertTrue(runCatching { AutomationRuntime.approve(rules,stale) {} }.isFailure)
            assertFalse(rules.active(changed.id,changed.revision))
        } } } finally {app.deleteDatabase(name);app.deleteDatabase(rulesName)}
    }

    @Test fun confirmedDeletionRevokesOnlyTheCapturedOwnerScope(): Unit = runBlocking {
        val name="library-delete-${UUID.randomUUID()}.db"
        val rulesName="$name-automations.db"
        val widget=1700000001
        val otherWidget=1700000002
        try { ToolStore(app,name).use { tools -> AutomationStore(app,rulesName).use { rules ->
            val target=counter("delete-${UUID.randomUUID()}","Счётчик")
            val sibling=counter("keep-${UUID.randomUUID()}","Покупки")
            val effect=CapabilityRequest("notice","notification.post",JSONObject("""{"title":"Fixture","text":"Fixture"}"""))
            tools.commitTool(target,null,true,effects=listOf(effect))
            tools.commitTool(sibling,null,true)
            val key=tools.effects(target.id).single().key
            tools.seedLegacyDeviceGrant(target.id,"notification.post",true)
            val draft=ToolDraft("pending",target.id,1,1,"Change",null,null,null,null)
            tools.saveDraft(draft)
            tools.setDesktopPlacement(target,true)
            val expected=tools.tool(target.id)!!
            val own=CapabilityResource("a".repeat(64),target.id,"document","content://fixture/shared","text/plain",1,true,true,ToolStore.processSession,Long.MAX_VALUE)
            val shared=own.copy(id="b".repeat(64),owner=sibling.id,ownsGrant=false)
            tools.insertResource(own);tools.insertResource(shared)
            ToolWidgets.bind(app,widget,target.id,WidgetPreferences())
            ToolWidgets.bind(app,otherWidget,sibling.id,WidgetPreferences())
            val staleWidget=ToolWidgets.actionIntent(app,widget,expected,"add",null)
            val dependent=rules.save(null,rule(target))
            rules.approve(dependent.id,dependent.revision)
            val history=rules.begin(dependent,AutomationEvent("manual","fixture"),1000,false)!!
            rules.finish(history,"succeeded","Fixture history")
            val approval=AutomationRuntime.prepareApproval(rules,dependent.id,dependent.revision)
            val confirmation=AutomationRuntime.deletionSnapshot(tools,rules,expected)
            assertEquals(expected,tools.tool(target.id));assertTrue(rules.rule(dependent.id)!!.enabled)
            assertEquals(target.id,ToolWidgets.toolId(app,widget));assertEquals(own,tools.resource(own.id))

            val later=draft.copy(id="later")
            tools.saveDraft(later)
            assertTrue(runCatching {AutomationRuntime.deleteTool(tools,rules,confirmation)}.isFailure)
            assertEquals(expected,tools.tool(target.id));assertTrue(rules.rule(dependent.id)!!.enabled)
            tools.deleteDraft(later.id)
            val confirmed=AutomationRuntime.deletionSnapshot(tools,rules,expected)
            assertTrue(AutomationRuntime.deleteTool(tools,rules,confirmed))
            assertNull(tools.tool(target.id));assertEquals(sibling,tools.tool(sibling.id))
            assertTrue(tools.revisions(target.id).isEmpty());assertNull(tools.draft(draft.id))
            assertTrue(tools.effects(target.id).isEmpty());assertNull(tools.legacyDeviceGrant(target.id,"notification.post"))
            assertNull(tools.resource(own.id));assertEquals(shared.copy(ownsGrant=true),tools.resource(shared.id))
            assertNull(ToolWidgets.toolId(app,widget));assertEquals(sibling.id,ToolWidgets.toolId(app,otherWidget))
            assertTrue(ToolWidgets.deleted(app,widget))
            val stopped=rules.rule(dependent.id)!!
            assertFalse(stopped.enabled);assertEquals(dependent.definition.json,stopped.definition.json)
            assertTrue(stopped.attention.isNotBlank());assertTrue(rules.history(dependent.id).any {it.message=="Fixture history"})
            assertEquals(stopped.attention,rules.history(dependent.id).first().message)
            assertTrue(runCatching {AutomationRuntime.approve(rules,approval) {}}.isFailure)
            assertFalse(tools.finishUserEffect(key,CapabilityResult("succeeded","Stale callback")))
            assertTrue(runCatching {tools.commitTool(expected.copy(stateVersion=2),expected,false)}.isFailure)
            assertTrue(runCatching {tools.commitTool(target,null,true)}.isFailure)
            val engine=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
            assertFalse(ToolWidgets.perform(app,staleWidget,tools,engine,false))
        } } } finally {
            ToolWidgets.remove(app,widget);ToolWidgets.remove(app,otherWidget);app.deleteDatabase(name);app.deleteDatabase(rulesName);app.deleteDatabase("$name-medicine.db")
        }
    }

    @Test fun interruptedResourceCleanupRetriesAfterReopen(): Unit = runBlocking {
        val name="library-cleanup-${UUID.randomUUID()}.db"
        val rulesName="$name-automations.db"
        val capture=java.io.File(app.filesDir,"capture-${UUID.randomUUID()}.jpg")
        val blocker=java.io.File(capture,"fixture")
        try {
            check(capture.mkdir());blocker.writeText("Prevent file deletion on the first attempt")
            val owner=counter("cleanup-${UUID.randomUUID()}","Счётчик")
            val resource=CapabilityResource("c".repeat(64),owner.id,"cameraPhoto",capture.canonicalPath,"image/jpeg",1,true,false,ToolStore.processSession,Long.MAX_VALUE)
            ToolStore(app,name).use { tools -> AutomationStore(app,rulesName).use { rules ->
                tools.commitTool(owner,null,true);tools.insertResource(resource)
                val confirmation=AutomationRuntime.deletionSnapshot(tools,rules,owner)
                assertFalse(AutomationRuntime.deleteTool(tools,rules,confirmation))
                assertNull(tools.tool(owner.id));assertTrue(tools.acquisitions().any {it.id==resource.id})
            } }
            check(blocker.delete())
            ToolStore(app,name).use { tools ->
                assertTrue(AutomationRuntime.recoverToolDeletions(tools))
                assertTrue(AutomationRuntime.recoverToolDeletions(tools))
                assertFalse(capture.exists());assertTrue(tools.acquisitions().isEmpty())
                assertNull(tools.tool(owner.id))
            }
        } finally {capture.deleteRecursively();app.deleteDatabase(name);app.deleteDatabase(rulesName);app.deleteDatabase("$name-medicine.db")}
    }

    @Test fun deletionKeepsOwnedEventInAndroidCalendar(): Unit = runBlocking {
        val name="library-calendar-delete-${UUID.randomUUID()}.db"
        val rulesName="$name-automations.db"
        try {
            SyntheticOwnedCalendar(app).use { fixture ->
                ToolStore(app,name).use { tools -> AutomationStore(app,rulesName).use { rules ->
                    val seed=counter("calendar-${UUID.randomUUID()}","Calendar")
                    val spec=ToolSpec.parse(JSONObject(seed.spec).put("capabilities",org.json.JSONArray(OwnedCalendarContract.ids.toList())).toString())
                    val tool=seed.copy(spec=spec.json)
                    tools.commitTool(tool,null,true)
                    val broker=CapabilityBroker(app,tools)
                    val provider=AndroidOwnedCalendarProvider(app)
                    val native=OwnedCalendarCapabilities(app,tools,provider,unlocked={true})
                    suspend fun execute(capability: String,args: JSONObject): CapabilityResult {
                        val old=tools.tool(tool.id)!!
                        val request=CapabilityRequest("op${old.stateVersion}",capability,args)
                        tools.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(request))
                        return broker.executeOwned(tools.effects(tool.id).last().key) { effect ->
                            if(capability=="calendar.select") native.select(effect,fixture.native(),{true})
                            else native.execute(effect,{true}) {}
                        }
                    }
                    val selected=execute("calendar.select",JSONObject().put("version",1))
                    assertEquals(selected.message,"succeeded",selected.status)
                    val args=JSONObject().put("version",1).put("calendarRef",selected.data.getString("calendarRef")).put("eventKey","keep")
                        .put("title","Keep after mini-app deletion").put("start",1893456000000L).put("end",1893459600000L)
                        .put("timeZone","UTC").put("deviceTimeZone",java.time.ZoneId.systemDefault().id).put("location","").put("reminderMinutes",10)
                    val created=execute("calendar.createOwned",args)
                    assertEquals(created.message,"succeeded",created.status)
                    val ledger=OwnedCalendarLedger(tools)
                    val owned=ledger.event(tool.id,created.data.getString("eventRef"))!!
                    assertEquals(owned.providerId,provider.find(fixture.id,app.packageName,owned.marker).single().id)
                    val confirmation=AutomationRuntime.deletionSnapshot(tools,rules,tools.tool(tool.id)!!)
                    assertTrue(AutomationRuntime.deleteTool(tools,rules,confirmation))
                    assertNull(ledger.event(tool.id,owned.eventRef))
                    assertEquals(owned.providerId,provider.find(fixture.id,app.packageName,owned.marker).single().id)
                } }
            }
        } finally {app.deleteDatabase(name);app.deleteDatabase(rulesName);app.deleteDatabase("$name-medicine.db")}
    }

    @Test fun sharedHostsReadOneStateAndOnlyOneDrivesFrames(): Unit = runBlocking {
        val name="library-shared-hosts-${UUID.randomUUID()}.db"
        try { ToolStore(app,name).use { tools ->
            val seed=counter("shared","Shared hosts")
            val spec=ToolSpec.parse(JSONObject(seed.spec)
                .put("surfaces",org.json.JSONArray("""[{"id":"main","width":320,"height":200}]"""))
                .put("source","if(input.event.type==='frame') input.state.count++; return {state:input.state,frames:['main']};").toString())
            val tool=seed.copy(spec=spec.json)
            tools.commitTool(tool,null,true)
            val first=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
            val second=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
            val frame=GraphicsContract.event("frame",16,surfaceId="main")
            val pause=GraphicsContract.event("pause",surfaceId="main")
            try {
                val card=first.openArtifact(tool)
                val full=second.openArtifact(tool)
                assertFalse(first.artifactSnapshot(tool.id,tool.revision,card.sessionGeneration).frameOwner)
                assertTrue(full.frameOwner)
                assertEquals(tool.state,first.artifactEvent(tool.id,tool.revision,frame,card.sessionGeneration).tool.state)
                val tick=second.artifactEvent(tool.id,tool.revision,frame,full.sessionGeneration)
                assertEquals(8,JSONObject(tick.tool.state).getInt("count"))
                assertEquals(tick.tool,first.artifactSnapshot(tool.id,tool.revision,card.sessionGeneration).tool)
                assertEquals("paused",first.artifactEvent(tool.id,tool.revision,pause,card.sessionGeneration).playback)
                assertEquals("paused",second.artifactSnapshot(tool.id,tool.revision,full.sessionGeneration).playback)
                second.closeArtifact(tool.id,tool.revision,full.sessionGeneration)
                assertTrue(first.artifactSnapshot(tool.id,tool.revision,card.sessionGeneration).frameOwner)
                first.closeArtifact(tool.id,tool.revision,card.sessionGeneration)
                assertEquals(8,JSONObject(tools.tool(tool.id)!!.state).getInt("count"))
            } finally {first.closeArtifacts();second.closeArtifacts()}
        } } finally {app.deleteDatabase(name)}
    }

    @Test fun activeCanvasKeepsLatestStateAcrossPlacementAndLastChatUpdates(): Unit = runBlocking {
        val name="library-live-placement-${UUID.randomUUID()}.db"
        try { ToolStore(app,name).use { tools ->
            val seed=counter("live-placement","Live placement")
            val spec=ToolSpec.parse(JSONObject(seed.spec)
                .put("surfaces",org.json.JSONArray("""[{"id":"main","width":320,"height":200}]"""))
                .put("source","if(input.event.type==='frame') input.state.count++; return {state:input.state,frames:['main']};").toString())
            val tool=seed.copy(spec=spec.json,conversationId="original",lastConversationId="original")
            tools.commitTool(tool,null,true)
            val engine=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
            val frame=GraphicsContract.event("frame",16,surfaceId="main")
            try {
                val host=engine.openArtifact(tool)
                engine.artifactEvent(tool.id,1,frame,host.sessionGeneration)
                val placed=engine.setDesktopPlacement(tool,true)
                assertTrue(placed.onDesktop);assertEquals(8,JSONObject(placed.state).getInt("count"))
                assertTrue(placed.stateVersion>tool.stateVersion)

                engine.artifactEvent(tool.id,1,frame,host.sessionGeneration)
                val saved=engine.flushArtifact(tool.id,1,host.sessionGeneration).tool
                assertTrue(saved.stateVersion>placed.stateVersion)
                assertTrue(runCatching {engine.openArtifact(tool)}.isFailure)
                val moved=engine.recordLastConversation(tool,"latest")
                assertEquals("original",moved.conversationId);assertEquals("latest",moved.lastConversationId)
                assertTrue(moved.onDesktop);assertEquals(saved.state,moved.state)
                assertEquals(9,JSONObject(moved.state).getInt("count"))
                assertEquals(moved,tools.tool(tool.id))

                val revised=engine.rollback(tool.id,1,moved.stateVersion,1)
                assertEquals(2,revised.revision)
                assertTrue(runCatching {engine.setDesktopPlacement(tool,false)}.isFailure)
                assertTrue(runCatching {engine.recordLastConversation(tool,"stale")}.isFailure)
                assertEquals(revised,tools.tool(tool.id))
            } finally {engine.closeArtifacts()}
        } } finally {app.deleteDatabase(name);app.deleteDatabase("$name-automations.db")}
    }

    @Test fun differentlySizedHostsMapInputAndRestoreThePromotedViewport(): Unit = runBlocking {
        val name="library-viewport-hosts-${UUID.randomUUID()}.db"
        try { ToolStore(app,name).use { tools ->
            val seed=counter("viewport","Shared viewport")
            val spec=ToolSpec.parse(JSONObject(seed.spec)
                .put("surfaces",org.json.JSONArray("""[{"id":"main","width":320,"height":200,"sizing":"viewport"}]"""))
                .put("source","""
                    const event=input.event;
                    if(event.type==='viewport') input.state.count=event.width;
                    if(event.type==='pointer') input.state.count=event.x+event.y;
                    return {state:input.state};
                """.trimIndent()).toString())
            val tool=seed.copy(spec=spec.json)
            tools.commitTool(tool,null,true)
            val first=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
            val second=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
            fun viewport(width: Int,height: Int)=GraphicsContract.viewport(ToolSurface("main",width,height,sizing="viewport"))
            fun pointer(x: Double,y: Double)=GraphicsContract.event("pointer",x=x,y=y,phase="down")
            try {
                val wide=first.openArtifact(tool)
                first.artifactEvent(tool.id,1,viewport(600,600),wide.sessionGeneration)
                val narrow=second.openArtifact(tools.tool(tool.id)!!)
                second.artifactEvent(tool.id,1,viewport(300,200),narrow.sessionGeneration)
                val snapshot=first.artifactSnapshot(tool.id,1,wide.sessionGeneration)
                assertEquals(300,snapshot.viewport!!.width);assertEquals(200,snapshot.viewport.height)
                val inputFromWide=first.artifactEvent(tool.id,1,pointer(500.0,450.0),wide.sessionGeneration)
                assertEquals(400.0,JSONObject(inputFromWide.tool.state).getDouble("count"),0.0001)
                val inputFromNarrow=second.artifactEvent(tool.id,1,pointer(200.0,100.0),narrow.sessionGeneration)
                assertEquals(300.0,JSONObject(inputFromNarrow.tool.state).getDouble("count"),0.0001)
                second.closeArtifact(tool.id,1,narrow.sessionGeneration)
                val promoted=first.artifactSnapshot(tool.id,1,wide.sessionGeneration)
                assertTrue(promoted.frameOwner);assertEquals(600,promoted.viewport!!.width);assertEquals(600,promoted.viewport.height)
                assertEquals(600,JSONObject(promoted.tool.state).getInt("count"))
                val inputAfterHandoff=first.artifactEvent(tool.id,1,pointer(500.0,450.0),wide.sessionGeneration)
                assertEquals(950.0,JSONObject(inputAfterHandoff.tool.state).getDouble("count"),0.0001)
            } finally {first.closeArtifacts();second.closeArtifacts()}
        } } finally {app.deleteDatabase(name)}
    }

    @Test fun failedOuterCommitPreservesToolAndKeepsAutomationPausedWithTruthfulReason(): Unit = runBlocking {
        for(deletion in listOf(true,false)) {
            val name="library-commit-failure-${UUID.randomUUID()}.db"
            val rulesName="$name-automations.db"
            try { ToolStore(app,name).use { tools -> AutomationStore(app,rulesName).use { rules ->
                val target=counter("target","Counter")
                tools.commitTool(target,null,true)
                tools.seedLegacyDeviceGrant(target.id,"notification.post",true)
                val dependent=rules.save(null,rule(target))
                rules.approve(dependent.id,dependent.revision)
                val approval=AutomationRuntime.prepareApproval(rules,dependent.id,dependent.revision)
                val db=tools.writableDatabase
                db.setForeignKeyConstraintsEnabled(true)
                db.execSQL("CREATE TABLE injected_parent(id INTEGER PRIMARY KEY)")
                db.execSQL("CREATE TABLE injected_child(id INTEGER REFERENCES injected_parent(id) DEFERRABLE INITIALLY DEFERRED)")
                val mutation=if(deletion) "DELETE ON tools" else "INSERT ON revisions"
                db.execSQL("CREATE TRIGGER injected_failure AFTER $mutation BEGIN INSERT INTO injected_child VALUES(1); END")
                val failure=if(deletion) {
                    val confirmation=AutomationRuntime.deletionSnapshot(tools,rules,target)
                    runCatching {AutomationRuntime.deleteTool(tools,rules,confirmation)}.exceptionOrNull()
                } else {
                    val engine=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
                    runCatching {engine.rollback(target.id,1,1,1)}.exceptionOrNull()
                }
                assertTrue(failure.toString(),failure is android.database.sqlite.SQLiteConstraintException)
                assertEquals(target,tools.tool(target.id))
                ToolStore(app,name).use { reopened ->
                    assertEquals(target,reopened.tool(target.id))
                    assertEquals(listOf(1),reopened.revisions(target.id).map {it.revision})
                    assertEquals(true,reopened.legacyDeviceGrant(target.id,"notification.post"))
                    assertFalse(reopened.isDeleted(target.id))
                }
                val paused=rules.rule(dependent.id)!!
                assertFalse(paused.enabled)
                assertTrue(paused.attention,paused.attention.contains("остановлен перед операцией"))
                assertEquals(paused.attention,rules.history(dependent.id).single().message)
                assertTrue(runCatching {AutomationRuntime.approve(rules,approval) {}}.isFailure)
            } } } finally {app.deleteDatabase(name);app.deleteDatabase(rulesName);app.deleteDatabase("$name-medicine.db")}
        }
    }

    @Test fun searchOpenArchiveCancelConfirmAndRestoreThroughNativeUi() {
        val name="library-ui-${UUID.randomUUID()}.db"
        val shell=ShellStore(app,name)
        val toolName="$name-tools.db"
        val rulesName="$toolName-automations.db"
        val keys=SecureKeyStore(app,name,name)
        lateinit var model: ShellViewModel
        try {
            ToolStore(app,toolName).use { tools ->
                val first=counter("first","Мой счётчик");tools.commitTool(first,null,true)
                tools.commitTool(counter("second","Покупки"),null,true)
                AutomationStore(app,rulesName).use { rules -> val dependency=rules.save(null,rule(first));rules.approve(dependency.id,dependency.revision) }
            }
            CloudTestActivity.factory={ application -> ShellViewModel(application,offline,shell,keys).also {
                model=it
                diagnostics.state={"loading=${model.loading} busy=${model.tools.busy} selected=${model.tools.selectedId} tools=${model.tools.tools.map {item -> item.id to item.archived}}"}
            } }
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                waitUntil {!model.loading && !model.tools.busy}
                robot.openDrawer(scenario);click(app.getString(R.string.mini_apps));robot.awaitDrawerClosed(scenario)
                edit("Поиск мини-приложений","  СЧЁТ  ")
                waitFor("Мой счётчик"); waitUntil { find(diagnostics.freshRoot(),"Покупки") == null }
                click("Мой счётчик"); waitFor("Счёт: 7")
                click("Действия мини-приложения");click("Архивировать");waitFor("Зависимый сценарий");click("Отмена")
                assertFalse(model.tools.tools.first {it.id=="first"}.archived)
                click("Действия мини-приложения");click("Архивировать");click("Архивировать")
                waitFor("Данные и версии сохранены в архиве. Восстановите мини-приложение, чтобы включить управление.")
                scenario.recreate(); waitFor("Данные и версии сохранены в архиве. Восстановите мини-приложение, чтобы включить управление.")
            }
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                waitUntil {!model.loading && !model.tools.busy}
                robot.openDrawer(scenario);click(app.getString(R.string.mini_apps));robot.awaitDrawerClosed(scenario)
                click(app.getString(R.string.tool_catalog_options));click(app.getString(R.string.tool_archive))
                waitFor("Мой счётчик");click("Мой счётчик")
                click("Восстановить из архива");waitFor("Счёт: 7")
                waitUntil {!model.tools.busy}
                AutomationStore(app,rulesName).use {assertFalse(it.rules().single().enabled)}
                click(app.getString(R.string.tool_library_back));edit("Поиск мини-приложений","не найдено");waitFor("Ничего не найдено");diagnostics.capture("empty-search-result")
            }
        } finally {
            CloudTestActivity.factory=null;shell.close();keys.clear()
            app.deleteDatabase(name);app.deleteDatabase(toolName);app.deleteDatabase(rulesName);app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(name)}
        }
    }
    @Test fun liveCardVersionsPlacementAndDeletionRequireCurrentScope() {
        val name="library-live-ui-${UUID.randomUUID()}.db"
        val shell=ShellStore(app,name)
        val toolName="$name-tools.db"
        val rulesName="$toolName-automations.db"
        val keys=SecureKeyStore(app,name,name)
        val widget=1700000153
        lateinit var model: ShellViewModel
        try {
            ToolStore(app,toolName).use { tools ->
                val old=counter("live-delete","Версионный счётчик")
                val invalid=old.copy(spec=JSONObject(old.spec).put("source","input.state.count++; return {state:input.state};").toString())
                tools.commitTool(invalid,null,true)
                val current=old.copy(revision=2,stateVersion=2)
                AutomationStore(app,rulesName).use { rules ->
                    val other=rules.save(null,rule(current).put("name","Другой сценарий"))
                    val stopped=rules.save(null,rule(current))
                    assertNotEquals(other.id,stopped.id)
                    tools.commitTool(current,invalid,true,revisionLabel="Новая кнопка",
                        stoppedAutomations=listOf(StoppedAutomation(stopped.id,stopped.definition.name)))
                }
                shell.saveConversation(Conversation("chat","Обсуждение счётчика",1,1,messages=listOf(
                    Message("card","assistant","Готово.",1,"complete",toolId=current.id,toolRevision=1))))
                shell.saveSetting("selected","chat")
            }
            CloudTestActivity.factory={ ShellViewModel(it,offline,shell,keys).also { model=it } }
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                waitUntil {!model.loading && !model.tools.busy}
                waitFor("v2"); waitFor("Счёт: 7")
                click("Действия мини-приложения Версионный счётчик");click("Версии")
                waitFor("v2 · Новая кнопка");waitFor(app.getString(R.string.tool_generation_preview_state))
                assertNull(find(diagnostics.freshRoot(),"Вернуть v1"))
                click("Закрыть")
                click("Открыть сценарий «Зависимый сценарий»")
                waitFor("Зависимый сценарий"); waitFor("Проверить и включить")
                assertNull(find(diagnostics.freshRoot(),"Другой сценарий"))
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                click("Действия мини-приложения Версионный счётчик");click(app.getString(R.string.tool_desktop_add))
                waitUntil {model.tools.tools.single().onDesktop && !model.tools.busy}
                robot.openDrawer(scenario);click(app.getString(R.string.mini_apps));robot.awaitDrawerClosed(scenario)
                waitFor("Версионный счётчик")
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                click("Действия мини-приложения Версионный счётчик");click("Удалить")
                waitFor("Удалить «Версионный счётчик»?");click("Отмена")
                assertEquals(1,model.tools.tools.size)
                click("Действия мини-приложения Версионный счётчик");click("Удалить")
                waitFor(app.getString(R.string.tool_delete_title, "Версионный счётчик"))
                waitFor(app.getString(R.string.tool_delete_widgets, 0))
                ToolWidgets.bind(app,widget,"live-delete",WidgetPreferences())
                click(app.getString(R.string.delete))
                waitFor(app.getString(R.string.tool_delete_changed))
                waitFor(app.getString(R.string.tool_delete_widgets, 1))
                ToolStore(app,toolName).use { assertNotNull(it.tool("live-delete")) }
                click("Удалить")
                waitFor("Мини-приложение удалено")
                assertNull(find(diagnostics.freshRoot(),"Открыть"))
                assertNull(find(diagnostics.freshRoot(),"Добавить"))
                assertTrue(ToolWidgets.deleted(app,widget))
                val deletedWidget = runBlocking {
                    ToolGlanceWidget.preview(app,null,WidgetPreferences(),DpSize(180.dp,180.dp),deleted = ToolWidgets.deleted(app,widget))
                }
                instrumentation.runOnMainSync {
                    val labels = deletedWidget.apply(app,null).descendants().filterIsInstance<TextView>().map { it.text.toString() }.toList()
                    assertTrue(labels.contains(app.getString(R.string.widget_deleted)))
                }
                diagnostics.capture("deleted-chat-reference")
            }
        } finally {
            CloudTestActivity.factory=null;shell.close();keys.clear();ToolWidgets.remove(app,widget)
            app.deleteDatabase(name);app.deleteDatabase(toolName);app.deleteDatabase(rulesName);app.deleteDatabase("$toolName-medicine.db")
            app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(name)}
        }
    }

    @Test fun emptyLibraryAndUnavailableSavedAppAtDoubleFontSize() {
        val name="library-empty-ui-${UUID.randomUUID()}.db"
        val shell=ShellStore(app,name)
        val toolName="$name-tools.db"
        val keys=SecureKeyStore(app,name,name)
        val automation=instrumentation.uiAutomation
        fun command(value: String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(value))
            .bufferedReader().use {it.readText().trim()}
        val font=command("settings get system font_scale")
        lateinit var model: ShellViewModel
        try {
            CloudTestActivity.factory={ ShellViewModel(it,offline,shell,keys).also { model=it } }
            command("settings put system font_scale 2.0")
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                waitUntil {!model.loading && !model.tools.busy}
                robot.openDrawer(scenario);click(app.getString(R.string.mini_apps));robot.awaitDrawerClosed(scenario)
                waitFor(app.getString(R.string.tool_catalog_empty))
            }
            val saved=counter("unavailable","Недоступный счётчик")
            ToolStore(app,toolName).use { tools ->
                tools.saveTool(saved,true)
                val values=android.content.ContentValues().apply {
                    put("spec",JSONObject(saved.spec).put("formatVersion",1000).toString())
                }
                assertEquals(1,tools.writableDatabase.update("tools",values,"id=?",arrayOf(saved.id)))
            }
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                waitUntil {!model.loading && !model.tools.busy}
                robot.openDrawer(scenario);click(app.getString(R.string.mini_apps));robot.awaitDrawerClosed(scenario)
                waitFor("Недоступный счётчик");waitFor("Формат мини-приложения недоступен")
                click("Недоступный счётчик")
                waitFor(app.getString(R.string.tool_closed_format_detail))
                assertNull(find(diagnostics.freshRoot(),"Изменить"))
                diagnostics.capture("unavailable-double-font")
            }
        } finally {
            if(font.toDoubleOrNull()==null) command("settings delete system font_scale")
            else command("settings put system font_scale $font")
            CloudTestActivity.factory=null;shell.close();keys.clear()
            app.deleteDatabase(name);app.deleteDatabase(toolName);app.deleteDatabase("$toolName-automations.db");app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(name)}
        }
    }

    private fun edit(label: String,value: String) {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isEditable)return node
            repeat(node.childCount) {node.getChild(it)?.let {child -> editable(child)?.let {return it}}};return null
        }
        var node: AccessibilityNodeInfo?=waitFor(label)
        node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        while(node!=null) {val input=editable(node);if(input!=null) {assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));waitUntil { input.refresh() && input.text?.toString() == value };return};node=node.parent}
        error("No input for $label")
    }
    private fun click(label: String) {
        waitUntil {
            var node: AccessibilityNodeInfo? = waitFor(label)
            while(node != null && !node.isClickable) node = node.parent
            node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.uiAutomation.waitForIdle(100,2000)
    }
    private fun scroll(node: AccessibilityNodeInfo?, action: Int): Boolean {
        if(node == null) return false
        if(node.actionList.any { it.id == action } && node.performAction(action)) return true
        repeat(node.childCount) { if(scroll(node.getChild(it),action)) return true }
        return false
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        diagnostics.target(label)
        var found: AccessibilityNodeInfo? = null
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        try { waitUntil {
            val root = diagnostics.freshRoot()
            found = find(root,label)
            if(found == null) {
                if(!scroll(root,direction)) direction = if(direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                instrumentation.uiAutomation.waitForIdle(100,2000)
            }
            found != null
        } } catch(failure: Throwable) {
            fun dump(node: AccessibilityNodeInfo?): String = if(node == null) "" else
                "[text=${node.text}, description=${node.contentDescription}, scrollable=${node.isScrollable}, actions=${node.actionList.map { it.id }}]" +
                    (0 until node.childCount).joinToString("") { dump(node.getChild(it)) }
            val cachedTree=dump(instrumentation.uiAutomation.rootInActiveWindow)
            diagnostics.capture("wait-timeout")
            throw AssertionError("Missing $label; tree=$cachedTree",failure)
        }
        return found!!
    }
    private fun find(node: AccessibilityNodeInfo?,label: String): AccessibilityNodeInfo? {if(node==null)return null;if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node;repeat(node.childCount) {find(node.getChild(it),label)?.let {return it}};return null}
    private fun waitUntil(condition: () -> Boolean) {val deadline=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<deadline) {if(condition())return;SystemClock.sleep(50)};fail("Library workflow timed out")}
}

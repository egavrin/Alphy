package com.alpha.assistant.capabilities

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Failure fixtures precede implementation. SQLite integration exposes crash boundaries that UI E2E cannot reliably interrupt.
 * Cases: opt-out, ordered three-effect batch, duplicate execution/callback/delivery, handler effects, state/revision/archive
 * changes, grants/permissions at receipt and commit, interrupted native claims, bounded delivery recovery, cancellation,
 * atomic state/receipt rollback, private refinement, oversized/forged data, resource owner/expiry, shared URI cleanup,
 * unsupported schemas without deletion. Device permission/UI flows remain acceptance work.
 */
@RunWith(AndroidJUnit4::class)
class CapabilityResultsWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) = error("Offline fixture")
    }
    private fun spec(results: Boolean = true, handler: String = "input.state.count+=input.event.entries.length;input.state.secret=input.event.entries.map(x=>x.requestId).join(',');return {state:input.state};") = ToolSpec.parse(JSONObject()
        .put("formatVersion",3).put("title","Results fixture").put("description","")
        .put("capabilities",JSONArray().put("vibration.play").put("notification.post").put("photo.pick").put("camera.capture"))
        .put("stateSchema",JSONObject("{\"count\":\"number\",\"secret\":\"string\"}"))
        .put("initialState",JSONObject("{\"count\":0,\"secret\":\"\"}"))
        .put("ui",JSONObject("{\"type\":\"button\",\"text\":\"Run\",\"action\":\"run\"}"))
        .put("source","if(input.event.type==='capabilityResults'){$handler}return {state:input.state};")
        .apply { if(results) put("capabilityResults",true) }.toString())
    private fun record(spec: ToolSpec = spec()) = ToolRecord(UUID.randomUUID().toString(),1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
    private fun request(id: String = "one", capability: String = "vibration.play") = CapabilityRequest(id,capability,if(capability == "photo.pick") JSONObject() else JSONObject("{\"milliseconds\":1}"))
    private fun engine(store: ToolStore) = ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)), permissionCheck = { true })
    private suspend fun fixture(block: suspend (ToolStore,ToolRecord) -> Unit) {
        val name = "results-${UUID.randomUUID()}.db"
        try { ToolStore(context,name).use { store -> val tool = record(); store.commitTool(tool,null,true); block(store,tool) } }
        finally { context.deleteDatabase(name) }
    }
    @Test fun batchWaitsForAllSiblingsAndCommitsOnce(): Unit = runBlocking { fixture { store, tool ->
        store.seedLegacyDeviceGrant(tool.id,"vibration.play",false)
        store.commitTool(tool.copy(stateVersion=2),tool,false,effects=listOf(request("a"),request("b"),request("c")))
        val broker = CapabilityBroker(context,store)
        var calls = 0
        store.effects(tool.id).forEachIndexed { index, effect ->
            broker.execute(effect.key) { calls++; CapabilityResult("succeeded","Native") }
            if(index < 2) { engine(store).deliverCapabilityResults(tool.id); assertEquals(0,JSONObject(store.tool(tool.id)!!.state).getInt("count")) }
        }
        engine(store).deliverCapabilityResults(tool.id)
        assertEquals(3,calls)
        assertEquals("a,b,c",JSONObject(store.tool(tool.id)!!.state).getString("secret"))
        assertEquals(3,JSONObject(store.tool(tool.id)!!.state).getInt("count"))
        store.effects(tool.id).forEach { broker.execute(it.key) { error("Repeated external action") } }
        engine(store).deliverCapabilityResults(tool.id)
        assertEquals(3,store.tool(tool.id)!!.stateVersion)
        assertEquals("committed",store.deliveries(tool.id).single().phase)
    } }
    @Test fun optOutAndResultHandlerEffectsCannotDeliver(): Unit = runBlocking {
        fixture { store, tool ->
            val bad = spec(handler="return {state:input.state,effects:[{id:'bad',capability:'notification.post',args:{title:'x',text:'y'}}]};")
            val changed = tool.copy(spec=bad.json,stateVersion=2)
            store.commitTool(changed,tool,false,effects=listOf(request()))
            CapabilityBroker(context,store).execute(store.effects(tool.id).single().key) { CapabilityResult("succeeded","Native") }
            engine(store).deliverCapabilityResults(tool.id)
            assertEquals(changed,store.tool(tool.id)); assertEquals("rejected",store.deliveries(tool.id).single().phase)
        }
        fixture { store, tool ->
            val legacy = tool.copy(spec=spec(false).json,stateVersion=2)
            store.commitTool(legacy,tool,false,effects=listOf(request()))
            CapabilityBroker(context,store).execute(store.effects(tool.id).single().key) { CapabilityResult("succeeded","Native") }
            engine(store).deliverCapabilityResults(tool.id)
            assertEquals(legacy,store.tool(tool.id)); assertTrue(store.deliveries(tool.id).isEmpty())
        }
    }
    @Test fun staleArchivedAndPermissionChangesRejectWithoutStateLoss(): Unit = runBlocking {
        for(mode in listOf("state","revision","archive","permission")) fixture { store, tool ->
            val changed = tool.copy(stateVersion=2)
            store.commitTool(changed,tool,false,effects=listOf(request()))
            CapabilityBroker(context,store).execute(store.effects(tool.id).single().key) { CapabilityResult("succeeded","Native") }
            when(mode) {
                "state" -> store.commitTool(changed.copy(stateVersion=3),changed,false)
                "revision" -> store.commitTool(changed.copy(revision=2,stateVersion=3),changed,true)
                "archive" -> store.setArchived(changed,true)
            }
            val before = store.tool(tool.id)
            ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)),permissionCheck={ mode != "permission" }).deliverCapabilityResults(tool.id)
            assertEquals(mode,before,store.tool(tool.id)); assertEquals(mode,"rejected",store.deliveries(tool.id).single().phase)
        }
    }
    @Test fun claimsAreDurableRecoveryIsBoundedAndCommitIsAtomic(): Unit = runBlocking { fixture { store, tool ->
        val changed = tool.copy(stateVersion=2)
        store.commitTool(changed,tool,false,effects=listOf(request()))
        val key = store.effects(tool.id).single().key
        assertNotNull(store.claimEffect(key)); assertEquals("running",store.effect(key)!!.phase)
        assertNull(store.claimEffect(key))
        store.recoverCapabilityWork(force = true)
        assertEquals("terminal",store.effect(key)!!.phase)
        assertEquals("unknownOutcome",store.effect(key)!!.result.status)
        val delivery = store.claimDelivery(tool.id) { true }!!
        assertEquals(1,delivery.attempts)
        assertTrue(runCatching { store.transaction { store.commitDelivery(delivery,changed.copy(stateVersion=3,state="{\"count\":1,\"secret\":\"\"}")) { true }; error("Crash before transaction commit") } }.isFailure)
        assertEquals(changed,store.tool(tool.id)); assertEquals("claimed",store.deliveries(tool.id).single().phase)
        store.recoverCapabilityWork(force = true)
        assertEquals(2,store.claimDelivery(tool.id) { true }!!.attempts)
        store.recoverCapabilityWork(force = true)
        assertNull(store.claimDelivery(tool.id) { true }); assertEquals("rejected",store.deliveries(tool.id).single().phase)
    } }
    @Test fun commitRechecksPermissionsAndCancellation(): Unit = runBlocking { fixture { store, tool ->
        val changed=tool.copy(stateVersion=2); store.commitTool(changed,tool,false,effects=listOf(request()))
        CapabilityBroker(context,store).execute(store.effects(tool.id).single().key) { CapabilityResult("succeeded","Native") }
        val claimed=store.claimDelivery(tool.id) { true }!!
        assertFalse(store.commitDelivery(claimed,changed.copy(stateVersion=3),canCommit={false}) { true })
        assertEquals(changed,store.tool(tool.id))
        assertFalse(store.commitDelivery(claimed,changed.copy(stateVersion=3)) { true })
    } }
    @Test fun permissionRevokeAfterClaimAndDuplicateCallbacksCannotCommit(): Unit = runBlocking {
        fixture { store, tool ->
            val changed=tool.copy(stateVersion=2); store.commitTool(changed,tool,false,effects=listOf(request()))
            CapabilityBroker(context,store).execute(store.effects(tool.id).single().key) { CapabilityResult("succeeded","Native") }
            val claimed=store.claimDelivery(tool.id) { true }!!
            assertFalse(store.commitDelivery(claimed,changed.copy(stateVersion=3)) { false })
            assertEquals(changed,store.tool(tool.id))
        }
        fixture { store, tool ->
            val changed=tool.copy(stateVersion=2); store.commitTool(changed,tool,false,effects=listOf(request(capability="photo.pick")))
            val key=store.effects(tool.id).single().key
            CapabilityBroker(context,store).execute(key) { CapabilityResult("awaitingUser","Picker",JSONObject().put("launched",true)) }
            assertEquals("waiting",store.effect(key)!!.phase)
            assertFalse(store.finishUserEffect(key,CapabilityResult("cancelled","Canceled")))
            assertFalse(store.finishUserEffect(key,CapabilityResult("succeeded","Late",JSONObject().put("uri","content://private"))))
            assertEquals("cancelled",store.effect(key)!!.result.status)
            engine(store).deliverCapabilityResults(tool.id)
            assertEquals(1,JSONObject(store.tool(tool.id)!!.state).getInt("count"))
        }
        fixture { store, tool ->
            store.commitTool(tool.copy(stateVersion=2),tool,false,effects=listOf(request()))
            val key=store.effects(tool.id).single().key
            assertTrue(runCatching { CapabilityBroker(context,store).execute(key) { throw kotlinx.coroutines.CancellationException("fixture") } }.isFailure)
            assertEquals("terminal",store.effect(key)!!.phase)
            assertEquals("unknownOutcome",store.effect(key)!!.result.status)
            CapabilityBroker(context,store).execute(key) { error("Repeated interrupted effect") }
        }
    }
    @Test fun interruptedResourceAcquisitionIsRecoverableWithoutDeletingProviderOriginals(): Unit = runBlocking { fixture { store, tool ->
        val manager=CapabilityResources(context,store)
        val file=java.io.File(context.filesDir,"capture-${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1,2,3)) }
        val resource=CapabilityResource("d".repeat(64),tool.id,"cameraPhoto",file.canonicalPath,"image/jpeg",3,true,false,"old-process",Long.MAX_VALUE)
        store.journalResource(resource)
        assertEquals(1,store.acquisitions().size)
        manager.recoverAcquisitions(force=true)
        assertFalse(file.exists()); assertTrue(store.acquisitions().isEmpty())
        val retained=resource.copy(id="e".repeat(64),kind="document",locator="content://fixture/original",ownsGrant=true)
        val sibling=retained.copy(id="f".repeat(64),ownsGrant=false)
        store.insertResource(sibling); store.journalResource(retained)
        manager.recoverAcquisitions(force=true)
        assertNotNull(store.resource(sibling.id)); assertTrue(store.resource(sibling.id)!!.ownsGrant)
        assertTrue(store.acquisitions().isEmpty())
    } }
    @Test fun sharedUriGrantOwnershipTransfersWithoutReleasingSibling(): Unit = runBlocking { fixture { store, tool ->
        val manager=CapabilityResources(context,store)
        val first=CapabilityResource("a".repeat(64),tool.id,"document","content://fixture/shared","text/plain",1,true,true,ToolStore.processSession,Long.MAX_VALUE)
        val second=first.copy(id="b".repeat(64),owner="another-owner",ownsGrant=false)
        store.insertResource(first); store.insertResource(second)
        manager.release(first)
        assertNull(store.resource(first.id)); assertTrue(store.resource(second.id)!!.ownsGrant)
        assertEquals(1,store.resourcesForLocator(first.locator).size)
        val temporary=first.copy(id="c".repeat(64),persistent=false,ownsGrant=false,session="previous-process")
        store.insertResource(temporary)
        assertTrue(runCatching { manager.resolve(tool.id,temporary.id) }.isFailure)
    } }
    @Test fun typedDataRejectsOversizeLocatorsProviderIdsAndForgedResources() {
        val result = CapabilityResult("succeeded","ignored",JSONObject().put("events",JSONArray().put(JSONObject().put("title","x").put("start",1).put("end",2).put("id",42))).put("limit",50))
        assertTrue(runCatching { CapabilityResultEvent.data("calendar.read",result) }.isFailure)
        assertTrue(runCatching { CapabilityResultEvent.data("photo.pick",CapabilityResult("succeeded","ignored",JSONObject().put("uri","content://private"))) }.isFailure)
        assertTrue(runCatching { CapabilityResultEvent.data("notification.post",CapabilityResult("succeeded","ignored",JSONObject().put("text","x".repeat(20000)))) }.isFailure)
    }
    @Test fun resourceHandlesRejectOtherOwnerExpiryAndOriginalFileDeletion(): Unit = runBlocking { fixture { store, tool ->
        val manager=CapabilityResources(context,store,permissionCheck={true})
        val original=java.io.File(context.filesDir,"capture-${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1,2,3)) }
        try {
            val acquired=manager.acquire(tool.id,"camera.capture",CapabilityResult("succeeded","Saved",JSONObject().put("localPhoto",original.name)))
            store.insertResource(acquired.resource!!)
            val handle=acquired.result.data.getString("resourceId")
            assertEquals("cameraPhoto",manager.resolve(tool.id,handle).kind)
            assertTrue(runCatching { manager.resolve("another",handle) }.isFailure)
            assertTrue(runCatching { manager.resolve(tool.id,"forged") }.isFailure)
            store.writableDatabase.execSQL("UPDATE capability_resources SET expires_at=0 WHERE resource_id=?",arrayOf(handle))
            assertTrue(runCatching { manager.resolve(tool.id,handle) }.isFailure)
            manager.release(acquired.resource)
            assertFalse(original.exists())
        } finally { original.delete() }
    } }
    @Test fun refinementOmitsLiveStateAndInitialPrivateValues(): Unit = runBlocking { fixture { store, tool ->
        val marker="PRIVATE-RESULT-MARKER"
        val requests=mutableListOf<CloudRequest>()
        val privateSpec=spec().let { JSONObject(it.json).put("initialState",JSONObject(it.initialState).put("secret",marker)).toString() }
        val base=tool.copy(spec=privateSpec,state=JSONObject(tool.state).put("secret",marker).toString())
        val capture=object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                requests.add(request)
                if (!request.replyWithFixturePlan(emit)) emit(CloudEvent.Text(spec().json))
            }
        }
        val result=ToolGenerationService(capture,SandboxClient(context)).generate("fixture","Change label",base,emptyList(),CloudSession(),tool.id)
        assertFalse(requests.any { marker in it.systemInstructions || it.turns.any { turn -> marker in turn.text } })
        assertEquals(marker,JSONObject(result.state).getString("secret"))
    } }
    // Review regressions: permission changes in the final guard, aggregate IPC limits, resource capacity after expiry,
    // and immutable picker identity across cancellation, rotation, duplicate callbacks, and different owners.
    @Test fun permissionChangeInEitherCommitGuardRejectsPrivateState(): Unit = runBlocking {
        for(revokeGuard in listOf(1,2)) fixture { store, tool ->
            val changed=tool.copy(stateVersion=2); store.commitTool(changed,tool,false,effects=listOf(request()))
            CapabilityBroker(context,store).execute(store.effects(tool.id).single().key) { CapabilityResult("succeeded","Native") }
            val claimed=store.claimDelivery(tool.id) { true }!!
            var allowed=true; var guards=0
            val committed=store.commitDelivery(claimed,changed.copy(stateVersion=3,state=JSONObject(changed.state).put("secret","PRIVATE").toString()),
                canCommit={ guards++; if(guards == revokeGuard) allowed=false; true },permissionCheck={allowed})
            assertFalse("Guard $revokeGuard",committed)
            assertEquals(changed,store.tool(tool.id)); assertEquals("rejected",store.deliveries(tool.id).single().phase)
        }
    }
    @Test fun multipleCalendarPayloadsFitIpcAndOversizedBatchDeliversTypedFailure(): Unit = runBlocking {
        val events=JSONArray().apply { repeat(30) { put(JSONObject().put("title","x".repeat(200)).put("start",1).put("end",2)) } }
        val result=CapabilityResult("succeeded","Native",JSONObject().put("events",events).put("limit",50))
        assertTrue(result.data.toString().toByteArray().size <= CapabilityResultEvent.MAX_DATA_BYTES)
        val effects=(0..2).map { CapabilityEffect("effect-$it","owner",1,1,
            CapabilityRequest("calendar$it","calendar.read",JSONObject().put("calendarId",1).put("from",1).put("to",2)),result) }
        val event=CapabilityResultEvent.event(UUID.randomUUID().toString(),effects.take(2))
        assertEquals(2,event.getJSONArray("entries").length())
        repeat(2) { assertEquals("succeeded",event.getJSONArray("entries").getJSONObject(it).getString("status")); assertEquals(30,event.getJSONArray("entries").getJSONObject(it).getJSONObject("data").getJSONArray("events").length()) }
        val largeState=JSONObject().apply { repeat(24) { put("field$it","x".repeat(2030)) } }
        assertTrue(largeState.toString().toByteArray().size <= 49152)
        val input=JSONObject().put("artifactId","a".repeat(128)).put("revision",Int.MAX_VALUE).put("state",largeState).put("event",event)
        com.alpha.assistant.sandbox.SandboxContract.validateRequest("return {state:input.state};",input.toString())
        val overflow=CapabilityResultEvent.event(UUID.randomUUID().toString(),effects)
        assertTrue(overflow.toString().toByteArray().size <= CapabilityResultEvent.MAX_EVENT_BYTES)
        repeat(3) {
            val entry=overflow.getJSONArray("entries").getJSONObject(it)
            assertEquals("calendar$it",entry.getString("requestId")); assertEquals("failed",entry.getString("status"))
            assertEquals("resultBatchTooLarge",entry.getJSONObject("data").getString("code"))
            assertEquals("succeeded",entry.getJSONObject("data").getString("nativeStatus"))
        }
        val preview=ToolGenerationService(provider,SandboxClient(context)).evaluate(spec(),"owner",1,spec().initialState,overflow,spec().ui.toString())
        assertEquals(3,JSONObject(preview.state).getInt("count")); assertTrue(preview.effects.isEmpty())
    }
    @Test fun acquisitionReclaimsThirtyTwoExpiredOrPriorSessionResources(): Unit = runBlocking { fixture { store, tool ->
        val manager=CapabilityResources(context,store,permissionCheck={true})
        val shared="content://fixture/shared-expired"
        val sibling=CapabilityResource("f".repeat(64),"another-owner","document",shared,"text/plain",1,true,false,ToolStore.processSession,Long.MAX_VALUE)
        store.insertResource(sibling)
        repeat(32) { index ->
            val resource=CapabilityResource(index.toString(16).padStart(64,'0'),tool.id,"document",if(index == 0) shared else "content://fixture/$index",
                "text/plain",1,index % 2 == 0,index == 0,if(index % 2 == 0) ToolStore.processSession else "old-process",if(index % 2 == 0) 0 else Long.MAX_VALUE)
            store.insertResource(resource)
        }
        assertEquals(32,store.resources(tool.id).size)
        val original=java.io.File(context.filesDir,"capture-${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1,2,3)) }
        try {
            val acquired=manager.acquire(tool.id,"camera.capture",CapabilityResult("succeeded","Saved",JSONObject().put("localPhoto",original.name)))
            store.insertResource(acquired.resource!!)
            assertEquals(1,store.resources(tool.id).size); assertTrue(original.exists())
            assertNotNull(store.resource(sibling.id)); assertTrue(store.resource(sibling.id)!!.ownsGrant)
            assertTrue(store.acquisitions().isEmpty())
            manager.release(acquired.resource)
        } finally { original.delete() }
    } }
    @Test fun pickerIdentityRejectsOutOfOrderDuplicateAndDifferentOwnerCallbacks() {
        val state=CapabilitySelectionState("library-A",processSession="session")
        val a=state.begin("owner-A:1:2:photo","photo.pick")!!
        assertNull(state.begin("owner-B:1:2:file","file.pick"))
        val rotated=state // The Activity retains the owning controller across rotation.
        assertEquals(a,rotated.active)
        assertEquals(a,rotated.consume(a.token))
        val b=rotated.begin("owner-B:1:2:file","file.pick")!!
        var privateReads=0
        rotated.consume(a.token)?.let { privateReads++ }
        assertEquals(0,privateReads); assertEquals(b,rotated.active)
        assertEquals(b,rotated.consume(b.token)); assertNull(rotated.consume(b.token))
        val canceled=rotated.begin("owner-A:1:3:camera","camera.capture")!!
        assertEquals(canceled,rotated.consume(canceled.token))
        val later=rotated.begin("owner-B:1:3:photo","photo.pick")!!
        assertNull(rotated.consume(canceled.token)); assertEquals(later,rotated.active)
        val restarted=CapabilitySelectionState("library-A",processSession="new-process")
        assertFalse(restarted.owns(later)); assertNull(restarted.consume(later.token))
    }
    @Test fun unsupportedVersionsPreserveReceiptsCredentialsAndVersion() {
        for(version in listOf(2,3,7,9)) {
            val name="upgrade-${UUID.randomUUID()}.db"
            val path=context.getDatabasePath(name); path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
                db.execSQL("CREATE TABLE tools(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, state_version INTEGER NOT NULL, spec TEXT NOT NULL, state TEXT NOT NULL, ui TEXT NOT NULL, conversation_id TEXT, saved INTEGER NOT NULL, archived INTEGER NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE revisions(tool_id TEXT,revision INTEGER,spec TEXT,state TEXT,ui TEXT,PRIMARY KEY(tool_id,revision))")
                db.execSQL("CREATE TABLE drafts(id TEXT PRIMARY KEY,tool_id TEXT,base_revision INTEGER,base_state_version INTEGER,prompt TEXT,candidate TEXT,preview_state TEXT,preview_ui TEXT,conversation_id TEXT)")
                db.execSQL("CREATE TABLE capability_grants(owner TEXT,capability TEXT,granted INTEGER,PRIMARY KEY(owner,capability))")
                db.execSQL("CREATE TABLE capability_effects(effect_key TEXT PRIMARY KEY,owner TEXT,revision INTEGER,state_version INTEGER,request TEXT,result TEXT,created_at INTEGER)")
                db.execSQL("INSERT INTO drafts VALUES('receipt','owner',-7,9,'keep','spec','state','ui','conversation')")
                db.execSQL("INSERT INTO capability_grants VALUES('owner','photo.pick',1)")
                val kept=record(spec(false))
                db.execSQL("INSERT INTO tools VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(kept.id,kept.revision,kept.stateVersion,kept.spec,kept.state,kept.ui,null,1,0,kept.createdAt))
                db.execSQL("INSERT INTO revisions VALUES(?,?,?,?,?)",arrayOf<Any?>(kept.id,1,kept.spec,kept.state,kept.ui))
                db.execSQL("INSERT INTO capability_effects VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>("kept-effect",kept.id,1,1,request().json().toString(),CapabilityResult("unknownOutcome","Preserved").json().toString(),1))
                db.execSQL("CREATE TABLE credentials(value TEXT)"); db.execSQL("INSERT INTO credentials VALUES('fixture-secret')")
                db.version=version
            }
            try {
                ToolStore(context,name).use { store ->
                    assertTrue(runCatching { store.readableDatabase }.exceptionOrNull() is ToolLibraryUnavailable)
                }
                SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                    assertEquals(version,db.version)
                    db.rawQuery("SELECT value FROM credentials",null).use { assertTrue(it.moveToFirst()); assertEquals("fixture-secret",it.getString(0)) }
                    db.rawQuery("SELECT count(*) FROM drafts",null).use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}

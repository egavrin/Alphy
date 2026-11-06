package com.alpha.assistant.capabilities

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Failure inventory: crashes around a batch, duplicate keys, altered markers/owners, stale grants,
 * permission loss, cancellation, no writable calendar, readback mismatch, and substituted controls. */
@RunWith(AndroidJUnit4::class)
class OwnedCalendarWorkflowTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun details()=JSONObject().put("title","Synthetic visit").put("start",1893456000000L).put("end",1893459600000L)
        .put("timeZone","UTC").put("deviceTimeZone",java.time.ZoneId.systemDefault().id).put("location","Synthetic room").put("reminderMinutes",15)
    private class Provider: OwnedCalendarProvider {
        val calendar=NativeOwnedCalendar(71,"Synthetic calendar","private-identity",true)
        val rows=mutableListOf<NativeOwnedEvent>()
        var fail=""; var inserts=0; var deletes=0
        var available=true
        override fun calendars()=if(available) listOf(calendar) else emptyList()
        override fun find(calendarId: Long, app: String, marker: String)=rows.filter {it.calendarId==calendarId && it.app==app && it.marker==marker}
        override fun insert(calendarId: Long, app: String, marker: String, details: JSONObject): Long {
            inserts++; if(fail=="before")error("before provider commit")
            rows.add(NativeOwnedEvent(100L+inserts,calendarId,app,marker,JSONObject(details.toString()),false))
            if(fail=="after")error("after provider commit")
            if(fail=="partial")rows[rows.lastIndex]=rows.last().copy(details=JSONObject(details.toString()).put("reminderMinutes",0))
            return rows.last().id
        }
        override fun delete(id: Long,calendarId: Long,app: String,marker: String,details: JSONObject): Int {
            deletes++;return if(rows.removeAll {it.id==id && it.calendarId==calendarId && it.app==app && it.marker==marker})1 else 0
        }
    }
    private fun fixture(block: (ToolStore,ToolRecord,Provider,OwnedCalendarCapabilities,(String,JSONObject)->CapabilityResult)->Unit) {
        check(OwnedCalendarCapabilities.hasPermissions(context)) {"Grant READ_CALENDAR and WRITE_CALENDAR before the calendar fixture suite."}
        val name="owned-calendar-${UUID.randomUUID()}.db";val store=ToolStore(context,name);val provider=Provider()
        val spec=ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Calendar fixture").put("description","")
            .put("capabilities",JSONArray(OwnedCalendarContract.ids.toList())).put("initialState",JSONObject("{\"ready\":true}")).put("stateSchema",JSONObject("{\"ready\":\"boolean\"}"))
            .put("ui",JSONObject("{\"type\":\"text\",\"text\":\"Fixture\"}")).put("source","return {state:input.state};").toString())
        val tool=ToolRecord("owner",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
        store.saveTool(tool,true)
        val native=OwnedCalendarCapabilities(context,store,provider,permissions={true},unlocked={true})
        var next=1
        fun execute(capability: String,args: JSONObject): CapabilityResult = runBlocking {
            val old=store.tool(tool.id)!!;val updated=old.copy(stateVersion=old.stateVersion+1)
            val request=CapabilityRequest("op${next++}",capability,args)
            store.commitTool(updated,old,false,effects=listOf(request))
            val key=store.effects(tool.id).last().key
            CapabilityBroker(context,store).executeOwned(key) {effect ->
                if(capability=="calendar.select")native.select(effect,provider.calendar,{true}) else native.execute(effect,{true}) {}
            }
        }
        try {block(store,tool,provider,native,::execute)}finally {
            store.close();context.deleteDatabase(name)
        }
    }
    private fun create(ref: String)=details().put("version",1).put("calendarRef",ref).put("eventKey","visit-1")
    @Test fun ownedWorkflowAndIdempotency()=fixture {store,tool,provider,_,execute ->
        val selected=execute("calendar.select",JSONObject().put("version",1));assertEquals("succeeded",selected.status)
        val args=create(selected.data.getString("calendarRef"))
        val created=execute("calendar.createOwned",args);assertEquals("succeeded",created.status)
        val eventRef=created.data.getString("eventRef")
        assertEquals("succeeded",execute("calendar.createOwned",args).status);assertEquals(1,provider.inserts)
        assertEquals("succeeded",execute("calendar.readOwned",JSONObject().put("version",1).put("eventRef",eventRef)).status)
        val neighbor=provider.rows.single().copy(id=999,marker="foreign");provider.rows.add(neighbor)
        val delete=details().put("version",1).put("eventRef",eventRef)
        assertEquals("succeeded",execute("calendar.deleteOwned",delete).status)
        assertEquals(listOf(neighbor),provider.rows);assertEquals(1,provider.deletes)
        assertEquals("succeeded",execute("calendar.deleteOwned",delete).status);assertEquals(1,provider.deletes)
        assertTrue(OwnedCalendarLedger(store).event(tool.id,eventRef)!!.phase=="deleted")
        assertFalse(created.data.toString().contains("private-identity"));OwnedCalendarContract.validateResult("calendar.createOwned",created)
    }
    @Test fun interruptedCreateNeverReplaysAndReadbackIsRequired() {
        for(failure in listOf("before","after","partial"))fixture {_,_,provider,_,execute ->
            val ref=execute("calendar.select",JSONObject().put("version",1)).data.getString("calendarRef")
            provider.fail=failure
            assertEquals("unknownOutcome",execute("calendar.createOwned",create(ref)).status)
            provider.fail=""
            val recovered=execute("calendar.createOwned",create(ref))
            assertEquals(if(failure=="after")"succeeded" else "unknownOutcome",recovered.status)
            assertEquals(1,provider.inserts)
        }
    }
    @Test fun uncertainCreateRecoversAfterDeviceTimezoneChangesWithoutAnotherInsert()=fixture {store,_,provider,_,execute ->
        val ref=execute("calendar.select",JSONObject().put("version",1)).data.getString("calendarRef")
        val args=create(ref);val original=java.util.TimeZone.getDefault()
        provider.fail="after";assertEquals("unknownOutcome",execute("calendar.createOwned",args).status);provider.fail=""
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(if(original.id=="Pacific/Honolulu")"Europe/Paris" else "Pacific/Honolulu"))
            val recovered=execute("calendar.createOwned",args)
            assertEquals(recovered.message,"succeeded",recovered.status);assertEquals(1,provider.inserts)
            assertEquals(OwnedCalendarContract.fingerprint(args),OwnedCalendarLedger(store).keyed("owner","visit-1")!!.fingerprint)
            assertEquals("denied",execute("calendar.createOwned",JSONObject(args.toString()).put("eventKey","new-visit")).status)
            assertEquals(1,provider.inserts)
        } finally {java.util.TimeZone.setDefault(original)}
    }
    @Test fun cachedSnapshotRejectsAnEventCreatedWithoutAStateChange()=fixture {store,_,provider,native,execute ->
        val ref=execute("calendar.select",JSONObject().put("version",1)).data.getString("calendarRef")
        val old=store.tool("owner")!!
        store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(CapabilityRequest("late","calendar.createOwned",create(ref))))
        val createKey=store.effects("owner").last().key
        val stale=OwnedCalendarLedger(store).snapshot(store.tool("owner")!!);assertTrue(stale.entries.isEmpty())
        runBlocking {assertEquals("succeeded",CapabilityBroker(context,store).executeOwned(createKey) {native.execute(it,{true}){}}.status)}
        val fresh=OwnedCalendarLedger(store).snapshot(store.tool("owner")!!);assertEquals(1,fresh.entries.size)
        val current=store.tool("owner")!!
        store.commitTool(current.copy(stateVersion=current.stateVersion+1),current,false,effects=listOf(CapabilityRequest("all","calendar.deleteAllOwned",JSONObject().put("version",1))))
        val key=store.effects("owner").last().key;store.claimEffect(key);val effect=store.effect(key)!!
        runBlocking {
            assertEquals("denied",native.deleteAll(effect,stale,{true}).status);assertEquals(0,provider.deletes)
            assertEquals("succeeded",native.deleteAll(effect,fresh,{true}).status);assertEquals(1,provider.deletes)
        }
    }
    @Test fun bulkInterruptionRecoveryRevocationAndArchiveRetainJournalCounts() {
        for(mode in listOf("cancel","recover","revoke","archive","access","exception"))fixture {store,_,provider,_,execute ->
            val ref=execute("calendar.select",JSONObject().put("version",1)).data.getString("calendarRef")
            repeat(3) {index ->assertEquals("succeeded",execute("calendar.createOwned",create(ref).put("eventKey","journal-$index")).status)}
            val ledger=OwnedCalendarLedger(store);val snapshot=ledger.snapshot(store.tool("owner")!!)
            val old=store.tool("owner")!!
            store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(CapabilityRequest("journal","calendar.deleteAllOwned",JSONObject().put("version",1))))
            val key=store.effects("owner").last().key
            fun journal(effect: CapabilityEffect) {
                assertTrue(ledger.beginDeleteAll(effect,snapshot))
                ledger.deleteProgress(effect,snapshot.entries[0],"succeeded")
                ledger.deleteProgress(effect,snapshot.entries[1],"running")
            }
            if(mode in setOf("cancel","exception"))runBlocking {
                val result=runCatching {CapabilityBroker(context,store).executeOwned(key) {effect ->
                    journal(effect)
                    if(mode=="cancel")throw kotlinx.coroutines.CancellationException("Synthetic interrupted bulk operation")
                    else error("Synthetic provider interruption")
                }}
                if(mode=="cancel")assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                else assertEquals(3,result.getOrThrow().data.getInt("total"))
            } else {
                store.claimEffect(key);val effect=store.effect(key)!!;journal(effect)
                when(mode) {
                    "recover" -> store.recoverCapabilityWork(force=true)
                    "revoke" -> {val old=store.tool("owner")!!;store.commitTool(old.copy(revision=old.revision+1),old,true);store.finishEffect(key,CapabilityResult("unknownOutcome","Revision changed"))}
                    "archive" -> store.setArchived(store.tool("owner")!!,true)
                    "access" -> {
                        val changed=store.tool("owner")!!;store.saveTool(changed.copy(stateVersion=changed.stateVersion+1),false)
                        store.finishEffect(key,CapabilityResult("unknownOutcome","Synthetic access changed after provider work"))
                    }
                }
            }
            val result=store.effect(key)!!.result
            assertEquals(mode,"unknownOutcome",result.status)
            assertEquals(mode,3,result.data.getInt("total"));assertEquals(mode,1,result.data.getInt("deleted"))
            assertEquals(mode,1,result.data.getInt("unknown"));assertEquals(mode,1,result.data.getInt("remaining"))
            OwnedCalendarContract.validateResult("calendar.deleteAllOwned",result)
            val previous=result.json().toString();store.recoverCapabilityWork(force=true)
            assertEquals(previous,store.effect(key)!!.result.json().toString());assertEquals(0,provider.deletes)
        }
    }
    @Test fun changedMarkersAndForeignOwnerCannotDelete()=fixture {store,_,provider,_,execute ->
        val ref=execute("calendar.select",JSONObject().put("version",1)).data.getString("calendarRef")
        val created=execute("calendar.createOwned",create(ref));val eventRef=created.data.getString("eventRef")
        assertNull(OwnedCalendarLedger(store).event("foreign-owner",eventRef))
        provider.rows[0]=provider.rows[0].copy(marker="changed")
        assertEquals("unknownOutcome",execute("calendar.deleteOwned",details().put("version",1).put("eventRef",eventRef)).status)
        assertEquals(0,provider.deletes);assertEquals(1,provider.rows.size)
    }
    @Test fun permissionLifecycleAndCalendarGuards()=fixture {store,_,provider,_,execute ->
        val ref=execute("calendar.select",JSONObject().put("version",1)).data.getString("calendarRef")
        provider.available=false
        assertEquals("unavailable",execute("calendar.createOwned",create(ref)).status);assertEquals(0,provider.inserts)
        provider.available=true
        val old=store.tool("owner")!!;val request=CapabilityRequest("guard","calendar.createOwned",create(ref))
        store.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(request))
        val key=store.effects("owner").last().key;store.claimEffect(key)
        val effect=store.effect(key)!!
        assertEquals("denied",OwnedCalendarCapabilities(context,store,provider,permissions={false},unlocked={true}).execute(effect,{true}){}.status)
        assertEquals("awaitingUser",OwnedCalendarCapabilities(context,store,provider,permissions={true},unlocked={true}).execute(effect,{false}){}.status)
        val changed=store.tool("owner")!!;store.commitTool(changed.copy(stateVersion=changed.stateVersion+1),changed,false)
        assertEquals("denied",OwnedCalendarCapabilities(context,store,provider,permissions={true},unlocked={true}).execute(effect,{true}){}.status)
        assertEquals(0,provider.inserts)
    }
    @Test fun closedContractAndFrozenNativeSelection() {
        val args=create("a".repeat(64));CapabilityCatalog.definition("calendar.createOwned").validate(args)
        for(invalid in listOf(JSONObject(args.toString()).put("timeZone","Bad/Zone"),JSONObject(args.toString()).put("end",args.getLong("start")),
            JSONObject(args.toString()).put("reminderMinutes",10081),JSONObject(args.toString()).put("account","private"),JSONObject(args.toString()).put("version",2)))
            assertTrue(runCatching {CapabilityCatalog.definition("calendar.createOwned").validate(invalid)}.isFailure)
        val node=JSONObject().put("type","calendar").put("action","create").put("capability","calendar.createOwned").put("args",args)
        val tool=ToolRecord("owner",2,3,"","{}",node.toString(),null,true)
        val selection=OwnedCalendarInteraction.from(tool,"create",null)!!
        fun effect(value: JSONObject)=CapabilityEffect("key","owner",2,4,CapabilityRequest("create","calendar.createOwned",value),CapabilityResult("awaitingUser","Pending"),"pending")
        assertTrue(selection.matches(effect(args)));assertFalse(selection.matches(effect(JSONObject(args.toString()).put("title","Other"))))
        assertFalse(selection.matches(effect(args).copy(owner="foreign")));assertFalse(selection.matches(effect(args).copy(stateVersion=7)))
        val button=JSONObject("{\"type\":\"button\",\"text\":\"Create\",\"action\":\"create\"}")
        assertNull(OwnedCalendarInteraction.from(tool.copy(ui=button.toString()),"create",null))
        assertTrue(runCatching {CapabilityCatalog.parseEffects(JSONArray().put(effect(args).request.json()),OwnedCalendarContract.ids,"preview")}.isFailure)
        // Both instants in a repeated local hour remain distinct. No local-time gap or overlap is guessed.
        val overlap=JSONObject(args.toString()).put("start",java.time.Instant.parse("2030-11-03T05:30:00Z").toEpochMilli())
            .put("end",java.time.Instant.parse("2030-11-03T06:30:00Z").toEpochMilli()).put("timeZone","America/New_York")
        CapabilityCatalog.definition("calendar.createOwned").validate(overlap)
    }
}

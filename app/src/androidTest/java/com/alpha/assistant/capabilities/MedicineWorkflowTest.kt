package com.alpha.assistant.capabilities

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import android.content.Intent
import com.alpha.assistant.tools.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Native integration cases are necessary because SQLite durability and native scope cannot run in the JS sandbox.
 * Failure cases: malformed/extra arguments, invented/future records, offset above 30, foreign meal reference,
 * duplicate keys, conflicting replay, intake inferred from reminder, wrong window, DST gap/overlap, truncated report.
 * This fixture uses private databases and a fixed clock. It never registers alarms or reads user data.
 */
class MedicineWorkflowTest {
    @Test fun explicitRecordsSurviveRestartAndNeverInferIntake() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="medicine-${UUID.randomUUID()}.db"
        val at=Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
        val meal=JSONObject().put("version",1).put("recordKey","lunch").put("at",at).put("timeZone","UTC")
        val intake=JSONObject().put("version",1).put("recordKey","taken").put("at",at+30*60000).put("timeZone","UTC")
            .put("medicine","User medicine").put("mealKey","lunch")
        try {
            MedicineStore(context,name).use { store ->
                assertEquals("succeeded",store.record("app","medicine.recordMeal",meal,at).status)
                assertEquals("succeeded",store.record("app","medicine.recordMeal",meal,at).status)
                assertEquals("denied",store.record("app","medicine.recordMeal",JSONObject(meal.toString()).put("at",at-1),at).status)
                assertEquals("denied",store.record("other","medicine.recordIntake",intake,at+30*60000).status)
                assertEquals("succeeded",store.record("app","medicine.recordIntake",intake,at+30*60000).status)
                assertEquals("succeeded",store.record("app","medicine.recordIntake",intake,at+30*60000).status)
            }
            MedicineStore(context,name).use { store ->
                val history=store.history("app",at-1,at+31*60000,0)
                assertEquals(2,history.getJSONArray("records").length())
                assertEquals("within30Minutes",history.getJSONArray("records").getJSONObject(1).getString("mealWindow"))
                assertEquals(0,store.history("other",at-1,at+31*60000,0).getJSONArray("records").length())
            }
        } finally {context.deleteDatabase(name)}
    }
    @Test fun closedContractAndDstPolicy() {
        fun args()=JSONObject().put("version",1).put("reminderKey","daily").put("medicine","User medicine").put("timeZone","America/New_York").put("minuteOfDay",150)
        CapabilityCatalog.definition("medicine.remindDaily").validate(args())
        assertTrue(runCatching {CapabilityCatalog.definition("medicine.remindDaily").validate(args().put("dose","invented"))}.isFailure)
        assertTrue(runCatching {CapabilityCatalog.definition("medicine.remindMeal").validate(JSONObject().put("version",1).put("reminderKey","meal").put("medicine","User medicine").put("mealKey","lunch").put("timeZone","UTC").put("offsetMinutes",31))}.isFailure)
        val next=MedicineSchedule.nextDaily(args(),Instant.parse("2026-03-08T06:00:00Z").toEpochMilli())
        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(),next)
        val overlap=MedicineSchedule.nextDaily(args().put("minuteOfDay",90),Instant.parse("2026-11-01T05:31:00Z").toEpochMilli())
        assertEquals(Instant.parse("2026-11-02T06:30:00Z").toEpochMilli(),overlap)
    }
    /** Repair failure cases: restore consumes an eligible occurrence; duplicate alarms replay it;
     * discovery ignores one denied prerequisite or blocks unrelated records; zero offset rejects T+1s,
     * moves the selected offset, records the attempt at meal time, or posts after meal+30m.
     * Injected Android operations keep these workflows independent of device permissions and alarms.
     */
    private class Platform: MedicinePlatform {
        val armed=mutableListOf<JSONObject>()
        val access=MedicineReminderAccess(true,true,true,true)
        var posts=0
        override fun accessFailure()=access.failure()
        override fun arm(reminder: JSONObject) {armed.add(JSONObject(reminder.toString()))}
        override fun cancel(reminder: JSONObject) {}
        override fun post(reminder: JSONObject,at: Long,now: Long): String {posts++;return "activeNotification"}
    }
    private fun reminderFixture(block: (ToolStore,MedicineStore,Platform,(String,JSONObject,Long)->CapabilityResult)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="medicine-repair-${UUID.randomUUID()}.db"
        val platform=Platform()
        val permissions=AndroidCapabilities(context,medicineAccess={platform.access})
        val tools=ToolStore(context,name,permissionCheck=permissions::permissionGranted)
        val medicineName=MedicineRuntime.database(tools)
        val medicine=MedicineStore(context,medicineName)
        val spec=ToolSpec.parse(JSONObject().put("formatVersion",3).put("title","Medicine fixture").put("description","")
            .put("capabilities",JSONArray(MedicineContract.ids.toList())).put("initialState",JSONObject("{\"ready\":true}"))
            .put("stateSchema",JSONObject("{\"ready\":\"boolean\"}"))
            .put("ui",JSONObject("{\"type\":\"text\",\"text\":\"Fixture\"}")).put("source","return {state:input.state};").toString())
        val tool=ToolRecord("owner",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
        tools.saveTool(tool,true)
        var sequence=0
        fun execute(id: String,args: JSONObject,now: Long): CapabilityResult=runBlocking {
            val old=tools.tool(tool.id)!!
            tools.commitTool(old.copy(stateVersion=old.stateVersion+1),old,false,effects=listOf(CapabilityRequest("op${sequence++}",id,args)))
            val key=tools.effects(tool.id).last().key
            CapabilityBroker(context,tools).executeOwned(key) {effect ->
                MedicineCapabilities(context,tools,clock={now},platform=platform,unlocked={true}).execute(effect,{true}) {}
            }
        }
        try {block(tools,medicine,platform,::execute)} finally {medicine.close();tools.close();context.deleteDatabase(name);context.deleteDatabase(medicineName)}
    }
    private fun mealAt(at: Long)=JSONObject().put("version",1).put("recordKey","lunch").put("at",at).put("timeZone","UTC")
    private fun mealReminder(key: String,offset: Int)=JSONObject().put("version",1).put("reminderKey",key).put("medicine","Explicit medicine")
        .put("timeZone","UTC").put("mealKey","lunch").put("offsetMinutes",offset)
    private fun alarm(reminder: JSONObject)=Intent().putExtra("owner",reminder.getString("owner")).putExtra("key",reminder.getString("reminderKey")).putExtra("at",reminder.getLong("nextAt"))
    @Test fun legacyScheduleNeedsFreshExplicitActivationOfTheSameDefinition() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val now=Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
        reminderFixture {tools,medicine,platform,execute ->
            val args=JSONObject().put("version",1).put("reminderKey","daily").put("medicine","Explicit medicine")
                .put("timeZone","UTC").put("minuteOfDay",12*60+1)
            val oldAt=now+60000
            medicine.writableDatabase.execSQL(
                "INSERT INTO reminders(owner,key,revision,capability,args,mode,status,next_at) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any>("owner","daily",1,"medicine.remindDaily",args.toString(),"daily","scheduled",oldAt))
            val legacy=medicine.reminders().single()
            MedicineRuntime.deliver(context,tools,medicine,alarm(legacy),oldAt,platform)
            assertEquals(0,platform.posts)
            assertTrue(platform.armed.isEmpty())
            val preserved=medicine.reminders().single()
            assertEquals("reactivationRequired",preserved.getString("status"))
            assertEquals(args.toString(),preserved.getJSONObject("args").toString())
            assertEquals("denied",execute("medicine.remindDaily",JSONObject(args.toString()).put("minuteOfDay",1),now).status)
            assertTrue(platform.armed.isEmpty())
            assertEquals("succeeded",execute("medicine.remindDaily",args,now).status)
            val active=medicine.reminders().single()
            assertEquals("scheduled",active.getString("status"))
            assertEquals(oldAt,active.getLong("nextAt"))
            MedicineRuntime.deliver(context,tools,medicine,alarm(active),oldAt,platform)
            MedicineRuntime.deliver(context,tools,medicine,alarm(active),oldAt,platform)
            assertEquals(1,platform.posts)
        }
    }
    @Test fun restoreAndDirectDeliveryShareTheSameWindowAndClaim() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val at=Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
        for(daily in listOf(false,true)) for(restore in listOf(false,true)) reminderFixture {tools,medicine,platform,execute ->
            execute("medicine.recordMeal",mealAt(at),at)
            val args=if(daily) JSONObject().put("version",1).put("reminderKey","daily").put("medicine","Explicit medicine").put("timeZone","UTC").put("minuteOfDay",12*60+1) else mealReminder("meal",1)
            assertEquals("succeeded",execute(if(daily)"medicine.remindDaily" else "medicine.remindMeal",args,at).status)
            val original=medicine.reminders().single()
            val intent=alarm(original)
            val now=at+90000
            if(restore) MedicineRuntime.reconcile(context,tools,medicine,now,platform)
            assertNull(medicine.entry("owner","reminder",original.getString("reminderKey")+":"+original.getLong("nextAt")))
            MedicineRuntime.deliver(context,tools,medicine,intent,now,platform)
            MedicineRuntime.deliver(context,tools,medicine,intent,now,platform)
            MedicineRuntime.reconcile(context,tools,medicine,now,platform)
            assertEquals(1,platform.posts)
            assertEquals("activeNotification",medicine.entry("owner","reminder",original.getString("reminderKey")+":"+original.getLong("nextAt"))!!.getString("status"))
        }
        for(daily in listOf(false,true)) reminderFixture {tools,medicine,platform,execute ->
            execute("medicine.recordMeal",mealAt(at),at)
            val args=if(daily) JSONObject().put("version",1).put("reminderKey","daily").put("medicine","Explicit medicine").put("timeZone","UTC").put("minuteOfDay",12*60+1) else mealReminder("meal",1)
            execute(if(daily)"medicine.remindDaily" else "medicine.remindMeal",args,at)
            val original=medicine.reminders().single()
            MedicineRuntime.reconcile(context,tools,medicine,at+31*60000,platform)
            MedicineRuntime.deliver(context,tools,medicine,alarm(original),at+31*60000,platform)
            assertEquals(0,platform.posts)
            assertEquals("missed",medicine.entry("owner","reminder",original.getString("reminderKey")+":"+original.getLong("nextAt"))!!.getString("status"))
        }
    }
    @Test fun zeroOffsetRequestsNowWithoutMovingMealOrSelectedOffset()=reminderFixture {_,medicine,platform,execute ->
        val at=Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
        execute("medicine.recordMeal",mealAt(at),at)
        val result=execute("medicine.remindMeal",mealReminder("zero",0),at+1000)
        assertEquals("succeeded",result.status);assertEquals(1,platform.posts)
        assertTrue(result.message.contains("selected time has passed"))
        assertEquals(at,result.data.getJSONObject("reminder").getLong("selectedAt"))
        assertEquals(at+1000,result.data.getJSONObject("reminder").getLong("requestedAt"))
        assertEquals(0,medicine.reminders().single().getJSONObject("args").getInt("offsetMinutes"))
        assertEquals(at+1000,medicine.history("owner",at,at+2000,0).getJSONArray("records").getJSONObject(1).getLong("at"))
        assertEquals("succeeded",execute("medicine.remindMeal",mealReminder("zero",0),at+2000).status)
        assertEquals(1,platform.posts)
        assertEquals("unavailable",execute("medicine.remindMeal",mealReminder("lateZero",0),at+1800001).status)
        assertEquals("unavailable",execute("medicine.remindMeal",mealReminder("lateNonzero",1),at+61000).status)
        assertEquals(1,platform.posts)
        MedicineContract.validateResult("medicine.remindMeal",result)
    }
    @Test fun discoverySeparatesReminderAccessFromLocalRecords() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(access in listOf(MedicineReminderAccess(false,true,true,true),MedicineReminderAccess(true,false,true,true),MedicineReminderAccess(true,true,false,true),MedicineReminderAccess(true,true,true,false))) {
            val native=AndroidCapabilities(context,medicineAccess={access})
            val discovery=native.discover().getJSONArray("implemented")
            for(id in MedicineContract.ids) {
                val row=(0 until discovery.length()).map {discovery.getJSONObject(it)}.single {it.getString("id")==id}.getJSONObject("availability")
                if(id in MedicineContract.reminderIds) {
                    assertNotEquals("available",row.getString("state"))
                    assertEquals(access.exactAlarms && access.notificationPermission,row.getBoolean("permissionGranted"))
                    assertEquals(access.channelEnabled,row.getJSONObject("prerequisites").getBoolean("medicineChannelEnabled"))
                } else {assertEquals("available",row.getString("state"));assertTrue(row.getBoolean("permissionGranted"))}
            }
        }
        assertEquals("available",AndroidCapabilities(context,medicineAccess={MedicineReminderAccess(true,true,true,true)}).availability("medicine.remindDaily").getString("state"))
    }

}

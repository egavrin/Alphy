package com.alpha.assistant.automations

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyCleanupUpgradeWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mode = InstrumentationRegistry.getArguments().getString("legacyCleanupUpgrade")
    private val due = 1_893_456_000_000L
    private val read = CapabilityRequest("read","calendar.read.rolling",JSONObject("""{"calendarId":7,"days":1,"zone":"UTC"}"""))
    private val sync = CapabilityRequest("sync","tool.records.calendar",JSONObject("""{"toolId":"calendar-fixture","revision":1,"field":"events","source":"read"}"""))
    private val notice = CapabilityRequest("notice","notification.post",JSONObject("""{"title":"Fixture","text":"Test only"}"""))
    private val spec = ToolSpec.parse("""
        {"formatVersion":3,"title":"Calendar fixture","description":"","capabilities":[],
        "stateSchema":{"events":{"type":"list","maxItems":10,"fields":{"id":"string","text":"string"}}},
        "initialState":{"events":[{"id":"alpha_calendar_7_42","text":"Imported"},{"id":"manual","text":"Manual"}]},
        "ui":{"type":"text","text":"Fixture"},"source":"return {state:input.state};"}
    """.trimIndent())

    private fun definition(name: String, trigger: JSONObject, vararg actions: CapabilityRequest) =
        JSONObject().put("name",name).put("trigger",trigger).put("conditions",JSONObject())
            .put("actions",JSONArray(actions.map { it.json() }))

    private fun alarmIntent(id: String) = Intent(context,AutomationReceiver::class.java)
        .setAction(AutomationRuntime.ALARM).setData(Uri.parse("alpha-automation://alarm/$id"))
        .putExtra("id",id).putExtra("revision",1).putExtra("at",due)

    private fun pendingAlarm(intent: Intent, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context,0,intent,flags or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleFixtureAlarm(intent: Intent) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,due,checkNotNull(pendingAlarm(intent,PendingIntent.FLAG_UPDATE_CURRENT)),
        )
    }

    @Test fun retireSyncAcrossSignedUpgrade(): Unit = runBlocking {
        assumeTrue(mode in setOf("seed","verify"))
        val keys = SecureKeyStore(context)
        val manual = JSONObject("""{"type":"manual"}""")
        val interval = JSONObject().put("type","interval").put("minutes",15).put("anchor",due)
        val mixed = definition("Retired scheduled",interval,read,sync,notice)
        val preview = definition("Retired manual",manual,read,sync,notice)
        if (mode == "seed") {
            ShellStore(context).use { it.saveConversation(Conversation("cleanup-chat","Upgrade fixture",1,2)) }
            ToolStore(context).use { tools ->
                tools.commitTool(ToolRecord("calendar-fixture",1,1,spec.json,spec.initialState,spec.ui.toString(),"cleanup-chat",true),null,true)
                tools.setNetworkGrant("calendar-fixture",true)
                tools.writableDatabase.execSQL("INSERT INTO capability_grants VALUES ('calendar-fixture','calendar.read',1)")
            }
            AutomationStore(context).use { store ->
                val scheduled = store.save(null,mixed)
                val pending = store.save(null,preview)
                store.save(null,definition("Standalone read",manual,read))
                store.approve(scheduled.id,scheduled.revision)
                store.next(scheduled.id,scheduled.revision,due)
                scheduleFixtureAlarm(alarmIntent(scheduled.id))
                val approved = checkNotNull(store.rule(scheduled.id))
                val run = checkNotNull(store.begin(approved,AutomationEvent("manual","seeded-history"),due,false))
                store.finish(run,"succeeded","Preserved history")
                store.setState("cleanup-fixture-ids",JSONArray(listOf(scheduled.id,pending.id)).toString())
                assertNotNull(store.oneTimeRule(AutomationOneTime.request(store,pending)))
            }
            keys.save("legacy-cleanup-fixture-key").getOrThrow()
            return@runBlocking
        }

        repeat(2) {
            AutomationStore(context).use { store ->
                val ids = JSONArray(checkNotNull(store.state("cleanup-fixture-ids")))
                val scheduled = ids.getString(0)
                val pending = ids.getString(1)
                assertNull(store.rule(scheduled))
                assertNull(store.rule(pending))
                assertEquals("Standalone read",store.rules().single().definition.name)
                assertEquals("Preserved history",store.history(scheduled).single().message)
                store.readableDatabase.rawQuery("SELECT definition,revision,enabled,approved,next_at FROM rules WHERE id=?",
                    arrayOf(scheduled)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(mixed.toString(),cursor.getString(0))
                    assertEquals(2,cursor.getInt(1))
                    assertEquals(0,cursor.getInt(2))
                    assertEquals(0,cursor.getInt(3))
                    assertTrue(cursor.isNull(4))
                }
                val oldDefinition = AutomationDefinition(mixed.toString(),"Retired scheduled",interval,JSONObject(),listOf(read,sync,notice))
                val oldRule = AutomationRule(scheduled,1,1,true,oldDefinition,due,"")
                val executor = AutomationExecutor(store,{ null },{ _,_ -> null }) { _,_,_ -> error("Retired effect ran") }
                assertNull(AutomationSchedule.deliver(store,oldRule,due,due,executor))
                assertNull(executor.run(scheduled,1,AutomationEvent("manual","stale")))
                assertNull(store.oneTimeRule(AutomationOneTime.Request(store.databaseName,pending,1,preview.toString(),null)))
                assertTrue(runCatching { AutomationDefinition.parse(mixed) }.isFailure)

                val lateAlarm = alarmIntent(scheduled)
                scheduleFixtureAlarm(lateAlarm)
                assertNotNull(pendingAlarm(lateAlarm,PendingIntent.FLAG_NO_CREATE))
                AutomationRuntime.reconcile(context,store)
                assertNull(pendingAlarm(lateAlarm,PendingIntent.FLAG_NO_CREATE))
                val delivered = CompletableDeferred<Unit>()
                AutomationRuntime.alarmReceived(context,lateAlarm) { delivered.complete(Unit) }
                withTimeout(5000) { delivered.await() }
                assertEquals("Preserved history",store.history(scheduled).single().message)
            }
        }
        ToolStore(context).use { tools ->
            assertEquals(spec.initialState,tools.tool("calendar-fixture")?.state)
            assertTrue(tools.networkGrantEnabled("calendar-fixture"))
            tools.readableDatabase.rawQuery("SELECT capability FROM capability_grants",null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("host.network",cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
        }
        assertEquals("legacy-cleanup-fixture-key",keys.read().getOrThrow())
    }
}

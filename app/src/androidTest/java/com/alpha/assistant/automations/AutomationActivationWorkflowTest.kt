package com.alpha.assistant.automations

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationActivationWorkflowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun notice(id: String="notice")=JSONObject().put("id",id).put("capability","notification.post")
        .put("args",JSONObject().put("title","Fixture").put("text","Local only"))
    private fun definition(trigger: JSONObject=JSONObject().put("type","manual"), actions: JSONArray=JSONArray().put(notice())) =
        JSONObject().put("name","Activation fixture").put("trigger",trigger).put("conditions",JSONObject()).put("actions",actions)
    private suspend fun fixture(block: suspend (String,AutomationStore)->Unit) {
        val name="automation-activation-${UUID.randomUUID()}.db"
        try {AutomationStore(context,name).use {block(name,it)}} finally {context.deleteDatabase(name)}
    }

    @Test fun immediatePauseFencesNextEffectAndUnchangedRuleResumes(): Unit=runBlocking {
        fixture { name,store ->
            val draft=store.save(null,definition(actions=JSONArray().put(notice("first")).put(notice("second"))))
            store.approve(draft.id,draft.revision)
            val enabled=checkNotNull(store.rule(draft.id))
            val started=CompletableDeferred<Unit>()
            val release=CompletableDeferred<Unit>()
            var effects=0
            val executor=AutomationExecutor(store,{null},{_,_->null}) {_,_,_ ->
                effects++
                started.complete(Unit)
                release.await()
                CapabilityResult("succeeded","Fixture")
            }
            val run=async {executor.run(enabled.id,enabled.revision,AutomationEvent("manual","pause-fixture"))}
            started.await()
            try {
                assertTrue(AutomationRuntime.pause(context,store,enabled))
                assertFalse(store.active(enabled.id,enabled.revision))
            } finally {release.complete(Unit)}
            assertEquals("cancelled",run.await()?.status)
            assertEquals(1,effects)
            AutomationStore(context,name).use { reopened ->
                val paused=checkNotNull(reopened.rule(enabled.id))
                assertEquals(enabled.revision,paused.approvedRevision)
                assertEquals(AutomationResumeResult.RESUMED,AutomationRuntime.resumeApproved(reopened,paused) {null})
                assertTrue(reopened.active(enabled.id,enabled.revision))
            }
        }
    }

    @Test fun staleRevisionAndRecordedDenialRequireFreshActivationAfterAccessReturns(): Unit=runBlocking {
        fixture { _,store ->
            val rule=store.save(null,definition())
            store.approve(rule.id,rule.revision)
            store.pause(rule.id)
            val pending=AutomationRuntime.prepareApproval(store,rule.id,rule.revision)
            val paused=checkNotNull(store.rule(rule.id))
            val denied="Android access denied"
            assertEquals(AutomationResumeResult.REVIEW_REQUIRED,AutomationRuntime.resumeApproved(store,paused) {denied})
            assertFalse(store.active(rule.id,rule.revision))
            assertEquals(denied,store.rule(rule.id)?.attention)
            assertEquals(AutomationResumeResult.REVIEW_REQUIRED,AutomationRuntime.resumeApproved(store,paused) {null})
            assertTrue(runCatching {AutomationRuntime.activateApproved(store,pending) {null}}.isFailure)
            val fresh=AutomationRuntime.prepareApproval(store,rule.id,rule.revision)
            assertTrue(runCatching {AutomationRuntime.activateApproved(store,fresh) {denied}}.isFailure)
            assertFalse(store.active(rule.id,rule.revision))
            val restored=AutomationRuntime.prepareApproval(store,rule.id,rule.revision)
            AutomationRuntime.activateApproved(store,restored) {null}
            assertTrue(store.active(rule.id,rule.revision))
            val changed=store.save(rule.id,definition().put("name","Edited rule"),rule.revision)
            assertEquals(AutomationResumeResult.REVIEW_REQUIRED,AutomationRuntime.resumeApproved(store,rule) {null})
            assertEquals(AutomationResumeResult.REVIEW_REQUIRED,AutomationRuntime.resumeApproved(store,changed) {null})
            assertFalse(store.active(changed.id,changed.revision))
        }
    }

    @Test fun dependencyAndExpiredScheduleInvalidateResume(): Unit=runBlocking {
        fixture { _,store ->
            val toolDb="automation-dependency-${UUID.randomUUID()}.db"
            try {ToolStore(context,toolDb).use { tools ->
                val spec=ToolSpec.parse("""{"formatVersion":3,"title":"Counter","description":"","capabilities":[],"stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"text","text":"Count","bind":"count"},"source":"return {state:input.state};"}""")
                val target=ToolRecord("counter",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true)
                tools.saveTool(target,true)
                val action=JSONObject("""{"id":"set","capability":"tool.state.set","args":{"toolId":"counter","revision":1,"field":"count","value":2}}""")
                val rule=store.save(null,definition(actions=JSONArray().put(action)))
                store.approve(rule.id,rule.revision)
                store.pause(rule.id)
                tools.setArchived(target,true)
                assertEquals(AutomationResumeResult.REVIEW_REQUIRED,AutomationRuntime.resumeApproved(store,rule) {
                    AutomationRuntime.dependencyFailure(tools,it)
                })
                tools.setArchived(checkNotNull(tools.tool(target.id)),false)
                assertEquals(AutomationResumeResult.REVIEW_REQUIRED,AutomationRuntime.resumeApproved(store,rule) {
                    AutomationRuntime.dependencyFailure(tools,it)
                })
            }} finally {context.deleteDatabase(toolDb)}
            val expired=store.save(null,definition(JSONObject("""{"type":"schedule","repeat":"once","date":"2000-01-01","time":"09:00","zone":"UTC","exact":false}""")))
            store.approve(expired.id,expired.revision)
            store.pause(expired.id)
            assertEquals(AutomationResumeResult.REVIEW_REQUIRED,AutomationRuntime.resumeApproved(store,expired) {null})
            assertFalse(store.active(expired.id,expired.revision))
        }
    }

    @Test fun confirmedDeletionCancelsAlarmAndRetainsOnlyExistingHistoryAcrossReopen(): Unit=runBlocking {
        fixture { name,store ->
            val initial=store.save(null,definition(JSONObject("""{"type":"schedule","repeat":"daily","time":"08:00","zone":"UTC","exact":false}""")))
            val edited=store.save(initial.id,JSONObject(initial.definition.json).put("name","Current revision"),initial.revision)
            val other=store.save(null,definition().put("name","Keep"))
            store.approve(edited.id,edited.revision)
            store.approve(other.id,other.revision)
            val now=System.currentTimeMillis()
            val at=now+86_400_000L
            store.next(edited.id,edited.revision,at)
            val scheduled=checkNotNull(store.rule(edited.id))
            val alarmIntent=Intent(context,AutomationReceiver::class.java).setAction(AutomationRuntime.ALARM)
                .setData(Uri.parse("alpha-automation://alarm/${edited.id}"))
            val alarm=PendingIntent.getBroadcast(context,0,alarmIntent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val manager=context.getSystemService(AlarmManager::class.java)
            try {
                manager.set(AlarmManager.RTC_WAKEUP,at,alarm)
                val previous=checkNotNull(store.begin(scheduled,AutomationEvent("manual","kept"),now,false))
                store.finish(previous,"succeeded","Kept run")
                store.effect(previous.id,"notice","Kept effect")
                assertTrue(runCatching {AutomationRuntime.deleteRule(context,store,initial)}.isFailure)
                assertTrue(store.active(edited.id,edited.revision))
                assertNotNull(PendingIntent.getBroadcast(context,0,alarmIntent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
                AutomationRuntime.deleteRule(context,store,scheduled)
                assertNull(PendingIntent.getBroadcast(context,0,alarmIntent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
                var called=false
                val executor=AutomationExecutor(store,{null},{_,_->null}) {_,_,_ ->
                    called=true
                    CapabilityResult("succeeded","Unexpected")
                }
                assertNull(AutomationSchedule.deliver(store,scheduled,at,at,executor))
                assertFalse(called)
                AutomationStore(context,name).use { reopened ->
                    assertNull(reopened.rule(edited.id));assertTrue(reopened.active(other.id,other.revision))
                    assertEquals("Kept run",reopened.history(edited.id).single().message)
                    fun retainedEffect(): Boolean=reopened.readableDatabase.rawQuery(
                        "SELECT result FROM effects WHERE run_id=?",arrayOf(previous.id)
                    ).use {it.moveToFirst() && it.getString(0)=="Kept effect"}
                    assertTrue(retainedEffect())
                    reopened.prune(now+31*86_400_000L)
                    assertTrue(reopened.history(edited.id).isEmpty());assertFalse(retainedEffect())
                }
            } finally {manager.cancel(alarm);alarm.cancel()}
        }
    }

    @Test fun cancelDeleteConfirmationLeavesRuleIntact() {
        val fixture=ShellFixture("automation-delete-cancel")
        val robot=ShellRobot("automation-delete-cancel")
        try {
            fixture.install(saveKey=false)
            AutomationStore(fixture.context,"${fixture.name}.db-tools.db-automations.db").use { store ->
                val rule=store.save(null,definition())
                fixture.launch().use { scenario ->
                    robot.openDrawer(scenario)
                    robot.click(robot.text(R.string.automations))
                    robot.awaitDrawerClosed(scenario)
                    robot.reveal("Удалить");robot.click("Удалить")
                    robot.waitFor("Журнал запусков хранится 30 дней.")
                    robot.click("Отмена")
                    val current=checkNotNull(store.rule(rule.id))
                    assertEquals(rule.revision,current.revision)
                    assertEquals(rule.definition.json,current.definition.json)
                    assertEquals(rule.enabled,current.enabled)
                }
            }
        } finally {fixture.remove()}
    }
}

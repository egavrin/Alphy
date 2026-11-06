package com.alpha.assistant.automations

import android.Manifest
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.AndroidCapabilities
import com.alpha.assistant.capabilities.CapabilityRequest
import com.alpha.assistant.capabilities.CapabilityResult
import com.alpha.assistant.capabilities.SyntheticOwnedCalendar
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationOneTimeWorkflowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val at=1893456000000L
    private fun action(id: String,kind: String,args: String) = CapabilityRequest(id,kind,JSONObject(args))
    private fun notice(id: String) = action(id,"notification.post","""{"title":"Fixture","text":"Local notice"}""")
    private fun field(id: String,value: Int) = action(id,"tool.state.set","""{"toolId":"target","revision":1,"field":"number","value":$value}""")
    private fun definition(vararg actions: CapabilityRequest) = JSONObject().put("name","One-time fixture")
        .put("trigger",JSONObject("""{"type":"notification","packages":["example.source"]}"""))
        .put("conditions",JSONObject()).put("actions",JSONArray(actions.map {it.json()}))
    private fun seed(tools: ToolStore): ToolRecord {
        val spec=ToolSpec.parse("""{"formatVersion":3,"title":"Target","description":"","capabilities":[],
            "stateSchema":{"number":"number","events":{"type":"list","maxItems":10,"fields":{"id":"string","text":"string"}}},
            "initialState":{"number":0,"events":[]},"ui":{"type":"text","text":"Fixture"},"source":"return {state:input.state};"}""")
        return ToolRecord("target",1,1,spec.json,spec.initialState,spec.ui.toString(),null,true).also {tools.commitTool(it,null,true)}
    }
    private class Device(val tools: ToolStore) {
        var allowed=true
        var readCount=0
        var calendar=JSONArray()
        var render: suspend (ToolRecord,String)->ToolPreview = {old,state -> ToolPreview(ToolSpec.parsePersisted(old.spec),state,old.ui)}
        var effect: suspend (CapabilityRequest,AutomationOneTime.Authority)->CapabilityResult = {_,_ -> CapabilityResult("succeeded","Fixture")}
        fun runner(at: Long)=AutomationOneTime(tools,{null},{if(allowed) null else "Access denied"},
            {readCount++; CapabilityResult("succeeded","Calendar read",JSONObject().put("events",calendar))},
            {old,state -> render(old,state)},{request,permit -> effect(request,permit)}, {at})
    }
    private suspend fun fixture(block: suspend (String,AutomationStore,Device)->Unit) {
        val name="one-time-${UUID.randomUUID()}.db"
        val toolName="tools-$name"
        try { AutomationStore(context,name).use {rules -> ToolStore(context,toolName).use {block(name,rules,Device(it))} } }
        finally { context.deleteDatabase(name);context.deleteDatabase(toolName) }
    }
    private suspend fun AutomationOneTime.propose(rules: AutomationStore,rule: AutomationRule) = prepare(rules,AutomationOneTime.request(rules,rule))
    private fun number(record: ToolRecord?)=JSONObject(checkNotNull(record).state).getInt("number")

    @Test fun exactSequentialPreviewAndConfirmationLeaveTheRuleDisabled(): Unit=runBlocking {
        fixture { _,rules,device ->
            val original=seed(device.tools)
            val runner=device.runner(at)
            val rule=rules.save(null,definition(field("first",1),field("second",2)))
            val proposal=runner.propose(rules,rule)
            assertEquals(listOf("0" to "1","1" to "2"),proposal.steps.map {it.before to it.after})
            assertEquals(original,device.tools.tool(original.id))
            assertTrue(rules.history(rule.id).isEmpty())
            val run=checkNotNull(runner.confirm(rules,proposal))
            assertEquals("succeeded",run.status)
            assertEquals(2,number(device.tools.tool(original.id)))
            assertEquals(3,device.tools.tool(original.id)?.stateVersion)
            val current=checkNotNull(rules.rule(rule.id))
            assertFalse(current.enabled)
            assertEquals(0,current.approvedRevision)
            assertNull(current.nextAt)
            assertNull(runner.confirm(rules,proposal))
            assertEquals(listOf(run),rules.history(rule.id))
        }
    }

    @Test fun cancellationAndChangedRuleTargetOrPermissionLeaveNoClaim(): Unit=runBlocking {
        fixture { _,rules,device ->
            val original=seed(device.tools)
            val runner=device.runner(at)
            val rule=rules.save(null,definition(field("change",1)))
            val cancelled=runner.propose(rules,rule)
            cancelled.cancel()
            assertNull(runner.confirm(rules,cancelled))
            val staleTarget=runner.propose(rules,rule)
            val updated=original.copy(stateVersion=2,state="""{"number":7,"events":[]}""")
            device.tools.commitTool(updated,original,false)
            assertTrue(runCatching {runner.confirm(rules,staleTarget)}.isFailure)
            val denied=runner.propose(rules,rule)
            device.allowed=false
            assertTrue(runCatching {runner.confirm(rules,denied)}.isFailure)
            device.allowed=true
            assertNull(runner.confirm(rules,denied))
            val invalidated=runner.propose(rules,rule)
            rules.attention(rule.id,rule.revision,"Access revoked")
            assertTrue(runCatching {runner.confirm(rules,invalidated)}.isFailure)
            val staleRule=runner.propose(rules,checkNotNull(rules.rule(rule.id)))
            rules.save(rule.id,definition(field("change",8)),rule.revision)
            assertTrue(runCatching {runner.confirm(rules,staleRule)}.isFailure)
            assertEquals(updated,device.tools.tool(original.id))
            assertTrue(rules.history(rule.id).isEmpty())
        }
    }

    @Test fun confirmationUsesFrozenCalendarInstancesAndNeverReadsAgain(): Unit=runBlocking {
        fixture { _,rules,device ->
            seed(device.tools)
            val first=JSONObject().put("id",42).put("title","Frozen one").put("start",at).put("end",at+60000)
            val second=JSONObject(first.toString()).put("title","Frozen two").put("start",at+3600000).put("end",at+3660000)
            device.calendar=JSONArray().put(first).put(second)
            val runner=device.runner(at)
            val read=action("source","calendar.read.rolling","""{"calendarId":9,"days":2,"zone":"UTC"}""")
            val write=action("write","tool.records.calendar","""{"toolId":"target","revision":1,"field":"events","source":"source"}""")
            val rule=rules.save(null,definition(read,write))
            val proposal=runner.propose(rules,rule)
            assertEquals(at,proposal.steps.first().readAt)
            assertEquals(at,proposal.steps.first().from)
            assertEquals(at+172800000,proposal.steps.first().to)
            assertTrue(proposal.steps.last().after.orEmpty().contains("Frozen two"))
            first.put("title","Changed after preview")
            device.calendar=JSONArray()
            val result=checkNotNull(runner.confirm(rules,proposal))
            assertEquals("succeeded",result.status)
            assertEquals(1,device.readCount)
            val rows=JSONObject(device.tools.tool("target")!!.state).getJSONArray("events")
            assertEquals(2,rows.length())
            assertNotEquals(rows.getJSONObject(0).getString("id"),rows.getJSONObject(1).getString("id"))
            assertTrue(rows.getJSONObject(0).getString("text").contains("Frozen one"))
            assertFalse(result.details.contains("\"id\":42"))
        }
    }

    @Test fun cancelDuringSuspendedEffectIsImmediateAndClaimSurvivesReopen(): Unit=runBlocking {
        fixture { name,rules,device ->
            val entered=CompletableDeferred<Unit>()
            val runner=device.runner(at)
            val rule=rules.save(null,definition(notice("first"),notice("second"),notice("third")))
            val proposal=runner.propose(rules,rule)
            val effects=mutableListOf<String>()
            device.effect={action,_ ->
                effects+=action.id
                if(action.id=="second") {entered.complete(Unit);awaitCancellation()}
                CapabilityResult("succeeded","Fixture")
            }
            val executing=async {runner.confirm(rules,proposal)}
            withTimeout(5000) {entered.await()}
            val outstanding=rules.history(rule.id).single()
            assertEquals("unknownOutcome",outstanding.status)
            assertTrue(outstanding.details.contains("first"))
            assertNull(runner.confirm(rules,proposal))
            proposal.cancel()
            withTimeout(5000) {executing.await()}
            assertEquals(listOf("first","second"),effects)
            AutomationStore(context,name).use {reopened ->
                assertEquals("unknownOutcome",reopened.occurrence(rule.id,outstanding.token)?.status)
                assertNull(AutomationExecutor(reopened,{null},{_,_->null}) {_,_,_->error("Replay")}
                    .run(rule.id,rule.revision,AutomationEvent("manual",outstanding.token)))
                assertFalse(reopened.active(rule.id,rule.revision))
            }
        }
    }

    @Test fun interruptedEffectCannotReplayAfterReopenEvenWhenTheRuleIsLaterEnabled(): Unit=runBlocking {
        fixture {name,rules,device ->
            val runner=device.runner(at)
            val rule=rules.save(null,definition(notice("external")))
            val proposal=runner.propose(rules,rule)
            val interruption=AssertionError("Injected process interruption after dispatch")
            var calls=0
            device.effect={_,_->calls++;throw interruption}
            assertSame(interruption,runCatching {runner.confirm(rules,proposal)}.exceptionOrNull())
            assertEquals(1,calls)
            AutomationStore(context,name).use {reopened ->
                val run=checkNotNull(reopened.occurrence(rule.id,proposal.token))
                assertEquals("unknownOutcome",run.status)
                assertFalse(reopened.active(rule.id,rule.revision))
                reopened.approve(rule.id,rule.revision)
                val scheduled=AutomationExecutor(reopened,{null},{_,_->null}) {_,_,_->calls++;CapabilityResult("succeeded","Fixture")}
                assertNull(scheduled.run(rule.id,rule.revision,AutomationEvent("manual",proposal.token)))
                assertEquals(1,calls)
                assertEquals(run,reopened.history(rule.id).single())
            }
        }
    }

    @Test fun invalidRenderStopsBeforeClaimAndLaterFailurePreservesCompletedActions(): Unit=runBlocking {
        fixture { _,rules,device ->
            val original=seed(device.tools)
            val normal=device.render
            val runner=device.runner(at)
            val rule=rules.save(null,definition(field("change",3),notice("notice")))
            device.render={old,state -> normal(old,state).copy(effects=listOf(notice("hidden")))}
            assertTrue(runCatching {runner.propose(rules,rule)}.isFailure)
            device.render={old,state -> normal(old,state).copy(records=JSONArray().put(JSONObject()))}
            assertTrue(runCatching {runner.propose(rules,rule)}.isFailure)
            assertEquals(original,device.tools.tool(original.id))
            assertTrue(rules.history(rule.id).isEmpty())
            device.render=normal
            device.effect={_,_->CapabilityResult("failed","Fixture failure")}
            val outcome=checkNotNull(runner.confirm(rules,runner.propose(rules,rule)))
            assertEquals("failed",outcome.status)
            assertEquals(3,number(device.tools.tool(original.id)))
            assertEquals(2,JSONArray(outcome.details).length())
            val external=rules.save(null,definition(action("external","link.open","""{"url":"https://example.com/path"}""")))
            device.effect={_,_->CapabilityResult("launched","Only the browser opened")}
            val proposal=runner.propose(rules,external)
            assertTrue(proposal.steps.single().target.contains("https://example.com/path"))
            assertEquals("launched",runner.confirm(rules,proposal)?.status)
        }
    }

    @Test fun savedCardShowsExactChangeAndExecutesWithNoCloudKeyOrProviderCall() {
        val fixture=ShellFixture("automation-one-time")
        val robot=ShellRobot("automation-one-time")
        try {
            fixture.install(saveKey=false)
            val toolsName="${fixture.name}.db-tools.db"
            ToolStore(fixture.context,toolsName).use {tools ->
                AutomationStore(fixture.context,"$toolsName-automations.db").use {rules ->
                    val original=seed(tools)
                    val rule=rules.save(null,definition(field("change",5)))
                    fixture.launch().use {scenario ->
                        robot.openDrawer(scenario)
                        robot.click(robot.text(R.string.automations))
                        robot.awaitDrawerClosed(scenario)
                        val testLabel=robot.text(R.string.automation_test_once)
                        robot.reveal(testLabel)
                        robot.click(testLabel)
                        robot.waitFor(robot.text(R.string.automation_test_title))
                        robot.waitFor(robot.text(R.string.automation_test_before,"0"))
                        robot.waitFor(robot.text(R.string.automation_test_after,"5"))
                        assertEquals(original,tools.tool(original.id))
                        robot.click(robot.text(R.string.automation_test_cancel))
                        assertTrue(rules.history(rule.id).isEmpty())
                        robot.click(testLabel)
                        robot.click(robot.text(R.string.automation_test_run))
                        robot.waitUntil {number(tools.tool(original.id))==5 && rules.history(rule.id).singleOrNull()?.status=="succeeded"}
                        assertEquals(0,fixture.provider.calls)
                        assertEquals(0,rules.rule(rule.id)?.approvedRevision)
                        assertFalse(checkNotNull(rules.rule(rule.id)).enabled)
                    }
                }
            }
        } finally {fixture.remove()}
    }

    @Test fun nativeCalendarReadRetainsRepeatingIdsOnlyForAutomationAndRespectsPermission(): Unit=runBlocking {
        SyntheticOwnedCalendar(context).use {fixture ->
            val event=context.contentResolver.insert(CalendarContract.Events.CONTENT_URI,ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID,fixture.id);put(CalendarContract.Events.TITLE,"Repeating fixture")
                put(CalendarContract.Events.DTSTART,at);put(CalendarContract.Events.DURATION,"PT1H")
                put(CalendarContract.Events.EVENT_TIMEZONE,"UTC");put(CalendarContract.Events.RRULE,"FREQ=DAILY;COUNT=2")
            })
            assertNotNull(event)
            val request=action("source","calendar.read","""{"calendarId":${fixture.id},"from":$at,"to":${at+172800000}}""")
            val native=AndroidCapabilities(context)
            val local= native.readAutomationCalendar(request)
            val miniApp=native.execute(request,false) {error("Calendar read cannot launch an app")}
            assertEquals("succeeded",local.status)
            assertEquals("succeeded",miniApp.status)
            val rows=local.data.getJSONArray("events")
            assertEquals(2,rows.length())
            assertEquals(rows.getJSONObject(0).getLong("id"),rows.getJSONObject(1).getLong("id"))
            assertNotEquals(rows.getJSONObject(0).getLong("start"),rows.getJSONObject(1).getLong("start"))
            val visible=miniApp.data.getJSONArray("events")
            repeat(rows.length()) {index ->
                assertFalse(visible.getJSONObject(index).has("id"))
                val expected=JSONObject(rows.getJSONObject(index).toString()).apply {remove("id")}
                assertEquals(expected.toString(),visible.getJSONObject(index).toString())
            }
            val denied=AndroidCapabilities(object: ContextWrapper(context) {
                override fun checkSelfPermission(permission: String)=if(permission==Manifest.permission.READ_CALENDAR)
                    PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
            })
            assertEquals("denied",denied.readAutomationCalendar(request).status)
        }
    }
}

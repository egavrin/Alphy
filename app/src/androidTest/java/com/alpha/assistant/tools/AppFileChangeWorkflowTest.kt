package com.alpha.assistant.tools

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage.AGENT
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_CHANGE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrevious
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan
import com.alpha.assistant.tools.appfile.row
import com.alpha.assistant.ui.FIXTURE_APP
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The change and the test and fix of a saved app with an app file, through the real ToolEngine, the real AppAgent, the real kit, and the real sandbox,
 * with scripted replies. One change call gives the lines that change, the kit computes the next state from the saved state,
 * and no way out loses, moves, or overwrites a saved value of the person.
 */
@RunWith(AndroidJUnit4::class)
class AppFileChangeWorkflowTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "appfile-change-${UUID.randomUUID()}.db"
    private val store = ToolStore(context, name)
    private var script = AgentScriptProvider(listOf(Scripted.Text("")))
    /** The model calls of the last change. */
    private val calls = CopyOnWriteArrayList<CloudRequest>()
    /** Runs at the start of each model call, inside it. */
    @Volatile private var duringCall: suspend (CloudRequest) -> Unit = {}
    private val service = ToolGenerationService(object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            calls += request
            duringCall(request)
            script.respond(key, request, session, emit)
        }
    }, SandboxClient(context))
    private val engine = ToolEngine(store, service)
    private val shopping get() = plan("shopping")
    private val prompt get() = AppFilePrompt.load(context)
    private val refused get() = context.getString(R.string.tool_generation_state)
    private val retry get() = context.getString(R.string.tool_generation_connection)

    @After fun remove() {
        runBlocking { engine.closeArtifacts() }
        store.close()
        context.deleteDatabase(store.automationDatabase)
        context.deleteDatabase(name)
    }

    private class Changed(val calls: List<CloudRequest>, events: List<ToolGenerationTraceEvent>, val result: Result<ToolRecord>) :
        TracedGeneration(calls, events) {
        val saved get() = result.getOrThrow()
        val failure get() = checkNotNull(result.exceptionOrNull()) { "The change was saved." }
    }

    /** A new app of [text] from one plan call, saved as the chat saves it. */
    private fun created(text: String = shopping, request: String = NEW): ToolRecord = runBlocking {
        script = AgentScriptProvider(listOf(Scripted.Text(text)))
        val draft = engine.createDraft(request, null)
        engine.generate(draft.id, "fixture", emptyList(), CloudSession(), null, null, trace = null, planStep = AppFileLimits())
        engine.accept(draft.id)
    }.also { assertEquals(text, ToolSpec.parsePersisted(it.spec).appFile) }

    /** [record] after one press of the person. */
    private fun pressed(record: ToolRecord, actionId: String, value: Any?) =
        runBlocking { engine.interact(record.id, record.revision, record.stateVersion, actionId, value) }

    /** The shopping list after the steps of the kit test: Bread (r2) is gone, and Milk (r1) is bought. */
    private fun shoppingWithData() = pressed(pressed(created(), "items_remove", row("r2", null)), "items_done", row("r1", true))

    /** The change of [base] by [request] with [replies], as the chat sends it: a draft, its generation, and its save. */
    private fun change(base: ToolRecord, replies: List<Scripted>, request: String = CHANGE, session: CloudSession = CloudSession()): Changed {
        script = AgentScriptProvider(replies)
        calls.clear()
        val operation = UUID.randomUUID()
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val result = runBlocking {
            runCatching {
                engine.createDraft(request, base.id, operationId = operation.toString(), expected = base)
                engine.generate(operation.toString(), "fixture", emptyList(), session, null, null, ToolGenerationTrace(operation, { events += it }))
                engine.accept(operation.toString())
            }
        }
        return Changed(calls.toList(), events, result)
    }

    /** The person sees [text], which says why nothing changed, and the store holds [before] with no new revision. No loop step ran. */
    private fun assertRefused(changed: Changed, before: ToolRecord, code: String = "widget_state", text: String = refused) {
        val failure = changed.failure
        assertEquals(failure.toString(), code, (failure as? CloudFailure)?.code)
        assertEquals(text, failure.message)
        assertEquals(before, store.tool(before.id))
        assertEquals(before.revision, store.revisions(before.id).size)
        assertTrue(changed.stages.toString(), AGENT !in changed.stages)
        assertTrue(changed.events.none { it is ToolGenerationTraceEvent.AgentStep })
    }

    private fun items(record: ToolRecord) = JSONObject(record.state).getJSONArray("items").let { list -> List(list.length()) { list.getJSONObject(it) } }

    private fun assertSameRows(expected: List<JSONObject>, actual: List<JSONObject>) =
        assertTrue("$expected\n$actual", sameToolJson(JSONArray(expected), JSONArray(actual)))

    // Failure case: a change of a list wrote the start rows again, so the rows of the person were lost.
    @Test fun aChangeByLinesKeepsTheRowsOfAList() {
        val before = shoppingWithData()
        val changed = change(before, listOf(Scripted.Text(BOUGHT)))
        val call = changed.calls.single()
        assertEquals(APP_FILE_CHANGE, call.generationStage)
        assertFalse(call.jsonObject)
        assertEquals(prompt.system, call.systemInstructions)
        assertEquals(listOf(prompt.changeText(CHANGE, shopping)), call.turns.map { it.text })
        assertEquals(listOf("change_call_start", "plan_first_text", "plan_reply_end", "compile", "plan_outcome"), changed.phases.map { it.name })
        assertEquals(listOf("ok"), changed.compiles)
        assertEquals("accepted", changed.outcome)
        val saved = changed.saved
        assertEquals(2, saved.revision)
        assertEquals(shopping + BOUGHT + "\n", ToolSpec.parsePersisted(saved.spec).appFile)
        // The rows and the marks of the person stay with their ids. Milk is bought: one bought, one left.
        assertSameRows(items(before), items(saved))
        assertEquals(listOf("r1", "r3"), items(saved).map { it.getString("id") })
        val d = JSONObject(saved.state).getJSONObject("d")
        assertEquals(listOf("1", "1"), listOf(d.getString("bought_text"), d.getString("left_text")))
        assertEquals(saved, store.tool(saved.id))
        assertEquals(2, store.revisions(saved.id).size)
        assertTrue(changed.events.none { it is ToolGenerationTraceEvent.AgentStep })
    }

    @Test fun aChangeThatAddsAColumnFillsEachRow() {
        val before = shoppingWithData()
        val changed = change(before, listOf(Scripted.Text(URGENT)))
        assertEquals("accepted", changed.outcome)
        // Each saved row keeps its values and gets the start value of the new column: no check mark.
        assertSameRows(items(before).map { it.put("urgent", false) }, items(changed.saved))
    }

    @Test fun faultsThenACleanRepairAreAccepted() {
        val before = shoppingWithData()
        val changed = change(before, listOf(Scripted.Text(BROKEN), Scripted.Text("The fix:\n```\n$BOUGHT\n```")))
        assertEquals(listOf(APP_FILE_CHANGE, APP_FILE), changed.stages)
        val repair = changed.calls[1].turns.single().text
        // The repair call gets the change task, the merged plan, and its faults.
        assertTrue(repair, repair.startsWith(prompt.changeText(CHANGE, shopping)) && (shopping + BROKEN) in repair && "\n- need bought" in repair)
        assertEquals(prompt.system, changed.calls[1].systemInstructions)
        assertEquals(listOf("faults", "ok"), changed.compiles)
        assertEquals(1, changed.phases.count { it.name == "repair_call_start" })
        assertEquals("accepted", changed.outcome)
        assertEquals(shopping + BOUGHT + "\n", ToolSpec.parsePersisted(changed.saved.spec).appFile)
        assertSameRows(items(before), items(changed.saved))
    }

    // Failure case: a change that the saved rows block was sent to a repair call, or it replaced the rows.
    @Test fun aRefusedChangeWithStoredDataChangesNothing() {
        val before = shoppingWithData()
        val changed = change(before, listOf(Scripted.Text(AISLE_NUMBER)) + LOOP)
        // No repair call: a model cannot fix the saved data.
        assertEquals(listOf(APP_FILE_CHANGE), changed.stages)
        assertEquals(listOf("faults"), changed.compiles)
        assertEquals("fallback:data_blocks", changed.outcome)
        assertRefused(changed, before)
    }

    /** [record] saved with a plan that has a column which its schema and rows do not have, so its state does not fit its plan. */
    private fun mismatched(record: ToolRecord) = record.copy(spec = ToolSpec.parsePersisted(record.spec)
        .withAppFile(checkNotNull(appFile(record)).replace("done: \"check\"}", "done: \"check\", urgent: \"check\"}")).json)
        .also { store.saveTool(it, false) }

    @Test fun aStoredStateThatDoesNotMatchItsAppFileChangesNothing() {
        val before = mismatched(shoppingWithData())
        val changed = change(before, listOf(Scripted.Text(BOUGHT)) + LOOP)
        assertEquals(listOf(APP_FILE_CHANGE), changed.stages)
        assertEquals("fallback:data_blocks", changed.outcome)
        assertRefused(changed, before)
    }

    // Failure case: an app with saved data went to the loop, which can write the app again with edit_app or write_app.
    @Test fun anAppWithAppFileNeverGetsEditApp() {
        val before = shoppingWithData()
        val ways = listOf(
            "fallback:faults" to listOf(Scripted.Text(BROKEN), Scripted.Text(BROKEN)),
            "fallback:outside" to listOf(Scripted.Text("outside(\"game\")")),
            "fallback:outside" to listOf(Scripted.Text(BROKEN), Scripted.Text("outside(\"game\")")),
        )
        for ((outcome, replies) in ways) {
            val changed = change(before, replies + LOOP)
            assertEquals("$replies", outcome, changed.outcome)
            assertRefused(changed, before)
        }
    }

    // Failure case: after a failed change call, the person read that the change does not fit the saved data, so the person did not retry.
    @Test fun aFailedChangeCallSaysToRetry() {
        val before = shoppingWithData()
        val changed = change(before, listOf(Scripted.Fails("pre_headers_io")) + LOOP)
        assertEquals("fallback:call_failed", changed.outcome)
        assertRefused(changed, before, "widget_generation", retry)
    }

    // Failure case: the long-decimal check ran only in finish of the loop, so a changed app could show 3.3333333333333335.
    @Test fun aChangeThatShowsALongDecimalChangesNothing() {
        val third = "app(\"Third\", lang=\"en\")\nneed a = input(\"number\", \"A\", 85, min=0, max=100)\n" +
            "need b = input(\"number\", \"B\", 4, min=1, max=10)\n\$/r := \$/a / \$/b\nroot = Column([a_field, b_field, Text(text(\$/r))])\n"
        val before = pressed(created(third, "User request: Divide A by B\nWidget brief: A divides by B"), "a_field", 10)
        // B still has its start value, so it takes the new start value 3: 10 / 3 is a long decimal.
        val changed = change(before, listOf(Scripted.Text("need b = input(\"number\", \"B\", 3, min=1, max=10)")) + LOOP)
        assertEquals(listOf("ok"), changed.compiles)
        assertEquals("fallback:host_check", changed.outcome)
        assertRefused(changed, before)
    }

    @Test fun aSlowChangeCallEndsAtItsLimit() {
        val before = shoppingWithData()
        val changed = change(before, listOf(Scripted.Waits(30_000, BOUGHT)) + LOOP)
        assertEquals("fallback:first_text_timeout", changed.outcome)
        // The change call has the limits of a plan call: 20 s to the first text.
        val ms = changed.msBetween("change_call_start", "plan_outcome")
        assertTrue("$ms ms", ms in 20_000..21_500)
        assertRefused(changed, before, "widget_generation", retry)
    }

    @Test fun aStopDuringTheChangeCallEndsTheRequest() {
        val before = shoppingWithData()
        val session = CloudSession()
        // The person stops the request while the change call runs. A provider then ends its call with the cancel of the session.
        duringCall = { if (it.generationStage == APP_FILE_CHANGE) { session.cancel(); session.check() } }
        val changed = change(before, listOf(Scripted.Text(BOUGHT)) + LOOP, session = session)
        assertEquals("cancelled", (changed.failure as CloudFailure).code)
        assertEquals(listOf(APP_FILE_CHANGE), changed.stages)
        assertTrue(changed.phases.none { it.name == "plan_outcome" })
        assertEquals(before, store.tool(before.id))
        assertEquals(1, store.revisions(before.id).size)
    }

    // Failure case: a change with no new line saved a new revision of the same app.
    @Test fun aChangeThatChangesNoLineSavesNothing() {
        val before = shoppingWithData()
        val changed = change(before, listOf(Scripted.Text(shopping.lines()[1])) + LOOP)
        assertEquals(listOf(APP_FILE_CHANGE), changed.stages)
        assertEquals("fallback:empty", changed.outcome)
        val failure = changed.failure as CloudFailure
        assertEquals(context.getString(R.string.tool_generation_unchanged), failure.message)
        assertEquals(before, store.tool(before.id))
        assertEquals(1, store.revisions(before.id).size)
    }

    // Failure case: an app that the person never used could not change when the kit could not serve the change.
    @Test fun aRefusedChangeWithNoStoredDataWritesANewApp() {
        val before = created()
        val changed = change(before, listOf(Scripted.Text("outside(\"game\")")) + LOOP)
        assertEquals(listOf(APP_FILE_CHANGE, AGENT, AGENT), changed.stages)
        assertEquals("fallback:outside", changed.outcome)
        // The loop builds a new app: it gets no plan call, no kit source, and no definition of the saved app.
        assertEquals(1, changed.phases.count { it.name == "plan_outcome" })
        assertTrue(changed.phases.none { it.name == "plan_call_start" })
        val task = changed.calls[1].turns.first().text
        assertTrue(task, "Build a new app." in task && "Current app definition" !in task && shopping.lines()[2] !in task)
        val saved = changed.saved
        assertEquals(2, saved.revision)
        assertEquals("Fixture", ToolSpec.parsePersisted(saved.spec).title)
        assertNull(ToolSpec.parsePersisted(saved.spec).appFile)
        assertEquals(2, store.revisions(saved.id).size)
    }

    // Failure case: the open of a new app wrote a state, so its first change counted as data of the person.
    @Test fun aNewAppHasNoStoredDataUntilAPress() {
        val replies = listOf(Scripted.Text(BROKEN), Scripted.Text(BROKEN)) + LOOP
        val opened = created().also { runBlocking { engine.openArtifact(it) } }
        val unused = change(checkNotNull(store.tool(opened.id)), replies)
        assertEquals(listOf(APP_FILE_CHANGE, APP_FILE, AGENT, AGENT), unused.stages)
        assertNull(ToolSpec.parsePersisted(unused.saved.spec).appFile)
        // One press of the person makes data that a change must keep.
        val used = created().also { runBlocking { engine.openArtifact(it) } }.let { pressed(it, "items_done", row("r1", true)) }
        assertRefused(change(used, replies), used)
    }

    // Failure case: a change that removed a list kept its rows only in the start state, so the app looked unused and a failed change wrote a new app.
    @Test fun aFailedChangeKeepsTheRowsOfARemovedList() {
        val before = change(shoppingWithData(), listOf(Scripted.Text(REMOVE_LIST))).saved
        assertEquals(listOf("r1", "r3"), items(before).map { it.getString("id") })
        val changed = change(before, listOf(Scripted.Text(BROKEN), Scripted.Text(BROKEN)) + LOOP)
        assertEquals("fallback:faults", changed.outcome)
        assertRefused(changed, before)
    }

    // Failure case: a press during the change call was lost, because the save wrote the state from before the press.
    /** The person marks Apples (r3) as bought while the change call of [before] runs. Gives the record after that press. */
    private fun pressDuringTheChangeCall(before: ToolRecord): () -> ToolRecord {
        var newer: ToolRecord? = null
        duringCall = {
            if (it.generationStage == APP_FILE_CHANGE) newer = engine.interact(before.id, before.revision, before.stateVersion, "items_done", row("r3", true))
        }
        return { checkNotNull(newer) }
    }

    @Test fun aPressDuringTheChangeCallKeepsTheNewerState() {
        val before = shoppingWithData()
        val newer = pressDuringTheChangeCall(before)
        val changed = change(before, listOf(Scripted.Text(BOUGHT)))
        assertEquals("accepted", changed.outcome)
        assertTrue(changed.failure.toString(), changed.failure is IllegalStateException)
        assertEquals("Инструмент изменился. Открой текущую версию.", changed.failure.message)
        // The store holds the press, and no revision of the change.
        assertEquals(newer(), store.tool(before.id))
        assertEquals(listOf(true, true), items(checkNotNull(store.tool(before.id))).map { it.getBoolean("done") })
        assertEquals(1, store.revisions(before.id).size)
    }

    /** Test and fix of [base] by the report FIX with [replies], as the chat runs it. calls holds its model calls. */
    private fun testFix(base: ToolRecord, replies: List<Scripted>): TestFixResult {
        script = AgentScriptProvider(replies)
        calls.clear()
        return runBlocking { service.testAndFix("fixture", FIX, base, false, CloudSession()) }
    }

    /** The save of a verified repair, as the chat saves it. */
    private fun saveFix(base: ToolRecord, result: TestFixResult) =
        runBlocking { engine.acceptTestFix(UUID.randomUUID().toString(), "chat", base, FIX, checkNotNull(result.candidate)) { true } }

    // Failure case: test and fix of an app with an app file went to the loop, which edits the kit source and can drop the rows.
    @Test fun aTestFixByLinesSavesAVerifiedRepair() {
        val before = shoppingWithData()
        val result = testFix(before, listOf(Scripted.Text(BOUGHT)) + LOOP)
        assertEquals(result.detail, TestFixStatus.VERIFIED, result.status)
        assertEquals("Shopping list", result.detail)
        // No run reproduced the report, so the chat does not say that the version was checked.
        assertFalse(result.checkedByRun)
        // The report of the person is the request of one change call.
        assertEquals(listOf(APP_FILE_CHANGE), calls.map { it.generationStage })
        assertEquals(listOf(prompt.changeText(FIX, shopping)), calls.single().turns.map { it.text })
        val saved = saveFix(before, result)
        assertEquals(2, saved.revision)
        assertEquals(shopping + BOUGHT + "\n", ToolSpec.parsePersisted(saved.spec).appFile)
        assertSameRows(items(before), items(saved))
        assertEquals(2, store.revisions(saved.id).size)
    }

    /** Test and fix of [before] with the change [reply] ends with [status] and [detail] after one call and no loop step. The store keeps the app. */
    private fun assertTestFixKeepsTheApp(reply: Scripted, status: TestFixStatus, detail: String, before: ToolRecord = shoppingWithData()) {
        val result = testFix(before, listOf(reply) + LOOP)
        assertEquals(result.detail, status, result.status)
        assertEquals(detail, result.detail)
        assertNull(result.candidate)
        assertEquals(listOf(APP_FILE_CHANGE), calls.map { it.generationStage })
        assertEquals(before, store.tool(before.id))
        assertEquals(1, store.revisions(before.id).size)
    }

    // Failure case: a test and fix that changed no line said that the checks passed, but no check ran.
    @Test fun aTestFixWithNoLineIsUnverified() = assertTestFixKeepsTheApp(Scripted.Text(shopping.lines()[1]), TestFixStatus.UNVERIFIED,
        context.getString(R.string.tool_test_fix_no_line))

    @Test fun aRefusedTestFixWithStoredDataIsUnrepaired() = assertTestFixKeepsTheApp(Scripted.Text(AISLE_NUMBER), TestFixStatus.UNREPAIRED, refused)

    // Failure case: with no data of the person, a refused test and fix went to a loop with no app to run, which made 20 calls for nothing.
    @Test fun aRefusedTestFixWithNoStoredDataIsUnrepaired() =
        assertTestFixKeepsTheApp(Scripted.Text("outside(\"game\")"), TestFixStatus.UNREPAIRED, refused, created())

    @Test fun aTestFixWithAFailedCallSaysToRetry() =
        assertTestFixKeepsTheApp(Scripted.Fails("pre_headers_io"), TestFixStatus.UNREPAIRED, retry, created())

    // Failure case: a press during the change call of test and fix was lost, because the save wrote the state from before the press.
    @Test fun aPressDuringATestFixByLinesKeepsTheNewerState() {
        val before = shoppingWithData()
        val newer = pressDuringTheChangeCall(before)
        val result = testFix(before, listOf(Scripted.Text(BOUGHT)))
        assertEquals(result.detail, TestFixStatus.VERIFIED, result.status)
        val failure = runCatching { saveFix(before, result) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is TestFixNotSaved)
        assertEquals(newer(), store.tool(before.id))
        assertEquals(1, store.revisions(before.id).size)
    }

    private fun appFile(record: ToolRecord) = ToolSpec.parsePersisted(record.spec).appFile

    /** [record] with [state] and [ui], as a write of the person commits it. */
    private fun written(record: ToolRecord, state: String, ui: String = record.ui) =
        record.copy(stateVersion = record.stateVersion + 1, state = state, ui = ui).also { store.saveTool(it, false) }

    /** [record] after the person typed [name] in the add form of the list and pressed Add. Both events go through the real run-time. */
    private fun added(record: ToolRecord, name: String): ToolRecord = runBlocking {
        val spec = ToolSpec.parsePersisted(record.spec)
        val typed = service.evaluate(spec, record.id, record.revision, record.state, liveInputEvent("items_new_name", name), record.ui)
        val add = JSONObject().put("type", "action").put("actionId", "items_add").put("value", JSONObject.NULL)
        val pressed = service.evaluate(spec, record.id, record.revision, typed.state, add, typed.ui)
        written(record, pressed.state, pressed.ui)
    }

    /** The restore of version [target] on [record], as the list of versions starts it after its check. */
    private fun restore(record: ToolRecord, target: Int): Result<ToolRecord> {
        val checked = runBlocking { engine.restoreAvailability(record, target) }
        return runCatching { runBlocking { engine.rollback(record.id, record.revision, record.stateVersion, target) } }
            .also { assertEquals("$checked\n$it", checked.available, it.isSuccess) }
    }

    /** The list of versions and the restore of [target] both give the message of widget_state, and the store keeps [before]. */
    private fun assertRestoreRefused(before: ToolRecord, target: Int) {
        val revisions = store.revisions(before.id).size
        assertEquals(ToolRestoreAvailability(refused, "widget_state"), runBlocking { engine.restoreAvailability(before, target) })
        val failure = restore(before, target).exceptionOrNull()
        assertEquals(failure.toString(), "widget_state", (failure as? CloudFailure)?.code)
        assertEquals(refused, failure?.message)
        assertEquals(before, store.tool(before.id))
        assertEquals(revisions, store.revisions(before.id).size)
    }

    // Failure case: a restore put the old plan beside the schema of the current version, so the plan and the code differed and a later change lost the rows.
    @Test fun aRestoreOfAnOlderPlanKeepsTheRowsOfThePerson() {
        val first = added(shoppingWithData(), "Butter")
        val second = pressed(change(first, listOf(Scripted.Text(BOUGHT))).saved, "items_done", row("r3", true))
        val restored = restore(second, 1).getOrThrow()
        assertEquals(3, restored.revision)
        assertEquals(shopping, appFile(restored))
        // The rows of the person stay with their ids and marks: Bread is gone, Milk and Apples are bought, and Butter is the row that the person added.
        assertSameRows(items(second), items(restored))
        assertEquals(listOf("r1", "r3", "r4"), items(restored).map { it.getString("id") })
        assertEquals("Butter", items(restored).last().getString("name"))
        assertEquals(restored, store.tool(second.id))
        assertEquals("Восстановление v1", store.revisions(second.id).first().label)
        // The plan and the code of the restored version agree, so a later change by lines works and keeps the rows.
        val renamed = change(restored, listOf(Scripted.Text(STILL_TO_BUY))).saved
        assertSameRows(items(second), items(renamed))
        // A restore is a new revision, so the person restores the version with the count again. The kit keeps the rows on the way back too.
        val back = restore(renamed, 2).getOrThrow()
        assertEquals(5, back.revision)
        // The restore builds the definition from the plan as the change step built it.
        assertTrue(back.spec, sameToolJson(JSONObject(second.spec), JSONObject(back.spec)))
        assertSameRows(items(second), items(back))
        assertEquals("2", JSONObject(back.state).getJSONObject("d").getString("bought_text"))
        assertEquals(5, store.revisions(second.id).size)
    }

    // Failure case: a restore of a plan with no column that the saved rows hold dropped the column, or it changed the app.
    @Test fun aRestoreThatASavedColumnBlocksChangesNothing() {
        val urgent = change(shoppingWithData(), listOf(Scripted.Text(URGENT))).saved
        assertRestoreRefused(urgent, 1)
    }

    // Failure case: a restore between an app of today's form and a part-built version put one schema beside the other code, so the data did not fit.
    @Test fun aRestoreBetweenTheTwoFormsNeedsAnAppWithNoData() {
        val planned = created()
        val today = change(planned, listOf(Scripted.Text("outside(\"game\")")) + LOOP).saved
        assertNull(appFile(today))
        // With a value of the person, neither form takes the other.
        assertRestoreRefused(written(today, """{"count":5}"""), 1)
        // With no value of the person, each form comes back as it was saved, with its own start state.
        val back = restore(written(checkNotNull(store.tool(today.id)), today.state), 1).getOrThrow()
        assertEquals(shopping, appFile(back))
        assertTrue(back.state, sameToolJson(JSONObject(planned.state), JSONObject(back.state)))
        val again = restore(back, 2).getOrThrow()
        assertNull(appFile(again))
        assertEquals("Fixture", ToolSpec.parsePersisted(again.spec).title)
        assertTrue(again.state, sameToolJson(JSONObject(today.state), JSONObject(again.state)))
        val used = pressed(restore(again, 3).getOrThrow(), "items_done", row("r1", true))
        assertRestoreRefused(used, 4)
        assertEquals(5, store.revisions(used.id).size)
    }

    // Failure case: the list of versions offered a part-built version to an app with a collection, and the commit then refused the restore.
    @Test fun aRestoreBetweenTheTwoFormsRefusesAnAppWithACollection() {
        val records = JSONObject(FIXTURE_APP).put("collections", JSONObject().put("history",
            JSONObject().put("type", "object").put("fields", JSONObject().put("at", "number")))).toString()
        val today = change(created(), listOf(Scripted.Text("outside(\"game\")"), Scripted.Text(agentWrite(records)),
            Scripted.Text(agentFixtureFinish))).saved
        assertEquals(1, ToolSpec.parsePersisted(today.spec).collections.length())
        // The state has no value of the person, but a collection is data of the person: it keeps their records outside the state.
        assertTrue(sameToolJson(JSONObject(today.state), JSONObject(ToolSpec.parsePersisted(today.spec).initialState)))
        assertRestoreRefused(today, 1)
    }

    /**
     * The shopping list built again in today's form (version 2), its plan restored with no value (version 3), Milk marked when [mark]
     * is true, and a change by lines that removes the list (version 4). Gives versions 2 and 4.
     */
    private fun aListRemovedAfterTodaysForm(mark: Boolean): Pair<ToolRecord, ToolRecord> {
        val today = change(created(), listOf(Scripted.Text("outside(\"game\")")) + LOOP).saved
        val planned = restore(today, 1).getOrThrow().let { if (mark) pressed(it, "items_done", row("r1", true)) else it }
        return today to change(planned, listOf(Scripted.Text(REMOVE_LIST))).saved
    }

    // Failure case: a change that removed a list kept its rows only in the start state, so the app looked unused and a restore to today's form dropped the rows.
    @Test fun aRestoreToTodaysFormKeepsTheRowsOfARemovedList() {
        val removed = aListRemovedAfterTodaysForm(mark = true).second
        // The app keeps the rows with the mark of the person, and its start state has no row.
        assertEquals(listOf(true, false, false), items(removed).map { it.getBoolean("done") })
        assertEquals(0, JSONObject(ToolSpec.parsePersisted(removed.spec).initialState).getJSONArray("items").length())
        assertRestoreRefused(removed, 2)
    }

    @Test fun aRestoreToTodaysFormTakesAnUntouchedAppWithARemovedList() {
        val (today, removed) = aListRemovedAfterTodaysForm(mark = false)
        val back = restore(removed, 2).getOrThrow()
        assertNull(appFile(back))
        assertTrue(back.state, sameToolJson(JSONObject(today.state), JSONObject(back.state)))
    }

    /**
     * The list of versions gives the generic text of a failed check for [target], the restore fails with a text that the person does not see, and the store keeps [before].
     * Gives the failure of the restore.
     */
    private fun assertRestoreFailed(before: ToolRecord, target: Int): Throwable? {
        val revisions = store.revisions(before.id).size
        assertEquals(ToolRestoreAvailability(CHECK_FAILED), runBlocking { engine.restoreAvailability(before, target) })
        val failure = restore(before, target).exceptionOrNull()
        // ToolController.run shows its generic text for a failure with no Russian message.
        assertTrue(failure.toString(), failure is IllegalStateException && failure.message.orEmpty().startsWith("The kit did not compile"))
        assertEquals(before, store.tool(before.id))
        assertEquals(revisions, store.revisions(before.id).size)
        return failure
    }

    /** The compile of [text] with [record] as the stored app, as the restore and the manual edit compile it. */
    private fun compiled(record: ToolRecord, text: String) = runBlocking {
        AppFileCompiler(context, SandboxClient(context)).compile(text,
            AppFilePrevious(checkNotNull(appFile(record)), ToolSpec.parsePersisted(record.spec).schema.toString(), record.state))
    }

    /** The shopping plan with [HOLE] and its view. The kit compiles it on [record] with no fault and with hole true, so only the hole check of the host stops it. */
    private fun builtHole(record: ToolRecord): String = "$shopping$HOLE\n$HOLE_VIEW\n".also { text ->
        val compilation = compiled(record, text)
        assertEquals(compilation.faults.toString(), listOf("ok", true), listOf(compilation.status, compilation.hole))
    }

    // Failure case: a version whose plan the kit of today does not take came back with code that its plan does not make.
    @Test fun aRestoreOfAPlanThatTheKitRefusesChangesNothing() {
        val base = shoppingWithData()
        // Versions 2 and 3 hold plans that an older kit could take: one with a fault and one with a part that the kit does not have.
        for ((revision, text) in listOf(2 to shopping + BROKEN + "\n", 3 to shopping + HOLE + "\n"))
            store.saveTool(base.copy(revision = revision, spec = ToolSpec.parsePersisted(base.spec).withAppFile(text).json), true)
        val current = base.copy(revision = 4).also { store.saveTool(it, true) }
        assertRestoreFailed(current, 2)
        assertRestoreFailed(current, 3)
    }

    // Failure case: a hole with its own view compiles clean, so a restore of a version with one brought back a part that the kit does not have.
    @Test fun aRestoreOfAPlanWithABuiltHoleChangesNothing() {
        val base = shoppingWithData()
        // An older kit could have taken this version, as in aRestoreOfAPlanThatTheKitRefusesChangesNothing.
        store.saveTool(base.copy(revision = 2, spec = ToolSpec.parsePersisted(base.spec).withAppFile(builtHole(base)).json), true)
        val failure = assertRestoreFailed(base.copy(revision = 3).also { store.saveTool(it, true) }, 2)
        // The status is ok, so the hole check stopped the restore.
        assertEquals(HOLE_REFUSED, failure?.message)
    }

    // Failure case: a stored state that its own plan does not give went to the kit, which could put a value on another line.
    @Test fun aStoredStateThatDoesNotFitItsPlanBlocksARestoreAndAManualEdit() {
        val changed = change(shoppingWithData(), listOf(Scripted.Text(BOUGHT))).saved
        val before = mismatched(changed)
        assertRestoreRefused(before, 1)
        // The clean plan of the app: the kit checks the stored state only after the new plan compiles.
        assertEditRefused(before, JSONObject(before.spec).put("appFile", appFile(changed)).toString(), refused, "widget_state")
    }

    // Failure case: a restore or a manual edit while an open app held the sandbox saved a plan that the kit did not check.
    @Test fun aBusySandboxStopsARestoreAndAManualEdit() {
        val before = change(shoppingWithData(), listOf(Scripted.Text(BOUGHT))).saved
        val slot = AppFileLoopWorkflowTest.Slot(context)
        try {
            assertRestoreFailed(before, 1)
            // The dialog shows its generic text for each failure that is not a refusal of the plan.
            val failure = edited(before, JSONObject(before.spec).put("appFile", shopping).toString()).exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalStateException)
            assertEquals(before, store.tool(before.id))
            assertEquals(2, store.revisions(before.id).size)
        } finally {
            runBlocking { slot.free() }
        }
        // With the sandbox free again, the same restore works.
        assertEquals(3, restore(before, 1).getOrThrow().revision)
    }

    /** The manual edit of [record] with the text [definition], as the dialog saves it. */
    private fun edited(record: ToolRecord, definition: String) =
        runCatching { runBlocking { engine.saveManualDefinition(record, definition) } }

    /** The manual edit of [before] with [definition] gives [text] with [code], and the store keeps [before]. */
    private fun assertEditRefused(before: ToolRecord, definition: String, text: String, code: String = APP_FILE_EDIT) {
        val revisions = store.revisions(before.id).size
        val failure = edited(before, definition).exceptionOrNull()
        assertEquals(failure.toString(), code, (failure as? CloudFailure)?.code)
        assertEquals(text, failure?.message)
        assertEquals(before, store.tool(before.id))
        assertEquals(revisions, store.revisions(before.id).size)
    }

    // Failure case: a manual edit of the plan of a part-built app failed on each save, because the kit did not compute the next state.
    @Test fun aManualEditOfTheAppFileThatAddsANeedKeepsTheStoredValues() {
        val before = added(shoppingWithData(), "Butter")
        val saved = edited(before, JSONObject(before.spec).put("appFile", shopping + BOUGHT + "\n").toString()).getOrThrow()
        assertEquals(2, saved.revision)
        assertEquals(shopping + BOUGHT + "\n", appFile(saved))
        assertSameRows(items(before), items(saved))
        assertEquals("1", JSONObject(saved.state).getJSONObject("d").getString("bought_text"))
        assertEquals(saved, store.tool(before.id))
        assertEquals("Ручное изменение", store.revisions(before.id).first().label)
        // The same values in another layout: the kit builds the same definition and the same state as the change step did.
        val same = edited(saved, JSONObject(saved.spec).toString(2)).getOrThrow()
        assertEquals(3, same.revision)
        assertTrue(same.spec, sameToolJson(JSONObject(saved.spec), JSONObject(same.spec)))
        assertTrue(same.state, sameToolJson(JSONObject(saved.state), JSONObject(same.state)))
    }

    // Failure case: a manual edit of a compiled field saved code that the plan does not make, so the next change by lines lost the edit or was refused.
    @Test fun aManualEditOfACompiledFieldIsRefused() {
        val before = shoppingWithData()
        val sentence = context.getString(R.string.tool_manual_edit_app_file)
        val other = listOf(JSONObject(before.spec).put("title", "Groceries"), JSONObject(before.spec).apply { remove("appFile") },
            JSONObject(before.spec).put("title", "Groceries").put("appFile", shopping + BOUGHT + "\n"))
        for (definition in other) assertEditRefused(before, definition.toString(), sentence)
        // Only the kit writes an app file, so an app of today's form takes none by hand.
        val today = change(created(), listOf(Scripted.Text("outside(\"game\")")) + LOOP).saved
        assertEditRefused(today, JSONObject(today.spec).put("appFile", shopping).toString(), sentence)
    }

    // Failure case: a manual edit that breaks the plan gave a text that names no line, so the person could not fix the plan.
    @Test fun aManualEditThatBreaksThePlanShowsTheFaultOfTheKit() {
        val before = shoppingWithData()
        // A line with a fault, and a hole with no view (a part that the kit does not have): the person sees each fault of the kit with its line.
        for ((line, start) in listOf(BROKEN to "need bought", HOLE to "hole chart")) {
            val text = shopping + line + "\n"
            val faults = compiled(before, text).faults
            assertTrue(faults.toString(), faults.isNotEmpty() && faults.all { it.startsWith(start) })
            assertEditRefused(before, JSONObject(before.spec).put("appFile", text).toString(), faults.joinToString("\n"))
        }
        // A plan that the saved rows block gives the message of widget_state.
        val aisle = shopping.replace(shopping.lines()[2], AISLE_NUMBER)
        assertEditRefused(before, JSONObject(before.spec).put("appFile", aisle).toString(), refused, "widget_state")
    }

    // Failure case: a manual edit saved a plan with a hole and its own view, a part that the kit does not have.
    @Test fun aManualEditWithABuiltHoleChangesNothing() {
        val before = shoppingWithData()
        val failure = edited(before, JSONObject(before.spec).put("appFile", builtHole(before)).toString()).exceptionOrNull()
        // The hole check stops the clean compile. It is not a refusal of the plan, so the dialog shows its generic text.
        assertTrue(failure.toString(), failure is IllegalStateException && failure.message == HOLE_REFUSED)
        assertEquals(before, store.tool(before.id))
        assertEquals(1, store.revisions(before.id).size)
    }

    private companion object {
        const val FIX = "The bought items are not counted. Test and fix this app."
        const val NEW ="User request: Make a shopping list by aisle\nWidget brief: A shopping list sorted by aisle"
        const val CHANGE = "User request: Count the bought items too\nWidget brief: Add a count of the bought items"
        /** A change of the label of the count of the shopping list. */
        const val STILL_TO_BUY = "need left = figure(\"count\", \"Still to buy\", count(\$/items, !\$done))"
        const val URGENT = "need items = entries(\"Items\", fields={name: \"text\", aisle: \"text\", done: \"check\", urgent: \"check\"}, " +
            "sort=\$aisle, labels={name: \"Product\", aisle: \"Aisle\"}, rows=[{name: \"Milk\", aisle: \"Dairy\", done: false, urgent: false}, " +
            "{name: \"Bread\", aisle: \"Bakery\", done: false, urgent: false}, {name: \"Apples\", aisle: \"Produce\", done: false, urgent: false}])"
        /** A change of the shopping list that removes the list, its count, and its check, and adds a count of people. */
        const val REMOVE_LIST = "delete left\ndelete items\ndelete check(\"Sorted by aisle and counted\")\n" +
            "need people = input(\"count\", \"People\", 4, min=1, max=20)"
        /** A need of a part that the kit does not have. */
        const val HOLE = "need chart = hole(\"A chart\")"
        /** The view that an author writes for [HOLE]. With it, the kit gives no fault for the hole. */
        const val HOLE_VIEW = "chart_view = Text(\"A chart\")"
        /** The failure of the check of a clean compile with a hole (ToolEngine.kitRevision). */
        const val HOLE_REFUSED = "The kit did not compile the app file: ok."
        /** The text of the list of versions for a version that fails its check (ToolEngine.restoreAvailability). */
        const val CHECK_FAILED = "Эта версия не прошла проверку на текущих данных."
        /** The replies of the loop: one write of the fixture app and a finish. */
        val LOOP = listOf(Scripted.Text(agentWrite(FIXTURE_APP)), Scripted.Text(agentFixtureFinish))
    }
}

/** A change line of the shopping list plan with a fault: count reads a value that no line sets. */
internal const val BROKEN = "need bought = figure(\"count\", \"Bought\", count(\$/nothing, \$done))"

/** A change line of the shopping list plan: a count of the bought items. */
internal const val BOUGHT = "need bought = figure(\"count\", \"Bought\", count(\$/items, \$done), example=[{items: [{done: true}]}, 1])"

/** A change line of the shopping list plan. The saved rows hold a text in the column aisle, so a number there is blocked. The order of the rows keeps the check of the plan. */
internal const val AISLE_NUMBER = "need items = entries(\"Items\", fields={name: \"text\", aisle: \"number\", done: \"check\"}, " +
    "sort=\$aisle, labels={name: \"Product\", aisle: \"Aisle\"}, rows=[{name: \"Milk\", aisle: 2, done: false}, " +
    "{name: \"Bread\", aisle: 1, done: false}, {name: \"Apples\", aisle: 3, done: false}])"

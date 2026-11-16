package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.automations.AutomationStore
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestFixWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val definition = """
        {"formatVersion":3,"title":"Double","description":"","capabilities":[],
        "stateSchema":{"amount":"number","result":"number"},"initialState":{"amount":0,"result":0},
        "ui":{"type":"column","children":[{"type":"numberInput","text":"Amount","bind":"amount","action":"amount","min":0,"max":10},
        {"type":"button","text":"Double","action":"apply"},{"type":"metric","text":"Result","bind":"result"}]},
        "source":"const {state:s,event:e}=input;if(e.type==='action'){if(e.actionId==='amount')s.amount=e.value;if(e.actionId==='apply'){if(s.amount===7)throw Error('bad');s.result=s.amount;}}return {state:s};"}
    """.trimIndent()
    private val working = definition.replace("if(s.amount===7)throw Error('bad');s.result=s.amount;", "s.result=s.amount*2;")

    /** Replies in order. After the last one, the provider repeats it. */
    private class Provider(private vararg val replies: String) : ChatProvider {
        val calls = mutableListOf<CloudRequest>()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            calls += request
            val reply = replies[minOf(calls.lastIndex, replies.lastIndex)]
            emit(CloudEvent.Text(reply))
            emit(CloudEvent.RoundComplete(reply, false))
        }
        fun feedback(call: Int) = calls[call].turns.last().text
    }

    private fun run(vararg steps: String) = """{"tool":"run","steps":[${steps.joinToString(",")}]}"""
    private fun finish(vararg steps: String) =
        """{"tool":"finish","summary":"Checked.","checks":[{"name":"double","steps":[${steps.joinToString(",")}]}]}"""
    private fun edit(vararg edits: Pair<String, String>) = JSONObject().put("tool", "edit_app").put("source_edits",
        JSONArray(edits.map { (find, replace) -> JSONObject().put("find", find).put("replace", replace) })).toString()

    private val enterSeven = """{"set":"amount","value":7}"""
    private val tapDouble = """{"tap":"apply"}"""
    private fun resultIs(value: Int) = """{"expect":"value","target":"result","equals":$value}"""
    private val fixDouble = edit("if(s.amount===7)throw Error('bad');" to "", "s.result=s.amount;" to "s.result=s.amount*2;")

    private fun repairReplies() = arrayOf(run(enterSeven, tapDouble), fixDouble, finish(enterSeven, tapDouble, resultIs(14)))

    private fun tool(raw: String = definition): ToolRecord {
        val spec = ToolSpec.parse(raw)
        return ToolRecord("double", 1, 1, spec.json, """{"amount":3,"result":6}""", spec.ui.toString(), null, true)
    }

    private suspend fun testAndFix(provider: Provider, base: ToolRecord, request: String = "Test and fix this app.") =
        ToolGenerationService(provider, SandboxClient(context)).testAndFix("fixture", request, base, false, CloudSession())

    @Test fun reproducesScriptFailureAndKeepsRealStateSeparateFromTestState(): Unit = runBlocking {
        val provider = Provider(*repairReplies())
        val result = testAndFix(provider, tool())
        assertEquals(result.detail, TestFixStatus.VERIFIED, result.status)
        val candidate = checkNotNull(result.candidate)
        assertEquals(3, JSONObject(candidate.state).getInt("amount"))
        assertEquals(6, JSONObject(candidate.state).getInt("result"))
        assertTrue(candidate.spec.source.contains("s.amount*2"))
        assertEquals(3, provider.calls.size)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("This confirms the problem."))
    }

    @Test fun aSavedAppThatFailsTheHostCheckCanBeRepaired(): Unit = runBlocking {
        val subscription = "if(e.type==='action'&&e.actionId==='apply')return {state:s,timers:[{id:'tick',intervalMs:50}]};"
        val broken = working.replace("return {state:s};", subscription + "return {state:s};")
        val provider = Provider(edit(subscription to ""), finish(enterSeven, tapDouble, resultIs(14)))
        val result = testAndFix(provider, tool(broken))
        assertEquals(result.detail, TestFixStatus.VERIFIED, result.status)
        assertTrue(provider.calls.first().turns.first().text.contains("The current app fails the host check"))
        assertFalse(checkNotNull(result.candidate).spec.source.contains("intervalMs"))
    }

    @Test fun anInventedExpectationCannotAuthorizeAnEdit(): Unit = runBlocking {
        val provider = Provider(run(enterSeven, tapDouble, resultIs(-156)), edit("s.amount*2" to "s.amount*3"),
            finish(enterSeven, tapDouble, resultIs(14)))
        val result = testAndFix(provider, tool(working))
        assertEquals(result.detail, TestFixStatus.PASSED, result.status)
        assertNull(result.candidate)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("This does not confirm a problem."))
        assertTrue(provider.feedback(2), provider.feedback(2).contains("edit_app: REJECTED. Reproduce the reported problem"))
    }

    @Test fun anExpectedValueFromTheUsersReportConfirmsTheProblem(): Unit = runBlocking {
        val noCrash = definition.replace("if(s.amount===7)throw Error('bad');", "")
        val provider = Provider(run(enterSeven, tapDouble, resultIs(14)), edit("s.result=s.amount;" to "s.result=s.amount*2;"),
            finish(enterSeven, tapDouble, resultIs(14)))
        val result = testAndFix(provider, tool(noCrash), "Put 7 in Amount, tap Double, and check that Result is 14.")
        assertEquals(result.detail, TestFixStatus.VERIFIED, result.status)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("This confirms the problem."))
    }

    @Test fun removingTheFailingControlDoesNotPassTheReproduction(): Unit = runBlocking {
        val original = JSONObject(definition)
        val ordinary = JSONObject(original.getJSONObject("ui").toString())
        original.getJSONObject("ui").getJSONArray("children").put(JSONObject("""{"type":"button","text":"Broken","action":"broken"}"""))
        original.put("source", original.getString("source").replace("if(s.amount===7)throw Error('bad');", "")
            .replace("if(e.type==='action'){", "if(e.type==='action'){if(e.actionId==='broken')throw Error('bad');"))
        val hide = JSONObject().put("tool", "edit_app").put("set", JSONObject().put("ui", ordinary)).toString()
        val provider = Provider(run("""{"tap":"broken"}"""), hide, finish(enterSeven, tapDouble, resultIs(7)))
        val result = testAndFix(provider, tool(original.toString()))
        assertEquals(TestFixStatus.UNREPAIRED, result.status)
        assertNull(result.candidate)
        assertTrue(provider.feedback(3), provider.feedback(3).contains("finish: FAILED. The reproduced problem still fails"))
    }

    @Test fun theReproductionRunsAtTheRevisionThatWillBeSaved(): Unit = runBlocking {
        val conditional = edit("if(s.amount===7)throw Error('bad');" to "if(s.amount===7&&input.revision===2)throw Error('next revision');",
            "s.result=s.amount;" to "s.result=s.amount*2;")
        val provider = Provider(run(enterSeven, tapDouble), conditional, finish(enterSeven, tapDouble, resultIs(14)))
        val result = testAndFix(provider, tool())
        assertEquals(TestFixStatus.UNREPAIRED, result.status)
        assertTrue(provider.feedback(3), provider.feedback(3).contains("finish: FAILED"))
    }

    @Test fun noConfirmedProblemAndNoFinishIsUnverified(): Unit = runBlocking {
        val provider = Provider(run(enterSeven, tapDouble, resultIs(14)))
        val result = testAndFix(provider, tool(working))
        assertEquals(TestFixStatus.UNVERIFIED, result.status)
        assertNull(result.candidate)
    }

    @Test fun aDependentAutomationBlocksTheVerifiedSaveWithoutChangingEitherRecord(): Unit = runBlocking {
        val name = "test-fix-guard-${java.util.UUID.randomUUID()}.db"
        val service = ToolGenerationService(Provider(*repairReplies()), SandboxClient(context))
        ToolStore(context, name).use { store ->
            try {
                val base = tool()
                store.saveTool(base, true)
                val engine = ToolEngine(store, service)
                val result = service.testAndFix("fixture", "Test and fix this app.", base, false, CloudSession())
                val candidate = checkNotNull(result.candidate)
                AutomationStore(context, store.automationDatabase).use { rules ->
                    val rule = rules.save(null, JSONObject("""
                        {"name":"Dependent","trigger":{"type":"manual"},"conditions":{},"actions":[{
                        "id":"set","capability":"tool.state.set","args":{"toolId":"double","revision":1,"field":"amount","value":2}}]}
                    """.trimIndent()))
                    val failure = runCatching { engine.acceptTestFix("repair", "chat", base, "Test and fix this app.", candidate) { true } }.exceptionOrNull()
                    assertTrue(failure is TestFixNotSaved)
                    val after = checkNotNull(rules.rule(rule.id))
                    assertEquals(rule.definition.json, after.definition.json)
                    assertEquals(rule, after.copy(definition = rule.definition))
                    assertEquals(base, store.tool(base.id))
                    assertEquals(1, store.revisions(base.id).size)
                }
                assertNull(store.draft("repair"))
            } finally {
                context.deleteDatabase(store.automationDatabase)
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun cancellationAndAConcurrentStateChangeLeaveNoRepairDraftOrRevision(): Unit = runBlocking {
        val name = "test-fix-conflict-${java.util.UUID.randomUUID()}.db"
        val service = ToolGenerationService(Provider(*repairReplies()), SandboxClient(context))
        ToolStore(context, name).use { store ->
            try {
                val base = tool()
                store.saveTool(base, true)
                val engine = ToolEngine(store, service)
                val candidate = checkNotNull(service.testAndFix("fixture", "Test and fix this app.", base, false, CloudSession()).candidate)
                val cancelled = runCatching {
                    engine.acceptTestFix("cancelled", "chat", base, "Test and fix this app.", candidate) { false }
                }
                assertTrue(cancelled.isFailure)
                assertEquals(base, store.tool(base.id))
                assertNull(store.draft("cancelled"))
                val changed = base.copy(stateVersion = 2, state = """{"amount":4,"result":8}""")
                store.saveTool(changed, false)
                val conflict = runCatching {
                    engine.acceptTestFix("conflict", "chat", base, "Test and fix this app.", candidate) { true }
                }.exceptionOrNull()
                assertTrue(conflict is TestFixNotSaved)
                assertEquals(changed, store.tool(base.id))
                assertNull(store.draft("conflict"))
                assertEquals(1, store.revisions(base.id).size)
            } finally {
                context.deleteDatabase(store.automationDatabase)
            }
        }
        context.deleteDatabase(name)
    }
}

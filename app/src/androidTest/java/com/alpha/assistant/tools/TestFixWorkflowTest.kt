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

    private fun scenario(expected: Int) = """
        {"behavior":{"status":"checkable","scenario":{"slots":{"enter":{"kind":"action"},"apply":{"kind":"action"},
        "result":{"kind":"value"}},"steps":[{"op":"action","slot":"enter","value":7},
        {"op":"action","slot":"apply"},{"op":"assert","slot":"result","test":"equals","value":$expected}]}},
        "targets":{"enter":"action1","apply":"action2","result":"value4"},"authoredReproduction":null}
    """.trimIndent()

    private val naturalRequest = "Test and fix the app. Put 7 in Amount, tap Double, and check that Result is 14."
    private val authoredReproduction = """
        [{"text":"Put 7 in Amount","target":"action1","value":"7"},
        {"text":"tap Double","target":"action2"},
        {"text":"Result is 14","target":"value4","value":"14"}]
    """.trimIndent()

    private fun patch() = JSONObject().put("sourceEdits", JSONArray()
        .put(JSONObject().put("search", "if(s.amount===7)throw Error('bad');").put("replace", ""))
        .put(JSONObject().put("search", "s.result=s.amount;").put("replace", "s.result=s.amount*2;"))).toString()

    private class Provider(private vararg val outputs: String) : ChatProvider {
        val calls = mutableListOf<CloudRequest>()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            calls += request
            emit(CloudEvent.Text(outputs[calls.lastIndex]))
            emit(CloudEvent.RoundComplete(outputs[calls.lastIndex], false))
        }
    }

    private fun tool(raw: String = definition): ToolRecord {
        val spec = ToolSpec.parse(raw)
        return ToolRecord("double", 1, 1, spec.json, """{"amount":3,"result":6}""", spec.ui.toString(), null, true)
    }

    @Test fun reproducesScriptFailureAndKeepsRealStateSeparateFromTestState(): Unit = runBlocking {
        val provider = Provider(scenario(14), patch())
        val result = ToolGenerationService(provider, SandboxClient(context))
            .testAndFix("fixture", "Test and fix this app.", tool(), false, CloudSession())
        val candidate = checkNotNull(result.candidate)
        assertEquals(TestFixStatus.VERIFIED, result.status)
        assertEquals(1, result.patchAttempts)
        assertEquals(3, JSONObject(candidate.state).getInt("amount"))
        assertEquals(6, JSONObject(candidate.state).getInt("result"))
        assertTrue(candidate.spec.source.contains("s.amount*2"))
        assertEquals(2, provider.calls.size)
        assertFalse(provider.calls.first().turns.joinToString().contains("throw Error"))
    }

    @Test fun modelInventedExpectationCannotAuthorizeAnEdit(): Unit = runBlocking {
        val provider = Provider(scenario(-156))
        val working = definition.replace("if(s.amount===7)throw Error('bad');s.result=s.amount;", "s.result=s.amount*2;")
        val result = ToolGenerationService(provider, SandboxClient(context))
            .testAndFix("fixture", "Test and fix this app.", tool(working), false, CloudSession())
        assertEquals(TestFixStatus.UNVERIFIED, result.status)
        assertNull(result.candidate)
        assertEquals(1, provider.calls.size)
    }

    @Test fun threeUnappliedPatchesExhaustTheOperation(): Unit = runBlocking {
        val provider = Provider(scenario(14), "{}", "{}", "{}")
        val result = ToolGenerationService(provider, SandboxClient(context))
            .testAndFix("fixture", "Test and fix this app.", tool(), false, CloudSession())
        assertEquals(TestFixStatus.UNREPAIRED, result.status)
        assertEquals(3, result.patchAttempts)
        assertNull(result.candidate)
        assertEquals(4, provider.calls.size)
    }

    @Test fun confirmsAndRepairsAnAssertionFromNaturalLanguage(): Unit = runBlocking {
        val proposal = JSONObject(scenario(14)).put("authoredReproduction", JSONArray(authoredReproduction))
        val provider = Provider(proposal.toString(), patch())
        val workingWithoutCrash = definition.replace("if(s.amount===7)throw Error('bad');", "")
        val assertionPatch = """{"sourceEdits":[{"search":"s.result=s.amount;","replace":"s.result=s.amount*2;"}]}"""
        val repairProvider = Provider(proposal.toString(), assertionPatch)
        val result = ToolGenerationService(repairProvider, SandboxClient(context)).testAndFix(
            "fixture", naturalRequest, tool(workingWithoutCrash), false, CloudSession())
        assertEquals(TestFixStatus.VERIFIED, result.status)
        assertEquals(2, repairProvider.calls.size)
        assertNotNull(result.candidate)

        val alteredRequest = naturalRequest.replace("Result is 14", "Result is 140")
        val unverified = ToolGenerationService(provider, SandboxClient(context)).testAndFix(
            "fixture", alteredRequest, tool(workingWithoutCrash), false, CloudSession())
        assertEquals(TestFixStatus.UNVERIFIED, unverified.status)
        assertEquals(1, provider.calls.size)
        assertNull(unverified.candidate)
    }

    @Test fun omittedInputCannotTurnAnAuthoredExpectationIntoPermissionToPatch(): Unit = runBlocking {
        val proposal = JSONObject(scenario(14)).apply {
            val scenario = getJSONObject("behavior").getJSONObject("scenario")
            scenario.getJSONObject("slots").remove("enter")
            scenario.getJSONArray("steps").remove(0)
            getJSONObject("targets").remove("enter")
            put("authoredReproduction", JSONArray(authoredReproduction))
        }.toString()
        val provider = Provider(proposal)
        val working = definition.replace("if(s.amount===7)throw Error('bad');s.result=s.amount;", "s.result=s.amount*2;")
        val result = ToolGenerationService(provider, SandboxClient(context)).testAndFix(
            "fixture", naturalRequest, tool(working), false, CloudSession())
        assertEquals(TestFixStatus.UNVERIFIED, result.status)
        assertEquals(1, provider.calls.size)
        assertNull(result.candidate)
    }

    @Test fun generatedSurfaceAndFrameEventsCannotChangeTheUsersExpectedSetup(): Unit = runBlocking {
        val graphics = JSONObject(definition)
        graphics.put("surfaces", JSONArray("""[{"id":"board","width":64,"height":64}]"""))
        graphics.getJSONObject("ui").getJSONArray("children").getJSONObject(1).put("text", "Reset")
        graphics.put("source", """
            const s=input.state,e=input.event;
            if(e.type==='action'&&e.actionId==='apply')s.result=0;
            if(e.type==='pointer'&&e.phase==='down'||e.type==='frame')s.result++;
            return {state:s,frames:['board'],surfaces:{board:{commands:[]}}};
        """.trimIndent())
        val extraEvents = listOf(
            """{"op":"surface","slot":"board","event":"pointer","phase":"down","x":0.5,"y":0.5}""",
            """{"op":"frames","slot":"board","count":1}""",
        )
        for (event in extraEvents) {
            val proposal = """
                {"behavior":{"status":"checkable","scenario":{"slots":{"reset":{"kind":"action"},
                "board":{"kind":"surface"},"result":{"kind":"value"}},
                "steps":[{"op":"action","slot":"reset"},$event,{"op":"assert","slot":"result","test":"equals","value":0}]}},
                "targets":{"reset":"action2","board":"surface5","result":"value4"},
                "authoredReproduction":[{"text":"Press Reset","target":"action2"},
                {"text":"Expected Result = 0","target":"value4","value":"0"}]}
            """.trimIndent()
            val provider = Provider(proposal)
            val result = ToolGenerationService(provider, SandboxClient(context)).testAndFix(
                "fixture", "Test and fix this app.\nPress Reset\nExpected Result = 0", tool(graphics.toString()), false, CloudSession())
            assertEquals(TestFixStatus.UNVERIFIED, result.status)
            assertTrue(result.detail, result.detail.contains("model-proposed expectation"))
            assertEquals(1, provider.calls.size)
            assertNull(result.candidate)
        }
    }

    @Test fun removingTheFailingControlDoesNotPassTheOriginalReproduction(): Unit = runBlocking {
        val original = JSONObject(definition)
        val ui = original.getJSONObject("ui")
        val ordinary = JSONObject(ui.toString())
        ui.getJSONArray("children").put(JSONObject("""{"type":"button","text":"Broken","action":"broken"}"""))
        original.put("source", original.getString("source").replace("if(s.amount===7)throw Error('bad');", "")
            .replace("if(e.type==='action'){", "if(e.type==='action'){if(e.actionId==='broken')throw Error('bad');"))
        val hide = JSONObject().put("fields", JSONObject().put("ui", ordinary)).toString()
        val provider = Provider(scenario(7), hide, "{}", "{}")
        val result = ToolGenerationService(provider, SandboxClient(context)).testAndFix(
            "fixture", "Test and fix this app.", tool(original.toString()), false, CloudSession())
        assertEquals(TestFixStatus.UNREPAIRED, result.status)
        assertNull(result.candidate)
        assertTrue(provider.calls[2].turns.last().text.contains("original failing event sequence"))
    }

    @Test fun checksTheActionsAtTheRevisionThatWillBeSaved(): Unit = runBlocking {
        val conditional = patch().replace("\"replace\":\"\"", "\"replace\":\"if(s.amount===7&&input.revision===2)throw Error('next revision');\"")
        val provider = Provider(scenario(14), conditional, "{}", "{}")
        val result = ToolGenerationService(provider, SandboxClient(context)).testAndFix(
            "fixture", "Test and fix this app.", tool(), false, CloudSession())
        assertEquals(TestFixStatus.UNREPAIRED, result.status)
        assertNull(result.candidate)
    }

    @Test fun aDependentAutomationBlocksTheVerifiedSaveWithoutChangingEitherRecord(): Unit = runBlocking {
        val name = "test-fix-guard-${java.util.UUID.randomUUID()}.db"
        val service = ToolGenerationService(Provider(scenario(14), patch()), SandboxClient(context))
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
        val service = ToolGenerationService(Provider(scenario(14), patch()), SandboxClient(context))
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

package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.agent.AppAgentGuide
import com.alpha.assistant.tools.agent.GenerationSettings
import com.alpha.assistant.tools.agent.ReplyJson
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The agent loop sees host results for each step. The host alone decides acceptance. */
@RunWith(AndroidJUnit4::class)
class AgentGenerationWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tipSource = "const s=input.state,e=input.event;" +
        "if(e.type==='action'){if(e.actionId==='reset'){s.bill=0;s.people=1;}else s[e.actionId]=e.value;}" +
        "s.total=Math.round(s.bill*(1+s.tip/100)*100)/100;s.share=Math.round(s.total/s.people*100)/100;return {state:s};"

    private fun tipApp(source: String = tipSource, withReset: Boolean = false) = JSONObject("""
        {"title":"Bill split","description":"Split a bill with a tip",
        "stateSchema":{"bill":"number","tip":"number","people":"number","total":"number","share":"number"},
        "initialState":{"bill":0,"tip":15,"people":1,"total":0,"share":0},
        "ui":{"type":"column","children":[
          {"type":"numberInput","text":"Bill","bind":"bill","action":"bill","min":0,"max":100000},
          {"type":"numberInput","text":"People","bind":"people","action":"people","min":1,"max":50},
          {"type":"metric","text":"Total","bind":"total"},
          {"type":"metric","text":"Each","bind":"share"}]}}
    """.trimIndent()).put("source", source).apply {
        if (withReset) getJSONObject("ui").getJSONArray("children")
            .put(JSONObject().put("type", "button").put("text", "Reset").put("action", "reset"))
    }

    private val splitCheck = JSONArray().put(JSONObject().put("name", "split").put("steps", JSONArray("""
        [{"set":"bill","value":80},{"set":"people","value":4},
         {"expect":"value","target":"total","equals":92},{"expect":"text","contains":"23"}]
    """.trimIndent())))

    private fun run(steps: String) = JSONObject().put("tool", "run").put("steps", JSONArray(steps)).toString()
    private fun finish(checks: JSONArray) = JSONObject().put("tool", "finish").put("checks", checks)
        .put("summary", "The bill split is ready.").toString()

    private suspend fun generate(provider: AgentScriptProvider, prompt: String = "Split a restaurant bill with a 15% tip",
        base: ToolRecord? = null): ToolPreview =
        ToolGenerationService(provider, SandboxClient(context))
            .generate("fixture", prompt, base, emptyList(), CloudSession(), "agent-workflow")

    @Test fun newAppRunsAndFinishesWithHostChecks(): Unit = runBlocking {
        val provider = AgentScriptProvider(writeAppReply(tipApp()), run("""[{"set":"bill","value":80}]"""), finish(splitCheck))
        val preview = generate(provider)
        assertEquals("Bill split", preview.spec.title)
        assertEquals(3, provider.calls.size)
        assertTrue(provider.calls.all { it.generationStage == CloudGenerationStage.AGENT && it.jsonObject })
        assertTrue(provider.calls.first().systemInstructions.contains("write_app"))
        // Localized product text in the prompt steers the interface language of the app.
        assertFalse(Regex("\\p{IsCyrillic}").containsMatchIn(provider.calls.first().systemInstructions))
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: OK"))
        assertTrue(provider.feedback(1), provider.feedback(1).contains("Bill"))
        assertTrue(provider.feedback(2), provider.feedback(2).contains("92"))
    }

    private fun withFinish(reply: JSONObject) = reply.put("checks", splitCheck).put("summary", "The bill split is ready.")

    // Failure case: 4.8 s passed between the routing decision and the first agent call with no mark inside them.
    @Test fun theTraceShowsTheGuidanceAndTheFirstAgentCall(): Unit = runBlocking {
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val provider = AgentScriptProvider(withFinish(JSONObject(writeAppReply(tipApp()))).toString())
        ToolGenerationService(provider, SandboxClient(context)).generate("fixture", "Split a restaurant bill with a 15% tip",
            null, emptyList(), CloudSession(), "agent-workflow", privateState = false,
            trace = ToolGenerationTrace(UUID.randomUUID(), { events += it }))
        val firstStep = events.indexOfFirst { it is ToolGenerationTraceEvent.AgentStep }
        val phases = events.filterIsInstance<ToolGenerationTraceEvent.Phase>()
        assertEquals(listOf("capability_guidance_start", "capability_guidance_end", "agent_call_start"), phases.map { it.name })
        assertEquals(phases, events.take(firstStep))
        assertTrue(phases.zipWithNext().all { (before, after) -> before.atNanos <= after.atNanos })
        assertTrue(phases.all { it.detail == null && it.ms == null && it.attempt == 1 })
    }

    // Failure case: a plan outcome could carry the word of a model reply, or any other text, off the device.
    @Test fun aPhaseKeepsOnlyListedNamesAndDetails() {
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val trace = ToolGenerationTrace(UUID.randomUUID(), { events += it })
        val run = trace.Run()
        val outcomes = listOf("accepted", "outside:3d", "outside:Космос", "outside:a game", "outside:averyveryverylongword",
            "fallback:hole", "fallback:compile_BUSY", "fallback:compile_a status", "fallback:the model wrote this", "what")
        outcomes.forEach { run.phase("plan_outcome", it) }
        run.phase("compile", "ok", 17)
        run.phase("compile", "TIMEOUT", 3000)
        run.phase("compile", "a person's text", 1)
        run.phase("agent_call_start", "a person's text")
        run.phase("a name of no list")
        trace.phase("draft_end")
        assertEquals(listOf("accepted", "outside:3d", "outside:other", "outside:other", "outside:other", "fallback:hole",
            "fallback:compile_BUSY", "fallback:other", "fallback:other", "other", "ok", "TIMEOUT", "other", null, null),
            events.map { (it as ToolGenerationTraceEvent.Phase).detail })
        assertEquals(List(outcomes.size) { "plan_outcome" } + List(3) { "compile" } + listOf("agent_call_start", "draft_end"),
            events.map { (it as ToolGenerationTraceEvent.Phase).name })
        assertEquals(listOf(17L, 3000L, 1L), events.mapNotNull { (it as ToolGenerationTraceEvent.Phase).ms })
        assertEquals(List(events.size - 1) { 1 } + 0, events.map { it.attempt })
    }

    /** Generates with [replies] and asserts that the app with [title] is accepted after the last reply. */
    private suspend fun assertFinishes(title: String, vararg replies: String) {
        val provider = AgentScriptProvider(*replies)
        assertEquals(title, generate(provider).spec.title)
        assertEquals(replies.size, provider.calls.size)
    }

    // Failure case: a passing first version still cost a second model call only to send finish.
    @Test fun aWriteWithPassingChecksFinishesInOneCall(): Unit = runBlocking {
        assertFinishes("Bill split", withFinish(JSONObject(writeAppReply(tipApp()))).toString())
    }

    // Failure case: the model put checks and summary inside app, and the whole app came back as rejected.
    @Test fun checksInsideTheAppStillFinishInOneCall(): Unit = runBlocking {
        assertFinishes("Bill split", writeAppReply(withFinish(tipApp())))
    }

    // Failure case: checks in an edit set were rejected as a field that set cannot change.
    @Test fun checksInsideAnEditSetRunAfterTheEdit(): Unit = runBlocking {
        val edit = JSONObject().put("tool", "edit_app").put("set", withFinish(JSONObject().put("title", "Tip split")))
        assertFinishes("Tip split", writeAppReply(tipApp()), edit.toString())
    }

    // Failure case: an edit with only new checks was rejected because it changed nothing.
    @Test fun anEditWithOnlyChecksRunsThem(): Unit = runBlocking {
        assertFinishes("Bill split", writeAppReply(tipApp()), withFinish(JSONObject().put("tool", "edit_app")).toString())
    }

    @Test fun aWriteWithFailingChecksKeepsTheVersionAndNamesTheCheck(): Unit = runBlocking {
        val wrong = JSONArray().put(JSONObject().put("name", "split").put("steps",
            JSONArray("""[{"set":"bill","value":80},{"expect":"value","target":"total","equals":93}]""")))
        val provider = AgentScriptProvider(JSONObject(writeAppReply(tipApp())).put("checks", wrong).toString(), finish(splitCheck))
        generate(provider)
        assertEquals(2, provider.calls.size)
        val feedback = provider.feedback(1)
        assertTrue(feedback, feedback.contains("write_app: OK") && feedback.contains("finish: FAILED. check \"split\""))
    }

    @Test fun appSourceCanFormatMoneyForTheAppLanguage(): Unit = runBlocking {
        // The guide tells the model to format money with Intl.NumberFormat, so the sandbox must support it.
        val app = JSONObject("""
            {"title":"Price","description":"Shows a price","language":"de",
            "stateSchema":{"amount":"number","price":"string"},"initialState":{"amount":0,"price":"0,00 €"},
            "ui":{"type":"column","children":[{"type":"numberInput","text":"Betrag","bind":"amount","action":"amount","min":0,"max":100000},
              {"type":"text","text":"Preis","bind":"price"}]},
            "source":"const s=input.state,e=input.event;if(e.type==='action'){s.amount=e.value;s.price=new Intl.NumberFormat('de-DE',{style:'currency',currency:'EUR'}).format(s.amount);}return {state:s};"}
        """.trimIndent())
        val check = JSONArray().put(JSONObject().put("name", "price").put("steps", JSONArray("""[{"set":"amount","value":5.5},{"expect":"text","contains":"5,50"}]""")))
        val provider = AgentScriptProvider(writeAppReply(app), run("""[{"set":"amount","value":1234.5},{"expect":"text","contains":"1.234,50"}]"""), finish(check))
        generate(provider, "Zeige einen Preis in Euro")
        assertTrue(provider.feedback(2), provider.feedback(2).contains("run: OK"))
    }

    @Test fun anAcceptedAppGetsDesignHintsWithoutARejection(): Unit = runBlocking {
        // Failure case: generated apps were gray and had no icons, and nothing in the feedback said so.
        val plain = tipApp().apply {
            put("ui", JSONObject().put("type", "theme").put("preset", "clean").put("children", JSONArray().put(
                JSONObject().put("type", "card").put("text", "Result").put("children", JSONArray().put(getJSONObject("ui"))))))
        }
        val styled = JSONObject(plain.toString()).apply {
            getJSONObject("ui").put("palette", "teal").getJSONArray("children").getJSONObject(0).put("icon", "Receipt")
        }
        val provider = AgentScriptProvider(writeAppReply(plain), writeAppReply(styled), finish(splitCheck))
        generate(provider)
        val hinted = provider.feedback(1)
        assertTrue(hinted, hinted.contains("write_app: OK") && hinted.contains("Design hints:"))
        assertTrue(hinted, hinted.contains("palette") && hinted.contains("icon"))
        assertFalse(provider.feedback(2), provider.feedback(2).contains("Design hints:"))
    }

    @Test fun theOptionalScreenshotReviewShowsTheFirstScreenOnceBeforeFinish(): Unit = runBlocking {
        // The model judged the design only from a text screen. The review is optional, because it adds a step.
        val settings = GenerationSettings(context)
        val before = settings.screenshotReview
        try {
            settings.screenshotReview = true
            val provider = AgentScriptProvider(writeAppReply(tipApp()), finish(splitCheck), run("""[{"set":"bill","value":80}]"""), finish(splitCheck))
            generate(provider)
            assertEquals(4, provider.calls.size)
            val review = provider.calls[2].turns.last()
            assertTrue(review.text, review.text.contains("finish: REVIEW"))
            assertEquals(1, review.images.size)
            assertTrue(review.images.single().startsWith("data:image/jpeg;base64,"))
            // Only the newest screenshot is sent again. The second finish ends the generation without a new review.
            assertTrue(provider.calls[3].turns.drop(1).all { it.images.isEmpty() })
            settings.screenshotReview = false
            val off = AgentScriptProvider(writeAppReply(tipApp()), finish(splitCheck))
            generate(off)
            assertEquals(2, off.calls.size)
        } finally { settings.screenshotReview = before }
    }

    @Test fun validationErrorReachesTheModelAndAnEditFixesIt(): Unit = runBlocking {
        val broken = tipApp().apply {
            getJSONObject("ui").getJSONArray("children").put(JSONObject().put("type", "button").put("action", "reset"))
        }
        val fixedUi = tipApp(withReset = true).getJSONObject("ui")
        val edit = JSONObject().put("tool", "edit_app").put("set", JSONObject().put("ui", fixedUi)).toString()
        val provider = AgentScriptProvider(writeAppReply(broken), edit, finish(splitCheck))
        val preview = generate(provider)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: REJECTED"))
        assertTrue(provider.feedback(2), provider.feedback(2).contains("edit_app: OK"))
        assertTrue(preview.spec.ui.toString().contains("Reset"))
    }

    @Test fun anUnsupportedPropertyIsNamedWithTheAllowedOnes(): Unit = runBlocking {
        val broken = tipApp().apply { getJSONObject("ui").getJSONArray("children").getJSONObject(2).put("value", 5) }
        val provider = AgentScriptProvider(writeAppReply(broken), writeAppReply(tipApp()), finish(splitCheck))
        generate(provider)
        val feedback = provider.feedback(1)
        assertTrue(feedback, feedback.contains("This metric has unsupported properties: value."))
        assertTrue(feedback, feedback.contains("Allowed:") && feedback.contains("style"))
    }

    /**
     * Writes the bill split with [banner] added in one step and returns the accepted banner node.
     * Failure case: in the baseline at the start commit, about one case in ten put tone beside style, mostly on banner and badge.
     */
    private suspend fun acceptedBanner(banner: String): JSONObject {
        val app = tipApp().apply { getJSONObject("ui").getJSONArray("children").put(JSONObject(banner)) }
        val provider = AgentScriptProvider(writeAppReply(app), finish(splitCheck))
        val preview = generate(provider)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: OK"))
        val children = preview.spec.ui.getJSONArray("children")
        return children.getJSONObject(children.length() - 1).also { assertEquals("banner", it.getString("type")) }
    }

    @Test fun aToneBesideStyleMovesIntoStyle(): Unit = runBlocking {
        val banner = acceptedBanner("""{"type":"banner","text":"Tip","tone":"warning","children":[{"type":"text","text":"Tip is 15%"}]}""")
        assertEquals("warning", banner.getJSONObject("style").getString("tone"))
        assertFalse(banner.has("tone"))
    }

    @Test fun aToneAlreadyInStyleWins(): Unit = runBlocking {
        val banner = acceptedBanner("""{"type":"banner","text":"Tip","tone":"warning","style":{"tone":"error"},"children":[{"type":"text","text":"Tip is 15%"}]}""")
        assertEquals("error", banner.getJSONObject("style").getString("tone"))
    }

    @Test fun aToneOnATypeWhoseStyleHasNoToneIsStillRejected(): Unit = runBlocking {
        val broken = tipApp().apply { getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("tone", "accent") }
        val provider = AgentScriptProvider(writeAppReply(broken), writeAppReply(tipApp()), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("This numberInput has unsupported properties: tone."))
    }

    @Test fun aFindAndReplaceCanChangeTheUi(): Unit = runBlocking {
        val edit = JSONObject().put("tool", "edit_app").put("source_edits", JSONArray().put(
            JSONObject().put("find", """{"type":"metric","text":"Each","bind":"share"}""")
                .put("replace", """{"type":"metric","text":"Each person pays","bind":"share"}"""))).toString()
        val broken = JSONObject().put("tool", "edit_app").put("source_edits", JSONArray().put(
            JSONObject().put("find", """"text":"Total","bind":"total"}""").put("replace", """"text":"Total""""))).toString()
        val provider = AgentScriptProvider(writeAppReply(tipApp()), edit, broken, finish(splitCheck))
        val preview = generate(provider)
        assertTrue(provider.feedback(2), provider.feedback(2).contains("edit_app: OK"))
        assertTrue(provider.feedback(3), provider.feedback(3).contains("breaks the ui JSON"))
        assertTrue(preview.spec.ui.toString().contains("Each person pays"))
    }

    @Test fun aSmokeFailureNamesTheRejectedResult(): Unit = runBlocking {
        val invalidUi = tipSource.replace("return {state:s};",
            "return e.type==='open'?{state:s,ui:{type:'text',text:'Hi',sparkle:true}}:{state:s};")
        val provider = AgentScriptProvider(writeAppReply(tipApp(invalidUi)), writeAppReply(tipApp()), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("Remove unsupported properties"))
    }

    @Test fun stateSchemaValuesBecomeTheirTypes(): Unit = runBlocking {
        val app = tipApp().apply { getJSONObject("stateSchema").put("tip", 15).put("bill", 0) }
        val provider = AgentScriptProvider(writeAppReply(app), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: OK"))
    }

    @Test fun aStringSchemaValueThatRepeatsTheInitialValueIsAString(): Unit = runBlocking {
        val app = tipApp().apply {
            getJSONObject("stateSchema").put("note", "Tip is 15%")
            getJSONObject("initialState").put("note", "Tip is 15%")
        }
        val provider = AgentScriptProvider(writeAppReply(app), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: OK"))
    }

    @Test fun aMissingBindIsNamed(): Unit = runBlocking {
        val broken = tipApp().apply { getJSONObject("ui").getJSONArray("children").getJSONObject(3).put("bind", "perPerson") }
        val provider = AgentScriptProvider(writeAppReply(broken), writeAppReply(tipApp()), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("bind \"perPerson\" is not a field"))
    }

    @Test fun failedExpectationShowsTheScreenAndBlocksAcceptance(): Unit = runBlocking {
        val buggy = tipSource.replace("s.tip/100", "s.tip")
        val fix = JSONObject().put("tool", "edit_app").put("source_edits", JSONArray().put(
            JSONObject().put("find", "(1+s.tip)").put("replace", "(1+s.tip/100)"))).toString()
        val provider = AgentScriptProvider(writeAppReply(tipApp(buggy)), finish(splitCheck), fix, finish(splitCheck))
        val preview = generate(provider)
        val failure = provider.feedback(2)
        assertTrue(failure, failure.contains("finish: FAILED"))
        assertTrue(failure, failure.contains("check \"split\""))
        assertTrue(failure, failure.contains("1280"))
        assertTrue(preview.spec.source.contains("s.tip/100"))
        assertEquals(4, provider.calls.size)
    }

    @Test fun aMetricLineAsTheScreenShowsItPassesAsVisibleText(): Unit = runBlocking {
        // The screen line is metric "Total: 92". The model copies it into a check.
        val check = JSONArray().put(JSONObject().put("name", "line").put("steps", JSONArray("""
            [{"set":"bill","value":80},{"expect":"text","contains":"Total: 92"}]
        """.trimIndent())))
        val provider = AgentScriptProvider(writeAppReply(tipApp()), finish(check))
        generate(provider)
        assertEquals(2, provider.calls.size)
    }

    @Test fun tapOnAValueControlSaysToUseSet(): Unit = runBlocking {
        val provider = AgentScriptProvider(writeAppReply(tipApp()), run("""[{"tap":"bill"},{"expect":"text","contains":"Bill"}]"""), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(2), provider.feedback(2).contains("Use set with a value"))
    }

    @Test fun aConversionConstantIsNotAFloatArtifact(): Unit = runBlocking {
        val constant = tipApp().apply {
            getJSONObject("ui").getJSONArray("children").put(JSONObject().put("type", "text").put("text", "1 km = 0.621371 mi"))
        }
        val provider = AgentScriptProvider(writeAppReply(constant), finish(splitCheck))
        generate(provider)
        assertEquals(2, provider.calls.size)
    }

    @Test fun aFloatArtifactBlocksFinish(): Unit = runBlocking {
        val artifact = tipSource.replace("return {state:s};", "s.share=s.total/3;return {state:s};")
        val total = JSONArray().put(JSONObject().put("name", "total").put("steps", JSONArray("""
            [{"set":"bill","value":80},{"expect":"value","target":"total","equals":92}]
        """.trimIndent())))
        val provider = AgentScriptProvider(writeAppReply(tipApp(artifact)), finish(total), writeAppReply(tipApp()), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(2), provider.feedback(2).contains("the screen shows 30.666"))
    }

    @Test fun aFinishInTheModelsNativeCallMarkupIsRead(): Unit = runBlocking {
        // DeepSeek sometimes answers in its own call markup, and the opening tags can be missing.
        val bar = "｜｜"
        val markup = "\n\n   " + splitCheck + "</${bar}DSML${bar} parameter>\n" +
            "<${bar}DSML${bar} parameter name=\"summary\" string=\"true\">The bill split is ready.</${bar}DSML${bar} parameter>\n" +
            "</${bar}DSML${bar} invoke>\n</${bar}DSML${bar} calls>"
        val provider = AgentScriptProvider(writeAppReply(tipApp()), markup)
        val preview = generate(provider)
        assertEquals("Bill split", preview.spec.title)
        assertEquals(2, provider.calls.size)
    }

    @Test fun invalidJsonNamesThePositionWithoutTheReply(): Unit = runBlocking {
        val provider = AgentScriptProvider(writeAppReply(tipApp()), """{"tool":"finish","checks":[{"name":"x" "steps":[]}]}""", finish(splitCheck))
        generate(provider)
        val feedback = provider.feedback(2)
        assertTrue(feedback, feedback.contains("character"))
        assertFalse(feedback, feedback.contains("\"steps\""))
    }

    // Failure case: 4 of 9 invalid replies lacked only the last brace; each cost a full repair step.
    @Test fun aReplyMissingItsLastBraceIsRead(): Unit = runBlocking {
        assertFinishes("Bill split", withFinish(JSONObject(writeAppReply(tipApp()))).toString().dropLast(1))
    }

    // Failure case: the parser named character 5851 for an array opened at 1890; the model resent the same reply 3 times.
    @Test fun anUnclosedArrayIsNamedAtItsStart(): Unit = runBlocking {
        val provider = AgentScriptProvider(writeAppReply(tipApp()), """{"tool":"run","steps":[{"set":"bill","value":80}}""", finish(splitCheck))
        generate(provider)
        val feedback = provider.feedback(2)
        assertTrue(feedback, feedback.contains("\"steps\":["))
        assertTrue(feedback, feedback.contains("no closing ]"))
    }

    @Test fun bracketsInStringsAndOpenStringsAreNotClosed() {
        assertEquals("}", ReplyJson.closers("""{"text":"[1] {name}""""))
        assertNull(ReplyJson.closers("""{"text":"open"""))
        assertNull(ReplyJson.closers("""{"a":[1}"""))
        assertEquals("}", ReplyJson.closers("""{"a":"say \"]\" now""""))
    }

    @Test fun anAutoClosedReplyStillGetsValidation(): Unit = runBlocking {
        val broken = JSONObject(writeAppReply(tipApp())).apply { getJSONObject("app").remove("ui") }.toString().dropLast(1)
        val provider = AgentScriptProvider(broken, writeAppReply(tipApp()), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("Validation category"))
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: REJECTED"))
    }

    @Test fun aReplyEndingInAStringGetsThePositionFeedback(): Unit = runBlocking {
        val provider = AgentScriptProvider(writeAppReply(tipApp()), """{"tool":"run","steps":[{"tap":"go}]}""", finish(splitCheck))
        generate(provider)
        val feedback = provider.feedback(2)
        assertFalse(feedback, feedback.contains("no closing"))
        assertTrue(feedback, feedback.contains("Unterminated string"))
    }

    // Failure case: an extra closer far from the fault was reported as a missing closer of an outer object.
    @Test fun anExtraCloserGetsThePositionFeedback(): Unit = runBlocking {
        val provider = AgentScriptProvider(writeAppReply(tipApp()), """{"tool":"run","steps":[{"set":"bill","value":80}]]}""", finish(splitCheck))
        generate(provider)
        val feedback = provider.feedback(2)
        assertTrue(feedback, feedback.contains("at character"))
        assertFalse(feedback, feedback.contains("no closing"))
    }

    @Test fun finishNeedsAnExpectationAfterTheLastOperation(): Unit = runBlocking {
        val weak = JSONArray().put(JSONObject().put("name", "weak").put("steps", JSONArray("""[{"set":"bill","value":80}]""")))
        val provider = AgentScriptProvider(writeAppReply(tipApp()), finish(weak), finish(splitCheck))
        generate(provider)
        assertTrue(provider.feedback(2), provider.feedback(2).contains("finish: REJECTED"))
        assertEquals(3, provider.calls.size)
    }

    @Test fun stepLimitEndsWithAReadableFailure(): Unit = runBlocking {
        val provider = AgentScriptProvider(run("""[{"tap":"go"}]"""))
        try {
            generate(provider)
            fail("The loop must stop at its step limit.")
        } catch (failure: CloudFailure) {
            assertEquals("widget_agent_incomplete", failure.code)
            assertFalse(failure.message.isNullOrBlank())
        }
        assertEquals(20, provider.calls.size)
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app"))
    }

    @Test fun followUpEditsTheSavedAppAndKeepsItsData(): Unit = runBlocking {
        val spec = ToolSpec.parse(tipApp().put("formatVersion", 3).put("capabilities", JSONArray()).toString())
        val saved = """{"bill":50,"tip":15,"people":2,"total":57.5,"share":28.75}"""
        val base = ToolRecord("agent-workflow", 1, 1, spec.json, saved, spec.ui.toString(), null, true)
        val edit = JSONObject().put("tool", "edit_app").put("set", JSONObject().put("ui", tipApp(withReset = true).getJSONObject("ui")))
        val resetCheck = JSONArray().put(JSONObject().put("name", "reset").put("steps", JSONArray("""
            [{"set":"bill","value":80},{"tap":"reset"},{"expect":"value","target":"bill","equals":0}]
        """.trimIndent())))
        val provider = AgentScriptProvider(edit.toString(), finish(resetCheck))
        val preview = generate(provider, "Add a reset button", base)
        val first = provider.calls.first().turns.joinToString("\n") { it.text }
        assertTrue(first, first.contains("Add a reset button"))
        assertTrue(first, first.contains("\"title\":\"Bill split\""))
        // Failure case: a follow-up that changed quiz points also restyled the app and removed the time column of the history.
        assertTrue(first, first.contains("Keep the other controls, texts, list columns, and layout as they are"))
        assertEquals(50, JSONObject(preview.state).getInt("bill"))
        assertTrue(preview.spec.ui.toString().contains("Reset"))
    }

    @Test fun drawingSurfaceAcceptsPointerChecks(): Unit = runBlocking {
        val app = JSONObject("""
            {"title":"Marks","description":"Tap to mark",
            "stateSchema":{"taps":"number","x":"number","y":"number"},"initialState":{"taps":0,"x":-1,"y":-1},
            "surfaces":[{"id":"board","width":300,"height":300}],
            "ui":{"type":"column","children":[{"type":"canvas","text":"Board","surfaces":["board"]},
              {"type":"text","text":"Taps","bind":"taps"},{"type":"button","text":"Clear","action":"clear"}]}}
        """.trimIndent()).put("source", "const s=input.state,e=input.event;" +
            "if(e.type==='pointer'&&e.phase==='down'){s.taps++;s.x=e.x;s.y=e.y;}" +
            "if(e.type==='action'&&e.actionId==='clear'){s.taps=0;s.x=-1;s.y=-1;}" +
            "const c=s.x<0?[]:[{type:'circle',x:s.x,y:s.y,radius:8,color:'#FF8800'}];" +
            "return {state:s,surfaces:{board:{commands:c}}};")
        val checks = JSONArray().put(JSONObject().put("name", "marks").put("steps", JSONArray("""
            [{"pointer":"board","x":0.2,"y":0.2},{"pointer":"board","x":0.8,"y":0.8},
             {"expect":"value","target":"taps","equals":2},{"tap":"clear"},{"expect":"value","target":"taps","equals":0}]
        """.trimIndent())))
        val provider = AgentScriptProvider(writeAppReply(app), run("""[{"pointer":"board","x":0.5,"y":0.5}]"""), finish(checks))
        generate(provider, "Tap the board to place one orange mark")
        assertTrue(provider.feedback(2), provider.feedback(2).contains("board 300x300: 1 drawing command within x 142..158, y 142..158"))
    }

    /** Failure case: a frame tick that vibrated at the end of a countdown got a message about a preview, and the agent spent 8 steps on it. */
    @Test fun anEffectFromAFrameNamesTheActionRule(): Unit = runBlocking {
        val app = JSONObject("""
            {"title":"Buzz","description":"Buzz one second after start","capabilities":["vibration.play"],
            "stateSchema":{"endAt":"number","running":"boolean","done":"boolean"},"initialState":{"endAt":0,"running":false,"done":false},
            "surfaces":[{"id":"main","width":100,"height":100}],
            "ui":{"type":"column","children":[{"type":"canvas","text":"Pulse","surfaces":["main"]},
              {"type":"toggle","text":"Done","bind":"done","action":"done"},{"type":"button","text":"Start","action":"start"}]}}
        """.trimIndent()).put("source", "const s=input.state,e=input.event;const out={state:s,surfaces:{main:{commands:[]}}};" +
            "if(e.type==='action'&&e.actionId==='start'){s.endAt=Date.now()+1000;s.running=true;}" +
            "if(e.type==='action'&&e.actionId==='done')s.done=e.value;" +
            "if(e.type==='frame'&&s.running&&Date.now()>=s.endAt){s.running=false;s.done=true;" +
            "out.effects=[{id:'buzz',capability:'vibration.play',args:{milliseconds:500}}];}" +
            "out.frames=s.running?['main']:[];return out;")
        val checks = JSONArray().put(JSONObject().put("name", "start").put("steps", JSONArray("""
            [{"tap":"start"},{"expect":"value","target":"done","equals":false}]
        """.trimIndent())))
        val provider = AgentScriptProvider(writeAppReply(app), run("""[{"tap":"start"},{"wait":2000}]"""), finish(checks))
        generate(provider, "Buzz once a second after start")
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: OK"))
        assertTrue(provider.feedback(2), provider.feedback(2).contains("Only action events may request effects"))
    }

    /** Failure case: every cell of a board had a button "Open", so the user and assistive technology could not tell the cells apart. */
    @Test fun gridButtonsWithOneLabelAreRejected(): Unit = runBlocking {
        fun board(label: (Int) -> String) = JSONObject("""
            {"title":"Stars","description":"Open cells","stateSchema":{"opened":"number"},"initialState":{"opened":0}}
        """.trimIndent()).put("ui", JSONObject().put("type", "column").put("children", JSONArray()
            .put(JSONObject("""{"type":"metric","text":"Opened","bind":"opened"}"""))
            .put(JSONObject().put("type", "grid").put("columns", 3).put("children", JSONArray((1..3).map { cell ->
                JSONObject().put("type", "card").put("children", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "Cell $cell"))
                    .put(JSONObject().put("type", "button").put("text", label(cell)).put("action", "c$cell")))
            })))))
            .put("source", "const s=input.state,e=input.event;if(e.type==='action')s.opened++;return {state:s};")
        val checks = JSONArray().put(JSONObject().put("name", "open").put("steps", JSONArray("""
            [{"tap":"c1"},{"expect":"value","target":"opened","equals":1}]
        """.trimIndent())))
        val provider = AgentScriptProvider(writeAppReply(board { "Open" }), writeAppReply(board { "Open $it" }), finish(checks))
        generate(provider, "A 3 by 3 board where I open cells")
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: REJECTED"))
        assertTrue(provider.feedback(1), provider.feedback(1).contains("\"Open\""))
        assertTrue(provider.feedback(2), provider.feedback(2).contains("write_app: OK"))
    }

    /** Failure case: a converter without a rate showed the literal None as its main result. */
    @Test fun aPlaceholderWordAsAValueIsRejected(): Unit = runBlocking {
        fun converter(placeholder: String) = JSONObject("""
            {"title":"Converter","description":"USD to EUR","stateSchema":{"usd":"number","eur":"string"},"initialState":{"usd":100,"eur":"$placeholder"},
            "ui":{"type":"column","children":[{"type":"numberInput","text":"Amount","bind":"usd","action":"usd","min":0,"max":1000000},
              {"type":"metric","text":"You get (EUR)","bind":"eur","style":{"emphasis":"number"}}]}}
        """.trimIndent()).put("source", "const s=input.state,e=input.event;if(e.type==='action')s.usd=e.value;return {state:s};")
        val checks = JSONArray().put(JSONObject().put("name", "amount").put("steps", JSONArray("""
            [{"set":"usd","value":50},{"expect":"value","target":"usd","equals":50}]
        """.trimIndent())))
        val provider = AgentScriptProvider(writeAppReply(converter("None")), writeAppReply(converter("Rate unavailable")), finish(checks))
        generate(provider, "Convert USD to EUR")
        assertTrue(provider.feedback(1), provider.feedback(1).contains("write_app: REJECTED"))
        assertTrue(provider.feedback(1), provider.feedback(1).contains("You get (EUR)"))
        assertTrue(provider.feedback(2), provider.feedback(2).contains("write_app: OK"))
    }

    @Test fun waitAdvancesTheClockThatTimersRead(): Unit = runBlocking {
        val app = JSONObject("""
            {"title":"Countdown","description":"One minute",
            "stateSchema":{"endAt":"number","status":"string"},"initialState":{"endAt":0,"status":"Ready"},
            "ui":{"type":"column","children":[{"type":"timer","text":"Time left","mode":"countdown","bind":"endAt"},
              {"type":"text","text":"Status","bind":"status"},{"type":"button","text":"Start","action":"start"}]}}
        """.trimIndent()).put("source", "const s=input.state,e=input.event;" +
            "if(e.type==='action'&&e.actionId==='start'){s.endAt=Date.now()+60000;s.status='Running';}" +
            "return {state:s};")
        val checks = JSONArray().put(JSONObject().put("name", "countdown").put("steps", JSONArray("""
            [{"tap":"start"},{"wait":30000},{"expect":"text","contains":"0:30"},{"expect":"text","contains":"Running"}]
        """.trimIndent())))
        val provider = AgentScriptProvider(writeAppReply(app), finish(checks))
        generate(provider, "One minute countdown")
        assertEquals(2, provider.calls.size)
    }

    private val anyScreen = JSONArray().put(JSONObject().put("name", "open").put("steps", JSONArray("""[{"expect":"absent","contains":"no such text"}]""")))

    /** Writes [app] with a check of [steps]. The check passes when the app finishes in one model call. */
    private suspend fun passesInOneCall(app: JSONObject, steps: String, prompt: String) {
        val checks = JSONArray().put(JSONObject().put("name", "drawn").put("steps", JSONArray(steps)))
        val provider = AgentScriptProvider(JSONObject(writeAppReply(app)).put("checks", checks).put("summary", "Ready.").toString(), finish(anyScreen))
        generate(provider, prompt)
        assertEquals(if (provider.calls.size > 1) provider.feedback(1) else "", 1, provider.calls.size)
    }

    private fun drawingApp(source: String, ui: String = """{"type":"column","children":[{"type":"canvas","text":"Board","surfaces":["main"]}]}""",
        surface: String = """{"id":"main","width":300,"height":200}""", schema: String = """{"n":"number"}""", state: String = """{"n":0}""") = JSONObject("""
            {"title":"Drawn","description":"Draws its own text","capabilities":[],"stateSchema":$schema,"initialState":$state,
            "surfaces":[$surface],"ui":$ui}
        """.trimIndent()).put("source", source)

    /** Failure case: games drew their score, but checks read only native nodes, so the agent added native score texts. */
    @Test fun drawnTextIsVisibleToChecks(): Unit = runBlocking {
        val app = drawingApp("const s=input.state,e=input.event;if(e.type==='pointer'&&e.phase==='down')s.n++;" +
            "return {state:s,surfaces:{main:{commands:[{type:'text',x:20,y:40,size:24,text:'Score '+(s.n+2),color:'default'}]}}};")
        passesInOneCall(app, """[{"pointer":"main","x":0.5,"y":0.5},{"expect":"text","contains":"Score 3"}]""", "Tap to score")
    }

    @Test fun immersiveSurfacesAppearInTheScreen(): Unit = runBlocking {
        val app = drawingApp("const s=input.state;return {state:s,surfaces:{main:{commands:[{type:'text',x:40,y:300,size:28,text:'Tap to start',color:'default'}]}}};",
            ui = """{"type":"column","children":[{"type":"text","text":"Settings"}]}""",
            surface = """{"id":"main","width":360,"height":640,"sizing":"viewport"}""").put("presentation", JSONObject().put("mode", "immersive"))
        val provider = AgentScriptProvider(writeAppReply(app), finish(anyScreen))
        generate(provider, "A game")
        assertTrue(provider.feedback(1), provider.feedback(1).contains("main 360x640") && provider.feedback(1).contains("Tap to start"))
    }

    /** Review focus: a drag that leaves the surface clamps to its edge. */
    @Test fun aDragStepSendsDownMovesAndUp(): Unit = runBlocking {
        val app = drawingApp("const s=input.state,e=input.event;" +
            "if(e.type==='pointer'&&e.phase!=='cancel'){s[e.phase]++;s.x=e.x;}" +
            "return {state:s,surfaces:{main:{commands:[{type:'text',x:10,y:30,size:16,text:'down '+s.down+' move '+s.move+' up '+s.up+' x '+Math.round(s.x),color:'default'}]}}};",
            schema = """{"down":"number","move":"number","up":"number","x":"number"}""", state = """{"down":0,"move":0,"up":0,"x":0}""")
        passesInOneCall(app, """[{"drag":"main","from":[0.5,0.5],"to":[1.2,0.5]},{"expect":"text","contains":"down 1 move 5 up 1 x 300"}]""", "Drag on the board")
    }

    @Test fun aTiltStepSendsOneSample(): Unit = runBlocking {
        val app = drawingApp("const s=input.state,e=input.event;" +
            "if(e.type==='action')return {state:s,effects:[{id:'tilt',capability:'sensor.accelerometer.stream',args:{enabled:true}}]};" +
            "if(e.type==='sensor')s.n=e.x;" +
            "return {state:s,surfaces:{main:{commands:[{type:'text',x:10,y:30,size:16,text:'tilt '+s.n.toFixed(1),color:'default'}]}}};",
            ui = """{"type":"column","children":[{"type":"canvas","text":"Board","surfaces":["main"]},{"type":"button","text":"Start tilt","action":"go"}]}""")
            .put("capabilities", JSONArray().put("sensor.accelerometer.stream"))
        passesInOneCall(app, """[{"tap":"go"},{"tilt":[2,0]},{"expect":"text","contains":"tilt 2.0"}]""", "Tilt the phone to roll a ball")
    }

    /** Review finding: a check tilted an inline app that never started tilt. On the phone, that app gets no tilt. */
    @Test fun aTiltStepNeedsAnImmersiveAppOrAStartedStream(): Unit = runBlocking {
        val source = "const s=input.state,e=input.event;if(e.type==='sensor')s.n=e.x;" +
            "return {state:s,surfaces:{main:{commands:[{type:'text',x:10,y:30,size:16,text:'tilt '+s.n.toFixed(1),color:'default'}]}}};"
        val app = drawingApp(source).put("capabilities", JSONArray().put("sensor.accelerometer.stream"))
        val checks = JSONArray().put(JSONObject().put("name", "tilt").put("steps", JSONArray("""[{"tilt":[2,0]},{"expect":"text","contains":"tilt 2.0"}]""")))
        val provider = AgentScriptProvider(JSONObject(writeAppReply(app)).put("checks", checks).put("summary", "Ready.").toString(), finish(anyScreen))
        generate(provider, "Tilt the phone to roll a ball")
        assertTrue(provider.feedback(1), provider.feedback(1).contains("immersive"))
        val immersive = drawingApp(source, ui = """{"type":"column","children":[{"type":"text","text":"Settings"}]}""",
            surface = """{"id":"main","width":360,"height":640,"sizing":"viewport"}""")
            .put("presentation", JSONObject().put("mode", "immersive")).put("capabilities", JSONArray().put("sensor.accelerometer.stream"))
        passesInOneCall(immersive, """[{"tilt":[2,0]},{"expect":"text","contains":"tilt 2.0"}]""", "Tilt the phone to roll a ball")
    }

    /** Review focus: drawn text from a busy frame must not flood the agent screen. */
    @Test fun drawnTextStaysShort(): Unit = runBlocking {
        val app = drawingApp("const s=input.state,c=[];for(let i=0;i<300;i++)c.push({type:'text',x:10,y:10+i,size:8,text:'w'+i,color:'default'});" +
            "return {state:s,surfaces:{main:{commands:c}}};")
        val provider = AgentScriptProvider(writeAppReply(app), finish(anyScreen))
        generate(provider, "Many words")
        val line = provider.feedback(1).lines().first { "main 300x200" in it }
        assertTrue(line, line.contains("w0. w1.") && line.length <= 520)
    }

    /** Review finding: the agent must see the unsupported drawing color and the command that holds it. */
    @Test fun aBadDrawingColorNamesItsValueAndPath(): Unit = runBlocking {
        val good = drawingApp("const s=input.state;return {state:s,surfaces:{main:{commands:[{type:'rect',x:0,y:0,width:300,height:200,color:'#10233F'}]}}};")
        val bad = JSONObject(good.toString()).put("source", good.getString("source").replace("'#10233F'", "'purple'"))
        val provider = AgentScriptProvider(writeAppReply(bad), writeAppReply(good), finish(anyScreen))
        generate(provider, "Draw a purple box")
        val feedback = provider.feedback(1)
        assertTrue(feedback, feedback.contains("Observed: \"purple\"") && feedback.contains("$.surfaces[0].commands[0].color"))
    }

    @Test fun theSmokeTestSendsATiltSample(): Unit = runBlocking {
        val good = drawingApp("const s=input.state,e=input.event;" +
            "if(e.type==='action')return {state:s,effects:[{id:'tilt',capability:'sensor.accelerometer.stream',args:{enabled:true}}]};" +
            "return {state:s,surfaces:{main:{commands:[]}}};",
            ui = """{"type":"column","children":[{"type":"canvas","text":"Board","surfaces":["main"]},{"type":"button","text":"Start tilt","action":"go"}]}""")
            .put("capabilities", JSONArray().put("sensor.accelerometer.stream"))
        val bad = JSONObject(good.toString()).put("source", "if(input.event.type==='sensor')throw new Error('tilt broke');" + good.getString("source"))
        val provider = AgentScriptProvider(writeAppReply(bad), writeAppReply(good), finish(anyScreen))
        generate(provider, "Tilt the phone to roll a ball")
        assertTrue(provider.feedback(1), provider.feedback(1).contains("REJECTED") && provider.feedback(1).contains("Smoke event: sensor"))
    }
}

/** The examples in the agent prompt must be valid apps whose documented checks pass. */
@RunWith(AndroidJUnit4::class)
class AgentGuideExampleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Provider(private vararg val replies: String) : ChatProvider {
        val calls = mutableListOf<CloudRequest>()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            calls += request
            val reply = replies[calls.lastIndex]
            emit(CloudEvent.Text(reply))
            emit(CloudEvent.RoundComplete(reply, false))
        }
    }

    private fun check(example: String, steps: String): Unit = runBlocking {
        val write = JSONObject().put("tool", "write_app").put("app", JSONObject(example)).toString()
        val finish = JSONObject().put("tool", "finish").put("summary", "Ready.")
            .put("checks", JSONArray().put(JSONObject().put("name", "example").put("steps", JSONArray(steps)))).toString()
        val provider = Provider(write, finish)
        val preview = ToolGenerationService(provider, SandboxClient(context))
            .generate("fixture", "Example", null, emptyList(), CloudSession(), "agent-example")
        val written = provider.calls[1].turns.last().text
        assertTrue(written, written.contains("write_app: OK"))
        // The examples follow their own guide, so the host gives them no design hint.
        assertFalse(written, written.contains("Design hints:"))
        assertEquals("en", preview.spec.appLanguage)
    }

    @Test fun converterExampleIsValid() = check(AppAgentGuide.exampleConverter, AppAgentGuide.exampleConverterCheck)

    @Test fun trackerExampleIsValid() = check(AppAgentGuide.exampleTracker, AppAgentGuide.exampleTrackerCheck)

    @Test fun gameExampleIsValid() = check(AppAgentGuide.exampleGame, AppAgentGuide.exampleGameCheck)
}

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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

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
        val broken = tipApp().apply { getJSONObject("ui").getJSONArray("children").getJSONObject(2).put("tone", "accent") }
        val provider = AgentScriptProvider(writeAppReply(broken), writeAppReply(tipApp()), finish(splitCheck))
        generate(provider)
        val feedback = provider.feedback(1)
        assertTrue(feedback, feedback.contains("This metric has unsupported properties: tone."))
        assertTrue(feedback, feedback.contains("Allowed:") && feedback.contains("style"))
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
        assertTrue(provider.calls[1].turns.last().text, provider.calls[1].turns.last().text.contains("write_app: OK"))
        assertEquals("en", preview.spec.appLanguage)
    }

    @Test fun calculatorExampleIsValid() = check(AppAgentGuide.exampleCalculator, AppAgentGuide.exampleCalculatorCheck)

    @Test fun listExampleIsValid() = check(AppAgentGuide.exampleList, AppAgentGuide.exampleListCheck)
}

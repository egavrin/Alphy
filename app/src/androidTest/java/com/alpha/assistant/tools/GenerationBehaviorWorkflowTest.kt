package com.alpha.assistant.tools

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.MainActivity
import com.alpha.assistant.cloud.CloudFailure
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.generation.GenerationBehavior
import com.alpha.assistant.tools.generation.PartsContractFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationBehaviorWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val appPlan = """
        {"slots":{"enter":{"kind":"action"},"apply":{"kind":"action"},
        "reset":{"kind":"action"},"result":{"kind":"value"}},
        "steps":[{"op":"capture","slot":"result","as":"initial"},{"op":"action","slot":"enter","value":7},
        {"op":"action","slot":"apply"},{"op":"assert","slot":"result","test":"equals","value":14},
        {"op":"action","slot":"reset"},{"op":"assert","slot":"result","test":"equals","capture":"initial"}]}
    """.trimIndent()
    private val appBindings = """{"enter":"amount","apply":"apply","reset":"reset","result":[2]}"""
    private val gamePlan = """
        {"slots":{"screen":{"kind":"surface"},"object":{"kind":"geometry"}},
        "steps":[{"op":"surface","slot":"screen","event":"restart"},{"op":"capture","slot":"object","as":"initial"},
        {"op":"frames","slot":"screen","count":4,"observe":"object","changes":3},
        {"op":"surface","slot":"screen","event":"restart"},{"op":"assert","slot":"object","test":"equals","capture":"initial"}]}
    """.trimIndent()

    private val offline = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            error("Authored checks do not call the provider.")
        }
    }

    private fun plan(scenario: String) = """{"status":"checkable","scenario":$scenario}"""
    private fun candidate(definition: String, bindings: String) =
        JSONObject(definition).put("behaviorBindings", JSONObject(bindings)).toString()

    private suspend fun check(scenario: String?, raw: String): ToolPreview {
        val json = JSONObject(raw)
        val bindings = json.remove("behaviorBindings")
        val sandbox = SandboxClient(context)
        val generation = ToolGenerationService(offline, sandbox)
        val spec = ToolSpec.parse(json.toString())
        val checker = ToolGenerationSmoke(sandbox, spec, "behavior", 1, spec.initialState, {}, generation)
        val preview = checker.run()
        if (scenario != null) {
            val check = GenerationBehavior.parse(JSONObject(plan(scenario)), emptySet()) as GenerationBehavior.Scenario
            checker.checkBehavior(check, GenerationBehavior.bindings(bindings, check.slots), preview,
                ToolGenerationRoute.WHOLE_SCREEN, emptySet())
        }
        return preview
    }

    private fun app(working: Boolean) = """
        {"formatVersion":3,"title":"Double","description":"","capabilities":[],
        "stateSchema":{"amount":"number","result":"number"},"initialState":{"amount":0,"result":0},
        "ui":{"type":"column","children":[{"type":"numberInput","text":"Amount","bind":"amount","action":"amount","min":0,"max":10},
        {"type":"button","text":"Double","action":"apply"},{"type":"metric","text":"Result","bind":"result"},
        {"type":"button","text":"Reset","action":"reset"}]},
        "source":"const {state:s,event:e}=input;if(e.type==='action'){if(e.actionId==='amount')s.amount=e.value;if(e.actionId==='apply')s.result=${if (working) "s.amount*2" else "s.amount"};if(e.actionId==='reset'){s.amount=0;s.result=0;}}return {state:s};"}
    """.trimIndent()

    @Test fun authoredCheckDistinguishesExactArithmeticFromAWorkingControl(): Unit = runBlocking {
        val working = check(appPlan, candidate(app(true), appBindings))
        assertEquals(0, JSONObject(working.state).getInt("result"))
        val failure = runCatching { check(appPlan, candidate(app(false), appBindings)) }.exceptionOrNull()
        assertTrue(failure is BehaviorCheckFailure)
        assertTrue((failure as BehaviorCheckFailure).detail.contains("assertion failed"))
    }

    @Test fun aThreeJsExclusionAllowsANativeAppToSaveAndReopen(): Unit = runBlocking {
        val original = "Build a native mini-app that doubles the entered number. Do not use Three.js."
        val brief = "$original\nSuggested implementation: use a Three.js viewport."
        val reference = "data:image/png;base64,native-reference"
        val stages = mutableListOf<CloudGenerationStage?>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                stages += request.generationStage
                val raw = when (request.generationStage) {
                    CloudGenerationStage.PLAN -> {
                        assertEquals(request.turns.last().text, 1, request.turns.size)
                        val user = request.turns.single()
                        val payload = JSONObject(user.text)
                        assertEquals(original, payload.getString("request"))
                        assertEquals(brief, payload.getString("context"))
                        assertEquals(listOf(reference), user.images)
                        assertTrue(request.systemInstructions.contains("If the plan uses Three.js"))
                        assertFalse(request.systemInstructions.contains("require a Three.js plan"))
                        assertFalse(request.systemInstructions.contains("orbit the Sun"))
                        assertFalse(request.systemInstructions.contains("animate the orbit"))
                        JSONObject("""{"wholeScreen":true,"language":"en"}""")
                            .put("behavior", JSONObject(plan(appPlan))).toString()
                    }
                    CloudGenerationStage.COMPLETE -> {
                        val user = request.turns.single()
                        val payload = JSONObject(user.text)
                        assertEquals(original, payload.getString("request"))
                        assertEquals(brief, payload.getString("context"))
                        assertEquals(listOf(reference), user.images)
                        assertTrue(request.systemInstructions.contains(GenerationBehavior.workflowScopeGuidance))
                        candidate(app(true), appBindings)
                    }
                    else -> error("Unexpected generation stage ${request.generationStage}")
                }
                emit(CloudEvent.Text(raw))
            }
        }
        val database = "native-runtime-exclusion-${java.util.UUID.randomUUID()}.db"
        try {
            ToolStore(context, database).use { store ->
                val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                try {
                    val draft = engine.createDraft(brief, null)
                    engine.generate(draft.id, "fixture", listOf(reference), CloudSession(), previewObserver = null,
                        trace = null, originalRequest = original)
                    val saved = engine.accept(draft.id)
                    assertFalse(ToolSpec.parsePersisted(saved.spec).threeScene)
                    val entered = engine.interact(saved.id, saved.revision, saved.stateVersion, "amount", 7)
                    val doubled = engine.interact(saved.id, saved.revision, entered.stateVersion, "apply", null)
                    val reopened = engine.openArtifact(doubled)
                    try {
                        assertEquals(14, JSONObject(reopened.tool.state).getInt("result"))
                        assertEquals(1, store.revisions(saved.id).size)
                    } finally { engine.closeArtifact(saved.id, saved.revision, reopened.sessionGeneration) }
                    assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE), stages)
                } finally { engine.closeArtifacts() }
            }
        } finally { context.deleteDatabase(database) }
    }

    private val noImagePlan = """{"disposition":"unchanged","excerpt":"","imageRequests":[],"removeImageSlots":[]}"""

    @Test fun newAppKeepsItsExactWorkflowDuringVisualRefinement(): Unit = runBlocking {
        val request = "Double the entered number and use a bright result label."
        val reference = "data:image/png;base64,fixture-reference"
        val stages = mutableListOf<CloudGenerationStage?>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, call: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                stages += call.generationStage
                val raw = when (call.generationStage) {
                    CloudGenerationStage.PLAN -> if (call.systemInstructions.startsWith("Plan only native mini-app illustrations.")) {
                        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.PLAN), stages)
                        assertEquals(listOf(reference), call.turns.single().images)
                        assertEquals(request, JSONObject(call.turns.single().text).getString("request"))
                        noImagePlan
                    } else JSONObject("""{"wholeScreen":true,"language":"en"}""")
                        .put("behavior", JSONObject(plan(appPlan)))
                        .put("visualReview", org.json.JSONArray().put("use a bright result label.")).toString()
                    CloudGenerationStage.COMPLETE -> candidate(app(true), appBindings)
                    CloudGenerationStage.REFINE -> {
                        assertTrue(call.turns.any { reference in it.images && request in it.text })
                        """{"fields":{"ui":{"type":"column","children":[
                            {"type":"numberInput","text":"Amount","bind":"amount","action":"amount","min":0,"max":10},
                            {"type":"button","text":"Double","action":"apply"},
                            {"type":"metric","text":"Bright result","bind":"result"},
                            {"type":"button","text":"Reset","action":"reset"}]}}}"""
                    }
                    else -> error("Unexpected generation stage")
                }
                emit(CloudEvent.Text(raw))
            }
        }
        val preview = ToolGenerationService(provider, SandboxClient(context)).generate("fixture", request, null,
            listOf(reference), CloudSession(), "staged-native")
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.PLAN,
            CloudGenerationStage.REFINE), stages)
        assertTrue(preview.spec.generatedImages.isEmpty())
        assertTrue(preview.ui.contains("Bright result"))
        assertEquals(0, JSONObject(preview.state).getInt("result"))
        check(appPlan, candidate(preview.spec.providerDefinition(), appBindings))
    }

    @Test fun failedNativePolishCanSaveOrCancelTheVerifiedCore(): Unit = runBlocking {
        val request = "Double the entered number and use a bright result label."
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, call: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                val raw = when (call.generationStage) {
                    CloudGenerationStage.PLAN -> if (call.systemInstructions.startsWith("Plan only native mini-app illustrations."))
                        noImagePlan
                    else JSONObject("""{"wholeScreen":true,"language":"en"}""")
                        .put("behavior", JSONObject(plan(appPlan)))
                        .put("visualReview", org.json.JSONArray().put("use a bright result label.")).toString()
                    CloudGenerationStage.COMPLETE -> candidate(app(true), appBindings)
                    CloudGenerationStage.REFINE -> """{"fields":{"behaviorBindings":{"apply":"reset"}}}"""
                    else -> error("Unexpected generation stage")
                }
                emit(CloudEvent.Text(raw))
            }
        }
        val database = "staged-native-fallback-${java.util.UUID.randomUUID()}.db"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        for (choice in listOf("Cancel", "Save")) {
                            val draft = engine.createDraft(request, null)
                            val generated = runCatching {
                                withTimeout(30_000) {
                                    val click = launch(Dispatchers.IO) {
                                        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                                        while (true) {
                                            val root = automation.rootInActiveWindow
                                            val notice = root?.findAccessibilityNodeInfosByText("Missing: requested appearance changes").orEmpty()
                                            val buttons = root?.findAccessibilityNodeInfosByText(choice).orEmpty()
                                            if (notice.isNotEmpty() && buttons.any {
                                                    it.isClickable && it.text?.toString().equals(choice, ignoreCase = true) &&
                                                        it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                                }) break
                                            delay(100)
                                        }
                                    }
                                    try { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                                    finally { click.cancelAndJoin() }
                                }
                            }
                            if (choice == "Cancel") {
                                val failure = generated.exceptionOrNull()
                                if (failure !is CloudFailure) throw AssertionError("Expected the native preview to be declined.", failure)
                                assertEquals("widget_declined", failure.code)
                                assertNull(store.draft(draft.id)?.candidate)
                                assertNull(store.tool(draft.id))
                                engine.reject(draft.id)
                            } else {
                                generated.getOrThrow()
                                val saved = engine.accept(draft.id, retainReceipt = true)
                                assertEquals(0, JSONObject(saved.state).getInt("result"))
                                assertFalse(saved.ui.contains("Bright result"))
                                assertEquals(saved.revision, store.draft(draft.id)?.acceptedRevision)
                                assertTrue(runCatching { engine.accept(draft.id) }.isFailure)
                            }
                        }
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun capturedTipWriterActionPathsPassTheBehaviorCheck(): Unit = runBlocking {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("behavior-replay/tip-action-paths.json").bufferedReader().use { JSONObject(it.readText()) }
        check(fixture.getJSONObject("scenario").toString(), fixture.getJSONObject("candidate").toString())
    }

    @Test fun visibleSingleChoicesReportTheirChangedSelection(): Unit = runBlocking {
        val scenario = """{"slots":{"choose":{"kind":"action"},"selection":{"kind":"value"}},"steps":[
            {"op":"capture","slot":"selection","as":"before"},{"op":"action","slot":"choose","value":"week"},
            {"op":"assert","slot":"selection","test":"changed","capture":"before"},
            {"op":"assert","slot":"selection","test":"equals","value":"week"}]}"""
        val bindings = """{"choose":"period","selection":[0]}"""
        val dashboard = JSONObject("""{"formatVersion":3,"title":"Activity dashboard","description":"","capabilities":[],
            "stateSchema":{"period":"string"},"initialState":{"period":"day"},"ui":{"type":"column","children":[
            {"type":"segmented","text":"Period","bind":"period","action":"period",
            "options":[{"text":"Day","value":"day"},{"text":"Week","value":"week"}]}]}}""")
        val select = "if(input.event.actionId==='period')input.state.period=input.event.value;"
        val ignored = "return {state:input.state};"
        val hidden = """return {state:input.state,ui:{type:'column',children:[{type:'segmented',text:'Period',
            bind:'period',action:'period',visible:input.state.period!=='week',
            options:[{text:'Day',value:'day'},{text:'Week',value:'week'}]}]}};"""
        val choice = dashboard.getJSONObject("ui").getJSONArray("children").getJSONObject(0)
        for (type in ToolUiCatalog.choiceTypes - "multiChoice") {
            choice.put("type", type)
            check(scenario, candidate(dashboard.put("source", select + ignored).toString(), bindings))
        }
        choice.put("type", "segmented")
        val ignoredFailure = runCatching { check(scenario, candidate(dashboard.put("source", ignored).toString(), bindings)) }.exceptionOrNull()
        assertTrue("A static selection fails its assertion", (ignoredFailure as? BehaviorCheckFailure)?.detail.orEmpty().contains("assertion failed"))
        val hiddenFailure = runCatching { check(scenario, candidate(dashboard.put("source", select + hidden).toString(), bindings)) }.exceptionOrNull()
        assertTrue("A hidden selection cannot satisfy the assertion",
            (hiddenFailure as? BehaviorCheckFailure)?.detail.orEmpty().contains("visible output unavailable"))
    }

    @Test fun malformedBindingsAndUnavailableControlsFailIndependently(): Unit = runBlocking {
        val wrongType = JSONObject(appBindings).put("apply", true).toString()
        val missing = JSONObject(appBindings).put("apply", "missing").toString()
        assertTrue(runCatching { check(appPlan, candidate(app(true), wrongType)) }.exceptionOrNull() is GenerationBehavior.BindingFailure)
        assertTrue(runCatching { check(appPlan, candidate(app(true), missing)) }.exceptionOrNull() is BehaviorCheckFailure)
    }

    @Test fun gameRequiresRepeatedGeometryChangeAndReset(): Unit = runBlocking {
        val game = GameGenerationExample.spec()
        val bindings = """{"screen":"main","object":{"surface":"main","path":[96]}}"""
        val raw = candidate(game, bindings)
        val result = check(gamePlan, raw)
        assertTrue(result.spec.surfaces.containsKey("main"))
        val stationary = JSONObject(game).apply {
            put("source", getString("source").replace("if(e.type==='frame'&&s.status==='playing')", "if(false)"))
        }.toString()
        val broken = candidate(stationary, bindings)
        assertTrue(runCatching { check(gamePlan, broken) }.exceptionOrNull() is BehaviorCheckFailure)
    }

    @Test fun gameReceivesViewportAndAutomaticStartBeforeTheScenario(): Unit = runBlocking {
        val raw = movingDrawing("""[{type:'circle',x:s.x,y:24,radius:5,color:'accent'}]""")
        val result = check(automaticMotionPlan, raw)
        assertEquals(20, JSONObject(result.state).getInt("x"))
    }

    @Test fun aScenarioCannotUseAnUndeclaredRestartButton(): Unit = runBlocking {
        val definition = JSONObject(GameGenerationExample.spec()).apply { remove("hostControls") }
        val raw = candidate(definition.toString(), """{"screen":"main","object":{"surface":"main","path":[96]}}""")
        val failure = runCatching { check(gamePlan, raw) }.exceptionOrNull()
        assertTrue(failure is BehaviorCheckFailure)
        assertTrue((failure as? BehaviorCheckFailure)?.detail.orEmpty().contains("playback control unavailable"))
    }

    @Test fun aVisibleButtonCanBeTheActionAndItsObservableResult(): Unit = runBlocking {
        val scenario = """{"slots":{"target":{"kind":"action"},"label":{"kind":"value"}},"steps":[
            {"op":"action","slot":"target"},{"op":"assert","slot":"label","test":"equals","value":"On"}]}"""
        val definition = JSONObject("""{"formatVersion":3,"title":"Switch","description":"","capabilities":[],
            "stateSchema":{"on":"boolean"},"initialState":{"on":false},
            "ui":{"type":"button","text":"Off","action":"switch"}}""").put("source", """
            const s=input.state,e=input.event;
            if(e.type==='action'&&e.actionId==='switch')s.on=!s.on;
            return {state:s,ui:{type:'button',text:s.on?'On':'Off',action:'switch'}};
        """.trimIndent())
        val raw = candidate(definition.toString(), """{"target":"switch","label":[]}""")
        assertFalse(JSONObject(check(scenario, raw).state).getBoolean("on"))
    }

    @Test fun aPointerScenarioMustCheckMoreThanPreexistingInk(): Unit = runBlocking {
        val scenario = """{"slots":{"screen":{"kind":"surface"},"drawing":{"kind":"geometry"}},"steps":[
            {"op":"capture","slot":"drawing","as":"before"},
            {"op":"surface","slot":"screen","event":"pointer","phase":"down","x":0.5,"y":0.5},
            {"op":"assert","slot":"drawing","test":"nonempty"}]}"""
        for (test in listOf("\"nonempty\"", "\"unchanged\",\"capture\":\"before\"", "\"equals\",\"capture\":\"before\"")) {
            val failure = runCatching {
                GenerationBehavior.parse(JSONObject(plan(scenario.replace("\"nonempty\"", test))), emptySet())
            }.exceptionOrNull()
            assertTrue(failure is PartsContractFailure)
            assertTrue(failure?.message.orEmpty().contains("after the pointer action"))
        }
    }

    @Test fun smokeStartReachesEverySurfaceEvenAfterPlaybackChanges(): Unit = runBlocking {
        val definition = JSONObject("""{"formatVersion":3,"title":"Two surfaces","description":"","capabilities":[],
            "stateSchema":{"status":"string"},"initialState":{"status":"ready"},"ui":{"type":"text","text":"Ready"},
            "surfaces":[{"id":"a","width":64,"height":64},{"id":"b","width":64,"height":64}],
            "hostControls":{"playback":true}}""").put("source", """
            const s = input.state, e = input.event;
            if (e.type === 'start' && e.surfaceId === 'a') s.status = 'playing';
            if (e.type === 'start' && e.surfaceId === 'b') throw Error('fixture');
            return {state:s,playback:s.status};
        """.trimIndent())
        val raw = definition.toString()
        assertTrue(runCatching { check(null, raw) }.exceptionOrNull() is ToolSmokeFailure)
    }

    @Test fun artifactRejectsMalformedHostControlDeclarations() {
        val declaration = JSONObject(GameGenerationExample.spec())
        val valid = JSONObject("""{"playback":true,"movement":["main"]}""")
        assertEquals(valid.toString(), JSONObject(ToolSpec.parse(declaration.put("hostControls", valid).toString())
            .providerDefinition()).getJSONObject("hostControls").toString())
        for (invalid in listOf(JSONObject.NULL, "true", JSONObject("""{"playback":"true"}"""),
            JSONObject("""{"extra":true}"""), JSONObject("""{"movement":["main","main"]}"""),
            JSONObject("""{"movement":["missing"]}"""), JSONObject("""{"movement":[1]}"""))) {
            assertTrue(invalid.toString(), runCatching { ToolSpec.parse(declaration.put("hostControls", invalid).toString()) }
                .exceptionOrNull() is ToolValidationFailure)
        }
    }

    @Test fun motionRemainsObservableOverAnOpaqueBackground(): Unit = runBlocking {
        val drawing = """[{type:'rect',x:0,y:0,width:64,height:64,color:'#000000'},
            {type:'circle',x:s.x,y:24,radius:5,color:'#FFFFFF'}]"""
        val raw = movingDrawing(drawing, path = "[]")
        assertNotNull(check(automaticMotionPlan, raw).surfaces)
    }

    @Test fun anObjectThatMatchesItsBackgroundCannotSatisfyMotion(): Unit = runBlocking {
        val drawing = """[{type:'rect',x:0,y:0,width:64,height:64,color:'#000000'},
            {type:'circle',x:s.x,y:24,radius:5,color:'#000000'}]"""
        for (path in listOf("[]", "[1]")) assertMotionRejected(drawing, path)
    }

    @Test fun checkedCandidateStillRejectsNonIntegerProtocolVersion(): Unit = runBlocking {
        val raw = app(true).replace("\"formatVersion\":3", "\"formatVersion\":3.0")
        assertTrue(runCatching { ToolSpec.parse(raw) }.exceptionOrNull() is ToolValidationFailure)
    }

    @Test fun invisibleOrStationaryRenderedGeometryCannotSatisfyMotion(): Unit = runBlocking {
        val drawings = listOf(
            """[{type:'circle',x:s.x,y:24,radius:0,color:'accent'}]""",
            """[{type:'circle',x:s.x,y:24,radius:5,color:'#00FF0000'}]""",
            """[{type:'circle',x:s.x,y:94,radius:5,color:'accent'}]""",
            """[{type:'circle',x:20,y:24,radius:5,color:s.x%2?'#FF0000':'#0000FF'}]""",
            """[{type:'group',transform:[1,0,0,1,s.x,0],children:[{type:'circle',x:40-s.x,y:24,radius:5,color:'accent'}]}]"""
        )
        for (drawing in drawings) assertMotionRejected(drawing)
    }

    private suspend fun assertMotionRejected(drawing: String, path: String = "[0]") {
        val raw = movingDrawing(drawing, path)
        val failure = runCatching { check(automaticMotionPlan, raw) }.exceptionOrNull()
        assertTrue(drawing, failure is BehaviorCheckFailure)
        assertTrue((failure as? BehaviorCheckFailure)?.detail.orEmpty().contains("repeated geometry change"))
    }

    private val automaticMotionPlan = """
        {"slots":{"screen":{"kind":"surface"},"object":{"kind":"geometry"}},
        "steps":[{"op":"frames","slot":"screen","count":4,"observe":"object","changes":3}]}
    """.trimIndent()

    private fun movingDrawing(commands: String, path: String = "[0]"): String {
        val definition = JSONObject("""{"formatVersion":3,"title":"Motion","description":"","capabilities":[],
            "stateSchema":{"x":"number","viewport":"boolean","started":"boolean"},
            "initialState":{"x":20,"viewport":false,"started":false},"ui":{"type":"text","text":"Motion"},
            "surfaces":[{"id":"main","width":64,"height":64,"sizing":"viewport"}],"source":""}""")
        definition.put("source", """
            const s=input.state,e=input.event;
            if(e.type==='viewport')s.viewport=true;
            if(e.type==='start'&&s.viewport)s.started=true;
            if(e.type==='frame')s.x+=3;
            return {state:s,frames:s.started?['main']:[],surfaces:{main:{commands:$commands}}};
        """.trimIndent())
        return candidate(definition.toString(), """{"screen":"main","object":{"surface":"main","path":$path}}""")
    }

    @Test fun aStatusOnlyGameAndAnUncalledSimulationCannotPass(): Unit = runBlocking {
        val bindings = """{"screen":"main","object":{"surface":"main","path":[0]}}"""
        for (source in listOf("", "function simulate(s){s.y-=3;return s;}")) {
            val game = JSONObject("""{"formatVersion":3,"title":"False game","description":"","capabilities":[],
                "stateSchema":{"y":"number"},"initialState":{"y":300},"ui":{"type":"button","text":"Start","action":"start"},
                "source":""}""").apply {
                put("source", source + "return {state:input.state};")
            }.toString()
            val raw = candidate(game, bindings)
            val failure = runCatching { check(gamePlan, raw) }.exceptionOrNull()
            assertTrue(failure is BehaviorCheckFailure)
        }
    }
}

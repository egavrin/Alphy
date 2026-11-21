package com.alpha.assistant.tools

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudGenerationStage.AGENT
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.RuntimeFailure
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxResult
import com.alpha.assistant.tools.agent.AppAgent
import com.alpha.assistant.tools.agent.AppAgentRequest
import com.alpha.assistant.tools.agent.AppFileNames
import com.alpha.assistant.tools.agent.AppFileOutcome
import com.alpha.assistant.tools.agent.AppFileSteps
import com.alpha.assistant.tools.agent.ScreenFallback
import com.alpha.assistant.tools.agent.ScreenRun
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan
import com.alpha.assistant.ui.FIXTURE_APP
import com.alpha.assistant.ui.NO_TEMPLATE
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.answerNoTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Each way out of the plan step of a new app, through the real AppAgent, the real AppFileCompiler, and the real sandbox, with scripted replies.
 * A clean plan becomes the app with no agent call. Each other outcome goes on with the loop of today, and the loop gets nothing of the plan.
 */
@RunWith(AndroidJUnit4::class)
class AppFileLoopWorkflowTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val calc get() = plan("calc")
    /** calc with a fault in line 5, the line of perPerson. */
    private val brokenCalc get() = calc.replace("/ \$/people, example", "/ , example")
    /** The replies of the loop: one write of the fixture app and a finish. */
    private val loop = listOf(Scripted.Text(agentWrite(FIXTURE_APP)), Scripted.Text(agentFixtureFinish))

    private class Generated(val script: AgentScriptProvider, events: List<ToolGenerationTraceEvent>, val result: Result<ToolPreview>) :
        TracedGeneration(script.calls, events) {
        val spec get() = result.getOrThrow().spec
    }

    /** One generation of [PROMPT] with the plan step at [planStep]. [provider] can wrap the script. [screen]: a screen run. */
    private fun generate(replies: List<Scripted>, planStep: AppFileLimits? = AppFileLimits(500, 2000), images: List<String> = emptyList(),
        session: CloudSession = CloudSession(), provider: (AgentScriptProvider) -> ChatProvider = { it },
        progress: (ToolGenerationProgress) -> Unit = {}, onPhase: (ToolGenerationTraceEvent.Phase) -> Unit = {},
        screen: ScreenRun? = null): Generated {
        val script = AgentScriptProvider(replies)
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val trace = ToolGenerationTrace(UUID.randomUUID(), { event -> events += event; (event as? ToolGenerationTraceEvent.Phase)?.let(onPhase) })
        val result = runBlocking {
            runCatching {
                ToolGenerationService(provider(script), SandboxClient(context)).generate("fixture", PROMPT, null, images, session,
                    "appfile-loop", privateState = false, progress = progress, trace = trace, planStep = planStep, screen = screen)
            }
        }
        return Generated(script, events, result)
    }

    /** The reason of the ScreenFallback of [run], or null when it ended in another way. */
    private fun fallback(run: Generated) = (run.result.exceptionOrNull() as? ScreenFallback)?.reason

    /** A sandbox double: the smoke step of each host check throws the next of [failures], as the sandbox does, and then runs for real. */
    private class Sandbox(vararg thrown: Exception) {
        private val failures = ArrayDeque(thrown.toList())
        /** The smoke steps that started, the failed ones too. */
        var runs = 0
        val smoke: suspend (ToolGenerationSmoke) -> ToolPreview = { runs++; failures.removeFirstOrNull()?.let { throw it } ?: it.run() }
    }

    /**
     * One generation of [PROMPT] whose plan call replies [calc], through an AppAgent whose host checks run their smoke step through [smoke].
     * [screen]: a screen run.
     */
    private fun checked(smoke: suspend (ToolGenerationSmoke) -> ToolPreview, session: CloudSession = CloudSession(),
        screen: ScreenRun? = null): Generated {
        val script = AgentScriptProvider(loop)
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val run = ToolGenerationTrace(UUID.randomUUID(), { events += it }).Run()
        val sandbox = SandboxClient(context)
        val service = ToolGenerationService(script, sandbox)
        val agent = AppAgent(service, sandbox, appFileCall = { _, _, _, _ -> AppFileReply.Text(calc) }, runSmoke = smoke) { turns, instructions ->
            service.response("fixture", turns, session, AGENT, instructions, expandedWriterOutput = true)
        }
        val request = AppAgentRequest(PROMPT, null, emptyList(), "appfile-loop", privateState = false, onPhase = run::phase,
            planStep = AppFileLimits(500, 2000), screen = screen)
        return Generated(script, events, runBlocking { runCatching { agent.run(request, session).preview } })
    }

    // Failure case: each new app paid a loop of many model calls, also when one plan call gave a clean app.
    @Test fun aCleanPlanIsAcceptedWithOneCall() {
        val run = generate(listOf(Scripted.Text(calc)))
        val call = run.script.calls.single()
        assertEquals(APP_FILE, call.generationStage)
        assertFalse(call.jsonObject)
        assertEquals(AppFilePrompt.load(context).system, call.systemInstructions)
        assertEquals(listOf(PROMPT), call.turns.map { it.text })
        assertEquals(calc, run.spec.appFile)
        assertEquals("Tip split", run.spec.title)
        assertEquals("en", run.spec.appLanguage)
        // One call, no capability guidance, and no agent step.
        assertEquals(listOf("plan_call_start", "plan_first_text", "plan_reply_end", "compile", "plan_outcome"), run.phases.map { it.name })
        assertEquals(listOf("ok"), run.compiles)
        assertEquals("accepted", run.outcome)
        assertTrue(run.events.none { it is ToolGenerationTraceEvent.AgentStep })
    }

    // Failure case: a request that the kit cannot serve lost the loop, or the loop got text of the plan step.
    @Test fun anOutsideReplyGoesToTheLoop() {
        val run = generate(listOf(Scripted.Text("outside(\"game\")")) + loop)
        assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("outside:game", run.outcome)
        assertNull(run.spec.appFile)
        assertEquals("Fixture", run.spec.title)
        assertEquals(listOf("plan_outcome", "capability_guidance_start", "capability_guidance_end", "agent_call_start"),
            run.phases.map { it.name }.dropWhile { it != "plan_outcome" })
        // The first agent call is the call of today: the same instructions and the same task as with the switch off.
        val today = generate(loop, planStep = null).script.calls.first()
        val first = run.script.calls[1]
        assertEquals(today.systemInstructions, first.systemInstructions)
        assertEquals(today.turns.map { withoutTime(it.text) }, first.turns.map { withoutTime(it.text) })
    }

    @Test fun faultsThenACleanRepairAreAccepted() {
        val line = calc.lines().single { it.startsWith("need perPerson") }
        val run = generate(listOf(Scripted.Text(brokenCalc), Scripted.Text("Here is the fix:\n```\n$line\n```")))
        assertEquals(listOf(APP_FILE, APP_FILE), run.stages)
        val repair = run.script.calls[1]
        val text = repair.turns.single().text
        assertTrue(text, text.startsWith(PROMPT) && "\n- line 5: " in text && brokenCalc.trimEnd() in text)
        assertEquals(AppFilePrompt.load(context).system, repair.systemInstructions)
        assertFalse(repair.jsonObject)
        // The merged plan is the stored plan.
        assertEquals(calc, run.spec.appFile)
        assertEquals(listOf("faults", "ok"), run.compiles)
        assertEquals(1, run.phases.count { it.name == "repair_call_start" })
        assertEquals("accepted", run.outcome)
    }

    @Test fun faultsTwiceGoToTheLoop() {
        val broken = brokenCalc.lines().single { it.startsWith("need perPerson") }
        val run = generate(listOf(Scripted.Text(brokenCalc), Scripted.Text(broken)) + loop)
        assertEquals(listOf(APP_FILE, APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("fallback:faults", run.outcome)
        assertNull(run.spec.appFile)
    }

    @Test fun aRepairThatAnswersOutsideGoesToTheLoop() {
        val run = generate(listOf(Scripted.Text(brokenCalc), Scripted.Text("outside(\"game\")")) + loop)
        assertEquals(listOf(APP_FILE, APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("fallback:outside", run.outcome)
    }

    @Test fun aPlanWithAHoleGoesToTheLoop() {
        val run = generate(listOf(Scripted.Text(calc + "need chart = hole(\"A bar chart of the split\")\n")) + loop)
        // No repair call: a hole is a part that the kit does not have.
        assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("fallback:hole", run.outcome)
    }

    @Test fun aFailedPlanCallGoesToTheLoop() {
        val run = generate(listOf(Scripted.Fails("pre_headers_io")) + loop)
        assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("fallback:call_failed", run.outcome)
        assertEquals("Fixture", run.spec.title)
    }

    @Test fun aSlowPlanCallGoesToTheLoopAtItsLimit() {
        val run = generate(listOf(Scripted.Waits(5000, calc)) + loop)
        assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("fallback:first_text_timeout", run.outcome)
        // The guidance runs between the outcome and the first agent call, so the limit is measured to the outcome.
        val ms = run.msBetween("plan_call_start", "plan_outcome")
        assertTrue("$ms ms", ms in 500..1500)
    }

    @Test fun aCallWithAFirstTextThatRunsPastItsTotalGoesToTheLoop() {
        val run = generate(listOf(Scripted.Stalls("need total = figure(\"money\", \"Total\", 1)", 5000)) + loop)
        assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("fallback:total_timeout", run.outcome)
        val ms = run.msBetween("plan_call_start", "plan_outcome")
        assertTrue("$ms ms", ms in 2000..3000)
    }

    // Failure case: a plan that the host checks of today refuse became the app.
    @Test fun aHostCheckFailureGoesToTheLoop() {
        // The visible text is English, and the plan says Russian: the language check of validate fails.
        val run = generate(listOf(Scripted.Text(calc.replace("lang=\"en\"", "lang=\"ru\""))) + loop)
        assertEquals(listOf("ok"), run.compiles)
        assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
        // A fault of the plan gets one try, and the trace names the check that failed.
        assertEquals("fallback:host_check:language", run.outcome)
        assertTrue(run.phases.none { it.name.startsWith("host_check_retry") })
        assertNull(run.spec.appFile)
    }

    // Failure case: validate does not catch a raw number such as 28.333333333333332. Only finish checked it.
    @Test fun aPlanThatShowsALongDecimalGoesToTheLoop() {
        val third = "app(\"Third\", lang=\"en\")\nneed a = input(\"number\", \"A\", 85, min=0, max=100)\n" +
            "need b = input(\"number\", \"B\", 3, min=1, max=10)\n\$/r := \$/a / \$/b\nroot = Column([a_field, b_field, Text(text(\$/r))])\n"
        val run = generate(listOf(Scripted.Text(third)) + loop)
        assertEquals(listOf("ok"), run.compiles)
        assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("fallback:host_check:long_decimal", run.outcome)
    }

    // Failure case: one sandbox evaluation over its deadline sent a correct plan to the loop, and the trace kept no reason.
    @Test fun aTimeoutOfTheHostCheckGetsOneMoreTry() {
        val sandbox = Sandbox(timeout())
        val run = checked(sandbox.smoke)
        assertEquals(calc, run.spec.appFile)
        assertEquals(2, sandbox.runs)
        assertEquals(listOf("plan_call_start", "compile", "host_check_retry:javascript_timeout", "plan_outcome"), run.phases.map { it.name })
        assertEquals("accepted", run.outcome)
        assertTrue(run.stages.isEmpty())
    }

    @Test fun twoTimeoutsOfTheHostCheckGoToTheLoopWithTheirReason() {
        // A deadline of an event, then a deadline of the readiness check of the next isolate.
        val run = checked(Sandbox(timeout(), RuntimeFailure("TIMEOUT")).smoke)
        assertEquals(1, run.phases.count { it.name == "host_check_retry:javascript_timeout" })
        assertEquals("fallback:host_check:javascript_timeout", run.outcome)
        assertEquals(listOf(AGENT, AGENT), run.stages)
        assertEquals("Fixture", run.spec.title)
    }

    // Failure case: a stop of the person between the two tries started the second try or the loop.
    @Test fun aStopBetweenTheTriesOfTheHostCheckEndsTheRequest() {
        val session = CloudSession()
        val sandbox = Sandbox(timeout())
        val run = checked({ session.cancel(); sandbox.smoke(it) }, session)
        assertEquals("cancelled", (run.result.exceptionOrNull() as CloudFailure).code)
        assertEquals(1, sandbox.runs)
        assertTrue(run.phases.any { it.name == "host_check_retry:javascript_timeout" })
        assertTrue(run.phases.none { it.name == "plan_outcome" || it.name == "capability_guidance_start" })
    }

    // Failure case: a person who used an open app while the plan was checked lost the plan.
    @Test fun aBusyHostCheckGetsOneMoreTry() {
        var slot: Slot? = null
        try {
            // The open app holds the slot from the end of the compile to the retry.
            val run = generate(listOf(Scripted.Text(calc)) + loop, onPhase = {
                if (it.name == "compile" && it.detail == "ok") slot = Slot(context)
                if (it.name == "host_check_retry:javascript_busy") slot?.release()
            })
            assertEquals(listOf(APP_FILE), run.stages)
            assertEquals(calc, run.spec.appFile)
            assertEquals(1, run.phases.count { it.name == "host_check_retry:javascript_busy" })
            assertEquals("accepted", run.outcome)
        } finally {
            runBlocking { slot?.free() }
        }
    }

    @Test fun aSandboxBusyForBothTriesGoesToTheLoopWithItsReason() {
        var slot: Slot? = null
        try {
            val run = generate(listOf(Scripted.Text(calc)) + loop, onPhase = { if (it.name == "compile" && it.detail == "ok") slot = Slot(context) },
                provider = { script -> object : ChatProvider {
                    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                        if (request.generationStage == AGENT) slot?.free()
                        script.respond(key, request, session, emit)
                    }
                } })
            assertEquals(1, run.phases.count { it.name == "host_check_retry:javascript_busy" })
            assertEquals("fallback:host_check:javascript_busy", run.outcome)
            assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
            assertEquals("Fixture", run.spec.title)
        } finally {
            runBlocking { slot?.free() }
        }
    }

    /** Holds the transient slot of the sandbox, as an open app that runs an event does, until [free]. */
    internal class Slot(context: Context) {
        private val release = CompletableDeferred<Unit>()
        private val held = CompletableDeferred<Unit>()
        private val job = CoroutineScope(Dispatchers.Default).launch {
            SandboxClient(context).disposable { held.complete(Unit); release.await() }
        }
        init { runBlocking { held.await() } }
        fun release() = release.complete(Unit)
        suspend fun free() { release(); job.join() }
    }

    // Failure case: a person who used an open app while the plan compiled lost the request.
    @Test fun aBusySandboxGoesToTheLoop() {
        val slot = Slot(context)
        try {
            // The slot stays held for both tries of the compile, and the loop gets it back for its own checks.
            val run = generate(listOf(Scripted.Text(calc)) + loop, provider = { script -> object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    if (request.generationStage == AGENT) slot.free()
                    script.respond(key, request, session, emit)
                }
            } })
            assertEquals(listOf("BUSY", "BUSY"), run.compiles)
            val compiles = run.phases.filter { it.name == "compile" }
            assertTrue((compiles[1].atNanos - compiles[0].atNanos) / 1_000_000 >= 500)
            assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
            assertEquals("fallback:compile_BUSY", run.outcome)
            assertEquals("Fixture", run.spec.title)
        } finally {
            runBlocking { slot.free() }
        }
    }

    @Test fun aShortBusySandboxStillGivesThePlan() {
        val slot = Slot(context)
        try {
            val run = generate(listOf(Scripted.Text(calc)) + loop, onPhase = { if (it.name == "compile" && it.detail == "BUSY") slot.release() })
            assertEquals(listOf("BUSY", "ok"), run.compiles)
            assertEquals(listOf(APP_FILE), run.stages)
            assertEquals("accepted", run.outcome)
            assertEquals(calc, run.spec.appFile)
        } finally {
            runBlocking { slot.free() }
        }
    }

    // Failure case: a stop of the person during the plan call started the loop.
    @Test fun aStopDuringThePlanCallEndsTheRequest() {
        val session = CloudSession()
        val started = SystemClock.elapsedRealtime()
        Thread { SystemClock.sleep(200); session.cancel() }.start()
        val run = generate(listOf(Scripted.Waits(5000, calc)) + loop, session = session)
        assertEquals("cancelled", (run.result.exceptionOrNull() as CloudFailure).code)
        assertTrue(SystemClock.elapsedRealtime() - started < 1500)
        assertEquals(listOf(APP_FILE), run.stages)
        assertTrue(run.phases.none { it.name == "plan_outcome" || it.name == "capability_guidance_start" })
    }

    @Test fun aStopDuringTheCompileEndsTheRequest() {
        val session = CloudSession()
        // The progress turns to VALIDATING just before the compile.
        val run = generate(listOf(Scripted.Text(calc)) + loop, session = session,
            progress = { if (it == ToolGenerationProgress.VALIDATING) session.cancel() })
        assertEquals("cancelled", (run.result.exceptionOrNull() as CloudFailure).code)
        assertEquals(listOf("ok"), run.compiles)
        assertEquals(listOf(APP_FILE), run.stages)
        assertTrue(run.phases.none { it.name == "plan_outcome" || it.name == "capability_guidance_start" })
    }

    @Test fun theSwitchOffSendsNoPlanCall() {
        val run = generate(loop, planStep = null)
        assertEquals(listOf(AGENT, AGENT), run.stages)
        // The trace names the condition that closed the plan step.
        assertEquals(listOf("plan_skipped:plan_step", "capability_guidance_start", "capability_guidance_end", "agent_call_start"),
            run.phases.map { it.name })
    }

    @Test fun aRequestWithAnImageSendsNoPlanCall() {
        val run = generate(loop, images = listOf("data:image/png;base64,iVBORw0KGgo="))
        assertEquals(listOf<CloudGenerationStage?>(AGENT, AGENT), run.stages)
        assertEquals(listOf("plan_skipped:images"), run.phases.map { it.name }.filter { it.startsWith("plan_") })
    }

    // Failure case (task Db2): the screen of a spoken talk went on to the loop of many calls, and came a minute after the voice had answered.
    @Test fun aScreenRunNeverStartsTheLoop() {
        val run = generate(listOf(Scripted.Text("outside(\"game\")")) + loop, screen = ScreenRun())
        assertEquals(AppFileNames.OUTSIDE, fallback(run))
        assertEquals(listOf(APP_FILE), run.stages)
        assertEquals("outside:game", run.outcome)
        assertTrue(run.phases.none { it.name == "capability_guidance_start" || it.name == "agent_call_start" })
        assertTrue(run.events.none { it is ToolGenerationTraceEvent.AgentStep })
    }

    @Test fun aScreenPlanThatFailsTheHostCheckFallsBack() {
        val run = generate(listOf(Scripted.Text(calc.replace("lang=\"en\"", "lang=\"ru\""))) + loop, screen = ScreenRun())
        assertEquals("host_check:language", fallback(run))
        assertEquals(listOf(APP_FILE), run.stages)
        assertEquals("fallback:host_check:language", run.outcome)
    }

    // Failure case: one sandbox deadline in the host check of a correct screen left the stage as it was.
    @Test fun aScreenRunRetriesItsHostCheck() {
        val passed = checked(Sandbox(timeout()).smoke, screen = ScreenRun())
        assertEquals(calc, passed.spec.appFile)
        assertEquals(1, passed.phases.count { it.name == "host_check_retry:javascript_timeout" })
        assertEquals("accepted", passed.outcome)
        val failed = checked(Sandbox(timeout(), timeout()).smoke, screen = ScreenRun())
        assertEquals("host_check:javascript_timeout", fallback(failed))
        assertEquals("fallback:host_check:javascript_timeout", failed.outcome)
        assertTrue(failed.stages.isEmpty())
    }

    // A screen run whose plan step cannot run (the switch is off, or the request has an image) makes no call at all.
    @Test fun aScreenRunWithAClosedGateMakesNoCall() {
        for ((run, condition) in listOf(generate(loop, planStep = null, screen = ScreenRun()) to "plan_step",
            generate(loop, images = listOf("data:image/png;base64,iVBORw0KGgo="), screen = ScreenRun()) to "images")) {
            assertEquals(AppFileNames.CLOSED, fallback(run))
            assertTrue(run.stages.isEmpty())
            // The trace names only the condition that closed the plan step.
            assertEquals(listOf("plan_skipped:$condition"), run.phases.map { it.name })
        }
    }

    // Failure case (task Db4): the screen paid a second plan call after the plan call beside the routing call had given the app.
    @Test fun anEarlyPlanReplacesThePlanCall() {
        val clean = runBlocking {
            AppFileSteps(SandboxClient(context), { _, _, _, _ -> AppFileReply.Text(calc) }, { _, _, _ -> }, {}).plan(PROMPT, AppFileLimits())
        }
        assertTrue(clean is AppFileOutcome.Clean)
        val run = generate(loop, screen = ScreenRun(early = CompletableDeferred(clean)))
        assertTrue(run.stages.isEmpty())
        assertEquals(calc, run.spec.appFile)
        assertEquals("accepted", run.outcome)
        assertTrue(run.phases.none { it.name == "plan_call_start" || it.name == "agent_call_start" })
    }

    /**
     * A cancel of the early plan alone ends the screen run as a fallback: the request goes on, and the stage stays as it was.
     * A stop of the request while the run waits for the early plan stays a stop.
     */
    @Test fun aCancelledEarlyPlanFallsBackAndAStopStaysAStop() {
        val cancelled = CompletableDeferred<AppFileOutcome>().apply { cancel() }
        val stopped = CompletableDeferred<AppFileOutcome>().apply { completeExceptionally(CloudFailure("cancelled", "Запрос остановлен")) }
        for (early in listOf(cancelled, stopped)) {
            val run = generate(loop, screen = ScreenRun(early))
            assertEquals(AppFileNames.EARLY_CANCELED, fallback(run))
            assertTrue(run.stages.isEmpty())
        }
        val session = CloudSession()
        val waiting = CompletableDeferred<AppFileOutcome>()
        Thread { SystemClock.sleep(300); session.cancel(); waiting.cancel() }.start()
        val run = generate(loop, session = session, screen = ScreenRun(waiting))
        assertEquals("cancelled", (run.result.exceptionOrNull() as? CloudFailure)?.code)
        assertTrue(run.stages.isEmpty())
        assertEquals(ToolGenerationTraceOutcome.CANCELED, run.events.filterIsInstance<ToolGenerationTraceEvent.Finished>().single().outcome)
    }

    /**
     * The tool controller of a launched shell with the plan step on. Only the generation calls reach [chat]: a template call gets
     * [NO_TEMPLATE] here, so the early plan goes on with its plan call.
     */
    private fun withController(chat: ChatProvider, test: (ToolController) -> Unit) {
        val fixture = ShellFixture("appfile-loop")
        try {
            withPlanStep(fixture.context, AiProvider.OPENAI, true) {
                fixture.install(chat = object : ChatProvider {
                    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                        if (answerNoTemplate(request, emit)) return
                        if (request.generationStage != null) chat.respond(key, request, session, emit)
                    }
                })
                fixture.launch().use {
                    val model = checkNotNull(fixture.model)
                    // The controller reads its library once at its start, and a tool operation in that time fails with widget_busy.
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    while ((model.loading || model.tools.busy) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                    assertFalse("The shell and its tool controller did not get ready within 10 s.", model.loading || model.tools.busy)
                    test(model.tools)
                }
            }
        } finally {
            fixture.remove()
        }
    }

    /** A screen run of [PROMPT] through the chat entry of the controller. Gives its failure, or null when it saved an app. */
    private fun ToolController.screenRun(screen: ScreenRun, operation: String): Throwable? = runBlocking(Dispatchers.Main) {
        runCatching {
            generateAutomatic(PROMPT, null, "appfile-loop-chat", operation, "fixture", emptyList(), CloudSession(), cancelRequest = {},
                prepared = {}, onPublicUpdate = {}, canCommit = { true }, committed = {}, screen = screen)
        }.exceptionOrNull()
    }

    /** What a screen run leaves in the controller: no draft, no preview, no saved app, and no error. */
    private fun ToolController.assertNothingLeft(operation: String) = runBlocking(Dispatchers.Main) {
        assertTrue(drafts.isEmpty())
        assertNull(previewFor(operation))
        assertTrue(tools.isEmpty())
        assertNull(error)
        assertFalse(busy)
    }

    // Failure case (task Db2): a screen with a faulty plan and a faulty repair went on to the loop, or left a draft behind.
    @Test fun aScreenFallbackMakesOnlyThePlanCallsAndLeavesNothing() {
        val repair = brokenCalc.lines().single { it.startsWith("need perPerson") }
        val script = AgentScriptProvider(listOf(Scripted.Text(brokenCalc), Scripted.Text(repair)) + loop)
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val operation = UUID.randomUUID().toString()
        withController(script) { tools ->
            tools.generationTraceObserver = { events += it }
            val failure = tools.screenRun(ScreenRun(), operation)
            // The message names what the run threw, or that it saved an app.
            assertEquals(failure?.stackTraceToString() ?: "The screen run saved an app.", AppFileNames.FAULTS, (failure as? ScreenFallback)?.reason)
            tools.assertNothingLeft(operation)
        }
        assertEquals(listOf(APP_FILE, APP_FILE), script.calls.map { it.generationStage })
        val run = TracedGeneration(script.calls, events)
        assertEquals("fallback:faults", run.outcome)
        assertTrue(events.none { it is ToolGenerationTraceEvent.AgentStep })
        // ScreenFallback is not a CloudFailure, so the trace names no code for it.
        val finished = events.filterIsInstance<ToolGenerationTraceEvent.Finished>().single()
        assertEquals(ToolGenerationTraceOutcome.FAILED to "unknown", finished.outcome to finished.failureCode)
    }

    // Failure case (task Db4): the plan call of a spoken request waited for the routing call, so the screen came one routing call later.
    @Test fun anEarlyPlanMakesOnePlanCallWithItsPrompt() {
        val script = AgentScriptProvider(listOf(Scripted.Text(calc)))
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val operation = UUID.randomUUID().toString()
        withController(script) { tools ->
            tools.generationTraceObserver = { events += it }
            val early = checkNotNull(tools.startEarlyPlan(PROMPT, PROMPT, "fixture", operation))
            assertEquals(calc, (runBlocking { early.result.await() } as? AppFileOutcome.Clean)?.appFile)
        }
        val call = script.calls.single()
        assertEquals(APP_FILE, call.generationStage)
        assertEquals(listOf(PROMPT), call.turns.map { it.text })
        assertEquals(AppFilePrompt.load(context).system, call.systemInstructions)
        val phases = events.filterIsInstance<ToolGenerationTraceEvent.Phase>()
        assertTrue(phases.all { it.operationId.toString() == operation })
        // The template call goes first. Its reply outside starts the plan call.
        assertEquals(listOf("early_plan_start", "template_call_start", "plan_first_text", "plan_reply_end", "compile", "template_outcome",
            "plan_call_start", "plan_first_text", "plan_reply_end", "compile"), phases.map { it.name })
        assertEquals("outside", phases.single { it.name == "template_outcome" }.detail)
        assertTrue(events.none { it is ToolGenerationTraceEvent.Finished })
    }

    /** A provider whose first call runs until a cancel of its session, as an HTTP call that the abort of its session ends. Later calls give [calc]. */
    private inner class UntilCancelled : ChatProvider {
        val started = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<CloudRequest>()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            calls += request
            if (calls.size == 1) {
                started.complete(Unit)
                while (true) { session.check(); delay(20) }
            }
            emit(CloudEvent.Text(calc))
            emit(CloudEvent.RoundComplete(calc, false))
        }
    }

    // Failure case (task Db4): a stop of the person left the plan call beside the routing call running until its limit of 45 s.
    @Test fun aCancelOfTheEarlyPlanSessionStopsItsCall() {
        val provider = UntilCancelled()
        withController(provider) { tools ->
            val early = checkNotNull(tools.startEarlyPlan(PROMPT, PROMPT, "fixture", UUID.randomUUID().toString()))
            runBlocking { withTimeout(5000) { provider.started.await() } }
            val cancelled = SystemClock.elapsedRealtime()
            early.session.cancel()
            val failure = runBlocking { withTimeout(5000) { runCatching { early.result.await() }.exceptionOrNull() } }
            assertEquals("cancelled", (failure as? CloudFailure)?.code)
            assertTrue(SystemClock.elapsedRealtime() - cancelled < 1000)
            // The failed plan does not end the scope of the controller: a later early plan still gives its plan.
            val next = checkNotNull(tools.startEarlyPlan(PROMPT, PROMPT, "fixture", UUID.randomUUID().toString()))
            assertTrue(runBlocking { withTimeout(10_000) { next.result.await() } } is AppFileOutcome.Clean)
        }
        assertEquals(listOf(APP_FILE, APP_FILE), provider.calls.map { it.generationStage })
    }

    // The early plan is cancelled while the screen run waits for it: the run falls back with no other call and leaves nothing.
    @Test fun aCancelOfTheEarlyPlanWhileTheScreenRunWaitsFallsBack() {
        val provider = UntilCancelled()
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val operation = UUID.randomUUID().toString()
        withController(provider) { tools ->
            tools.generationTraceObserver = { events += it }
            val early = checkNotNull(tools.startEarlyPlan(PROMPT, PROMPT, "fixture", operation))
            var failure: Throwable? = null
            val screen = Thread { failure = tools.screenRun(ScreenRun(early.result), operation) }.apply { start() }
            // The draft is ready just before the run waits for the early plan.
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (events.none { it is ToolGenerationTraceEvent.Phase && it.name == "draft_end" } && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(20)
            SystemClock.sleep(200)
            early.session.cancel()
            screen.join(10_000)
            assertEquals(AppFileNames.EARLY_CANCELED, (failure as? ScreenFallback)?.reason)
            tools.assertNothingLeft(operation)
        }
        assertEquals(listOf(APP_FILE), provider.calls.map { it.generationStage })
        assertTrue(events.none { it is ToolGenerationTraceEvent.Phase && it.name == "plan_outcome" })
    }

    private companion object {
        const val PROMPT = "User request: Split a restaurant bill with a tip\nWidget brief: A tip calculator for a bill and a group"
        /** The task text names the time of the request to the minute. */
        fun withoutTime(text: String) = text.replace(Regex("Today is [^\\n]*"), "")
        /** A deadline of a smoke event, as ToolEngine.executeEvent reports a sandbox TIMEOUT. */
        fun timeout() = ToolSmokeFailure("action", "$.ui", CloudFailure("javascript_timeout", SandboxResult("TIMEOUT").message))
    }
}

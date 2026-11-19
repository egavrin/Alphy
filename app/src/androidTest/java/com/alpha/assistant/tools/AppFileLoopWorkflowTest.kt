package com.alpha.assistant.tools

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudGenerationStage.AGENT
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan
import com.alpha.assistant.ui.FIXTURE_APP
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    /** One generation of [PROMPT] with the plan step at [planStep]. [provider] can wrap the script. */
    private fun generate(replies: List<Scripted>, planStep: AppFileLimits? = AppFileLimits(500, 2000), images: List<String> = emptyList(),
        session: CloudSession = CloudSession(), provider: (AgentScriptProvider) -> ChatProvider = { it },
        progress: (ToolGenerationProgress) -> Unit = {}, onPhase: (ToolGenerationTraceEvent.Phase) -> Unit = {}): Generated {
        val script = AgentScriptProvider(replies)
        val events = CopyOnWriteArrayList<ToolGenerationTraceEvent>()
        val trace = ToolGenerationTrace(UUID.randomUUID(), { event -> events += event; (event as? ToolGenerationTraceEvent.Phase)?.let(onPhase) })
        val result = runBlocking {
            runCatching {
                ToolGenerationService(provider(script), SandboxClient(context)).generate("fixture", PROMPT, null, images, session,
                    "appfile-loop", privateState = false, progress = progress, trace = trace, planStep = planStep)
            }
        }
        return Generated(script, events, result)
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
        assertEquals("fallback:host_check", run.outcome)
        assertNull(run.spec.appFile)
    }

    // Failure case: validate does not catch a raw number such as 28.333333333333332. Only finish checked it.
    @Test fun aPlanThatShowsALongDecimalGoesToTheLoop() {
        val third = "app(\"Third\", lang=\"en\")\nneed a = input(\"number\", \"A\", 85, min=0, max=100)\n" +
            "need b = input(\"number\", \"B\", 3, min=1, max=10)\n\$/r := \$/a / \$/b\nroot = Column([a_field, b_field, Text(text(\$/r))])\n"
        val run = generate(listOf(Scripted.Text(third)) + loop)
        assertEquals(listOf("ok"), run.compiles)
        assertEquals(listOf(APP_FILE, AGENT, AGENT), run.stages)
        assertEquals("fallback:host_check", run.outcome)
    }

    /** Holds the transient slot of the sandbox, as an open app that runs an event does, until [free]. */
    private class Slot(context: Context) {
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
        assertEquals(listOf("capability_guidance_start", "capability_guidance_end", "agent_call_start"), run.phases.map { it.name })
    }

    @Test fun aRequestWithAnImageSendsNoPlanCall() {
        val run = generate(loop, images = listOf("data:image/png;base64,iVBORw0KGgo="))
        assertEquals(listOf<CloudGenerationStage?>(AGENT, AGENT), run.stages)
        assertTrue(run.phases.none { it.name.startsWith("plan_") })
    }

    private companion object {
        const val PROMPT = "User request: Split a restaurant bill with a tip\nWidget brief: A tip calculator for a bill and a group"
        /** The task text names the time of the request to the minute. */
        fun withoutTime(text: String) = text.replace(Regex("Today is [^\\n]*"), "")
    }
}

package com.alpha.assistant.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.ChatToolReply
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudGenerationStage.AGENT
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_TEMPLATE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.Source
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.agentFixtureFinish
import com.alpha.assistant.tools.agentWrite
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.ui.TemplateTalk.Companion.APP_REQUEST
import com.alpha.assistant.ui.TemplateTalk.Companion.FRESH_REQUEST
import com.alpha.assistant.ui.TemplateTalk.Companion.FRESH_SOURCE
import com.alpha.assistant.ui.TemplateTalk.Companion.FRESH_TEXT
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_BRIEF
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_TEXT
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A new screen of a spoken talk by template (plan of stage templates, Task 4): the template call goes first, a reply outside goes on to
 * the free plan, a faulty line gets one repair of that line, and a whole failure goes on to the full generation with the waiting line.
 * The real shell, kit, and sandbox run with scripted providers. The ship template is the copy of the kit fixture.
 */
class StageTemplateWorkflowTest {
    private val fixture = ShellFixture("stage-template")
    private val robot = ShellRobot("stage-template")
    private val socket = FakeLiveTransport()
    private val shell = TemplateShell(fixture, robot, socket)
    private val ship get() = shipTemplate()
    private val prompt get() = AppFilePrompt.load(fixture.context)
    @After fun remove() = shell.remove()

    @Test fun aSpokenExplainScreenIsAStoredTemplate() {
        val talk = TemplateTalk(AgentScriptProvider(ship))
        shell.live(talk) { scenario, model, phases ->
            val app = shell.screen(model)
            assertEquals(ship, appFileOf(app))
            assertEquals(listOf(APP_FILE_TEMPLATE), talk.stages)
            assertEquals(shell.messages(model).last().toolId, model.stageToolId)
            robot.waitUntil { robot.composedTextIn(scenario, STAGE, SHIP_CARD) }
            assertTrue(phases.toString(), "template_outcome:clean0" in phases)
        }
    }

    @Test fun outsideFallsToTheFreePlanWithItsDialogueLine() {
        val talk = TemplateTalk(AgentScriptProvider("outside(\"learn\")"), AgentScriptProvider(appFilePlan("calc")))
        shell.live(talk) { _, model, phases ->
            val app = shell.screen(model)
            assertEquals(listOf(APP_FILE_TEMPLATE, APP_FILE), talk.stages)
            val input = talk.calls[1].turns.single().text
            assertTrue(input, input.startsWith("User request: $SCREEN_REQUEST\n${prompt.dialogue}\nWidget brief: "))
            assertEquals(appFilePlan("calc"), appFileOf(app))
            assertTrue(phases.toString(), "template_outcome:outside" in phases)
        }
    }

    @Test fun aFaultyLineGetsOneRepairOfThatLine() {
        val talk = TemplateTalk(AgentScriptProvider(shipWith(ship, BAD_S2), lineOf(ship, "s2")))
        shell.live(talk) { _, model, phases ->
            val app = shell.screen(model)
            assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages)
            val repair = talk.calls[1]
            assertEquals(prompt.template, repair.systemInstructions)
            assertTrue(repair.turns.single().text, "s2: " in repair.turns.single().text)
            assertEquals(ship, appFileOf(app))
            assertTrue(phases.toString(), "template_outcome:clean1" in phases)
        }
    }

    @Test fun aLineThatStaysFaultyIsLeftOut() {
        // The faulty s2 is a text, so the labels of the canvas show that it is not drawn.
        val talk = TemplateTalk(AgentScriptProvider(shipWith(ship, HULL), HULL))
        shell.live(talk) { scenario, model, phases ->
            val app = shell.screen(model)
            assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages)
            assertEquals(ship.lines().filterNot { it.startsWith("s2 = ") }.joinToString("\n"), appFileOf(app))
            // s3 is drawn and s2 is not. s1 is a shape with no text, which no canvas label names: the stored file holds it.
            robot.waitUntil(30_000) { drew(scenario, SHIP_AIR) }
            assertFalse(drew(scenario, HULL_TEXT))
            assertTrue(phases.toString(), "template_outcome:partial1" in phases)
        }
    }

    // The faulty first line stays faulty after its repair, so no part can show.
    @Test fun aWholeFaultRunsTheFullGeneration() =
        fullGeneration(Scripted.Text(shipWith(ship, SKY_HEAD)), Scripted.Text(SKY_HEAD)) { model, stages, phases ->
            assertEquals(List(2) { APP_FILE_TEMPLATE } + APP_FILE + List(2) { AGENT }, stages)
            assertTrue(phases.toString(), "template_outcome:whole" in phases && "plan_outcome:fallback:template_whole" in phases)
            assertEquals(listOf("complete", SCREEN_TEXT), shell.messages(model).last().let { listOf(it.status, it.text) })
        }

    @Test fun aStoppedTemplateCallRunsTheFullGeneration() = fullGeneration(Scripted.Fails("pre_headers_io")) { _, stages, phases ->
        assertEquals(listOf(APP_FILE_TEMPLATE, APP_FILE) + List(2) { AGENT }, stages)
        assertTrue(phases.toString(), "template_outcome:failed" in phases && "plan_outcome:fallback:call_failed" in phases)
    }

    // The template is clean, but its texts are not in the language of its first line: the host check of the language fails.
    @Test fun aHostCheckFaultOfATemplateRunsTheFullGenerationWithNoRepairCall() =
        fullGeneration(Scripted.Text(ship.replace("lang=\"ru\"", "lang=\"en\""))) { _, stages, phases ->
            assertEquals(listOf(APP_FILE_TEMPLATE, APP_FILE) + List(2) { AGENT }, stages)
            assertTrue(phases.toString(), "template_outcome:clean0" in phases && "plan_outcome:fallback:template_check" in phases)
            assertFalse(phases.toString(), "repair_call_start" in phases)
        }

    @Test fun theEarlyPlanSendsTheTemplateFirst() {
        val talk = TemplateTalk(AgentScriptProvider(ship))
        shell.live(talk) { _, model, phases ->
            val app = shell.screen(model)
            val call = talk.calls.single()
            assertEquals(APP_FILE_TEMPLATE, call.generationStage)
            assertEquals(prompt.template, call.systemInstructions)
            // The call starts beside the routing call: no brief yet, and no dialogue line. Its ready objects are the count of its trace mark.
            val (task, count) = TemplateTalk.withoutLibrary(call.turns.single().text)
            assertEquals(ChatToolReply.generationPrompt(SCREEN_REQUEST, "", "", emptyList(), host = ""), task)
            assertEquals(phases.toString(), listOf(count), TemplateTalk.libraryMarks(phases))
            assertFalse(prompt.dialogue in call.turns.single().text)
            assertEquals(listOf("early_plan_start", "early_plan_used"), phases.filter { it.startsWith("early_plan") })
            assertEquals(1, phases.count { it == "template_call_start" })
            assertEquals(ship, appFileOf(app))
        }
    }

    @Test fun theTemplateInputHoldsTheBriefAndTheExcerpts() {
        val talk = TemplateTalk(AgentScriptProvider(ship), AgentScriptProvider(appFilePlan("calc")))
        shell.live(talk) { _, model, _ ->
            val calc = shell.app(model)
            val before = shell.messages(model).size
            socket.say(FRESH_REQUEST, "d2", 2_000)
            shell.waitForTheAnswer(model, before)
            robot.waitUntil(60_000) { model.stageToolId != null && model.stageToolId != calc.id }
            assertEquals(listOf(APP_FILE, APP_FILE_TEMPLATE), talk.stages)
            val input = talk.calls[1].turns.single().text
            // Stage 6, S3: the candidates of the library search may stand between the request and the brief (a host line, docs/voice-integration.md).
            assertTrue(input, input.startsWith("User request: $FRESH_REQUEST\n") && "\nWidget brief: $SCREEN_BRIEF\n" in input)
            assertTrue(input, "\nReply excerpt: $FRESH_TEXT" in input)
            assertTrue(input, "\nSource [1] excerpt: ${FRESH_SOURCE.title}\n${FRESH_SOURCE.summary}" in input)
            assertFalse(input, prompt.dialogue in input)
            assertEquals(ship, appFileOf(shell.stored(model, checkNotNull(model.stageToolId))))
        }
    }

    @Test fun aTypedChatRequestSendsNoTemplateCall() {
        val talk = TemplateTalk()
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            fixture.install(chat = talk)
            fixture.launch().use { scenario ->
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading }
                scenario.onActivity { model.editDraft(Draft(APP_REQUEST)); model.send() }
                robot.waitUntil(60_000) { shell.messages(model).any { it.toolId != null } }
                assertEquals(listOf(APP_FILE), talk.stages)
            }
        }
    }

    /**
     * The calc app is on the stage. The spoken screen request (show true) gets [templates] as the replies of its template calls, which give
     * no app. The full generation then builds a new app: its plan step replies outside, and the loop writes the fixture app. Its first call
     * waits until the voice has the waiting line. [check] gets the model, the stages of the calls of the screen request, and the phases.
     */
    private fun fullGeneration(vararg templates: Scripted, check: (ShellViewModel, List<CloudGenerationStage?>, List<String>) -> Unit) {
        val full = CompletableDeferred<Unit>()
        val plans = AgentScriptProvider(listOf(Scripted.Text(appFilePlan("calc")), Scripted.Text("outside(\"game\")"),
            Scripted.Text(agentWrite(FIXTURE_APP)), Scripted.Text(agentFixtureFinish)))
        val talk = TemplateTalk(AgentScriptProvider(templates.toList()), plans, show = true, hold = 1 to full)
        shell.live(talk) { _, model, phases ->
            val calc = shell.app(model)
            val before = shell.messages(model).size
            socket.say(SCREEN_REQUEST, "d2", 2_000)
            robot.waitUntil(60_000) { "d2" to FULL_WAIT in socket.commentary() }
            // Before the waiting line, the screen request made only template calls: no plan call.
            assertEquals(listOf(APP_FILE) + List(templates.size) { APP_FILE_TEMPLATE }, talk.stages)
            full.complete(Unit)
            shell.waitForTheAnswer(model, before)
            robot.waitUntil(60_000) { model.stageToolId != null && model.stageToolId != calc.id }
            robot.waitUntil { socket.commentary().count { it.first == "d2" } == 2 }
            robot.awaitFrames(5)
            assertEquals(listOf("d2" to "${LiveProtocol.MARK}$SCREEN_TEXT", "d2" to FULL_WAIT), socket.commentary().filter { it.first == "d2" })
            check(model, talk.stages.drop(1), phases)
        }
    }

    /** A label of the stage canvas holds [text]: the canvas draws a text part with it. */
    private fun drew(scenario: ActivityScenario<CloudTestActivity>, text: String) = robot.composedIn(scenario, STAGE) { config ->
        config.getOrNull(SemanticsProperties.ContentDescription)?.any { text in it } == true
    }

    private companion object {
        const val STAGE = "dialogue_stage"
        const val SHIP_CARD = "Вытеснение воды"
        /** The text of s3 of the ship template. */
        const val SHIP_AIR = "Воздух внутри"
        const val HULL_TEXT = "Корпус"
        /** A text part whose x is no number: the kit names its line. */
        const val HULL = "s2 = text(\"a\", 90, \"$HULL_TEXT\", \"ink\", 14)"
        /** A first line with a palette that the kit does not have: a whole fault. */
        const val SKY_HEAD = "scene(\"Почему корабль не тонет?\", lang=\"ru\", palette=\"sky\")"
        const val FULL_WAIT = LiveVoiceBridge.FULL_WAIT
    }
}

/** The ship template of the kit fixtures (kit/test/fixtures/template/ship.plan), as the kit build copies it to the test assets. */
internal fun shipTemplate() = appFilePlan("template/ship")

/** [template] with [line] in place of its line of the same name. A line with no name replaces the first line. */
internal fun shipWith(template: String, line: String): String {
    val name = line.substringBefore(" = ", "").takeIf { it.isNotEmpty() }
    val first = template.lines().first()
    return template.lines().joinToString("\n") { old -> if (if (name == null) old == first else old.startsWith("$name = ")) line else old }
}

/** The line of [template] with [name]. */
internal fun lineOf(template: String, name: String) = template.lines().single { it.startsWith("$name = ") }

/** The choice of the ship template that the person sets: the one text value of its state. */
internal fun shipSubject(record: ToolRecord) = JSONObject(record.state).let { state ->
    state.keys().asSequence().map { state.get(it) }.filterIsInstance<String>().single()
}

/** The app file of [record]. */
internal fun appFileOf(record: ToolRecord) = ToolSpec.parsePersisted(record.spec).appFile

/** The id of the choice control of the ship template, as the kit names it. */
internal const val SHIP_CONTROL = "v_field"
/** The second option of the choice of the ship template. */
internal const val NAIL = "Гвоздь"
/** A new part of the ship template that shows with [NAIL]. */
internal const val SHIP_S4 = "s4 = circle(150, 180, 15, \"sun\", visible=\$/v == \"$NAIL\")"
/** An s2 of the ship template as a polygon with 3 numbers: the kit names its line. */
internal const val BAD_S2 = "s2 = polygon(45, 75, 255)"
/** A change line of the expansion of the ship template: a new text of its card. */
internal const val SHIP_CARD_CHANGE = "need card = card(\"Вытеснение воды\", \"Корабль вытесняет воду и плывёт.\")"

/** The ship template with [SHIP_S4] before its card, as the kit merges it. */
internal fun shipWithS4() = shipTemplate().replace("card = ", "$SHIP_S4\ncard = ")

/** The expansion of the ship template, as the kit gives it. Call it while no shell holds the sandbox. */
internal fun shipExpansion(context: Context) =
    checkNotNull(runBlocking { AppFileCompiler(context, SandboxClient(context)).compile(shipTemplate()) }.expanded)

/** [expanded] with [SHIP_CARD_CHANGE] merged, as the kit merges it. */
internal fun shipCardChanged(expanded: String) =
    expanded.lines().joinToString("\n") { if (it.startsWith("need card = ")) SHIP_CARD_CHANGE else it }

/**
 * The scripted provider of a talk with templates. A template call answers from [templates] ([NO_TEMPLATE] by default), and each other
 * generation call from [plans]. The routing call creates an app for [APP_REQUEST], gives a screen with [show] for [SCREEN_REQUEST], a
 * screen with a found source for [FRESH_REQUEST], and a change for [CHANGE_REQUEST], and answers each other request. The early call of a
 * request that gets no screen waits for its cancel and goes to no list. The call of [plans] with the index of [hold] waits for its gate.
 * [calls] holds each other generation call in its order: a held call after its gate.
 */
internal class TemplateTalk(private val templates: AgentScriptProvider = AgentScriptProvider(NO_TEMPLATE),
    private val plans: AgentScriptProvider = AgentScriptProvider(appFilePlan("calc")), private val show: Boolean = false,
    private val hold: Pair<Int, CompletableDeferred<Unit>>? = null) : ChatProvider {
    val calls = CopyOnWriteArrayList<CloudRequest>()
    val stages get() = calls.map { it.generationStage }
    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        val stage = request.generationStage
        if (stage != null) {
            if (EARLY_BRIEF in request.turns.last().text && SCREEN_REQUEST !in request.turns.last().text) awaitCancellation()
            if (stage == APP_FILE_TEMPLATE) { calls += request; return templates.respond(key, request, session, emit) }
            if (hold?.first == plans.calls.size) hold.second.await()
            calls += request
            return plans.respond(key, request, session, emit)
        }
        val text = request.decisionContext?.originalUserTurn?.text
        if (text == FRESH_REQUEST) emit(CloudEvent.Search("found", listOf(FRESH_SOURCE)))
        emit(CloudEvent.RoundComplete(when (text) {
            APP_REQUEST -> chatDecision("create", "Готово.", "Make the app")
            SCREEN_REQUEST -> JSONObject(chatDecision("screen", SCREEN_TEXT, SCREEN_BRIEF)).put("show", show).toString()
            FRESH_REQUEST -> JSONObject(chatDecision("screen", FRESH_TEXT, SCREEN_BRIEF)).put("search", "required").toString()
            CHANGE_REQUEST, HEART_CHANGE -> chatDecision("refine", "Готово.", CHANGE_BRIEF)
            else -> chatDecision("answer", "Fixture response")
        }, false))
    }

    companion object {
        const val APP_REQUEST = "Сделай калькулятор чаевых"
        const val SCREEN_REQUEST = "Объясни, почему корабль не тонет"
        const val FRESH_REQUEST = "Найди свежие данные о плавучести кораблей"
        const val CHANGE_REQUEST = "Покажи ещё гвоздь"
        /** A change that names a ready object, the third step of the live heart talk (fix list 4, K3). */
        const val HEART_CHANGE = "Покажи, как кровь течёт по сердцу"
        private val LIBRARY_BLOCK = Regex("\nReady objects that may fit this request[^\n]*(\n- [^\n]*)*")
        /**
         * The task [text] with no block of ready objects, and the number of its candidates. The search of the app gives the vectors or the word
         * match by the time its model needs (fix list 4, K1), so a test compares the rest and the count of the trace mark library_candidates.
         */
        fun withoutLibrary(text: String): Pair<String, Int> {
            val block = LIBRARY_BLOCK.find(text) ?: return text to 0
            return text.removeRange(block.range) to block.value.lines().count { it.startsWith("- ") }
        }
        /** The count of the trace mark library_candidates in [phases], such as 24 of "library_candidates:24 vectors". */
        fun libraryMarks(phases: List<String>) = phases.filter { it.startsWith("library_candidates:") }.map { it.removePrefix("library_candidates:").substringBefore(' ').toInt() }
        const val SCREEN_BRIEF = "Why a ship floats and a nail sinks"
        const val CHANGE_BRIEF = "Add a part that shows with the nail"
        const val SCREEN_TEXT = "Полый корабль вытесняет много воды."
        const val FRESH_TEXT = "Корабль вытесняет воду."
        val FRESH_SOURCE = Source("Плавучесть", "https://example.com/buoyancy", "Тело плавает, когда вытесняет воду своего веса")
        /** The plan call beside the routing call starts before a brief exists, so its prompt has an empty brief line. */
        private const val EARLY_BRIEF = "\nWidget brief: \n"
    }
}

/** The voice mode of the shell of [fixture] with a [TemplateTalk], for the template tests of the stage. */
internal class TemplateShell(private val fixture: ShellFixture, private val robot: ShellRobot, private val socket: FakeLiveTransport) {
    fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
    }

    /**
     * Launches the shell with the plan step on and [talk], starts the voice mode, and answers `session.started`. [test] gets the trace phases
     * of each operation in their order, each as name or name:detail. [observe] gets each phase after the list, on the thread that marks it.
     * [audio] is the speaker and the microphone of the mode.
     */
    fun live(talk: ChatProvider, observe: (String) -> Unit = {}, audio: FakeLiveAudio = FakeLiveAudio(),
        test: (ActivityScenario<CloudTestActivity>, ShellViewModel, List<String>) -> Unit) =
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            fixture.install(chat = talk, liveVoice = fixture.liveVoice(socket, audio))
            fixture.launch().use { scenario ->
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading }
                val phases = CopyOnWriteArrayList<String>()
                scenario.onActivity {
                    model.tools.generationTraceObserver = { event ->
                        if (event is ToolGenerationTraceEvent.Phase) {
                            val phase = event.name + event.detail?.let { ":$it" }.orEmpty()
                            phases += phase
                            observe(phase)
                        }
                    }
                    model.startLiveVoice(voiceApproval = false)
                }
                robot.waitUntil { socket.connected }
                socket.emit(LIVE_STARTED)
                robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
                test(scenario, model, phases)
            }
        }

    /** The spoken [SCREEN_REQUEST] on an empty stage. Gives the record of the app that the stage then shows. */
    fun screen(model: ShellViewModel) = spoken(model, SCREEN_REQUEST)

    /** The spoken [APP_REQUEST] on an empty stage. Gives the record of the app that the stage then shows. */
    fun app(model: ShellViewModel) = spoken(model, APP_REQUEST)

    private fun spoken(model: ShellViewModel, request: String): ToolRecord {
        socket.say(request, "d1", 100)
        return shown(model)
    }

    /** The record of the app that the stage shows after the operation of the request ends. */
    fun shown(model: ShellViewModel): ToolRecord {
        // The controller refuses a press while the operation of the request still runs.
        robot.waitUntil(60_000) { model.stageToolId != null && !model.tools.busy }
        return stored(model, checkNotNull(model.stageToolId))
    }

    fun stored(model: ShellViewModel, id: String) = model.tools.tools.first { it.id == id }

    /** The stored app file of [id] after its narration call ended: the host stores a scene before its narration lines and saves them later (plan "one scene", decision 6). */
    fun finalFile(model: ShellViewModel, id: String): String? {
        robot.waitUntil(30_000) { !model.tools.narrationPending(id) }
        val record = stored(model, id)
        return model.tools.savedNarration(record) ?: appFileOf(record)
    }

    fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    /** The answer of the request after [before] messages ended. */
    fun waitForTheAnswer(model: ShellViewModel, before: Int) =
        robot.waitUntil(60_000) { messages(model).size == before + 2 && messages(model).last().status !in unfinishedStatuses }
}

package com.alpha.assistant.ui

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.ChatToolReply
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage.AGENT
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_CHANGE
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_TEMPLATE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.SLOW_LAP
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.sameToolJson
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling FX-6: a spoken change of the stage app while that app commits its state. The request reads its app before its routing call,
 * so a touch or a tick of the app during that call comes between the read and the draft of the change (R1 of the owner's dialogue).
 */
class StageChangeWorkflowTest {
    private val fixture = ShellFixture("stage-change")
    private val robot = ShellRobot("stage-change")
    private val socket = FakeLiveTransport()
    private val shell = TemplateShell(fixture, robot, socket)
    private val prompt get() = AppFilePrompt.load(fixture.context)
    private val ship get() = shipTemplate()
    /** The task of the spoken change of [TemplateTalk], as the shell gives it to the change step. */
    private val changeTask get() = ChatToolReply.generationPrompt(TemplateTalk.CHANGE_REQUEST, TemplateTalk.CHANGE_BRIEF, "Готово.", emptyList())
    @After fun remove() = shell.remove()

    // Failure case (R1): a touch of the stage app during the routing call of a spoken change ended the change with the generic error.
    @Test fun aTouchDuringTheRoutingKeepsTheChange() = changeWhileTheAppCommits("calc", SEATS, move = { model, app ->
        robot.instrumentation.runOnMainSync { model.tools.interact(app, "people_field", 5) }
        // The touch is complete when its operation ends, as it is 3 to 5 s before the change in a spoken talk.
        robot.waitUntil { people(stored(model, app.id)) == 5 && !model.tools.busy }
    }) { saved ->
        // The change counts from the value of the touch.
        assertEquals(5, people(saved))
        assertEquals("10", JSONObject(saved.state).getJSONObject("d").getString("seats_text"))
    }

    // Failure case (R1): the ticks of a running animate of the stage app during the routing call ended the change with the generic error.
    // The stage keeps its frames and timers while the change runs, so the ticks also come during the change call and at the save.
    @Test fun aRunningAnimateKeepsTheChange() = changeWhileTheAppCommits("lesson", SLOW_LAP, move = { model, app ->
        robot.instrumentation.runOnMainSync { model.tools.interact(app, "lap_button", null) }
        // The press and at least one tick of the lap.
        robot.waitUntil { stored(model, app.id).stateVersion >= app.stateVersion + 2 && !model.tools.busy }
    }) { saved -> assertEquals(true, "Медленный круг" in saved.ui) }

    /**
     * The voice mode puts the app of [plan] on the stage. Then a spoken change waits in its routing call until [move] commits a state of
     * that app. The change by [line] is then saved as revision 2, and [check] gets the saved record.
     */
    private fun changeWhileTheAppCommits(plan: String, line: String, move: (ShellViewModel, ToolRecord) -> Unit, check: (ToolRecord) -> Unit) {
        val provider = HeldChange(AgentScriptProvider(appFilePlan(plan), line))
        withPlanStep(fixture.context, AiProvider.OPENAI, true) {
            fixture.install(chat = provider, liveVoice = fixture.liveVoice(socket, FakeLiveAudio()))
            fixture.launch().use { scenario ->
                val model = checkNotNull(fixture.model)
                robot.waitUntil { !model.loading }
                scenario.onActivity { model.startLiveVoice(voiceApproval = false) }
                robot.waitUntil { socket.connected }
                socket.emit(LIVE_STARTED)
                robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
                socket.say(APP_REQUEST, "d1", 100)
                robot.waitUntil(60_000) { model.stageToolId != null }
                val before = stored(model, checkNotNull(model.stageToolId))
                val count = messages(model).size
                socket.say(CHANGE_REQUEST, "d2", 2_000)
                robot.waitUntil(30_000) { provider.routed.isCompleted }
                move(model, before)
                provider.held.complete(Unit)
                robot.waitUntil(60_000) { messages(model).size == count + 2 && messages(model).last().status !in unfinishedStatuses }
                val answer = messages(model).last()
                assertEquals("${answer.status} ${answer.errorCode} ${answer.error}", listOf("complete", before.id, 2),
                    listOf(answer.status, answer.toolId, answer.toolRevision))
                robot.waitUntil { stored(model, before.id).revision == 2 }
                val saved = stored(model, before.id)
                assertFalse(sameToolJson(JSONObject(before.state), JSONObject(saved.state)))
                check(saved)
            }
        }
    }

    // Plan of stage templates, Task 5: a spoken change of a template screen sends the template file, and the kit merges the changed lines.
    @Test fun aSpokenChangeOfATemplateScreenSendsTheTemplateFile() = templateChange(Scripted.Text(SHIP_S4)) { model, talk, before, _ ->
        assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages)
        val call = talk.calls[1]
        assertEquals(prompt.template, call.systemInstructions)
        assertEquals(listOf(prompt.templateChangeText(changeTask, ship)), call.turns.map { it.text })
        val saved = saved(model, before)
        assertEquals(listOf(shipWithS4(), NAIL), listOf(appFileOf(saved), shipSubject(saved)))
    }

    @Test fun aDeleteLineRemovesThePart() = templateChange(Scripted.Text("delete s3")) { model, talk, before, _ ->
        assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages)
        val saved = saved(model, before)
        assertEquals(listOf(ship.lines().filterNot { it.startsWith("s3 = ") }.joinToString("\n"), NAIL),
            listOf(appFileOf(saved), shipSubject(saved)))
    }

    // The bad s2 stays bad after its one repair: the kit leaves it out and keeps the stored s2, and s4 is saved.
    @Test fun aChangeWithAFaultyLineKeepsTheStoredLine() =
        templateChange(Scripted.Text("$BAD_S2\n$SHIP_S4"), Scripted.Text(BAD_S2)) { model, talk, before, _ ->
            assertEquals(List(3) { APP_FILE_TEMPLATE }, talk.stages)
            val saved = saved(model, before)
            assertEquals(listOf(shipWithS4(), NAIL), listOf(appFileOf(saved), shipSubject(saved)))
        }

    @Test fun aTemplateOutsideRunsTodaysChangeOnTheExpansion() {
        val expanded = shipExpansion(fixture.context)
        templateChange(Scripted.Text(NO_TEMPLATE), plans = AgentScriptProvider(SHIP_CARD_CHANGE)) { model, talk, before, _ ->
            assertEquals(List(2) { APP_FILE_TEMPLATE } + APP_FILE_CHANGE, talk.stages)
            assertEquals(listOf(prompt.changeText(changeTask, expanded)), talk.calls[2].turns.map { it.text })
            val saved = saved(model, before)
            assertEquals(shipCardChanged(expanded), appFileOf(saved))
            assertFalse(prompt.isTemplate(checkNotNull(appFileOf(saved))))
            assertEquals(NAIL, shipSubject(saved))
        }
    }

    // The new screen of the fallback and the full generation give no app either, so the voice says the reason.
    @Test fun aWholeFaultOfAChangeKeepsTheApp() {
        val plans = AgentScriptProvider(List(2) { Scripted.Text(BROKEN_PLAN) } + Scripted.Text(OUTSIDE) + Scripted.Fails("provider"))
        templateChange(Scripted.Text(ITEMS_HEAD), Scripted.Text(ITEMS_HEAD), plans = plans) { model, talk, before, phases ->
            assertEquals(List(3) { APP_FILE_TEMPLATE } + List(3) { APP_FILE } + AGENT, talk.stages)
            assertTrue(phases.toString(), "plan_outcome:fallback:template_whole" in phases)
            assertEquals(listOf(before.revision, before.state), stored(model, before.id).let { listOf(it.revision, it.state) })
            robot.waitUntil { socket.commentary().count { it.first == "d2" } == 2 }
            robot.awaitFrames(5)
            assertEquals(listOf("d2" to FULL_WAIT, "d2" to FULL_NO_ANSWER), socket.commentary().filter { it.first == "d2" })
        }
    }

    // A reply of the stored s1 line merges into the stored text again.
    @Test fun anEmptyTemplateChangeIsEmpty() = templateChange(Scripted.Text(lineOf(ship, "s1"))) { model, talk, before, phases ->
        assertEquals(List(2) { APP_FILE_TEMPLATE }, talk.stages.filter { it == APP_FILE_TEMPLATE })
        assertTrue(phases.toString(), "plan_outcome:fallback:empty" in phases)
        assertEquals(listOf(before.revision, before.state), stored(model, before.id).let { listOf(it.revision, it.state) })
    }

    // Plan "one scene", decision 22: the stored scene stays dimmed with its control off until the change is stored, and no frame of the change
    // draws over it. Alpha 17 (stage templates, Task 18) drew each changed line over the shown screen, so the study saw new shapes with the
    // old labels and the old card for about 6 s.
    @Test fun theOldSceneDimsDuringAChange() {
        var samples = emptyList<ChangeSample>()
        templateChange(Scripted.Deltas(halves("$S4_TEXT\n$CARD_CHANGE").map { 300L to it }), nail = false,
            watch = { model, before -> samples = watchChange(model, before) }) { model, _, before, _ ->
            val framed = samples.filter { it.preview }
            // The host still makes the frames of the change: the last one seeds the drawing of the stored change.
            assertTrue(samples.toString(), framed.any { it.dimmed && it.drawing == listOf("rect", "polygon", "text", "text") })
            assertTrue("a frame of the change drew over the stored scene: $samples", samples.filter { it.dimmed }.all { it.texts == listOf(AIR) })
            // The draft leaves the store just before the commit: from then, a frame shows only as the whole changed scene.
            assertTrue("a part of the change showed without the dim: $samples", framed.filter { !it.dimmed }.all { it.texts == listOf(AIR, NAIL_SINKS) })
            assertTrue("the stored scene was not dimmed while the change call waited: $samples", samples.any { it.dimmed && !it.preview })
            // The routing call comes before the change, and the control stays on during it.
            assertTrue("the control was on during the change: $samples", samples.filter { it.preview || it.dimmed }.none { it.controlOn })
            assertTrue("the voice spoke before the change was stored: $samples", samples.none { it.spoken })
            // The changed scene, at full alpha with its control on.
            robot.waitUntil { shipCanvasTexts(robot) == listOf(AIR, NAIL_SINKS) && stageControlOn(robot) && !stageDimmed(robot) }
            robot.waitUntil { socket.commentary().any { it.first == "d2" } }
            assertEquals(listOf("d2" to "Ответ Alphy: Готово."), socket.commentary().filter { it.first == "d2" })
            assertEquals(ship.replace("card = ", "$S4_TEXT\ncard = ").lines().joinToString("\n") { if (it.startsWith("card = ")) CARD_CHANGE else it },
                appFileOf(saved(model, before)))
        }
    }

    @Test fun theChangePreviewKeepsTheMovedValue() {
        var state: String? = null
        templateChange(Scripted.Deltas(halves(SHIP_S4).map { 300L to it }), watch = { model, before ->
            robot.waitUntil(30_000) { changePreview(model, before)?.frame?.state?.also { state = it } != null }
        }) { model, _, before, _ ->
            // The choice is the one text value of the state, as shipSubject reads it.
            assertEquals(listOf(NAIL), JSONObject(checkNotNull(state)).let { s -> s.keys().asSequence().map(s::get).filterIsInstance<String>().toList() })
            assertEquals(listOf(shipWithS4(), NAIL), saved(model, before).let { listOf(appFileOf(it), shipSubject(it)) })
        }
    }

    // The frame of the change has no s3, and the stage shows the stored s3, dimmed, while the change runs (plan "one scene", decision 22).
    @Test fun aDeleteLineRemovesThePartInThePreview() {
        var samples = emptyList<ChangeSample>()
        templateChange(Scripted.Deltas(listOf(300L to "delete ", 300L to "s3\n")), nail = false,
            watch = { model, before -> samples = watchChange(model, before) }) { model, _, before, _ ->
            assertTrue(samples.toString(), samples.first().texts == listOf(AIR))
            assertTrue(samples.toString(), samples.any { it.preview && it.dimmed && it.drawing == listOf("rect", "polygon") })
            assertTrue("a frame of the change drew over the stored scene: $samples", samples.filter { it.dimmed }.all { it.texts == listOf(AIR) })
            assertTrue("a part of the change showed without the dim: $samples", samples.filter { it.preview && !it.dimmed }.all { it.texts == emptyList<String>() })
            assertEquals(ship.lines().filterNot { it.startsWith("s3 = ") }.joinToString("\n"), appFileOf(saved(model, before)))
            robot.waitUntil { shipCanvasTexts(robot) == emptyList<String>() }
        }
    }

    // The stream makes the frame of s4, then a first line of another template kind leaves no part: the frame goes, and the app stays.
    @Test fun aWholeFailureOfAChangeKeepsTheApp() {
        val plans = AgentScriptProvider(List(2) { Scripted.Text(BROKEN_PLAN) } + Scripted.Text(OUTSIDE) + Scripted.Fails("provider"))
        var shown = false
        val scenes = mutableListOf<List<String>?>()
        templateChange(Scripted.Deltas(halves("$S4_TEXT\n$ITEMS_HEAD\n").map { 300L to it }), Scripted.Text(ITEMS_HEAD), plans = plans,
            watch = { model, before ->
                robot.waitUntil(30_000) { changePreview(model, before) != null && stageDimmed(robot) }
                shown = true
                // From the dim to the spoken end of the failure, dimmed or not, the stage shows the stored scene and no frame of the change.
                val end = SystemClock.uptimeMillis() + 60_000
                while (socket.commentary().count { it.first == "d2" } < 2 && SystemClock.uptimeMillis() < end) {
                    scenes += shipCanvasTexts(robot)
                    SystemClock.sleep(30)
                }
            }) { model, _, before, phases ->
            assertTrue(shown)
            assertTrue("a frame of the failed change showed: $scenes", scenes.isNotEmpty() && scenes.all { it != null && it == scenes.first() })
            assertTrue(phases.toString(), "plan_outcome:fallback:template_whole" in phases && "stage_preview_gone" in phases)
            assertNull(model.tools.stagePreviews.current)
            assertEquals(listOf(before.revision, before.state), stored(model, before.id).let { listOf(it.revision, it.state) })
            robot.waitUntil { socket.commentary().count { it.first == "d2" } == 2 }
            robot.awaitFrames(5)
            assertEquals(listOf("d2" to FULL_WAIT, "d2" to FULL_NO_ANSWER), socket.commentary().filter { it.first == "d2" })
            // The stored scene is back at full alpha with its control on (plan "one scene", decision 22).
            robot.waitUntil { shipCanvasTexts(robot) == scenes.first() && stageControlOn(robot) && !stageDimmed(robot) }
        }
    }

    // Plan "one scene", decision 22: a spoken change of a full mini-app that adds a card below the fold scrolls the stage to the card and
    // marks it for 1 s. The first screen of the app marks nothing.
    @Test fun aNewPartScrollsIntoViewAndIsMarked() {
        shell.live(TemplateTalk(plans = AgentScriptProvider(appFilePlan("lesson"), FACT_CARD))) { _, model, _ ->
            shell.app(model)
            during(1_200) { assertNull("the first screen of the app marked a part", markedPart()) }
            assertFalse("the card is on the stage before the change", stageHasText(robot, FACT_TITLE))
            socket.say(TemplateTalk.CHANGE_REQUEST, "d2", 2_000)
            robot.waitUntil(60_000) { markedPart() != null }
            val first = SystemClock.uptimeMillis()
            val part = checkNotNull(markedPart()) { "the mark ended at once" }
            assertTrue("the marked part is not the new card: $part", FACT_TITLE in part.texts)
            // The stage reads with no wait for an idle main thread, because the mark lasts 1 s.
            while (markedPart()?.inView != true) {
                check(SystemClock.uptimeMillis() - first < 1_000) { "the new card did not come into view while it was marked: ${markedPart()}" }
                SystemClock.sleep(30)
            }
            SystemClock.sleep(maxOf(0, first + 1_200 - SystemClock.uptimeMillis()))
            assertNull("the mark stayed after 1.2 s", markedPart())
            assertTrue("the new card left the stage", stageHasText(robot, FACT_TITLE))
        }
    }

    /** A node of the stage with the state description of a new part: the texts in it, and whether the stage shows all of it. */
    private data class MarkedPart(val texts: List<String>, val inView: Boolean)

    /** The first node of the stage marked new, or null. The bounds come from the position and the size, so the scroll does not clip them. */
    private fun markedPart(): MarkedPart? = readStage(robot) { nodes ->
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        fun top(node: SemanticsNode) = node.positionInWindow.y
        val stage = nodes.firstOrNull() ?: return@readStage null
        nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.StateDescription) == NEW_PART }?.let { node ->
            MarkedPart(all(node).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } },
                top(node) >= top(stage) && top(node) + node.size.height <= top(stage) + stage.size.height)
        }
    }

    @Test fun anOutsideChangeShowsNoStream() =
        templateChange(Scripted.Deltas(listOf(300L to NO_TEMPLATE.take(4), 300L to NO_TEMPLATE.drop(4) + "\n")),
            plans = AgentScriptProvider(SHIP_CARD_CHANGE)) { model, talk, before, phases ->
            assertEquals(List(2) { APP_FILE_TEMPLATE } + APP_FILE_CHANGE, talk.stages)
            assertTrue(phases.toString(), phases.none { it.startsWith("stage_preview") })
            assertEquals(2, saved(model, before).revision)
        }

    /** One read of the stage while a change runs: the canvas texts, the drawing of a change preview of the tool, what is on, and the dim. */
    private data class ChangeSample(val texts: List<String>?, val preview: Boolean, val drawing: List<String>?, val controlOn: Boolean, val spoken: Boolean,
        val dimmed: Boolean)

    /** The stage every 30 ms until the change of [before] is stored as revision 2. */
    private fun watchChange(model: ShellViewModel, before: ToolRecord): List<ChangeSample> {
        val samples = mutableListOf<ChangeSample>()
        val end = SystemClock.uptimeMillis() + 60_000
        while (true) {
            // The voice is read before the revision, so a sample with a spoken answer and no stored change is a true early answer.
            val spoken = socket.commentary().any { it.first == "d2" }
            if (stored(model, before.id).revision != before.revision) break
            check(SystemClock.uptimeMillis() < end) { "the change was not stored within 60 s" }
            val preview = changePreview(model, before)
            // Each read is one main-thread pass. A sample whose reads straddle the start or the end of the dim shows no one screen, so it is dropped.
            val dimmed = stageDimmed(robot)
            val sample = ChangeSample(shipCanvasTexts(robot), preview != null, preview?.frame?.drawing?.let(::drawingTypes),
                stageControlOn(robot), spoken, dimmed)
            if (stageDimmed(robot) == dimmed) samples += sample
            SystemClock.sleep(30)
        }
        return samples
    }

    /** The uncommitted preview of a change of [before] that the stage shows, or null. */
    private fun changePreview(model: ShellViewModel, before: ToolRecord) =
        model.tools.stagePreviews.current?.takeIf { it.toolId == before.id && it.revision == null }

    /** The type of each drawing command of a frame drawing, in order. */
    private fun drawingTypes(drawing: String) = JSONObject(drawing).let { surfaces ->
        surfaces.keys().asSequence().flatMap { id -> surfaces.getJSONObject(id).getJSONArray("commands").let { list -> List(list.length()) { list.getJSONObject(it).getString("type") } } }.toList()
    }

    /**
     * The voice mode puts the ship template on the stage, and with [nail] the person sets its choice to [NAIL]. Then a spoken change gets
     * [changes] as the replies of its template calls and [plans] as the replies of each other generation call. [watch] reads the stage while
     * the change runs. [check] gets the model, the provider, the stored app before the change, and the trace phases of the change, after its
     * answer.
     */
    private fun templateChange(vararg changes: Scripted, plans: AgentScriptProvider = AgentScriptProvider(appFilePlan("calc")),
        nail: Boolean = true, watch: (ShellViewModel, ToolRecord) -> Unit = { _, _ -> },
        check: (ShellViewModel, TemplateTalk, ToolRecord, List<String>) -> Unit) {
        val talk = TemplateTalk(AgentScriptProvider(listOf(Scripted.Text(ship)) + changes), plans)
        shell.live(talk) { _, model, phases ->
            val app = shell.screen(model)
            if (nail) {
                robot.instrumentation.runOnMainSync { model.tools.interact(app, SHIP_CONTROL, NAIL) }
                robot.waitUntil { shipSubject(stored(model, app.id)) == NAIL && !model.tools.busy }
            }
            val before = stored(model, app.id)
            val count = messages(model).size
            val start = phases.size
            socket.say(TemplateTalk.CHANGE_REQUEST, "d2", 2_000)
            watch(model, before)
            shell.waitForTheAnswer(model, count)
            check(model, talk, before, phases.drop(start))
        }
    }

    /** The change of [before] saved as revision 2 of the same app. */
    private fun saved(model: ShellViewModel, before: ToolRecord): ToolRecord {
        robot.waitUntil { stored(model, before.id).revision == 2 }
        return stored(model, before.id)
    }

    private fun stored(model: ShellViewModel, id: String) = model.tools.tools.first { it.id == id }

    private fun people(record: ToolRecord) = JSONObject(record.state).getInt("people")

    private fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    /**
     * The routing call of [APP_REQUEST] creates an app, and the routing call of [CHANGE_REQUEST] completes [routed] and changes the app after
     * [held]. Each plan call and change call answers from [script]. A template call gets [NO_TEMPLATE]. The plan call that starts beside a
     * routing call waits for its cancel.
     */
    private class HeldChange(private val script: AgentScriptProvider) : ChatProvider {
        val routed = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            if (answerNoTemplate(request, emit)) return
            if (request.generationStage != null) {
                if (EARLY_BRIEF in request.turns.last().text) awaitCancellation()
                return script.respond(key, request, session, emit)
            }
            val change = request.decisionContext?.originalUserTurn?.text == CHANGE_REQUEST
            if (change) { routed.complete(Unit); held.await() }
            emit(CloudEvent.RoundComplete(if (change) chatDecision("refine", "Готово.", "Change the shown app")
                else chatDecision("create", "Готово.", "Make the app"), false))
        }
    }

    private companion object {
        const val APP_REQUEST = "Сделай приложение"
        const val CHANGE_REQUEST = "Измени его"
        /** The plan call beside the routing call starts before a brief exists, so its prompt has an empty brief line. */
        const val EARLY_BRIEF = "\nWidget brief: \n"
        /** A change line of the calc plan: a figure from the count of people. */
        const val SEATS = "need seats = figure(\"count\", \"Seats\", \$/people * 2, example=[{people: 4}, 8])"
        /** A first line of another template kind: the stored parts do not fit it, so no part can show. */
        const val ITEMS_HEAD = "items(\"Почему корабль не тонет?\", lang=\"ru\")"
        const val OUTSIDE = "outside(\"game\")"
        const val FULL_WAIT = "Быстро не получилось. Alphy собирает экран заново, это займёт около минуты."
        const val FULL_NO_ANSWER = "Экран не собран: сервис ИИ не ответил. Скажите запрос полностью, одной фразой."
        /** The calc plan with a fault in the line of perPerson. */
        val BROKEN_PLAN get() = appFilePlan("calc").replace("/ \$/people, example", "/ , example")
        const val NAIL_SINKS = "Гвоздь тонет"
        /** A new text part of the ship template that shows for each choice. */
        const val S4_TEXT = "s4 = text(150, 190, \"$NAIL_SINKS\", \"ink\", 14)"
        const val CARD_CHANGE = "card = card(\"Вытеснение воды\", \"Гвоздь тонет, корабль плывёт.\")"
        const val FACT_TITLE = "Факт"
        /** A card that a change of the lesson plan adds after its question, below the fold of the stage. */
        const val FACT_CARD = "need fact = card(\"$FACT_TITLE\", \"Луна делает один оборот вокруг оси за 27 суток.\")"
        /** The state description of the mark of a new part. */
        const val NEW_PART = "Новое"
    }
}

/** True when the stage dims its scene while a change of it runs (plan "one scene", decision 22). */
internal fun stageDimmed(robot: ShellRobot) = readStage(robot) { nodes -> nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "stage_dimmed" } }

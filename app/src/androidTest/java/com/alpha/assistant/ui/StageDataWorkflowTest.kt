package com.alpha.assistant.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage.APP_FILE_TEMPLATE
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Source
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.TABLE_ROWS_TAG
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.appfile.AppFilePrompt
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.ui.DataTalk.Companion.CHANGE_REQUEST
import com.alpha.assistant.ui.DataTalk.Companion.CHART_REQUEST
import com.alpha.assistant.ui.DataTalk.Companion.DATA_BRIEF
import com.alpha.assistant.ui.DataTalk.Companion.DATA_REQUEST
import com.alpha.assistant.ui.DataTalk.Companion.MARK_REQUEST
import com.alpha.assistant.ui.DataTalk.Companion.PLANETS
import com.alpha.assistant.ui.DataTalk.Companion.PLANETS_SOURCE
import com.alpha.assistant.ui.DataTalk.Companion.SEARCHED_REQUEST
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_RATE
import com.alpha.assistant.voice.LiveProtocol.MARK
import com.alpha.assistant.voice.inputDelta
import com.alpha.assistant.voice.outputAudio
import com.alpha.assistant.voice.outputDelta
import android.os.SystemClock
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The data family on the dialogue stage (block 2 plan, stage 5): a data decision stops the early explain call and sends one data call (D1),
 * the host check of a searched form (decision 3, D6), the mark by a tap and by a routing press (decision 2, D7), and the native marks (D8).
 * The real shell, kit, and sandbox run with scripted providers.
 */
class StageDataWorkflowTest {
    private val fixture = ShellFixture("stage-data")
    private val robot = ShellRobot("stage-data")
    private val socket = FakeLiveTransport()
    private val shell = TemplateShell(fixture, robot, socket)
    private val prompt get() = AppFilePrompt.load(fixture.context)
    @After fun remove() = shell.remove()

    @Test fun aDataDecisionStopsTheEarlyCallAndSendsOneDataCall() {
        val talk = DataTalk(AgentScriptProvider(PLANETS))
        shell.live(talk) { scenario, model, phases ->
            socket.say(DATA_REQUEST, "d1", 100)
            val app = shell.shown(model)
            assertEquals(PLANETS.trimEnd(), shell.finalFile(model, app.id)?.trimEnd())
            val call = talk.calls.single()
            assertEquals(APP_FILE_TEMPLATE, call.generationStage)
            assertEquals(prompt.templateData, call.systemInstructions)
            val input = call.turns.single().text
            assertTrue(input, input.startsWith("User request: $DATA_REQUEST\nWidget brief: $DATA_BRIEF\n"))
            assertFalse(input, prompt.dialogue in input)
            assertEquals(listOf("early_plan_start", "early_plan_canceled"), phases.filter { it.startsWith("early_plan") })
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, CARD_TITLE) }
        }
    }

    @Test fun aSearchedFormWithEachNumberInTheExcerptsShowsItsSource() {
        val talk = DataTalk(AgentScriptProvider(PLANETS))
        shell.live(talk) { scenario, model, phases ->
            socket.say(SEARCHED_REQUEST, "d1", 100)
            val app = shell.shown(model)
            val file = checkNotNull(shell.finalFile(model, app.id))
            assertTrue(file, file.startsWith(PLANETS.substringBefore("say = ")))
            assertTrue(file, "\nsource = source(\"example.org\", \"" in file)
            assertTrue(talk.calls.single().turns.single().text.contains("\nSource [1] excerpt: ${PLANETS_SOURCE.title}\n${PLANETS_SOURCE.summary}"))
            robot.waitUntil(30_000) {
                robot.composedIn(scenario, STAGE) { config -> config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("example.org · ") } == true }
            }
            assertFalse(phases.toString(), "data_rejected" in phases)
        }
    }

    @Test fun aNumberThatNoExcerptHoldsGivesNodataThatTheVoiceSays() {
        val talk = DataTalk(AgentScriptProvider(PLANETS.replace("687", "686")))
        shell.live(talk) { scenario, model, phases ->
            socket.say(SEARCHED_REQUEST, "d1", 100)
            val app = shell.shown(model)
            val file = checkNotNull(shell.finalFile(model, app.id))
            assertTrue(file, file.startsWith("nodata(\"Планеты\", lang=\"ru\")\ncard = card(\"Нет точных данных\", "))
            assertTrue(file, "686" in file && "\nsay = " in file)
            assertTrue(phases.toString(), "data_rejected" in phases)
            robot.waitUntil(30_000) { robot.composedTextIn(scenario, STAGE, "Нет точных данных") }
            robot.waitUntil(30_000) { socket.thinking().any { "686" in it.second } }
        }
    }

    /** Fix 2 of stage 5: a spoken change of a searched form keeps the excerpts of its search, so a value that no excerpt holds gives nodata. */
    @Test fun aSpokenChangeOfASearchedFormWithANewValueGivesNodata() =
        changed(PLANETS.replace("r2 = row(\"Марс\", [687])", "r2 = row(\"Марс\", [687])\nr3 = row(\"Меркурий\", [88])")) { file ->
            assertTrue(file, file.startsWith("nodata(\"Планеты\", lang=\"ru\")\n") && "88" in file)
        }

    /** The same change with a value that an excerpt holds (225) gives the new form with its source line. */
    @Test fun aSpokenChangeOfASearchedFormWithHeldValuesKeepsItsSourceLine() =
        changed(PLANETS.replace("r2 = row(\"Марс\", [687])", "r2 = row(\"Марс\", [687])\nr3 = row(\"Венера\", [225])")) { file ->
            assertTrue(file, file.startsWith("table(\"Планеты\"") && "225" in file && "\nsource = source(\"example.org\", \"" in file)
        }

    /** A searched table, then the spoken [CHANGE_REQUEST] with no search that the template call answers with [reply]. [check] gets the stored file. */
    private fun changed(reply: String, check: (String) -> Unit) {
        val talk = DataTalk(AgentScriptProvider(PLANETS, reply))
        shell.live(talk) { _, model, _ ->
            socket.say(SEARCHED_REQUEST, "d1", 100)
            val first = shell.shown(model)
            shell.finalFile(model, first.id)
            val count = shell.messages(model).size
            socket.say(CHANGE_REQUEST, "d2", 2_000)
            shell.waitForTheAnswer(model, count)
            robot.waitUntil(60_000) { model.stageToolId.let { it != null && it != first.id } && !model.tools.busy }
            assertEquals(2, talk.calls.size)
            assertTrue(talk.calls.last().turns.single().text.contains("\nSource [1] excerpt: ${PLANETS_SOURCE.title}\n"))
            check(checkNotNull(shell.finalFile(model, checkNotNull(model.stageToolId))))
        }
    }

    @Test fun aTapAndARoutingPressMoveTheMarkAndTheShownScreenNamesIt() {
        val talk = DataTalk(AgentScriptProvider(PLANETS))
        shell.live(talk) { scenario, model, _ ->
            socket.say(DATA_REQUEST, "d1", 100)
            val app = shell.shown(model)
            shell.finalFile(model, app.id)
            fun shown() = checkNotNull(ShownScreen.of(shell.stored(model, app.id)))
            assertEquals(listOf("Планеты: Земля", "Планеты: Марс"), shown().controls.map { it.label })
            assertEquals(listOf("Планеты" to "Марс"), shown().values)
            assertTrue(shown().line(), "\"values\":[{\"control\":\"Планеты\",\"value\":\"Марс\"}]" in shown().line())
            tap(scenario, "Земля")
            robot.waitUntil(10_000) { shown().values == listOf("Планеты" to "Земля") }
            val count = shell.messages(model).size
            socket.say(MARK_REQUEST, "d2", 2_000)
            shell.waitForTheAnswer(model, count)
            robot.waitUntil(10_000) { shown().values == listOf("Планеты" to "Марс") }
            // The mark moves with no template call.
            assertEquals(1, talk.calls.size)
        }
    }

    /** A follow mark of a row moves the mark with no words of the host and no template call (block 2 plan, D7). */
    @Test fun aFollowMarkMovesTheMarkWithNoWords() {
        val talk = DataTalk(AgentScriptProvider(PLANETS))
        shell.live(talk) { _, model, phases ->
            socket.say(DATA_REQUEST, "d1", 100)
            val app = shell.shown(model)
            shell.finalFile(model, app.id)
            fun shown() = checkNotNull(ShownScreen.of(shell.stored(model, app.id)))
            // The voice says the narration line, so the next turn of the person is a voice-only turn.
            robot.waitUntil(30_000) { socket.thinking().isNotEmpty() }
            socket.emit(outputDelta(socket.thinking().last().second.removePrefix(MARK), 1_500, 2_100))
            socket.emit(outputAudio(3, LIVE_RATE * 6 / 10))
            SystemClock.sleep(1_300)
            talk.follow = CloudEvent.Follow("mark", "", "Планеты: Земля")
            val texts = socket.commentary().size to socket.thinking().size
            socket.emit(inputDelta("А у какой планеты год короче?", 3_000, 3_500))
            socket.emit(outputDelta("Короче год у Земли.", 3_700, 4_200))
            robot.waitUntil(10_000) { shown().values == listOf("Планеты" to "Земля") }
            assertTrue(phases.toString(), "follow:mark" in phases)
            assertEquals(texts, socket.commentary().size to socket.thinking().size)
            assertEquals(1, talk.calls.size)
        }
    }

    @Test fun aMarkedRowAndCellShowAndAChartOfEightPointsShowsEachLabel() {
        val table = appFilePlan("template/table-caps")
        val chart = appFilePlan("template/chart-caps")
        val talk = DataTalk(AgentScriptProvider(table, chart))
        shell.live(talk) { scenario, model, _ ->
            socket.say(DATA_REQUEST, "d1", 100)
            val first = shell.shown(model)
            shell.finalFile(model, first.id)
            robot.waitUntil(30_000) { selectedHolds(scenario, MARKED_ROW) }
            // The marked cell is the value of the column of the mark in the marked row.
            val cell = Regex("""r4 = row\("[^"]*", \["[^"]*", "([^"]*)"""").find(table)!!.groupValues[1]
            robot.waitUntil(10_000) { selectedHolds(scenario, cell, own = true) }
            val count = shell.messages(model).size
            socket.say(CHART_REQUEST, "d2", 2_000)
            shell.waitForTheAnswer(model, count)
            robot.waitUntil(60_000) { model.stageToolId.let { it != null && it != first.id } && !model.tools.busy }
            // The robot compares a shown text without its trailing spaces, and some cap labels end with one.
            val labels = Regex("""p\d = point\("([^"]*)"""").findAll(chart).map { it.groupValues[1].trimEnd() }.toList()
            assertEquals(8, labels.size)
            robot.waitUntil(30_000) { labels.all { robot.composedTextIn(scenario, STAGE, it) } }
            robot.waitUntil(10_000) { selectedHolds(scenario, MARKED_ROW, own = true) }
        }
    }

    /** Fix round of stage 5: a phone shows 6 rows in the box of a table, and a mark under the shown rows scrolls into the box. */
    @Test fun aMarkUnderTheShownRowsScrollsIntoTheBox() {
        val table = appFilePlan("template/table-caps").replace("mark(\"Марс четвёртая п\"", "mark(\"$LAST_ROW\"")
        shell.live(DataTalk(AgentScriptProvider(table))) { scenario, model, _ ->
            socket.say(DATA_REQUEST, "d1", 100)
            shell.finalFile(model, shell.shown(model).id)
            robot.waitUntil(30_000) {
                val box = robot.composedBoundsIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.TestTag) == TABLE_ROWS_TAG }
                val row = robot.composedBoundsIn(scenario, STAGE, robot.hasText(LAST_ROW))
                box != null && row != null && row.top >= box.top && row.bottom <= box.bottom
            }
        }
    }

    /** A tap on the row or the point with [text]: the click action of the nearest node around it that has one. */
    private fun tap(scenario: ActivityScenario<CloudTestActivity>, text: String) = scenario.onActivity { activity ->
        val node = robot.nodes(activity).first { robot.hasText(text)(it.config) }
        val target = generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
        checkNotNull(target.config[SemanticsActions.OnClick].action).invoke()
    }

    /** A composed node that is marked (selected) holds [text]: in its own text with [own], else in its own text or a text inside it. */
    private fun selectedHolds(scenario: ActivityScenario<CloudTestActivity>, text: String, own: Boolean = false): Boolean {
        var found = false
        scenario.onActivity { activity ->
            fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                if (own) emptyList() else node.children.flatMap(::texts)
            found = robot.nodes(activity).any { it.config.getOrNull(SemanticsProperties.Selected) == true && text in texts(it) }
        }
        return found
    }

    private companion object {
        const val STAGE = "dialogue_stage"
        const val CARD_TITLE = "Год Марса длиннее"
        /** The label of the marked row of the table cap file and of the marked point of the chart cap file. */
        const val MARKED_ROW = "Марс четвёртая п"
        /** The label of the last row of the table cap file. */
        const val LAST_ROW = "Хаумеа карлик че"
    }
}

/**
 * The scripted provider of a talk with data screens (block 2 plan, stage 5). Each template call answers from [templates], and the early call
 * beside the routing call waits for its cancel and goes to no list. Each follow call gets [follow]. The routing call gives a data screen for [DATA_REQUEST] and
 * [CHART_REQUEST], a data screen after a search with [PLANETS_SOURCE] for [SEARCHED_REQUEST], a press of the row Марс for [MARK_REQUEST], and a change of the stage form for [CHANGE_REQUEST].
 */
internal class DataTalk(private val templates: AgentScriptProvider) : ChatProvider {
    val calls = CopyOnWriteArrayList<CloudRequest>()
    /** The answer of each follow call. */
    @Volatile var follow = CloudEvent.Follow("none", "", "")
    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        if (request.follow) return emit(follow)
        if (request.generationStage != null) {
            if (EARLY_BRIEF in request.turns.last().text) awaitCancellation()
            calls += request
            return templates.respond(key, request, session, emit)
        }
        val text = request.decisionContext?.originalUserTurn?.text
        if (text == SEARCHED_REQUEST) emit(CloudEvent.Search("found", listOf(PLANETS_SOURCE)))
        fun data(search: String) = JSONObject(chatDecision("screen", DATA_TEXT, DATA_BRIEF)).put("search", search).put("show", true).put("family", "data").toString()
        emit(CloudEvent.RoundComplete(when (text) {
            DATA_REQUEST, CHART_REQUEST -> data("none")
            SEARCHED_REQUEST -> data("required")
            MARK_REQUEST -> chatDecision("press", "Отмечаю Марс.", "Планеты: Марс")
            CHANGE_REQUEST -> chatDecision("refine", "Меняю таблицу.", "The same planets with one change")
            else -> chatDecision("answer", "Fixture response")
        }, false))
    }

    companion object {
        const val DATA_REQUEST = "Покажи планеты таблицей"
        const val CHART_REQUEST = "Покажи это графиком"
        const val SEARCHED_REQUEST = "Найди длину года планет и покажи таблицей"
        const val MARK_REQUEST = "Отметь Марс"
        const val CHANGE_REQUEST = "Добавь ещё одну планету"
        const val DATA_BRIEF = "The length of the year of the planets"
        const val DATA_TEXT = "Год Марса почти вдвое длиннее земного."
        val PLANETS_SOURCE = Source("Планеты", "https://example.org/planets", "Год Земли длится 365 дней, а год Марса 687 дней. Год Венеры длится 225 дней.")
        /** The early call beside the routing call starts before a brief exists, so its prompt has an empty brief line. */
        private const val EARLY_BRIEF = "\nWidget brief: \n"
        /** A table of two planets, whose numbers the summary of [PLANETS_SOURCE] holds. */
        val PLANETS = listOf("table(\"Планеты\", lang=\"ru\")", "control = mark(\"Марс\")", "head = head([\"Планета\", \"Год, дней\"])",
            "r1 = row(\"Земля\", [365])", "r2 = row(\"Марс\", [687])", "card = card(\"Год Марса длиннее\", \"Марс идёт вокруг Солнца почти два земных года.\")",
            "say = \"Смотри: год Марса почти вдвое длиннее земного.\"").joinToString("\n", postfix = "\n")
    }
}

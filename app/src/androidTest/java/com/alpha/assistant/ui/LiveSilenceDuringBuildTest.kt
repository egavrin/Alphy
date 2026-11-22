package com.alpha.assistant.ui

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.data.Draft
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.agentFixtureFinish
import com.alpha.assistant.tools.agentWrite
import com.alpha.assistant.tools.send
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LiveConnection
import com.alpha.assistant.voice.LiveProtocol
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveTiming
import com.alpha.assistant.voice.LiveTransport
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * O1 of the UX study of 2026-11-21 (lane B): the voice mode closed by its silence timer about 1 s after the full generation put its
 * screen on the stage. Here the scripted voice is silent, the silence time is [SILENCE_MS], and the full generation ends no earlier
 * than [BUILD_MS] after its waiting line. Each [ROW_MS] row and each change of the state logs the status of the hand-off message,
 * handOffRunning(), and the phase of the session to logcat (tag [TAG]). All times are relative to the waiting line.
 */
class LiveSilenceDuringBuildTest {
    private val stage = StageShell("live-silence-build")
    private val robot get() = stage.robot
    private val socket get() = stage.socket
    /** Each text that the session sent to the socket, with its uptime. */
    private val sent = CopyOnWriteArrayList<Pair<Long, JSONObject>>()
    /** The start and the end of each chat call, each generation phase, and each agent step, with its uptime. */
    private val marks = CopyOnWriteArrayList<Pair<Long, String>>()
    private val agentCalls = AtomicInteger()
    /** The scripted socket, with the uptime of each sent text in [sent]. */
    private val stamped = LiveTransport { url, key, listener ->
        val inner = socket.connect(url, key, listener)
        object : LiveConnection {
            override fun send(text: String) = inner.send(text).also { sent += SystemClock.uptimeMillis() to JSONObject(text) }
            override fun close() = inner.close()
        }
    }
    @After fun remove() = stage.remove()

    /** The rule of the silence close stays for a talk with nothing in progress: the close comes at the first check. */
    @Test fun theTalkClosesAfterSilenceWithNoBuild() {
        var start = 0L
        val status = silentTalk(before = { start = SystemClock.uptimeMillis() })
        closesOneSilenceTimeAfter("session.started", start, status)
    }

    /** A spoken request gets an answer with no app: the hand-off ends, and the close comes one silence time after the answer line. */
    @Test fun theTalkClosesOneSilenceTimeAfterAnAnswer() {
        val status = silentTalk { _, _ -> socket.say(REQUEST, "d1", 100) }
        val answer = sent.first { (_, event) ->
            event.optString("type") == COMMENTARY && event.optString("content").startsWith(LiveProtocol.MARK)
        }.first
        closesOneSilenceTimeAfter("the answer line", answer, status)
    }

    /** A typed turn ends, and the close comes one silence time after its thinking line. */
    @Test fun theTalkClosesOneSilenceTimeAfterATypedTurn() {
        val status = silentTalk { scenario, model -> scenario.onActivity { model.editDraft(Draft("Привет")); model.sendLiveTyped() } }
        closesOneSilenceTimeAfter("the thinking line", sent.first { it.second.optString("type") == THINKING }.first, status)
    }

    /**
     * Starts the voice mode with the silent scripted voice and the fixture chat, runs [before] just before `session.started` and [act]
     * after it, waits for the close, and gives the end status.
     */
    private fun silentTalk(before: () -> Unit = {}, act: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit = { _, _ -> }): String {
        stage.fixture.install(liveVoice = stage.fixture.liveVoice(stamped, stage.audio, LOOPBACK, LiveTiming(silenceMs = SILENCE_MS)))
        return stage.fixture.launch().use { scenario ->
            val model = checkNotNull(stage.fixture.model)
            robot.waitUntil { !model.loading }
            scenario.onActivity { model.startLiveVoice() }
            robot.waitUntil { socket.connected }
            before()
            socket.emit(LIVE_STARTED)
            robot.waitUntil { model.liveVoice.phase != LivePhase.CONNECTING }
            act(scenario, model)
            // Long enough to see a close at a later check, so the bound below names it.
            robot.waitUntil(SILENCE_MS * 4) { closeAt() != null && model.liveVoice.phase == LivePhase.IDLE }
            lateinit var status: String
            robot.instrumentation.runOnMainSync { status = model.liveVoice.status }
            status
        }
    }

    /** The silence close comes at the first check after [origin] (named [what]): at least one silence time later, and not a later check. */
    private fun closesOneSilenceTimeAfter(what: String, origin: Long, status: String) {
        val after = checkNotNull(closeAt()) - origin
        assertTrue("Close $after ms after $what, expected from $SILENCE_MS to ${SILENCE_MS * 3 / 2} ms",
            after >= SILENCE_MS && after < SILENCE_MS * 3 / 2)
        assertEquals(robot.text(R.string.live_voice_no_speech), status)
    }

    @Test fun theTalkStaysOpenDuringALongBuild() {
        withPlanStep(stage.fixture.context, AiProvider.OPENAI, true) {
            stage.fixture.install(chat = chat, liveVoice = stage.fixture.liveVoice(stamped, stage.audio, LOOPBACK, LiveTiming(silenceMs = SILENCE_MS)))
            stage.fixture.launch().use { scenario ->
                val model = checkNotNull(stage.fixture.model)
                robot.waitUntil { !model.loading }
                fun row(): Row {
                    val message = model.snapshot.selected?.messages?.find { it.id == model.liveVoiceCardId }
                    return Row(SystemClock.uptimeMillis(), message?.status, message?.toolId != null, model.liveVoiceWorking,
                        model.liveVoice.phase, model.stageToolId != null)
                }
                val changes = CopyOnWriteArrayList<Row>()
                // Registered before the voice mode starts, so it runs before the snapshot flow of the bridge for the same change.
                val observer = Snapshot.registerApplyObserver { _, _ ->
                    if (Looper.myLooper() == Looper.getMainLooper())
                        row().let { if (it.copy(at = 0) != changes.lastOrNull()?.copy(at = 0)) changes += it }
                }
                val rows = ArrayList<Row>()
                try {
                    scenario.onActivity {
                        model.tools.generationTraceObserver = { event ->
                            val mark = when (event) {
                                is ToolGenerationTraceEvent.Phase -> "phase ${event.name} ${event.detail.orEmpty()}"
                                is ToolGenerationTraceEvent.AgentStep -> "agent_step ${event.tool} ${event.durationNanos / 1_000_000} ms"
                                else -> null
                            }
                            mark?.let { marks += SystemClock.uptimeMillis() to it }
                        }
                        model.startLiveVoice()
                    }
                    robot.waitUntil { socket.connected }
                    socket.emit(LIVE_STARTED)
                    robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
                    socket.say(REQUEST, "d1", 100)
                    val start = SystemClock.uptimeMillis()
                    while (true) {
                        lateinit var current: Row
                        robot.instrumentation.runOnMainSync { current = row() }
                        rows += current
                        val now = current.at
                        val screen = changes.firstOrNull { it.app }?.at
                        val close = closeAt()
                        if (screen != null && (close != null && now > maxOf(screen, close) + 500 || now > screen + SILENCE_MS + 2_000)) break
                        if (now > start + LIMIT_MS) break
                        SystemClock.sleep(maxOf(0L, start + rows.size * ROW_MS - SystemClock.uptimeMillis()))
                    }
                } finally {
                    observer.dispose()
                }
                lateinit var status: String
                robot.instrumentation.runOnMainSync { status = model.liveVoice.status }
                // Before the end of the mode by the test, which sends its own close.
                val close = closeAt()
                scenario.onActivity { model.endLiveVoice() }
                judge(rows, changes, status, close)
            }
        }
    }

    /** Logs the run and checks the two points of the brief together, so the failure message names each one. */
    private fun judge(rows: List<Row>, changes: List<Row>, status: String, close: Long?) {
        val t0 = fullWaitAt()
        val origin = t0 ?: rows.first().at
        val screen = changes.firstOrNull { it.app }?.at
        val events = sent.filter { it.second.optString("type") in setOf(COMMENTARY, CLOSE) }.map { (at, event) ->
            "%+6d ms sent %s %s".format(at - origin, event.optString("type"), event.optString("content"))
        }
        val silenceClose = close == null || status == robot.text(R.string.live_voice_no_speech)
        val summary = "waiting line ${t0 != null}, screen ${screen?.minus(origin)} ms, close ${close?.minus(origin)} ms, " +
            "close after the screen ${if (screen != null && close != null) close - screen else null} ms, end status «$status»"
        val head = listOf(summary) + events + marks.map { (at, mark) -> "%+6d ms %s".format(at - origin, mark) } +
            changes.map { "change " + it.line(origin) }
        (head + rows.map { "row " + it.line(origin) }).forEach { Log.i(TAG, it) }
        val timeline = head.joinToString("\n")
        assertTrue("The full generation sent no waiting line.\n$timeline", t0 != null)
        assertTrue("The screen came less than $BUILD_MS ms after the waiting line, so the run is not a long build.\n$timeline",
            screen != null && screen - t0!! >= BUILD_MS)
        val openAt5 = close == null || close > t0!! + BUILD_MS
        val lateClose = close == null || close - screen!! >= SILENCE_MS
        // The run waits up to SILENCE_MS + 2000 ms after the screen: the count starts again at the screen, and the close still comes.
        val closed = close != null
        assertTrue("phase not IDLE at $BUILD_MS ms: $openAt5. Close at least $SILENCE_MS ms after the screen: $lateClose. " +
            "Silence close: $silenceClose. A close after the screen: $closed.\n$timeline", openAt5 && lateClose && silenceClose && closed)
    }

    private fun fullWaitAt() = sent.firstOrNull { (_, event) ->
        event.optString("type") == COMMENTARY && LiveVoiceBridge.FULL_WAIT in event.optString("content")
    }?.first

    private fun closeAt() = sent.firstOrNull { it.second.optString("type") == CLOSE }?.first

    /**
     * The routing call gives a screen that the person asked to see. Each plan call replies outside, so the fast path gives no app and the
     * full generation starts. Its first loop call waits until [BUILD_MS] after the waiting line, then writes the fixture app and finishes.
     */
    private val chat = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            val name = request.generationStage?.name ?: "routing"
            marks += SystemClock.uptimeMillis() to "call_start $name"
            try { reply(request, emit) } finally { marks += SystemClock.uptimeMillis() to "call_end $name" }
        }

        private suspend fun reply(request: CloudRequest, emit: suspend (CloudEvent) -> Unit) {
            if (answerNoTemplate(request, emit)) return
            val reply = when (request.generationStage) {
                null -> return emit(CloudEvent.RoundComplete(JSONObject(chatDecision("screen", ANSWER, BRIEF)).put("show", true).toString(), false))
                CloudGenerationStage.AGENT -> if (agentCalls.getAndIncrement() == 0) {
                    val t0 = checkNotNull(fullWaitAt()) { "The full generation started with no waiting line." }
                    delay(maxOf(0L, t0 + BUILD_MS - SystemClock.uptimeMillis()))
                    agentWrite(FIXTURE_APP)
                } else agentFixtureFinish
                else -> OUTSIDE
            }
            Scripted.Text(reply).send(emit)
        }
    }

    /** One reading of the state: the hand-off message has a status and an app, handOffRunning(), the phase, and an app on the stage. */
    private data class Row(val at: Long, val status: String?, val app: Boolean, val running: Boolean, val phase: LivePhase, val stage: Boolean) {
        fun line(origin: Long) = "%+6d ms status=%s handOffRunning=%s phase=%s app=%s stage=%s".format(at - origin, status, running, phase, app, stage)
    }

    private companion object {
        const val TAG = "LiveSilenceDuringBuild"
        const val SILENCE_MS = 3_000L
        const val BUILD_MS = 5_000L
        const val ROW_MS = 100L
        const val LIMIT_MS = 60_000L
        const val COMMENTARY = "session.commentary.append"
        const val THINKING = "session.thinking.append"
        const val CLOSE = "session.close"
        const val REQUEST = "Покажи, как выглядит снежный барс"
        const val ANSWER = "Снежный барс — крупная кошка с густым пятнистым мехом."
        const val BRIEF = "A snow leopard"
        /** A plan reply for a thing that the kit cannot give. */
        const val OUTSIDE = "outside(\"game\")"
        val LOOPBACK = Endpoints { "http://127.0.0.1:4000" }
    }
}

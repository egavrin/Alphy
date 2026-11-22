package com.alpha.assistant.ui

import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.SceneAction
import com.alpha.assistant.tools.SceneStep
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.agent.AppFileNames
import com.alpha.assistant.voice.LIVE_VOICED_RMS
import com.alpha.assistant.voice.LiveConnection
import com.alpha.assistant.voice.LiveListener
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveProtocol
import com.alpha.assistant.voice.LiveTransport
import com.alpha.assistant.voice.rms
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One point of the clock of a voice talk. [detail] is a name (a stage, a kind, a phase detail), never a text of the talk. */
internal data class DialogueMark(val name: String, val detail: String?, val atNanos: Long)

/**
 * The clock of a voice talk in a test (plan D, Dd2). It wraps the voice transport and the chat provider and watches the shell model,
 * so a scripted run and a paid run give the same marks:
 * - `speech_end`: the planned end of a recorded request ([speechEnd], from `WavFeeder.say`).
 * - `delegation`, `commentary`: a hand-off of the voice model, and a commentary that the app sends it, with its class ([lineOf]).
 * - `full_wait`: the commentary is the waiting line of the full generation ([LiveVoiceBridge.FULL_WAIT]), so the full generation starts.
 * - `voice_first`: the first voiced output audio after the end of speech. `voice_out`: the first voiced output audio after a commentary.
 * - `call_start`, `call_end`: a chat call, with its stage or `routing`. `kind`: the kind of a routing decision.
 * - each trace phase of a generation, and `screen_visible`: the stage shows a new app (`new`) or a new revision (`revision`).
 * - `state_commit`: the first state commit of the stage app, with no new revision, after a hand-off.
 * - `voice_phase`: the phase of the voice mode changed, with the new phase, such as `SPEAKING`.
 * - `thinking`: the app sends a thinking text to the voice, such as a sentence of the narrator of a walk.
 * - `stage_step`: the step of the stage app changed, with the step and the count, and who set it ([stage]).
 */
internal class DialogueRecorder(private val clock: () -> Long = SystemClock::elapsedRealtimeNanos) : Closeable {
    private val marks = CopyOnWriteArrayList<DialogueMark>()
    @Volatile private var awaitFirstVoice = false
    @Volatile private var awaitAnswerVoice = false
    @Volatile private var awaitStateCommit = false
    private var poll: java.util.concurrent.ScheduledExecutorService? = null

    private fun mark(name: String, detail: String? = null, at: Long = clock()) { marks += DialogueMark(name, detail, at) }

    fun transport(inner: LiveTransport): LiveTransport = LiveTransport { url, key, listener ->
        val connection = inner.connect(url, key, object : LiveListener {
            override fun onText(text: String) { received(text); listener.onText(text) }
            override fun onFailure(status: Int?, message: String) = listener.onFailure(status, message)
            override fun onClosed() = listener.onClosed()
        })
        object : LiveConnection {
            override fun send(text: String): Boolean {
                if ("\"session.commentary.append\"" in text.take(60)) {
                    awaitAnswerVoice = true
                    val content = runCatching { JSONObject(text).optString("content") }.getOrDefault("")
                    mark("commentary", lineOf(content))
                    if (content == LiveVoiceBridge.FULL_WAIT) mark("full_wait")
                }
                if ("\"session.thinking.append\"" in text.take(60)) mark(THINKING)
                return connection.send(text)
            }
            override fun close() = connection.close()
        }
    }

    private fun received(text: String) {
        val event = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (event.optString("type")) {
            "session.delegation.created" -> { awaitStateCommit = true; mark("delegation") }
            "session.output_audio.delta" -> {
                if (!awaitFirstVoice && !awaitAnswerVoice) return
                val bytes = ByteBuffer.wrap(Base64.decode(event.optString("delta"), Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                if (rms(ShortArray(bytes.remaining()).also { bytes.get(it) }) <= LIVE_VOICED_RMS) return
                val now = clock()
                // The end of speech is a planned time, so a voiced chunk before it is the tail of an earlier reply.
                val end = marks.lastOrNull { it.name == "speech_end" }?.atNanos ?: Long.MAX_VALUE
                if (awaitFirstVoice && now > end) { awaitFirstVoice = false; mark("voice_first", at = now) }
                if (awaitAnswerVoice) { awaitAnswerVoice = false; mark("voice_out", at = now) }
            }
        }
    }

    fun chat(inner: ChatProvider): ChatProvider = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            val stage = request.generationStage?.name ?: if (request.chatDecision) "routing" else "chat"
            mark("call_start", stage)
            try {
                inner.respond(key, request, session) { event ->
                    if (stage == "routing" && event is CloudEvent.RoundComplete) mark("kind", kindOf(event.text))
                    emit(event)
                }
            } finally {
                mark("call_end", stage)
            }
        }
    }

    fun watch(model: ShellViewModel) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            // An observer of the test shell keeps its events.
            val earlier = model.tools.generationTraceObserver
            model.tools.generationTraceObserver = { event ->
                earlier?.invoke(event)
                if (event is ToolGenerationTraceEvent.Phase) phase(event)
            }
        }
        var shown: Triple<String, Int, Int>? = null
        var phase: String? = null
        // The shown screen of the stage app, read again only when the app, its revision, or its state changed.
        var read: Triple<String, Int, Int>? = null
        var screen: ShownScreen? = null
        poll = Executors.newSingleThreadScheduledExecutor { Thread(it, "dialogue-clock").apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay({
                var now: Triple<String, Int, Int>? = null
                var voice: String? = null
                var actions: List<SceneAction> = emptyList()
                instrumentation.runOnMainSync {
                    val record = model.stageToolId?.let { id -> model.tools.tools.firstOrNull { it.id == id } }
                    now = record?.let { Triple(it.id, it.revision, it.stateVersion) }
                    if (now != read) { read = now; screen = record?.let { ShownScreen.of(it) } }
                    voice = model.liveVoice.phase.name
                    actions = model.liveActions
                }
                if (voice != phase) { phase = voice; mark(VOICE_PHASE, voice) }
                screen.let { stage(it?.toolId, it?.step, it?.stepControl, actions) }
                val stage = now
                if (stage != null && (stage.first != shown?.first || stage.second != shown?.second))
                    mark("screen_visible", if (stage.first == shown?.first) "revision" else "new")
                // A moving app commits often, so only the first commit after each hand-off is a mark.
                else if (stage != null && stage.third != shown?.third && awaitStateCommit) { awaitStateCommit = false; mark("state_commit") }
                shown = stage
            }, 0, 100, TimeUnit.MILLISECONDS)
        }
    }

    /** The step of the stage app at the last poll, and a change of it whose who the bridge has not recorded yet, with its time and polls. */
    private var stepOf: Pair<String, SceneStep>? = null
    private var moved: Triple<SceneStep, Long, Int>? = null

    /**
     * One poll of the step of the stage app [toolId]. A change of the step of the same app is a `stage_step` mark at its time, with the who
     * of the last action of the bridge ([SceneAction.who]: narrator, voice, or tap). The bridge records that action after the change, so the
     * mark waits for it for at most [WHO_POLLS] polls, and then names `unknown`. [watch] calls it each poll, and a test can call it.
     */
    fun stage(toolId: String?, step: SceneStep?, stepControl: String?, actions: List<SceneAction>) {
        val before = stepOf
        stepOf = if (toolId != null && step != null) toolId to step else null
        if (toolId != null && step != null && before?.first == toolId && before.second.index != step.index) {
            moved?.let { (earlier, at, _) -> mark(STAGE_STEP, "${earlier.index}/${earlier.count} unknown", at) }
            moved = Triple(step, clock(), 0)
        }
        val (pending, at, polls) = moved ?: return
        val who = actions.lastOrNull()?.takeIf { it.control == stepControl && it.value == pending.label }?.who
        if (who == null && polls < WHO_POLLS) { moved = Triple(pending, at, polls + 1); return }
        mark(STAGE_STEP, "${pending.index}/${pending.count} ${who ?: "unknown"}", at)
        moved = null
    }

    /** Marks a trace phase of a generation at its time on the trace clock. [watch] passes each phase, and a test can pass its own. */
    fun phase(event: ToolGenerationTraceEvent.Phase) = mark(event.name, event.detail, event.atNanos)

    fun speechEnd(atNanos: Long) {
        mark("speech_end", at = atNanos)
        awaitFirstVoice = true
        awaitStateCommit = false
    }

    /** The marks in time order. */
    fun marks(): List<DialogueMark> = marks.sortedBy { it.atNanos }

    /**
     * For each request (the marks from one `speech_end` to the next): the times from the end of speech to the hand-off, to the first
     * voiced output, to the first voice after a commentary, to a new screen, and to the first state commit (the changed stage of a
     * press), the routing kind, the chat calls by stage, and the
     * last plan outcome, such as `fallback:host_check:javascript_busy`, which tells why a screen did not come. Also: the time of each
     * voice after a commentary, the class of each commentary, whether the voice spoke after the last one, whether the full generation
     * ran (its waiting line came, or an `AGENT` call), whether the voice spoke after the waiting line and before the stage changed
     * (`waitingLine`), and whether the stage app committed a state after the hand-off and before a new screen or revision.
     */
    fun report(): JSONObject {
        val all = marks()
        val ends = all.filter { it.name == "speech_end" }.map { it.atNanos }
        val requests = ends.mapIndexed { i, end ->
            val window = all.filter { it.atNanos >= end && it.atNanos < ends.getOrElse(i + 1) { Long.MAX_VALUE } }
            fun ms(mark: DialogueMark?) = mark?.let { (it.atNanos - end) / 1_000_000 }
            fun after(name: String) = ms(window.firstOrNull { it.name == name })
            val lines = window.filter { it.name == "commentary" }
            val voices = window.filter { it.name == "voice_out" }
            val screen = window.firstOrNull { it.name == "screen_visible" }?.atNanos ?: Long.MAX_VALUE
            val wait = window.firstOrNull { it.name == "full_wait" }?.atNanos
            val previews = window.filter { it.name == STAGE_PREVIEW }
            val controls = after(CONTROLS_ON)
            JSONObject().put("speechEndToDelegationMs", after("delegation")).put("speechEndToFirstVoiceMs", after("voice_first"))
                .put("speechEndToAnswerVoiceMs", after("voice_out")).put("speechEndToScreenMs", after("screen_visible"))
                .put("speechEndToStateCommitMs", after("state_commit"))
                .put("kind", window.firstOrNull { it.name == "kind" }?.detail).put("calls", calls(window))
                .put("planOutcome", window.lastOrNull { it.name == AppFileNames.PLAN_OUTCOME }?.detail)
                .put("voiceOutMs", JSONArray(voices.map { (it.atNanos - end) / 1_000_000 })).put("lines", JSONArray(lines.map { it.detail }))
                .put("spokeAfterLastLine", lines.lastOrNull()?.let { line -> voices.any { it.atNanos > line.atNanos } } ?: false)
                // A full generation whose plan step builds the app makes no AGENT call, so its waiting line is the sign.
                .put("fullGeneration", wait != null || window.any { it.name == "call_start" && it.detail == "AGENT" })
                .put("waitingLine", wait != null && voices.any { it.atNanos > wait && it.atNanos < screen })
                .put("stateCommit", window.any { it.name == "state_commit" && it.atNanos < screen })
                // The marks of a stage preview (plan of 2026-11-21, decision 11): the frame, the first part, the complete picture, and
                // controls on, from the end of speech, and the counts of preview frames and of previews that went away.
                .put("first_line_visible", ms(previews.firstOrNull()))
                .put("first_part_visible", ms(previews.firstOrNull { it.detail in setOf("parts", "card", "whole") }))
                .put("picture_complete", ms(previews.firstOrNull { it.detail == "card" || it.detail == "whole" }) ?: controls)
                .put("controls_on", controls).put("preview_updates", previews.size).put("previews_gone", window.count { it.name == STAGE_PREVIEW_GONE })
                // The UX study of 2026-11-21: each commentary to the voice (in the order of `lines`), and the last end of the voice phase
                // SPEAKING, the end of the spoken answer.
                .put("speechEndToCommentaryMs", ms(lines.firstOrNull())).put("commentaryMs", JSONArray(lines.map { (it.atNanos - end) / 1_000_000 }))
                .put("speechEndToVoiceEndMs", ms(window.filter { it.name == VOICE_PHASE }.zipWithNext()
                    .lastOrNull { (from, to) -> from.detail == SPEAKING && to.detail != SPEAKING }?.second))
                // The walk fix: each thinking text to the voice, and each move of the step of the stage app with who set it.
                .put("thinkingMs", JSONArray(window.filter { it.name == THINKING }.map { ms(it) }))
                .put("stepMoves", JSONArray(window.filter { it.name == STAGE_STEP }.map { move ->
                    val (step, who) = checkNotNull(move.detail).split(" ")
                    JSONObject().put("ms", ms(move)).put("step", step).put("who", who)
                }))
        }
        return JSONObject().put("requests", JSONArray(requests)).put("calls", calls(all))
            .put("chatCalls", all.count { it.name == "call_start" })
    }

    private fun calls(marks: List<DialogueMark>) = JSONObject().apply {
        marks.filter { it.name == "call_start" }.groupingBy { it.detail }.eachCount().forEach { (stage, count) -> put(stage.toString(), count) }
    }

    override fun close() { poll?.shutdownNow() }

    internal companion object {
        /** The trace phases of a stage preview that the production code writes (plan of 2026-11-21, decision 11). */
        const val STAGE_PREVIEW = "stage_preview"
        const val STAGE_PREVIEW_GONE = "stage_preview_gone"
        const val CONTROLS_ON = "controls_on"
        const val VOICE_PHASE = "voice_phase"
        const val THINKING = "thinking"
        const val STAGE_STEP = "stage_step"
        /** The polls (100 ms each) that a step move waits for the action of the bridge that names its who. */
        const val WHO_POLLS = 10
        val SPEAKING = LivePhase.SPEAKING.name

        /**
         * The class of a commentary of the app, from the mark that the bridge puts before a text of Alphy: `answer` (a reply), `question`
         * (a reply that asks back), `progress` (an app is made), or `host` (any other line of the app with no mark, such as a failure or a
         * wait).
         */
        fun lineOf(content: String) = when {
            content.startsWith(LiveVoiceBridge.MINI_APP_STARTED) -> "progress"
            !content.startsWith(LiveProtocol.MARK) -> "host"
            content.trimEnd().endsWith("?") -> "question"
            else -> "answer"
        }

        /** A routing kind is a schema name. Any other text, such as a decision with no JSON, is `other`. */
        private fun kindOf(decision: String) = runCatching { JSONObject(decision).optString("kind") }.getOrNull()
            ?.takeIf { it.matches(Regex("[a-z_]{1,24}")) } ?: "other"
    }
}

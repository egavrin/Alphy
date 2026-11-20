package com.alpha.assistant.ui

import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.agent.AppFileNames
import com.alpha.assistant.voice.LIVE_VOICED_RMS
import com.alpha.assistant.voice.LiveConnection
import com.alpha.assistant.voice.LiveListener
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
            model.tools.generationTraceObserver = { event ->
                if (event is ToolGenerationTraceEvent.Phase) mark(event.name, event.detail, event.atNanos)
            }
        }
        var shown: Triple<String, Int, Int>? = null
        poll = Executors.newSingleThreadScheduledExecutor { Thread(it, "dialogue-clock").apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay({
                var now: Triple<String, Int, Int>? = null
                instrumentation.runOnMainSync {
                    now = model.stageToolId?.let { id -> model.tools.tools.firstOrNull { it.id == id }?.let { Triple(id, it.revision, it.stateVersion) } }
                }
                val stage = now
                if (stage != null && (stage.first != shown?.first || stage.second != shown?.second))
                    mark("screen_visible", if (stage.first == shown?.first) "revision" else "new")
                // A moving app commits often, so only the first commit after each hand-off is a mark.
                else if (stage != null && stage.third != shown?.third && awaitStateCommit) { awaitStateCommit = false; mark("state_commit") }
                shown = stage
            }, 0, 100, TimeUnit.MILLISECONDS)
        }
    }

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
            fun after(name: String) = window.firstOrNull { it.name == name }?.let { (it.atNanos - end) / 1_000_000 }
            val lines = window.filter { it.name == "commentary" }
            val voices = window.filter { it.name == "voice_out" }
            val screen = window.firstOrNull { it.name == "screen_visible" }?.atNanos ?: Long.MAX_VALUE
            val wait = window.firstOrNull { it.name == "full_wait" }?.atNanos
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
        }
        return JSONObject().put("requests", JSONArray(requests)).put("calls", calls(all))
            .put("chatCalls", all.count { it.name == "call_start" })
    }

    private fun calls(marks: List<DialogueMark>) = JSONObject().apply {
        marks.filter { it.name == "call_start" }.groupingBy { it.detail }.eachCount().forEach { (stage, count) -> put(stage.toString(), count) }
    }

    override fun close() { poll?.shutdownNow() }

    internal companion object {
        /**
         * The class of a commentary of the app, from the prefixes that the bridge puts before a reply: `answer` (a reply), `question`
         * (a reply that asks back), `progress` (an app is made), or `host` (any other line of the app, such as a failure or a wait).
         */
        fun lineOf(content: String) = when {
            content.startsWith(LiveVoiceBridge.MINI_APP_STARTED) -> "progress"
            !content.startsWith(LiveVoiceBridge.ANSWER) && !content.startsWith(LiveVoiceBridge.MINI_APP_READY) -> "host"
            content.trimEnd().endsWith("?") -> "question"
            else -> "answer"
        }

        /** A routing kind is a schema name. Any other text, such as a decision with no JSON, is `other`. */
        private fun kindOf(decision: String) = runCatching { JSONObject(decision).optString("kind") }.getOrNull()
            ?.takeIf { it.matches(Regex("[a-z_]{1,24}")) } ?: "other"
    }
}

package com.alpha.assistant.voice

import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.OPENAI_LIVE_MODEL
import com.alpha.assistant.tools.liveCheck
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Throwaway probe of 2026-11-22: can the stage picture follow the spoken steps of GPT-Live? Each paid method is one short session on
 * the real socket. The test plays Alphy itself (no chat call) and records each event to `events.jsonl` next to `report.json`. The
 * analysis runs offline on that log. The probe asserts only that it ran and that the log holds no key.
 */
class LiveStepSyncProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Every experiment, the cap, and the exit without a delegation on the scripted server, so a paid session is not lost to a bug. */
    @Test fun probeRunsOnAScriptedServer() {
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "step-probe-rehearsal").apply { deleteRecursively(); mkdirs() }
        val account = AiAccount(AiProvider.OPENAI, "alpha-test-only")
        val local = Endpoints { "http://127.0.0.1:4000" }
        val results = ArrayList<String>()
        for (experiment in ProbeExperiment.entries) {
            val log = File(directory, "${experiment.name}.jsonl")
            results += experiment.name + ": " + StepProbe(ScriptedLiveServer(listOf(HEART_TEXT, TAP_TEXT)), local, account, experiment).run(log)
            val text = log.readText()
            assertFalse("${log.name} holds the key.", "alpha-test-only" in text)
            assertTrue(text.takeLast(2000), "\"-> session.close\"" in text && "\"session.closed\"" in text)
        }
        val noDelegation = File(directory, "no-delegation.jsonl")
        results += "no delegation: " + StepProbe(ScriptedLiveServer(listOf(HEART_TEXT), undelegated = setOf(0)), local, account,
            ProbeExperiment.M2_PRODUCT).run(noDelegation)
        assertTrue(noDelegation.readText().takeLast(2000), "no delegation" in noDelegation.readText())
        val capped = File(directory, "cap.jsonl")
        results += "cap: " + StepProbe(ScriptedLiveServer(listOf(HEART_TEXT)), local, account, ProbeExperiment.M1_COMMENTARY, capMs = 12_000).run(capped)
        assertTrue(capped.readText().takeLast(2000), "cap reached" in capped.readText())
        File(directory, "results.txt").writeText(results.joinToString("\n"))
    }

    @Test fun m1Commentary() = paid("m1Commentary", ProbeExperiment.M1_COMMENTARY)
    @Test fun m1Thinking() = paid("m1Thinking", ProbeExperiment.M1_THINKING)
    @Test fun m1Early() = paid("m1Early", ProbeExperiment.M1_EARLY)
    @Test fun m2Product() = paid("m2Product", ProbeExperiment.M2_PRODUCT)
    @Test fun m2Exact() = paid("m2Exact", ProbeExperiment.M2_EXACT)
    @Test fun m1CommentaryExact() = paid("m1CommentaryExact", ProbeExperiment.M1_COMMENTARY_EXACT)
    @Test fun tapAndSilentFact() = paid("tapAndSilentFact", ProbeExperiment.TAP_AND_FACT)

    private fun paid(method: String, experiment: ProbeExperiment) = liveCheck("LiveStepSyncProbeTest#$method",
        missing = { account -> "Only OpenAI has GPT-Live.".takeIf { account.provider != AiProvider.OPENAI } },
        model = { OPENAI_LIVE_MODEL }) { account, _ ->
        val runId = checkNotNull(InstrumentationRegistry.getArguments().getString("liveRunId"))
        val log = File(checkNotNull(context.getExternalFilesDir(null)), "live-check/$runId/events.jsonl")
        val result = StepProbe(OkHttpLiveTransport(), Endpoints.PRODUCTION, account, experiment).run(log)
        // assertFalse keeps the key out of the failure text.
        assertFalse("The log holds the key.", account.key in log.readText())
        result
    }
}

private const val HEART_TEXT = "Покажи, как кровь течёт по сердцу."
private const val TAP_TEXT = "Что сейчас на экране?"

/** The six steps of the explanation, one stage each, with no commas, so the pauses sit at sentence ends. */
private val STEPS = listOf(
    "Кровь из тела входит в правое предсердие по полым венам.",
    "Из правого предсердия кровь проходит в правый желудочек.",
    "Правый желудочек выталкивает кровь в лёгочную артерию.",
    "В лёгких кровь отдаёт углекислый газ и берёт кислород.",
    "Из лёгких кровь возвращается в левое предсердие и левый желудочек.",
    "Левый желудочек выталкивает кровь в аорту и ко всему телу.",
)
private const val PRODUCT_MARK = "Ответ Alphy: "
private const val EXACT_MARK = "Дословно: "
private const val EXACT_LINE = "Если текст Alphy начинается с метки «Дословно:», произнеси только текст после метки, слово в слово: " +
    "без вступления, без своих слов, без вопросов и без предложений."
private const val TAP_STEP = "Теперь кровь идёт в правый желудочек."
private const val SILENT_FACT = "Не произноси это. Запомни: на экране выбрана кнопка «Груз»."

/** The product instructions, with [EXACT_LINE] after the retell line when [exact]. */
private fun probeInstructions(exact: Boolean): String {
    if (!exact) return LiveProtocol.INSTRUCTIONS
    val retell = "Ответ Alphy перескажи своими словами."
    val lines = LiveProtocol.INSTRUCTIONS.lines()
    require(retell in lines) { "No retell line." }
    return lines.flatMap { if (it == retell) listOf(it, EXACT_LINE) else listOf(it) }.joinToString("\n")
}

private enum class Send { COMMENTARY, THINKING }

/** One paid session each. [capMs] is the hard limit of session time from `session.start`. */
private enum class ProbeExperiment(val exact: Boolean, val capMs: Long, val script: StepProbe.() -> Unit) {
    /** Q1 (a), Q2: each step is a commentary on the request's delegation, sent at the end of the last reply. */
    M1_COMMENTARY(false, 50_000, { sequential(Send.COMMENTARY) }),
    // After S1 the product text made step 1 a 19 s retell of the whole path, so the other M1 runs use the exact line.
    /** Q1 (b): step 1 is a commentary, steps 2 to 6 are thinking texts without a delegation. */
    M1_THINKING(true, 45_000, { sequential(Send.THINKING) }),
    /** Q1 (c), Q5: three pairs; the second step of a pair goes out while the first one plays. */
    M1_EARLY(true, 40_000, { early() }),
    /** Q3, Q2: all six steps in one commentary. */
    M2_PRODUCT(false, 45_000, { oneAnswer() }),
    /** Q3 run 2, Q2: all six steps in one commentary, marked for exact speech. */
    M2_EXACT(true, 40_000, { oneAnswer() }),
    /** Q2: as [M1_COMMENTARY], marked for exact speech. */
    M1_COMMENTARY_EXACT(true, 45_000, { sequential(Send.COMMENTARY) }),
    /** Q4, Q6: no request; a tap sentence as thinking, then a silent fact and a spoken question about the screen. */
    TAP_AND_FACT(false, 30_000, { tapAndFact() }),
}

private class CapReached : RuntimeException()

/** One output reply by rulings R24 and R27: it starts at a voiced chunk and ends after 800 ms of silent samples. */
private class ProbeReply(val start: Long) { @Volatile var end = -1L }

/**
 * One probe session on [transport]. It sends its own `session.start` with the instructions of [experiment], feeds 20 ms input blocks
 * from `session.started` to the close (silence or a fixture), and records every sent and received event with the client ms (`t`) and
 * the input audio ms (`in`). An audio chunk is recorded as its sample count, its stream position, and its RMS, never as audio.
 */
private class StepProbe(private val transport: LiveTransport, private val endpoints: Endpoints, private val account: AiAccount,
    private val experiment: ProbeExperiment, private val capMs: Long = experiment.capMs) {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val begin = SystemClock.elapsedRealtime()
    private val lines = CopyOnWriteArrayList<JSONObject>()
    private val audio = FakeLiveAudio()
    private val feeder = WavFeeder(audio)
    private val instructions = probeInstructions(experiment.exact)
    @Volatile private var connection: LiveConnection? = null
    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile private var startAt = -1L
    @Volatile private var usageSeconds: Any? = null
    private val delegations = CopyOnWriteArrayList<Pair<Long, String>>()
    private val replies = CopyOnWriteArrayList<ProbeReply>()
    private val streamLock = Any()
    private var streamSamples = 0L
    private var silentSamples = 0L
    private var open: ProbeReply? = null
    @Volatile private var lastVoiced = -1L

    fun now() = SystemClock.elapsedRealtime() - begin

    /** Runs the session and writes [log]. Returns a one-line summary for the report. */
    fun run(log: File): String {
        var stop = "done"
        try {
            check(audio.open({ block -> if (started && !closed) connection?.send(LiveProtocol.audio(block)) }, {}))
            val start = JSONObject(LiveProtocol.start(emptyList())).apply { getJSONObject("session").put("instructions", instructions) }
            val socket = transport.connect(liveUrl(endpoints), account.key, listener())
            connection = socket
            startAt = now()
            record("-> session.start") { put("instructions", instructions) }
            socket.send(start.toString())
            check(waitFor("session.started", 12_000) { started }) { "no session.started" }
            feeder.start()
            record("feed start")
            pause(1_000)
            experiment.script(this)
        } catch (_: CapReached) {
            stop = "cap reached"; note("cap reached")
        } catch (failure: Throwable) {
            stop = "stopped: ${failure.javaClass.simpleName}"; note("script stopped: ${failure.javaClass.simpleName} ${failure.message?.take(80)}")
        } finally {
            if (connection != null && !closed) {
                record("-> session.close")
                connection?.send(LiveProtocol.close())
                waitFor("session.closed", 4_000, capped = false) { closed }
            }
            connection?.close()
            feeder.stop()
            record("end") { put("wallMs", if (startAt < 0) 0 else now() - startAt); put("usageSeconds", usageSeconds ?: JSONObject.NULL) }
            log.parentFile?.mkdirs()
            log.writeText(lines.sortedBy { it.getLong("t") }.joinToString("\n") { it.toString() } + "\n")
        }
        return "$stop; ${now() - startAt} ms from session.start; usage seconds $usageSeconds; ${delegations.size} delegation(s); " +
            "${replies.size} stream replies; ${lines.count { it.optString("type") == OUTPUT }} output deltas"
    }

    private fun listener() = object : LiveListener {
        override fun onText(text: String) = received(text)
        override fun onFailure(status: Int?, message: String) { note("socket failure, HTTP status $status"); closed = true }
        override fun onClosed() { note("socket closed"); closed = true }
    }

    private fun received(text: String) {
        val at = now()
        val e = runCatching { JSONObject(text) }.getOrNull() ?: return record("unparsed", at)
        val type = e.optString("type")
        when (type) {
            "session.output_audio.delta" -> chunk(at, e.optString("delta"))
            "session.started" -> { started = true; record(type, at) }
            INPUT, OUTPUT -> record(type, at) { put("text", e.optString("delta")); put("start", e.opt("start_ms")); put("end", e.opt("end_ms")) }
            DELEGATION -> {
                val id = e.optJSONObject("delegation")?.optString("id").orEmpty()
                delegations += at to id
                record(type, at) { put("id", id); put("offset", e.opt("offset_ms")) }
            }
            // Only the type and code of an error: its message can echo a request.
            "error" -> e.optJSONObject("error").let { error ->
                record(type, at) { put("error", "${error?.optString("type")}/${error?.optString("code")}"); put("eventId", error?.optString("event_id")) }
            }
            else -> {
                val seconds = e.optJSONObject("usage")?.opt("seconds")
                if (seconds != null) usageSeconds = seconds
                if (type == "session.closed") closed = true
                record(type, at) {
                    put("keys", JSONArray(e.keys().asSequence().sorted().toList()))
                    e.optString("client_event_id").takeIf { it.isNotEmpty() }?.let { put("id", it) }
                    if (e.has("start_ms")) { put("start", e.opt("start_ms")); put("end", e.opt("end_ms")) }
                    if (seconds != null) put("seconds", seconds)
                    if (type == "session.closed") put("reason", e.optString("reason"))
                }
            }
        }
    }

    /** Records one output chunk and follows the replies by the count of silent samples (ruling R27). */
    private fun chunk(at: Long, base64: String) {
        val buffer = ByteBuffer.wrap(Base64.decode(base64, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(buffer.remaining()).also { buffer.get(it) }
        val level = rms(samples)
        synchronized(streamLock) {
            val pos = streamSamples * 1000 / LIVE_RATE
            streamSamples += samples.size
            if (level > LIVE_VOICED_RMS) {
                lastVoiced = at
                if (open == null) open = ProbeReply(at).also { replies += it }
                silentSamples = 0
            } else open?.let { reply ->
                silentSamples += samples.size
                if (silentSamples >= 800L * LIVE_RATE / 1000) { reply.end = at; open = null }
            }
            record("audio", at) { put("n", samples.size); put("pos", pos); put("rms", Math.round(level * 10_000) / 10_000.0) }
        }
    }

    private fun record(type: String, at: Long = now(), fill: JSONObject.() -> Unit = {}) {
        lines += JSONObject().put("t", at).put("in", feeder.fed * 20).put("type", type).apply(fill)
    }

    fun note(text: String) = record("note") { put("text", text) }

    /** Waits for [condition]. Past the cap it throws [CapReached] unless [capped] is false. */
    fun waitFor(what: String, timeoutMs: Long, capped: Boolean = true, condition: () -> Boolean): Boolean {
        val deadline = now() + timeoutMs
        while (!condition()) {
            if (capped && startAt >= 0 && now() - startAt > capMs) throw CapReached()
            if (capped && closed) error("closed early")
            if (now() > deadline) { if (what.isNotEmpty()) note("no $what within $timeoutMs ms"); return false }
            SystemClock.sleep(10)
        }
        return true
    }

    fun pause(ms: Long) { waitFor("", ms) { false } }

    fun commentary(delegation: String, text: String): Long {
        val id = LiveProtocol.newId()
        val at = now()
        record("-> session.commentary.append", at) { put("id", id); put("delegation", delegation); put("content", text) }
        connection?.send(LiveProtocol.commentary(id, delegation, text))
        return at
    }

    fun thinking(text: String): Long {
        val id = LiveProtocol.newId()
        val at = now()
        record("-> session.thinking.append", at) { put("id", id); put("content", text) }
        connection?.send(LiveProtocol.thinking(id, text))
        return at
    }

    fun send(how: Send, delegation: String, text: String) = if (how == Send.COMMENTARY) commentary(delegation, text) else thinking(text)

    fun step(k: Int) = (if (experiment.exact) EXACT_MARK else PRODUCT_MARK) + STEPS[k]

    fun replyAfter(t: Long) = replies.firstOrNull { it.start >= t }

    /** The first reply that starts after [t], once it has started, or null. */
    fun replyStart(t: Long, timeoutMs: Long = 8_000): ProbeReply? =
        if (waitFor("reply after $t", timeoutMs) { replyAfter(t) != null }) replyAfter(t) else null

    /** The first reply that starts after [t], once it has ended, or null. */
    fun replyEnd(t: Long, startMs: Long = 8_000, endMs: Long = 20_000): ProbeReply? {
        val reply = replyStart(t, startMs) ?: return null
        return reply.takeIf { waitFor("end of reply ${reply.start}", endMs) { reply.end >= 0 } }
    }

    /** No reply is open, and no voiced chunk came for [quietMs]. */
    fun quiet(quietMs: Long, timeoutMs: Long) = waitFor("quiet $quietMs ms", timeoutMs) {
        synchronized(streamLock) { open == null } && now() - lastVoiced >= quietMs
    }

    /** Fixture [name] goes out in real time. Returns the client ms of its end of speech. */
    fun say(name: String): Long {
        val samples = wavSamples(assets.open("voice/$name.wav").use { it.readBytes() })
        val end = feeder.say(samples) / 1_000_000 - begin
        record("say") { put("fixture", name); put("endOfSpeech", end) }
        waitFor("$name sent", 10_000) { feeder.idle }
        return end
    }

    /** The heart request, its delegation, and the end of the waiting line after it. Null closes the session: no delegation. */
    fun request(): String? {
        val end = say("ux-heart-3")
        if (!waitFor("delegation", maxOf(0, end + 10_000 - now())) { delegations.any { it.first >= end - 2_000 } }) {
            note("no delegation"); return null
        }
        val (at, id) = delegations.first { it.first >= end - 2_000 }
        note("delegation $id at $at")
        // The waiting line of GPT-Live: it starts after the end of speech, and step 1 waits for its end.
        replyStart(end, 3_000)?.let { reply -> waitFor("end of the waiting line", 15_000) { reply.end >= 0 } }
        quiet(800, 5_000)
        return id
    }

    /** M1: each step goes out at the end of the last reply. */
    fun sequential(rest: Send) {
        val delegation = request() ?: return
        for (k in STEPS.indices) {
            val sent = send(if (k == 0) Send.COMMENTARY else rest, delegation, step(k))
            if (replyEnd(sent) == null) note("step ${k + 1}: no ended reply")
        }
        quiet(1_500, 5_000)
    }

    /** Q1 (c) and Q5: (1, 2) and (3, 4) as commentary 300 ms after the first voice, (5, 6) with a thinking 1,500 ms after it. */
    fun early() {
        val delegation = request() ?: return
        for ((first, how, delayMs) in listOf(Triple(0, Send.COMMENTARY, 300L), Triple(2, Send.COMMENTARY, 300L), Triple(4, Send.THINKING, 1_500L))) {
            val sent = commentary(delegation, step(first))
            val reply = replyStart(sent)
            if (reply != null) pause(maxOf(0, reply.start + delayMs - now()))
            send(how, delegation, step(first + 1))
            quiet(2_500, 25_000)
        }
    }

    /** M2: all six steps in one commentary. */
    fun oneAnswer() {
        val delegation = request() ?: return
        val sent = commentary(delegation, (if (experiment.exact) EXACT_MARK else PRODUCT_MARK) + STEPS.joinToString(" "))
        replyStart(sent)
        quiet(2_500, 40_000)
    }

    /** Q4 then Q6, with no request and no delegation. */
    fun tapAndFact() {
        val tap = thinking(PRODUCT_MARK + TAP_STEP)
        replyEnd(tap, 8_000, 12_000)
        quiet(2_000, 6_000)
        thinking(SILENT_FACT)
        pause(5_000)
        val end = say("ux-tap-4")
        waitFor("delegation or reply after the question", maxOf(0, end + 10_000 - now())) {
            delegations.any { it.first >= end - 2_000 } || replyAfter(end)?.let { it.end >= 0 } == true
        }
        quiet(1_500, 10_000)
    }
}

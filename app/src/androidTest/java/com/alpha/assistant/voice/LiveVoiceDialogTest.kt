package com.alpha.assistant.voice

import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.OPENAI_LIVE_MODEL
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.liveCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The live check of spec 6.3: one GPT-Live session with the real key runs a Russian request with a 25 s late commentary, a second
 * commentary, a thinking append, an interruption, and a spoken «да» after a proposal. The events go to `events.log` next to the
 * report of `run.py live-check`. [dialogScriptPassesOnAScriptedServer] runs the same script without cost, so a script fault does not
 * spend a paid run.
 */
class LiveVoiceDialogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun dialogScriptPassesOnAScriptedServer() {
        val log = File(context.cacheDir, "live-dialog-rehearsal.log")
        val outcome = LiveDialog(ScriptedLiveServer(), Endpoints { "http://127.0.0.1:4000" }, AiAccount(AiProvider.OPENAI, "alpha-test-only"), 200)
            .run(log)
        assertTrue(outcome.summary, outcome.failures.isEmpty())
        val text = log.readText()
        assertFalse("The event log holds the key.", "alpha-test-only" in text)
        assertTrue(text, "-> session.thinking.append" in text && "<- session.delegation.created" in text)
    }

    @Test fun russianDialogWithHandOffs() = liveCheck("LiveVoiceDialogTest#russianDialogWithHandOffs",
        missing = { account -> "Only OpenAI has GPT-Live.".takeIf { account.provider != AiProvider.OPENAI } },
        model = { OPENAI_LIVE_MODEL }) { account, _ ->
        val runId = checkNotNull(InstrumentationRegistry.getArguments().getString("liveRunId"))
        val log = File(checkNotNull(context.getExternalFilesDir(null)), "live-check/$runId/events.log")
        val outcome = LiveDialog(OkHttpLiveTransport(), Endpoints.PRODUCTION, account, 25_000).run(log)
        // assertFalse keeps the key out of the failure text.
        assertFalse("The event log holds the key.", account.key in log.readText())
        assertTrue(outcome.summary, outcome.failures.isEmpty())
        outcome.summary
    }
}

private class Outcome(val failures: List<String>, val summary: String)

/** One server event, or one sent append. Times are milliseconds since the dialog began; start, end, and offset are session ms. */
private data class Seen(val at: Long, val type: String, val text: String = "", val startMs: Long? = null, val endMs: Long? = null,
    val id: String? = null)

private const val INPUT = "session.input_transcript.delta"
private const val OUTPUT = "session.output_transcript.delta"
private const val DELEGATION = "session.delegation.created"
private const val APPENDED = "session.commentary.appended"
/** A 20 ms or longer chunk above this RMS is speech, not the silence of the output stream. */
private const val VOICED_RMS = 0.01
private const val QUIET_MS = 1_500L

/**
 * The dialog of spec 6.3 through [LiveVoiceSession] with [FakeLiveAudio] as the capture. A feeder sends a 20 ms block every 20 ms
 * for the whole session: digital silence, or the queued fixture. Every step runs with a bounded wait, and the checks are judged at
 * the end, so one paid run observes every assumption. The log holds event types, ids, timeline values, and transcript text. It
 * never holds the raw text of an event, an error message, or the key.
 */
private class LiveDialog(transport: LiveTransport, endpoints: Endpoints, account: AiAccount, private val commentaryDelayMs: Long) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val begin = SystemClock.elapsedRealtime()
    private val lines = ArrayList<Pair<Long, String>>()
    private val events = CopyOnWriteArrayList<Seen>()
    private val sends = CopyOnWriteArrayList<Seen>()
    /** The arrival times of voiced output audio chunks. */
    private val voiced = CopyOnWriteArrayList<Long>()
    private val audio = FakeLiveAudio()
    private val host = Host()
    private val session = LiveVoiceSession(instrumentation.targetContext, scope, Recorder(transport), { audio }, { account }, endpoints)
    private val blocks = LinkedBlockingQueue<ShortArray>()
    private val feeder = Thread(::feed, "live-dialog-capture")
    @Volatile private var feeding = true
    /** The blocks that the feeder has given to the session: session audio ms / 20. */
    @Volatile private var fed = 0L
    @Volatile private var feedStart = 0L
    private val checks = ArrayList<String>()
    private val failures = ArrayList<String>()
    /** (fixture, session audio ms of its speech onset, dialog ms of the queue). */
    private val said = ArrayList<Triple<String, Long, Long>>()
    private val audioLock = Any()
    private var audioRun: AudioRun? = null
    private var lastAudioAt = -1L
    private var audioChunks = 0
    private var audioSamples = 0L
    private var maxAudioGap = 0L
    private var audioGaps = 0

    private class AudioRun(val voiced: Boolean, val from: Long) { var to = from; var chunks = 0; var samples = 0L; var maxGap = 0L }

    private inner class Host : LiveVoiceHost {
        @Volatile var history = emptyList<Message>()
        @Volatile var ended: String? = null
        val delegations = LinkedBlockingQueue<Pair<String, String>>()
        val outputEnds = CopyOnWriteArrayList<Pair<Long, Long>>()
        override fun history() = history
        override fun delegation(id: String, request: String) { note("host delegation $id request «$request»"); delegations += id to request }
        override fun turn(turn: UserTurn) = note("host turn «${turn.text}» ${turn.startMs}..${turn.endMs} overlapped=${turn.overlappedPlayback}")
        // A hand-off is open for the whole check, so the 60 s silence rule never ends it.
        override fun handOffRunning() = true
        override fun appended(eventId: String) = note("host appended $eventId")
        override fun outputEnded(endMs: Long) { note("host outputEnded endMs=$endMs"); outputEnds += now() to endMs }
        override fun ended(status: String, settingsRequired: Boolean) { note("host ended «$status» settings=$settingsRequired"); ended = status }
    }

    /** Logs each event and send before the session gets it. */
    private inner class Recorder(private val inner: LiveTransport) : LiveTransport {
        override fun connect(url: String, key: String, listener: LiveListener): LiveConnection {
            note("connect $url")
            val connection = inner.connect(url, key, object : LiveListener {
                override fun onText(text: String) { received(text); listener.onText(text) }
                override fun onFailure(status: Int?, message: String) { note("socket failure, HTTP status $status"); listener.onFailure(status, message) }
                override fun onClosed() { note("socket closed"); listener.onClosed() }
            })
            return object : LiveConnection {
                override fun send(text: String): Boolean { sent(text); return connection.send(text) }
                override fun close() = connection.close()
            }
        }
    }

    fun run(log: File): Outcome {
        try {
            script()
        } catch (failure: Throwable) {
            failures += "The script stopped: ${failure.javaClass.simpleName}"
            note("script stopped: ${failure.javaClass.simpleName}")
        } finally {
            main { session.stop() }
            waitFor("session.closed", 5_000, whileActive = false) { events.any { it.type == "session.closed" } }
            feeding = false
            feeder.join(1_000)
            scope.cancel()
            synchronized(audioLock) { audioRun?.let(::logRun); audioRun = null }
        }
        val summary = summary()
        log.writeText(synchronized(lines) { lines.sortedBy { it.first }.joinToString("\n") { "%7d %s".format(it.first, it.second) } } +
            "\n\n" + summary + "\n")
        return Outcome(failures.toList(), summary)
    }

    private fun script() {
        // Ruling R7: the item format of the preloaded history is unverified, so a rejection is recorded and the check goes on without it.
        host.history = HISTORY
        val withHistory = start()
        expect("history preload accepted (ruling R7)", withHistory, "${HISTORY.size} items; errors ${errors(0)}")
        if (!withHistory) {
            host.history = emptyList(); host.ended = null
            if (!start()) { failures += "No session.started without history."; return }
        }
        feedStart = now()
        feeder.start()
        SystemClock.sleep(1_000)

        // Step 1: the request, its delegation, and no claim before the commentary of 25 s later.
        val asked = now()
        say("russian")
        val first = host.delegations.poll(15, TimeUnit.SECONDS)
        expect("delegation for the request", first != null, "request «${first?.second}»")
        if (first == null) return
        val (d1, request) = first
        val created = events.firstOrNull { it.type == DELEGATION && it.id == d1 }?.at ?: now()
        val early = events.filter { it.type == INPUT && it.at in asked..created }
        info("input transcript before the delegation (spec 5.5.2)", "${early.size} deltas «${text(early)}» before delegation.created at $created; " +
            "deltas after it: ${events.count { it.type == INPUT && it.at > created && it.at < created + 3_000 }}; host request «$request»")
        expect("request text complete at the hand-off (spec 5.5.2)", "таймер" in request.lowercase(), "«$request»")
        SystemClock.sleep(maxOf(0, created + commentaryDelayMs - now()))
        val c1 = comment(d1, ON_SCREEN)
        val claims = text(outputs(asked, c1.first)).lowercase()
        expect("no claim before the commentary (anti-goal 1)", CLAIMS.none { it in claims }, "output before it «$claims»")
        expect("a commentary ${commentaryDelayMs / 1000} s after its delegation is spoken (spec 5.5.6)", spoken(c1.first, "commentary 1"),
            "«${text(outputs(c1.first))}»")

        // Step 2: a second commentary for the same delegation.
        val c2 = comment(d1, DONE)
        expect("a second commentary for one delegation is spoken (spec 5.5.6)", spoken(c2.first, "commentary 2"), "«${text(outputs(c2.first))}»")

        // Step 3: quiet context without a delegation.
        val thought = now()
        main { session.thinking(THINKING) }
        SystemClock.sleep(5_000)
        val thinking = sends.lastOrNull { it.type == "session.thinking.append" }?.id
        expect("thinking without a delegation gives no error (spec 5.7.2)", errors(thought).isEmpty() && host.ended == null,
            "event_id $thinking; errors ${errors(thought)}; output after it «${text(outputs(thought))}»")

        // Step 4: the user speaks over a long answer.
        val c3 = comment(d1, LONG_ANSWER)
        val replying = waitFor("the long answer", 20_000) { outputs(c3.first).isNotEmpty() }
        val interrupted = now()
        val onset = say("russian")
        val second = host.delegations.poll(15, TimeUnit.SECONDS)
        val cut = second?.let { d -> events.firstOrNull { it.type == DELEGATION && it.id == d.first }?.at } ?: now()
        waitFor("the interrupting turn", 3_000) { inputs(interrupted).isNotEmpty() }
        val reply = outputs(c3.first, cut)
        val turn = inputs(interrupted)
        val userStart = turn.minOfOrNull { it.startMs ?: 0 }
        val userEnd = turn.maxOfOrNull { it.endMs ?: 0 }
        val speaking = userStart != null && reply.any { (it.endMs ?: 0) >= userStart }
        val stopped = userEnd != null && reply.all { (it.startMs ?: 0) <= userEnd + 1_000 }
        val lastVoice = voiced.lastOrNull { it in interrupted..cut }
        expect("the output transcript stops at an interruption (spec 5.4.5)", replying && speaking && stopped,
            "reply «${text(reply)}» ends at ${reply.maxOfOrNull { it.endMs ?: 0 }}; user turn «${text(turn)}» $userStart..$userEnd " +
                "(fixture onset at session audio $onset); voiced output until ${lastVoice?.minus(interrupted)} ms after the queue; " +
                "second delegation ${second?.first} request «${second?.second}»")

        // Step 5: a proposal on the interruption's delegation, then «да».
        waitFor("quiet before the proposal", 30_000) { now() - lastOutput() >= QUIET_MS }
        val d2 = second?.first ?: d1
        val p = comment(d2, PROPOSAL)
        val proposed = spoken(p.first, "proposal")
        val proposal = outputs(p.first)
        val proposalEnd = proposal.maxOfOrNull { it.endMs ?: 0 }
        // The approval window compares session times, so «да» starts after the proposal's last transcript end on the audio clock.
        if (proposalEnd != null) waitFor("session audio past the proposal", 10_000) { fed * 20 > proposalEnd + 500 }
        val asking = now()
        say("da")
        waitFor("the «да» transcript", 10_000) { inputs(asking).isNotEmpty() }
        SystemClock.sleep(QUIET_MS)
        val yes = inputs(asking)
        val answer = text(yes).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val yesStart = yes.minOfOrNull { it.startMs ?: 0 }
        expect("«да» is transcribed after the proposal output (spec 5.5.10)",
            proposed && answer == "да" && proposalEnd != null && yesStart != null && yesStart > proposalEnd,
            "proposal «${text(proposal)}» ends at $proposalEnd; «да» turn «${text(yes)}» starts at $yesStart")
        info("outputEnded after the proposal (Task 7 window)", host.outputEnds.filter { it.first > p.first }
            .joinToString { "endMs ${it.second} at ${it.first}" }.ifEmpty { "none" } + "; proposal appended " +
            "${events.firstOrNull { it.type == APPENDED && it.id == p.second }?.let { "at ${it.at}, ${it.startMs}..${it.endMs}" } ?: "not seen"}")
        // Rule 7: GPT-Live can open a delegation for the approval turn. Alphy answers it with the rule-5 text.
        val third = host.delegations.poll(5, TimeUnit.SECONDS)
        info("delegation for the «да» turn (spec 5.5.10 rule 7)", third?.let { "${it.first} request «${it.second}»" } ?: "none")
        val started = comment(third?.first ?: d2, STARTED)
        val early2 = text(outputs(asking, started.first)).lowercase()
        info("claim after «да» before «Запускаю.» (spec 5.3.3)", "${STARTS.filter { it in early2 }.ifEmpty { "none" }} in «$early2»")
        spoken(started.first, "start confirmation")
    }

    private fun start(): Boolean {
        main { session.start(host) }
        return waitFor("session.started", 12_000) { main { session.phase } == LivePhase.LISTENING }
    }

    /** Queues one fixture and waits until the feeder has sent it. Returns the session audio ms of its speech onset. */
    private fun say(name: String): Long {
        val samples = wavSamples(instrumentation.context.assets.open("voice/$name.wav").use { it.readBytes() })
        val onset = fed * 20 + maxOf(0, samples.indexOfFirst { abs(it.toInt()) > 1000 }) * 1000L / LIVE_RATE
        for (from in samples.indices step LIVE_BLOCK) {
            blocks += ShortArray(LIVE_BLOCK).also { samples.copyInto(it, 0, from, minOf(from + LIVE_BLOCK, samples.size)) }
        }
        said += Triple(name, onset, now())
        note("say $name: ${samples.size * 1000L / LIVE_RATE} ms, speech onset at session audio $onset ms")
        waitFor("$name sent", 10_000) { blocks.isEmpty() }
        return onset
    }

    private fun feed() {
        val start = System.nanoTime()
        var i = 0L
        while (feeding) {
            audio.capture(blocks.poll() ?: ShortArray(LIVE_BLOCK))
            fed = ++i
            val wait = start + i * 20_000_000L - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }

    private fun comment(delegation: String, text: String): Pair<Long, String> {
        val at = now()
        return at to main { session.commentary(delegation, text) }
    }

    /** Waits for output transcript text after [since], then for [QUIET_MS] without output. Returns whether text came. */
    private fun spoken(since: Long, what: String): Boolean {
        val spoke = waitFor("$what output transcript", 20_000) { outputs(since).isNotEmpty() }
        waitFor("quiet after $what", 30_000) { now() - lastOutput() >= QUIET_MS }
        return spoke
    }

    private fun waitFor(what: String, timeoutMs: Long, whileActive: Boolean = true, condition: () -> Boolean): Boolean {
        val deadline = now() + timeoutMs
        while (!condition()) {
            if (now() > deadline || (whileActive && host.ended != null)) { note("no $what within $timeoutMs ms"); return false }
            SystemClock.sleep(20)
        }
        return true
    }

    private fun received(text: String) {
        val at = now()
        val e = runCatching { JSONObject(text) }.getOrNull() ?: return note("<- unparsed event")
        val type = e.optString("type")
        if (type == "session.output_audio.delta") return audioChunk(at, e.optString("delta"))
        val error = e.optJSONObject("error")
        val seen = when (type) {
            INPUT, OUTPUT -> Seen(at, type, e.optString("delta"), e.long("start_ms"), e.long("end_ms"))
            DELEGATION -> Seen(at, type, startMs = e.long("offset_ms"), id = e.optJSONObject("delegation")?.optString("id"))
            APPENDED -> Seen(at, type, startMs = e.long("start_ms"), endMs = e.long("end_ms"), id = e.optString("client_event_id"))
            // Only the type and code of an error: its message can echo a request.
            "error" -> Seen(at, type, "${error?.optString("type")}/${error?.optString("code")}", id = error?.optString("event_id"))
            else -> Seen(at, type)
        }
        events += seen
        val detail = when (type) {
            INPUT, OUTPUT -> " ${seen.startMs}..${seen.endMs} «${seen.text}»"
            DELEGATION -> " delegation=${seen.id} offset_ms=${seen.startMs}"
            APPENDED -> " client_event_id=${seen.id} ${seen.startMs}..${seen.endMs}"
            "error" -> " error=${seen.text} error.event_id=${seen.id}"
            "session.started" -> " input=${e.optJSONObject("session")?.optJSONArray("input")?.length()}"
            "session.closed" -> " reason=${e.optString("reason")} seconds=${e.optJSONObject("usage")?.opt("seconds")}"
            "session.usage.updated" -> " seconds=${e.optJSONObject("usage")?.opt("seconds")}"
            else -> " keys=${e.keys().asSequence().sorted().toList()}"
        }
        note(at, "<- $type event_id=${e.optString("event_id")}$detail")
    }

    private fun sent(text: String) {
        // The audio blocks are 50 a second. The fixture queue lines stand for them.
        if ("session.input_audio.append" in text.take(60)) return
        val at = now()
        val e = JSONObject(text)
        val type = e.getString("type")
        sends += Seen(at, type, e.optString("content"), id = e.optString("event_id"))
        note(at, "-> $type event_id=${e.optString("event_id")}" + when (type) {
            "session.start" -> " input=${e.getJSONObject("session").getJSONArray("input").length()}"
            "session.commentary.append", "session.thinking.append" -> " delegation=${e.opt("delegation_id")} «${e.optString("content")}»"
            else -> ""
        })
    }

    /** Collapses the output audio into runs of voiced or silent chunks, split at an arrival gap of 300 ms. */
    private fun audioChunk(at: Long, base64: String) = synchronized(audioLock) {
        val buffer = ByteBuffer.wrap(Base64.decode(base64, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.remaining() / 2
        var sum = 0.0
        repeat(count) { val value = buffer.short / 32768.0; sum += value * value }
        val loud = count > 0 && sqrt(sum / count) > VOICED_RMS
        if (loud) voiced += at
        val gap = if (lastAudioAt < 0) 0 else at - lastAudioAt
        lastAudioAt = at; audioChunks++; audioSamples += count
        maxAudioGap = maxOf(maxAudioGap, gap)
        if (gap >= 300) audioGaps++
        val current = audioRun
        val run = if (current == null || current.voiced != loud || gap >= 300) { current?.let(::logRun); AudioRun(loud, at) } else current
        if (run !== current) audioRun = run else run.maxGap = maxOf(run.maxGap, gap)
        run.to = at; run.chunks++; run.samples += count
    }

    private fun logRun(run: AudioRun) = note(run.from, "<- session.output_audio.delta x${run.chunks}: ${run.samples * 1000 / LIVE_RATE} ms of " +
        "${if (run.voiced) "voiced" else "silent"} audio, arrived ${run.from}..${run.to}, max gap ${run.maxGap} ms")

    private fun summary(): String {
        val inputLag = said.map { (name, onset, at) -> "$name ${events.firstOrNull { it.type == INPUT && it.at > at }?.startMs?.minus(onset)}" }
        val leads = events.filter { it.type == OUTPUT && it.startMs != null }.map { it.at - feedStart - it.startMs!! }
        val comments = sends.filter { it.type == "session.commentary.append" }
        val echoed = comments.count { c -> events.any { it.type == APPENDED && it.id == c.id } }
        val types = events.groupingBy { it.type }.eachCount().entries.joinToString { "${it.key} x${it.value}" }
        val audioText = synchronized(audioLock) {
            "$audioChunks chunks, ${audioSamples * 1000 / LIVE_RATE} ms of audio, arrival max gap $maxAudioGap ms, arrival gaps of 300 ms or more: $audioGaps"
        }
        return (checks + listOf(
            "timeline: first input start_ms minus the fixture onset on the audio clock: ${inputLag.joinToString()}",
            "timeline: output transcript arrival minus start_ms on the audio clock: ${leads.minOrNull()}..${leads.maxOrNull()} ms",
            "commentary appends acknowledged with their client event id: $echoed of ${comments.size}",
            "event types: $types; output audio: $audioText; outputEnded calls: ${host.outputEnds.size}",
            "session end: ${host.ended}",
        )).joinToString("\n")
    }

    private fun expect(name: String, ok: Boolean, detail: String) {
        checks += "${if (ok) "PASS" else "FAIL"} $name: $detail"
        if (!ok) failures += name
    }

    private fun info(name: String, detail: String) { checks += "INFO $name: $detail" }

    private fun outputs(from: Long, to: Long = Long.MAX_VALUE) = events.filter { it.type == OUTPUT && it.at > from && it.at <= to }
    private fun inputs(from: Long) = events.filter { it.type == INPUT && it.at > from }
    private fun errors(from: Long) = events.filter { it.type == "error" && it.at >= from }.map { "${it.text} for ${it.id}" }
    private fun text(seen: List<Seen>) = seen.joinToString("") { it.text }
    private fun lastOutput() = maxOf(events.lastOrNull { it.type == OUTPUT }?.at ?: -1, voiced.lastOrNull() ?: -1)
    private fun now() = SystemClock.elapsedRealtime() - begin
    private fun note(line: String) = note(now(), line)
    private fun note(at: Long, line: String) { synchronized(lines) { lines += at to line } }
    private fun JSONObject.long(name: String) = if (has(name)) optLong(name) else null

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        val HISTORY = listOf(Message("live-h1", "user", "Привет, Alphy", 1, "sent"), Message("live-h2", "assistant", "Здравствуйте! Чем помочь?", 2, "complete"))
        // The texts that the chat bridge sends for a timer plan with voice approval off, then on (LiveVoiceBridge).
        const val ON_SCREEN = "Нужно подтверждение на экране."
        const val DONE = "Готово. План завершён."
        const val THINKING = "Пользователь написал: Сколько будет два плюс два? Ответ Alphy на экране: Четыре."
        const val LONG_ANSWER = "Ответ Alphy: Луна — единственный естественный спутник Земли. Она находится примерно в трёхстах восьмидесяти " +
            "четырёх тысячах километров от нас и всегда повёрнута к Земле одной стороной. Приливы и отливы в океанах возникают в основном " +
            "из-за притяжения Луны, а полный цикл её фаз длится около двадцати девяти с половиной суток."
        const val PROPOSAL = "Alphy предлагает: Таймер, 5 минут. Скажите «да», чтобы выполнить, или «нет», чтобы отменить."
        const val STARTED = "Запускаю."
        val CLAIMS = listOf("поставил", "поставлен", "запустил", "запускаю", "готово")
        val STARTS = listOf("запускаю", "запущен", "запустил", "выполняю", "выполнен", "готово", "одобрен", "поставил", "поставлен")
    }
}

/**
 * A scripted GPT-Live server for the rehearsal. It hears speech by level on the audio clock, gives the next scripted transcript after
 * 300 ms of silence, opens a delegation for the two requests, answers each commentary word by word, and stops a reply at speech.
 */
private class ScriptedLiveServer : LiveTransport {
    // A daemon worker that is never shut down, so a late audio send after the close cannot throw.
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "scripted-live").apply { isDaemon = true } }
    private lateinit var listener: LiveListener
    private var position = 0L
    private var speechStart = -1L
    private var lastVoiced = -1L
    private var turns = 0
    private var reply: ScheduledFuture<*>? = null

    override fun connect(url: String, key: String, listener: LiveListener): LiveConnection {
        this.listener = listener
        return object : LiveConnection {
            override fun send(text: String): Boolean { val e = JSONObject(text); worker.execute { receive(e) }; return true }
            override fun close() { worker.execute { listener.onClosed() } }
        }
    }

    private fun receive(e: JSONObject) {
        when (e.getString("type")) {
            "session.start" -> emit(JSONObject().put("type", "session.started")
                .put("session", JSONObject().put("id", "live_rehearsal").put("input", e.getJSONObject("session").getJSONArray("input"))))
            "session.input_audio.append" -> heard(Base64.decode(e.getString("audio"), Base64.DEFAULT))
            "session.commentary.append" -> {
                emit(JSONObject().put("type", APPENDED).put("client_event_id", e.getString("event_id")))
                speak(e.getString("content"))
            }
            "session.close" -> emit(JSONObject().put("type", "session.closed").put("reason", "close_requested"))
        }
    }

    private fun heard(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        val count = bytes.size / 2
        repeat(count) { val value = buffer.short / 32768.0; sum += value * value }
        position += 20
        if (sqrt(sum / count) > VOICED_RMS) {
            if (speechStart < 0) { speechStart = position - 20; reply?.cancel(false) }
            lastVoiced = position
        } else if (speechStart >= 0 && position - lastVoiced >= 300) {
            val text = TURNS.getOrElse(turns++) { "Ещё." }
            emit(JSONObject().put("type", INPUT).put("delta", text).put("start_ms", speechStart).put("end_ms", lastVoiced))
            if (turns <= 2) emit(JSONObject().put("type", DELEGATION).put("offset_ms", lastVoiced + 100)
                .put("delegation", JSONObject().put("id", "item_$turns")))
            speechStart = -1
        }
    }

    private fun speak(content: String) {
        reply?.cancel(false)
        val words = ArrayDeque(content.split(' '))
        val pcm = ByteBuffer.allocate(4800).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(2400) { putShort(if (it % 2 == 0) 3000 else -3000) } }.array()
        reply = worker.scheduleWithFixedDelay({
            val word = words.removeFirstOrNull()
            if (word == null) reply?.cancel(false)
            else {
                emit(JSONObject().put("type", "session.output_audio.delta").put("delta", Base64.encodeToString(pcm, Base64.NO_WRAP)))
                emit(JSONObject().put("type", OUTPUT).put("delta", "$word ").put("start_ms", position).put("end_ms", position + 150))
            }
        }, 300, 150, TimeUnit.MILLISECONDS)
    }

    private fun emit(event: JSONObject) = listener.onText(event.toString())

    private companion object {
        val TURNS = listOf("Поставь таймер на пять минут.", "Поставь таймер на пять минут.", "Да.")
    }
}

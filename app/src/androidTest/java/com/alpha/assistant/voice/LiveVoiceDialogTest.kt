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

/**
 * The live check of spec 6.3: one GPT-Live session with the real key runs a Russian request with a 25 s late commentary, a second
 * commentary, a thinking append, an interruption, a spoken «да» after a proposal, and a mute and an unmute. The events go to
 * `events.log` next to the report of `run.py live-check`. [dialogScriptPassesOnAScriptedServer] runs the same script without cost, so
 * a script fault does not spend a paid run.
 */
class LiveVoiceDialogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun dialogScriptPassesOnAScriptedServer() {
        val log = File(context.cacheDir, "live-dialog-rehearsal.log")
        val outcome = LiveDialog(ScriptedLiveServer(), Endpoints { "http://127.0.0.1:4000" }, AiAccount(AiProvider.OPENAI, "alpha-test-only"), 4_000)
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

internal class Outcome(val failures: List<String>, val summary: String)

/** One server event, or one sent append. Times are milliseconds since the dialog began; start, end, and offset are session ms. */
internal data class Seen(val at: Long, val type: String, val text: String = "", val startMs: Long? = null, val endMs: Long? = null,
    val id: String? = null)

/** One output audio chunk as it arrived. */
private class Chunk(val at: Long, val samples: Int, val voiced: Boolean)

/** One reply on the output stream by the rule of rulings R24 and R27. Times are dialog ms of chunk arrivals. */
private class StreamReply(val first: Long) { var lastVoiced = first; var voicedChunks = 0 }

internal const val INPUT = "session.input_transcript.delta"
internal const val OUTPUT = "session.output_transcript.delta"
internal const val DELEGATION = "session.delegation.created"
internal const val APPENDED = "session.commentary.appended"
private const val QUIET_MS = 1_500L
/**
 * Two output transcript replies are apart by at least this stamp gap. The stamps step by 200 ms; the first live run had stamp gaps of
 * at most 800 ms inside a reply and at least 1,200 ms between replies.
 */
private const val TRANSCRIPT_GAP_MS = 1_000L

/**
 * The dialog of spec 6.3 through [LiveVoiceSession] with a real-time [FakeLiveAudio]: a written chunk keeps the playback active until a
 * speaker would have played it. A feeder sends a 20 ms capture block every 20 ms for the whole session: digital silence, or the queued
 * fixture. Every step runs with a bounded wait, and the checks are judged at the end, so one paid run observes every assumption. The log
 * holds event types, ids, timeline values, and transcript text. It never holds the raw text of an event, an error message, or the key.
 */
internal class LiveDialog(transport: LiveTransport, endpoints: Endpoints, account: AiAccount, private val commentaryDelayMs: Long) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val begin = SystemClock.elapsedRealtime()
    private val lines = ArrayList<Pair<Long, String>>()
    val events = CopyOnWriteArrayList<Seen>()
    private val sends = CopyOnWriteArrayList<Seen>()
    /** The arrival times of voiced output audio chunks. */
    val voiced = CopyOnWriteArrayList<Long>()
    private val chunks = CopyOnWriteArrayList<Chunk>()
    private val timing = LiveTiming()
    private val audio = FakeLiveAudio(realTime = true)
    private val speaker = Speaker(audio)
    val host = Host()
    private val session = LiveVoiceSession(instrumentation.targetContext, scope, Recorder(transport), { speaker }, { account }, endpoints, timing)
    val feeder = WavFeeder(audio, afterBlock = ::sample)
    @Volatile private var feedStart = 0L
    /** The last `playbackActive` and phase that [sample] saw. Only the feeder thread uses them. */
    private var sampledActive: Boolean? = null
    private var sampledPhase: LivePhase? = null
    /** The dialog ms when the interrupting fixture was queued, or -1. */
    @Volatile private var interruptAt = -1L
    /** (dialog ms, voiced) of each chunk that the session wrote to the speaker. */
    private val plays = CopyOnWriteArrayList<Pair<Long, Boolean>>()
    private val flushes = CopyOnWriteArrayList<Long>()
    /** The changes of `playbackActive` and of the phase, as the feeder sees them every 20 ms. */
    private val activeChanges = CopyOnWriteArrayList<Pair<Long, Boolean>>()
    private val phaseChanges = CopyOnWriteArrayList<Pair<Long, LivePhase>>()
    private val checks = ArrayList<String>()
    private val failures = ArrayList<String>()
    /** (fixture, session audio ms of its speech onset, dialog ms of the queue). */
    private val said = ArrayList<Triple<String, Long, Long>>()
    private val audioLock = Any()
    private var audioRun: AudioRun? = null
    private var lastAudioAt = -1L

    private class AudioRun(val voiced: Boolean, val from: Long) { var to = from; var chunks = 0; var samples = 0L; var maxGap = 0L }

    inner class Host : LiveVoiceHost {
        @Volatile var history = emptyList<Message>()
        @Volatile var ended: String? = null
        val delegations = LinkedBlockingQueue<Pair<String, String>>()
        val outputEnds = CopyOnWriteArrayList<Pair<Long, Long>>()
        override fun history() = history
        override fun delegation(id: String, request: String) { note("host delegation $id request «$request»"); delegations += id to request }
        override fun turn(turn: UserTurn) = note("host turn «${turn.text}» ${turn.startMs}..${turn.endMs} overlapped=${turn.overlappedPlayback}")
        // A hand-off is open for the whole check. With the speech and output of the script, the 180 s hold of ruling R38 never ends.
        override fun handOffRunning() = true
        override fun appended(eventId: String) = note("host appended $eventId")
        override fun outputEnded(endMs: Long) { note("host outputEnded endMs=$endMs"); outputEnds += now() to endMs }
        override fun ended(status: String, settingsRequired: Boolean) { note("host ended «$status» settings=$settingsRequired"); ended = status }
    }

    /** Records what the session writes to the speaker and each local flush. */
    private inner class Speaker(private val inner: FakeLiveAudio) : LiveAudio by inner {
        override fun play(samples: ShortArray) { plays += now() to (rms(samples) > LIVE_VOICED_RMS); inner.play(samples) }
        override fun flushPlayback() { flushes += now(); note("local flush"); inner.flushPlayback() }
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

    /** Runs the dialog of spec 6.3, or [probe] in its place without the core path checks of that dialog. */
    fun run(log: File, probe: (() -> Unit)? = null): Outcome {
        try {
            if (probe != null) probe() else script()
        } catch (failure: Throwable) {
            failures += "The script stopped: ${failure.javaClass.simpleName}"
            note("script stopped: ${failure.javaClass.simpleName}")
        } finally {
            val ended = host.ended
            expect("core: no error ends the mode", ended == null, "end before the stop «$ended»; errors ${errors(0)}")
            main { session.stop() }
            waitFor("session.closed", 5_000, whileActive = false) { events.any { it.type == "session.closed" } }
            feeder.stop()
            phaseChanges += now() to LivePhase.IDLE
            scope.cancel()
            synchronized(audioLock) { audioRun?.let(::logRun); audioRun = null }
        }
        if (probe == null) corePath()
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
        listen()
        SystemClock.sleep(1_000)

        // Step 1: the request, its delegation, and no claim before the late commentary.
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
        val onScreen = text(outputs(c1.first)).lowercase()
        info("on-screen confirmation (ruling R25)", "asks for «да» or «нет»: ${ASKS.any { it in onScreen }}; " +
            "tells to tap the card: ${TAPS.any { it in onScreen }}; «$onScreen»")

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
        waitFor("quiet after the thinking text", 30_000) { now() - lastOutput() >= QUIET_MS }

        // Step 4: the user speaks over a long answer, once its voice plays and the barge-in grace has passed.
        val c3 = comment(d1, LONG_ANSWER)
        val replying = waitFor("the voice of the long answer", 20_000) { voiced.any { it > c3.first } }
        SystemClock.sleep(500)
        interruptAt = now()
        val onset = say("russian")
        val second = host.delegations.poll(15, TimeUnit.SECONDS)
        val cut = second?.let { d -> events.firstOrNull { it.type == DELEGATION && it.id == d.first }?.at } ?: now()
        waitFor("the interrupting turn", 3_000) { inputs(interruptAt).isNotEmpty() }
        val reply = outputs(c3.first, cut)
        val turn = inputs(interruptAt)
        val userStart = turn.minOfOrNull { it.startMs ?: 0 }
        val userEnd = turn.maxOfOrNull { it.endMs ?: 0 }
        val speaking = userStart != null && reply.any { (it.endMs ?: 0) >= userStart }
        val stopped = userEnd != null && reply.all { (it.startMs ?: 0) <= userEnd + 1_000 }
        expect("the session flushed its playback at the interruption (spec 5.4.5)", flushes.any { it in interruptAt..interruptAt + 4_000 },
            "local flushes at ${flushes.joinToString()}; the long answer was ${if (replying) "playing" else "not heard"}")
        expect("the output transcript stops at an interruption (spec 5.4.5)", replying && speaking && stopped,
            "reply «${text(reply)}» ends at ${reply.maxOfOrNull { it.endMs ?: 0 }}; user turn «${text(turn)}» $userStart..$userEnd " +
                "(fixture onset at session audio $onset)")
        info("GPT-Live delegation for the interrupting request (server behavior)",
            second?.let { "${it.first} request «${it.second}»" } ?: "none")

        // Step 5: a proposal on the interruption's delegation, then «да».
        waitFor("quiet before the proposal", 30_000) { now() - lastOutput() >= QUIET_MS }
        val d2 = second?.first ?: d1
        val p = comment(d2, PROPOSAL)
        val proposed = spoken(p.first, "proposal")
        val proposal = outputs(p.first)
        val proposalEnd = proposal.maxOfOrNull { it.endMs ?: 0 }
        // The approval window compares session times, so «да» starts after the proposal's last transcript end on the audio clock.
        if (proposalEnd != null) waitFor("session audio past the proposal", 10_000) { feeder.fed * 20 > proposalEnd + 500 }
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
            "${events.firstOrNull { it.type == APPENDED && it.id == p.second }?.let { "at ${it.at}, ${it.startMs}..${it.endMs}" } ?: "not seen"}; " +
            "proposal voice arrived until ${voiced.lastOrNull { it in p.first..asking }}")
        // Rule 7: GPT-Live can open a delegation for the approval turn. Alphy answers it with the rule-5 text.
        val third = host.delegations.poll(5, TimeUnit.SECONDS)
        info("delegation for the «да» turn (spec 5.5.10 rule 7)", third?.let { "${it.first} request «${it.second}»" } ?: "none")
        val started = comment(third?.first ?: d2, STARTED)
        val early2 = text(outputs(asking, started.first)).lowercase()
        info("claim after «да» before «Запускаю.» (spec 5.3.3)", "${STARTS.filter { it in early2 }.ifEmpty { "none" }} in «$early2»")
        spoken(started.first, "start confirmation")

        // Step 6 (ruling R37): the microphone button mutes and unmutes, as at a permission dialog. It comes last, because a mute
        // stops the input audio clock, and the earlier steps compare input and output stamps.
        val muted = now()
        main { session.mute(true) }
        SystemClock.sleep(1_000)
        main { session.mute(false) }
        SystemClock.sleep(2_000)
        val ids = sends.filter { it.at >= muted && it.type in MUTES }.map { "${it.type} ${it.id}" }
        val rejected = events.filter { it.type == "error" && it.at >= muted && sends.any { s -> s.id == it.id && s.type in MUTES } }
        expect("mute and unmute keep the mode (ruling R37)", ids.size == 2 && host.ended == null, "sent $ids; errors ${errors(muted)}")
        expect("GPT-Live accepts mute and unmute (ruling R37)", rejected.isEmpty(), rejected.joinToString { "${it.text} for ${it.id}" }
            .ifEmpty { "no error for ${ids.joinToString()}" })
    }

    /**
     * The core path of rulings R24 to R30, judged on the whole recorded stream. A stream reply starts at a voiced chunk and ends after
     * [LiveTiming.replyGapMs] of silent samples, or after that long without any chunk.
     */
    private fun corePath() {
        val replies = streamReplies()
        val transcriptReplies = transcriptReplies()
        val ends = host.outputEnds.map { it.first }
        val stop = phaseChanges.lastOrNull { it.second == LivePhase.IDLE }?.first ?: now()
        val rows = replies.mapIndexed { i, r ->
            val next = replies.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
            val played = plays.count { it.second && it.first in r.first..r.lastVoiced + 500 }
            val endsHere = ends.filter { it >= r.lastVoiced && it < next }
            val active = activeChanges.filter { it.first in r.first..r.lastVoiced + 200 }
                .joinToString(" ") { "${it.first}:${if (it.second) "on" else "off"}" }
            val listening = listening(r.lastVoiced, minOf(next, stop))
            ReplyLine(i + 1, r, played, endsHere, listening, "reply ${i + 1}: voiced ${r.first}..${r.lastVoiced}, voiced chunks " +
                "${r.voicedChunks}, played ${played}; outputEnded at ${endsHere.joinToString().ifEmpty { "none" }}; LISTENING after it: " +
                "$listening; playbackActive ${active.ifEmpty { "no change" }}")
        }
        rows.forEach { info("stream", it.text) }
        val unmatched = ends.count { e -> replies.indices.none { i -> e >= replies[i].lastVoiced && e < (replies.getOrNull(i + 1)?.first ?: Long.MAX_VALUE) } }
        expect("core: one outputEnded for each reply", rows.isNotEmpty() && rows.all { it.ends.size == 1 } && unmatched == 0 &&
            replies.size == transcriptReplies.size, "${replies.size} stream replies, ${transcriptReplies.size} transcript replies " +
            "(${transcriptReplies.joinToString { "${it.first}..${it.second}" }}); ${ends.size} outputEnded calls, $unmatched outside a reply")
        expect("core: the phase returns to LISTENING after each reply", rows.isNotEmpty() && rows.all { it.listening },
            "replies without LISTENING after them: ${rows.filter { !it.listening }.map { it.index }}")
        val cut = if (interruptAt < 0) -1 else replies.indexOfFirst { interruptAt in it.first..it.lastVoiced + timing.replyGapMs }
        val after = when {
            interruptAt < 0 -> emptyList()
            cut < 0 -> rows.filter { it.reply.first > interruptAt }
            else -> rows.drop(cut + 1)
        }
        expect("core: every reply after the interruption plays", after.isNotEmpty() && after.all { it.played > 0 },
            after.joinToString { "reply ${it.index} played ${it.played} of ${it.reply.voicedChunks}" }.ifEmpty { "no reply after it" })
        if (cut >= 0) {
            val interrupted = rows[cut]
            val next = rows.getOrNull(cut + 1)
            val silent = chunks.filter { !it.voiced && it.at > interrupted.reply.lastVoiced && it.at < (next?.reply?.first ?: Long.MAX_VALUE) }
                .sumOf { it.samples.toLong() } * 1000 / LIVE_RATE
            info("interruption stream", "interrupted reply ${interrupted.index}: played ${interrupted.played} of ${interrupted.reply.voicedChunks} " +
                "voiced chunks; local flushes at ${flushes.joinToString().ifEmpty { "none" }}; $silent ms of silent stream audio before the next " +
                "voiced chunk; next reply ${next?.let { "${it.index} ${if (it.played > 0) "played" else "dropped"} (${it.played} of ${it.reply.voicedChunks})" } ?: "none"}")
        } else info("interruption stream", "no stream reply was open at the interruption (queued at $interruptAt)")
        val gaps = chunks.zipWithNext { a, b -> (b.at - a.at) to b.at }.sortedByDescending { it.first }
        info("output chunk arrival gaps", "max ${gaps.firstOrNull()?.first} ms; largest ${gaps.take(3).joinToString { "${it.first} ms at ${it.second}" }}; " +
            "${chunks.size} chunks, ${chunks.sumOf { it.samples.toLong() } * 1000 / LIVE_RATE} ms of audio")
    }

    private class ReplyLine(val index: Int, val reply: StreamReply, val played: Int, val ends: List<Long>, val listening: Boolean, val text: String)

    /** The replies of the recorded stream, by the same rule as the session but computed apart from it. */
    private fun streamReplies(): List<StreamReply> {
        val replies = ArrayList<StreamReply>()
        var open: StreamReply? = null
        var silent = 0L
        var last = -1L
        for (chunk in chunks) {
            if (open != null && chunk.at - last >= timing.replyGapMs) open = null
            last = chunk.at
            if (chunk.voiced) {
                val reply = open ?: StreamReply(chunk.at).also { replies += it }
                reply.lastVoiced = chunk.at; reply.voicedChunks++
                open = reply; silent = 0
            } else if (open != null) {
                silent += chunk.samples
                if (silent >= timing.replyGapMs * LIVE_RATE / 1000) open = null
            }
        }
        return replies
    }

    /** (first start_ms, last end_ms) of each group of output transcript deltas apart by [TRANSCRIPT_GAP_MS] or more. */
    private fun transcriptReplies(): List<Pair<Long, Long>> {
        val groups = ArrayList<Pair<Long, Long>>()
        for (delta in events.filter { it.type == OUTPUT }) {
            val start = delta.startMs ?: continue
            val end = delta.endMs ?: continue
            val last = groups.lastOrNull()
            if (last != null && start - last.second < TRANSCRIPT_GAP_MS) groups[groups.lastIndex] = last.first to maxOf(last.second, end)
            else groups += start to end
        }
        return groups
    }

    /** Whether the phase was LISTENING at some time in [from, to). */
    private fun listening(from: Long, to: Long): Boolean {
        val before = phaseChanges.lastOrNull { it.first <= from }?.second
        return before == LivePhase.LISTENING || phaseChanges.any { it.first in from until to && it.second == LivePhase.LISTENING }
    }

    fun start(): Boolean {
        main { session.start(host) }
        return waitFor("session.started", 12_000) { main { session.phase } == LivePhase.LISTENING }
    }

    /** Starts the capture feeder: from now on the session hears silence or a queued fixture. */
    fun listen() { feedStart = now(); feeder.start() }

    /** Queues one fixture and waits until the feeder has sent it. Returns the session audio ms of its speech onset. */
    private fun say(name: String): Long {
        val samples = wavSamples(instrumentation.context.assets.open("voice/$name.wav").use { it.readBytes() })
        val onset = feeder.fed * 20 + maxOf(0, samples.indexOfFirst { abs(it.toInt()) > 1000 }) * 1000L / LIVE_RATE
        feeder.say(samples)
        said += Triple(name, onset, now())
        note("say $name: ${samples.size * 1000L / LIVE_RATE} ms, speech onset at session audio $onset ms")
        waitFor("$name sent", 10_000) { feeder.idle }
        return onset
    }

    /** Samples `playbackActive` and the phase after each capture block. */
    private fun sample() {
        val nowActive = audio.playbackActive
        if (nowActive != sampledActive) { sampledActive = nowActive; activeChanges += now() to nowActive }
        // A read of the snapshot state outside the main thread sees the last applied value.
        val nowPhase = session.phase
        if (nowPhase != sampledPhase) { sampledPhase = nowPhase; phaseChanges += now() to nowPhase; note("phase $nowPhase") }
    }

    fun comment(delegation: String, text: String): Pair<Long, String> {
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

    /** Records each output chunk and logs the stream as runs of voiced or silent chunks, split at an arrival gap of 300 ms. */
    private fun audioChunk(at: Long, base64: String) = synchronized(audioLock) {
        val buffer = ByteBuffer.wrap(Base64.decode(base64, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(buffer.remaining()).also { buffer.get(it) }
        val loud = rms(samples) > LIVE_VOICED_RMS
        if (loud) voiced += at
        chunks += Chunk(at, samples.size, loud)
        val gap = if (lastAudioAt < 0) 0 else at - lastAudioAt
        lastAudioAt = at
        val current = audioRun
        val run = if (current == null || current.voiced != loud || gap >= 300) { current?.let(::logRun); AudioRun(loud, at) } else current
        if (run !== current) audioRun = run else run.maxGap = maxOf(run.maxGap, gap)
        run.to = at; run.chunks++; run.samples += samples.size
    }

    private fun logRun(run: AudioRun) = note(run.from, "<- session.output_audio.delta x${run.chunks}: ${run.samples * 1000 / LIVE_RATE} ms of " +
        "${if (run.voiced) "voiced" else "silent"} audio, arrived ${run.from}..${run.to}, max gap ${run.maxGap} ms")

    private fun summary(): String {
        val inputLag = said.map { (name, onset, at) -> "$name ${events.firstOrNull { it.type == INPUT && it.at > at }?.startMs?.minus(onset)}" }
        val leads = events.filter { it.type == OUTPUT && it.startMs != null }.map { it.at - feedStart - it.startMs!! }
        val comments = sends.filter { it.type == "session.commentary.append" }
        val echoed = comments.count { c -> events.any { it.type == APPENDED && it.id == c.id } }
        val types = events.groupingBy { it.type }.eachCount().entries.joinToString { "${it.key} x${it.value}" }
        return (checks + listOf(
            "timeline: first input start_ms minus the fixture onset on the audio clock: ${inputLag.joinToString()}",
            "timeline: output transcript arrival minus start_ms on the audio clock: ${leads.minOrNull()}..${leads.maxOrNull()} ms",
            "commentary appends acknowledged with their client event id: $echoed of ${comments.size}",
            "event types: $types; output audio chunks: ${chunks.size}; outputEnded calls: ${host.outputEnds.size}",
            "session end: ${host.ended}",
        )).joinToString("\n")
    }

    private fun expect(name: String, ok: Boolean, detail: String) {
        checks += "${if (ok) "PASS" else "FAIL"} $name: $detail"
        if (!ok) failures += name
    }

    private fun info(name: String, detail: String) { checks += "INFO $name: $detail" }

    // The send time is taken before the send, so a part that arrives in the same millisecond answers it.
    fun outputs(from: Long, to: Long = Long.MAX_VALUE) = events.filter { it.type == OUTPUT && it.at >= from && it.at <= to }
    private fun inputs(from: Long) = events.filter { it.type == INPUT && it.at > from }
    private fun errors(from: Long) = events.filter { it.type == "error" && it.at >= from }.map { "${it.text} for ${it.id}" }
    fun text(seen: List<Seen>) = seen.joinToString("") { it.text }
    fun lastOutput() = maxOf(events.lastOrNull { it.type == OUTPUT }?.at ?: -1, voiced.lastOrNull() ?: -1)
    fun now() = SystemClock.elapsedRealtime() - begin
    fun note(line: String) = note(now(), line)
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
        val MUTES = setOf("session.input_audio.mute", "session.input_audio.unmute")
        val CLAIMS = listOf("поставил", "поставлен", "запустил", "запускаю", "готово")
        val STARTS = listOf("запускаю", "запущен", "запустил", "выполняю", "выполнен", "готово", "одобрен", "поставил", "поставлен")
        val ASKS = listOf("«да»", "«нет»", "скажите да", "скажите мне да", "да или нет")
        val TAPS = listOf("нажм", "кнопк", "карточк")
    }
}

/**
 * A scripted GPT-Live server for the rehearsal, shaped like the live runs. From `session.started` it streams one 100 ms output chunk
 * every 100 ms: voiced inside a reply, silent between replies and in the pauses of a reply (up to 600 ms). The output transcript comes
 * 2 s ahead of its audio. It hears speech by level on the input audio clock and gives the next scripted transcript after 300 ms of
 * silence. A reply that the user talked over stops at the end of that user turn, as in the second live run: the audio of the words
 * already in the transcript still streams, and the next reply starts after at least 1.1 s of silent stream audio. Each turn opens a
 * delegation and gets a filler line, except a turn of [undelegated]: it gets a short answer and no delegation. [script] gives the
 * transcript of each turn.
 */
internal class ScriptedLiveServer(private val script: List<String> = TURNS, private val undelegated: Set<Int> = emptySet()) : LiveTransport {
    // A daemon worker that is never shut down, so a late audio send after the close cannot throw.
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "scripted-live").apply { isDaemon = true } }
    private lateinit var listener: LiveListener
    private var stream: ScheduledFuture<*>? = null
    private var input = 0L
    private var ticks = 0L
    private var speechStart = -1L
    private var lastVoiced = -1L
    private var turns = 0
    /** The planned chunks of the open replies: true is voiced. */
    private val planned = ArrayDeque<Boolean>()
    /** (tick to send at, delta, start_ms) of the planned transcript. */
    private val transcript = ArrayDeque<Triple<Long, String, Long>>()

    override fun connect(url: String, key: String, listener: LiveListener): LiveConnection {
        this.listener = listener
        return object : LiveConnection {
            override fun send(text: String): Boolean { val e = JSONObject(text); worker.execute { receive(e) }; return true }
            override fun close() { worker.execute { stream?.cancel(false); listener.onClosed() } }
        }
    }

    private fun receive(e: JSONObject) {
        when (e.getString("type")) {
            "session.start" -> {
                emit(JSONObject().put("type", "session.started")
                    .put("session", JSONObject().put("id", "live_rehearsal").put("input", e.getJSONObject("session").getJSONArray("input"))))
                stream = worker.scheduleAtFixedRate(::tick, 100, 100, TimeUnit.MILLISECONDS)
            }
            "session.input_audio.append" -> heard(Base64.decode(e.getString("audio"), Base64.DEFAULT))
            "session.commentary.append" -> {
                emit(JSONObject().put("type", APPENDED).put("client_event_id", e.getString("event_id")))
                speak(e.getString("content"))
            }
            "session.thinking.append" -> {
                emit(JSONObject().put("type", "session.thinking.appended").put("client_event_id", e.getString("event_id")))
                speak("Четыре.")
            }
            "session.close" -> { stream?.cancel(false); emit(JSONObject().put("type", "session.closed").put("reason", "close_requested")) }
        }
    }

    /** One chunk of the stream, and the transcript that is due. */
    private fun tick() {
        ticks++
        while (transcript.isNotEmpty() && transcript.first().first <= ticks) {
            val (_, delta, start) = transcript.removeFirst()
            emit(JSONObject().put("type", OUTPUT).put("delta", delta).put("start_ms", start).put("end_ms", start + 200))
        }
        listener.onText(if (planned.removeFirstOrNull() == true) outputAudio(3, LIVE_RATE / 10) else silentAudio())
    }

    private fun heard(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(buffer.remaining()).also { buffer.get(it) }
        input += 20
        if (rms(samples) > LIVE_VOICED_RMS) {
            if (speechStart < 0) speechStart = input - 20
            lastVoiced = input
        } else if (speechStart >= 0 && input - lastVoiced >= 300) {
            val text = script.getOrElse(turns++) { "Ещё." }
            if (true in planned) {
                // The transcript runs 20 chunks ahead of the audio. 6 more silent chunks keep the next reply's stamps 1,000 ms apart.
                while (planned.size > 20) planned.removeLast()
                transcript.clear()
                repeat(6) { planned += false }
            }
            emit(JSONObject().put("type", INPUT).put("delta", text).put("start_ms", speechStart).put("end_ms", lastVoiced))
            if (turns - 1 in undelegated) speak("Отвечу сама, коротко.")
            else {
                emit(JSONObject().put("type", DELEGATION).put("offset_ms", lastVoiced + 100).put("delegation", JSONObject().put("id", "item_$turns")))
                speak(if (turns == 1) "Секундочку." else "Понял.")
            }
            speechStart = -1
        }
    }

    /** Plans a reply 500 ms after the planned audio: 200 ms of voice for each word, a 200 ms pause after a comma, 600 ms after a sentence. */
    private fun speak(content: String) {
        repeat(5) { planned += false }
        for (word in content.split(' ')) {
            val at = ticks + planned.size + 1
            transcript += Triple(maxOf(ticks + 1, at - 20), "$word ", at * 100 - 2_000)
            repeat(2) { planned += true }
            repeat(when (word.last()) { '.', '!', '?' -> 6; ',' -> 2; else -> 0 }) { planned += false }
        }
    }

    private fun emit(event: JSONObject) = listener.onText(event.toString())

    private companion object {
        val TURNS = listOf("Поставь таймер на пять минут.", "Поставь таймер на пять минут.", "Да.")
    }
}

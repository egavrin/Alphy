package com.alpha.assistant.voice

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.KEY_ERRORS
import com.alpha.assistant.cloud.providerFailure
import com.alpha.assistant.data.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal enum class LivePhase { IDLE, CONNECTING, LISTENING, SPEAKING, MUTED }

/** The gate of a touch on the stage ([LiveVoiceSession.touch]): it stays closed this long after the end of the touch. */
private const val TOUCH_TAIL_MS = 300L
/** The gate opens this long after the down of a touch that stays down, because the sound comes at the down and at the end. */
private const val TOUCH_HOLD_MS = 1_000L

/** The sync rule of plan "one scene", decision 13: the quiet run before a voiced start, the fallback frame, and the wait for an onset. */
private const val ONSET_QUIET_MS = 200L
private const val ONSET_FALLBACK_MS = 450L
private const val ONSET_WAIT_MS = 1_500L
/** The received chunks that a later arm can still find: the chunks of the last 3 s. */
private const val RECEIVED_KEEP_MS = 3_000L
private const val SENTENCE_ENDS = ".!?…"

/** [text] with no letter case, no punctuation, and single spaces, for the match of a spoken text with a sentence. */
private fun plain(text: String) = text.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

/** The mark word of [LiveProtocol.MARK] in [plain] form. */
private val SPOKEN_MARK = plain(LiveProtocol.MARK)

/** [heard] in [plain] form with no mark word at its start: the voice can say the mark aloud before a sentence (live record live-2b-uxHeart). */
private fun plainHeard(heard: String) = plain(heard).let { if (it == SPOKEN_MARK) "" else it.removePrefix("$SPOKEN_MARK ") }

/**
 * True when [heard], the transcript since the end of the last sentence, starts [sentence]: one is the start of the other, and [heard] has
 * a letter or a digit (plan "one scene", decision 14). A spoken mark word at the start of [heard] does not count.
 */
internal fun startsSentence(heard: String, sentence: String): Boolean {
    val said = plainHeard(heard)
    val whole = plain(sentence)
    return said.isNotEmpty() && whole.isNotEmpty() && (whole.startsWith(said) || said.startsWith(whole))
}

/** True when [heard] holds the whole of [sentence] from its start, after a spoken mark word. */
internal fun coversSentence(heard: String, sentence: String) = plain(sentence).let { it.isNotEmpty() && plainHeard(heard).startsWith(it) }

/** The text after the last sentence end of [text]. */
internal fun afterSentenceEnd(text: String) = text.substring(text.indexOfLast { it in SENTENCE_ENDS } + 1)

/** The text of [heard] after the shortest start of it that holds the whole of [sentence], with no punctuation at its start. */
internal fun afterCovered(heard: String, sentence: String): String =
    heard.indices.firstOrNull { coversSentence(heard.substring(0, it + 1), sentence) }?.let { end ->
        heard.substring(end + 1).dropWhile { !it.isLetterOrDigit() && !it.isWhitespace() }
    } ?: ""

/**
 * The timers of spec 5.8 and 5.4.5. A user turn also ends after [turnGapMs] without input transcript deltas. A reply of Alphy ends
 * after [replyGapMs] of silent stream audio after its last voiced chunk, counted in samples, or after [replyGapMs] without any chunk
 * (rulings R24 and R27). The live check heard 600 ms silent runs inside a reply and a 900 ms run between two replies. A running
 * hand-off holds off the [silenceMs] end for at most [handOffHoldMs] after the last user speech or Alphy output (ruling R38).
 */
internal data class LiveTiming(val startedMs: Long = 10_000, val silenceMs: Long = 60_000, val firstSpeechMs: Long = 10_000,
    val requestWaitMs: Long = 500, val turnGapMs: Long = 1_200, val replyGapMs: Long = 800, val handOffHoldMs: Long = 180_000)

/** The chat side of the voice mode. The session calls it on the main thread only. */
internal interface LiveVoiceHost {
    fun history(): List<Message>
    /** A hand-off. [request] is blank when no request text arrived within [LiveTiming.requestWaitMs] (spec 5.5.2). */
    fun delegation(id: String, request: String)
    fun turn(turn: UserTurn)
    fun handOffRunning(): Boolean
    /**
     * Each output transcript piece of a reply with its server times, true for the first piece of a reply, and the uptime of its arrival in
     * nanoseconds (plan "one scene", decision 12). A skipped sentence gives none ([LiveVoiceSession.skip]).
     */
    fun outputText(text: String, startMs: Long, endMs: Long, replyStart: Boolean, arrivedNanos: Long) {}
    /** The first input transcript piece of a user turn. */
    fun inputStarted() {}
    /** Each input transcript piece, after [inputStarted] for the first one, with its server event id (plan "one scene", K1). */
    fun inputText(delta: String, eventId: String?) {}
    /** The server event id of each output transcript piece, before [outputText] and also for a skipped piece (K5). */
    fun outputPiece(eventId: String?) {}
    /** A classifier answer, only while [LiveVoiceSession.classifierOn] (K1). */
    fun decision(decision: LiveEvent.Decision) {}
    /** The server id of the scene item of [LiveVoiceSession.createItem] (K2). */
    fun itemCreated(clientEventId: String, itemId: String) {}
    /** The classifier went off: for the session after a classifier error, or for the app run after a failed start with triggers (K8). */
    fun classifierOff(appRun: Boolean) {}
    /** At `session.commentary.appended` for the client event id that [LiveVoiceSession.commentary] returned. */
    fun appended(eventId: String)
    /** Once at the end of each reply, as [LiveTiming] defines it. [endMs] is [LiveTranscript.outputEndMs]. */
    fun outputEnded(endMs: Long)
    fun ended(status: String, settingsRequired: Boolean)
}

/**
 * The live voice mode (spec 5.3, 5.4.5, 5.4.6, 5.8). All state and every host call stay on the main thread of [scope]. Socket
 * callbacks and capture blocks hop to it with the generation token of their session, so late events after an end are dropped.
 * Only the audio sends and the blocking playback run on their own serial workers.
 */
internal class LiveVoiceSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transport: LiveTransport,
    private val audio: () -> LiveAudio,
    private val account: suspend () -> AiAccount?,
    private val endpoints: Endpoints,
    private val timing: LiveTiming = LiveTiming(),
) {
    var active by mutableStateOf(false); private set
    var phase by mutableStateOf(LivePhase.IDLE); private set
    var level by mutableStateOf(0f); private set
    /** The end status. It is empty while the session runs, and [stop] without a session clears it. */
    var status by mutableStateOf(""); private set
    /** The end status is a key error, so the chat offers «Сервис ИИ» (spec 5.8). */
    var settingsRequired by mutableStateOf(false); private set
    var userSpeaking by mutableStateOf(false); private set
    /** The microphone button state (spec 5.2.5). An away mute does not change it. */
    var userMuted by mutableStateOf(false); private set
    /** The output transcript of the current or the last reply (plan D, Da3). The end of the mode clears it. */
    var speech by mutableStateOf(""); private set
    /** The count of host texts (commentary and thinking appends) sent in this session, for the follow signal (plan "one scene", decision 19). */
    var texts = 0; private set
    /** The start held the classifier triggers, and no classifier error came since (plan "one scene", K8). */
    var classifierOn = false; private set
    private var triggers: org.json.JSONArray? = null
    /** This start is the K8 start with no triggers. At `session.started`, the classifier goes off for the app run. */
    private var retried = false
    /** The client event ids of the scene item creates and deletes of this session. An error for one turns the classifier off (K8). */
    private val classifierIds = HashSet<String>()

    private var generation = 0
    private var host: LiveVoiceHost? = null
    private var connection: LiveConnection? = null
    private var live: LiveAudio? = null
    private var transcript = LiveTranscript()
    private var detector = BargeInDetector()
    private var started = false
    private var firstSpeech = false
    private var awayMuted = false
    private var captureMuted = false
    /** After a barge-in or a flush, the rest of the current reply is dropped (spec 5.4.5). */
    private var dropping = false
    /** A barge-in emptied the playback, so the next user turn still started over Alphy's voice. */
    private var interrupted = false
    /** The next output transcript delta starts [speech] again. */
    private var replyEnded = true
    /**
     * The silent output chunks after the last voiced chunk of the open reply. They play only if a voiced chunk of the reply follows
     * while the device still plays (ruling R26).
     */
    private val held = ArrayList<ShortArray>()
    /** The samples of silent stream audio after the last voiced chunk of the open reply. */
    private var silentSamples = 0L
    /** The uptime of the last capture block or output chunk that saw audio in the device. The overlap rule (R11) uses the device flag. */
    private var playbackSeenMs = Long.MIN_VALUE
    private var waiting: LiveEvent.Delegation? = null
    /** The uptime of the down of a touch on the stage while it is down, and the uptime at which the gate after its end opens ([touch]). */
    private var touchDownMs: Long? = null
    private var touchOpenMs = 0L
    /** The user turn has an input transcript piece, so the next piece starts no turn ([LiveVoiceHost.inputStarted]). */
    private var inputOpen = false
    /** The session time of the end of the last input transcript piece, and whether the output transcript ended the user turn of that piece. */
    private var lastInputEndMs: Long? = null
    private var endedByOutput = false
    /** A received voiced chunk: its arrival, its first frame in [queuedFrames], and a quiet run of [ONSET_QUIET_MS] before it. */
    private class Received(val arrivedNanos: Long, val frame: Long, val onset: Boolean)
    private val received = ArrayDeque<Received>()
    /** The frames queued for the speaker in this session. The player adds the frames that a flush skipped to [skippedFrames]. */
    private var queuedFrames = 0L
    private val skippedFrames = AtomicLong()
    /** The quiet run since the last voiced chunk, and the arrival and the length of the last chunk, for the arrival gap. */
    private var quietMs = 0L
    private var lastChunkNanos = 0L
    private var lastChunkMs = 0L
    private var onsetJob: Job? = null
    /** The sentence of a pending [skip], and true while it waits through the reply that was open at the skip (fix list item 2c of stage 2). */
    private var skipSentence: String? = null
    private var skipSpans = false
    /**
     * A started skip: its sentence, the transcript since the sentence start, the dropped pieces with the start and the reply start of the
     * first, and true once the whole sentence came. [skipText] drops the transcript pieces. From the next voiced onset, [skipDrop] holds the
     * audio in [skipHeld] until the whole sentence came, and then drops it. [skipResume]: a new text came after the sentence, so the next
     * onset plays.
     */
    private var skipping: String? = null
    private var skipHeard = ""
    private var skipDropped = ""
    private var skipStartMs = 0L
    private var skipReplyStart = false
    private var skipCovered = false
    private var skipText = false
    private var skipAudio = false
    private var skipDrop = false
    private var skipResume = false
    private val skipHeld = ArrayList<ShortArray>()
    /** The output transcript of the open reply since its last sentence end, for the match of a skip. */
    private var sinceSentence = ""
    /** The client event ids of the appends, mutes, and unmutes of this session. An error for one keeps the mode (rulings R10, R37). */
    private val tolerated = HashSet<String>()
    /** The uptime of the session start, the last change of the user's speech, or the last output end (ruling R38). */
    private var heardMs = 0L
    private var silenceJob: Job? = null
    private var firstJob: Job? = null
    private var gapJob: Job? = null
    private var replyJob: Job? = null
    // The playback worker reads it: a flush or an end drops the chunks that still wait for play().
    @Volatile private var playback = 0
    private val sender = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val player = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

    /**
     * [firstSpeech]: an assistant button start, which ends after [LiveTiming.firstSpeechMs] without speech (spec 5.11.2). [triggers]: the
     * classifier triggers of plan "one scene", K1. A failed start with them starts once more without them (K8).
     */
    fun start(host: LiveVoiceHost, firstSpeech: Boolean = false, triggers: org.json.JSONArray? = null) {
        if (active) return
        val token = ++generation
        this.host = host
        this.firstSpeech = firstSpeech
        this.triggers = triggers; classifierOn = triggers != null; classifierIds.clear(); texts = 0; retried = false
        transcript = LiveTranscript(); detector = BargeInDetector(); waiting = null; tolerated.clear()
        started = false; userMuted = false; awayMuted = false; captureMuted = false; dropping = false; interrupted = false
        playbackSeenMs = Long.MIN_VALUE; held.clear(); touchDownMs = null; touchOpenMs = 0L
        inputOpen = false; lastInputEndMs = null; endedByOutput = false; received.clear(); queuedFrames = 0; skippedFrames.set(0); quietMs = 0; lastChunkNanos = 0; lastChunkMs = 0
        skipSentence = null; endSkip(); sinceSentence = ""
        active = true; status = ""; phase = LivePhase.CONNECTING
        scope.launch {
            try {
                // The account read can reach the key store.
                val account = withContext(Dispatchers.IO) { account() }
                if (token != generation) return@launch
                if (account == null || account.provider != AiProvider.OPENAI) { end(string(R.string.live_voice_openai_only), settingsRequired = true); return@launch }
                val url = liveUrl(endpoints)
                // Before the connect, so a failed read leaves no open socket (review m9).
                val first = LiveProtocol.start(host.history(), triggers)
                val device = audio().also { live = it }
                if (captureMuted) device.setCaptureMuted(true)
                if (!device.open({ block -> onMain(token) { captured(block) } }, { onMain(token) { end(string(R.string.live_voice_focus_lost)) } })) {
                    end(string(R.string.live_voice_focus_lost)); return@launch
                }
                connection = transport.connect(url, account.key, listener(token)).also { it.send(first) }
                after(timing.startedMs) { if (!started) end(string(R.string.live_voice_connection)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                val failure = error as? CloudFailure
                if (token == generation) end(failure?.message ?: string(R.string.live_voice_failed), failure?.code in KEY_ERRORS)
            }
        }
    }

    /** The microphone button (spec 5.4.6). */
    fun mute(muted: Boolean) { userMuted = muted; applyMute() }

    /** A screen or an app that Alphy opened is in front (spec 5.8.1): mute and flush, and unmute when Alphy is back. */
    fun away(away: Boolean) {
        if (away && active) flush()
        awayMuted = away
        applyMute()
    }

    /**
     * A touch on the stage starts ([down]) or ends (plan "one scene", Task 6). On a phone the sound of a tap in the microphone stopped
     * Alphy's voice as speech. So from the down until [TOUCH_TAIL_MS] after the end, but not longer than [TOUCH_HOLD_MS] after the down,
     * GPT-Live and the barge-in check get zero samples. A person can speak while a finger moves a slider, and a lost end opens again.
     */
    fun touch(down: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (down) touchDownMs = now else { touchDownMs = null; touchOpenMs = now + TOUCH_TAIL_MS }
    }

    /** Sends a hand-off result and returns its client event id. After the end it sends nothing, as hand-offs outlive it (spec 5.5.9). */
    fun commentary(delegationId: String, text: String): String {
        skipSentence?.let { if (it in text) skipSentence = null }
        val id = LiveProtocol.newId()
        if (started) { tolerated += id; texts++; send(LiveProtocol.commentary(id, delegationId, text)); countSilence() }
        return id
    }

    /** Alphy speaks now: a reply is open or the speaker plays, and the reply is not dropped (plan "one scene", decision 11). */
    val speaksNow: Boolean get() = active && speaking() && !dropping

    /**
     * The sync rule of plan "one scene", decision 13. The target is the first voiced chunk received after [armedNanos] with a quiet run of
     * [ONSET_QUIET_MS] before it: silent received frames or a gap in arrival. With no such chunk within [ONSET_WAIT_MS] of the arm, the
     * target is the frame received [ONSET_FALLBACK_MS] after the arm. [fire] runs on the main thread when [LiveAudio.playedFrames] reaches
     * the target, polled every 20 ms, and at once when the speaker already played it. A new call replaces a pending one.
     */
    fun onsetAfter(armedNanos: Long, fire: () -> Unit) {
        cancelOnset()
        val token = generation
        onsetJob = scope.launch {
            var target: Long? = null
            var fallback: Long? = null
            while (target == null) {
                val now = SystemClock.elapsedRealtimeNanos()
                target = received.firstOrNull { it.arrivedNanos > armedNanos && it.onset }?.frame
                if (fallback == null && now - armedNanos >= ONSET_FALLBACK_MS * 1_000_000) fallback = queuedFrames
                if (target == null && now - armedNanos >= ONSET_WAIT_MS * 1_000_000) target = fallback ?: queuedFrames
                if (target == null) delay(20)
            }
            // The first frame of the target plays once the speaker played more than the frames before it. A target at the end of the
            // received frames fires when they all played.
            while (token == generation && !(playedFrames() > target || playedFrames() >= target && target >= queuedFrames)) delay(20)
            if (token == generation) fire()
        }
    }

    fun cancelOnset() { onsetJob?.cancel(); onsetJob = null }

    /**
     * Drops [sentence] once (plan "one scene", decision 15, and the fix list of stage 2, item 2). From the first output transcript piece
     * that starts it, the transcript pieces go nowhere, and from the next voiced start by the sync rule, the audio is held. When the whole
     * sentence came, the held audio is dropped, and so is the audio until a new text comes: that text goes to the host, and its audio plays
     * from the next voiced start. When the transcript goes another way before the end of the sentence, the skip ends: the dropped text goes
     * to the host and the held audio plays. It sends nothing. A pending skip ends at a thinking or commentary text that holds [sentence], and
     * at the end of the first reply after the one that is open now, because the voice says a waiting sentence in the open reply or the next.
     * A reply end inside a sentence ends neither a started skip nor a reply of this count, unless a barge-in cut that reply.
     */
    fun skip(sentence: String) { skipSentence = sentence; skipSpans = replyJob?.isActive == true || !replyEnded }

    /** Ends a started skip, with no text and no audio of it. */
    private fun endSkip() {
        skipping = null; skipHeard = ""; skipDropped = ""; skipCovered = false
        skipText = false; skipAudio = false; skipDrop = false; skipResume = false; skipHeld.clear()
    }

    /** A transcript piece of a started skip: it goes nowhere while it is part of the skipped sentence (fix list item 2a and 2b). */
    private fun skipPiece(delta: String, startMs: Long, endMs: Long, replyStart: Boolean) {
        val sentence = checkNotNull(skipping)
        skipHeard += delta; skipDropped += delta
        val rest = when {
            skipCovered -> delta
            coversSentence(skipHeard, sentence) -> { skipCovered = true; skipHeld.clear(); afterCovered(skipHeard, sentence) }
            startsSentence(skipHeard, sentence) -> return
            else -> {
                // The reply went another way before the end of the sentence: its text goes to the host whole, and then its held audio plays.
                val held = skipHeld.toList()
                val dropped = skipDropped; val from = skipStartMs; val start = skipReplyStart
                endSkip()
                said(dropped, from, endMs, start)
                live?.takeIf { held.isNotEmpty() && !dropping }?.let { queue(it, held, SystemClock.elapsedRealtimeNanos(), queuedFrames, onset = true) }
                return
            }
        }
        if (plain(rest).isEmpty()) return
        // A new text after the sentence goes to the host, and its audio plays from the next voiced start. When the sentence opened the
        // reply, the host got nothing of the reply before, so this text starts it.
        skipText = false; skipResume = true
        said(rest, startMs, endMs, replyStart || skipReplyStart)
    }

    /** A piece of the voice for the strip line and the host. */
    private fun said(text: String, startMs: Long, endMs: Long, replyStart: Boolean) {
        if (text.isEmpty()) return
        speech = if (replyStart) text else speech + text
        host?.outputText(text, startMs, endMs, replyStart, SystemClock.elapsedRealtimeNanos())
    }

    /** The frames that the speaker played in this session, with the queued frames that a flush skipped. */
    private fun playedFrames() = (live?.playedFrames() ?: 0L) + skippedFrames.get()

    /** Quiet context for a typed turn (spec 5.7.2). [delegationId]: the hand-off of a turn that the host already answered (plan "one scene", K3). */
    fun thinking(text: String, delegationId: String? = null) {
        skipSentence?.let { if (it in text) skipSentence = null }
        if (!started) return
        val id = LiveProtocol.newId()
        tolerated += id
        texts++
        send(LiveProtocol.thinking(id, text, delegationId))
        countSilence()
    }

    /** The scene item of the classifier (K2). Its client event id, or null when the classifier is off. */
    fun createItem(text: String): String? {
        if (!started || !classifierOn) return null
        val id = LiveProtocol.newId()
        classifierIds += id
        send(LiveProtocol.itemCreate(id, text))
        return id
    }

    fun deleteItem(itemId: String) {
        if (!started || !classifierOn) return
        val id = LiveProtocol.newId()
        classifierIds += id
        send(LiveProtocol.itemDelete(id, itemId))
    }

    /**
     * K8: an error or a close before `session.started`, after a start with triggers, starts the session once more with no triggers. The
     * classifier stays off for the app run only when that start reaches `session.started`. When it fails too, the mode ends. A refused upgrade with an HTTP status cannot come from the triggers, so it ends as before.
     */
    private fun retryWithoutTriggers(socketOpen: Boolean): Boolean {
        val host = host
        if (started || triggers == null || host == null) return false
        generation++
        replyJob?.cancel(); cancelOnset()
        connection?.let { if (socketOpen) it.send(LiveProtocol.close()); it.close() }
        connection = null
        live?.close(); live = null
        active = false
        start(host, firstSpeech, null)
        retried = true
        return true
    }

    /** An error of a scene item or of a decision: the classifier goes off for this session, and the mode stays on (K8). */
    private fun classifierFailure(event: LiveEvent.Failure): Boolean = triggers != null && (event.clientEventId in classifierIds ||
        listOf(event.code, event.param).any { it.contains("decision", true) || it.contains("trigger", true) })

    /** Ends the session. Without a session, it clears the last end status, as a stop of the one-shot voice clears its status (review I2). */
    fun stop(status: String = "") { if (active) end(status) else { this.status = ""; settingsRequired = false } }

    /** Every end path: sends `session.close` while the socket is open, releases the socket and the audio once, then tells the host (spec 5.8). */
    private fun end(status: String, settingsRequired: Boolean = false, socketOpen: Boolean = true) {
        if (!active) return
        generation++
        active = false; started = false; this.status = status; this.settingsRequired = settingsRequired; userSpeaking = false; level = 0f; waiting = null; tolerated.clear()
        speech = ""
        // An open reply of this session must not count as open in the next one.
        replyJob?.cancel(); held.clear()
        cancelOnset(); skipSentence = null; endSkip()
        playback++
        connection?.let { if (socketOpen) it.send(LiveProtocol.close()); it.close() }
        connection = null
        live?.close(); live = null
        refresh()
        val host = host
        this.host = null
        host?.ended(status, settingsRequired)
    }

    private fun listener(token: Int) = object : LiveListener {
        override fun onText(text: String) { val event = LiveProtocol.parse(text); onMain(token) { handle(event) } }
        override fun onFailure(status: Int?, message: String) = onMain(token) {
            if (status == null) { if (!retryWithoutTriggers(socketOpen = false)) end(string(R.string.live_voice_connection), socketOpen = false) }
            else end(message, providerFailure(AiProvider.OPENAI, status, null, false).code in KEY_ERRORS, socketOpen = false)
        }
        override fun onClosed() = onMain(token) { if (!retryWithoutTriggers(socketOpen = false)) end(string(R.string.live_voice_connection), socketOpen = false) }
    }

    private fun handle(event: LiveEvent) {
        when (event) {
            is LiveEvent.Started -> if (!started) {
                started = true
                if (retried) { retried = false; host?.classifierOff(appRun = true) }
                if (captureMuted) sendMute(true)
                // Both counts start when GPT-Live can hear the user, so they do not race the connection.
                heardMs = SystemClock.uptimeMillis()
                countSilence()
                if (firstSpeech) firstJob = after(timing.firstSpeechMs) { end(string(R.string.live_voice_no_first_speech)) }
                refresh()
            }
            is LiveEvent.OutputAudio -> output(event.samples)
            is LiveEvent.InputTranscript -> {
                // The voice can begin its reply, or go on with a narration, before the last caption piece of a phrase comes. A piece of
                // the same speech run (SPEECH_RUN_GAP_MS) is no new turn of the host.
                val joins = endedByOutput && lastInputEndMs?.let { event.startMs - it <= SPEECH_RUN_GAP_MS } == true
                if (!inputOpen && event.delta.isNotBlank()) { inputOpen = true; endedByOutput = false; if (!joins) host?.inputStarted() }
                if (event.delta.isNotBlank()) lastInputEndMs = event.endMs
                host?.inputText(event.delta, event.eventId)
                refresh()
                // A delta arrives after its own end, so its phrase began at or before this time (ruling R11).
                val began = SystemClock.uptimeMillis() - (event.endMs - event.startMs)
                transcript.input(event.delta, event.startMs, event.endMs, playbackSeenMs >= began || interrupted)
                interrupted = false
                gapJob?.cancel()
                gapJob = after(timing.turnGapMs) { inputOpen = false; endedByOutput = false; turn(transcript.endTurn()) }
            }
            is LiveEvent.OutputTranscript -> {
                host?.outputPiece(event.eventId)
                val replyStart = replyEnded
                // A pending skip starts at the first piece that starts its sentence, also inside the reply before it (decision 15). The
                // transcript runs ahead of the audio, and a reply ends by its audio, so a reply start can fall inside a sentence: the words
                // since the last sentence end stay, and a piece at a reply start is also tried alone, so older words block no skip.
                val before = afterSentenceEnd(sinceSentence)
                skipSentence?.let { sentence ->
                    val heard = when {
                        skipping != null -> null
                        startsSentence(before + event.delta, sentence) -> before
                        replyStart && startsSentence(event.delta, sentence) -> ""
                        else -> null
                    }
                    if (heard != null) {
                        skipSentence = null; skipping = sentence; skipHeard = heard; skipDropped = ""; skipCovered = false
                        skipStartMs = event.startMs; skipReplyStart = replyStart; skipText = true; skipAudio = true
                    }
                }
                sinceSentence = afterSentenceEnd(sinceSentence + event.delta)
                // An empty delta must not empty the line, or the strip would lose its place.
                if (event.delta.isNotEmpty()) {
                    replyEnded = false
                    if (skipText) skipPiece(event.delta, event.startMs, event.endMs, replyStart) else said(event.delta, event.startMs, event.endMs, replyStart)
                }
                transcript.output(event.delta, event.startMs, event.endMs)?.let { endedByOutput = true; turn(it) }
            }
            is LiveEvent.Delegation -> delegation(event)
            is LiveEvent.Appended -> host?.appended(event.clientEventId)
            is LiveEvent.Closed -> if (!retryWithoutTriggers(socketOpen = false)) end(string(when (event.reason) {
                "expired" -> R.string.live_voice_expired
                "content" -> R.string.live_voice_content
                else -> R.string.live_voice_connection
            }), socketOpen = false)
            // A rejected append keeps the mode (the fallbacks of spec 5.5.6 and 5.7.2), and so does a rejected mute or unmute, as the
            // capture stops here in any case (R37). The server message can echo part of the key, so it is never shown (spec 5.3.6).
            is LiveEvent.Failure -> when {
                retryWithoutTriggers(socketOpen = true) -> Unit
                // The triggers still run on the server after the first error, so a later classifier error keeps the mode too.
                classifierFailure(event) -> if (classifierOn) { classifierOn = false; host?.classifierOff(appRun = false) }
                event.clientEventId !in tolerated && event.clientEventId !in classifierIds -> end(string(R.string.live_voice_failed))
            }
            is LiveEvent.Decision -> if (classifierOn) host?.decision(event)
            is LiveEvent.ItemCreated -> if (event.clientEventId in classifierIds) host?.itemCreated(event.clientEventId, event.itemId)
            LiveEvent.Other -> Unit
        }
    }

    private fun captured(block: ShortArray) {
        val device = live ?: return
        // Blocks that were on the way at a mute.
        if (captureMuted) return
        val now = SystemClock.uptimeMillis()
        // A touch on the stage: zero samples at the usual cadence. The mute state and the server stay as they are.
        val touched = touchDownMs?.let { now - it < TOUCH_HOLD_MS } == true || now < touchOpenMs
        val heard = if (touched) ShortArray(block.size) else block
        val playing = speaking()
        val plays = device.playbackActive
        // Barge-in: the user's voice over the playback stops it at once (spec 5.4.5). Only the device flag teaches the echo (R35), and
        // the zero samples of a touch hold no echo, so they teach nothing: Alphy's echo after the touch stays under the threshold.
        if (detector.accept(heard, playing, plays && !touched) && playing) {
            // A barge-in in a dry pause did not start over Alphy's voice, so only the device flag marks the next turn (R11).
            if (plays) interrupted = true
            flush()
        }
        if (detector.userSpeaking != userSpeaking) {
            userSpeaking = detector.userSpeaking
            if (userSpeaking) { firstSpeech = false; firstJob?.cancel() }
            heardMs = SystemClock.uptimeMillis()
            countSilence()
        }
        level = device.level
        // GPT-Live hears the user also during a barge-in. Nothing goes out before session.started (spec 5.3.2).
        if (started) connection?.let { socket -> sender.launch { socket.send(LiveProtocol.audio(heard)) } }
        refresh()
    }

    /**
     * GPT-Live streams output audio also between replies, so only voiced chunks start and continue a reply (ruling R24). The
     * playback, the speaking phase, the barge-in check, the output end, and the silence count all follow this one definition.
     */
    private fun output(samples: ShortArray) {
        val device = live ?: return
        // The reply timer runs from the last voiced chunk until the reply ends.
        val open = replyJob?.isActive == true
        // The quiet run before this chunk: silent received frames and a gap in arrival (decision 13).
        val now = SystemClock.elapsedRealtimeNanos()
        // The first chunk of a session follows the quiet before the session, so it is an onset.
        val gapMs = if (lastChunkNanos == 0L) ONSET_QUIET_MS else maxOf(0L, (now - lastChunkNanos) / 1_000_000 - lastChunkMs)
        lastChunkNanos = now
        lastChunkMs = samples.size * 1000L / LIVE_RATE
        while (received.isNotEmpty() && now - received.first().arrivedNanos > RECEIVED_KEEP_MS * 1_000_000) received.removeFirst()
        if (rms(samples) <= LIVE_VOICED_RMS) {
            quietMs += gapMs + lastChunkMs
            if (open) {
                if (!dropping) held += samples
                // Ruling R27: a count of samples, not of arrival time, so network jitter cannot end a reply.
                silentSamples += samples.size
                if (silentSamples >= timing.replyGapMs * LIVE_RATE / 1000) endReply() else stall()
            }
            refresh()
            return
        }
        // The next reply starts at a voiced chunk after the last reply has ended, once the user has stopped (spec 5.4.5).
        if (dropping && !open && !detector.userSpeaking) { dropping = false; interrupted = false }
        val onset = quietMs + gapMs >= ONSET_QUIET_MS
        quietMs = 0
        // A started skip holds or drops the audio from the next voiced start (decision 15). After a new text, the onset that follows a
        // dropped start plays, so a transcript that runs ahead of the audio drops the start of the sentence first.
        // shortcut: a new text whose audio comes before its transcript piece loses its words until the next onset; measure it on a phone.
        if (onset && skipResume && skipDrop) endSkip()
        else if (onset && skipAudio) skipDrop = true
        if (skipDrop) {
            if (!skipCovered) skipHeld += samples
            held.clear()
            silentSamples = 0
            stall()
            refresh()
            return
        }
        // shortcut: a chunk that the player has already taken can still play after a flush; lock it if the echo check hears it.
        if (!dropping) {
            // shortcut: ruling R26 writes the held pause only while the device still plays. A device that has run dry played the
            // pause as its underrun, so the pause is shorter by the backlog; measure the pause on a phone if replies sound rushed.
            // The phase and the barge-in check follow the open reply (R34), so a dry device changes neither.
            val chunks = if (device.playbackActive) held + samples else listOf(samples)
            queue(device, chunks, now, queuedFrames + chunks.dropLast(1).sumOf { it.size.toLong() }, onset)
        }
        held.clear()
        silentSamples = 0
        stall()
        refresh()
    }

    /** Queues [chunks] for the speaker, with the voiced chunk at [frame] for the sync rule (decision 13). */
    private fun queue(device: LiveAudio, chunks: List<ShortArray>, now: Long, frame: Long, onset: Boolean) {
        val epoch = playback
        received.addLast(Received(now, frame, onset))
        queuedFrames += chunks.sumOf { it.size.toLong() }
        player.launch { for (chunk in chunks) if (epoch == playback) device.play(chunk) else skippedFrames.addAndGet(chunk.size.toLong()) }
    }

    /** Ends the reply when the stream stops: [LiveTiming.replyGapMs] after its last chunk of any kind (ruling R27). */
    private fun stall() {
        replyJob?.cancel()
        replyJob = after(timing.replyGapMs) { endReply() }
    }

    /** The one end of a reply, at the silent count or at the stall timer, whichever comes first. */
    private fun endReply() {
        replyJob?.cancel(); replyJob = null
        held.clear()
        replyEnded = true
        // A reply ends by its audio while the transcript runs ahead, so a reply end can fall inside a sentence. A started skip ends here only
        // when its whole sentence came or a barge-in cut the reply. Only a cut reply or a reply end at a sentence end counts for a pending skip.
        val counts = dropping || plain(sinceSentence).isEmpty()
        if (skipCovered || dropping) endSkip()
        // A pending skip waits through the reply that was open at the skip, then through one more reply (fix list item 2c).
        if (skipSentence != null && counts) { if (skipSpans) skipSpans = false else skipSentence = null }
        host?.outputEnded(transcript.outputEndMs)
        heardMs = SystemClock.uptimeMillis()
        countSilence()
        refresh()
    }

    /**
     * Ruling R34: a reply is open (R27) or the device still plays. The barge-in check and the silence count use it. The phase uses it
     * outside a dropped reply.
     */
    private fun speaking() = replyJob?.isActive == true || live?.playbackActive == true

    private fun delegation(event: LiveEvent.Delegation) {
        waiting?.let { handOff(it, transcript.request(it.offsetMs)) }
        val request = transcript.request(event.offsetMs)
        if (request.isNotEmpty()) { handOff(event, request); return }
        // The input transcript can come after the delegation (spec 5.5.2).
        waiting = event
        after(timing.requestWaitMs) { if (waiting === event) handOff(event, transcript.request(event.offsetMs)) }
    }

    /** The turn of the request reaches the host before its hand-off. */
    private fun handOff(event: LiveEvent.Delegation, request: String) {
        waiting = null
        inputOpen = false; endedByOutput = false
        turn(transcript.endTurn())
        host?.delegation(event.id, request)
    }

    private fun turn(turn: UserTurn?) { if (turn != null) { inputOpen = false; host?.turn(turn) } }

    /** Stops the playback at once and drops the rest of the current reply. */
    private fun flush() {
        // shortcut: the frames of a write that the flush cuts short count in full; the target of a later onset can be one chunk early.
        playback++
        dropping = true
        held.clear()
        live?.flushPlayback()
    }

    private fun applyMute() {
        val muted = userMuted || awayMuted
        if (!active || muted == captureMuted) return
        captureMuted = muted
        // The microphone button and the return to Alphy call this on the main thread, so a microphone that does not start again
        // ends the mode instead of the app (review I1).
        try { live?.setCaptureMuted(muted) } catch (_: Exception) { end(string(R.string.live_voice_failed)); return }
        if (started) sendMute(muted)
        if (muted) {
            // A muted capture has no blocks, so the detector would keep the last state of the user's voice.
            detector = BargeInDetector()
            level = 0f
            if (userSpeaking) { userSpeaking = false; countSilence() }
        }
        refresh()
    }

    /** The server mute of spec 5.4.6. Its id joins [tolerated] (ruling R37). */
    private fun sendMute(muted: Boolean) {
        val id = LiveProtocol.newId()
        tolerated += id
        send(if (muted) LiveProtocol.mute(id) else LiveProtocol.unmute(id))
    }

    /**
     * Counts the 60 s without user speech again. At its end, Alphy's own reply (an open reply or playback, ruling R34) restarts the
     * count. A running hand-off also restarts it until [LiveTiming.handOffHoldMs] after the last user speech or Alphy output (ruling
     * R38). The output end, a commentary, and a thinking text also restart it, because the user needs time to hear a result (spec
     * 5.8, ruling R12). The bridge restarts it when a screen comes on the stage, which can come with no text (O1 of 2026-11-21).
     */
    fun countSilence() {
        silenceJob?.cancel()
        if (!started || userSpeaking) return
        silenceJob = after(timing.silenceMs) {
            val now = SystemClock.uptimeMillis()
            if (speaking()) heardMs = now
            val handOff = host?.handOffRunning() == true && now - heardMs < timing.handOffHoldMs
            if (speaking() || handOff) countSilence() else end(string(R.string.live_voice_no_speech))
        }
    }

    private fun refresh() {
        if (live?.playbackActive == true) playbackSeenMs = SystemClock.uptimeMillis()
        phase = when {
            !active -> LivePhase.IDLE
            !started -> LivePhase.CONNECTING
            captureMuted -> LivePhase.MUTED
            // The user hears nothing of a dropped reply (review m4).
            speaking() && !dropping -> LivePhase.SPEAKING
            else -> LivePhase.LISTENING
        }
    }

    private fun send(text: String) { connection?.send(text) }

    /** Runs [action] on the main thread unless the session of [token] has ended. */
    private fun onMain(token: Int, action: () -> Unit) { scope.launch { if (token == generation) action() } }

    private fun after(ms: Long, action: () -> Unit): Job {
        val token = generation
        return scope.launch { delay(ms); if (token == generation) action() }
    }

    private fun string(id: Int) = context.getString(id)
}

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

internal enum class LivePhase { IDLE, CONNECTING, LISTENING, SPEAKING, MUTED }

/** The timers of spec 5.8 and 5.4.5. A user turn also ends after [turnGapMs] without input transcript deltas. */
internal data class LiveTiming(val startedMs: Long = 10_000, val silenceMs: Long = 60_000, val firstSpeechMs: Long = 10_000,
    val requestWaitMs: Long = 500, val turnGapMs: Long = 1_200, val replyGapMs: Long = 300)

/** The chat side of the voice mode. The session calls it on the main thread only. */
internal interface LiveVoiceHost {
    fun history(): List<Message>
    /** A hand-off. [request] is blank when no request text arrived within [LiveTiming.requestWaitMs] (spec 5.5.2). */
    fun delegation(id: String, request: String)
    fun turn(turn: UserTurn)
    fun handOffRunning(): Boolean
    /** At `session.commentary.appended` for the client event id that [LiveVoiceSession.commentary] returned. */
    fun appended(eventId: String)
    /** Output audio has been silent for [LiveTiming.replyGapMs]. [endMs] is [LiveTranscript.outputEndMs]. */
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
    /** The end status. It is empty while the session runs. */
    var status by mutableStateOf(""); private set
    var userSpeaking by mutableStateOf(false); private set

    private var generation = 0
    private var host: LiveVoiceHost? = null
    private var connection: LiveConnection? = null
    private var live: LiveAudio? = null
    private var transcript = LiveTranscript()
    private var detector = BargeInDetector()
    private var started = false
    private var firstSpeech = false
    private var userMuted = false
    private var awayMuted = false
    private var captureMuted = false
    /** After a barge-in or a flush, the rest of the current reply is dropped (spec 5.4.5). */
    private var dropping = false
    /** A barge-in emptied the playback, so the next user turn still started over Alphy's voice. */
    private var interrupted = false
    private var lastOutputMs = 0L
    private var waiting: LiveEvent.Delegation? = null
    /** The client event ids of the commentary and thinking appends of this session. */
    private val appends = HashSet<String>()
    private var silenceJob: Job? = null
    private var firstJob: Job? = null
    private var gapJob: Job? = null
    private var replyJob: Job? = null
    // The playback worker reads it: a flush or an end drops the chunks that still wait for play().
    @Volatile private var playback = 0
    private val sender = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val player = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

    /** [firstSpeech]: an assistant button start, which ends after [LiveTiming.firstSpeechMs] without speech (spec 5.11.2). */
    fun start(host: LiveVoiceHost, firstSpeech: Boolean = false) {
        if (active) return
        val token = ++generation
        this.host = host
        this.firstSpeech = firstSpeech
        transcript = LiveTranscript(); detector = BargeInDetector(); waiting = null; appends.clear()
        started = false; userMuted = false; awayMuted = false; captureMuted = false; dropping = false; interrupted = false; lastOutputMs = 0
        active = true; status = ""; phase = LivePhase.CONNECTING
        scope.launch {
            try {
                // The account read can reach the key store.
                val account = withContext(Dispatchers.IO) { account() }
                if (token != generation) return@launch
                if (account == null || account.provider != AiProvider.OPENAI) { end(string(R.string.live_voice_openai_only), settingsRequired = true); return@launch }
                val url = liveUrl(endpoints)
                val device = audio().also { live = it }
                if (captureMuted) device.setCaptureMuted(true)
                if (!device.open({ block -> onMain(token) { captured(block) } }, { onMain(token) { end(string(R.string.live_voice_focus_lost)) } })) {
                    end(string(R.string.live_voice_focus_lost)); return@launch
                }
                connection = transport.connect(url, account.key, listener(token)).also { it.send(LiveProtocol.start(host.history())) }
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

    /** Sends a hand-off result and returns its client event id. After the end it sends nothing, as hand-offs outlive it (spec 5.5.9). */
    fun commentary(delegationId: String, text: String): String {
        val id = LiveProtocol.newId()
        if (started) { appends += id; send(LiveProtocol.commentary(id, delegationId, text)); countSilence() }
        return id
    }

    /** Quiet context for a typed turn (spec 5.7.2). */
    fun thinking(text: String) {
        if (!started) return
        val id = LiveProtocol.newId()
        appends += id
        send(LiveProtocol.thinking(id, text))
        countSilence()
    }

    fun stop(status: String = "") = end(status)

    /** Every end path: sends `session.close` while the socket is open, releases the socket and the audio once, then tells the host (spec 5.8). */
    private fun end(status: String, settingsRequired: Boolean = false, socketOpen: Boolean = true) {
        if (!active) return
        generation++
        active = false; started = false; this.status = status; userSpeaking = false; level = 0f; waiting = null; appends.clear()
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
            if (status == null) end(string(R.string.live_voice_connection), socketOpen = false)
            else end(message, providerFailure(AiProvider.OPENAI, status, null, false).code in KEY_ERRORS, socketOpen = false)
        }
        override fun onClosed() = onMain(token) { end(string(R.string.live_voice_connection), socketOpen = false) }
    }

    private fun handle(event: LiveEvent) {
        when (event) {
            is LiveEvent.Started -> if (!started) {
                started = true
                if (captureMuted) send(LiveProtocol.mute(LiveProtocol.newId()))
                // Both counts start when GPT-Live can hear the user, so they do not race the connection.
                countSilence()
                if (firstSpeech) firstJob = after(timing.firstSpeechMs) { end(string(R.string.live_voice_no_first_speech)) }
                refresh()
            }
            is LiveEvent.OutputAudio -> output(event.samples)
            is LiveEvent.InputTranscript -> {
                transcript.input(event.delta, event.startMs, event.endMs, live?.playbackActive == true || interrupted)
                interrupted = false
                gapJob?.cancel()
                gapJob = after(timing.turnGapMs) { turn(transcript.endTurn()) }
            }
            is LiveEvent.OutputTranscript -> turn(transcript.output(event.delta, event.startMs, event.endMs))
            is LiveEvent.Delegation -> delegation(event)
            is LiveEvent.Appended -> host?.appended(event.clientEventId)
            is LiveEvent.Closed -> end(string(when (event.reason) {
                "expired" -> R.string.live_voice_expired
                "content" -> R.string.live_voice_content
                else -> R.string.live_voice_connection
            }), socketOpen = false)
            // A rejected append keeps the mode (the fallbacks of spec 5.5.6 and 5.7.2). The server message can echo part of the key,
            // so it is never shown (spec 5.3.6).
            is LiveEvent.Failure -> if (event.clientEventId !in appends) end(string(R.string.live_voice_failed))
            LiveEvent.Other -> Unit
        }
    }

    private fun captured(block: ShortArray) {
        val device = live ?: return
        // Blocks that were on the way at a mute.
        if (captureMuted) return
        val playing = device.playbackActive
        // Barge-in: the user's voice over the playback stops it at once (spec 5.4.5).
        if (detector.accept(block, playing) && playing) { flush(); interrupted = true }
        if (detector.userSpeaking != userSpeaking) {
            userSpeaking = detector.userSpeaking
            if (userSpeaking) { firstSpeech = false; firstJob?.cancel() }
            countSilence()
        }
        level = device.level
        // GPT-Live hears the user also during a barge-in. Nothing goes out before session.started (spec 5.3.2).
        if (started) connection?.let { socket -> sender.launch { socket.send(LiveProtocol.audio(block)) } }
        refresh()
    }

    private fun output(samples: ShortArray) {
        val device = live ?: return
        val now = SystemClock.uptimeMillis()
        // The next reply starts at output audio after replyGapMs of output silence, once the user has stopped (spec 5.4.5).
        if (dropping && !detector.userSpeaking && now - lastOutputMs >= timing.replyGapMs) { dropping = false; interrupted = false }
        lastOutputMs = now
        // shortcut: a chunk that the player has already taken can still play after a flush; lock it if the echo check hears it.
        if (!dropping) { val epoch = playback; player.launch { if (epoch == playback) device.play(samples) } }
        replyJob?.cancel()
        replyJob = after(timing.replyGapMs) { host?.outputEnded(transcript.outputEndMs) }
        refresh()
    }

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
        turn(transcript.endTurn())
        host?.delegation(event.id, request)
    }

    private fun turn(turn: UserTurn?) { if (turn != null) host?.turn(turn) }

    /** Stops the playback at once and drops the rest of the current reply. */
    private fun flush() {
        playback++
        dropping = true
        live?.flushPlayback()
    }

    private fun applyMute() {
        val muted = userMuted || awayMuted
        if (!active || muted == captureMuted) return
        captureMuted = muted
        live?.setCaptureMuted(muted)
        if (started) send(if (muted) LiveProtocol.mute(LiveProtocol.newId()) else LiveProtocol.unmute(LiveProtocol.newId()))
        if (muted) {
            // A muted capture has no blocks, so the detector would keep the last state of the user's voice.
            detector = BargeInDetector()
            level = 0f
            if (userSpeaking) { userSpeaking = false; countSilence() }
        }
        refresh()
    }

    /**
     * Counts the 60 s without user speech again. At its end, a running hand-off restarts the count. A commentary or a thinking
     * text also restarts it, because the user needs time to hear a result (spec 5.8).
     */
    private fun countSilence() {
        silenceJob?.cancel()
        if (!started || userSpeaking) return
        silenceJob = after(timing.silenceMs) { if (host?.handOffRunning() == true) countSilence() else end(string(R.string.live_voice_no_speech)) }
    }

    private fun refresh() {
        phase = when {
            !active -> LivePhase.IDLE
            !started -> LivePhase.CONNECTING
            captureMuted -> LivePhase.MUTED
            live?.playbackActive == true -> LivePhase.SPEAKING
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

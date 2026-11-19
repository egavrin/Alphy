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

    /** [firstSpeech]: an assistant button start, which ends after [LiveTiming.firstSpeechMs] without speech (spec 5.11.2). */
    fun start(host: LiveVoiceHost, firstSpeech: Boolean = false) {
        if (active) return
        val token = ++generation
        this.host = host
        this.firstSpeech = firstSpeech
        transcript = LiveTranscript(); detector = BargeInDetector(); waiting = null; tolerated.clear()
        started = false; userMuted = false; awayMuted = false; captureMuted = false; dropping = false; interrupted = false
        playbackSeenMs = Long.MIN_VALUE; held.clear()
        active = true; status = ""; phase = LivePhase.CONNECTING
        scope.launch {
            try {
                // The account read can reach the key store.
                val account = withContext(Dispatchers.IO) { account() }
                if (token != generation) return@launch
                if (account == null || account.provider != AiProvider.OPENAI) { end(string(R.string.live_voice_openai_only), settingsRequired = true); return@launch }
                val url = liveUrl(endpoints)
                // Before the connect, so a failed read leaves no open socket (review m9).
                val first = LiveProtocol.start(host.history())
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

    /** Sends a hand-off result and returns its client event id. After the end it sends nothing, as hand-offs outlive it (spec 5.5.9). */
    fun commentary(delegationId: String, text: String): String {
        val id = LiveProtocol.newId()
        if (started) { tolerated += id; send(LiveProtocol.commentary(id, delegationId, text)); countSilence() }
        return id
    }

    /** Quiet context for a typed turn (spec 5.7.2). */
    fun thinking(text: String) {
        if (!started) return
        val id = LiveProtocol.newId()
        tolerated += id
        send(LiveProtocol.thinking(id, text))
        countSilence()
    }

    /** Ends the session. Without a session, it clears the last end status, as a stop of the one-shot voice clears its status (review I2). */
    fun stop(status: String = "") { if (active) end(status) else { this.status = ""; settingsRequired = false } }

    /** Every end path: sends `session.close` while the socket is open, releases the socket and the audio once, then tells the host (spec 5.8). */
    private fun end(status: String, settingsRequired: Boolean = false, socketOpen: Boolean = true) {
        if (!active) return
        generation++
        active = false; started = false; this.status = status; this.settingsRequired = settingsRequired; userSpeaking = false; level = 0f; waiting = null; tolerated.clear()
        // An open reply of this session must not count as open in the next one.
        replyJob?.cancel(); held.clear()
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
                if (captureMuted) sendMute(true)
                // Both counts start when GPT-Live can hear the user, so they do not race the connection.
                heardMs = SystemClock.uptimeMillis()
                countSilence()
                if (firstSpeech) firstJob = after(timing.firstSpeechMs) { end(string(R.string.live_voice_no_first_speech)) }
                refresh()
            }
            is LiveEvent.OutputAudio -> output(event.samples)
            is LiveEvent.InputTranscript -> {
                refresh()
                // A delta arrives after its own end, so its phrase began at or before this time (ruling R11).
                val began = SystemClock.uptimeMillis() - (event.endMs - event.startMs)
                transcript.input(event.delta, event.startMs, event.endMs, playbackSeenMs >= began || interrupted)
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
            // A rejected append keeps the mode (the fallbacks of spec 5.5.6 and 5.7.2), and so does a rejected mute or unmute, as the
            // capture stops here in any case (R37). The server message can echo part of the key, so it is never shown (spec 5.3.6).
            is LiveEvent.Failure -> if (event.clientEventId !in tolerated) end(string(R.string.live_voice_failed))
            LiveEvent.Other -> Unit
        }
    }

    private fun captured(block: ShortArray) {
        val device = live ?: return
        // Blocks that were on the way at a mute.
        if (captureMuted) return
        val playing = speaking()
        val plays = device.playbackActive
        // Barge-in: the user's voice over the playback stops it at once (spec 5.4.5). Only the device flag teaches the echo (R35).
        if (detector.accept(block, playing, plays) && playing) {
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
        if (started) connection?.let { socket -> sender.launch { socket.send(LiveProtocol.audio(block)) } }
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
        if (rms(samples) <= LIVE_VOICED_RMS) {
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
        // shortcut: a chunk that the player has already taken can still play after a flush; lock it if the echo check hears it.
        if (!dropping) {
            // shortcut: ruling R26 writes the held pause only while the device still plays. A device that has run dry played the
            // pause as its underrun, so the pause is shorter by the backlog; measure the pause on a phone if replies sound rushed.
            // The phase and the barge-in check follow the open reply (R34), so a dry device changes neither.
            val chunks = if (device.playbackActive) held + samples else listOf(samples)
            val epoch = playback
            player.launch { for (chunk in chunks) if (epoch == playback) device.play(chunk) }
        }
        held.clear()
        silentSamples = 0
        stall()
        refresh()
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
        turn(transcript.endTurn())
        host?.delegation(event.id, request)
    }

    private fun turn(turn: UserTurn?) { if (turn != null) host?.turn(turn) }

    /** Stops the playback at once and drops the rest of the current reply. */
    private fun flush() {
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
     * 5.8, ruling R12).
     */
    private fun countSilence() {
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

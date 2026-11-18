package com.alpha.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** 16 kHz mono PCM16 in both directions (spec 5.3 and 5.4). */
internal const val LIVE_RATE = 16000
/** 20 ms of samples: the size of one capture block. */
internal const val LIVE_BLOCK = 320
/**
 * An output audio chunk above this RMS is voiced. GPT-Live streams near-silent chunks between replies (ruling R24). The live check
 * (spec 6.3) split its output stream into voiced and silent runs with this level.
 */
internal const val LIVE_VOICED_RMS = 0.01

/** The root mean square of PCM16 [samples], 0 to 1. */
internal fun rms(samples: ShortArray): Double {
    var sum = 0.0
    for (sample in samples) { val value = sample / 32768.0; sum += value * value }
    return if (samples.isEmpty()) 0.0 else sqrt(sum / samples.size)
}

/**
 * The `Build.MODEL` values whose loudspeaker echo check passed (spec 6.4). Voice approval (spec 5.5.10) works only on these models.
 * It stays empty until both halves of the check have passed on a real phone.
 */
internal val VOICE_APPROVAL_MODELS: Set<String> = emptySet()

/** The audio path of the live voice mode (spec 5.4). */
internal interface LiveAudio {
    /** Starts capture and playback. Returns false when audio focus is denied. [onCapture] runs on the capture thread, once for each 20 ms block. */
    fun open(onCapture: (ShortArray) -> Unit, onFocusLost: () -> Unit): Boolean
    /** A muted capture is stopped, so the microphone is free. */
    fun setCaptureMuted(muted: Boolean)
    /** Queues [samples] for playback. It blocks while the playback buffer (5 s) is full, so call it from a playback thread. */
    fun play(samples: ShortArray)
    /** Drops the queued audio at once. It also ends a blocked [play]. */
    fun flushPlayback()
    /** The playback buffer still holds audio that the speaker has not played. */
    val playbackActive: Boolean
    /** The level of the last captured block, 0 to 1. */
    val level: Float
    fun close()
}

/** The platform implementation. The caller holds the microphone permission. */
@SuppressLint("MissingPermission")
internal class AndroidLiveAudio(context: Context) : LiveAudio {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private val written = AtomicLong()
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var track: AudioTrack? = null
    // Bumped by each flush, so frames of a write that the flush cut short are not counted.
    private var generation = 0
    private var thread: Thread? = null
    private var opened = false
    private var routed = false
    @Volatile private var muted = false
    @Volatile private var running = false
    @Volatile override var level = 0f; private set

    override fun open(onCapture: (ShortArray) -> Unit, onFocusLost: () -> Unit): Boolean = synchronized(lock) {
        check(!opened) { "The live audio is open." }
        opened = true
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION))
                .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) onFocusLost() }.build()
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { release(); return false }
            focus = request
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            routed = true
            routeToSpeaker()
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION))
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(LIVE_RATE * 2 * 5).build()
            track = newTrack
            check(newTrack.state == AudioTrack.STATE_INITIALIZED) { "Playback initialization failed" }
            newTrack.play()
            val minimum = AudioRecord.getMinBufferSize(LIVE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "PCM16 microphone is unsupported" }
            val newRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setBufferSizeInBytes(maxOf(minimum, LIVE_BLOCK * 2 * 8)).build()
            record = newRecord
            check(newRecord.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(newRecord.audioSessionId)?.also { it.enabled = true }
            if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(newRecord.audioSessionId)?.also { it.enabled = true }
            running = true
            if (!muted) startRecording(newRecord)
            thread = Thread({ captureLoop(newRecord, onCapture) }, "live-audio-capture").also { it.start() }
            true
        } catch (failure: Throwable) { release(); throw failure }
    }

    override fun setCaptureMuted(muted: Boolean) = synchronized(lock) {
        this.muted = muted
        val input = record ?: return@synchronized
        if (muted) { runCatching { input.stop() }; level = 0f } else startRecording(input)
    }

    override fun play(samples: ShortArray) {
        val output = track ?: return
        val before = synchronized(written) { generation }
        val count = output.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        if (count > 0) synchronized(written) { if (generation == before) written.addAndGet(count.toLong()) }
    }

    override fun flushPlayback() {
        val output = track ?: return
        synchronized(written) {
            generation++
            output.pause()
            output.flush()
            output.play()
            // The head position keeps counting after a flush, so the new count of written frames starts at it.
            written.set(head(output))
        }
    }

    override val playbackActive: Boolean
        get() = track?.let { written.get() > head(it) } ?: false  // one read of track: release() can null it

    override fun close() = synchronized(lock) { release() }

    /** Frees everything that [open] acquired. It is safe after a partial [open] and after a close. */
    private fun release() {
        running = false
        record?.let { runCatching { it.stop() } }
        thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }
        thread = null
        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null
        record?.release(); record = null
        track?.let { runCatching { it.stop() }; it.release() }; track = null
        written.set(0)
        level = 0f
        if (routed) {
            audioManager.clearCommunicationDevice()
            audioManager.mode = previousMode
            routed = false
        }
        focus?.let { audioManager.abandonAudioFocusRequest(it) }; focus = null
        opened = false
    }

    private fun startRecording(input: AudioRecord) {
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone recording failed" }
    }

    private fun captureLoop(input: AudioRecord, onCapture: (ShortArray) -> Unit) {
        while (running) {
            if (muted) { Thread.sleep(20); continue }
            val block = ShortArray(LIVE_BLOCK)
            val count = input.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
            // A stop for mute or close ends the read with 0 or an error. The loop then checks its flags.
            if (count != block.size) { Thread.sleep(5); continue }
            level = (rms(block).toFloat() * 12f).coerceIn(0f, 1f)
            onCapture(block)
        }
    }

    /** The communication mode routes to the earpiece by default, so without a headset the built-in speaker is selected. */
    private fun routeToSpeaker() {
        val devices = audioManager.availableCommunicationDevices
        if (devices.any { it.type in HEADSETS }) return
        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audioManager.setCommunicationDevice(it) }
    }

    private fun head(output: AudioTrack) = output.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun attributes(usage: Int) = AudioAttributes.Builder().setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun format(channels: Int) = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(LIVE_RATE).setChannelMask(channels).build()

    private companion object {
        val HEADSETS = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
    }
}

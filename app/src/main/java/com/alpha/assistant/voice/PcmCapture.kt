package com.alpha.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

/** 16 kHz mono 16-bit PCM. [read] blocks until samples arrive. [close] may come from another thread and ends a blocking read. */
internal interface PcmStream {
    fun read(buffer: ShortArray): Int
    fun close()
}

internal fun interface PcmSource { fun open(): PcmStream }

internal const val SAMPLE_RATE = 16000

/** The microphone as spec 12A.2 records it. The caller holds the microphone permission. */
@SuppressLint("MissingPermission")
internal val Microphone = PcmSource {
    val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum > 0) { "PCM16 microphone is unsupported" }
    val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
    try {
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone recording failed" }
    } catch (failure: Throwable) { record.release(); throw failure }
    MicrophoneStream(record)
}

/** [close] stops the recording at once, so the microphone is free while a read still runs. Only the end of that read releases it. */
private class MicrophoneStream(private val record: AudioRecord) : PcmStream {
    private val lock = Any()
    private var reading = false
    private var closed = false
    override fun read(buffer: ShortArray): Int {
        synchronized(lock) { if (closed) return 0; reading = true }
        try { return record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) }
        finally { synchronized(lock) { reading = false; if (closed) record.release() } }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { record.stop() }
            if (!reading) record.release()
        }
    }
}

/**
 * Records one utterance into memory (spec 12A.2, 12A.3): at most 60 s. One-shot recording ends at [finish] or at the limit.
 * A continuous utterance also ends after 1.2 s of silence that follows speech, or after 8 s without speech.
 */
internal class PcmCapture(private val source: PcmSource) {
    @Volatile private var stream: PcmStream? = null
    @Volatile private var finished = false
    @Volatile private var canceled = false

    fun finish() { finished = true; stream?.close() }
    fun cancel() { canceled = true; stream?.close() }

    /** Blocks until the utterance ends. Returns its samples, or null without speech or after [cancel]. */
    suspend fun record(oneShot: Boolean, onLevel: (Float) -> Unit): ShortArray? {
        val samples = ShortArray(MAX_SAMPLES)
        var total = 0
        val detector = SpeechDetector()
        try {
            if (finished || canceled) return null
            val input = source.open()
            stream = input
            try {
                // A close() before the assignment above would not reach this stream.
                if (finished || canceled) return null
                val block = ShortArray(BLOCK_SAMPLES)
                while (total < MAX_SAMPLES && !canceled) {
                    val count = input.read(block)
                    if (count <= 0) { if (finished || canceled) break; error("Microphone read failed: $count") }
                    currentCoroutineContext().ensureActive()
                    val used = minOf(count, MAX_SAMPLES - total)
                    block.copyInto(samples, total, 0, used)
                    total += used
                    onLevel(detector.accept(block, used))
                    if (finished) break
                    if (!oneShot && (detector.ended || !detector.heard && total >= NO_SPEECH_SAMPLES)) break
                }
            } finally { stream = null; input.close() }
        } finally { finished = false }
        // One-shot keeps the rule of the earlier recorder: any sound above the floor is speech.
        if (canceled || !(if (oneShot) detector.sound else detector.heard)) return null
        return samples.copyOf(total)
    }

    private companion object {
        const val BLOCK_SAMPLES = SAMPLE_RATE / 10
        const val MAX_SAMPLES = SAMPLE_RATE * 60
        const val NO_SPEECH_SAMPLES = SAMPLE_RATE * 8
    }
}

/**
 * The end-of-speech detector of spec 12A.3. It depends only on the samples. A block is speech when its level is
 * more than three times the quietest block of the last 10 s, so the threshold follows the background noise.
 * The window is longer than one sentence, so steady speech does not become the background. Digital silence,
 * such as the first buffers of a microphone, is not background noise and stays out of the window.
 */
internal class SpeechDetector {
    private val recent = ArrayDeque<Double>()
    private var silence = 0
    /** A block was speech. */
    var heard = false; private set
    /** A block was above the fixed floor of the earlier recorder. */
    var sound = false; private set
    /** 1.2 s of silence followed speech. */
    val ended get() = heard && silence >= SAMPLE_RATE * 12 / 10

    /** Takes [count] samples of [block] and returns their level for the level bar, 0 to 1. */
    fun accept(block: ShortArray, count: Int): Float {
        var sum = 0.0
        for (index in 0 until count) { val value = block[index] / 32768.0; sum += value * value }
        val rms = sqrt(sum / count)
        if (rms >= DIGITAL_SILENCE) {
            recent.addLast(rms)
            if (recent.size > WINDOW_BLOCKS) recent.removeFirst()
        }
        if (rms > FLOOR) sound = true
        if (rms > maxOf(FLOOR, (recent.minOrNull() ?: 0.0) * 3)) { heard = true; silence = 0 }
        else if (heard) silence += count
        return (rms.toFloat() * 12f).coerceIn(0f, 1f)
    }

    private companion object {
        const val FLOOR = 0.004
        const val WINDOW_BLOCKS = 100
        // -80 dBFS: a live microphone always records more than this.
        const val DIGITAL_SILENCE = 0.0001
    }
}

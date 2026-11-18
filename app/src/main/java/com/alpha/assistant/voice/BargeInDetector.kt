package com.alpha.assistant.voice

import kotlin.math.sqrt

/**
 * The local barge-in detector of spec 5.4.5, on the echo-cancelled microphone blocks. A block is speech when its level is
 * more than three times the quietest block of the last 10 s, as in [SpeechDetector]. While playback has audio, the threshold
 * is also at least twice the mean level of the playback blocks that were not speech, so residual echo stays below it.
 */
internal class BargeInDetector(private val minSpeechMs: Int = 200) {
    private val recent = ArrayDeque<Double>()
    private var echoMean = 0.0
    private var speechMs = 0

    /** The user's voice has lasted at least [minSpeechMs] and still goes on. */
    val userSpeaking get() = speechMs >= minSpeechMs

    /** Takes one block. Returns true at the block where the user's voice has lasted [minSpeechMs]. */
    fun accept(block: ShortArray, playbackActive: Boolean): Boolean {
        var sum = 0.0
        for (sample in block) { val value = sample / 32768.0; sum += value * value }
        val rms = sqrt(sum / block.size)
        if (rms >= DIGITAL_SILENCE) {
            recent.addLast(rms)
            if (recent.size > WINDOW_BLOCKS) recent.removeFirst()
        }
        var threshold = maxOf(FLOOR, (recent.minOrNull() ?: 0.0) * 3)
        if (playbackActive) threshold = maxOf(threshold, echoMean * 2)
        val speech = rms > threshold
        if (playbackActive && !speech) echoMean = if (echoMean == 0.0) rms else echoMean + (rms - echoMean) * ECHO_SMOOTHING
        val before = speechMs
        speechMs = if (speech) speechMs + block.size * 1000 / LIVE_RATE else 0
        return before < minSpeechMs && speechMs >= minSpeechMs
    }

    private companion object {
        const val FLOOR = 0.004
        const val WINDOW_BLOCKS = 500
        // -80 dBFS: a live microphone always records more than this.
        const val DIGITAL_SILENCE = 0.0001
        // About 1 s of 20 ms blocks.
        const val ECHO_SMOOTHING = 0.02
    }
}

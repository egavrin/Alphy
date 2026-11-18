package com.alpha.assistant.voice

import kotlin.math.sqrt

/**
 * The local barge-in detector of spec 5.4.5, on the echo-cancelled microphone blocks. A block is speech when its level is
 * more than three times the quietest block of the last 10 s, as in [SpeechDetector]. Each playback period starts with a grace
 * period of [GRACE_MS], in which the blocks only teach the echo level and never count as speech. After it, the threshold is also
 * at least twice the echo level, which keeps learning from the blocks that are not speech. The echo level is forgotten when playback stops.
 */
internal class BargeInDetector(private val minSpeechMs: Int = 200) {
    private val recent = ArrayDeque<Double>()
    private var echoMean = 0.0
    private var speechMs = 0
    private var graceMs = 0

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
        val blockMs = block.size * 1000 / LIVE_RATE
        if (!playbackActive) { echoMean = 0.0; graceMs = 0 }
        else if (graceMs < GRACE_MS) {
            graceMs += blockMs
            // The echo ramps up after playback starts, so the grace keeps the loudest block, not a slow average.
            echoMean = maxOf(echoMean, rms)
            speechMs = 0
            return false
        }
        var threshold = maxOf(FLOOR, (recent.minOrNull() ?: 0.0) * 3)
        if (playbackActive) threshold = maxOf(threshold, echoMean * 2)
        val speech = rms > threshold
        if (playbackActive && !speech) echoMean += (rms - echoMean) * ECHO_SMOOTHING
        val before = speechMs
        speechMs = if (speech) speechMs + blockMs else 0
        return before < minSpeechMs && speechMs >= minSpeechMs
    }

    private companion object {
        const val GRACE_MS = 240
        const val FLOOR = 0.004
        const val WINDOW_BLOCKS = 500
        // -80 dBFS: a live microphone always records more than this.
        const val DIGITAL_SILENCE = 0.0001
        // About 1 s of 20 ms blocks.
        const val ECHO_SMOOTHING = 0.02
    }
}

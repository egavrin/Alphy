package com.alpha.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The local barge-in detector of spec 5.4.5 on synthetic blocks. A block has random signs and exactly the stated RMS. */
class BargeInDetectorTest {
    private val random = Random(7)

    private fun block(rms: Double) = ShortArray(LIVE_BLOCK) { ((if (random.nextBoolean()) rms else -rms) * Short.MAX_VALUE).toInt().toShort() }

    @Test fun silenceNeverFires() {
        val detector = BargeInDetector()
        repeat(500) { assertFalse(detector.accept(block(0.002), playbackActive = false)) }
    }

    @Test fun speechFiresAfter200Ms() {
        val detector = BargeInDetector()
        repeat(50) { detector.accept(block(0.002), playbackActive = false) }
        repeat(9) { assertFalse("block ${it + 1}", detector.accept(block(0.1), playbackActive = false)) }
        assertTrue(detector.accept(block(0.1), playbackActive = false))
        assertTrue(detector.userSpeaking)
    }

    @Test fun residualEchoDoesNotFire() {
        val detector = BargeInDetector()
        repeat(250) { assertFalse(detector.accept(block(0.02), playbackActive = true)) }
        assertFalse(detector.userSpeaking)
    }

    @Test fun speechOverEchoFires() {
        val detector = BargeInDetector()
        repeat(50) { assertFalse(detector.accept(block(0.02), playbackActive = true)) }
        val fired = (1..10).any { detector.accept(block(0.15), playbackActive = true) }
        assertTrue(fired)
    }
}

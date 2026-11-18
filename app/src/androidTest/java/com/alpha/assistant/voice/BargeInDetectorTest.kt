package com.alpha.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The local barge-in detector of spec 5.4.5 on synthetic blocks. A block has random signs and exactly the stated RMS. */
class BargeInDetectorTest {
    private val random = Random(7)

    private fun block(rms: Double) = random.pcmBlock(rms)

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

    @Test fun echoAfterAQuietRoomDoesNotFireAndSpeechOverItDoes() {
        val detector = BargeInDetector()
        repeat(100) { assertFalse(detector.accept(block(0.002), playbackActive = false)) }
        repeat(250) { assertFalse(detector.accept(block(0.02), playbackActive = true)) }
        assertTrue((1..10).any { detector.accept(block(0.15), playbackActive = true) })
    }

    @Test fun echoLevelResetsAfterPlaybackStops() {
        val detector = BargeInDetector()
        repeat(100) { detector.accept(block(0.02), playbackActive = true) }
        repeat(50) { detector.accept(block(0.002), playbackActive = false) }
        // A quieter reply: a stale echo level of 0.02 would put the threshold at 0.04 and hide this speech.
        repeat(50) { assertFalse(detector.accept(block(0.005), playbackActive = true)) }
        assertTrue((1..10).any { detector.accept(block(0.03), playbackActive = true) })
    }

    /** Ruling R35: the device flag alone teaches the echo level, so a dry pause of an open reply keeps it. */
    @Test fun dryPauseKeepsTheEchoLevel() {
        val detector = BargeInDetector()
        repeat(100) { detector.accept(block(0.002), playbackActive = false) }
        repeat(100) { assertFalse(detector.accept(block(0.02), playbackActive = true, devicePlays = true)) }
        // 2 s of the open reply while the device is dry: the microphone hears the quiet room.
        repeat(100) { assertFalse(detector.accept(block(0.002), playbackActive = true, devicePlays = false)) }
        repeat(250) { assertFalse(detector.accept(block(0.02), playbackActive = true, devicePlays = true)) }
    }

    /** Ruling R36: the grace counts only while the device plays, so a reply that opens dry learns the echo of its first played block. */
    @Test fun graceCountsOnlyWhileTheDevicePlays() {
        val detector = BargeInDetector()
        repeat(100) { detector.accept(block(0.002), playbackActive = false) }
        // 400 ms of the open reply before the device plays, longer than the 240 ms grace.
        repeat(20) { assertFalse(detector.accept(block(0.002), playbackActive = true, devicePlays = false)) }
        repeat(250) { assertFalse(detector.accept(block(0.02), playbackActive = true, devicePlays = true)) }
    }

    @Test fun echoThatRampsUpDuringTheGraceDoesNotFire() {
        val detector = BargeInDetector()
        repeat(100) { assertFalse(detector.accept(block(0.002), playbackActive = false)) }
        repeat(5) { assertFalse(detector.accept(block(0.002), playbackActive = true)) }
        repeat(250) { assertFalse(detector.accept(block(0.02), playbackActive = true)) }
        assertTrue((1..10).any { detector.accept(block(0.15), playbackActive = true) })
    }
}

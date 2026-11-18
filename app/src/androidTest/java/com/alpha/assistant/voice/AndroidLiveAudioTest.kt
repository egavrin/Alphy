package com.alpha.assistant.voice

import android.Manifest
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * The playback of [AndroidLiveAudio] on the device that runs the test, an emulator included (review C1). The session writes only
 * Alphy's voiced audio, so a short chunk must play at once: on a new track, after an underrun, and after a flush.
 */
class AndroidLiveAudioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audio = AndroidLiveAudio(context)

    @Before fun open() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        assertTrue("Audio focus was denied.", audio.open({ }, { }))
    }

    @After fun close() = audio.close()

    /** Control: 6 s of voice fills the 5 s buffer, so the playback starts with any start threshold. The head must move. */
    @Test fun playbackHeadMoves() {
        val writer = thread(isDaemon = true) { repeat(60) { audio.play(voice(CHUNK)) } }
        writer.join(10_000)
        // A head that never moves keeps the last write blocked. The flush ends it.
        if (writer.isAlive) audio.flushPlayback()
        assertFalse("The playback head did not move, so this device cannot show the start threshold.", writer.isAlive)
        played("6 s of voice", 10_000)
    }

    @Test fun shortChunksPlayAtOnce() {
        audio.play(voice(CHUNK))
        played("A 100 ms chunk on a new track", 1_000)
        SystemClock.sleep(1_000)
        audio.play(voice(CHUNK))
        played("A 100 ms chunk after an underrun", 1_000)
        audio.flushPlayback()
        audio.play(voice(CHUNK))
        played("A 100 ms chunk after a flush", 1_000)
    }

    private fun played(what: String, timeoutMs: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (audio.playbackActive) {
            if (SystemClock.uptimeMillis() > deadline) fail("$what did not play within $timeoutMs ms.")
            SystemClock.sleep(10)
        }
    }

    /** A 400 Hz tone at about -12 dBFS. */
    private fun voice(samples: Int) = ShortArray(samples) { (8_000 * sin(2 * PI * 400 * it / LIVE_RATE)).toInt().toShort() }

    private companion object {
        const val CHUNK = LIVE_RATE / 10
    }
}

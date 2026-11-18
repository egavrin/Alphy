package com.alpha.assistant.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import kotlin.math.sqrt

/**
 * The local half of the device echo check of spec 6.4. It needs a real phone with the loudspeaker, no headset, and a quiet room,
 * because an emulator has no acoustic echo. The captures go to `files/echo-check/<Build.MODEL>-<name>.wav` of the app.
 * Run it with `adb shell am instrument -w -e class com.alpha.assistant.voice.EchoCheckDeviceTest`. The operator pulls the captures.
 */
class EchoCheckDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audio = AndroidLiveAudio(context)
    // Written on the capture thread, read on the test thread.
    @Volatile private var flushAt = -1L
    @Volatile private var flushes = 0
    @Volatile private var spokenAt = -1L

    @Before fun grantMicrophoneOnARealPhone() {
        assumeFalse("An emulator has no acoustic echo.", getprop("ro.kernel.qemu") == "1")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
    }

    @After fun closeAudio() = audio.close()

    @Test fun loudspeakerEchoStaysBelowTheDetector() = echoStaysBelowTheDetector("live-reply", "reply")

    @Test fun proposalEchoStaysBelowTheDetector() = echoStaysBelowTheDetector("live-proposal", "proposal")

    /** The operator speaks from second 4 of the 12 s of playback and measures the speech onset in the capture. */
    @Test fun speechOverPlaybackFlushes() {
        val reply = fixture("live-reply")
        val detector = BargeInDetector()
        val captured = Collections.synchronizedList(mutableListOf<ShortArray>())
        val start = SystemClock.elapsedRealtime()
        assertTrue("Audio focus was denied.", audio.open({ block ->
            captured += block
            val now = SystemClock.elapsedRealtime() - start
            if (now >= SPEAK_AT_MS && spokenAt < 0) { spokenAt = now; Log.i(TAG, "speak now") }
            if (detector.accept(block, audio.playbackActive) && flushes == 0) { flushes++; flushAt = now; audio.flushPlayback() }
        }, onFocusLost = {}))
        val player = Thread { feed(reply) { flushes == 0 } }
        player.start()
        while (SystemClock.elapsedRealtime() - start < RUN_MS) Thread.sleep(50)
        player.join(2000)
        audio.close()
        save("speech-over", captured)
        Log.i(TAG, "speak-now at $spokenAt ms, flush at $flushAt ms after the start of playback, flushes $flushes")
        assertEquals("flushes", 1, flushes)
        assertTrue("The flush came at $flushAt ms, before second 4: echo fired the detector.", flushAt >= SPEAK_AT_MS)
    }

    private fun echoStaysBelowTheDetector(fixture: String, name: String) {
        val samples = fixture(fixture)
        val detector = BargeInDetector()
        val captured = Collections.synchronizedList(mutableListOf<ShortArray>())
        val fired = Collections.synchronizedList(mutableListOf<Long>())
        val start = SystemClock.elapsedRealtime()
        assertTrue("Audio focus was denied.", audio.open({ block ->
            captured += block
            if (detector.accept(block, audio.playbackActive)) fired += SystemClock.elapsedRealtime() - start
        }, onFocusLost = {}))
        feed(samples) { true }
        while (audio.playbackActive) Thread.sleep(20)
        Thread.sleep(500)
        audio.close()
        val played = rms(listOf(samples))
        val heard = rms(synchronized(captured) { captured.toList() }.take(samples.size / LIVE_BLOCK))
        val ratio = save(name, captured, "playback rms $played, capture rms $heard, ratio ${heard / played}")
        Log.i(TAG, "$name: $ratio")
        assertTrue("The detector fired at $fired ms on echo. $ratio", fired.isEmpty())
    }

    /** Plays [samples] in 100 ms pieces while [more] holds. */
    private fun feed(samples: ShortArray, more: () -> Boolean) {
        for (at in samples.indices step LIVE_BLOCK * 5) { if (!more()) return; audio.play(samples.copyOfRange(at, minOf(at + LIVE_BLOCK * 5, samples.size))) }
    }

    private fun fixture(name: String) = wavSamples(InstrumentationRegistry.getInstrumentation().context.assets.open("voice/$name.wav").use { it.readBytes() })

    private fun rms(blocks: List<ShortArray>): Double {
        var sum = 0.0
        var count = 0
        for (block in blocks) for (sample in block) { val value = sample / 32768.0; sum += value * value; count++ }
        return sqrt(sum / count)
    }

    /** Writes the capture as WAV and returns [note]. The caller closed the audio, so no block arrives any more. */
    private fun save(name: String, blocks: List<ShortArray>, note: String = ""): String {
        val snapshot = synchronized(blocks) { blocks.toList() }
        val directory = File(checkNotNull(context.getExternalFilesDir(null)) { "External files directory is unavailable." }, "echo-check").apply { mkdirs() }
        val samples = ShortArray(snapshot.sumOf { it.size }).also { out -> var at = 0; for (block in snapshot) { block.copyInto(out, at); at += block.size } }
        File(directory, "${Build.MODEL}-$name.wav").writeBytes(wavBytes(samples))
        return note
    }

    private fun getprop(key: String) = Runtime.getRuntime().exec(arrayOf("getprop", key)).inputStream.bufferedReader().use { it.readText().trim() }

    private companion object {
        const val TAG = "EchoCheck"
        const val SPEAK_AT_MS = 4000L
        const val RUN_MS = 12000L
    }
}

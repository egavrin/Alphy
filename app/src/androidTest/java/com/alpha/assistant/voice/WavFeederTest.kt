package com.alpha.assistant.voice

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/** The spoken request fixtures and the real-time feeder of the live voice checks. */
class WavFeederTest {
    @Test fun fixturesAre16kMonoPcm() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in listOf("explain-moon", "teach-japanese", "show-rainbow", "explain-atom", "teach-times-seven", "japanese-next",
                "ask-largest-planet", "ask-photosynthesis", "ask-seven-times-eight", "ask-fall-asleep",
                "explain-heart", "heart-press", "heart-change", "moon-press", "moon-change", "japanese-press", "japanese-change")) {
            val samples = wavSamples(assets.open("voice/$name.wav").use { it.readBytes() })
            assertTrue("$name has ${samples.size} samples", samples.size >= LIVE_RATE)
        }
    }

    /** The end of speech is the end of the last voiced 20 ms block, so trailing silence does not count. */
    @Test fun endOfSpeechIgnoresTrailingSilence() {
        val voice = ShortArray(LIVE_RATE) { (8_000 * sin(2 * PI * 440 * it / LIVE_RATE)).toInt().toShort() }
        val samples = voice + ShortArray(LIVE_RATE / 2)
        val audio = FakeLiveAudio()
        // (elapsed-realtime nanos, voiced) of each block that the session gets.
        val blocks = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        audio.open({ blocks += SystemClock.elapsedRealtimeNanos() to (rms(it) > LIVE_VOICED_RMS) }, {})
        val feeder = WavFeeder(audio)
        feeder.start()
        SystemClock.sleep(200)
        val end = feeder.say(samples)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!feeder.idle && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        feeder.stop()
        val first = blocks.indexOfFirst { it.second }
        assertTrue("the fixture was fed", first >= 0 && feeder.idle)
        // A block is never sent before its time, so the earliest value is the planned start of the fixture.
        val start = (first until blocks.size).minOf { blocks[it].first - (it - first) * 20_000_000L }
        assertEquals(1_000.0, (end - start) / 1e6, 20.0)
    }
}

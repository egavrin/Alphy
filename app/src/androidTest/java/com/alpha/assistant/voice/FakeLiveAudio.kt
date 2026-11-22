package com.alpha.assistant.voice

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** A synthetic capture block with random signs and exactly the stated [rms]. */
internal fun Random.pcmBlock(rms: Double) = ShortArray(LIVE_BLOCK) { ((if (nextBoolean()) rms else -rms) * Short.MAX_VALUE).toInt().toShort() }

/**
 * Scripted live audio: the test delivers the capture blocks and can set [playbackActive]. It opens no device. With [realTime], the
 * written samples also keep [playbackActive] true until a speaker at [LIVE_RATE] has played them, as in [AndroidLiveAudio].
 * [playedFrames] comes from a frame clock: a speaker at [LIVE_RATE] that starts each written run [lagMs] after its write, and plays the
 * frames in order (plan "one scene", Task 30).
 */
internal class FakeLiveAudio(private val realTime: Boolean = false, @Volatile var lagMs: Long = 0) : LiveAudio {
    val played = CopyOnWriteArrayList<ShortArray>()
    val flushes = AtomicInteger()
    val closes = AtomicInteger()
    @Volatile var captureMuted = false; private set
    @Volatile private var held = false
    /** The uptime at which the written samples have played. [play] runs on the playback worker and [flushPlayback] on the main thread. */
    private var playEnd = 0L
    private val lock = Any()
    override var playbackActive: Boolean
        get() = held || synchronized(lock) { SystemClock.uptimeMillis() < playEnd }
        set(value) { held = value }
    @Volatile override var level = 0f
    /** False answers [open] as a denied audio focus does. */
    @Volatile var focusGranted = true
    /** True makes an unmute throw, as [AndroidLiveAudio] does when the microphone does not start again. */
    @Volatile var failUnmute = false
    @Volatile private var onCapture: ((ShortArray) -> Unit)? = null
    @Volatile private var onFocusLost: (() -> Unit)? = null

    val opened get() = onCapture != null

    override fun open(onCapture: (ShortArray) -> Unit, onFocusLost: () -> Unit): Boolean {
        this.onCapture = onCapture; this.onFocusLost = onFocusLost
        return focusGranted
    }
    override fun setCaptureMuted(muted: Boolean) { check(muted || !failUnmute) { "Microphone recording failed" }; captureMuted = muted }
    /** The uptime at which the frame clock starts the next written frame, and the frames before that frame. */
    private var clockAt = 0L
    private var clockFrames = 0L
    /** The written frames, and the frames that a flush counted as played. */
    private var writtenFrames = 0L
    private var flushedFrames = 0L

    override fun play(samples: ShortArray) {
        played += samples
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            if (realTime) playEnd = maxOf(playEnd, now) + samples.size * 1000L / LIVE_RATE
            // A run that starts after the speaker played all earlier frames starts lagMs after its write.
            if (clockAt + (writtenFrames - clockFrames) * 1000L / LIVE_RATE <= now + lagMs) {
                clockAt = maxOf(now + lagMs, clockAt + (writtenFrames - clockFrames) * 1000L / LIVE_RATE)
                clockFrames = writtenFrames
            }
            writtenFrames += samples.size
        }
    }
    override fun playedFrames(): Long = synchronized(lock) {
        val now = SystemClock.uptimeMillis()
        val clock = if (now < clockAt) clockFrames else clockFrames + (now - clockAt) * LIVE_RATE / 1000
        maxOf(flushedFrames, minOf(writtenFrames, clock))
    }
    /** The written frames, for a check that [playedFrames] stays at or below them. */
    fun writtenFrames(): Long = synchronized(lock) { writtenFrames }
    override fun flushPlayback() {
        synchronized(lock) { playEnd = 0; flushedFrames = writtenFrames; clockFrames = writtenFrames; clockAt = 0 }
        flushes.incrementAndGet()
    }
    override fun close() { closes.incrementAndGet() }

    /** Delivers one block as the capture thread does. It delivers even while muted, so a test sees what the session sends. */
    fun capture(block: ShortArray) = checkNotNull(onCapture).invoke(block)
    fun loseFocus() = checkNotNull(onFocusLost).invoke()
}

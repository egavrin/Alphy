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
 */
internal class FakeLiveAudio(private val realTime: Boolean = false) : LiveAudio {
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
    @Volatile private var onCapture: ((ShortArray) -> Unit)? = null
    @Volatile private var onFocusLost: (() -> Unit)? = null

    val opened get() = onCapture != null

    override fun open(onCapture: (ShortArray) -> Unit, onFocusLost: () -> Unit): Boolean {
        this.onCapture = onCapture; this.onFocusLost = onFocusLost
        return focusGranted
    }
    override fun setCaptureMuted(muted: Boolean) { captureMuted = muted }
    override fun play(samples: ShortArray) {
        played += samples
        if (realTime) synchronized(lock) { playEnd = maxOf(playEnd, SystemClock.uptimeMillis()) + samples.size * 1000L / LIVE_RATE }
    }
    override fun flushPlayback() { synchronized(lock) { playEnd = 0 }; flushes.incrementAndGet() }
    override fun close() { closes.incrementAndGet() }

    /** Delivers one block as the capture thread does. It delivers even while muted, so a test sees what the session sends. */
    fun capture(block: ShortArray) = checkNotNull(onCapture).invoke(block)
    fun loseFocus() = checkNotNull(onFocusLost).invoke()
}

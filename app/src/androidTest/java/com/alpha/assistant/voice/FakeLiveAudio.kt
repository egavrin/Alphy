package com.alpha.assistant.voice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** A synthetic capture block with random signs and exactly the stated [rms]. */
internal fun Random.pcmBlock(rms: Double) = ShortArray(LIVE_BLOCK) { ((if (nextBoolean()) rms else -rms) * Short.MAX_VALUE).toInt().toShort() }

/** Scripted live audio: the test delivers the capture blocks and sets [playbackActive]. It opens no device. */
internal class FakeLiveAudio : LiveAudio {
    val played = CopyOnWriteArrayList<ShortArray>()
    val flushes = AtomicInteger()
    val closes = AtomicInteger()
    @Volatile var captureMuted = false; private set
    @Volatile override var playbackActive = false
    @Volatile override var level = 0f
    @Volatile private var onCapture: ((ShortArray) -> Unit)? = null
    @Volatile private var onFocusLost: (() -> Unit)? = null

    val opened get() = onCapture != null

    override fun open(onCapture: (ShortArray) -> Unit, onFocusLost: () -> Unit): Boolean {
        this.onCapture = onCapture; this.onFocusLost = onFocusLost
        return true
    }
    override fun setCaptureMuted(muted: Boolean) { captureMuted = muted }
    override fun play(samples: ShortArray) { played += samples }
    override fun flushPlayback() { flushes.incrementAndGet() }
    override fun close() { closes.incrementAndGet() }

    /** Delivers one block as the capture thread does. It delivers even while muted, so a test sees what the session sends. */
    fun capture(block: ShortArray) = checkNotNull(onCapture).invoke(block)
    fun loseFocus() = checkNotNull(onFocusLost).invoke()
}

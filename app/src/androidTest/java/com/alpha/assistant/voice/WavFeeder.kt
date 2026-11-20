package com.alpha.assistant.voice

import android.os.SystemClock

private const val BLOCK_NANOS = 20_000_000L

/**
 * The capture thread of a live voice check: it gives [audio] one 20 ms block every 20 ms in real time, the queued fixture or else
 * digital silence. [afterBlock] runs on the feeder thread after each block.
 */
internal class WavFeeder(private val audio: FakeLiveAudio, private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val afterBlock: () -> Unit = {}) {
    private val lock = Any()
    private val blocks = ArrayDeque<ShortArray>()
    private val thread = Thread(::feed, "wav-feeder")
    @Volatile private var feeding = true
    @Volatile private var start = 0L
    /** The blocks given to the session: its audio clock in 20 ms steps. */
    @Volatile var fed = 0L; private set
    /** The largest delay of a block behind its planned time, in ms. A check may reset it. */
    @Volatile var maxLagMs = 0L
    /** The queued fixtures have all gone to the session. */
    val idle get() = synchronized(lock) { blocks.isEmpty() }

    fun start() { start = clock(); thread.start() }

    /**
     * Queues [samples] and returns the planned [clock] time of the end of their speech: the end of the last block above
     * [LIVE_VOICED_RMS], so trailing silence does not count.
     */
    fun say(samples: ShortArray): Long = synchronized(lock) {
        val first = fed + blocks.size
        val queued = (samples.indices step LIVE_BLOCK).map { from ->
            ShortArray(LIVE_BLOCK).also { samples.copyInto(it, 0, from, minOf(from + LIVE_BLOCK, samples.size)) }
        }
        blocks += queued
        start + (first + queued.indexOfLast { rms(it) > LIVE_VOICED_RMS } + 1) * BLOCK_NANOS
    }

    fun stop() { feeding = false; thread.join(1_000) }

    private fun feed() {
        var i = 0L
        while (feeding) {
            // fed and the queue change together, so say() plans from a consistent index.
            val block = synchronized(lock) { blocks.removeFirstOrNull().also { fed = i + 1 } } ?: ShortArray(LIVE_BLOCK)
            maxLagMs = maxOf(maxLagMs, (clock() - start - i * BLOCK_NANOS) / 1_000_000)
            audio.capture(block)
            i++
            afterBlock()
            val wait = start + i * BLOCK_NANOS - clock()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }
}

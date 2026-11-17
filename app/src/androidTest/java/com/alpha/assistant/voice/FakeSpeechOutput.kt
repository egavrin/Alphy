package com.alpha.assistant.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Records what the session reads aloud, chunk by chunk as an engine plays it. It plays no audio and takes no audio focus. */
internal class FakeSpeechOutput : SpeechOutput {
    val spoken: MutableList<String> = CopyOnWriteArrayList()
    /** The number of [speak] calls. */
    val calls = AtomicInteger()
    @Volatile var stopped = false
    /** The chunk limit of this engine. */
    @Volatile var limit = 3000
    /** [speak] throws it instead of reading. */
    @Volatile var failure: Exception? = null
    /** When set, each chunk plays until it gets one permit. [stop] does not end the playback: only the cancellation does. */
    @Volatile var chunkPermits: Channel<Unit>? = null
    /** [speak] returns only after it completes. */
    @Volatile var gate: CompletableDeferred<Unit>? = null
    override suspend fun speak(text: String) {
        calls.incrementAndGet()
        failure?.let { throw it }
        for (chunk in speechChunks(text, limit)) {
            spoken += chunk
            chunkPermits?.receive()
        }
        gate?.await()
    }
    override fun stop() { stopped = true }
}

package com.alpha.assistant.tools.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * The text of one streamed template call (stage templates, Task 15). The call gives each text delta to [append] on the thread that reads the
 * HTTP stream, under the limits of the call, so [append] only appends under a lock and signals a conflated channel: it never blocks or
 * suspends. [collect] runs its block with the newest complete lines when the block is free, so a slow device gets fewer runs, not a late one.
 */
internal class TemplateStream {
    private val buffer = StringBuilder()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var closed = false

    /**
     * The complete lines before the first narration line, when that line starts (plan "one scene", decision 6), or null when the call ends
     * with no narration line.
     */
    val narration = CompletableDeferred<String?>()

    fun append(delta: String) {
        val head = synchronized(buffer) {
            buffer.append(delta)
            if (narration.isCompleted) null else NARRATION.find(buffer)?.let { buffer.substring(0, it.range.first) }
        }
        head?.let(narration::complete)
        signal.trySend(Unit)
    }

    /** The whole text so far. */
    val text: String get() = synchronized(buffer) { buffer.toString() }

    /** The text up to its last line end. An unfinished last line is not complete. */
    fun completeLines(): String = text.let { it.substring(0, it.lastIndexOf('\n') + 1) }

    /** Runs [block] with the newest complete lines when they changed. No run starts after [close], and it returns when the run in flight ends. */
    suspend fun collect(block: suspend (String) -> Unit) {
        var last = ""
        for (ignored in signal) {
            if (closed) return
            val lines = completeLines()
            if (lines != last) { last = lines; block(lines) }
        }
    }

    /** Ends [collect] after the run in flight. The text goes on to take the deltas. */
    fun stopCollect() {
        closed = true
        signal.trySend(Unit)
    }

    fun close() {
        closed = true
        signal.close()
        narration.complete(null)
    }

    private companion object {
        /** The start of a narration line: say, a step number or an equals sign, at the start of a line. */
        val NARRATION = Regex("""(?m)^[ \t]*say(?:\d|[ \t]*=)""")
    }
}

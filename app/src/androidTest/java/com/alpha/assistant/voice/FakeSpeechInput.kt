package com.alpha.assistant.voice

import java.util.concurrent.atomic.AtomicInteger

/** Scripted speech input for the voice workflow tests: each capture returns the next [result]. It opens no microphone. */
internal class FakeSpeechInput(private val result: suspend () -> String?) : SpeechInput {
    val captures = AtomicInteger()
    override suspend fun capture(oneShot: Boolean, onLevel: (Float) -> Unit, onRecognizing: () -> Unit): String? {
        captures.incrementAndGet()
        return result()
    }
    override fun finish() = Unit
    override fun cancel() = Unit
}

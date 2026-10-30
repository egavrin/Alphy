package com.offlineassistant.app.speech
import java.io.Closeable
data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int
)

interface SpeechSynthesizer : Closeable {
    suspend fun warmUp()

    suspend fun synthesize(text: String): PcmAudio

    fun cancel()

    override fun close()
}


package com.offlineassistant.app.voice

import java.io.File

interface AudioTranscriber {
    fun warmUp() = Unit

    fun release() = Unit

    fun startStreaming(
        onPartialTranscript: (String) -> Unit,
        onEndpointDetected: () -> Unit = {}
    ): StreamingTranscriptionSession? = null

    fun transcribe(audioFile: File): AudioTranscription
}

interface StreamingTranscriptionSession {
    fun acceptPcm16(samples: ShortArray, sampleRate: Int)
    fun finish(): AudioTranscription
    fun cancel()
}

fun StreamingTranscriptionSession.asAudioTranscriber(): AudioTranscriber = object : AudioTranscriber {
    override fun transcribe(audioFile: File): AudioTranscription = finish()
}

data class AudioTranscription(
    val text: String?,
    val error: String?,
    val latencyMs: Long
)


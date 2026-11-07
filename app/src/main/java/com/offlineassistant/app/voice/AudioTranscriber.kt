package com.offlineassistant.app.voice

interface AudioTranscriber {
    fun warmUp() = Unit

    fun release() = Unit

    fun startStreaming(
        onPartialTranscript: (String) -> Unit,
        onEndpointDetected: () -> Unit = {}
    ): StreamingTranscriptionSession? = null
}

interface StreamingTranscriptionSession {
    fun acceptPcm16(samples: ShortArray, sampleRate: Int)
    fun finish(): AudioTranscription
    fun cancel()
}

data class AudioTranscription(
    val text: String?,
    val error: String?,
    val latencyMs: Long
)


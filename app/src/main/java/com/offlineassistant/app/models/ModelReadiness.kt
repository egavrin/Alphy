package com.offlineassistant.app.models

// Readiness is supplied only after VoiceModels verifies the packaged manifest.
data class ModelReadiness(val ready: Boolean, val location: String)
object ModelNames { const val TONE = "asr" }
object ModelOperations { const val WARM_UP = "warm-up"; const val TRANSCRIPTION = "transcription" }
interface ModelRuntimeTelemetryStore {
    fun recordSuccess(model: String, operation: String, latencyMs: Long) {}
    fun recordFailure(model: String, operation: String, latencyMs: Long, error: String) {}
}
object NoOpModelRuntimeTelemetryStore : ModelRuntimeTelemetryStore

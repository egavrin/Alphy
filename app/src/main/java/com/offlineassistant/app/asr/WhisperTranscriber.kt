package com.offlineassistant.app.asr

import com.offlineassistant.app.models.ModelNames
import com.offlineassistant.app.models.ModelOperations
import com.offlineassistant.app.models.ModelReadiness
import com.offlineassistant.app.models.ModelRuntimeTelemetryStore
import com.offlineassistant.app.models.NoOpModelRuntimeTelemetryStore
import com.offlineassistant.app.voice.AudioTranscriber
import com.offlineassistant.app.voice.AudioTranscription
import java.io.File

interface WhisperNativeEngine {
    fun prepare(modelPath: String) = Unit

    fun transcribe(modelPath: String, audioFile: File): String

    fun release() = Unit
}

object JniWhisperNativeEngine : WhisperNativeEngine {
    @Volatile
    private var loaded = false

    override fun prepare(modelPath: String) {
        ensureLoaded()
        nativePrepare(modelPath)
    }

    override fun transcribe(modelPath: String, audioFile: File): String {
        ensureLoaded()
        return nativeTranscribe(modelPath, audioFile.absolutePath)
    }

    fun setThreadCount(threadCount: Int) {
        require(threadCount in 1..16)
        ensureLoaded()
        nativeSetThreadCount(threadCount)
    }

    override fun release() {
        if (loaded) nativeRelease()
    }

    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("offlineassistant_whisper")
            loaded = true
        }
    }

    private external fun nativeTranscribe(modelPath: String, audioPath: String): String
    private external fun nativePrepare(modelPath: String)
    private external fun nativeSetThreadCount(threadCount: Int)
    private external fun nativeRelease()
}

class WhisperTranscriber(
    private val readiness: ModelReadiness,
    private val nativeEngine: WhisperNativeEngine = JniWhisperNativeEngine,
    private val telemetryStore: ModelRuntimeTelemetryStore = NoOpModelRuntimeTelemetryStore
) : AudioTranscriber {
    override fun warmUp() {
        if (readiness.ready) nativeEngine.prepare(readiness.location)
    }

    override fun release() = nativeEngine.release()

    override fun transcribe(audioFile: File): AudioTranscription {
        val started = System.currentTimeMillis()
        if (!readiness.ready) {
            val result = AudioTranscription(
                text = null,
                error = "whisper.cpp model is not installed: ${readiness.location}. Audio: ${audioFile.name}",
                latencyMs = System.currentTimeMillis() - started
            )
            telemetryStore.recordFailure(
                ModelNames.WHISPER,
                ModelOperations.TRANSCRIPTION,
                result.latencyMs,
                result.error.orEmpty()
            )
            return result
        }
        return runCatching { nativeEngine.transcribe(readiness.location, audioFile).trim() }
            .fold(
                onSuccess = { transcript ->
                    val result = AudioTranscription(
                        text = transcript.ifBlank { null },
                        error = if (transcript.isBlank()) "whisper.cpp returned an empty transcript. Audio: ${audioFile.name}" else null,
                        latencyMs = System.currentTimeMillis() - started
                    )
                    if (result.error == null) {
                        telemetryStore.recordSuccess(ModelNames.WHISPER, ModelOperations.TRANSCRIPTION, result.latencyMs)
                    } else {
                        telemetryStore.recordFailure(
                            ModelNames.WHISPER,
                            ModelOperations.TRANSCRIPTION,
                            result.latencyMs,
                            result.error
                        )
                    }
                    result
                },
                onFailure = { error ->
                    val result = AudioTranscription(
                        text = null,
                        error = "whisper.cpp native transcription failed: ${error.message ?: error::class.java.simpleName}. Audio: ${audioFile.name}",
                        latencyMs = System.currentTimeMillis() - started
                    )
                    telemetryStore.recordFailure(
                        ModelNames.WHISPER,
                        ModelOperations.TRANSCRIPTION,
                        result.latencyMs,
                        result.error.orEmpty()
                    )
                    result
                }
            )
    }
}

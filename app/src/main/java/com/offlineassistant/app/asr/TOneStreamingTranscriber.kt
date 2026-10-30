package com.offlineassistant.app.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.offlineassistant.app.models.ModelNames
import com.offlineassistant.app.models.ModelOperations
import com.offlineassistant.app.models.ModelReadiness
import com.offlineassistant.app.models.ModelRuntimeTelemetryStore
import com.offlineassistant.app.models.NoOpModelRuntimeTelemetryStore
import com.offlineassistant.app.voice.AudioTranscriber
import com.offlineassistant.app.voice.AudioTranscription
import com.offlineassistant.app.voice.StreamingTranscriptionSession
import java.io.File
import kotlin.math.sqrt

class TOneStreamingTranscriber(
    private val readiness: ModelReadiness,
    private val telemetryStore: ModelRuntimeTelemetryStore = NoOpModelRuntimeTelemetryStore,
    private val numThreads: Int = 4
) : AudioTranscriber {
    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null

    override fun warmUp() {
        if (readiness.ready) synchronized(lock) { recognizer() }
    }

    override fun startStreaming(
        onPartialTranscript: (String) -> Unit,
        onEndpointDetected: () -> Unit
    ): StreamingTranscriptionSession? {
        if (!readiness.ready) return null
        return synchronized(lock) {
            SherpaTOneSession(
                recognizer = recognizer(),
                telemetryStore = telemetryStore,
                onPartialTranscript = onPartialTranscript,
                onEndpointDetected = onEndpointDetected,
                lock = lock
            )
        }
    }

    override fun transcribe(audioFile: File): AudioTranscription {
        if (!readiness.ready) {
            return AudioTranscription(
                text = null,
                error = "T-one model is not installed: ${readiness.location}. Audio: ${audioFile.name}",
                latencyMs = 0
            )
        }
        val wave = WaveReader.readWave(audioFile.absolutePath)
        val session = requireNotNull(startStreaming(onPartialTranscript = {}, onEndpointDetected = {}))
        val pcm16 = ShortArray(wave.samples.size) { index ->
            (wave.samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        session.acceptPcm16(pcm16, wave.sampleRate)
        return session.finish()
    }

    override fun release() = synchronized(lock) {
        recognizer?.release()
        recognizer = null
    }

    private fun recognizer(): OnlineRecognizer {
        recognizer?.let { return it }
        val directory = File(readiness.location)
        val toneConfig = OnlineToneCtcModelConfig().apply {
            model = File(directory, "model.onnx").absolutePath
        }
        val modelConfig = OnlineModelConfig().apply {
            toneCtc = toneConfig
            tokens = File(directory, "tokens.txt").absolutePath
            this.numThreads = this@TOneStreamingTranscriber.numThreads
            provider = "cpu"
            debug = false
        }
        val config = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig(sampleRate = 8_000, featureDim = 80, dither = 0f)
            this.modelConfig = modelConfig
            endpointConfig = EndpointConfig(
                EndpointRule(false, 2.4f, 0f),
                EndpointRule(true, 0.9f, 0f),
                EndpointRule(false, 0f, 20f)
            )
            enableEndpoint = true
            decodingMethod = "greedy_search"
            maxActivePaths = 4
        }
        return OnlineRecognizer(null, config).also { recognizer = it }
    }
}

private class SherpaTOneSession(
    private val recognizer: OnlineRecognizer,
    private val telemetryStore: ModelRuntimeTelemetryStore,
    private val onPartialTranscript: (String) -> Unit,
    private val onEndpointDetected: () -> Unit,
    private val lock: Any
) : StreamingTranscriptionSession {
    private val stream: OnlineStream = recognizer.createStream()
    private var lastTranscript = ""
    private var finished = false
    private var endpointNotified = false
    private var inputSampleRate = 8_000
    private val trailingSilenceDetector = TrailingSilenceEndpointDetector()

    override fun acceptPcm16(samples: ShortArray, sampleRate: Int) {
        if (finished || samples.isEmpty()) return
        trailingSilenceDetector.accept(samples, sampleRate)
        val floats = FloatArray(samples.size) { index -> samples[index] / 32768f }
        synchronized(lock) {
            if (finished) return
            inputSampleRate = sampleRate
            stream.acceptWaveform(floats, sampleRate)
            decodeReady()
            emitPartial()
            emitEndpointIfDetected()
        }
    }

    override fun finish(): AudioTranscription = synchronized(lock) {
        val finalizationStarted = System.nanoTime()
        if (finished) {
            return@synchronized AudioTranscription(
                text = lastTranscript.ifBlank { null },
                error = if (lastTranscript.isBlank()) "T-one returned an empty transcript." else null,
                latencyMs = 0
            )
        }
        stream.acceptWaveform(FloatArray(inputSampleRate * 3 / 10), inputSampleRate)
        stream.inputFinished()
        decodeReady()
        val finalTranscript = recognizer.getResult(stream).text.trim()
        if (finalTranscript.isNotBlank() && finalTranscript != lastTranscript) {
            lastTranscript = finalTranscript
            onPartialTranscript(finalTranscript)
        }
        finished = true
        stream.release()
        val latencyMs = (System.nanoTime() - finalizationStarted) / 1_000_000L
        if (lastTranscript.isBlank()) {
            telemetryStore.recordFailure(
                ModelNames.WHISPER,
                ModelOperations.TRANSCRIPTION,
                latencyMs,
                "T-one returned an empty transcript."
            )
            AudioTranscription(null, "T-one returned an empty transcript.", latencyMs)
        } else {
            telemetryStore.recordSuccess(ModelNames.WHISPER, ModelOperations.TRANSCRIPTION, latencyMs)
            AudioTranscription(lastTranscript, null, latencyMs)
        }
    }

    override fun cancel() = synchronized(lock) {
        if (!finished) {
            finished = true
            stream.release()
        }
    }

    private fun decodeReady() {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    private fun emitPartial() {
        val transcript = recognizer.getResult(stream).text.trim()
        if (transcript.isNotBlank() && transcript != lastTranscript) {
            lastTranscript = transcript
            trailingSilenceDetector.markRecognizedSpeech()
            onPartialTranscript(transcript)
        }
    }

    private fun emitEndpointIfDetected() {
        if (
            !endpointNotified &&
            lastTranscript.isNotBlank() &&
            (recognizer.isEndpoint(stream) || trailingSilenceDetector.isEndpoint())
        ) {
            endpointNotified = true
            onEndpointDetected()
        }
    }
}

internal class TrailingSilenceEndpointDetector(
    private val silenceThresholdRms: Double = 0.004,
    private val requiredTrailingSilenceMs: Long = 1_100L
) {
    private var recognizedSpeech = false
    private var trailingSilenceMs = 0L

    fun accept(samples: ShortArray, sampleRate: Int) {
        if (samples.isEmpty() || sampleRate <= 0) return
        var sumSquares = 0.0
        samples.forEach { sample ->
            val normalized = sample.toDouble() / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / samples.size)
        if (rms <= silenceThresholdRms) {
            if (recognizedSpeech) trailingSilenceMs += samples.size * 1_000L / sampleRate
        } else {
            trailingSilenceMs = 0L
        }
    }

    fun markRecognizedSpeech() {
        recognizedSpeech = true
    }

    fun isEndpoint(): Boolean = recognizedSpeech && trailingSilenceMs >= requiredTrailingSilenceMs
}

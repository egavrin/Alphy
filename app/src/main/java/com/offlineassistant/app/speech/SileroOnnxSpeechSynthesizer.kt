package com.offlineassistant.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.offlineassistant.app.models.ModelNames
import com.offlineassistant.app.models.ModelOperations
import com.offlineassistant.app.models.ModelRuntimeTelemetryStore
import com.offlineassistant.app.models.NoOpModelRuntimeTelemetryStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicLong

data class SileroSynthesisInput(
    val sequence: LongArray,
    val durationRate: FloatArray,
    val pitchCoefficients: FloatArray,
    val typeIds: LongArray
) {
    init {
        require(sequence.isNotEmpty()) { "Silero sequence must not be empty" }
        require(durationRate.size == sequence.size) { "Duration rate must match sequence" }
        require(pitchCoefficients.size == sequence.size) { "Pitch coefficients must match sequence" }
        require(typeIds.size == sequence.size) { "Sentence types must match sequence" }
    }
}

data class SileroSpectrum(
    val magnitudeLogits: FloatArray,
    val phase: FloatArray,
    val frameCount: Int
)

fun interface SileroTextFrontend : AutoCloseable {
    fun prepare(text: String): SileroSynthesisInput

    fun warmUp() = Unit

    override fun close() = Unit
}

interface SileroAcousticInference : AutoCloseable {
    fun warmUp()

    fun infer(input: SileroSynthesisInput): SileroSpectrum

    fun cancel()
}

data class SileroModelBundle(
    val predictors: File,
    val acoustic: File,
    val window: File
) {
    fun requireComplete(): SileroModelBundle = apply {
        require(predictors.isFile) { "Missing Silero predictors: ${predictors.absolutePath}" }
        require(acoustic.isFile) { "Missing Silero acoustic model: ${acoustic.absolutePath}" }
        require(window.isFile) { "Missing Silero ISTFT window: ${window.absolutePath}" }
    }

    companion object {
        fun fromDirectory(directory: File): SileroModelBundle = SileroModelBundle(
            predictors = File(directory, "predictors.onnx"),
            acoustic = File(directory, "acoustic.onnx"),
            window = File(directory, "window.f32")
        )
    }
}

class SileroSpeechSynthesizer(
    private val frontend: SileroTextFrontend,
    private val inference: SileroAcousticInference,
    private val istft: SileroIstft,
    private val telemetryStore: ModelRuntimeTelemetryStore = NoOpModelRuntimeTelemetryStore
) : SpeechSynthesizer {
    override suspend fun warmUp() {
        val started = System.nanoTime()
        runCatching {
            frontend.warmUp()
            inference.warmUp()
        }
            .onSuccess {
                telemetryStore.recordSuccess(ModelNames.SILERO_TTS, ModelOperations.WARM_UP, elapsedMillis(started))
            }
            .onFailure { error ->
                telemetryStore.recordFailure(
                    ModelNames.SILERO_TTS,
                    ModelOperations.WARM_UP,
                    elapsedMillis(started),
                    error.message ?: error::class.java.simpleName
                )
            }
            .getOrThrow()
    }

    override suspend fun synthesize(text: String): PcmAudio {
        val started = System.nanoTime()
        return runCatching {
            val input = frontend.prepare(text)
            val spectrum = inference.infer(input)
            PcmAudio(
                samples = istft.synthesize(
                    spectrum.magnitudeLogits,
                    spectrum.phase,
                    spectrum.frameCount
                ),
                sampleRate = SampleRate
            )
        }.onSuccess {
            telemetryStore.recordSuccess(ModelNames.SILERO_TTS, ModelOperations.SYNTHESIS, elapsedMillis(started))
        }.onFailure { error ->
            telemetryStore.recordFailure(
                ModelNames.SILERO_TTS,
                ModelOperations.SYNTHESIS,
                elapsedMillis(started),
                error.message ?: error::class.java.simpleName
            )
        }.getOrThrow()
    }

    override fun cancel() = inference.cancel()

    override fun close() {
        frontend.close()
        inference.close()
    }

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L

    companion object {
        const val SampleRate = 48_000

        fun fromBundle(
            bundle: SileroModelBundle,
            frontend: SileroTextFrontend,
            telemetryStore: ModelRuntimeTelemetryStore = NoOpModelRuntimeTelemetryStore
        ): SileroSpeechSynthesizer {
            val completeBundle = bundle.requireComplete()
            return SileroSpeechSynthesizer(
                frontend = frontend,
                inference = OnnxSileroAcousticInference(completeBundle),
                istft = SileroIstft(readFloatArray(completeBundle.window)),
                telemetryStore = telemetryStore
            )
        }

        fun fromBundle(
            bundle: SileroModelBundle,
            frontendBundle: SileroFrontendBundle,
            telemetryStore: ModelRuntimeTelemetryStore = NoOpModelRuntimeTelemetryStore
        ): SileroSpeechSynthesizer {
            val linguisticInference = OnnxSileroLinguisticInference(frontendBundle)
            val frontend = SileroRussianTextFrontend(frontendBundle, linguisticInference)
            return fromBundle(bundle, frontend, telemetryStore)
        }

        private fun readFloatArray(file: File): FloatArray {
            val bytes = file.readBytes()
            require(bytes.size % Float.SIZE_BYTES == 0) { "Invalid Silero window file" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also(buffer::get)
        }
    }
}

class OnnxSileroAcousticInference(
    private val bundle: SileroModelBundle,
    private val threadCount: Int = 4
) : SileroAcousticInference {
    private val environment = OrtEnvironment.getEnvironment()
    private val cancellationGeneration = AtomicLong(0L)
    private val lock = Any()
    private var sessions: Sessions? = null

    override fun warmUp() {
        sessions()
    }

    override fun infer(input: SileroSynthesisInput): SileroSpectrum {
        val generation = cancellationGeneration.get()
        val activeSessions = sessions()
        val tokenCount = input.sequence.size
        val predictorInputs = mutableListOf<OnnxTensor>()
        val duration: FloatArray
        val pitch: FloatArray
        try {
            val values = mapOf(
                "sequence" to input.sequence.tensor(longArrayOf(1, tokenCount.toLong()), predictorInputs),
                "speaker_ids" to longArrayOf(XeniaSpeakerId).tensor(longArrayOf(1), predictorInputs),
                "duration_rate" to input.durationRate.tensor(longArrayOf(1, tokenCount.toLong()), predictorInputs),
                "pitch_coefficients" to input.pitchCoefficients.tensor(
                    longArrayOf(1, tokenCount.toLong()),
                    predictorInputs
                ),
                "type_ids" to input.typeIds.tensor(longArrayOf(1, tokenCount.toLong()), predictorInputs)
            )
            activeSessions.predictors.run(values).use { result ->
                duration = result.floatOutput("duration")
                pitch = result.floatOutput("pitch")
            }
        } finally {
            predictorInputs.forEach(OnnxTensor::close)
        }
        check(generation == cancellationGeneration.get()) { "Silero synthesis cancelled" }

        val frameCount = duration.sumOf { it.toInt().coerceAtLeast(0) }
        require(frameCount > 0) { "Silero duration predictor returned no audio frames" }
        val alignment = FloatArray(tokenCount * frameCount)
        var frame = 0
        duration.forEachIndexed { token, value ->
            repeat(value.toInt().coerceAtLeast(0)) {
                alignment[token * frameCount + frame] = 1f
                frame++
            }
        }
        val acousticInputs = mutableListOf<OnnxTensor>()
        try {
            val values = mapOf(
                "sequence" to input.sequence.tensor(longArrayOf(1, tokenCount.toLong()), acousticInputs),
                "speaker_ids" to longArrayOf(XeniaSpeakerId).tensor(longArrayOf(1), acousticInputs),
                "pitch" to pitch.tensor(longArrayOf(1, 1, tokenCount.toLong()), acousticInputs),
                "alignment" to alignment.tensor(
                    longArrayOf(1, tokenCount.toLong(), frameCount.toLong()),
                    acousticInputs
                )
            )
            return activeSessions.acoustic.run(values).use { result ->
                check(generation == cancellationGeneration.get()) { "Silero synthesis cancelled" }
                SileroSpectrum(
                    magnitudeLogits = result.floatOutput("magnitude_logit"),
                    phase = result.floatOutput("phase"),
                    frameCount = frameCount
                )
            }
        } finally {
            acousticInputs.forEach(OnnxTensor::close)
        }
    }

    override fun cancel() {
        cancellationGeneration.incrementAndGet()
    }

    override fun close() {
        synchronized(lock) {
            sessions?.close()
            sessions = null
        }
    }

    private fun sessions(): Sessions = synchronized(lock) {
        sessions ?: run {
            bundle.requireComplete()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threadCount.coerceAtLeast(1))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            Sessions(
                predictors = environment.createSession(bundle.predictors.absolutePath, options),
                acoustic = environment.createSession(bundle.acoustic.absolutePath, options),
                options = options
            ).also { sessions = it }
        }
    }

    private fun LongArray.tensor(shape: LongArray, owner: MutableList<OnnxTensor>): OnnxTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(this), shape).also(owner::add)

    private fun FloatArray.tensor(shape: LongArray, owner: MutableList<OnnxTensor>): OnnxTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(this), shape).also(owner::add)

    private fun OrtSession.Result.floatOutput(name: String): FloatArray {
        val tensor = get(name).orElseThrow { IllegalStateException("Missing Silero output: $name") } as OnnxTensor
        val buffer = tensor.floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private data class Sessions(
        val predictors: OrtSession,
        val acoustic: OrtSession,
        val options: OrtSession.SessionOptions
    ) : AutoCloseable {
        override fun close() {
            predictors.close()
            acoustic.close()
            options.close()
        }
    }

    private companion object {
        const val XeniaSpeakerId = 4L
    }
}

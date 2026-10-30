package com.offlineassistant.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

class OnnxSileroLinguisticInference(
    private val bundle: SileroFrontendBundle,
    private val threadCount: Int = 2
) : SileroLinguisticInference {
    private val environment = OrtEnvironment.getEnvironment()
    private val lock = Any()
    private var sessions: Sessions? = null

    override fun warmUp() {
        sessions()
    }

    override fun accent(batch: SileroNgramBatch): SileroAccentLogits {
        require(batch.rowCount > 0 && batch.columnCount > 0)
        val tensors = mutableListOf<OnnxTensor>()
        try {
            val shape = longArrayOf(batch.rowCount.toLong(), batch.columnCount.toLong())
            val inputs = mapOf(
                "ngram_ids" to batch.ids.tensor(shape, tensors),
                "ngram_mask" to batch.mask.tensor(shape, tensors)
            )
            return sessions().accentor.run(inputs).use { result ->
                val stress = result.floatOutput("stress_logits")
                val yo = result.floatOutput("yo_logits")
                require(stress.size == batch.rowCount * StressClasses) { "Invalid Silero stress output" }
                require(yo.size == batch.rowCount * YoClasses) { "Invalid Silero yo output" }
                SileroAccentLogits(stress, StressClasses, yo, YoClasses)
            }
        } finally {
            tensors.forEach(OnnxTensor::close)
        }
    }

    override fun resolveHomographs(batch: SileroHomographBatch): FloatArray {
        require(batch.rowCount > 0 && batch.columnCount > 0)
        val tensors = mutableListOf<OnnxTensor>()
        try {
            val inputs = mapOf(
                "input_ids" to batch.inputIds.tensor(
                    longArrayOf(batch.rowCount.toLong(), batch.columnCount.toLong()),
                    tensors
                ),
                "homo_start_ids" to batch.starts.tensor(longArrayOf(batch.rowCount.toLong()), tensors),
                "homo_end_ids" to batch.ends.tensor(longArrayOf(batch.rowCount.toLong()), tensors)
            )
            return sessions().homosolver.run(inputs).use { result ->
                result.floatOutput("logits").also { logits ->
                    require(logits.size == batch.rowCount) { "Invalid Silero homosolver output" }
                }
            }
        } finally {
            tensors.forEach(OnnxTensor::close)
        }
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
                accentor = environment.createSession(bundle.accentor.absolutePath, options),
                homosolver = environment.createSession(bundle.homosolver.absolutePath, options),
                options = options
            ).also { sessions = it }
        }
    }

    private fun LongArray.tensor(shape: LongArray, owner: MutableList<OnnxTensor>): OnnxTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(this), shape).also(owner::add)

    private fun FloatArray.tensor(shape: LongArray, owner: MutableList<OnnxTensor>): OnnxTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(this), shape).also(owner::add)

    private fun OrtSession.Result.floatOutput(name: String): FloatArray {
        val tensor = get(name).orElseThrow { IllegalStateException("Missing Silero output: $name") } as OnnxTensor
        val buffer = tensor.floatBuffer
        return FloatArray(buffer.remaining()).also(buffer::get)
    }

    private data class Sessions(
        val accentor: OrtSession,
        val homosolver: OrtSession,
        val options: OrtSession.SessionOptions
    ) : AutoCloseable {
        override fun close() {
            accentor.close()
            homosolver.close()
            options.close()
        }
    }

    private companion object {
        const val StressClasses = 10
        const val YoClasses = 7
    }
}

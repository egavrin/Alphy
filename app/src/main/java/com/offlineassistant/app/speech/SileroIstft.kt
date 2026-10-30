package com.offlineassistant.app.speech

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import org.jtransforms.fft.FloatFFT_1D

/** Reconstructs Silero Vocos float PCM using its `same` overlap-add convention. */
class SileroIstft(
    private val window: FloatArray,
    private val fftSize: Int = 2400,
    private val hopLength: Int = 600
) {
    private val fft = FloatFFT_1D(fftSize.toLong())
    private val frequencyBins = fftSize / 2 + 1
    private val crop = (fftSize - hopLength) / 2

    init {
        require(window.size == fftSize) { "Expected a $fftSize-sample ISTFT window" }
        require(hopLength in 1..fftSize) { "Invalid ISTFT hop length: $hopLength" }
    }

    fun synthesize(
        magnitudeLogits: FloatArray,
        phase: FloatArray,
        frameCount: Int
    ): FloatArray {
        require(frameCount > 0) { "ISTFT requires at least one frame" }
        val expectedValues = frequencyBins * frameCount
        require(magnitudeLogits.size == expectedValues) { "Invalid magnitude tensor size" }
        require(phase.size == expectedValues) { "Invalid phase tensor size" }

        val overlapSize = (frameCount - 1) * hopLength + fftSize
        val overlap = FloatArray(overlapSize)
        val envelope = FloatArray(overlapSize)
        val complex = FloatArray(fftSize * 2)
        repeat(frameCount) { frame ->
            complex.fill(0f)
            repeat(frequencyBins) { bin ->
                val tensorIndex = bin * frameCount + frame
                val magnitude = min(exp(magnitudeLogits[tensorIndex].toDouble()), 100.0)
                val angle = phase[tensorIndex].toDouble()
                val real = (magnitude * cos(angle)).toFloat()
                val imaginary = (magnitude * sin(angle)).toFloat()
                complex[bin * 2] = real
                complex[bin * 2 + 1] = imaginary
                if (bin != 0 && bin != fftSize / 2) {
                    val mirrored = fftSize - bin
                    complex[mirrored * 2] = real
                    complex[mirrored * 2 + 1] = -imaginary
                }
            }
            fft.complexInverse(complex, true)
            val frameOffset = frame * hopLength
            repeat(fftSize) { sample ->
                val target = frameOffset + sample
                val windowValue = window[sample]
                overlap[target] += complex[sample * 2] * windowValue
                envelope[target] += windowValue * windowValue
            }
        }

        val output = FloatArray(overlapSize - crop * 2)
        output.indices.forEach { sample ->
            val source = sample + crop
            val weight = envelope[source]
            require(weight > MinimumEnvelope) { "Invalid ISTFT window envelope" }
            output[sample] = overlap[source] / weight
        }
        return output
    }

    companion object {
        private const val MinimumEnvelope = 1e-11f
    }
}

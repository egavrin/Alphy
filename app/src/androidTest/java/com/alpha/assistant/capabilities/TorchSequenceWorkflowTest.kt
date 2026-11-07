package com.alpha.assistant.capabilities

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device output needs separate physical evidence. These checks cover the bounded synthesis contract. */
@RunWith(AndroidJUnit4::class)
class TorchSequenceWorkflowTest {
    private fun arguments() = JSONObject().put("notes", "60,64,67,72").put("bpm", 120)
        .put("durationMillis", 5000).put("torchPulseMillis", 80)

    @Test fun requestedSequenceProducesExactPcmAndBeatSchedule() {
        val request = CapabilityCatalog.request(JSONObject().put("id", "sequence").put("capability", "audio.torchSequence")
            .put("args", arguments()))
        val sequence = TorchSequence.parse(request.args)
        assertEquals(40_000, sequence.samples.size)
        assertEquals(8000, sequence.sampleRate)
        assertEquals((0L..4500L step 500).toList(), sequence.beats)
        assertEquals(80, sequence.pulseMillis)
        assertTrue(sequence.samples.any { it > 0 })
        assertTrue(sequence.samples.any { it < 0 })
        assertTrue(sequence.samples.all { kotlin.math.abs(it.toInt()) <= 8192 })
        assertArrayEquals(sequence.samples, TorchSequence.parse(arguments()).samples)
    }

    @Test fun malformedAndUnboundedSequencesFailBeforePlayback() {
        for (arguments in listOf(arguments().put("notes", "60,eval(1)"), arguments().put("notes", "128"),
            arguments().put("notes", (1..40).joinToString(",") { "60" }), arguments().put("durationMillis", 30001),
            arguments().put("torchPulseMillis", 501), arguments().put("bpm", 0))) {
            assertThrows(IllegalArgumentException::class.java) { TorchSequence.parse(arguments) }
        }
    }
}

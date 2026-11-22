package com.alpha.assistant.ui

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.ToolGenerationTraceEvent
import com.alpha.assistant.tools.agent.AppFileNames
import com.alpha.assistant.tools.appfile.AppFileCompiler
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.ui.TemplateTalk.Companion.SCREEN_REQUEST
import com.alpha.assistant.voice.FakeLiveTransport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Gate 1b (Task 20): the stream of a new screen adds at most 0.3 s to controls on. For each example file, a count of pairs of spoken screens
 * with the same scripted deltas (one line in each delta, the first at 1.7 s, the last at 4.5 s, even gaps between, and three narration
 * lines at the end), one turn with
 * previewStream on and one with it off, in alternating order. Each turn runs in a new shell on an empty stage, and the recorder gives the time
 * from the end of speech to controls_on. Each pair prints one JSON line, and each file one line with the median of its differences. The bar
 * applies to that median, because the difference of one pair with no hold went from -785 ms to +537 ms under host load (2026-11-21).
 *
 * The full suite skips this test: a timing check of 0.3 s fails by chance under host load. A gate run names its count of pairs of each file
 * with `-e stageStreamCostPairs 15`, and its proof adds `-e previewHoldMs 500` and must fail. Five pairs do not detect a hold of 500 ms: in
 * one run with that hold, the median of ship was 157 ms.
 */
class StageStreamCostTest {
    private val robot = ShellRobot("stage-stream-cost")
    @After fun reset() { AppFileCompiler.previewHoldMs = 0 }

    @Test fun theStreamAddsAtMostThreeTenthsOfASecond() {
        val arguments = InstrumentationRegistry.getArguments()
        val pairs = arguments.getString("stageStreamCostPairs")?.toInt()
        assumeTrue("a gate run only: give the count of pairs of each file with -e stageStreamCostPairs 15", pairs != null)
        // A run with -e previewHoldMs 500 holds each preview script that long and must fail: the proof that the pairs see a delay (Step 2).
        AppFileCompiler.previewHoldMs = arguments.getString("previewHoldMs")?.toLong() ?: 0
        val misses = mutableListOf<String>()
        for (name in FILES) {
            val file = appFilePlan("template/$name")
            val differences = List(checkNotNull(pairs)) { pair ->
                val order = if (pair % 2 == 0) listOf(true, false) else listOf(false, true)
                val ms = order.associateWith { stream -> controlsOn(file, stream) }
                val difference = ms.getValue(true) - ms.getValue(false)
                println(JSONObject().put("gate", "1b").put("file", name).put("pair", pair + 1).put("first", if (order.first()) "stream" else "plain")
                    .put("streamMs", ms.getValue(true)).put("plainMs", ms.getValue(false)).put("differenceMs", difference))
                difference
            }
            // The median of an even count is the mean of the two middle values.
            val median = differences.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
            println(JSONObject().put("gate", "1b").put("file", name).put("medianMs", median))
            if (median > BAR_MS) misses += "$name: median $median ms of $differences"
        }
        assertTrue("files whose median difference is more than $BAR_MS ms: $misses", misses.isEmpty())
    }

    /** The time from the end of speech to controls_on of one spoken screen whose template reply is [file], with the stream on or off. */
    private fun controlsOn(file: String, stream: Boolean): Long {
        // The deltas end with three narration lines, so the controls come on when the first one starts (plan "one scene", Task 27).
        val lines = (file.lines().filter { it.isNotBlank() } + NARRATION_LINES).map { "$it\n" }
        val gaps = listOf(FIRST_MS) + List(lines.size - 1) { (LAST_MS - FIRST_MS) / (lines.size - 1) }
        val fixture = ShellFixture("stage-stream-cost")
        val socket = FakeLiveTransport()
        val shell = TemplateShell(fixture, robot, socket)
        val recorder = DialogueRecorder()
        try {
            shell.live(StreamTalk(listOf(Streamed(lines, gaps = gaps)))) { _, model, _ ->
                robot.instrumentation.runOnMainSync {
                    model.tools.previewStream = stream
                    model.tools.generationTraceObserver = { event -> if (event is ToolGenerationTraceEvent.Phase) recorder.phase(event) }
                }
                recorder.speechEnd(SystemClock.elapsedRealtimeNanos())
                socket.say(SCREEN_REQUEST, "d1", 100)
                robot.waitUntil(60_000) {
                    recorder.marks().any { it.name == DialogueRecorder.CONTROLS_ON || it.name == AppFileNames.PLAN_OUTCOME && it.detail != "accepted" }
                }
            }
        } finally { shell.remove() }
        val request = recorder.report().getJSONArray("requests").getJSONObject(0)
        return checkNotNull(request.opt("controls_on") as? Number) { "no controls_on, the plan outcome ${request.opt("planOutcome")}" }.toLong()
    }

    private companion object {
        /** The example files of design section 1 (Global Constraints). */
        val FILES = listOf("ship", "word-parts", "majority")
        const val FIRST_MS = 1_700L
        const val LAST_MS = 4_500L
        /** The bar of gate 1b: the stream adds at most 0.3 s to controls on. */
        const val BAR_MS = 300L
    }
}

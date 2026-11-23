package com.alpha.assistant.tools

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.appfile.APP_FILE_COMPILE_MS
import com.alpha.assistant.tools.appfile.APP_FILE_RETURN_BYTES
import com.alpha.assistant.tools.appfile.AppFilePrevious
import com.alpha.assistant.tools.appfile.plan
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.stageCanvasHeight
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/** The deadline of one preview update in its new transient isolate, and the bar of gate 1a (design 7.1). */
private const val UPDATE_MS = 1000L

private fun kitScript(context: Context) = context.assets.open("appfile/kit.js").use { it.readBytes().decodeToString() }

/** One call of the kit preview step (Task 13), with its input as one JSON text so each value stays data, as AppFileCompiler sends compileStep. */
private fun previewExpression(text: String, plan: String?, previous: AppFilePrevious?): String {
    val input = JSONObject().put("text", text).put("plan", plan ?: JSONObject.NULL).put("previous",
        previous?.let { JSONObject().put("appFile", it.appFile).put("stateSchema", it.stateSchema).put("state", it.state) } ?: JSONObject.NULL)
    // The step can give its result or a promise of it.
    return "Promise.resolve(previewStep(JSON.parse(${JSONObject.quote(input.toString())}))).then(JSON.stringify)"
}

/** The frame of a result of the preview step as the stage takes it (StagePreviewFrame), or null for { kind: "none" }. */
private fun previewFrame(json: String): StagePreviewFrame? {
    val result = JSONObject(json)
    if (result.getString("kind") != "preview") return null
    val frame = result.getJSONObject("frame")
    return StagePreviewFrame(ToolSpec.parse(frame.get("definition").toString()), frame.get("ui").toString(), frame.get("state").toString(),
        frame.optJSONObject("drawing")?.toString(), frame.getString("holds"))
}

/**
 * The frame of [text] from the preview step of the real kit asset in a new transient isolate, or null for { kind: "none" }. A failed
 * evaluation throws. The stage tests use it until the host step (AppFileCompiler.preview of batch A) can take its place.
 */
internal suspend fun kitPreview(context: Context, text: String, plan: String? = null, previous: AppFilePrevious? = null): StagePreviewFrame? {
    val result = SandboxClient(context).evaluateScript(kitScript(context), previewExpression(text, plan, previous), APP_FILE_COMPILE_MS, APP_FILE_RETURN_BYTES)
    check(result.status == "OK") { "previewStep: ${result.status}" }
    return previewFrame(checkNotNull(result.json))
}

private inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val begin = SystemClock.elapsedRealtimeNanos()
    val value = block()
    return value to SystemClock.elapsedRealtimeNanos() - begin
}

private fun ms(nanos: Long) = Math.round(nanos / 100_000.0) / 10.0

/**
 * Gate 1a (Task 14): the device cost of one preview update, for each prefix by complete line of the three example files. One update is the
 * preview step in a new transient isolate, the parse of its frame, and the draw of the frame in the stage layout until its frame is drawn.
 * Each update prints one JSON line, and the end prints the median and the p90. The controller runs it at the phone and the fold size.
 */
@RunWith(AndroidJUnit4::class)
class StagePreviewCostTest {
    @Test fun eachPreviewUpdateTakesAtMostOneSecond() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // A run with -e previewDeadlineMs 1 must fail, which proves that the test reads real times (Task 14, Step 2).
        val bar = InstrumentationRegistry.getArguments().getString("previewDeadlineMs")?.toLong() ?: UPDATE_MS
        val kit = kitScript(context)
        val sandbox = SandboxClient(context)
        val shown = mutableStateOf<StagePreviewFrame?>(null)
        val drawing = mutableIntStateOf(0)
        val drawn = AtomicInteger(0)
        val updates = mutableListOf<JSONObject>()
        val faults = mutableListOf<String>()
        var prefixes = 0
        val shell = ShellFixture("stage-preview-cost")
        try {
            shell.install()
            shell.launch().use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContentView(ComposeView(activity).apply { setContent { AlphaTheme { Surface(Modifier.fillMaxSize()) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            // The gutter and the canvas height of the stage (DialogueStage). The whole window is a little taller than the stage.
                            val canvasHeight = stageCanvasHeight(maxHeight, LocalConfiguration.current.smallestScreenWidthDp >= DIALOGUE_WIDE_DP)
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = if (maxWidth >= 600.dp) 28.dp else 20.dp, vertical = 12.dp)
                                .drawWithContent { drawContent(); drawn.set(drawing.intValue) }) {
                                shown.value?.let { StagePreviewContent(it, canvasHeight) }
                            }
                        }
                    } } } })
                }
                for (name in FILES) {
                    val lines = plan("template/$name").lines().filter(String::isNotBlank)
                    for (count in 1..lines.size) {
                        prefixes++
                        val text = lines.take(count).joinToString("\n", postfix = "\n")
                        val (opened, start) = timed { runBlocking { sandbox.evaluateScript(kit, "1", APP_FILE_COMPILE_MS, APP_FILE_RETURN_BYTES) } }
                        if (opened.status != "OK") faults += "$name $count: the isolate start gave ${opened.status}"
                        val (result, evaluate) = timed {
                            runBlocking { sandbox.evaluateScript(kit, previewExpression(text, null, null), bar, APP_FILE_RETURN_BYTES) }
                        }
                        val line = JSONObject().put("gate", "1a").put("file", name).put("lines", count).put("start", ms(start)).put("evaluate", ms(evaluate))
                        var frame: StagePreviewFrame? = null
                        var parse = 0L
                        var draw = 0L
                        if (result.status != "OK") faults += "$name $count: ${result.status}"
                        else {
                            timed { previewFrame(checkNotNull(result.json)) }.let { (value, time) -> frame = value; parse = time }
                            if (frame == null) faults += "$name $count: previewStep gave no frame"
                        }
                        frame?.let { next ->
                            val index = drawing.intValue + 1
                            draw = timed {
                                instrumentation.runOnMainSync { shown.value = next; drawing.intValue = index }
                                val end = SystemClock.uptimeMillis() + 10_000
                                while (drawn.get() != index) { check(SystemClock.uptimeMillis() < end) { "$name $count was not drawn" }; SystemClock.sleep(1) }
                            }.second
                            line.put("holds", next.holds)
                        }
                        val update = evaluate + parse + draw
                        line.put("parse", ms(parse)).put("draw", ms(draw)).put("update", ms(update))
                        if (update > bar * 1_000_000) faults += "$name $count: ${ms(update)} ms"
                        println(line)
                        updates += line
                    }
                }
            }
        } finally { shell.remove() }
        val sorted = updates.map { it.getDouble("update") }.sorted()
        val starts = updates.map { it.getDouble("start") }.sorted()
        val metrics = context.resources.configuration
        println(JSONObject().put("gate", "1a").put("updates", sorted.size).put("median", sorted.getOrNull(sorted.size / 2))
            .put("p90", sorted.getOrNull(ceil(sorted.size * 0.9).toInt() - 1)).put("startMedian", starts.getOrNull(starts.size / 2))
            .put("widthDp", metrics.screenWidthDp).put("heightDp", metrics.screenHeightDp).put("smallestWidthDp", metrics.smallestScreenWidthDp))
        assertEquals("each prefix gives one update", prefixes, updates.size)
        assertTrue("updates above $bar ms or with no frame: $faults", prefixes > 0 && faults.isEmpty())
    }

    private companion object {
        /**
         * The example files of the plan (Global Constraints): a scene, items, and cells, a table and a chart at their caps (block 2 plan, stage 5),
         * and a play scene with image objects (C5).
         */
        val FILES = listOf("ship", "word-parts", "majority", "table-caps", "chart-caps", "orbit")
    }
}

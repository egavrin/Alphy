package com.alpha.assistant.tools

import android.app.AlertDialog
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.MainActivity
import com.alpha.assistant.tools.three.ThreeSceneForeground
import com.alpha.assistant.tools.three.ThreeSceneSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeSceneLiveWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val name get() = requireNotNull(args.getString("threeScene"))
    private val runId get() = requireNotNull(args.getString("threeRun"))
    private val out get() = File(context.getExternalFilesDir(null), "three-live/$runId").apply { mkdirs() }

    @Test fun reopenLiveFromDisk() = runBlocking {
        assumeTrue(args.getString("threeLive") == "true")
        val id = File(out, "scene-id.txt").readText()
        val tool = ToolStore(context, "three-live-$runId.db").use { checkNotNull(it.tool(id)) }
        val spec = ToolSpec.parsePersisted(tool.spec)
        val expectedState = File(out, "expected-state.json").takeIf(File::isFile)?.readText()
        ActivityScenario.launch(MainActivity::class.java).use {
            withContext(Dispatchers.Main.immediate) {
                val activity = ThreeSceneForeground.activity()
                val session = ThreeSceneSession(activity, spec, tool.id, tool.revision, tool.state, tool.ui)
                val container = FrameLayout(activity).apply {
                    addView(session.web.view, FrameLayout.LayoutParams(-1, 640))
                }
                val dialog = AlertDialog.Builder(activity).setTitle(spec.title).setView(container).create()
                try {
                    dialog.show()
                    dialog.window!!.setLayout(920, 760)
                    delay(120)
                    session.start()
                    if (expectedState != null) check(session.snapshot.state == expectedState)
                    val frames = mutableListOf<List<Int>>()
                    for (i in 0..3) {
                        repeat(30) {
                            session.frame(50)
                            delay(17)
                        }
                        val frame = session.web.capture(checkNotNull(dialog.window))
                        try {
                            File(out, "reopen-$i.png").outputStream().use {
                                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            frames += List(1024) { pixel ->
                                frame.getPixel(pixel % 32 * frame.width / 32, pixel / 32 * frame.height / 32) and 0xf0f0f0
                            }
                        } finally { frame.recycle() }
                    }
                    check(frames.any { pixels -> pixels.groupingBy { it }.eachCount().values.max() < 1014 }) {
                        "The reopened viewport did not contain visible scene geometry."
                    }
                    if (name == "earth") check(frames.distinct().size >= 2) { "The reopened orbit did not move." }
                    File(out, "reopened-state.json").writeText(JSONObject(session.snapshot.state).toString())
                } finally {
                    dialog.dismiss()
                    session.close()
                }
            }
        }
    }
}

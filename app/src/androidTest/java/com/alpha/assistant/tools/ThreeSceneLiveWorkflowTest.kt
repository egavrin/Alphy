package com.alpha.assistant.tools

import android.app.AlertDialog
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.MainActivity
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.three.ThreeSceneForeground
import com.alpha.assistant.tools.three.ThreeSceneSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    @Test fun generateFromLiveProvider() = runBlocking {
        assumeTrue(args.getString("threeLive") == "true")
        val key = OneUseCredential.receive(runId) { File(out, "phase.txt").writeText("credentialReady") }
        val prompt = if (name == "earth") {
            "Create a Three.js mini app with a blue Earth fixed at the center and a small white Moon on a circular orbit. " +
                "Use an angled camera, enable camera dragging, and support pause and resume. The Moon should pass in front " +
                "of and behind Earth and take eight seconds to complete one orbit. Use a dark background and show both bodies clearly."
        } else {
            "Создай Three.js мини-приложение «Мастерская слов». Поставь светлый кубик с большой буквой М слева, " +
                "а пустую ячейку справа. Перетаскивание кубика в ячейку должно показать «Готово», " +
                "проиграть локальный cue.wav и запустить взмах руки у локального robot.glb. " +
                "Сохрани завершение задания, чтобы оно восстановилось после открытия карточки."
        }
        File(out, "prompt.txt").writeText(prompt)
        val confirmation = launch(Dispatchers.IO) {
            while (true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val button = root?.findAccessibilityNodeInfosByText("Save scene")
                    ?.firstOrNull { it.isClickable && it.isEnabled }
                if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    File(out, "preview-accepted.txt").writeText("Clicked the enabled scene-preview Save button.")
                    break
                }
                delay(350)
            }
        }
        val cloud = CloudSession()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, "three-live-$runId.db").use { store ->
                    val live = DeepSeekProvider()
                    val provider = object : ChatProvider by live {
                        private var sequence = 0

                        override suspend fun respond(
                            key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit,
                        ) {
                            val capture = File(out, "model-${++sequence}-${request.generationStage}.json")
                            if (request.turns.size > 1) {
                                File(out, "instruction-$sequence.txt").writeText(request.turns.last().text)
                            }
                            live.respond(key, request, session) { event ->
                                if (event is CloudEvent.Text) capture.appendText(event.value)
                                emit(event)
                            }
                        }
                    }
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft(prompt, null)
                        val trace = ToolGenerationTrace(java.util.UUID.fromString(draft.id), onEvent = { event ->
                            synchronized(this) { File(out, "trace.txt").appendText(event.toString() + "\n") }
                        })
                        engine.generate(draft.id, key, emptyList(), cloud, previewObserver = null, trace = trace)
                        val record = engine.accept(draft.id)
                        check(ToolSpec.parsePersisted(record.spec).threeScene)
                        File(out, "scene.json").writeText(record.spec)
                        File(out, "scene-id.txt").writeText(record.id)
                        File(out, "phase.txt").writeText("accepted")
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally {
            cloud.cancel()
            confirmation.cancelAndJoin()
        }
    }

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

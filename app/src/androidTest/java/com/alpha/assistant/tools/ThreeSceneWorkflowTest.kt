package com.alpha.assistant.tools

import android.app.AlertDialog
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.three.ThreeSceneAssets
import com.alpha.assistant.tools.three.ThreeSceneForeground
import com.alpha.assistant.tools.three.ThreeSceneSession
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.seedOpenChat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeSceneWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null), "three-scene-evidence").apply { mkdirs() }

    @Test fun repairedOrbitSavesAndReopens() = runBlocking {
        val result = generate("earth", injectStatic = true)
        assertEquals(1, result.repairs)
        val spec = ToolSpec.parsePersisted(result.record.spec)
        assertTrue(spec.threeScene)
        assertTrue(spec.source.contains("const enabled = true;"))
        ActivityScenario.launch(MainActivity::class.java).use {
            onScene(result.record) { scene, dialog ->
                val before = JSONObject(scene.snapshot.state).getDouble("phase")
                repeat(35) { scene.frame(50) }
                val after = JSONObject(scene.snapshot.state).getDouble("phase")
                assertTrue(after > before + 1)
                saveFrame("earth-orbit", scene, dialog)
                scene.event(JSONObject().put("type", "pause"))
                repeat(3) { scene.frame(50) }
                assertEquals(after, JSONObject(scene.snapshot.state).getDouble("phase"), 0.0)
                scene.event(JSONObject().put("type", "resume"))
                scene.event(scene.pointer("pointerdown", 0, .50, .50))
                scene.event(scene.pointer("pointermove", 0, .67, .50))
                scene.event(scene.pointer("pointerup", 0, .67, .50))
                scene.frame(33)
                saveFrame("earth-camera", scene, dialog)
                assertTrue(JSONObject(scene.snapshot.state).getDouble("phase") > after)
            }
        }
    }

    @Test fun workshopDragsPlaysAndRestoresSavedCheckpoint() = runBlocking {
        val result = generate("workshop")
        val restartRun = InstrumentationRegistry.getArguments().getString("threeRun")
        require(restartRun == null || restartRun.matches(Regex("[a-f0-9]{32}")))
        val name = if (restartRun == null) "three-workshop-" + UUID.randomUUID() + ".db" else "three-live-$restartRun.db"
        var retained = false
        try {
            val saved = ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, name).use { store ->
                    store.saveTool(result.record, true)
                    val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                    val controller = ToolController(context.applicationContext as Application, controllerScope, result.provider,
                        SecureKeyStore(context), store)
                    try {
                        onScene(result.record) { scene, dialog ->
                            saveFrame("workshop-initial", scene, dialog)
                            scene.event(scene.pointer("pointerdown", 0, .30, .50))
                            scene.event(scene.pointer("pointermove", 0, .70, .50))
                            scene.event(scene.pointer("pointerup", 0, .70, .50))
                            assertEquals("completed", scene.lastAudio)
                            scene.event(scene.pointer("pointerup", 0, .70, .50))
                            assertNull(scene.lastAudio)
                            assertTrue(JSONObject(scene.snapshot.state).getBoolean("placed"))
                            repeat(6) { scene.frame(50) }
                            saveFrame("workshop-drop", scene, dialog)
                            controller.saveThreeScene(result.record, scene.snapshot)
                        }
                    } finally {
                        controller.closeAndJoin()
                        controllerScope.cancel()
                    }
                }
            }
            ToolStore(context, name).use { store ->
                val reopened = checkNotNull(store.tool(saved.id))
                assertEquals(saved.state, reopened.state)
                ActivityScenario.launch(MainActivity::class.java).use {
                    onScene(reopened) { scene, dialog ->
                        assertEquals("Готово", JSONObject(scene.snapshot.state).getString("status"))
                        saveFrame("workshop-reopened", scene, dialog)
                    }
                }
            }
            if (restartRun != null) {
                val evidence = File(context.getExternalFilesDir(null), "three-live/$restartRun").apply { mkdirs() }
                File(evidence, "scene-id.txt").writeText(saved.id)
                File(evidence, "expected-state.json").writeText(saved.state)
                retained = true
            }
        } finally {
            if (!retained) context.deleteDatabase(name)
        }
    }

    @Test fun savedScenesOpenFromChatAndHandleNativeInput() = runBlocking {
        for (name in listOf("earth", "workshop")) {
            val tool = generate(name).record
            val spec = ToolSpec.parsePersisted(tool.spec)
            val fixture = ShellFixture("three-chat")
            val robot = ShellRobot(fixture.name)
            try {
                val chat = "three-chat"
                fixture.store.saveConversation(Conversation(chat, spec.title, 1, 2, messages = listOf(
                    Message("request", "user", "Create a Three.js scene.", 1, "sent"),
                    Message("scene", "assistant", spec.title, 2, "complete", replyTo = "request",
                        toolId = tool.id, toolRevision = tool.revision),
                )))
                fixture.store.seedOpenChat(chat)
                ToolStore(context, "${fixture.store.databaseName}-tools.db").use { store ->
                    store.saveTool(tool, revision = true)
                    fixture.install(saveKey = false)
                    fixture.launch().use {
                        robot.click(robot.text(R.string.tool_open))
                        robot.waitFor("Pause")
                        fun state() = JSONObject(checkNotNull(store.tool(tool.id)).state)
                        if (name == "earth") {
                            robot.waitUntil { state().getDouble("phase") > 0 }
                            robot.click("Pause")
                            robot.waitFor("Resume")
                        } else {
                            val bounds = Rect()
                            robot.waitUntil {
                                robot.find("3D scene")?.getBoundsInScreen(bounds)
                                !bounds.isEmpty
                            }
                            robot.waitUntil {
                                val frame = checkNotNull(robot.automation.takeScreenshot())
                                try {
                                    val bg = frame.getPixel(bounds.left + 5, bounds.top + 5)
                                    (1..7).any { column ->
                                        (1..7).any { row ->
                                            frame.getPixel(bounds.left + bounds.width() * column / 8,
                                                bounds.top + bounds.height() * row / 8) != bg
                                        }
                                    }
                                } finally { frame.recycle() }
                            }
                            val down = SystemClock.uptimeMillis()
                            fun touch(action: Int, fraction: Float) {
                                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                                    bounds.left + fraction * bounds.width(), bounds.exactCenterY(), 0)
                                try {
                                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                                    assertTrue(robot.automation.injectInputEvent(event, true))
                                } finally { event.recycle() }
                            }
                            touch(MotionEvent.ACTION_DOWN, .30f)
                            repeat(8) { step ->
                                touch(MotionEvent.ACTION_MOVE, .30f + .40f * (step + 1) / 8)
                                delay(25)
                            }
                            touch(MotionEvent.ACTION_UP, .70f)
                            robot.waitUntil { state().getBoolean("placed") }
                        }
                    }
                }
            } finally {
                fixture.model?.tools?.closeAndJoin()
                fixture.remove()
            }
        }
    }

    @Test fun aFailedRefinementKeepsTheAcceptedRevision() = runBlocking {
        val result = generate("earth")
        val name = "three-refinement-${UUID.randomUUID()}.db"
        var patchRequests = 0
        val provider = object : ChatProvider by result.provider {
            override suspend fun respond(
                key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit,
            ) {
                if (request.generationStage != CloudGenerationStage.REFINE) {
                    result.provider.respond(key, request, session, emit)
                } else {
                    val patch = if (++patchRequests == 1) {
                        """{"sourceEdits":[{"search":"const enabled = true;","replace":"const enabled = false;"}]}"""
                    } else """{"fields":{"title":"Earth and Moon"}}"""
                    emit(CloudEvent.Text(patch))
                }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, name).use { store ->
                    store.saveTool(result.record, revision = true)
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft("Change the Earth color and keep the Moon moving.", result.record.id)
                        val failure = runCatching {
                            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
                        }.exceptionOrNull() as? CloudFailure
                        assertEquals("three_behavior", failure?.code)
                        assertEquals(2, patchRequests)
                        assertEquals(result.record, store.tool(result.record.id))
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun cancelingThePreviewStopsAnInFlightVisualReview() = runBlocking {
        val reviewing = CompletableDeferred<Unit>()
        val generation = async(Dispatchers.IO) {
            runCatching { generate("shader", onVisual = { reviewing.complete(Unit); awaitCancellation() }) }
        }
        try {
            withTimeout(30_000) { reviewing.await() }
            ShellRobot("three-visual-cancel").waitUntil {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                root?.findAccessibilityNodeInfosByText("Cancel").orEmpty().any {
                    it.isClickable && it.isEnabled &&
                        it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            val failure = withTimeout(5_000) { generation.await().exceptionOrNull() } as? CloudFailure
            assertEquals("three_declined", failure?.code)
        } finally {
            generation.cancel()
        }
    }

    @Test fun generatedShaderDrawsAThirdScene() = runBlocking {
        val result = generate("shader")
        assertTrue(ToolSpec.parsePersisted(result.record.spec).source.contains("new THREE.ShaderMaterial"))
        ActivityScenario.launch(MainActivity::class.java).use {
            onScene(result.record) { scene, dialog ->
                repeat(8) { scene.frame(33) }
                saveFrame("shader", scene, dialog)
            }
        }
    }

    @Test fun recordFixture() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("threeRecord")
        org.junit.Assume.assumeTrue(name in setOf("earth", "workshop"))
        val liveRun = args.getString("threeRecordRun")
        val from = args.getString("threeRecordFrom")?.toDouble() ?: .30
        val to = args.getString("threeRecordTo")?.toDouble() ?: .70
        val record = if (liveRun == null) generate(checkNotNull(name)).record else {
            require(liveRun.matches(Regex("[a-f0-9]{32}")))
            val id = File(context.getExternalFilesDir(null), "three-live/$liveRun/scene-id.txt").readText()
            ToolStore(context, "three-live-$liveRun.db").use { store -> checkNotNull(store.tool(id)) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onScene(record) { scene, dialog ->
                dialog.window!!.setLayout(920, 620)
                delay(120)
                scene.viewport()
                File(output, "record-$name-ready.txt").writeText("ready")
                if (name == "earth") {
                    repeat(245) {
                        scene.frame(33)
                        delay(22)
                    }
                } else {
                    repeat(30) { scene.frame(33); delay(26) }
                    val before = scene.snapshot.state
                    scene.event(scene.pointer("pointerdown", 0, from, .50))
                    repeat(32) { index ->
                        scene.event(scene.pointer("pointermove", 0, from + (to - from) * (index + 1) / 32, .50))
                        scene.frame(33)
                        delay(24)
                    }
                    scene.event(scene.pointer("pointerup", 0, to, .50))
                    assertEquals("completed", scene.lastAudio)
                    assertNotEquals(before, scene.snapshot.state)
                    repeat(135) { scene.frame(33); delay(24) }
                }
                File(output, "record-$name-done.txt").writeText("complete")
                delay(800)
            }
        }
    }

    @Test fun importsAUserSelectedGlbAndRejectsAnExternalReference() = runBlocking {
        val file = File(context.filesDir, "chat-camera/three-import-" + UUID.randomUUID() + ".glb")
        file.parentFile?.mkdirs()
        val original = context.assets.open("threejs/catalog/robot.glb").use { it.readBytes() }
        try {
            file.writeBytes(original)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".chat-camera", file)
            val assets = ThreeSceneAssets(context)
            val attachment = assets.import(uri, "Robot.glb", "model/gltf-binary")
            val refs = ThreeSceneAssets.attachmentRefs(listOf(attachment))
            assertEquals(1, refs.size)
            assertEquals(original.size.toLong(), refs.single().size)
            val asset = assets.workerAssets(refs).first()
            assertArrayEquals(original, asset.open().use { it.readBytes() })
            val malformed = original.toString(Charsets.ISO_8859_1).replace("\"name\":\"Robot\"", "\"uri\":\"/abcde\"")
                .toByteArray(Charsets.ISO_8859_1)
            file.writeBytes(malformed)
            assertTrue(runCatching { assets.import(uri, "External.glb", "model/gltf-binary") }.isFailure)
        } finally { file.delete() }
    }

    private data class Run(val record: ToolRecord, val provider: Provider, val repairs: Int)

    private suspend fun generate(name: String, injectStatic: Boolean = false, onVisual: suspend () -> Unit = {}): Run {
        val prompt = when (name) {
            "earth" -> "Create an Earth and Moon Three.js scene. The Moon orbits Earth every eight seconds. Add camera controls and pause."
            "workshop" -> "Создай 3D-мастерскую. Перетащи кубик М в ячейку. Проиграй локальный звук, покажи анимацию робота и сохрани прогресс."
            else -> "Create a Three.js torus knot with a moving custom shader."
        }
        val source = instrumentation.context.assets.open("threejs/$name.js").bufferedReader().use { it.readText() }
        val provider = Provider(name, source, injectStatic, onVisual)
        val database = "three-generation-" + UUID.randomUUID() + ".db"
        try {
            return ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft(prompt, null)
                        engine.generate(draft.id, "fixture", emptyList(), CloudSession())
                        val record = engine.accept(draft.id)
                        File(output, "$name-generated.json").writeText(record.spec)
                        File(output, "$name-request.txt").writeText(prompt)
                        File(output, "$name-repairs.txt").writeText(provider.repairs.toString())
                        Run(record, provider, provider.repairs)
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    private suspend fun <T> onScene(
        tool: ToolRecord, body: suspend (ThreeSceneSession, AlertDialog) -> T,
    ): T = withContext(Dispatchers.Main.immediate) {
        val activity = ThreeSceneForeground.activity()
        val spec = ToolSpec.parsePersisted(tool.spec)
        val session = ThreeSceneSession(activity, spec, tool.id, tool.revision, tool.state, tool.ui)
        val container = android.widget.FrameLayout(activity).apply {
            addView(session.web.view, android.widget.FrameLayout.LayoutParams(-1, 440))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(spec.title).setView(container).create()
        try {
            dialog.show()
            dialog.window!!.setLayout(680, 500)
            delay(90)
            session.start()
            body(session, dialog)
        } finally {
            dialog.dismiss()
            session.close()
        }
    }

    private suspend fun saveFrame(name: String, scene: ThreeSceneSession, dialog: AlertDialog) {
        delay(55)
        val bitmap = scene.web.capture(checkNotNull(dialog.window))
        try {
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private inner class Provider(
        val name: String, val source: String, val injectStatic: Boolean, val onVisual: suspend () -> Unit,
    ) : ChatProvider {
        override val supportsSceneVision = true
        var repairs = 0
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            val text = when {
                request.generationStage == CloudGenerationStage.PLAN -> plan()
                request.generationStage == null -> {
                    val frames = request.turns.flatMap { it.images }
                    assertTrue(frames.isNotEmpty())
                    frames.forEachIndexed { index, frame ->
                        val image = android.util.Base64.decode(frame.substringAfter(','), android.util.Base64.DEFAULT)
                        File(output, "$name-checked-$index.png").writeBytes(image)
                    }
                    onVisual()
                    """{"issue":null}"""
                }
                request.turns.last().text.contains("Return one JSON patch") -> {
                    repairs++
                    android.util.Log.i("ThreeSceneTest", name + " " + request.turns.last().text.take(380))
                    """{"sourceEdits":[{"search":"const enabled = false;","replace":"const enabled = true;"}]}"""
                }
                else -> candidate()
            }
            emit(CloudEvent.Text(text))
        }

        private fun plan(): String {
            val motion = """{"slots":{"view":{"kind":"surface"},"render":{"kind":"geometry"}},"steps":[
                {"op":"frames","slot":"view","count":6,"observe":"render","changes":2}]}"""
            val workshop = """{"slots":{"view":{"kind":"surface"},"render":{"kind":"geometry"},"message":{"kind":"value"},"audio":{"kind":"audio"},"saved":{"kind":"state"}},"steps":[
                {"op":"capture","slot":"render","as":"initial"},
                {"op":"surface","slot":"view","event":"pointer","phase":"down","x":0.30,"y":0.50},
                {"op":"surface","slot":"view","event":"pointer","phase":"move","x":0.70,"y":0.50},
                {"op":"surface","slot":"view","event":"pointer","phase":"up","x":0.70,"y":0.50},
                {"op":"assert","slot":"render","test":"changed","capture":"initial"},
                {"op":"assert","slot":"message","test":"equals","value":"Готово"},
                {"op":"assert","slot":"audio","test":"equals","value":"completed"},
                {"op":"surface","slot":"view","event":"reopen"},
                {"op":"assert","slot":"saved","test":"equals","value":true}]}"""
            val checks = JSONObject().put("status", "checkable").put("scenario", JSONObject(if (name == "workshop") workshop else motion))
            return JSONObject().put("wholeScreen", true).put("language", if (name == "workshop") "ru" else "en")
                .put("runtime", "threejs-worker").put("scene", JSONObject().put("placement", "stage")
                    .put("brief", "Render the requested interactive 3D scene.").put("checks", checks)).toString()
        }

        private fun candidate(): String {
            val data = JSONObject("""{"formatVersion":3,"capabilities":[],"runtime":{"kind":"threejs-worker","version":1},
                "behaviorBindings":{"view":"scene","render":{"surface":"scene","path":[0]}}}""")
            data.put("title", if (name == "workshop") "Мастерская слов" else if (name == "earth") "Earth and Moon" else "Three.js shader")
            data.put("description", if (name == "workshop") "Собери букву" else "An interactive 3D scene")
            data.put("source", if (injectStatic) source.replace("const enabled = true;", "const enabled = false;") else source)
            if (name == "workshop") {
                data.put("stateSchema", JSONObject("""{"placed":"boolean","status":"string"}"""))
                data.put("initialState", JSONObject("""{"placed":false,"status":"Перетащи М"}"""))
                data.put("ui", JSONObject("""{"type":"column","children":[{"type":"text","text":"Задание","bind":"status"}]}"""))
                data.getJSONObject("behaviorBindings").put("message", JSONArray().put(0)).put("saved", "placed")
            } else {
                data.put("stateSchema", JSONObject("""{"phase":"number","paused":"boolean"}"""))
                data.put("initialState", JSONObject("""{"phase":0,"paused":false}"""))
                data.put("ui", JSONObject("""{"type":"column","children":[{"type":"text","text":"3D scene"}]}"""))
            }
            return data.toString()
        }
    }
}

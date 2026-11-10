package com.alpha.assistant.tools

import android.app.AlertDialog
import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
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
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.three.ThreeSceneAsset
import com.alpha.assistant.tools.three.ThreeSceneAssetCleanup
import com.alpha.assistant.tools.three.ThreeSceneAssets
import com.alpha.assistant.tools.three.ThreeSceneForeground
import com.alpha.assistant.tools.three.ThreeSceneSession
import com.alpha.assistant.tools.three.ThreeSceneWebView
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.seedOpenChat
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
                    fixture.launch().use { activity ->
                        robot.click(robot.text(R.string.tool_open))
                        robot.waitFor("Pause")
                        fun state() = JSONObject(checkNotNull(store.tool(tool.id)).state)
                        if (name == "earth") {
                            robot.waitUntil { state().getDouble("phase") > 0 }
                            robot.click("Pause")
                            robot.waitFor("Resume")
                            robot.waitUntil { state().getBoolean("paused") }
                            val paused = state().toString()
                            fun findWebView(view: View): WebView? = when (view) {
                                is WebView -> view
                                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
                                else -> null
                            }
                            var inlineView: WebView? = null
                            activity.onActivity { inlineView = findWebView(it.window.decorView) }
                            val retainedView = checkNotNull(inlineView)
                            fun height(): Int {
                                var result = 0
                                activity.onActivity {
                                    if (retainedView.isAttachedToWindow) result = retainedView.height
                                }
                                return result
                            }
                            val inlineHeight = height()
                            assertTrue(inlineHeight > 0)
                            val enter = robot.text(R.string.tool_full_screen_enter)
                            val exit = robot.text(R.string.tool_full_screen_exit)
                            fun enterFullscreen() {
                                robot.click(enter)
                                robot.waitFor(exit)
                                robot.waitFor("Resume")
                                robot.waitUntil { height() > inlineHeight }
                                assertEquals(paused, state().toString())
                            }
                            enterFullscreen()
                            robot.click(exit)
                            robot.waitFor(enter)
                            robot.waitUntil { height() == inlineHeight }
                            enterFullscreen()
                            robot.back()
                            robot.waitFor(enter)
                            robot.waitUntil { height() == inlineHeight }
                            enterFullscreen()
                            activity.moveToState(Lifecycle.State.STARTED)
                            robot.waitUntil { height() == 0 }
                            activity.moveToState(Lifecycle.State.RESUMED)
                            robot.waitFor(enter)
                            robot.waitFor("Resume")
                            assertEquals(paused, state().toString())
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

    @Test fun repairDoesNotSendLocalAssetData() = runBlocking {
        val marker = "SYNTHETIC_ASSET_PRIVACY_MARKER"
        val file = File(context.filesDir, "chat-camera/private-${UUID.randomUUID()}.glb")
        file.parentFile?.mkdirs()
        val database = "three-privacy-${UUID.randomUUID()}.db"
        val lease = ThreeSceneAssetCleanup(context).lease()
        try {
            file.writeBytes(catalogGlb { it.getJSONObject("asset").put("extras", JSONObject().put("fixture", marker)) })
            val uri = FileProvider.getUriForFile(context, context.packageName + ".chat-camera", file)
            val assets = ThreeSceneAssets(context)
            val attachment = assets.import(uri, "Private.glb", "model/gltf-binary")
            val refs = ThreeSceneAssets.attachmentRefs(listOf(attachment))
            val original = fixtureSource("earth")
            val injected = listOf(
                "throw Error(new TextDecoder().decode(await readAsset('${refs.single().id}')).match(/SYNTHETIC_ASSET_[A-Z_]+/)[0]);",
                "await Promise.reject(Error(new TextDecoder().decode(await readAsset('${refs.single().id}')).match(/SYNTHETIC_ASSET_[A-Z_]+/)[0]));",
                "state.phase = new TextDecoder().decode(await readAsset('${refs.single().id}')).match(/SYNTHETIC_ASSET_[A-Z_]+/)[0];",
            )
            val source = original.replace("state, viewport}", "state, viewport, readAsset}")
                .replace("const enabled = true;", "const enabled = true; ${injected.first()}")
            val fixture = Provider("earth", source, false) {}
            var repairs = 0
            val provider = object : ChatProvider by fixture {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    assertFalse(request.systemInstructions.contains(marker))
                    assertTrue(request.turns.none { it.text.contains(marker) || it.images.isNotEmpty() })
                    if (request.turns.last().text.contains("Return one JSON patch")) {
                        val previous = injected[repairs++]
                        emit(CloudEvent.Text(sourcePatch(previous, injected.getOrElse(repairs) { "" })))
                    } else fixture.respond(key, request, session, emit)
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft("Create a Three.js orbit and read the selected asset.", null)
                        val events = mutableListOf<ToolGenerationTraceEvent>()
                        val trace = ToolGenerationTrace(UUID.fromString(draft.id), onEvent = { events += it })
                        approvePreview {
                            engine.generate(draft.id, "fixture", emptyList(), CloudSession(), previewObserver = null,
                                trace = trace, sceneAssets = refs)
                        }
                        assertEquals(3, repairs)
                        val failures = events.filterIsInstance<ToolGenerationTraceEvent.Attempt>()
                            .filter { it.stage == ToolGenerationStage.BEHAVIOR }.map { it.failureCode }
                        assertEquals(listOf("three_init_failed", "three_init_failed", "three_invalid_snapshot"), failures)
                        val exported = events.joinToString("\n")
                        assertFalse(exported.contains(marker))
                        File(output, "privacy-repair-trace.txt").writeText(exported)
                        assertEquals(refs, ToolSpec.parsePersisted(engine.accept(draft.id).spec).sceneAssets)
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally {
            file.delete()
            context.deleteDatabase(database)
            lease.close()
        }
    }

    @Test fun refinementRepairsStartupAndPreservesCompletedProgressThroughRestore() = runBlocking {
        val result = generate("workshop")
        val marker = "SYNTHETIC_CHECKPOINT_PRIVACY_MARKER"
        val completed = result.record.copy(state = JSONObject(result.record.state)
            .put("placed", true).put("status", marker).toString())
        val database = "three-checkpoint-${UUID.randomUUID()}.db"
        var patches = 0
        val provider = object : ChatProvider by result.provider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                assertFalse(request.systemInstructions.contains(marker))
                assertTrue(request.turns.none { it.text.contains(marker) })
                if (request.generationStage != CloudGenerationStage.REFINE) {
                    result.provider.respond(key, request, session, emit)
                    return
                }
                val color = "scene.background = new THREE.Color(0x16322f);"
                val throwing = "if (state.placed) throw Error(state.status);"
                val reset = "if (state.placed) state.status = 'reset';"
                val patch = when (++patches) {
                    1 -> sourcePatch(color, "$color $throwing").let(::JSONObject).put("fields", JSONObject()
                        .put("behaviorBindings", JSONObject("""{"message":[0],"saved":"placed"}"""))).toString()
                    2 -> sourcePatch(throwing, reset)
                    else -> sourcePatch(reset, "").let(::JSONObject).put("fields", JSONObject()
                        .put("stateSchema", JSONObject("""{"placed":"boolean","status":"string","stars":"number"}"""))
                        .put("initialState", JSONObject("""{"placed":false,"status":"Перетащи М","stars":3}"""))).toString()
                }
                emit(CloudEvent.Text(patch))
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    store.saveTool(completed, revision = true)
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft("Change the appearance of the Three.js Workshop.", completed.id)
                        approvePreview { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                        assertEquals(3, patches)
                        val saved = engine.accept(draft.id)
                        assertEquals(marker, JSONObject(saved.state).getString("status"))
                        assertEquals(3, JSONObject(saved.state).getInt("stars"))
                        assertTrue(engine.restoreAvailability(saved, completed.revision).reason == null)
                        val restored = engine.rollback(saved.id, saved.revision, saved.stateVersion, completed.revision)
                        assertEquals(saved.state, restored.state)
                        assertEquals(result.record.revision + 2, restored.revision)
                        onScene(restored) { scene, _ -> assertEquals(restored.state, scene.snapshot.state) }
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun rejectsOverdeepGlbInEitherNodeOrder() = runBlocking {
        val file = File(context.filesDir, "chat-camera/depth-${UUID.randomUUID()}.glb")
        file.parentFile?.mkdirs()
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".chat-camera", file)
            for (levels in listOf(32, 33, 64)) for (childFirst in listOf(false, true)) {
                file.writeBytes(catalogGlb { glb ->
                    val nodes = JSONArray()
                    for (i in 0 until levels) {
                        val child = if (childFirst) i - 1 else i + 1
                        nodes.put(JSONObject().apply {
                            if (child in 0 until levels) put("children", JSONArray().put(child))
                        })
                    }
                    glb.put("nodes", nodes).put("animations", JSONArray())
                    glb.getJSONArray("scenes").getJSONObject(0).put("nodes", JSONArray().put(if (childFirst) levels - 1 else 0))
                })
                val result = runCatching { ThreeSceneAssets(context).import(uri, "Depth.glb", "model/gltf-binary") }
                assertEquals("$levels levels, child-first=$childFirst", levels <= 32, result.isSuccess)
            }
        } finally { file.delete() }
    }

    @Test fun idleCleanupKeepsSharedAndPreservedAssets() = runBlocking {
        val directory = File(context.cacheDir, "three-collect-${UUID.randomUUID()}").apply { mkdirs() }
        val chatDb = "three-cleanup-chat-${UUID.randomUUID()}"
        val library = "three-cleanup-${UUID.randomUUID()}.db"
        val namedLibrary = "three-cleanup-library-${UUID.randomUUID()}"
        val preserved = "${ToolLibraryStorage.DEFAULT}.library-${UUID.randomUUID()}.db"
        val active = "${ToolLibraryStorage.DEFAULT}.library-${UUID.randomUUID()}.db"
        val pauseScan = AtomicBoolean(false)
        val verifyMissing = AtomicBoolean(false)
        val missingScans = LinkedBlockingQueue<Unit>()
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val privateContext = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = directory
            override fun getDatabasePath(name: String): File {
                if (name == preserved && pauseScan.compareAndSet(true, false)) {
                    paused.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                if (name == preserved && verifyMissing.get()) missingScans.add(Unit)
                return if (name in setOf(preserved, active, ToolLibraryStorage.DEFAULT)) File(directory, name)
                    else super.getDatabasePath(name)
            }
            override fun databaseList() = listOf(chatDb, preserved, active, library, namedLibrary)
                .filter { name ->
                    val file = if (name in setOf(preserved, active)) File(directory, name)
                        else super.getDatabasePath(name)
                    file.exists()
                }.toTypedArray()
        }
        val files = File(directory, "threejs-assets").apply { mkdirs() }
        val cleanup = ThreeSceneAssetCleanup(privateContext)
        val shared = "1".repeat(64)
        val old = "2".repeat(64)
        val candidate = "3".repeat(64)
        val unused = "4".repeat(64)
        val publishedDuringScan = "5".repeat(64)
        val namedAsset = "6".repeat(64)
        val part = "import-orphan.part"
        val unrelated = "keep.txt"
        fun ref(id: String) = JSONObject().put("id", id).put("name", "Fixture").put("mime", "model/gltf-binary").put("size", 1)
        val base = ToolSpec.parse(JSONObject()
            .put("formatVersion", 3).put("runtime", JSONObject("""{"kind":"threejs-worker","version":1}"""))
            .put("title", "Fixture").put("description", "").put("capabilities", JSONArray())
            .put("stateSchema", JSONObject("""{"phase":"number","paused":"boolean"}"""))
            .put("initialState", JSONObject("""{"phase":0,"paused":false}"""))
            .put("ui", JSONObject("""{"type":"text","text":"Fixture"}"""))
            .put("source", fixtureSource("earth")).toString())
        fun spec(id: String) = base.withThreeAssets(ThreeSceneAssets.read(JSONArray().put(ref(id)))).json
        fun present() = files.list()?.toSet().orEmpty()
        fun attachment(id: String) = com.alpha.assistant.data.Attachment("alphy-three-asset:$id", "Fixture",
            ThreeSceneAssets.ATTACHMENT_MIME, ref(id).toString())
        val now = System.currentTimeMillis()
        try {
            ShellStore(privateContext, chatDb).use { chat ->
                ToolStore(privateContext, library).use { tools ->
                    val lease = cleanup.lease()
                    try {
                        for (name in listOf(shared, old, candidate, unused, namedAsset, part, unrelated)) File(files, name).writeText("x")
                        val conversation = Conversation("chat", "Fixture", now, now,
                            refinementDraft = Draft(attachments = listOf(attachment(shared))))
                        chat.saveConversation(conversation)
                        chat.saveNewDraft(Draft(attachments = listOf(attachment(shared))))
                        val record = ToolRecord("scene", 1, 1, spec(old), base.initialState, base.ui.toString(), null, true, false, now)
                        tools.saveTool(record, true)
                        ToolStore(privateContext, namedLibrary).use {
                            it.saveTool(record.copy(spec = spec(namedAsset)), true)
                        }
                        tools.saveTool(record.copy(revision = 2, spec = spec(shared)), true)
                        tools.saveDraft(ToolDraft("preview", null, 0, 0, "Fixture", spec(candidate), "{}", "{}", null))
                        tools.close()
                        check(context.getDatabasePath(library).renameTo(privateContext.getDatabasePath(preserved)))
                        cleanup.request()
                        assertEquals(setOf(shared, old, candidate, unused, namedAsset, part, unrelated), present())
                    } finally { lease.close() }
                    withTimeout(5_000) { while (unused in present() || part in present()) delay(25) }
                    assertEquals(setOf(shared, old, candidate, namedAsset, unrelated), present())
                    File(files, publishedDuringScan).writeText("x")
                    File(files, unused).writeText("x")
                    pauseScan.set(true)
                    cleanup.request()
                    try {
                        assertTrue(withContext(Dispatchers.IO) { paused.await(5, TimeUnit.SECONDS) })
                        cleanup.lease().use {
                            chat.saveNewDraft(Draft(attachments = listOf(attachment(publishedDuringScan))))
                        }
                    } finally {
                        resume.countDown()
                    }
                    withTimeout(5_000) { while (unused in present()) delay(25) }
                    assertTrue(publishedDuringScan in present())

                    val preservedFile = privateContext.getDatabasePath(preserved)
                    val activeFile = privateContext.getDatabasePath(active)
                    preservedFile.copyTo(activeFile)
                    val marker = ToolLibraryStorage.selectionFile(privateContext, ToolLibraryStorage.DEFAULT)
                    marker.writeText(JSONObject().put("active", active).put("preserved", preserved).toString())
                    assertTrue(preservedFile.delete())
                    verifyMissing.set(true)
                    chat.clearHistory()
                    chat.saveNewDraft(Draft())
                    repeat(2) {
                        cleanup.request()
                        assertNotNull(withContext(Dispatchers.IO) { missingScans.poll(5, TimeUnit.SECONDS) })
                    }
                    verifyMissing.set(false)
                    assertTrue(publishedDuringScan in present())
                    assertTrue(shared in present())
                    assertTrue(marker.delete())
                    assertTrue(activeFile.delete())
                    assertTrue(context.deleteDatabase(namedLibrary))
                    cleanup.request()
                    withTimeout(5_000) { while (present() != setOf(unrelated)) delay(25) }
                }
            }
        } finally {
            context.deleteDatabase(chatDb)
            context.deleteDatabase(library)
            context.deleteDatabase(namedLibrary)
            directory.deleteRecursively()
        }
    }

    @Test fun webViewSerializesLargeReadsWithinTheTransferBudget() = runBlocking {
        val bytes = 33 * 1024 * 1024
        val opens = AtomicInteger()
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val asset = ThreeSceneAsset("budget-fixture", "application/octet-stream", bytes.toLong(), "Budget fixture") {
            opens.incrementAndGet()
            val active = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, active) }
            object : InputStream() {
                private var remaining = bytes
                private val done = AtomicBoolean(false)
                override fun available() = remaining
                override fun read(): Int {
                    if (remaining == 0) { close(); return -1 }
                    remaining--
                    return 0
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (remaining == 0) { close(); return -1 }
                    val count = minOf(remaining, length)
                    buffer.fill(0, offset, offset + count)
                    remaining -= count
                    return count
                }
                override fun close() {
                    if (done.compareAndSet(false, true)) inFlight.decrementAndGet()
                }
            }
        }
        val source = """
            export async function createScene({THREE, canvas, viewport, readAsset}) {
                const renderer = new THREE.WebGLRenderer({canvas});
                renderer.setSize(viewport.width, viewport.height, false);
                renderer.clear();
                const size = async () => (await readAsset('budget-fixture')).byteLength;
                const reads = await Promise.all([size(), size()]);
                return {
                    event() {}, frame() {}, snapshot() { return {state: {reads}}; },
                    dispose() { renderer.dispose(); }
                };
            }
        """.trimIndent()
        ActivityScenario.launch(MainActivity::class.java).use {
            withContext(Dispatchers.Main.immediate) {
                val activity = ThreeSceneForeground.activity()
                val web = ThreeSceneWebView(activity, "asset-budget", 1, source, listOf(asset))
                val viewport = android.widget.FrameLayout(activity).apply {
                    addView(web.view, android.widget.FrameLayout.LayoutParams(-1, 440))
                }
                val dialog = AlertDialog.Builder(activity).setView(viewport).create()
                try {
                    dialog.show()
                    withTimeout(2_500) { while (web.view.width == 0 || web.view.height == 0) delay(16) }
                    val result = try {
                        web.start(JSONObject()).getJSONObject("state")
                    } catch (failure: Exception) {
                        throw AssertionError("Broker opened ${opens.get()} reads with ${inFlight.get()} still active.", failure)
                    }
                    val reads = result.getJSONArray("reads")
                    assertEquals(2, reads.length())
                    assertEquals(bytes, reads.getInt(0))
                    assertEquals(bytes, reads.getInt(1))
                    assertEquals(2, opens.get())
                    assertEquals(1, peak.get())
                } finally {
                    dialog.dismiss()
                    web.close()
                }
            }
        }
    }

    @Test fun threeScenePlanRejectsUnsupportedControlsBeforeWriter() {
        val fixture = Provider("earth", fixtureSource("earth"), false) {}
        val plan = JSONObject(fixture.plan())
        val steps = plan.getJSONObject("scene").getJSONObject("checks").getJSONObject("scenario").getJSONArray("steps")
        val motion = steps.getJSONObject(0)
        steps.put(0, JSONObject("""{"op":"surface","slot":"view","event":"control","direction":"left"}"""))
        steps.put(motion)
        assertTrue(runCatching {
            com.alpha.assistant.tools.generation.NeedsPlan.parseResponse(plan.toString(), null, emptySet())
        }.isFailure)
    }

    private fun sourcePatch(search: String, replacement: String) = JSONObject()
        .put("sourceEdits", JSONArray().put(JSONObject().put("search", search).put("replace", replacement))).toString()

    private fun fixtureSource(name: String) =
        instrumentation.context.assets.open("threejs/$name.js").bufferedReader().use { it.readText() }

    private fun catalogGlb(edit: (JSONObject) -> Unit): ByteArray {
        val original = context.assets.open("threejs/catalog/robot.glb").use { it.readBytes() }
        val oldLength = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        val model = JSONObject(original.copyOfRange(20, 20 + oldLength).toString(Charsets.UTF_8)).also(edit)
        val json = model.toString().toByteArray()
        val length = (json.size + 3) / 4 * 4
        return ByteBuffer.allocate(original.size - oldLength + length).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67).putInt(2).putInt(capacity()).putInt(length).putInt(0x4e4f534a)
            put(json)
            repeat(length - json.size) { put(' '.code.toByte()) }
            put(original, 20 + oldLength, original.size - 20 - oldLength)
        }.array()
    }

    private suspend fun <T> approvePreview(block: suspend () -> T): T = coroutineScope {
        val approval = launch(Dispatchers.IO) {
            while (true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root?.findAccessibilityNodeInfosByText("Save scene").orEmpty().any {
                        it.isClickable && it.isEnabled &&
                            it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    }) break
                delay(100)
            }
        }
        try { block() } finally { approval.cancelAndJoin() }
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

    @Test fun cancelingDuringLocalStartupDoesNotAskForRepair() = runBlocking {
        val original = fixtureSource("earth")
        val source = original.replace("const enabled = true;", "const enabled = true; await new Promise(() => {});")
        val fixture = Provider("earth", source, false) {}
        val provider = object : ChatProvider by fixture {
            override val supportsSceneVision = false
        }
        val database = "three-startup-cancel-${UUID.randomUUID()}.db"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                ToolStore(context, database).use { store ->
                    val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
                    try {
                        val draft = engine.createDraft("Create a Three.js orbit.", null)
                        val generation = async(Dispatchers.IO) {
                            runCatching { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
                        }
                        ShellRobot("three-startup-cancel").waitUntil {
                            val root = instrumentation.uiAutomation.rootInActiveWindow
                            root?.findAccessibilityNodeInfosByText("Cancel").orEmpty().any {
                                it.isClickable && it.isEnabled &&
                                    it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            }
                        }
                        val failure = withTimeout(5_000) { generation.await().exceptionOrNull() } as? CloudFailure
                        assertEquals("three_declined", failure?.code)
                        assertEquals(0, fixture.repairs)
                        assertNull(store.draft(draft.id)?.candidate)
                    } finally { engine.closeArtifacts() }
                }
            }
        } finally { context.deleteDatabase(database) }
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
                        approvePreview { engine.generate(draft.id, "fixture", emptyList(), CloudSession()) }
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

        fun plan(): String {
            val motion = """{"slots":{"view":{"kind":"surface"},"render":{"kind":"geometry"}},"steps":[
                {"op":"frames","slot":"view","count":6,"observe":"render","changes":2}]}"""
            val workshop = """{"slots":{"view":{"kind":"surface"},"render":{"kind":"geometry"},"message":{"kind":"value"},"audio":{"kind":"audio"},"saved":{"kind":"state"}},"steps":[
                {"op":"capture","slot":"render","as":"initial"},
                {"op":"surface","slot":"view","event":"pointer","phase":"down","x":0.30,"y":0.50},
                {"op":"surface","slot":"view","event":"pointer","phase":"move","x":0.45,"y":0.70},
                {"op":"surface","slot":"view","event":"pointer","phase":"up","x":0.45,"y":0.70},
                {"op":"assert","slot":"saved","test":"equals","value":false},
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

package com.alpha.assistant.tools

import android.app.AlertDialog
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.MainActivity
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Draft
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.tools.three.ThreeSceneAsset
import com.alpha.assistant.tools.three.ThreeSceneAssetCleanup
import com.alpha.assistant.tools.three.ThreeSceneAssets
import com.alpha.assistant.tools.three.ThreeSceneForeground
import com.alpha.assistant.tools.three.ThreeSceneFailure
import com.alpha.assistant.tools.three.ThreeSceneProblem
import com.alpha.assistant.tools.three.ThreeSceneSession
import com.alpha.assistant.tools.three.ThreeSceneWebView
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
import kotlinx.coroutines.async
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

    @Test fun texturedWorkerChecksActualSurfaceDrawAndLaterBindingChanges() = runBlocking {
        val png = java.io.ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.CYAN)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            } finally { bitmap.recycle() }
        }
        val cleanup = ThreeSceneAssetCleanup(context)
        cleanup.lease().use {
            val asset = ThreeSceneAssets(context).importGenerated(png, "paint", "image/png")
            val image = com.alpha.assistant.images.GeneratedImage("paint", "Cyan paint",
                com.alpha.assistant.images.ImageAspect.SQUARE, asset, 16, 16, "fixture")
            val setup = """
                const {THREE, canvas, bindTexture} = context;
                const renderer = new THREE.WebGLRenderer({canvas});
                const scene = new THREE.Scene();
                const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 10);
                camera.position.z = 3;
                const mesh = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshBasicMaterial());
                scene.add(mesh);
            """.trimIndent()
            val bind = "await bindTexture('paint', mesh);"
            val draw = "renderer.render(scene, camera);"
            val offscreen = "renderer.setRenderTarget(new THREE.WebGLRenderTarget(16, 16)); $draw"
            fun tool(start: String, update: String = ""): ToolRecord {
                val raw = JSONObject("""{"formatVersion":3,"title":"Texture check","description":"A textured cube",
                    "capabilities":[],"runtime":{"kind":"threejs-worker","version":1},"stateSchema":{"ready":"boolean"},"initialState":{"ready":true},
                    "ui":{"type":"text","text":"Texture check"}}""")
                    .put("source", """
                        export async function createScene(context) {
                            $setup
                            $start
                            return {
                                event() {},
                                frame() { $update },
                                snapshot() { return {state:{ready:true}}; },
                                dispose() { renderer.dispose(); }
                            };
                        }
                    """.trimIndent())
                val spec = ToolSpec.parse(raw.toString()).withThreeAssets(listOf(asset)).withGeneratedImages(listOf(image))
                return ToolRecord(UUID.randomUUID().toString(), 1, 0, spec.json, spec.initialState,
                    spec.ui.toString(), null, true)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                for (start in listOf("$bind $draw", "$bind context.render(renderer, scene, camera);",
                    "$offscreen renderer.setRenderTarget(null); $bind $draw")) {
                    onScene(tool(start)) { session, _ -> session.frame(16) }
                }
                val rejected = listOf(draw, "$bind $bind $draw", "$bind $offscreen", "$bind renderer.render(scene, {});",
                    "$bind renderer.render(scene, new THREE.ArrayCamera([]));",
                    "mesh.geometry.deleteAttribute('uv'); $bind $draw",
                    "$bind renderer.render(new THREE.Scene(), camera);",
                    "$bind new THREE.WebGLRenderer({canvas:new OffscreenCanvas(16,16)}).render(scene,camera);")
                for (start in rejected) {
                    val failure = runCatching { onScene(tool(start)) { _, _ -> } }.exceptionOrNull()
                    assertEquals(start, com.alpha.assistant.tools.three.ThreeSceneProblem.TEXTURE_BINDING,
                        (failure as? com.alpha.assistant.tools.three.ThreeSceneFailure)?.problem)
                }
                for (update in listOf("mesh.material.map = null;", "mesh.material = mesh.material.clone();",
                    "scene.remove(mesh);", "mesh.geometry.deleteAttribute('uv');")) {
                    val failure = runCatching {
                        onScene(tool("$bind $draw", update)) { session, _ -> session.frame(16) }
                    }.exceptionOrNull()
                    assertEquals(update, com.alpha.assistant.tools.three.ThreeSceneProblem.TEXTURE_BINDING,
                        (failure as? com.alpha.assistant.tools.three.ThreeSceneFailure)?.problem)
                }
            }
            cleanup.request()
        }
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
}

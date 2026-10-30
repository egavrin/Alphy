package com.alpha.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.*
import com.alpha.assistant.sandbox.SandboxClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ToolWorkflowTest {
    private fun spec(label: String = "Count", bad: Boolean = false): String = JSONObject()
        .put("formatVersion", 2).put("title", label).put("description", "Local counter")
        .put("capabilities", org.json.JSONArray().apply { if (bad) put("calendar") })
        .put("stateSchema", JSONObject().put("count", "number"))
        .put("initialState", JSONObject().put("count", 0))
        .put("ui", JSONObject("""{"type":"column","children":[{"type":"text","text":"$label","bind":"count"},{"type":"button","text":"Add","action":"add"}]}"""))
        .put("source", "if(input.event.type==='action' && input.event.actionId==='add') input.state.count++; return {state:input.state};").toString()

    @Test fun generatePreviewInteractRefineRejectAcceptReopenRollbackAndStaleCommands() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "tool-test-${UUID.randomUUID()}.db"
        val requests = mutableListOf<CloudRequest>()
        var answer = spec()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                assertEquals("tool-test-only", key); requests += request; emit(CloudEvent.Text(answer))
            }
        }
        val store = ToolStore(context, name)
        val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
        try {
            val draft = engine.createDraft("Create a counter", null)
            val bitmap = android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888)
            val imageBytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            bitmap.recycle()
            val image = "data:image/png;base64,"+android.util.Base64.encodeToString(imageBytes,android.util.Base64.NO_WRAP)
            engine.generate(draft.id, "tool-test-only", listOf(image), CloudSession())
            assertTrue(store.tools().isEmpty())
            val first = engine.accept(draft.id)
            assertEquals(1, first.revision); assertEquals(0, JSONObject(first.state).getInt("count"))
            val changed = engine.interact(first.id, first.revision, first.stateVersion, "add", null)
            assertEquals(1, JSONObject(changed.state).getInt("count"))
            assertTrue(runCatching { engine.interact(first.id, first.revision, first.stateVersion, "add", null) }.isFailure)
            val refine = engine.createDraft("Rename to Total", changed.id)
            answer = spec("Total")
            engine.generate(refine.id, "tool-test-only", emptyList(), CloudSession())
            assertEquals(1, store.tool(changed.id)!!.revision)
            engine.reject(refine.id)
            assertEquals(1, JSONObject(store.tool(changed.id)!!.state).getInt("count"))
            val acceptedDraft = engine.createDraft("Rename to Total", changed.id)
            engine.generate(acceptedDraft.id, "tool-test-only", emptyList(), CloudSession())
            val second = engine.accept(acceptedDraft.id)
            assertEquals(2, second.revision); assertEquals(1, JSONObject(second.state).getInt("count"))
            val reopened = ToolStore(context, name)
            try {
                assertEquals(second, reopened.tool(second.id))
                assertEquals(2, reopened.revisions(second.id).size)
                val rolled = engine.rollback(second.id, second.revision, second.stateVersion, 1)
                assertEquals(3, rolled.revision); assertEquals("Count", ToolSpec.parse(rolled.spec).title)
                assertEquals(1, JSONObject(rolled.state).getInt("count"))
                assertTrue(runCatching { engine.rollback(second.id, second.revision, second.stateVersion, 1) }.isFailure)
            } finally { reopened.close() }
            assertEquals(3, requests.size)
            assertArrayEquals(imageBytes,android.util.Base64.decode(requests.first().turns.single().images.single().substringAfter(','),android.util.Base64.DEFAULT))
            assertTrue(requests.all { !it.search })
            assertTrue(requests.drop(1).all { it.turns.none { turn -> turn.images.isNotEmpty() } })
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun invalidGenerationAndRuntimeNeverReplaceWorkingState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "tool-test-${UUID.randomUUID()}.db"
        var answer = spec()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { emit(CloudEvent.Text(answer)) }
        }
        val store = ToolStore(context, name)
        val engine = ToolEngine(store, ToolGenerationService(provider, SandboxClient(context)))
        try {
            val draft = engine.createDraft("counter", null)
            engine.generate(draft.id, "fixture", emptyList(), CloudSession())
            val live = engine.accept(draft.id)
            answer = spec(bad = true)
            val failed = engine.createDraft("device action", live.id)
            assertTrue(runCatching { engine.generate(failed.id, "fixture", emptyList(), CloudSession()) }.isFailure)
            assertEquals(live, store.tool(live.id)); assertNull(store.draft(failed.id)!!.candidate)
            answer = JSONObject(spec()).put("source", "if(input.event.type==='action') while(true){}; return {state:input.state};").toString()
            val slow = engine.createDraft("slow action", live.id)
            engine.generate(slow.id, "fixture", emptyList(), CloudSession())
            val accepted = engine.accept(slow.id)
            assertTrue(runCatching { engine.interact(accepted.id, accepted.revision, accepted.stateVersion, "add", null) }.isFailure)
            assertEquals(accepted, store.tool(live.id))
            val stale = engine.createDraft("rename", live.id)
            answer = spec("Changed")
            engine.generate(stale.id, "fixture", emptyList(), CloudSession())
            engine.rollback(accepted.id, accepted.revision, accepted.stateVersion, 1)
            assertTrue(runCatching { engine.accept(stale.id) }.isFailure)
            val base = JSONObject(spec())
            listOf(
                JSONObject(base.toString()).put("ui", JSONObject("""{"type":"webview"}""")),
                JSONObject(base.toString()).put("ui", JSONObject("""{"type":"text","url":"https://example.com"}""")),
                JSONObject(base.toString()).put("initialState", JSONObject().put("count", "bad")),
                JSONObject(base.toString()).put("source", "x".repeat(70000))
            ).forEach { invalid -> assertTrue(runCatching { ToolSpec.parse(invalid.toString()) }.isFailure) }
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun nativePreviewAcceptanceAndLocalInteractionAreUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val name = "tool-ui-${UUID.randomUUID()}"
        val store = com.alpha.assistant.data.ShellStore(app,"$name.db")
        val keys = com.alpha.assistant.data.SecureKeyStore(app,name,name)
        keys.save("tool-test-only").getOrThrow()
        var calls = 0
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                assertEquals("tool-test-only",key); calls++; emit(CloudEvent.Text(spec()))
            }
        }
        lateinit var model: com.alpha.assistant.ui.ShellViewModel
        com.alpha.assistant.CloudTestActivity.factory = { application -> com.alpha.assistant.ui.ShellViewModel(application,provider,store,keys).also { model = it } }
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.alpha.assistant.CloudTestActivity::class.java)
        fun waitUntil(condition: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis()+12000
            while(android.os.SystemClock.uptimeMillis()<deadline) { if(condition()) return; android.os.SystemClock.sleep(50) }
            fail("Native tool workflow timed out")
        }
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
            if(node == null) return null
            if(node.text?.toString()==text || node.contentDescription?.toString()==text) return node
            repeat(node.childCount) { find(node.getChild(it),text)?.let { found -> return found } }
            return null
        }
        fun waitFor(text: String): android.view.accessibility.AccessibilityNodeInfo {
            var found: android.view.accessibility.AccessibilityNodeInfo? = null
            waitUntil { found = find(instrumentation.uiAutomation.rootInActiveWindow,text); found != null }
            return found!!
        }
        fun click(text: String) {
            waitUntil { !model.tools.busy }
            waitUntil {
                var node = find(instrumentation.uiAutomation.rootInActiveWindow,text)
                while(node != null && !node.isClickable) node = node.parent
                node != null && node.isEnabled && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
            instrumentation.uiAutomation.waitForIdle(100,2000)
        }
        try {
            waitUntil { !model.loading && !model.tools.busy }
            click("Создать")
            instrumentation.runOnMainSync { model.tools.preparePrompt("Create a counter") }
            waitFor("Create a counter")
            click("Создать предпросмотр")
            waitFor("Отправить запрос для инструмента в DeepSeek?")
            assertEquals(0,calls)
            click("Отправить запрос")
            waitFor("Предпросмотр без действий · элементы управления отключены")
            assertTrue(model.tools.tools.isEmpty())
            click("Применить версию")
            waitFor("Count: 0")
            click("Add")
            waitFor("Count: 1")
            assertEquals(1,calls)
            click("Сохранить в библиотеку")
            waitUntil { model.tools.tools.single().saved }
            waitFor("Сохранено в библиотеке")
            click("Библиотека")
            waitFor("Count")
            assertEquals(1,JSONObject(model.tools.tools.single().state).getInt("count"))
            scenario.recreate()
            waitUntil { !model.loading && !model.tools.busy }
            waitFor("Count")
            assertEquals(1,calls)
        } catch (failure: Throwable) {
            fun dump(node: android.view.accessibility.AccessibilityNodeInfo?): String {
                if(node == null) return ""
                return "[text=${node.text}, description=${node.contentDescription}, enabled=${node.isEnabled}]" + (0 until node.childCount).joinToString("") { dump(node.getChild(it)) }
            }
            throw AssertionError("${failure.message}; tool saved=${model.tools.tools.map { it.saved }}; error=${model.tools.error}; tree=${dump(instrumentation.uiAutomation.rootInActiveWindow)}",failure)
        } finally {
            scenario.close(); com.alpha.assistant.CloudTestActivity.factory = null
            android.os.SystemClock.sleep(150)
            store.close(); app.deleteDatabase("$name.db"); app.deleteDatabase("$name.db-tools.db")
            keys.clear(); app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }

}

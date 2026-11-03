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
        .put("formatVersion", 3).put("title", label).put("description", "Local counter")
        .put("capabilities", org.json.JSONArray().apply { if (bad) put("calendar") })
        .put("stateSchema", JSONObject().put("count", "number"))
        .put("initialState", JSONObject().put("count", 0))
        .put("ui", JSONObject("""{"type":"column","children":[{"type":"text","text":"$label","bind":"count"},{"type":"button","text":"Add","action":"add"}]}"""))
        .put("source", "if(input.event.type==='action' && input.event.actionId==='add') input.state.count++; return {state:input.state};").toString()

    // Failure cases: noncanonical stored contracts, scalar extras/nulls/unions, malformed collections, and non-JSON output.
    @Test fun generatedScalarObjectsNormalizeOnlyAtGenerationAndPreserveRevisionState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "tool-boundary-${UUID.randomUUID()}.db"
        val store = ToolStore(context,name)
        val requests = mutableListOf<CloudRequest>()
        var answer = JSONObject(spec()).put("stateSchema",JSONObject("""{"count":{"type":"number"},"items":{"type":"list","maxItems":10,"fields":{"id":{"type":"string"},"done":{"type":"boolean"},"amount":{"type":"number"}}}}"""))
            .put("initialState",JSONObject("""{"count":0,"items":[{"id":"first","done":false,"amount":3}]}""")).toString()
        val objectSchema = answer
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                requests += request; assertTrue(request.jsonObject); assertFalse(request.search); emit(CloudEvent.Text(answer))
            }
        }
        val engine = ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
        try {
            assertTrue(runCatching { ToolSpec.parse(answer) }.isFailure)
            val draft = engine.createDraft("Create a counter and list",null)
            val preview = engine.generate(draft.id,"fixture",emptyList(),CloudSession())
            assertTrue(store.tools().isEmpty()); assertEquals(0,JSONObject(preview.previewState!!).getInt("count"))
            val first = engine.accept(draft.id)
            val parsed = ToolSpec.parse(first.spec)
            assertEquals("number",parsed.schema.get("count"))
            assertEquals("string",parsed.schema.getJSONObject("items").getJSONObject("fields").get("id"))
            val changed = engine.interact(first.id,first.revision,first.stateVersion,"add",null)
            val revision = engine.createDraft("Rename without data loss",changed.id)
            engine.generate(revision.id,"fixture",emptyList(),CloudSession())
            val second = engine.accept(revision.id)
            assertEquals(2,second.revision); assertEquals(changed.state,second.state); assertTrue(store.effects(first.id).isEmpty())
            val invalidSchemas = listOf(
                """{"count":{"type":"number","minimum":0}}""", """{"count":{"type":"integer"}}""",
                """{"count":null}""", """{"count":{"type":null}}""", """{"count":{"type":["number","string"]}}""",
                """{"count":{"type":"list","maxItems":10}}""",
                """{"count":"number","items":{"type":"list","maxItems":10,"fields":{"id":{"type":"string","extra":true}}}}""",
                """{"count":"number","items":{"type":"list","maxItems":10,"fields":{"id":"string","nested":{"type":"list","fields":{}}}}}"""
            )
            // Preserve raw literals here. JSONObject serialization would convert integral doubles to integers.
            val numericBoundaries = listOf("3.0","3e0","null","2147483648","0","2").map {
                objectSchema.replace("\"formatVersion\":3,","\"formatVersion\":$it,")
            } + listOf("10.0","1e1","null","2147483648","0","101").map {
                objectSchema.replace("\"maxItems\":10,","\"maxItems\":$it,")
            }
            val candidates = numericBoundaries + invalidSchemas.map { JSONObject(objectSchema).put("stateSchema",JSONObject(it)).toString() } +
                listOf("", "   ", "not JSON", objectSchema.dropLast(1), objectSchema + " trailing", "```json\n$objectSchema\n```", "[]")
            candidates.forEach { invalid ->
                answer = invalid; requests.clear()
                val rejected = engine.createDraft("Invalid refinement",second.id)
                val failure = runCatching { engine.generate(rejected.id,"fixture",emptyList(),CloudSession()) }.exceptionOrNull()
                assertTrue(invalid.take(100),failure is CloudFailure)
                assertEquals(2,requests.size); assertEquals(invalid,requests[1].turns[1].text)
                assertEquals(second,store.tool(first.id)); assertNull(store.draft(rejected.id)!!.candidate)
                engine.reject(rejected.id)
            }
        } finally { store.close(); context.deleteDatabase(name) }
    }

    // Failure cases: correction loops, preview-only rejection, lost revisions, and invented dates for unknown saved values.
    @Test fun structuralCorrectionsPreserveSavedToolsDatesAndRevisions(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="tool-ui-correction-${UUID.randomUUID()}.db"
        val requests=mutableListOf<CloudRequest>()
        var answers=emptyList<String>()
        val provider=object: ChatProvider {
            override suspend fun respond(key: String,request: CloudRequest,session: CloudSession,emit: suspend (CloudEvent)->Unit) {
                requests.add(request); emit(CloudEvent.Text(answers[requests.lastIndex]))
            }
        }
        val original=JSONObject(spec()).put("stateSchema",JSONObject().put("SECRET_DATE_BIND","string"))
            .put("initialState",JSONObject().put("SECRET_DATE_BIND",""))
            .put("ui",JSONObject("""{"type":"input","text":"Visit date","bind":"SECRET_DATE_BIND","action":"date"}"""))
            .put("source","return {state:input.state};").toString()
        // A valid generated default must never replace an unknown saved date during refinement.
        val dateCandidate=JSONObject(original).put("initialState",JSONObject().put("SECRET_DATE_BIND","2028-02-29"))
            .put("ui",JSONObject("""{"type":"dateInput","text":"Visit date","bind":"SECRET_DATE_BIND","action":"date"}""")).toString()
        val missingLabel=JSONObject(original).put("ui",JSONObject("""{"type":"icon","name":"Plus","action":"SECRET_ACTION"}""")).toString()
        val store=ToolStore(context,name)
        val engine=ToolEngine(store,ToolGenerationService(provider,SandboxClient(context)))
        try {
            answers=listOf(original)
            val draft=engine.createDraft("Create a date note",null)
            engine.generate(draft.id,"offline",emptyList(),CloudSession())
            assertEquals(1,requests.size)
            val saved=engine.accept(draft.id)
            val revisions=store.revisions(saved.id)
            for(candidate in listOf(missingLabel,dateCandidate)) {
                requests.clear(); answers=listOf(candidate,candidate)
                val rejected=engine.createDraft("Refine without changing the date",saved.id)
                val failure=runCatching {engine.generate(rejected.id,"offline",emptyList(),CloudSession())}.exceptionOrNull() as CloudFailure
                assertEquals(2,requests.size); assertEquals(saved,store.tool(saved.id)); assertEquals(revisions,store.revisions(saved.id))
                assertNull(store.draft(rejected.id)!!.candidate); assertNull(store.draft(rejected.id)!!.previewState); assertNull(store.draft(rejected.id)!!.previewUi)
                val correction=requests[1].turns.last().text
                assertEquals(candidate,requests[1].turns[1].text)
                assertFalse(correction.contains("SECRET_")); assertFalse(correction.contains("2028-02-29"))
                val message=checkNotNull(failure.message)
                assertFalse(message.contains("UI rule:")); assertFalse(message.contains("$.ui"))
                if(candidate == dateCandidate) {
                    assertEquals("widget_preview",failure.code)
                    assertTrue(correction.contains("UI rule: date_value.")); assertTrue(correction.contains("Stage: PREVIEW."))
                } else assertTrue(correction.contains("UI rule: accessible_label."))
                engine.reject(rejected.id)
            }
            requests.clear(); answers=listOf(dateCandidate,original)
            val corrected=engine.createDraft("Keep unknown dates as strings",saved.id)
            val preview=engine.generate(corrected.id,"offline",emptyList(),CloudSession())
            assertEquals(2,requests.size); assertEquals(saved.state,preview.previewState)
            val next=engine.accept(corrected.id)
            assertEquals(saved.state,next.state); assertEquals("",JSONObject(next.state).getString("SECRET_DATE_BIND"))
            assertEquals(2,next.revision); assertEquals(2,store.revisions(saved.id).size)
        } finally {store.close(); context.deleteDatabase(name)}
    }

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
            assertTrue(runCatching { engine.generate(slow.id, "fixture", emptyList(), CloudSession()) }.exceptionOrNull() is CloudFailure)
            assertNull(store.draft(slow.id)!!.candidate)
            engine.reject(slow.id)
            val accepted = live
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
        fun scroll(node: android.view.accessibility.AccessibilityNodeInfo?, action: Int): Boolean {
            if(node == null) return false
            if(node.actionList.any { it.id == action } && node.performAction(action)) return true
            repeat(node.childCount) { if(scroll(node.getChild(it),action)) return true }
            return false
        }
        fun waitFor(text: String): android.view.accessibility.AccessibilityNodeInfo {
            var found: android.view.accessibility.AccessibilityNodeInfo? = null
            var direction=android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            waitUntil {
                val root=instrumentation.uiAutomation.rootInActiveWindow
                found=find(root,text)
                if(found?.isVisibleToUser != true) found?.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                if(found?.isVisibleToUser != true) {
                    val viewport=find(root,"Mini-app content") ?: find(root,"Mini-app library content")
                    if(viewport != null && !viewport.performAction(direction)) direction=if(direction == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                found?.isVisibleToUser == true
            }
            return found!!
        }
        fun click(text: String) {
            waitUntil { !model.tools.busy }
            var direction = android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            waitUntil {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                var node = find(root,text)
                if(node == null) {
                    if(!scroll(root,direction)) direction = if(direction == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    instrumentation.uiAutomation.waitForIdle(100,2000)
                    false
                } else {
                    while(node != null && !node.isClickable) node = node.parent
                    node != null && node.isEnabled && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            instrumentation.uiAutomation.waitForIdle(100,2000)
        }
        try {
            waitUntil { !model.loading && !model.tools.busy }
            click("Создать")
            instrumentation.runOnMainSync { model.tools.preparePrompt("Create a counter") }
            waitFor("Create a counter")
            click("Create preview")
            waitFor("Preview · controls are disabled")
            assertTrue(model.tools.tools.isEmpty())
            click("Apply changes")
            waitFor("Count: 0")
            click("Add")
            waitFor("Count: 1")
            assertEquals(1,calls)
            waitUntil { model.tools.tools.single().saved }
            click("Mini-app library")
            waitFor("Count")
            assertEquals(1,JSONObject(model.tools.tools.single().state).getInt("count"))
            scenario.recreate()
            waitUntil { !model.loading && !model.tools.busy }
            assertTrue(waitFor("Count").isVisibleToUser)
            assertNull("Recreation keeps the requested library page",model.tools.selectedId)
            assertEquals(1,JSONObject(model.tools.tools.single().state).getInt("count"))
            assertEquals(1,calls)
            val evidence=java.io.File(app.getExternalFilesDir(null),"mini-app-evidence").apply {mkdirs()}
            instrumentation.uiAutomation.takeScreenshot()?.let {image ->
                java.io.File(evidence,"compose-library-recreated.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
            println("NATIVE_LIBRARY recreation=PASS visibleSavedCount=PASS stateCount=1 providerCalls=1")
        } catch (failure: Throwable) {
            fun dump(node: android.view.accessibility.AccessibilityNodeInfo?): String {
                if(node == null) return ""
                return "[text=${node.text}, description=${node.contentDescription}, enabled=${node.isEnabled}, scrollable=${node.isScrollable}, actions=${node.actionList.map { it.id }}]" + (0 until node.childCount).joinToString("") { dump(node.getChild(it)) }
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

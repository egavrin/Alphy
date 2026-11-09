package com.alpha.assistant.portability

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.DeepSeekProvider
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.patchSavedFixture
import com.alpha.assistant.tools.replyWithFixturePlan
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MiniAppPortabilityWorkflowTest {
    private val spec = ToolSpec.parse("""{"formatVersion":3,"title":"Portable counter","description":"",
        "capabilities":[],"stateSchema":{"count":"number"},"initialState":{"count":0},
        "ui":{"type":"text","text":"Count","bind":"count"},"source":"return {state:input.state};"}""")

    @Test fun portableFilesRestoreOnlySelectedDataAndNeverReplaceAnExistingApp() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val databases = List(3) { "portable-${UUID.randomUUID()}.db" }
        val saved = ToolRecord("portable-app", 1, 2, spec.json, """{"count":7}""", spec.ui.toString(),
            "private-chat", saved = true, onDesktop = true)
        val malformed = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, text) in mapOf("manifest.json" to """{"version":1,"id":"a","id":"b"}""",
                    "definition.json" to spec.json)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        try {
            ToolStore(app, databases[0]).use { original ->
                original.saveTool(saved, revision = true)
                original.setNetworkGrant(saved.id, true)
                val source = ToolEngine(original, ToolGenerationService(DeepSeekProvider(), SandboxClient(app)))
                for ((index, includeState) in listOf(false, true).withIndex()) {
                    val archive = ByteArrayOutputStream().also { source.exportPortable(saved, includeState, it) }.toByteArray()
                    ToolStore(app, databases[index + 1]).use { destination ->
                        val engine = ToolEngine(destination, ToolGenerationService(DeepSeekProvider(), SandboxClient(app)))
                        assertTrue(runCatching { engine.importPortable(malformed.inputStream()) }.isFailure)
                        assertTrue(destination.tools().isEmpty())
                        val imported = engine.importPortable(archive.inputStream())
                        assertEquals(saved.id, imported.id)
                        assertEquals(if (includeState) 7 else 0, JSONObject(imported.state).getInt("count"))
                        assertEquals(null, imported.conversationId)
                        assertFalse(imported.onDesktop)
                        assertFalse(destination.networkGrantEnabled(imported.id))
                        assertTrue(runCatching { engine.importPortable(archive.inputStream()) }.isFailure)
                        assertTrue(runCatching { engine.importPortable(byteArrayOf(1, 2, 3).inputStream()) }.isFailure)
                        assertTrue(runCatching { engine.saveManualDefinition(imported, "{}") }.isFailure)
                        assertEquals(imported, destination.tool(imported.id))
                        val edited = engine.saveManualDefinition(imported, JSONObject(spec.json).put("title", "Edited counter").toString())
                        assertEquals(imported.state, edited.state)
                        assertEquals(2, edited.revision)
                        assertTrue(runCatching { engine.saveManualDefinition(imported, spec.json) }.isFailure)
                        assertEquals(edited, destination.tool(imported.id))
                    }
                }
                assertTrue(original.networkGrantEnabled(saved.id))
                assertEquals(saved, original.tool(saved.id))
            }
        } finally {
            databases.forEach(app::deleteDatabase)
        }
    }

    @Test fun largeAppGeneratesRunsRefinesAndSurvivesPortability() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val databases = List(2) { "portable-large-${UUID.randomUUID()}.db" }
        val label = " — Field notes describe the local survey area, access conditions, and the observations to record when revisiting this location."
        fun definition(title: String, heading: String): String {
            val labels = List(300) { "Station ${it + 1}$label" }
            val controls = JSONArray()
                .put(JSONObject("""{"type":"text","text":"$heading","bind":"count"}"""))
                .put(JSONObject("""{"type":"button","text":"Add","action":"add"}"""))
            labels.chunked(30).forEach { group ->
                controls.put(JSONObject().put("type", "column").put("children", JSONArray(group.map {
                    JSONObject().put("type", "text").put("text", it)
                })))
            }
            val state = JSONObject().put("count", 0)
            val schema = JSONObject().put("count", "number")
            repeat(12) { index ->
                state.put("note$index", label.repeat(15))
                schema.put("note$index", "string")
            }
            val source = """
                const labels = ${JSONArray(labels)};
                const children = [{type:'text',text:'$heading',bind:'count'}, {type:'button',text:'Add',action:'add'}];
                for (let start = 0; start < labels.length; start += 30) {
                    children.push({type:'column',children:labels.slice(start,start+30).map(text=>({type:'text',text}))});
                }
                if (input.event.actionId === 'add') input.state.count++;
                return {state:input.state,ui:{type:'column',children}};
            """.trimIndent()
            assertTrue(source.toByteArray().size in 32769..65536)
            return JSONObject(spec.json).put("title", title).put("stateSchema", schema).put("initialState", state)
                .put("ui", JSONObject().put("type", "column").put("children", controls))
                .put("source", source).toString()
        }
        var answer = definition("Survey log", "Visits")
        assertTrue(answer.toByteArray().size > 65536)
        val stages = mutableListOf<CloudGenerationStage?>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                stages += request.generationStage
                if (request.replyWithFixturePlan(emit)) return
                val response = request.patchSavedFixture(answer)
                if (request.generationStage == CloudGenerationStage.REFINE) assertTrue(response.toByteArray().size > 65536)
                emit(CloudEvent.Text(response))
            }
        }
        val generation = ToolGenerationService(provider, SandboxClient(app))
        try {
            ToolStore(app, databases[0]).use { store ->
                val engine = ToolEngine(store, generation)
                val draft = engine.createDraft("Create a large survey log", null)
                engine.generate(draft.id, "fixture", emptyList(), CloudSession())
                val created = engine.accept(draft.id)
                val changed = engine.interact(created.id, created.revision, created.stateVersion, "add", null)
                assertEquals(1, JSONObject(changed.state).getInt("count"))
                assertTrue(changed.ui.toByteArray().size > 32768)
                assertTrue(changed.state.toByteArray().size + changed.ui.toByteArray().size > 65536)
                answer = definition("Updated survey log", "Total visits")
                val refine = engine.createDraft("Rename the log and its counter", changed.id)
                engine.generate(refine.id, "fixture", emptyList(), CloudSession())
                val updated = engine.accept(refine.id)
                assertEquals("Updated survey log", ToolSpec.parsePersisted(updated.spec).title)
                assertEquals(1, JSONObject(updated.state).getInt("count"))
                ToolStore(app, databases[0]).use { reopened ->
                    assertEquals(updated, reopened.tool(updated.id))
                }

                val archive = ByteArrayOutputStream().also { engine.exportPortable(updated, true, it) }.toByteArray()
                ToolStore(app, databases[1]).use { destination ->
                    val receiver = ToolEngine(destination, generation)
                    val imported = receiver.importPortable(archive.inputStream())
                    assertEquals(updated.spec, imported.spec)
                    assertEquals(updated.state, imported.state)
                    val edited = receiver.saveManualDefinition(imported, JSONObject(imported.spec)
                        .put("title", "Imported survey log").toString())
                    val interacted = receiver.interact(edited.id, edited.revision, edited.stateVersion, "add", null)
                    assertEquals(2, JSONObject(interacted.state).getInt("count"))
                    val oversized = JSONObject(interacted.spec).put("source", " ".repeat(65536) + "return {};").toString()
                    assertTrue(runCatching { receiver.saveManualDefinition(interacted, oversized) }.isFailure)
                    assertEquals(interacted, destination.tool(interacted.id))
                }
            }
            assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.REFINE), stages)
        } finally {
            databases.forEach(app::deleteDatabase)
        }
    }

    @Test fun shareUsesSelectedDataAndAnOpenEditorCannotReplaceANewerRevision() {
        val fixture = ShellFixture("portable-share")
        val robot = ShellRobot("portable-share")
        val chooser = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val saved = ToolRecord("portable-share-app", 1, 2, spec.json, """{"count":7}""", spec.ui.toString(),
            null, saved = true)
        var shared: Uri? = null
        robot.instrumentation.addMonitor(monitor)
        try {
            ToolStore(fixture.context, "${fixture.store.databaseName}-tools.db").use { store ->
                store.saveTool(saved, revision = true)
            }
            fixture.install(saveKey = false)
            fixture.launch().use { scenario ->
                robot.waitUntil { fixture.model?.tools?.tools?.any { it.id == saved.id } == true }
                scenario.onActivity { checkNotNull(fixture.model).openSurface(saved.id) }
                robot.click(robot.text(R.string.tool_options))
                robot.click(robot.text(R.string.tool_export))
                robot.click(robot.text(R.string.tool_export_state))
                robot.click(robot.text(R.string.tool_export_share))
                robot.waitUntil { chooser.get() != null }
                val send = checkNotNull(IntentCompat.getParcelableExtra(chooser.get(), Intent.EXTRA_INTENT, Intent::class.java))
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("application/zip", send.type)
                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val uri = checkNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
                shared = uri
                assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
                checkNotNull(fixture.context.contentResolver.openInputStream(uri)).use { input ->
                    val archive = MiniAppArchive.read(input, fixture.context.cacheDir.toPath()) {}
                    assertEquals(saved.id, archive.id)
                    assertEquals(7, JSONObject(archive.state).getInt("count"))
                }
                robot.click(robot.text(R.string.tool_options))
                robot.click(robot.text(R.string.tool_manual_edit))
                val input = robot.text(R.string.tool_manual_edit_definition)
                val userEdit = JSONObject(spec.json).put("title", "Unsaved title").toString()
                robot.edit(input, userEdit)
                robot.enter(input, "я".repeat(131_073))
                robot.reveal(robot.text(R.string.tool_manual_edit_size, 256))
                assertTrue(generateSequence(robot.waitFor(robot.text(R.string.tool_manual_edit_save))) { it.parent }.any { !it.isEnabled })
                robot.reveal(input, forward = false)
                assertEquals(userEdit, robot.field(input).text.toString())
                robot.edit(input, userEdit + " ")
                robot.edit(input, userEdit)
                val newer = saved.copy(revision = 2, stateVersion = 3,
                    spec = JSONObject(spec.json).put("title", "Newer title").toString())
                ToolStore(fixture.context, "${fixture.store.databaseName}-tools.db").use { store ->
                    store.saveTool(newer, revision = true)
                    robot.reveal(robot.text(R.string.tool_refinement_current_version))
                    val save = robot.waitFor(robot.text(R.string.tool_manual_edit_save))
                    assertTrue(generateSequence(save) { it.parent }.any { !it.isEnabled })
                    robot.reveal(input, forward = false)
                    assertEquals(userEdit, robot.field(input).text.toString())
                    assertEquals(newer, store.tool(saved.id))
                }
            }
        } finally {
            robot.instrumentation.removeMonitor(monitor)
            shared?.let { fixture.context.contentResolver.delete(it, null, null) }
            fixture.remove()
        }
    }
}

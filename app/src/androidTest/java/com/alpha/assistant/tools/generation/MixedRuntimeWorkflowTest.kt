package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxResult
import com.alpha.assistant.tools.GraphicsContract
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MixedRuntimeWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val partState = "p5_count_value"
    private val partAction = "p5_count_add"
    private val fillAction = "fill_action"
    private val surface = "fill_board"

    private val offline = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            error("Mixed runtime workflow must not call a provider.")
        }
    }

    private suspend fun spec(source: String, rows: Boolean = false): ToolSpec {
        val plan = NeedsPlan.parse("""
            {"title":"Counter and scene","description":"","language":"en","inputs":[],"constants":[],"needs":[
              {"id":"count","type":"counter","label":"Count","description":"Increase and reset"},
              {"id":"board","type":"scene","label":"Board","description":"Move a dot"}
            ],"questions":[]}
        """.trimIndent())
        val assembly = PartsGeneration.build(plan) { raw ->
            val question = JSONObject(raw).getJSONArray("questions").getJSONObject(0)
            val answer = JSONObject().put("id", question.getString("id")).put("choice", "A").put("confidence", 1)
                .put("probabilities", JSONObject().put("A", 1).put("B", 0))
            JSONObject().put("answers", JSONArray().put(answer)).toString()
        }
        val schema = JSONObject("""{"fill_pos":"number"}""")
        val initial = JSONObject("""{"fill_pos":0}""")
        if (rows) {
            schema.put("fill_rows", JSONObject("""{"type":"list","maxItems":4,"fields":{"id":"string","label":"string"}}"""))
            initial.put("fill_rows", JSONArray("""[{"id":"row","label":"One"}]"""))
        }
        val fill = JSONObject().put("stateSchema", schema).put("initialState", initial)
            .put("ui", column(JSONObject("""{"type":"button","text":"Move","action":"$fillAction"}""")))
            .put("surfaces", JSONArray("""[{"id":"$surface","width":64,"height":64}]"""))
            .put("capabilities", JSONArray()).put("source", source)
        return ToolComposition.complete(assembly, fill.toString())
    }

    private fun column(child: JSONObject) = JSONObject().put("type", "column").put("children", JSONArray().put(child))
    private fun event(type: String) = JSONObject().put("type", type)
    private fun action(id: String) = event("action").put("actionId", id).put("value", JSONObject.NULL)

    @Test fun selectedActionSkipsFillAndSceneReceivesOnlyFillState(): Unit = runBlocking {
        val sandbox = SandboxClient(context)
        val generation = ToolGenerationService(offline, sandbox)
        val spec = spec("""
            if (Object.keys(input.state).join(",") !== "fill_pos") throw Error("Foreign state entered fill.");
            if (input.event.type === "action" && input.event.actionId !== "$fillAction") throw Error("Foreign action.");
            if (input.event.type === "frame" || input.event.type === "action") input.state.fill_pos += 1;
            return {state:input.state,frames:["$surface"],surfaces:{"$surface":{commands:[
              {type:"circle",x:8+input.state.fill_pos,y:16,radius:2,color:"accent"}
            ]}}};
        """.trimIndent())
        val preview = ToolGenerationSmoke(sandbox, spec, "mixed", 1, spec.initialState, {}, generation).run()
        val count = generation.executeEvent(spec, "mixed", 1, preview.state, action(partAction), preview.ui) { _, _ ->
            error("Selected action reached the fill executor.")
        }
        assertEquals(1, JSONObject(count.state).getInt(partState))
        assertEquals(0, JSONObject(count.state).getInt("fill_pos"))
        assertEquals(preview.ui, count.ui)
        assertTrue(count.effects.isEmpty())
        assertEquals(0, count.records.length())
        assertNull(count.frames)
        assertNull(count.timers)
        assertNull(count.surfaces)
        assertNull(count.playback)

        val frame = generation.evaluate(spec, "mixed", 1, count.state,
            GraphicsContract.event("frame", 16, surfaceId = surface), count.ui)
        assertEquals(1, JSONObject(frame.state).getInt(partState))
        assertEquals(1, JSONObject(frame.state).getInt("fill_pos"))
        assertEquals(setOf(surface), frame.frames)
        assertNotEquals(preview.surfaces.toString(), frame.surfaces.toString())
    }

    @Test fun replacementOfRetainedHandlerCannotReadOrChangeTheSelectedCounter(): Unit = runBlocking {
        val generation = ToolGenerationService(offline, SandboxClient(context))
        val spec = spec("""
            if ("$partState" in input.state) throw Error("Foreign state entered fill.");
            if (input.event.type === "open") {
              try {
                globalThis.__artifact = raw => {
                  raw.state.$partState = 91;
                  return {state:raw.state};
                };
              } catch (error) {
                if (!(error instanceof TypeError)) throw error;
              }
            }
            if (input.event.type === "action" && input.event.actionId === "$fillAction") input.state.fill_pos += 1;
            return {state:input.state};
        """.trimIndent())
        val retained = generation.retainedSession()
        try {
            val open = generation.executeEvent(spec, "mixed", 1, spec.initialState, event("open"), spec.ui.toString()) { source, input ->
                assertEquals(setOf("fill_pos"), input.getJSONObject("state").keys().asSequence().toSet())
                retained.open(source, input.getJSONObject("state").toString(), "mixed", 1)
            }
            val count = generation.evaluate(spec, "mixed", 1, open.state, action(partAction), open.ui, retained)
            assertEquals(1, JSONObject(count.state).getInt(partState))
            val filled = generation.evaluate(spec, "mixed", 1, count.state, action(fillAction), count.ui, retained)
            val state = JSONObject(filled.state)
            assertEquals(1, state.getInt(partState))
            assertEquals(1, state.getInt("fill_pos"))
        } finally {
            retained.close()
        }
    }

    @Test fun runtimeRejectsForgedStateNodesNestedActionsAndTemplateActions(): Unit = runBlocking {
        val generation = ToolGenerationService(offline, SandboxClient(context))
        val spec = spec("return {state:input.state};", rows = true)
        val fillState = checkNotNull(spec.composition).fillState(JSONObject(spec.initialState))
        val valid = JSONObject().put("state", fillState)
        val forgeries = listOf(
            JSONObject().put("state", JSONObject(fillState.toString()).put(partState, 91)),
            JSONObject(valid.toString()).put("ui", column(JSONObject("""{"type":"text","text":"Counter","partId":"counter"}"""))),
            JSONObject(valid.toString()).put("ui", column(column(JSONObject("""{"type":"button","text":"Counter","action":"$partAction"}""")))),
            JSONObject(valid.toString()).put("ui", column(JSONObject("""{"type":"list","text":"Rows","bind":"fill_rows","rowTemplate":{"type":"button","text":"Counter","action":"$partAction"}}""")))
        )
        for (forgery in forgeries) {
            val failure = runCatching {
                generation.executeEvent(spec, "mixed", 1, spec.initialState, event("render"), spec.ui.toString()) { _, _ ->
                    SandboxResult("OK", forgery.toString())
                }
            }
            assertTrue(forgery.toString(), failure.isFailure)
        }
        var invoked = false
        val unknown = runCatching {
            generation.executeEvent(spec, "mixed", 1, spec.initialState, action("fill_missing"), spec.ui.toString()) { _, _ ->
                invoked = true
                SandboxResult("OK", valid.toString())
            }
        }
        assertTrue(unknown.isFailure)
        assertFalse(invoked)
    }

    @Test fun canceledExecutionAndDraftPublicationDoNotPublish(): Unit = runBlocking {
        val generation = ToolGenerationService(offline, SandboxClient(context))
        val spec = spec("return {state:input.state};")
        val canceled = runCatching {
            generation.executeEvent(spec, "mixed", 1, spec.initialState, event("preview"), spec.ui.toString()) { _, _ ->
                throw CancellationException("Fixture stop")
            }
        }
        assertTrue(canceled.exceptionOrNull() is CancellationException)
        val name = "mixed-runtime-${UUID.randomUUID()}.db"
        val store = ToolStore(context, name)
        try {
            val engine = ToolEngine(store, generation)
            val draft = engine.createDraft("Counter and scene", null)
                .copy(candidate = spec.json, previewState = spec.initialState, previewUi = spec.ui.toString())
            store.saveDraft(draft)
            assertTrue(runCatching { engine.accept(draft.id, canCommit = { false }) }.isFailure)
            assertEquals(draft, store.draft(draft.id))
            assertNull(store.tool(draft.id))

            val ordinary = ToolSpec.parse("""
                {"formatVersion":3,"title":"Existing","description":"","capabilities":[],
                 "stateSchema":{"value":"number"},"initialState":{"value":0},
                 "ui":{"type":"text","text":"Existing"},"source":"return {state:input.state};"}
            """.trimIndent())
            val old = ToolRecord("existing", 1, 1, ordinary.json, ordinary.initialState, ordinary.ui.toString(), null, true)
            store.saveTool(old, revision = true)
            val stale = engine.createDraft("Change existing", old.id)
                .copy(candidate = spec.json, previewState = spec.initialState, previewUi = spec.ui.toString())
            store.saveDraft(stale)
            val live = old.copy(stateVersion = 2, state = """{"value":7}""")
            store.saveTool(live, revision = false)
            assertTrue(runCatching { engine.accept(stale.id) }.isFailure)
            assertEquals(live, store.tool(old.id))
            assertEquals(stale, store.draft(stale.id))
        } finally {
            store.close()
            context.deleteDatabase(name)
            context.deleteDatabase(store.automationDatabase)
        }
    }
}

package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.sandbox.SandboxResult
import com.alpha.assistant.tools.ToolEngine
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.ToolGenerationSmoke
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.liveInputEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiveInputEngineWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val offline = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            error("Live input must stay offline.")
        }
    }

    private fun rawDefinition(
        source: String = """
            const state=input.state, event=input.event;
            if (event.type==="liveInput" && event.actionId==="edit") state.text=event.value;
            if (event.type==="liveInput" && event.actionId==="scale") state.scale=event.value;
            if (event.type==="action" && event.actionId==="apply") state.saved=event.value;
            return {state};
        """.trimIndent(),
    ) = JSONObject("""
        {"formatVersion":3,"title":"Live inputs","description":"","capabilities":[],
         "stateSchema":{"text":"string","scale":"number","saved":"string"},
         "initialState":{"text":"","scale":0,"saved":""},
         "ui":{"type":"column","children":[
           {"type":"input","text":"Live text","bind":"text","action":"edit","inputMode":"live","maxLength":12},
           {"type":"slider","text":"Live scale","bind":"scale","action":"scale","inputMode":"live","min":0,"max":10},
           {"type":"input","text":"Apply text","bind":"saved","action":"apply"}
         ]}}
    """.trimIndent()).put("source", source)

    private fun action(id: String, value: Any?) =
        JSONObject().put("type", "action").put("actionId", id).put("value", value ?: JSONObject.NULL)

    @Test fun ordinaryLiveTextAndSliderAreRestrictedAndOldApplyStillCommits(): Unit = runBlocking {
        val generation = ToolGenerationService(offline, SandboxClient(context))
        val spec = ToolSpec.parse(rawDefinition().toString())
        val name = "live-input-${UUID.randomUUID()}.db"
        ToolStore(context, name).use { store ->
            val old = ToolRecord("live", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, true)
            store.saveTool(old, revision = true)
            val engine = ToolEngine(store, generation)
            try {
                assertTrue(runCatching { engine.interact(old.id, 1, 1, "edit", "unsafe") }.isFailure)
                assertTrue(runCatching {
                    generation.evaluate(spec, old.id, 1, old.state, action("edit", "unsafe"), old.ui)
                }.isFailure)
                val applied = engine.interact(old.id, 1, 1, "apply", "applied")
                assertEquals("applied", JSONObject(applied.state).getString("saved"))
                val text = generation.evaluate(spec, old.id, 1, applied.state, liveInputEvent("edit", "typed"), applied.ui)
                val scale = generation.evaluate(spec, old.id, 1, text.state, liveInputEvent("scale", 6), text.ui)
                assertEquals("typed", JSONObject(scale.state).getString("text"))
                assertEquals(6, JSONObject(scale.state).getInt("scale"))
                assertEquals("applied", JSONObject(scale.state).getString("saved"))
                assertTrue(scale.effects.isEmpty())
                assertEquals(0, scale.records.length())
                assertNull(scale.frames)
                assertNull(scale.timers)
                assertNull(scale.playback)
                assertEquals(applied, store.tool(old.id))
                assertTrue(store.effects(old.id).isEmpty())
            } finally {
                engine.closeArtifacts()
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun mixedLiveInputReceivesOnlyFillStateAndRejectsFixedPartIds(): Unit = runBlocking {
        val plan = NeedsPlan.parse("""
            {"title":"Mixed","description":"","language":"en","inputs":[],"constants":[],"needs":[
              {"id":"count","type":"counter","label":"Count","description":"Increase and reset"},
              {"id":"custom","type":"action","label":"Editor","description":"Independent live input"}],"questions":[]}
        """.trimIndent())
        val assembly = PartsGeneration.build(plan) { body ->
            val id = JSONObject(body).getJSONArray("questions").getJSONObject(0).getString("id")
            val answer = JSONObject().put("id", id).put("choice", "A").put("confidence", 1)
                .put("probabilities", JSONObject().put("A", 1).put("B", 0))
            JSONObject().put("answers", JSONArray().put(answer)).toString()
        }
        val fill = """
            {"capabilities":[],"stateSchema":{"fill_text":"string"},"initialState":{"fill_text":""},
             "ui":{"type":"column","children":[
               {"type":"input","text":"Edit","bind":"fill_text","action":"fill_edit","inputMode":"live"}]},
             "source":"return {state:input.state};"}
        """.trimIndent()
        val spec = ToolComposition.complete(assembly, fill)
        val generation = ToolGenerationService(offline, SandboxClient(context))
        val result = generation.executeEvent(spec, "mixed", 1, spec.initialState, liveInputEvent("fill_edit", "local"), spec.ui.toString()) { _, input ->
            val state = input.getJSONObject("state")
            assertEquals(setOf("fill_text"), state.keys().asSequence().toSet())
            assertEquals("liveInput", input.getJSONObject("event").getString("type"))
            SandboxResult("OK", JSONObject().put("state", state.put("fill_text", "local")).toString())
        }
        assertEquals(0, JSONObject(result.state).getInt("p5_count_value"))
        assertEquals("local", JSONObject(result.state).getString("fill_text"))
        var fixedInvoked = false
        assertTrue(runCatching {
            generation.executeEvent(spec, "mixed", 1, result.state, liveInputEvent("p5_count_add", 1), result.ui) { _, _ ->
                fixedInvoked = true
                SandboxResult("OK", """{"state":{"fill_text":"wrong"}}""")
            }
        }.isFailure)
        assertFalse(fixedInvoked)
    }

    @Test fun malformedOrUnavailableLiveInputDoesNotEnterTheExecutor(): Unit = runBlocking {
        val raw = rawDefinition()
        raw.getJSONObject("stateSchema").put("stack", JSONObject("""{"type":"array","items":"string","maxItems":4}"""))
        raw.getJSONObject("initialState").put("stack", JSONArray().put("home"))
        val active = raw.getJSONObject("ui")
        val hidden = JSONObject("""{"type":"input","text":"Hidden","bind":"text","action":"hidden","inputMode":"live","visible":false}""")
        val disabled = JSONObject("""{"type":"input","text":"Disabled","bind":"text","action":"disabled","inputMode":"live","enabled":false}""")
        active.getJSONArray("children").put(hidden).put(disabled)
        val other = JSONObject("""{"type":"input","text":"Other route","bind":"text","action":"inactive","inputMode":"live"}""")
        raw.put("ui", JSONObject("""{"type":"routeStack","text":"Routes","bind":"stack","action":"back","routes":["home","detail"]}""")
            .put("children", JSONArray().put(active).put(other)))
        val spec = ToolSpec.parse(raw.toString())
        val generation = ToolGenerationService(offline, SandboxClient(context))
        val bad = listOf(
            liveInputEvent("hidden", "x"), liveInputEvent("disabled", "x"), liveInputEvent("inactive", "x"),
            liveInputEvent("apply", "x"), liveInputEvent("edit", "x".repeat(13)), liveInputEvent("scale", -1),
            liveInputEvent("edit", "x").put("calendar", JSONObject()),
            liveInputEvent("edit", "x").put("value", true),
            liveInputEvent("edit", "x").put("value", JSONObject().put("key", "x").put("value", "y").put("extra", 0))
        )
        var calls = 0
        for (event in bad) {
            assertTrue(event.toString(), runCatching {
                generation.executeEvent(spec, "live", 1, spec.initialState, event, spec.ui.toString()) { _, _ ->
                    calls++
                    SandboxResult("OK", """{"state":${spec.initialState}}""")
                }
            }.isFailure)
        }
        assertEquals(0, calls)
    }

    @Test fun rawPrivilegeAndSubscriptionFieldsFailEvenWhenEmptyAndTimeoutDoesNotReplay(): Unit = runBlocking {
        val generation = ToolGenerationService(offline, SandboxClient(context))
        val spec = ToolSpec.parse(rawDefinition().toString())
        val event = liveInputEvent("edit", "valid")
        val result = JSONObject().put("state", JSONObject(spec.initialState).put("text", "valid"))
        for ((name, value) in listOf(
            "effects" to JSONArray(), "records" to JSONArray(), "frames" to JSONArray(), "timers" to JSONArray(),
            "playback" to "paused", "calendar" to JSONObject(), "medicine" to JSONObject(), "navigation" to JSONObject()
        )) {
            assertTrue(name, runCatching {
                generation.executeEvent(spec, "live", 1, spec.initialState, event, spec.ui.toString()) { _, _ ->
                    SandboxResult("OK", JSONObject(result.toString()).put(name, value).toString())
                }
            }.isFailure)
        }
        var attempts = 0
        val timeout = runCatching {
            generation.executeEvent(spec, "live", 1, spec.initialState, event, spec.ui.toString()) { _, _ ->
                attempts++
                SandboxResult("TIMEOUT")
            }
        }.exceptionOrNull() as CloudFailure
        assertEquals("javascript_timeout", timeout.code)
        assertEquals(1, attempts)
        val cancel = runCatching {
            generation.executeEvent(spec, "live", 1, spec.initialState, event, spec.ui.toString()) { _, _ ->
                throw CancellationException("Fixture cancellation")
            }
        }.exceptionOrNull()
        assertTrue(cancel is CancellationException)
        assertEquals("", JSONObject(spec.initialState).getString("text"))
    }

    @Test fun smokeUsesLiveEventsAndOldNativeAndTemplateActionsStillValidate(): Unit = runBlocking {
        val sandbox = SandboxClient(context)
        val generation = ToolGenerationService(offline, sandbox)
        val source = """if(input.event.type==="liveInput") return {state:input.state, effects:[]}; return {state:input.state};"""
        val live = ToolSpec.parse(rawDefinition(source).toString())
        val smoke = runCatching {
            ToolGenerationSmoke(sandbox, live, "live", 1, live.initialState, {}, generation).run()
        }.exceptionOrNull() as com.alpha.assistant.tools.ToolSmokeFailure
        assertEquals("liveInput", smoke.event)

        val natives = listOf(
            Triple("calendar", "calendar.select", JSONObject("""{"version":1}""")),
            Triple("medicine", "medicine.cancelReminder", JSONObject("""{"version":1,"reminderKey":"fixture"}""")),
            Triple("navigation", "maps.markReached", JSONObject("""{"version":1,"stopId":"one","label":"One","latE6":0,"lonE6":0,"coordinateSystem":"wgs84"}"""))
        )
        for ((type, capability, args) in natives) {
            val node = JSONObject().put("type", type).put("action", "native").put("capability", capability).put("args", args)
            val spec = ToolSpec.parse(rawDefinition().put("ui", node).put("capabilities", JSONArray().put(capability)).toString())
            val event = action("native", null).put(type, JSONObject().put("capability", capability).put("args", args))
            val preview = generation.executeEvent(spec, "native", 1, spec.initialState, event, spec.ui.toString()) { program, input ->
                SandboxContract.validateRequest(program, input.toString())
                assertEquals(capability, input.getJSONObject("event").getJSONObject(type).getString("capability"))
                SandboxResult("OK", JSONObject().put("state", input.getJSONObject("state")).toString())
            }
            assertEquals(spec.initialState, preview.state)
        }

        val raw = rawDefinition().put("ui", JSONObject("""
            {"type":"list","text":"Rows","bind":"rows","rowTemplate":
             {"type":"input","text":"Edit row","bind":"label","action":"rowEdit","inputMode":"live"}}
        """.trimIndent()))
        raw.getJSONObject("stateSchema").put("rows", JSONObject("""{"type":"list","maxItems":2,"fields":{"id":"string","label":"string"}}"""))
        raw.getJSONObject("initialState").put("rows", JSONArray("""[{"id":"one","label":"Before"}]"""))
        for (liveMode in listOf(true, false)) {
            raw.getJSONObject("ui").getJSONObject("rowTemplate").put("inputMode", if (liveMode) "live" else "apply")
            val spec = ToolSpec.parse(raw.toString())
            val value = JSONObject().put("key", "one").put("value", "After")
            val event = if (liveMode) liveInputEvent("rowEdit", value) else action("rowEdit", value)
            val preview = generation.executeEvent(spec, "rows", 1, spec.initialState, event, spec.ui.toString()) { program, input ->
                SandboxContract.validateRequest(program, input.toString())
                val next = input.getJSONObject("state")
                next.getJSONArray("rows").getJSONObject(0).put("label", input.getJSONObject("event").getJSONObject("value").getString("value"))
                SandboxResult("OK", JSONObject().put("state", next).toString())
            }
            assertEquals("After", JSONObject(preview.state).getJSONArray("rows").getJSONObject(0).getString("label"))
        }
    }
}

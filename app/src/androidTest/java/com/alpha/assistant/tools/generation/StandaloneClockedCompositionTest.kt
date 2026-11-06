package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.capabilities.CapabilityCatalog
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class StandaloneClockedCompositionTest {
    // Failure cases: invalid executable or authority fields, noncanonical part data,
    // invalid identities or positions, absent projection, public parse, and fill refinement.
    private val clock = PartClockSample(LocalDate.of(2026, 3, 8), 540)
    private val plan = """
        {"title":"Workshop","description":"Workshop deadlines and attempts","language":"en",
         "inputs":[],"questions":[],"constants":[{"id":"windowDays","value":3}],
         "needs":[
           {"id":"due","type":"due_items","label":"Deadlines","description":"Track deadlines",
            "uses":{"window":"windowDays"},"items":[{"name":"Filter","offsetDays":2,"repeatDays":14}]},
           {"id":"count","type":"counter","label":"Attempts","description":"Count attempts"}
         ]}
    """.trimIndent()

    private suspend fun assembly(needs: String = plan, presentation: Boolean = false): PartsAssembly =
        PartsGeneration.build(NeedsPlan.parse(needs), lessonTarget = null, selectPresentation = presentation, clock = clock) { body ->
            val questions = JSONObject(body).getJSONArray("questions")
            val answers = (0 until questions.length()).map { index ->
                val question = questions.getJSONObject(index)
                val labels = question.getJSONObject("choices").keys().asSequence().toList()
                JSONObject().put("id", question.getString("id")).put("choice", "A").put("confidence", 0.9)
                    .put("probabilities", JSONObject().apply {
                        labels.forEach { put(it, if (it == "A") 0.9 else 0.1 / (labels.size - 1)) }
                    })
            }
            JSONObject().put("answers", jsonArray(answers)).toString()
        }

    private fun reject(block: () -> Any?) {
        assertTrue("Expected contract rejection.", runCatching(block).isFailure)
    }

    @Test fun sealReopensCanonicalPartsAndKeepsPresentation(): Unit = runBlocking {
        val assembled = assembly(presentation = true)
        assertTrue(assembled.requiresReadProjection)
        assertTrue(assembled.holes.isEmpty())
        reject { assembled.requireComplete() }
        val sealed = ToolComposition.sealStandalone(assembled)
        val stored = ToolSpec.parsePersisted(sealed.json)
        assertTrue(sealed.isSealedStandalone)
        assertTrue(stored.isSealedStandalone)
        assertEquals(sealed.json, stored.json)
        assertEquals("return {state:input.state};", stored.source)
        assertTrue(stored.capabilities.isEmpty())
        assertEquals(0, JSONObject(stored.json).getJSONObject("composition").getJSONArray("fillPositions").length())
        assertTrue(checkNotNull(assembled.preview.nativePresentation).sameSelection(stored.nativePresentation))
        val children = stored.ui.getJSONArray("children")
        assembled.parts.forEach { part ->
            val root = children.getJSONObject(part.position)
            assertEquals(part.partId, root.getString("partId"))
            assertEquals(part.instanceId, root.getString("partInstanceId"))
        }
        val composition = checkNotNull(stored.composition)
        val state = stored.validateState(stored.initialState)
        val view = composition.projectState(state, clock)
        assertFalse(sameToolJson(state, view))
        assertEquals(stored.initialState, state.toString())
        stored.validateUi(stored.ui, stored.validateState(view.toString()))
        assertEquals(0, composition.fillState(state).length())
        assertEquals(0, composition.fillUi(stored.ui).getJSONArray("children").length())
        val due = children.getJSONObject(0).getJSONArray("children").getJSONObject(2)
        val item = view.getJSONArray(due.getString("bind")).getJSONObject(0)
        val dueAction = due.getJSONArray("rowActions").getJSONObject(0).getString("action")
        val event = JSONObject().put("type", "action").put("actionId", dueAction).put("value", item.getString("id"))
        val updated = checkNotNull(composition.applyPartEvent(event, stored.ui, state, clock))
        assertEquals("2026-03-22", updated.getJSONArray("p3_due_items").getJSONObject(0).getString("date"))
        assertEquals(stored.initialState, state.toString())
        reject { composition.applyPartEvent(JSONObject().put("type", "action").put("actionId", "fill_forged"), stored.ui, state, clock) }
        reject { ToolSpec.parse(stored.json) }
        val stripped = JSONObject(stored.json).apply { remove("composition"); remove(NativePresentation.FIELD) }
        reject { ToolSpec.parse(stripped.toString()) }
        assertFalse(ToolSpec.parsePersisted(assembled.preview.json).isSealedStandalone)
        assertTrue(runCatching { stored.providerDefinition() }.exceptionOrNull() is StandaloneRefinementUnavailable)
        assertTrue(runCatching { ToolComposition.refine(stored, "{}") }.exceptionOrNull() is StandaloneRefinementUnavailable)
        assertEquals(sealed.json, stored.json)
        assertEquals(sealed.initialState, state.toString())
    }

    @Test fun persistedSealRejectsForgedSourceAuthorityStateUiAndPartData(): Unit = runBlocking {
        val original = ToolComposition.sealStandalone(assembly()).json
        fun altered(change: (JSONObject, JSONObject, JSONArray) -> Unit) {
            val raw = JSONObject(original)
            val manifest = raw.getJSONObject("composition")
            change(raw, manifest, manifest.getJSONArray("parts"))
            assertNull(ToolSpec.availablePersisted(raw.toString()))
        }
        altered { raw, _, _ -> raw.put("source", "return {state:input.state,effects:[]};") }
        altered { raw, _, _ -> raw.put("source", " return {state:input.state};") }
        altered { raw, _, _ -> raw.put("capabilities", JSONArray().put(CapabilityCatalog.ids.first())) }
        altered { raw, _, _ -> raw.put("capabilityResults", false) }
        altered { raw, _, _ -> raw.put("collections", JSONObject()) }
        altered { raw, _, _ -> raw.put("surfaces", JSONArray()) }
        altered { raw, _, _ -> raw.put("presentation", JSONObject().put("mode", "app")) }
        altered { raw, _, _ ->
            raw.getJSONObject("stateSchema").put("fill_forged", "number")
            raw.getJSONObject("initialState").put("fill_forged", 0)
        }
        altered { raw, _, _ -> raw.getJSONObject("initialState").put("fill_forged", 0) }
        altered { raw, _, _ ->
            val schema = raw.getJSONObject("stateSchema")
            schema.put("p3_due_items", "number")
        }
        altered { raw, _, _ ->
            val defaults = raw.getJSONObject("initialState")
            val key = defaults.keys().asSequence().single { it.startsWith("p5_count_") }
            defaults.put(key, 99)
        }
        altered { raw, _, _ -> raw.getJSONObject("ui").put("text", "Forged title") }
        altered { raw, _, _ ->
            val children = raw.getJSONObject("ui").getJSONArray("children")
            children.put(JSONObject().put("type", "text").put("text", "Forged fill"))
        }
        altered { raw, _, _ ->
            raw.getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("partInstanceId", "forged")
        }
        altered { raw, _, _ ->
            raw.getJSONObject("ui").getJSONArray("children").getJSONObject(0).getJSONArray("children")
                .getJSONObject(0).put("text", "Forged child")
        }
        altered { _, _, parts -> parts.getJSONObject(0).put("partId", "unregistered") }
        altered { _, _, parts -> parts.getJSONObject(0).put("instanceId", "forged") }
        altered { _, _, parts -> parts.getJSONObject(0).put("position", 1) }
        altered { _, _, parts -> parts.put(1, parts.getJSONObject(0)) }
        altered { _, _, parts -> parts.getJSONObject(0).getJSONObject("config").put("source", "return {};") }
        altered { _, _, parts -> parts.getJSONObject(0).getJSONObject("config").put("window", -1) }
        altered { _, _, parts -> parts.getJSONObject(0).getJSONObject("config").getJSONArray("items")
            .getJSONObject(0).put("date", "2026-03-20") }
        altered { _, manifest, _ -> manifest.put("parts", JSONArray()) }
        altered { raw, _, _ -> raw.put(NativePresentation.FIELD, JSONObject()) }
    }

    @Test fun absentProjectionAndUnresolvedNeedCannotCreateASeal(): Unit = runBlocking {
        val ordinary = JSONObject(plan).apply {
            getJSONArray("needs").remove(0)
            put("constants", JSONArray())
        }
        val ordinaryAssembly = assembly(ordinary.toString())
        assertFalse(ordinaryAssembly.requiresReadProjection)
        reject { ToolComposition.sealStandalone(ordinaryAssembly) }
        val whole = ToolComposition.sealStandalone(assembly())
        val raw = JSONObject(whole.json)
        val parts = raw.getJSONObject("composition").getJSONArray("parts")
        parts.remove(0)
        parts.getJSONObject(0).put("position", 0)
        raw.getJSONObject("ui").getJSONArray("children").remove(0)
        for (field in listOf("stateSchema", "initialState")) {
            val values = raw.getJSONObject(field)
            values.keys().asSequence().filter { it.startsWith("p3_due_") }.toList().forEach(values::remove)
        }
        assertNull(ToolSpec.availablePersisted(raw.toString()))

        val withHole = JSONObject(plan).apply {
            getJSONArray("needs").put(JSONObject().put("id", "extra").put("type", "action")
                .put("label", "Extra").put("description", "Custom section"))
        }
        val mixed = assembly(withHole.toString())
        reject { ToolComposition.sealStandalone(mixed) }
        val fill = """{"capabilities":[],"stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
            "ui":{"type":"column","children":[{"type":"text","text":"Custom"}]},"source":"return {state:input.state};"}"""
        val complete = ToolComposition.complete(mixed, fill)
        assertFalse(ToolSpec.parsePersisted(complete.json).isSealedStandalone)
        val strippedFill = JSONObject(complete.json).apply {
            getJSONObject("composition").put("fillPositions", JSONArray())
        }
        assertNull(ToolSpec.availablePersisted(strippedFill.toString()))
        assertTrue(JSONObject(complete.providerDefinition()).getJSONObject("stateSchema").has("fill_value"))
    }
}

package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePresentationWorkflowTest {
    // Failure cases: forged provider metadata, invalid recipes, stale focal slots, lost recipes,
    // mixed fill ownership, and canonical part tampering.
    private val needs = """
        {
          "title":"Домашние записи","description":"Счёт и заметки","language":"ru",
          "inputs":[],"constants":[],
          "needs":[
            {"id":"count","type":"counter","label":"Повторения","description":"Считать повторы"},
            {"id":"notes","type":"entries","label":"Заметки","description":"Короткие записи"}
          ],"questions":[]
        }
    """.trimIndent()

    private val fill = """
        {"capabilities":[],"stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
         "ui":{"type":"column","children":[{"type":"text","text":"Другой раздел"}]},
         "source":"return {state:input.state};"}
    """.trimIndent()

    private fun needsWithHole(): String = JSONObject(needs).apply {
        getJSONArray("needs").put(JSONObject().put("id", "custom").put("type", "action")
            .put("label", "Другой раздел").put("description", "Отдельная область"))
    }.toString()

    private fun selector(
        choices: Map<String, String> = emptyMap(), batches: MutableList<String>? = null,
    ): JevAsk = { raw ->
        val request = JSONObject(raw)
        batches?.add(request.getString("stage"))
        val questions = request.getJSONArray("questions")
        JSONObject().put("answers", JSONArray().apply {
            repeat(questions.length()) { index ->
                val question = questions.getJSONObject(index)
                val id = question.getString("id")
                val offered = question.getJSONObject("choices")
                val chosen = choices[id] ?: "A"
                check(offered.has(chosen))
                val probabilities = JSONObject()
                offered.keys().forEach { letter ->
                    probabilities.put(letter, if (letter == chosen) 0.9 else 0.1 / (offered.length() - 1))
                }
                put(JSONObject().put("id", id).put("choice", chosen).put("confidence", 0.9)
                    .put("probabilities", probabilities))
            }
        }).toString()
    }

    private suspend fun generate(raw: String = needs, ask: JevAsk = selector()): PartsAssembly =
        PartsGeneration.build(NeedsPlan.parse(raw), lessonTarget = null, selectPresentation = true, ask = ask)

    private fun reject(block: () -> Any?) {
        assertTrue("Expected contract rejection.", runCatching(block).isFailure)
    }

    @Test fun closedChoicesPickDifferentPresentationsInTheExistingBatches(): Unit = runBlocking {
        val batches = mutableListOf<String>()
        val plain = PartsGeneration.build(NeedsPlan.parse(needs), ask = selector()).requireComplete()
        val first = generate(ask = selector(batches = batches)).requireComplete()
        val second = generate(ask = selector(mapOf("v_profile" to "B", "v_preset" to "B", "v_focal" to "B"))).requireComplete()
        assertEquals(listOf("sections", "parts"), batches)
        val focus = checkNotNull(first.nativePresentation)
        val workspace = checkNotNull(second.nativePresentation)
        assertEquals(NativePresentationProfile.FOCUS, focus.profile)
        assertEquals(NativePresentationProfile.WORKSPACE, workspace.profile)
        assertEquals(NativeCompactSummary.FOCAL, focus.compactSummary)
        assertNotEquals(focus.preset, workspace.preset)
        assertEquals(0, focus.focalPosition)
        assertEquals(1, workspace.focalPosition)
        assertEquals(plain.ui.toString(), first.ui.toString())
        assertEquals(plain.ui.toString(), second.ui.toString())
        assertEquals(plain.initialState, first.initialState)
        assertEquals(plain.source, first.source)
        assertNull(plain.nativePresentation)
        assertEquals(focus.primaryActionId, first.ui.getJSONArray("children").getJSONObject(0)
            .getJSONArray("children").getJSONObject(1).getJSONArray("children").getJSONObject(0).getString("action"))
        assertEquals(workspace.primaryActionId, second.ui.getJSONArray("children").getJSONObject(1)
            .getJSONArray("children").getJSONObject(1).getString("action"))
        ToolUiCatalog.validateAction(first.ui, checkNotNull(focus.primaryActionId), null, JSONObject(first.initialState))

        val failed = generate(ask = { body ->
            if (JSONObject(body).getString("stage") == "parts") "" else selector()(body)
        })
        assertNull(failed.preview.nativePresentation)
        assertTrue(failed.selectorFailures.isNotEmpty())
        val declined = generate(ask = selector(mapOf("v_focal" to "C")))
        assertNull(declined.requireComplete().nativePresentation)
        assertTrue(declined.selectorFailures.isEmpty())
        val conflicting = generate(ask = selector(mapOf("p_count" to "B")))
        assertNull(conflicting.preview.nativePresentation)
        assertTrue(conflicting.selectorFailures.isNotEmpty())
    }

    @Test fun optionalPresentationErrorsKeepOnlyValidFunctionalSelections(): Unit = runBlocking {
        val valid = selector()
        suspend fun withAnswers(change: (JSONArray) -> Unit) = generate(ask = { raw ->
            val response = JSONObject(valid(raw))
            if (JSONObject(raw).getString("stage") == "parts") change(response.getJSONArray("answers"))
            response.toString()
        })
        // The fixture requests two part choices followed by three presentation choices.
        val presentationErrors = mapOf<String, (JSONArray) -> Unit>(
            "missing style answer" to { it.remove(2) },
            "duplicate style answer" to { it.put(2, it.getJSONObject(3)) },
            "invalid style distribution" to { it.getJSONObject(2).remove("probabilities") },
        )
        for ((name, change) in presentationErrors) {
            val result = withAnswers(change)
            assertEquals(name, listOf("count", "notes"), result.parts.map { it.needId })
            assertNull(name, result.requireComplete().nativePresentation)
            assertEquals(name, 1, result.selectorFailures.size)
        }

        val batchErrors = mapOf<String, (JSONArray) -> Unit>(
            "missing part answer" to { it.remove(0) },
            "duplicate part answer" to { it.put(1, it.getJSONObject(0)) },
            "invalid part choice" to { it.getJSONObject(0).put("choice", "Z") },
            "non-object row" to { it.put(2, "invalid") },
            "unidentifiable row" to { it.getJSONObject(2).remove("id") },
            "unknown style ID" to { it.getJSONObject(2).put("id", "v_unknown") },
            "too many rows" to { it.put(it.getJSONObject(2)) },
        )
        for ((name, change) in batchErrors) {
            val result = withAnswers(change)
            assertTrue(name, result.parts.isEmpty())
            assertEquals(name, HoleKind.SCREEN, result.holes.single().kind)
            assertEquals(name, 1, result.selectorFailures.size)
        }
        val canceled = runCatching {
            generate(ask = { raw ->
                if (JSONObject(raw).getString("stage") == "parts") throw CancellationException("Fixture canceled.")
                valid(raw)
            })
        }.exceptionOrNull()
        assertTrue(canceled is CancellationException)
    }

    @Test fun persistedRecipeSurvivesRefinementAndRejectsStaleFocalUi(): Unit = runBlocking {
        val original = generate().requireComplete()
        val recipe = checkNotNull(original.nativePresentation)
        val stored = ToolSpec.parsePersisted(original.json)
        assertEquals(recipe.profile, stored.nativePresentation?.profile)
        assertEquals(original.json, stored.json)
        assertFalse(original.providerDefinition().contains("nativePresentation"))
        reject { ToolSpec.parse(original.json) }

        val revised = JSONObject(original.providerDefinition()).put("title", "Новый заголовок")
        val counter = revised.getJSONObject("ui").getJSONArray("children").getJSONObject(0)
        counter.getJSONArray("children").getJSONObject(0).put("text", "Повторы").put("visible", false)
        val candidate = ToolSpec.parse(revised.toString())
        original.validateUi(candidate.ui)
        reject { ToolSpec.stateForRevision(original, candidate, original.initialState) }
        val refined = original.preserveNativePresentation(candidate)
        assertEquals(recipe.primaryActionId, refined.nativePresentation?.primaryActionId)
        assertEquals("Новый заголовок", refined.title)
        assertEquals(original.initialState, ToolSpec.stateForRevision(original, refined, original.initialState))
        assertFalse(refined.providerDefinition().contains("nativePresentation"))

        val moved = JSONObject(original.ui.toString())
        val children = moved.getJSONArray("children")
        val first = children.getJSONObject(0)
        children.put(0, children.getJSONObject(1)).put(1, first)
        reject { original.validateUi(moved) }
        reject { original.preserveNativePresentation(ToolSpec.parse(JSONObject(original.providerDefinition())
            .put("ui", moved).toString())) }
        val missing = JSONObject(original.ui.toString())
        val controls = missing.getJSONArray("children").getJSONObject(0).getJSONArray("children")
            .getJSONObject(1).getJSONArray("children")
        controls.getJSONObject(0).put("action", "unrelated_action")
        reject { original.validateUi(missing) }
    }

    @Test fun mixedPartsKeepCanonicalActionsAndFillCannotSeeOrForgeTheRecipe(): Unit = runBlocking {
        val old = ToolComposition.complete(PartsGeneration.build(NeedsPlan.parse(needsWithHole()), ask = selector()), fill)
        assertNull(old.nativePresentation)
        assertEquals(old.json, ToolSpec.parsePersisted(old.json).json)
        val assembly = generate(needsWithHole())
        val original = ToolComposition.complete(assembly, fill)
        val stored = ToolSpec.parsePersisted(original.json)
        val recipe = checkNotNull(stored.nativePresentation)
        val composition = checkNotNull(stored.composition)
        assertFalse(composition.fillDefinition(stored).has("nativePresentation"))
        assertFalse(stored.providerDefinition().contains("nativePresentation"))
        assertEquals(old.ui.toString(), stored.ui.toString())
        val counter = stored.ui.getJSONArray("children").getJSONObject(0)
        val metric = counter.getJSONArray("children").getJSONObject(0)
        val add = checkNotNull(recipe.primaryActionId)
        val event = JSONObject().put("type", "action").put("actionId", add).put("value", JSONObject.NULL)
        val updated = checkNotNull(composition.applyPartEvent(event, stored.ui, JSONObject(stored.initialState)))
        assertEquals(1, updated.getInt(metric.getString("bind")))
        val reset = counter.getJSONArray("children").getJSONObject(1).getJSONArray("children").getJSONObject(1)
        event.put("actionId", reset.getString("action"))
        assertEquals(0, checkNotNull(composition.applyPartEvent(event, stored.ui, updated)).getInt(metric.getString("bind")))

        val refined = ToolComposition.refine(stored, JSONObject(fill).put("title", "Новая запись").toString())
        assertEquals(add, refined.nativePresentation?.primaryActionId)
        assertEquals(stored.ui.toString(), refined.ui.toString())
        val rawRecipe = JSONObject(stored.json).getJSONObject("nativePresentation")
        reject { ToolComposition.complete(assembly, JSONObject(fill).put("nativePresentation", rawRecipe).toString()) }
        reject { ToolComposition.refine(stored, JSONObject(fill).put("nativePresentation", rawRecipe).toString()) }
        val fillUi = JSONObject(fill).getJSONObject("ui").put("children", JSONArray().put(metric))
        reject { ToolComposition.refine(stored, JSONObject(fill).put("ui", fillUi).toString()) }
        reject { composition.mergeFillUi(JSONObject(fill).getJSONObject("ui").put("children", JSONArray())) }
        val reorderFill = JSONObject("""
            {"capabilities":[],"stateSchema":{"fill_value":"number","fill_rows":{"type":"list","maxItems":4,
             "fields":{"id":"string","label":"string"}}},"initialState":{"fill_value":0,"fill_rows":[]},
             "ui":{"type":"column","children":[{"type":"list","text":"Записи","bind":"fill_rows",
             "reorder":{"action":"fill_move","labelField":"label"}}]},"source":"return {state:input.state};"}
        """)
        assertEquals(add, ToolComposition.refine(stored, reorderFill.toString()).nativePresentation?.primaryActionId)
        reorderFill.getJSONObject("ui").getJSONArray("children").getJSONObject(0).getJSONObject("reorder")
            .put("action", "unowned_move")
        reject { ToolComposition.refine(stored, reorderFill.toString()) }

        fun corrupt(edit: (JSONObject, JSONObject) -> Unit) {
            val raw = JSONObject(stored.json)
            edit(raw, raw.getJSONObject("nativePresentation"))
            reject { ToolSpec.parsePersisted(raw.toString()) }
        }
        corrupt { _, metadata -> metadata.put("profile", "full") }
        corrupt { _, metadata -> metadata.put("preset", "custom") }
        corrupt { _, metadata -> metadata.put("compactSummary", "everything") }
        corrupt { _, metadata -> metadata.put("color", "#F00") }
        corrupt { _, metadata -> metadata.put("focalPosition", 50) }
        corrupt { _, metadata -> metadata.put("focalPosition", 2).remove("primaryActionId") }
        corrupt { _, metadata -> metadata.put("primaryActionId", reset.getString("action")) }
        corrupt { raw, _ -> raw.getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("partId", "forged") }
    }
}

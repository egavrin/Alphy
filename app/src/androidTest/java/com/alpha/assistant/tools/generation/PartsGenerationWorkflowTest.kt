package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
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
class PartsGenerationWorkflowTest {
    // Failure cases: malformed JSON, unknown keys and types, duplicate IDs, missing references,
    // bad slot types and bounds, ambiguous choices, invalid probabilities, injection, and holes.
    private val raw = """
        {
          "title":"Домашние записи",
          "description":"Счёт, список и отметка",
          "language":"ru",
          "inputs":[{"id":"ready","label":"Готово","type":"boolean","value":false}],
          "constants":[{"id":"by","value":2}],
          "needs":[
            {"id":"count","type":"counter","label":"Повторения","description":"Счёт с шагом два","uses":{"step":"by"}},
            {"id":"count_value","type":"counter","label":"Круги","description":"Отдельный счёт"},
            {"id":"notes","type":"entries","label":"Заметки","description":"Добавить и удалить короткие записи"}
          ],
          "questions":[]
        }
    """.trimIndent()

    private val chooseFirst: JevAsk = { answer(it) }

    private fun answer(body: String, edit: (JSONObject) -> Unit = {}): String {
        val questions = JSONObject(body).getJSONArray("questions")
        val answers = JSONArray()
        repeat(questions.length()) {
            val question = questions.getJSONObject(it)
            val response = JSONObject().put("id", question.getString("id"))
            if (question.getString("type") == "noul") {
                response.put("probability", 0.8)
            } else {
                val choices = question.getJSONObject("choices")
                val probabilities = JSONObject()
                choices.keys().forEach { key -> probabilities.put(key, if (key == "A") 0.9 else 0.1 / (choices.length() - 1)) }
                response.put("choice", "A").put("confidence", 0.9).put("probabilities", probabilities)
            }
            edit(response)
            answers.put(response)
        }
        return JSONObject().put("answers", answers).toString()
    }

    private fun rejected(block: () -> Unit): PartsContractFailure =
        (runCatching(block).exceptionOrNull() as? PartsContractFailure)
            ?: error("Expected a parts contract failure.")

    @Test fun planRejectsAmbiguousOrExecutableStructure() {
        val invalid = listOf(
            raw.replace("\"title\":", "\"title\":\"repeat\",\"title\":"),
            raw.replace("\"counter\"", "\"unrecognized\""),
            raw.replace("\"count_value\"", "\"count\""),
            raw.replace("\"id\":\"by\"", "\"id\":\"ready\""),
            raw.replace("\"id\":\"count\"", "\"id\":\"constructor\""),
            raw.replace("\"step\":\"by\"", "\"step\":\"missing\""),
            raw.replace("\"type\":\"boolean\",\"value\":false", "\"type\":\"boolean\",\"value\":\"false\""),
            raw.replace("\"value\":2", "\"value\":1e309"),
            raw.replace("\"questions\":[]", "\"source\":\"return {}\",\"questions\":[]")
        )
        invalid.forEach { candidate -> assertTrue(rejected { NeedsPlan.parse(candidate) }.path.startsWith("$")) }
    }

    @Test fun invalidSlotsAndUnsupportedNeedsBecomeExplicitHoles(): Unit = runBlocking {
        for (value in listOf("0", "1000001", "\"two\"")) {
            val assembly = PartsGeneration.build(NeedsPlan.parse(raw.replace("\"value\":2", "\"value\":$value")), chooseFirst)
            assertEquals(listOf("count"), assembly.holes.single().needIds)
            assertEquals(HoleKind.NEED, assembly.holes.single().kind)
            assertEquals("$.ui.children[1]", assembly.holes.single().rootPath)
            assertEquals(listOf("ready", "count_value", "notes"), assembly.parts.map { it.needId })
            assertTrue(rejected { assembly.requireComplete() }.path.startsWith("$.holes"))
        }
        val unsupported = raw.replace("\"type\":\"counter\"", "\"type\":\"scene\"")
        val assembly = PartsGeneration.build(NeedsPlan.parse(unsupported), chooseFirst)
        assertEquals(listOf("count", "count_value"), assembly.holes.map { it.needIds.single() })
        assertTrue(assembly.holes.all { it.kind == HoleKind.SCENE })
        ToolSpec.parse(assembly.preview.json)
    }

    @Test fun badSelectionLeavesWholeScreenHoleAndReportsWhy() = runBlocking {
        val plan = NeedsPlan.parse(raw)
        val invalid: List<(JSONObject) -> Unit> = listOf(
            { it.put("choice", "Z") },
            { it.put("confidence", -0.1) },
            { it.put("confidence", "0.9") },
            { it.getJSONObject("probabilities").put("A", 1.1) },
            { it.getJSONObject("probabilities").put("B", 0.5) },
            { it.getJSONObject("probabilities").put("B", 0.9).put("A", 0.1) },
            { it.put("id", "s_ready") }
        )
        for (edit in invalid) {
            val result = PartsGeneration.build(plan, { answer(it, edit) })
            assertTrue(result.parts.isEmpty())
            assertEquals(HoleKind.SCREEN, result.holes.single().kind)
            assertEquals(listOf("ready", "count", "count_value", "notes"), result.holes.single().needIds)
            assertFalse(result.selectorFailures.isEmpty())
            rejected { result.requireComplete() }
        }
        val empty = PartsGeneration.build(plan, { "" })
        assertEquals(HoleKind.SCREEN, empty.holes.single().kind)
        assertFalse(empty.selectorFailures.isEmpty())
        val canceled = runCatching { PartsGeneration.build(plan, { throw CancellationException("stop") }) }
        assertTrue(canceled.exceptionOrNull() is CancellationException)
    }

    @Test fun selectionChecksWhichQuestionsBlockCompletion() = runBlocking {
        val plan = NeedsPlan.parse(raw.replace(
            "\"questions\":[]",
            "\"questions\":[{\"id\":\"target\",\"ask\":\"Что считать?\",\"options\":[\"Круги\",\"Шаги\"]}]"
        ))
        val blocked = PartsGeneration.build(plan, chooseFirst)
        assertEquals("target", blocked.blockingQuestions.single().id)
        assertTrue(blocked.holes.isEmpty())
        assertEquals("$.questions", rejected { blocked.requireComplete() }.path)
        val completed = PartsGeneration.build(plan, { body ->
            answer(body) { if (it.has("probability")) it.put("probability", 0.1) }
        })
        assertTrue(completed.blockingQuestions.isEmpty())
        assertEquals("Домашние записи", completed.requireComplete().title)
    }

    @Test fun partsOnlyFixtureExecutesControlsAndKeepsSlotTextInData() = runBlocking {
        val injectedText = """Заметки "`; return {state:{broken:true}}; //"""
        val source = JSONObject(raw)
        source.getJSONArray("needs").getJSONObject(2).put("label", injectedText)
        val plan = NeedsPlan.parse(source.toString())
        val result = PartsGeneration.build(plan, chooseFirst)
        val spec = result.requireComplete()
        assertEquals(result.preview.json, PartsGeneration.build(plan, chooseFirst).requireComplete().json)
        assertEquals(listOf("ready", "count", "count_value", "notes"), result.parts.map { it.needId })
        assertEquals(4, result.parts.map { it.instanceId }.toSet().size)
        assertEquals(4, result.parts.map { it.rootPath }.toSet().size)
        assertEquals(result.parts[1].partId, result.parts[2].partId)
        assertTrue(result.parts.all { it.widget.isNotEmpty() })
        assertFalse(spec.source.contains("broken"))
        assertTrue(ToolUiCatalog.widgetNodes(spec.ui).any { it.optString("text") == injectedText })

        val sandbox = SandboxClient(InstrumentationRegistry.getInstrumentation().targetContext)
        var state = spec.validateState(spec.initialState)
        suspend fun act(node: JSONObject, payload: Any? = null, id: String = node.getString("action")) {
            ToolUiCatalog.validateAction(spec.ui, id, payload, state)
            val event = JSONObject().put("type", "action").put("actionId", id).put("value", payload ?: JSONObject.NULL)
            val input = JSONObject().put("artifactId", "parts_fixture").put("revision", 1).put("state", state).put("event", event)
            val output = sandbox.execute(spec.source, input.toString())
            assertEquals("OK", output.status)
            state = spec.validateState(JSONObject(checkNotNull(output.json)).getJSONObject("state").toString())
            spec.validateUi(spec.ui, state)
        }
        val roots = spec.ui.getJSONArray("children")
        val toggle = roots.getJSONObject(0)
        act(toggle, true)
        assertTrue(state.getBoolean(toggle.getString("bind")))

        fun counter(index: Int) = roots.getJSONObject(index).getJSONArray("children")
        val first = counter(1)
        val second = counter(2)
        val firstBind = first.getJSONObject(0).getString("bind")
        val secondBind = second.getJSONObject(0).getString("bind")
        val buttons = first.getJSONObject(1).getJSONArray("children")
        act(buttons.getJSONObject(0))
        assertEquals(2, state.getInt(firstBind))
        assertEquals(0, state.getInt(secondBind))
        act(buttons.getJSONObject(1))
        assertEquals(0, state.getInt(firstBind))

        val notes = roots.getJSONObject(3).getJSONArray("children")
        val input = notes.getJSONObject(0)
        val add = notes.getJSONObject(1)
        val list = notes.getJSONObject(2)
        act(input, "Купить хлеб")
        act(add)
        val rows = state.getJSONArray(list.getString("bind"))
        val row = rows.getJSONObject(0)
        assertEquals("Купить хлеб", row.getString("text"))
        assertEquals("", state.getString(input.getString("bind")))
        act(list, row.getString("id"), list.getJSONArray("rowActions").getJSONObject(0).getString("action"))
        assertEquals(0, state.getJSONArray(list.getString("bind")).length())
    }

    @Test fun assembledFragmentsRejectGlobalIdentifierCollisions() {
        val duplicate = listOf(
            PartFragmentIds(setOf("p_count_value"), setOf("p_count_add")),
            PartFragmentIds(setOf("p_count_value"), setOf("p_count_reset"))
        )
        assertEquals("$.parts[1].state", rejected { PartsGeneration.requireUniqueIds(duplicate) }.path)
        val actions = listOf(duplicate[0], PartFragmentIds(setOf("p_other_value"), setOf("p_count_add")))
        assertEquals("$.parts[1].actions", rejected { PartsGeneration.requireUniqueIds(actions) }.path)
    }
}

package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolCompositionWorkflowTest {
    // Failure cases: untrusted manifest entry, unsupported version or part, invalid configuration,
    // misplaced or duplicate parts, forged native UI/actions/bindings, scope overlap, and aggregate bounds.
    private val needs = """
        {
          "title":"Практика","description":"Пример","language":"ru",
          "inputs":[{"id":"ready","label":"Готово","type":"boolean","value":false}],
          "constants":[{"id":"step","value":2},{"id":"first","value":"comí"},{"id":"second","value":"comía"},{"id":"right","value":2}],
          "needs":[
            {"id":"count","type":"counter","label":"Попытки","description":"Счёт с шагом два","uses":{"step":"step"}},
            {"id":"custom","type":"action","writes":[],"label":"Другой раздел","description":"Раздел с отдельным действием"},
            {"id":"notes","type":"entries","label":"Заметки","description":"Короткие записи"},
            {"id":"choice","type":"choice_exercise","label":"Выберите форму","description":"Задание с проверкой",
             "uses":{"option1":"first","option2":"second","correct":"right"}}
          ],
          "questions":[]
        }
    """.trimIndent()

    private val fill = """
        {
          "stateSchema":{"fill_value":"number"},"initialState":{"fill_value":0},
          "ui":{"type":"column","children":[{"type":"button","text":"Другой раздел","action":"fill_add"}]},
          "source":"input.state.fill_value += 1; return {state:input.state};","capabilities":[],
          "surfaces":[{"id":"fill_board","width":320,"height":240}],
          "hostControls":{"playback":true,"movement":["fill_board"]}
        }
    """.trimIndent()

    private suspend fun assembly(): PartsAssembly = PartsGeneration.build(NeedsPlan.parse(needs)) { request ->
        val questions = JSONObject(request).getJSONArray("questions")
        val answers = JSONArray()
        repeat(questions.length()) { index ->
            answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                .put("choice", "A").put("confidence", 0.9).put("probabilities", JSONObject().put("A", 0.9).put("B", 0.1)))
        }
        JSONObject().put("answers", answers).toString()
    }

    private fun reject(block: () -> Any?) {
        assertTrue("Expected contract rejection.", runCatching(block).isFailure)
    }

    @Test fun hostSealsOneMixedSpecAndModelInputCannotLoadItsManifest(): Unit = runBlocking {
        val base = assembly()
        val spec = ToolComposition.complete(base, fill)
        val stored = ToolSpec.parsePersisted(spec.json)
        assertEquals(spec.json, stored.json)
        assertEquals(setOf("fill_board"), stored.surfaces.keys)
        assertTrue(stored.hostControls.playback)
        assertEquals(setOf("fill_board"), stored.hostControls.movement)
        assertEquals(JSONObject(fill).getJSONObject("hostControls").toString(),
            JSONObject(stored.providerDefinition()).getJSONObject("hostControls").toString())
        val composition = checkNotNull(stored.composition)
        val full = stored.ui.getJSONArray("children")
        assertEquals(listOf(0, 1, 3, 4), base.parts.map { it.rootPath.substringAfterLast('[').dropLast(1).toInt() })
        assertEquals("counter", full.getJSONObject(1).getString("partId"))
        assertEquals(base.parts[1].instanceId, full.getJSONObject(1).getString("partInstanceId"))
        assertEquals("fill_add", composition.fillUi(stored.ui).getJSONArray("children").getJSONObject(0).getString("action"))
        assertEquals(setOf("fill_value"), composition.fillState(JSONObject(stored.initialState)).keys().asSequence().toSet())
        assertFalse(stored.source.contains(base.parts[1].instanceId))
        reject { ToolSpec.parse(spec.json) }
        reject { ToolComposition.complete(base, JSONObject(fill).put("composition", JSONObject()).toString()) }
        assertNull(ToolSpec.parsePersisted(base.preview.json).composition)
    }

    @Test fun storedManifestRejectsNoncanonicalPartsAndOwnership(): Unit = runBlocking {
        val spec = ToolComposition.complete(assembly(), fill)
        fun altered(change: (JSONObject, JSONObject, JSONArray) -> Unit) {
            val raw = JSONObject(spec.json)
            val manifest = raw.getJSONObject("composition")
            val parts = manifest.getJSONArray("parts")
            change(raw, manifest, parts)
            reject { ToolSpec.parsePersisted(raw.toString()) }
        }
        altered { _, manifest, _ -> manifest.put("version", 2) }
        altered { _, _, parts -> parts.getJSONObject(0).put("partId", "unknown") }
        altered { _, _, parts -> parts.getJSONObject(0).put("instanceId", "forged") }
        altered { _, _, parts -> parts.getJSONObject(0).put("position", 2) }
        altered { _, _, parts -> parts.getJSONObject(0).put("position", 1) }
        altered { _, _, parts -> parts.getJSONObject(1).getJSONObject("config").put("step", true) }
        altered { _, _, parts -> parts.getJSONObject(1).getJSONObject("config").put("step", 1000001) }
        altered { _, _, parts -> parts.getJSONObject(1).getJSONObject("config").put("handler", "return {};") }
        altered { _, manifest, parts -> manifest.put("parts", JSONArray().apply { repeat(13) { put(parts.getJSONObject(0)) } }) }
        altered { raw, _, _ ->
            raw.getJSONObject("ui").getJSONArray("children").getJSONObject(0).put("action", "fill_forged")
        }
        altered { raw, _, _ ->
            raw.getJSONObject("ui").getJSONArray("children").getJSONObject(2).put("partId", "counter")
        }
        altered { raw, _, _ ->
            raw.getJSONObject("ui").getJSONArray("children").getJSONObject(2).put("action", "outside_namespace")
        }
        altered { raw, _, _ ->
            val trusted = raw.getJSONObject("ui").getJSONArray("children").getJSONObject(0).getString("bind")
            raw.getJSONObject("initialState").put(trusted, true)
        }
        altered { raw, _, _ ->
            val trusted = raw.getJSONObject("ui").getJSONArray("children").getJSONObject(0).getString("bind")
            raw.getJSONObject("ui").getJSONArray("children").put(2, JSONObject().put("type", "text")
                .put("text", "Borrow").put("bind", trusted))
        }
    }

    @Test fun fillWritesAndRefinementPreserveHostOwnership(): Unit = runBlocking {
        val base = assembly()
        val spec = ToolComposition.complete(base, fill)
        val composition = checkNotNull(spec.composition)
        val original = JSONObject(spec.initialState)
        val trusted = spec.ui.getJSONArray("children").getJSONObject(0).getString("bind")
        val fillState = JSONObject().put("fill_value", 7)
        val next = composition.mergeFillState(original, fillState)
        assertEquals(7, next.getInt("fill_value"))
        assertFalse(next.getBoolean(trusted))
        assertEquals(0, original.getInt("fill_value"))
        reject { composition.mergeFillState(original, JSONObject().put("fill_value", 7).put(trusted, true)) }
        reject { composition.mergeFillState(original, JSONObject().put("fill_value", "7")) }
        val maliciousUi = JSONObject().put("type", "column").put("children", JSONArray().put(
            JSONObject().put("type", "button").put("text", "Fake").put("action", "p5_ready_edit")))
        reject { composition.mergeFillUi(maliciousUi) }
        val replacement = JSONObject(fill).put("initialState", JSONObject().put("fill_value", 10))
        val refined = ToolComposition.refine(spec, replacement.toString())
        assertEquals(spec.hostControls, refined.hostControls)
        assertEquals(7, JSONObject(ToolSpec.stateForRevision(spec, refined, next.toString())).getInt("fill_value"))
        reject { ToolSpec.stateForRevision(spec, base.preview, next.toString()) }
        val changed = JSONObject(spec.json)
        changed.getJSONObject("composition").getJSONArray("parts").getJSONObject(1).getJSONObject("config").put("step", 3)
        val changedPart = ToolSpec.parsePersisted(changed.toString())
        reject { ToolSpec.stateForRevision(spec, changedPart, next.toString()) }
    }

    @Test fun trustedPartEventsMatchThePartsOnlyHandlers(): Unit = runBlocking {
        val original = assembly()
        val spec = ToolComposition.complete(original, fill)
        val composition = checkNotNull(ToolSpec.parsePersisted(spec.json).composition)
        val sandbox = SandboxClient(InstrumentationRegistry.getInstrumentation().targetContext)
        var full = JSONObject(spec.initialState)
        var plain = JSONObject(original.preview.initialState)
        suspend fun parity(action: String, value: Any? = null) {
            val event = JSONObject().put("type", "action").put("actionId", action).put("value", value ?: JSONObject.NULL)
            full = checkNotNull(composition.applyPartEvent(event, spec.ui, full))
            val result = sandbox.execute(original.preview.source, JSONObject().put("artifactId", "native_parts")
                .put("revision", 1).put("event", event).put("state", plain).toString())
            assertEquals("OK", result.status)
            plain = JSONObject(checkNotNull(result.json)).getJSONObject("state")
            plain.keys().forEach { key -> assertEquals(plain.get(key).toString(), full.get(key).toString()) }
            assertEquals(0, full.getInt("fill_value"))
            spec.validateUi(spec.ui, full)
        }
        val children = spec.ui.getJSONArray("children")
        parity(children.getJSONObject(0).getString("action"), true)
        val buttons = children.getJSONObject(1).getJSONArray("children").getJSONObject(1).getJSONArray("children")
        parity(buttons.getJSONObject(0).getString("action"))
        parity(buttons.getJSONObject(1).getString("action"))
        val notes = children.getJSONObject(3).getJSONArray("children")
        parity(notes.getJSONObject(0).getString("action"), "  Запись  ")
        parity(notes.getJSONObject(1).getString("action"))
        parity(notes.getJSONObject(2).getJSONArray("rowActions").getJSONObject(0).getString("action"), "0")
        val choice = children.getJSONObject(4).getJSONArray("children").getJSONObject(0).getString("action")
        parity(choice, 1)
        parity(choice, 2)
        assertNull(composition.applyPartEvent(JSONObject().put("type", "action").put("actionId", "fill_add")
            .put("value", JSONObject.NULL), spec.ui, full))
    }
}

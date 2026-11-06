package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.courses.CourseSlideKind
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonSourcePlanWorkflowTest {
    // Failure cases: sources in the wrong plan, misplaced fields, no selected sources,
    // malformed lists, duplicate or oversized selections, and IDs outside the host allowlist.
    private val kind = CourseSlideKind.EXPLANATION
    private val allowlist = setOf("s3", "s1", "s2")
    private val raw = """
        {
          "title":"Пример","description":"Краткое объяснение","language":"ru",
          "inputs":[],"constants":[{"id":"detail","value":"Текст из материала"}],
          "needs":[
            {"id":"main","type":"explanation","label":"Объяснение","description":"Короткое объяснение","uses":{"body":"detail"}},
            {"id":"more","type":"example","label":"Пример","description":"Дополнительный пример","uses":{"body":"detail"}}
          ],"questions":[]
        }
    """.trimIndent()

    private fun withSources(first: Any, second: Any? = null): JSONObject = JSONObject(raw).apply {
        val needs = getJSONArray("needs")
        needs.getJSONObject(0).put("sourceIds", first)
        if (second != null) needs.getJSONObject(1).put("sourceIds", second)
    }

    private fun ids(vararg values: Any) = JSONArray().apply { values.forEach { put(it) } }

    private fun reject(block: () -> Any?) {
        assertTrue("Expected a plan contract failure.", runCatching(block).exceptionOrNull() is PartsContractFailure)
    }

    @Test fun promptIncludesOnlyTheCurrentSourceIds(): Unit {
        val prompt = NeedsPlan.promptFor(kind, allowlist)
        assertTrue(prompt.contains("sourceIds"))
        assertTrue(prompt.contains("""["s1","s2","s3"]"""))
        assertFalse(NeedsPlan.promptFor(kind).contains("sourceIds"))
        assertEquals(NeedsPlan.prompt, NeedsPlan.promptFor(null))
        reject { NeedsPlan.promptFor(null, allowlist) }
        reject { NeedsPlan.promptFor(kind, setOf("https://example.invalid")) }
        reject { NeedsPlan.promptFor(kind, setOf("s21")) }
    }

    @Test fun attributedPartsKeepIdsInThePlanOnly(): Unit = runBlocking {
        val plan = NeedsPlan.parse(withSources(ids("s3", "s1"), ids("s1")).toString(), kind, allowlist)
        assertEquals(listOf("s3", "s1"), plan.needs[0].sourceIds)
        assertEquals(listOf("s1"), plan.needs[1].sourceIds)
        assertTrue(runCatching { (plan.needs[0].sourceIds as MutableList<String>).add("s2") }.isFailure)
        val assembly = PartsGeneration.build(plan, kind) { body ->
            assertFalse(body.contains("sourceIds"))
            assertFalse(body.contains("\"s1\""))
            val questions = JSONObject(body).getJSONArray("questions")
            val answers = JSONArray()
            repeat(questions.length()) { index ->
                answers.put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                    .put("choice", "A").put("confidence", 0.9)
                    .put("probabilities", JSONObject().put("A", 0.9).put("B", 0.1)))
            }
            JSONObject().put("answers", answers).toString()
        }
        assertEquals(listOf("main", "more"), assembly.parts.map { it.needId })
        val definition = assembly.requireComplete().json
        assertFalse(definition.contains("sourceIds"))
        assertFalse(definition.contains("\"s1\""))
        assertFalse(definition.contains("\"s3\""))
    }

    @Test fun parserRejectsUnprovenOrMissingAttribution() {
        val invalidValues = listOf(
            "s1", JSONObject.NULL, ids(1), ids("s4"), ids("S1"), ids("s1", "s1"),
            ids("s1", "s2", "s3", "s4")
        )
        invalidValues.forEach { value ->
            reject { NeedsPlan.parse(withSources(value).toString(), kind, allowlist) }
        }
        reject { NeedsPlan.parse(raw, kind, allowlist) }
        reject { NeedsPlan.parse(withSources(ids(), ids()).toString(), kind, allowlist) }
        reject { NeedsPlan.parse(JSONObject(raw).put("sourceIds", ids("s1")).toString(), kind, allowlist) }
        reject { NeedsPlan.parse(withSources(ids("s1")).toString(), kind) }
        reject { NeedsPlan.parse(withSources(ids()).toString(), kind) }
        reject { NeedsPlan.parse(withSources(ids("s1")).toString()) }
        reject { NeedsPlan.parse(raw, allowedSourceIds = allowlist) }
        reject { NeedsPlan.parse(raw, kind, setOf("file:///private/data")) }

        val partial = NeedsPlan.parse(withSources(ids(), ids("s2")).toString(), kind, allowlist)
        assertTrue(partial.needs[0].sourceIds.isEmpty())
        assertEquals(listOf("s2"), partial.needs[1].sourceIds)
        assertTrue(NeedsPlan.parse(raw, kind).needs.all { it.sourceIds.isEmpty() })
    }
}

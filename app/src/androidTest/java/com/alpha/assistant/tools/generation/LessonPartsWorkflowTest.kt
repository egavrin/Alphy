package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.courses.CourseAnswer
import com.alpha.assistant.courses.CourseExercise
import com.alpha.assistant.courses.CourseSlideKind
import com.alpha.assistant.courses.EXERCISE_FIELD
import com.alpha.assistant.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonPartsWorkflowTest {
    private val selector: JevAsk = { raw ->
        val questions = JSONObject(raw).getJSONArray("questions")
        JSONObject().put("answers", JSONArray().apply {
            repeat(questions.length()) { index ->
                put(JSONObject().put("id", questions.getJSONObject(index).getString("id"))
                    .put("choice", "A").put("confidence", 0.9)
                    .put("probabilities", JSONObject().put("A", 0.9).put("B", 0.1)))
            }
        }).toString()
    }

    private fun plan(type: String, vararg slots: Pair<String, Any>): JSONObject {
        val constants = JSONArray()
        val uses = JSONObject()
        slots.forEachIndexed { index, (role, value) ->
            val id = "value$index"
            constants.put(JSONObject().put("id", id).put("value", value))
            uses.put(role, id)
        }
        val need = JSONObject().put("id", "main").put("type", type).put("label", "Выберите форму")
            .put("description", "Упражнение из урока").put("uses", uses)
        return JSONObject().put("title", "Практика").put("description", "Формы").put("language", "ru")
            .put("inputs", JSONArray()).put("constants", constants).put("needs", JSONArray().put(need))
            .put("questions", JSONArray())
    }

    private fun choice() = plan("choice_exercise", "option1" to "comí", "option2" to "comía", "correct" to 2)
    private suspend fun build(raw: JSONObject, target: CourseSlideKind): PartsAssembly =
        PartsGeneration.build(NeedsPlan.parse(raw.toString(), target), target, ask = selector)

    @Test fun allSevenKindsUseTheTargetAndTheCourseGradesPassiveExercises(): Unit = runBlocking {
        for ((target, need) in listOf(
            CourseSlideKind.EXPLANATION to "explanation", CourseSlideKind.EXAMPLE to "example",
            CourseSlideKind.CHECK to "feedback", CourseSlideKind.SUMMARY to "summary"
        )) {
            val assembly = build(plan(need, "body" to "Форма зависит от контекста."), target)
            val spec = assembly.requireComplete()
            assertNull(CourseExercise.read(spec, target))
            assertEquals("information", assembly.parts.single().partId)
            assertTrue(assembly.parts.single().ids.actions.isEmpty())
        }

        val selected = build(choice(), CourseSlideKind.CHOICE)
        val spec = selected.requireComplete()
        val choiceExercise = checkNotNull(CourseExercise.read(spec, CourseSlideKind.CHOICE))
        assertEquals("Выберите форму", choiceExercise.prompt.question)
        assertEquals(listOf("comí", "comía"), choiceExercise.prompt.options.map { it.label })
        assertNull(choiceExercise.actionId)
        assertTrue(selected.parts.single().ids.actions.isEmpty())
        assertEquals("divider", selected.parts.single().full.getString("type"))
        assertTrue(choiceExercise.grade(CourseAnswer.Choice(choiceExercise.prompt.options[1].id)))
        assertFalse(choiceExercise.grade(CourseAnswer.Choice(choiceExercise.prompt.options[0].id)))
        assertEquals(choiceExercise.prompt, CourseExercise.read(
            build(choice(), CourseSlideKind.CHOICE).requireComplete(), CourseSlideKind.CHOICE
        )?.prompt)

        val fill = build(plan("fill_exercise", "accepted1" to " comía ", "accepted2" to "comí"), CourseSlideKind.FILL_IN)
        val fillExercise = checkNotNull(CourseExercise.read(fill.requireComplete(), CourseSlideKind.FILL_IN))
        assertNull(fillExercise.actionId)
        assertTrue(fillExercise.grade(CourseAnswer.FillIn("comía")))
        assertTrue(fillExercise.grade(CourseAnswer.FillIn(" comí ")))
        assertFalse(fillExercise.grade(CourseAnswer.FillIn("como")))

        val order = build(plan("order_exercise", "option1" to "ayer", "option2" to "comí", "option3" to "pan",
            "position1" to 2, "position2" to 3, "position3" to 1), CourseSlideKind.ORDER)
        val orderExercise = checkNotNull(CourseExercise.read(order.requireComplete(), CourseSlideKind.ORDER))
        val ids = orderExercise.prompt.options.map { it.id }
        assertNull(orderExercise.actionId)
        assertTrue(orderExercise.grade(CourseAnswer.Order(listOf(ids[1], ids[2], ids[0]))))
        assertFalse(orderExercise.grade(CourseAnswer.Order(ids)))
    }

    @Test fun invalidTargetsAndSlotsFailBeforeSelection(): Unit = runBlocking {
        val cases = listOf(
            CourseSlideKind.CHECK to plan("summary", "body" to "Context"),
            CourseSlideKind.FILL_IN to choice(),
            CourseSlideKind.ORDER to plan("order_exercise", "option1" to "a", "option2" to "b",
                "position1" to 2, "position2" to 2),
            CourseSlideKind.ORDER to plan("order_exercise", "option1" to "a", "option2" to "b", "position1" to 1),
            CourseSlideKind.CHOICE to plan("choice_exercise", "option1" to "a", "option3" to "b", "correct" to 1),
            CourseSlideKind.CHOICE to plan("choice_exercise", "option1" to "a", "option2" to " a ", "correct" to 1),
            CourseSlideKind.CHOICE to plan("choice_exercise", "option1" to "a", "option2" to "b", "correct" to 3),
            CourseSlideKind.FILL_IN to plan("fill_exercise", "accepted1" to "a", "accepted2" to " a "),
            CourseSlideKind.FILL_IN to plan("fill_exercise", "accepted2" to "a"),
            CourseSlideKind.FILL_IN to plan("fill_exercise", "accepted1" to "a".repeat(500), "accepted2" to "b".repeat(500),
                "accepted3" to "c".repeat(500), "accepted4" to "d".repeat(500)).apply {
                getJSONArray("needs").getJSONObject(0).put("label", "q".repeat(100))
            },
            CourseSlideKind.CHOICE to choice().apply {
                getJSONArray("needs").put(JSONObject().put("id", "other").put("type", "action")
                    .put("label", "Other").put("description", "Unrelated action"))
            },
            CourseSlideKind.CHOICE to choice().apply {
                val needs = getJSONArray("needs")
                needs.put(JSONObject(needs.getJSONObject(0).toString()).put("id", "second"))
            },
            CourseSlideKind.CHOICE to choice().put("lessonTarget", "CHOICE")
        )
        for ((target, raw) in cases) {
            var selectorCalled = false
            val failure = runCatching {
                PartsGeneration.build(NeedsPlan.parse(raw.toString(), target), target) {
                    selectorCalled = true
                    error("Invalid lesson needs entered the selector.")
                }
            }.exceptionOrNull()
            assertTrue(target.name, failure is PartsContractFailure)
            assertFalse(selectorCalled)
        }
    }

    @Test fun restoredConfigurationAndImmutableDefinitionKeepTheOriginalKey(): Unit = runBlocking {
        val assembly = build(choice(), CourseSlideKind.CHOICE)
        val part = assembly.parts.single()
        val original = assembly.requireComplete()
        val restored = ToolSpec.parsePersisted(original.json)
        val exercise = checkNotNull(CourseExercise.read(restored, CourseSlideKind.CHOICE))
        val originalKey = JSONObject(restored.initialState).getString(EXERCISE_FIELD)
        val rebuilt = ToolParts.restore(part.needId, part.partId, part.config, "$.config")
        assertEquals(originalKey, rebuilt.fragment.initial.getString(EXERCISE_FIELD))
        val spoof = JSONObject(originalKey).put("correct", exercise.prompt.options[0].id)
        val runtime = JSONObject(restored.initialState).put(EXERCISE_FIELD, spoof.toString())
        restored.validateState(runtime.toString())
        assertTrue(checkNotNull(CourseExercise.read(restored, CourseSlideKind.CHOICE))
            .grade(CourseAnswer.Choice(exercise.prompt.options[1].id)))
        assertFalse(restored.source.contains(EXERCISE_FIELD))

        val hostMixed = assembly.copy(holes = listOf(PartHole(HoleKind.NEED, listOf("tail"), "$.ui.children[1]")))
        val fill = JSONObject("""{"capabilities":[],"stateSchema":{"fill_count":"number"},"initialState":{"fill_count":0},
            "ui":{"type":"column","children":[{"type":"text","text":"Extra"}]},"source":"return {state:input.state};"}""")
        val sealed = ToolComposition.complete(hostMixed, fill.toString())
        val stored = ToolSpec.parsePersisted(sealed.json)
        val composition = checkNotNull(stored.composition)
        assertFalse(composition.fillState(JSONObject(stored.initialState)).has(EXERCISE_FIELD))
        assertFalse(composition.fillDefinition(stored).getJSONObject("stateSchema").has(EXERCISE_FIELD))
        assertEquals(originalKey, JSONObject(stored.initialState).getString(EXERCISE_FIELD))
        assertTrue(runCatching {
            composition.mergeFillState(JSONObject(stored.initialState), JSONObject().put("fill_count", 1)
                .put(EXERCISE_FIELD, spoof.toString()))
        }.isFailure)
        val forged = JSONObject(sealed.json)
        forged.getJSONObject("initialState").put(EXERCISE_FIELD, spoof.toString())
        assertTrue(runCatching { ToolSpec.parsePersisted(forged.toString()) }.isFailure)
        fill.getJSONObject("stateSchema").put(EXERCISE_FIELD, "string")
        fill.getJSONObject("initialState").put(EXERCISE_FIELD, spoof.toString())
        assertTrue(runCatching { ToolComposition.complete(hostMixed, fill.toString()) }.isFailure)
    }

    @Test fun normalChoiceKeepsItsNativeActionAndHasNoCourseMetadata(): Unit = runBlocking {
        val normal = PartsGeneration.build(NeedsPlan.parse(choice().toString()), ask = selector)
        val spec = normal.requireComplete()
        assertEquals("choice_exercise", normal.parts.single().partId)
        assertFalse(spec.schema.has(EXERCISE_FIELD))
        assertTrue(normal.parts.single().ids.actions.isNotEmpty())
        assertEquals("singleChoice", normal.parts.single().full.getJSONArray("children").getJSONObject(0).getString("type"))
    }
}

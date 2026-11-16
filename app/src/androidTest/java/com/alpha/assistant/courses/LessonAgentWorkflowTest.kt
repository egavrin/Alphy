package com.alpha.assistant.courses

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.images.ImageGeneration
import com.alpha.assistant.images.ImageProvider
import com.alpha.assistant.images.ImageSettings
import com.alpha.assistant.images.ProviderImage
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.LessonToolPreview
import com.alpha.assistant.tools.ToolExecutionPolicy
import com.alpha.assistant.tools.ToolGenerationService
import com.alpha.assistant.tools.writeAppReply
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Course slides come from the app agent loop. The host checks the course rules in each step. */
@RunWith(AndroidJUnit4::class)
class LessonAgentWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun slide(text: String, exercise: JSONObject? = null, extraUi: JSONObject? = null) = JSONObject().apply {
        put("title", "Past tense")
        put("description", "")
        val schema = JSONObject().put("step", "number")
        val state = JSONObject().put("step", 0)
        exercise?.let { schema.put(EXERCISE_FIELD, "string"); state.put(EXERCISE_FIELD, it.toString()) }
        put("stateSchema", schema)
        put("initialState", state)
        val children = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        extraUi?.let(children::put)
        put("ui", JSONObject().put("type", "column").put("children", children))
        put("source", "return {state:input.state};")
    }

    private fun scene(commands: String) = JSONObject("""
        {"title":"Orbit","description":"","stateSchema":{"step":"number"},"initialState":{"step":0},
        "surfaces":[{"id":"diagram","width":64,"height":64}],
        "ui":{"type":"column","children":[{"type":"text","text":"The Moon moves around Earth."},
          {"type":"canvas","text":"Orbit illustration","surfaces":["diagram"]}]}}
    """.trimIndent()).put("source", "return {state:input.state,surfaces:{diagram:{commands:$commands}}};")

    private val choice = JSONObject().put("kind", "CHOICE").put("question", "Which form is past?")
        .put("options", JSONArray().put(JSONObject().put("id", "a").put("label", "walked"))
            .put(JSONObject().put("id", "b").put("label", "walk")))
        .put("correct", "a")

    private fun finish(text: String, sources: List<String>? = null, illustration: JSONObject? = null) = JSONObject()
        .put("tool", "finish").put("summary", "The slide is ready.")
        .put("checks", JSONArray().put(JSONObject().put("name", "shows").put("steps",
            JSONArray().put(JSONObject().put("expect", "text").put("contains", text)))))
        .apply {
            sources?.let { put("sources", JSONArray(it)) }
            illustration?.let { put("illustration", it) }
        }.toString()

    private suspend fun generate(
        provider: ChatProvider, kind: CourseSlideKind, sources: Set<String> = emptySet(),
        policy: ToolExecutionPolicy = ToolExecutionPolicy(validateDefinition = {}, validateResult = {}),
        languageHint: String? = null, session: CloudSession = CloudSession(), images: ImageGeneration = pictures(false),
    ): LessonToolPreview {
        return ToolGenerationService(provider, SandboxClient(context), images).generateLesson("fixture", "Build the reached slide about the past tense.", session, "lesson-agent",
            courseLessonTarget(kind, sources, policy, languageHint))
    }

    @Test fun staticSlideFinishesWithItsSources(): Unit = runBlocking {
        val text = "The past tense describes finished actions."
        val provider = AgentScriptProvider(writeAppReply(slide(text)), finish(text, listOf("s2", "s1")))
        val result = generate(provider, CourseSlideKind.EXPLANATION, setOf("s1", "s2"))
        assertEquals(listOf("s2", "s1"), result.sourceIds)
        assertEquals(2, provider.calls.size)
        for (raw in listOf(result.preview.spec.json, result.preview.state, result.preview.ui)) {
            assertFalse(raw, raw.contains("sourceIds", ignoreCase = true))
        }
    }

    @Test fun aSlideWriteWithChecksAndSourcesFinishesInOneCall(): Unit = runBlocking {
        val text = "The past tense describes finished actions."
        val reply = JSONObject(finish(text, listOf("s1"))).put("tool", "write_app").put("app", slide(text)).toString()
        val provider = AgentScriptProvider(reply)
        val result = generate(provider, CourseSlideKind.EXPLANATION, setOf("s1"))
        assertEquals(listOf("s1"), result.sourceIds)
        assertEquals(1, provider.calls.size)
    }

    @Test fun exerciseKindsKeepTheCourseContract(): Unit = runBlocking {
        val fill = JSONObject().put("kind", "FILL_IN").put("question", "Past of walk?").put("accepted", JSONArray().put("walked"))
        val order = JSONObject().put("kind", "ORDER").put("question", "Put the steps in order.")
            .put("options", JSONArray().put(JSONObject().put("id", "a").put("label", "first"))
                .put(JSONObject().put("id", "b").put("label", "second")))
            .put("correctOrder", JSONArray().put("a").put("b"))
        for ((kind, exercise) in listOf(CourseSlideKind.CHOICE to choice, CourseSlideKind.FILL_IN to fill, CourseSlideKind.ORDER to order)) {
            val text = "Answer the question below."
            val provider = AgentScriptProvider(writeAppReply(slide(text, exercise)), finish(text))
            val result = generate(provider, kind)
            val read = checkNotNull(CourseExercise.read(result.preview.spec, kind)) { kind.name }
            assertEquals(kind.name, kind, read.prompt.kind)
        }
    }

    @Test fun answerControlIsRejectedThenFixed(): Unit = runBlocking {
        val text = "Choose the past form."
        val control = JSONObject("""{"type":"singleChoice","text":"Form","action":"answer","bind":"selected",
            "options":[{"text":"walked","value":"a"},{"text":"walk","value":"b"}]}""")
        val withControl = slide(text, JSONObject(choice.toString()).put("actionId", "answer"), control).apply {
            getJSONObject("stateSchema").put("selected", "string")
            getJSONObject("initialState").put("selected", "a")
        }
        val provider = AgentScriptProvider(writeAppReply(withControl), writeAppReply(slide(text, choice)), finish(text))
        val result = generate(provider, CourseSlideKind.CHOICE)
        assertTrue(provider.feedback(1), provider.feedback(1).startsWith("write_app: REJECTED"))
        assertTrue(provider.feedback(1), provider.feedback(1).contains("Remove actionId"))
        assertNotNull(CourseExercise.read(result.preview.spec, CourseSlideKind.CHOICE))
    }

    @Test fun capabilityIsRejectedByTheCoursePolicy(): Unit = runBlocking {
        val text = "The past tense describes finished actions."
        val withCapability = slide(text).put("capabilities", JSONArray().put("notification.post"))
        val provider = AgentScriptProvider(writeAppReply(withCapability), writeAppReply(slide(text)), finish(text))
        val result = generate(provider, CourseSlideKind.EXPLANATION, policy = CourseExecutionChecks(CourseSlideKind.EXPLANATION).policy)
        assertTrue(provider.feedback(1), provider.feedback(1).startsWith("write_app: REJECTED"))
        assertEquals(0, result.preview.spec.capabilities.size)
    }

    @Test fun sourcesMustMatchTheCourse(): Unit = runBlocking {
        val text = "The past tense describes finished actions."
        val sourced = AgentScriptProvider(writeAppReply(slide(text)), finish(text, listOf("s9")), finish(text, emptyList()),
            finish(text, listOf("s1", "s1")), finish(text, listOf("s1")))
        assertEquals(listOf("s1"), generate(sourced, CourseSlideKind.EXPLANATION, setOf("s1")).sourceIds)
        for (call in 2..4) assertTrue(sourced.feedback(call), sourced.feedback(call).startsWith("finish: REJECTED"))

        val unsourced = AgentScriptProvider(writeAppReply(slide(text)), finish(text, listOf("s1")), finish(text, emptyList()))
        assertTrue(generate(unsourced, CourseSlideKind.EXPLANATION).sourceIds.isEmpty())
        assertTrue(unsourced.feedback(2), unsourced.feedback(2).startsWith("finish: REJECTED"))
    }

    @Test fun blankSceneIsRejected(): Unit = runBlocking {
        val provider = AgentScriptProvider(writeAppReply(scene("[]")),
            writeAppReply(scene("[{type:'circle',x:32,y:32,radius:12,color:'accent'}]")), finish("The Moon moves around Earth."))
        generate(provider, CourseSlideKind.EXPLANATION)
        assertTrue(provider.feedback(1), provider.feedback(1).startsWith("write_app: REJECTED"))
        assertTrue(provider.feedback(2), provider.feedback(2).startsWith("write_app: OK"))
    }

    @Test fun languageHintIsNotForced(): Unit = runBlocking {
        val text = "El pasado describe acciones terminadas en un momento concreto."
        val provider = AgentScriptProvider(writeAppReply(slide(text).put("title", "El pasado").put("language", "es")), finish(text))
        val result = generate(provider, CourseSlideKind.EXPLANATION, languageHint = "ru")
        assertEquals("es", result.preview.spec.appLanguage)
    }

    @Test fun cancelStopsTheLoop(): Unit = runBlocking {
        var calls = 0
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                calls++
                session.cancel()
                emit(CloudEvent.Text(writeAppReply(slide("Text"))))
            }
        }
        val failure = runCatching { generate(provider, CourseSlideKind.EXPLANATION) }.exceptionOrNull()
        assertEquals("cancelled", (failure as CloudFailure).code)
        assertEquals(1, calls)
    }

    @Test fun illustrationNeedsPictures(): Unit = runBlocking {
        val text = "The past tense describes finished actions."
        val intent = JSONObject().put("slot", "scene").put("prompt", "A calendar with a past day circled")
            .put("alt", "A calendar").put("aspect", "3:2")
        val off = AgentScriptProvider(writeAppReply(slide(text)), finish(text, illustration = intent), finish(text))
        assertNull(generate(off, CourseSlideKind.EXPLANATION).illustration)
        assertTrue(off.feedback(2), off.feedback(2).startsWith("finish: REJECTED"))

        val on = AgentScriptProvider(writeAppReply(slide(text)), finish(text, illustration = intent))
        val illustration = generate(on, CourseSlideKind.EXPLANATION, images = pictures(true)).illustration
        assertEquals("scene", illustration?.slot)
        assertEquals("A calendar", illustration?.alt)
    }

    /** Pictures on needs a saved key. Each call uses its own settings file, so no real key takes part. */
    private fun pictures(enabled: Boolean): ImageGeneration {
        val settings = ImageSettings(context, "lesson-agent-${UUID.randomUUID()}")
        if (enabled) {
            settings.saveKey("fixture-only").getOrThrow()
            settings.setEnabled(true).getOrThrow()
        }
        return ImageGeneration(context, settings, mapOf("openrouter" to ImageProvider { _, _, _ ->
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
            ProviderImage(bytes.toByteArray(), "image/png")
        }))
    }
}

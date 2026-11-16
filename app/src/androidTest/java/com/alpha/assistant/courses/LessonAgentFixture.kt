package com.alpha.assistant.courses

import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.tools.agentAppAccepted
import com.alpha.assistant.tools.agentFixtureFinish
import com.alpha.assistant.tools.agentStall
import com.alpha.assistant.tools.agentWrite
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** A scripted app agent for course slides. It writes [slide], then finishes with [sources] after the host accepts it. */
internal class LessonAgentProvider : ChatProvider {
    lateinit var slide: JSONObject
    var sources: List<String> = emptyList()
    var before: (CloudSession) -> Unit = {}
    val requests = mutableListOf<CloudRequest>()

    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        requests += request
        assertEquals(CloudGenerationStage.AGENT, request.generationStage)
        before(session)
        emit(CloudEvent.Text(request.lessonAgentReply(slide, sources)))
    }
}

/** Writes [slide] in the first step and finishes after the host accepts it. After a rejection the agent stalls. */
internal fun CloudRequest.lessonAgentReply(slide: JSONObject, sources: List<String> = emptyList()): String = when {
    agentAppAccepted -> JSONObject(agentFixtureFinish).apply { if (sources.isNotEmpty()) put("sources", JSONArray(sources)) }.toString()
    turns.size == 1 -> agentWrite(slide.toString())
    else -> agentStall
}

/**
 * A slide of [kind] that keeps the course contract. CHOICE options are "before" (a) and "after" (b), and b is correct.
 * FILL_IN accepts "after". ORDER expects b, then a. [change] edits the exercise before it is saved in the state.
 */
internal fun lessonSlide(
    kind: CourseSlideKind, question: String = "Past tense", language: String = "en", change: (JSONObject) -> Unit = {},
): JSONObject {
    val options = JSONArray().put(JSONObject().put("id", "a").put("label", "before"))
        .put(JSONObject().put("id", "b").put("label", "after"))
    val exercise = when (kind) {
        CourseSlideKind.CHOICE -> JSONObject().put("options", options).put("correct", "b")
        CourseSlideKind.FILL_IN -> JSONObject().put("accepted", JSONArray().put("after"))
        CourseSlideKind.ORDER -> JSONObject().put("options", options).put("correctOrder", JSONArray().put("b").put("a"))
        else -> null
    }?.put("kind", kind.name)?.put("question", question)?.also(change)
    val schema = JSONObject().put("step", "number")
    val state = JSONObject().put("step", 0)
    exercise?.let { schema.put(EXERCISE_FIELD, "string"); state.put(EXERCISE_FIELD, it.toString()) }
    return JSONObject().put("title", question).put("description", "").put("language", language)
        .put("stateSchema", schema).put("initialState", state)
        .put("ui", JSONObject().put("type", "text").put("text", question))
        .put("source", "return {state:input.state};")
}

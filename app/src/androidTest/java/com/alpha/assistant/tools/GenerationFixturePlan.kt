package com.alpha.assistant.tools

import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import org.json.JSONArray
import org.json.JSONObject

private val uncheckedBehavior = """{"status":"unchecked","reason":"unsupported_observation"}"""

internal fun String.withUncheckedBehavior(): String =
    JSONObject(this).apply { if (!has("behavior")) put("behavior", JSONObject(uncheckedBehavior)) }.toString()

internal fun firstSelectorChoices(body: String): String {
    val questions = JSONObject(body).getJSONArray("questions")
    val answers = (0 until questions.length()).map { index ->
        val question = questions.getJSONObject(index)
        val probabilities = JSONObject()
        question.getJSONObject("choices").keys().forEach { probabilities.put(it, if (it == "A") 1 else 0) }
        JSONObject().put("id", question.getString("id")).put("choice", "A")
            .put("confidence", 1).put("probabilities", probabilities)
    }
    return JSONObject().put("answers", JSONArray(answers)).toString()
}

internal val CloudRequest.isFixtureRevisionPlan: Boolean
    get() = generationStage == CloudGenerationStage.PLAN &&
        systemInstructions.startsWith("Plan the requested revision.")

private const val fixtureNeeds = """{"title":"Fixture","description":"Local test fixture","language":"en","inputs":[],"constants":[],"needs":[{"id":"custom","type":"other","label":"Fixture behavior","description":"Implement the complete behavior in the request."}],"questions":[]}"""

// A single unsupported need makes production request a full definition from the fixture.
internal suspend fun CloudRequest.replyWithFixturePlan(emit: suspend (CloudEvent) -> Unit): Boolean {
    if (generationStage == CloudGenerationStage.SELECTOR) error("The fixture plan must not require part selection")
    if (generationStage != CloudGenerationStage.PLAN) return false
    check(jsonObject && !search && systemInstructions.contains("top-level \"behavior\"") && selectedText.isEmpty())
    val needs = if (isFixtureRevisionPlan) "{}" else fixtureNeeds.let {
        if ("For a parts plan set language to ru." in systemInstructions) {
            JSONObject(it).put("language", "ru").toString()
        } else it
    }
    emit(CloudEvent.Text(needs.withUncheckedBehavior()))
    return true
}

/** Adapt an existing full-output fixture to the writer repair wire format. */
internal fun writerFixturePatch(before: String, after: String): String {
    val original = JSONObject(before)
    val corrected = JSONObject(after)
    val fields = JSONObject()
    val removals = JSONArray()
    val patch = JSONObject()
    original.keys().forEach { key -> if (!corrected.has(key)) removals.put(key) }
    corrected.keys().forEach { key ->
        val value = corrected.get(key)
        if (original.opt(key)?.toString() != value.toString()) {
            when (key) {
                "behaviorBindings" -> patch.put(key, value)
                "source" -> patch.put("sourceEdits", JSONArray().put(JSONObject()
                    .put("search", original.getString(key)).put("replace", value)))
                else -> fields.put(key, value)
            }
        }
    }
    if (fields.length() > 0) patch.put("fields", fields)
    if (removals.length() > 0) patch.put("removeFields", removals)
    return patch.toString()
}

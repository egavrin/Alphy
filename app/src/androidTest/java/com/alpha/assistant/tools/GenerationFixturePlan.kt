package com.alpha.assistant.tools

import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import org.json.JSONArray
import org.json.JSONObject

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

private const val fixtureNeeds = """{"title":"Fixture","description":"Local test fixture","language":"en","inputs":[],"constants":[],"needs":[{"id":"custom","type":"other","label":"Fixture behavior","description":"Implement the complete behavior in the request."}],"questions":[]}"""

// A single unsupported need makes production request a full definition from the fixture.
internal suspend fun CloudRequest.replyWithFixturePlan(emit: suspend (CloudEvent) -> Unit, scene: JSONObject? = null): Boolean {
    if (generationStage == CloudGenerationStage.SELECTOR) error("The fixture plan must not require part selection")
    if (generationStage != CloudGenerationStage.PLAN) return false
    check(jsonObject && !search && !systemInstructions.contains("top-level \"behavior\"") && selectedText.isEmpty())
    emit(CloudEvent.Text(if (scene == null) fixtureNeeds else JSONObject(fixtureNeeds).put("scene", scene).toString()))
    return true
}

internal fun fixtureScene(placement: String = "stage", moving: Boolean = false, start: Boolean = false): JSONObject {
    val slots = JSONObject("""{"drawing":{"kind":"geometry"}}""")
    val steps = JSONArray()
    if (moving) {
        slots.put("surface", JSONObject("""{"kind":"surface"}"""))
        if (start) steps.put(JSONObject("""{"op":"surface","slot":"surface","event":"start"}"""))
        steps.put(JSONObject("""{"op":"frames","slot":"surface","count":4,"observe":"drawing","changes":3}"""))
    } else steps.put(JSONObject("""{"op":"assert","slot":"drawing","test":"nonempty"}"""))
    val scenario = JSONObject().put("slots", slots).put("steps", steps)
    return JSONObject().put("placement", placement).put("brief", "Show visible drawing.")
        .put("checks", JSONObject().put("status", "checkable").put("scenario", scenario))
}

internal fun JSONObject.withFixtureDrawingBinding(surface: String, moving: Boolean = false): JSONObject {
    val bindings = JSONObject().put("drawing", JSONObject().put("surface", surface).put("path", JSONArray()))
    if (moving) bindings.put("surface", surface)
    return put("behaviorBindings", bindings)
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

internal val CloudRequest.expectsSavedPatch: Boolean
    get() = generationStage == CloudGenerationStage.REFINE &&
        turns.last().text.contains("Return one JSON patch object only")

internal fun CloudRequest.withFixtureLanguage(patch: String, language: String? = null, languageChange: String? = null): String {
    val input = turns.first().text
    if (!turns.last().text.contains("Include appLanguage at the top level")) return patch
    val context = JSONObject(input.substringAfter("\nInterface language context: ")
        .substringBefore("\nCurrent tool definition (data): "))
    val selected = language ?: context.optString("savedLanguage", "en").takeUnless { it == "null" } ?: "en"
    return JSONObject(patch).apply {
        if (!has("appLanguage") || language != null) put("appLanguage", selected)
        languageChange?.let { put("languageChange", it) }
    }.toString()
}

internal fun CloudRequest.patchSavedFixture(definition: String, language: String? = null, languageChange: String? = null): String {
    if (!expectsSavedPatch) return withFixtureLanguage(definition, language, languageChange)
    val current = JSONObject(turns.first().text.substringAfter("\nCurrent tool definition (data): "))
    val revised = JSONObject(definition)
    val patch = if (current.has("fill")) writerFixturePatch(current.getJSONObject("fill").toString(),
        revised.getJSONObject("fill").toString()) else writerFixturePatch(current.toString(), definition)
    return withFixtureLanguage(patch, language, languageChange)
}

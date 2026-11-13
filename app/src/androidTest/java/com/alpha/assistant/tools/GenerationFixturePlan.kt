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
internal suspend fun CloudRequest.replyWithFixturePlan(
    emit: suspend (CloudEvent) -> Unit, scene: JSONObject? = null, requiredCapabilities: JSONArray? = null,
): Boolean {
    if (generationStage == CloudGenerationStage.SELECTOR) error("The fixture plan must not require part selection")
    if (generationStage != CloudGenerationStage.PLAN) return false
    check(jsonObject && !search && selectedText.isEmpty())
    val raw = if (systemInstructions.startsWith("Plan required Android capabilities")) """{"requiredCapabilities":[]}"""
        else if (scene == null) fixtureNeeds else JSONObject(fixtureNeeds).put("scene", scene).toString()
    val reply = if (requiredCapabilities == null) raw
        else JSONObject(raw).put("requiredCapabilities", requiredCapabilities).toString()
    emit(CloudEvent.Text(withFixtureCheckEvidence(reply)))
    return true
}

internal fun CloudRequest.withFixtureCapabilityPlan(raw: String): String {
    if (generationStage != CloudGenerationStage.PLAN || !systemInstructions.contains("Include requiredCapabilities as an array")) return raw
    val plan = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    if (plan.has("fields") || plan.has("removeFields") || plan.has("requiredCapabilities")) return raw
    return plan.put("requiredCapabilities", JSONArray()).toString()
}

internal fun CloudRequest.withFixtureCheckEvidence(original: String, scope: String = "whole"): String {
    val raw = withFixtureCapabilityPlan(original)
    if (generationStage != CloudGenerationStage.PLAN) return raw
    val plan = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    val envelope = plan.optJSONObject("fields") ?: plan
    val scene = envelope.optJSONObject("scene")
    val stagedApp = systemInstructions.contains("Plan the functional core first.")
    if (scene == null) {
        if (!stagedApp || envelope !== plan) return raw
        if (envelope.has("runtime") || envelope.has("behavior") ||
            !envelope.has("needs") && envelope.opt("wholeScreen") != true) return raw
        // These fixture plans test another generation contract. Full checks have their own workflow fixtures.
        envelope.put("behavior", JSONObject("""{"status":"unchecked","reason":"unsupported_observation"}"""))
        return plan.toString()
    }
    val scenario = scene.getJSONObject("checks").getJSONObject("scenario")
    val steps = scenario.getJSONArray("steps")
    val slots = scenario.getJSONObject("slots")
    if (stagedApp && !scene.has("outcome")) {
        val animated = (0 until steps.length()).any { steps.getJSONObject(it).optString("op") == "frames" }
        scene.put("outcome", JSONObject().put("kind", if (animated) "continuous" else "static"))
    }
    if (scene.has("checkEvidence")) return plan.toString()
    val input = turns.first().text
    val excerpt = runCatching { JSONObject(input).getString("request") }.getOrDefault(input).take(600)
    val rows = JSONArray()
    for (index in 0 until steps.length()) {
        val step = steps.getJSONObject(index)
        val slot = when (step.getString("op")) {
            "assert" -> step.getString("slot")
            "complete" -> step.getString("observe")
            "frames" -> step.optString("observe").takeIf { it.isNotEmpty() } ?: continue
            else -> continue
        }
        val row = JSONObject().put("step", index + 1).put("excerpt", excerpt)
            .put("purpose", when {
                step.getString("op") == "frames" -> "motion"
                step.optString("test") == "unchanged" -> "stability"
                else -> "outcome"
            })
        if (slots.getJSONObject(slot).getString("kind") == "geometry") row.put("scope", scope)
        rows.put(row)
    }
    scene.put("checkEvidence", rows)
    return plan.toString()
}

internal fun nativeFixtureBehavior(slots: String, steps: String): JSONObject =
    JSONObject("""{"status":"checkable","scenario":{"slots":{$slots},"steps":[$steps]}}""")

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

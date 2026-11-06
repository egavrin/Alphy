package com.alpha.assistant.tools

import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import org.json.JSONObject

private val uncheckedBehavior = """{"status":"unchecked","reason":"unsupported_observation"}"""

internal fun String.withUncheckedBehavior(): String =
    JSONObject(this).apply { if (!has("behavior")) put("behavior", JSONObject(uncheckedBehavior)) }.toString()

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

package com.alpha.assistant.tools

import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.tools.generation.NeedsPlan

// A single unsupported need makes production request a full definition from the fixture.
internal suspend fun CloudRequest.replyWithFixturePlan(emit: suspend (CloudEvent) -> Unit): Boolean {
    if (generationStage == CloudGenerationStage.SELECTOR) error("The fixture plan must not require part selection")
    if (generationStage != CloudGenerationStage.PLAN) return false
    check(jsonObject && !search && systemInstructions.startsWith(NeedsPlan.prompt) && selectedText.isEmpty())
    val language = if ("For a parts plan set language to ru." in systemInstructions) "ru" else "en"
    emit(CloudEvent.Text("""{"title":"Fixture","description":"Local test fixture","language":"$language","inputs":[],"constants":[],"needs":[{"id":"custom","type":"other","label":"Fixture behavior","description":"Implement the complete behavior in the request."}],"questions":[]}"""))
    return true
}

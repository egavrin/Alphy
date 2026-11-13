package com.alpha.assistant.tools

import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.generation.NeedsPlan
import com.alpha.assistant.tools.generation.PartsContractFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPlanWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val itinerary = "Build a walk. Create owned calendar events, delete this app's events, open the calendar, and route to the next stop."
    private val requirements = linkedMapOf(
        "calendar.createOwned" to "Create owned calendar events",
        "calendar.deleteAllOwned" to "delete this app's events",
        "calendar.open" to "open the calendar",
        "maps.route" to "route to the next stop",
    )
    private val complete = requirements.keys + setOf("calendar.select", "maps.markReached")

    private fun plan(rows: Map<String, String>) = JSONObject("""{"wholeScreen":true,"language":"en",
        "behavior":{"status":"unchecked","reason":"external_outcome"}}""").put("requiredCapabilities",
        JSONArray(rows.map { (id, excerpt) -> JSONObject().put("id", id).put("excerpt", excerpt) }))

    @Test fun planRequiresGroundedUniqueMiniAppOperations() {
        NeedsPlan.parseResponse(plan(requirements).toString(), null, emptySet(), itinerary)
        for (invalid in listOf(
            plan(requirements).apply { remove("requiredCapabilities") },
            plan(requirements).apply { getJSONArray("requiredCapabilities").put(getJSONArray("requiredCapabilities").getJSONObject(0)) },
            plan(mapOf("calendar.createOwned" to "invented excerpt")),
            plan(mapOf("calendar.createOwned" to "Create  owned calendar events")),
            plan(mapOf("clock.setAlarm" to "open the calendar")),
        )) {
            assertTrue(runCatching { NeedsPlan.parseResponse(invalid.toString(), null, emptySet(), itinerary) }
                .exceptionOrNull() is PartsContractFailure)
        }
    }

    @Test fun invalidNativeReferenceReportsTheArgumentBoundary() {
        val candidate = writer()
        candidate.getJSONObject("ui").getJSONArray("children").getJSONObject(0)
            .getJSONObject("args").put("calendarRef", "pending")
        val failure = runCatching { ToolSpec.parse(candidate.toString()) }.exceptionOrNull() as ToolValidationFailure
        val diagnostic = checkNotNull(failure.uiDiagnostic)
        assertEquals("native_capability_arguments", diagnostic.rule)
        assertEquals("$.ui.children[0].args", diagnostic.path)
        assertTrue(diagnostic.correction.contains("matching native receipt"))
    }

    @Test fun itineraryRequiresAllSixOperationsAcrossWriterAndDiscovery() = runBlocking {
        val stages = mutableListOf<CloudGenerationStage?>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                stages += request.generationStage
                val raw = when (request.generationStage) {
                    CloudGenerationStage.PLAN -> plan(requirements).toString()
                    CloudGenerationStage.COMPLETE -> {
                        assertTrue(complete.all(request.systemInstructions::contains))
                        val discovery = checkNotNull(request.capabilityDiscovery).invoke()
                        val eligible = discovery.getJSONArray("eligible")
                        val allowed = (0 until eligible.length()).map(eligible::getString).toSet()
                        assertTrue(allowed.containsAll(complete))
                        val inventory = discovery.getJSONArray("implemented")
                        val callable = (0 until inventory.length()).map(inventory::getJSONObject)
                            .filter { it.getBoolean("callable") }.map { it.getString("id") }.toSet()
                        assertEquals(allowed, callable)
                        if (stages.count { it == CloudGenerationStage.COMPLETE } == 1) {
                            writer().put("capabilities", JSONArray(complete - "maps.markReached")).toString()
                        } else {
                            assertTrue(request.turns.last().text.contains("maps.markReached"))
                            JSONObject().put("fields", JSONObject().put("capabilities", JSONArray(complete))).toString()
                        }
                    }
                    else -> error("Unexpected stage ${request.generationStage}")
                }
                emit(CloudEvent.Text(raw))
            }
        }
        val result = ToolGenerationService(provider, SandboxClient(context)).generate(
            "offline", itinerary, null, emptyList(), CloudSession(), "capability-plan",
        )
        assertEquals(complete, result.spec.capabilities)
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.COMPLETE, CloudGenerationStage.COMPLETE), stages)
    }

    @Test fun savedRefinementPlansCapabilitiesBeforeWriterAndKeepsStoredState() = runBlocking {
        val original = ToolSpec.parse("""{"formatVersion":3,"title":"Fixture","description":"Local notes","capabilities":[],
            "stateSchema":{"note":"string"},"initialState":{"note":""},"ui":{"type":"text","text":"Note","bind":"note"},
            "source":"return {state:input.state};"}""")
        val saved = ToolRecord("saved", 1, 1, original.json, """{"note":"keep this"}""", original.ui.toString(), null, true)
        val stages = mutableListOf<CloudGenerationStage?>()
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                stages += request.generationStage
                emit(CloudEvent.Text(when (request.generationStage) {
                    CloudGenerationStage.PLAN -> """{"requiredCapabilities":[]}"""
                    CloudGenerationStage.REFINE -> """{"fields":{"description":"Saved local notes"},"appLanguage":"en"}"""
                    else -> error("Unexpected stage ${request.generationStage}")
                }))
            }
        }
        val result = ToolGenerationService(provider, SandboxClient(context)).generate(
            "offline", "Describe this app as saved local notes", saved, emptyList(), CloudSession(), saved.id,
        )
        assertEquals(listOf(CloudGenerationStage.PLAN, CloudGenerationStage.REFINE), stages)
        assertEquals("keep this", JSONObject(result.state).getString("note"))
    }

    private fun writer(): JSONObject {
        val event = JSONObject("""{"version":1,"title":"Stop","start":1893456000000,"end":1893459600000,
            "timeZone":"UTC","deviceTimeZone":"UTC","reminderMinutes":0,"eventKey":"stop"}""")
            .put("calendarRef", "c".repeat(64))
        val stop = JSONObject("""{"version":1,"stopId":"stop","label":"Stop","latE6":1000000,"lonE6":2000000,"coordinateSystem":"wgs84"}""")
        val arguments = mapOf("calendar.createOwned" to event, "maps.markReached" to stop,
            "maps.route" to JSONObject(stop.toString()).put("mode", "walking").put("reachedRef", "d".repeat(64)))
        val controls = complete.mapIndexed { index, id ->
            JSONObject().put("type", if (id.startsWith("calendar.")) "calendar" else "navigation")
                .put("action", "operation$index").put("capability", id)
                .put("args", arguments[id] ?: JSONObject().put("version", 1))
        }
        return JSONObject("""{"formatVersion":3,"title":"Walking itinerary","description":"Plan a walk",
            "stateSchema":{"ready":"boolean"},"initialState":{"ready":true}}""")
            .put("capabilities", JSONArray(complete)).put("ui", JSONObject().put("type", "column").put("children", JSONArray(controls)))
            .put("source", """const s=input.state,e=input.event,c=e.calendar||e.navigation;
                return c ? {state:s,effects:[{id:'selected',capability:c.capability,args:c.args}]} : {state:s};""")
    }
}

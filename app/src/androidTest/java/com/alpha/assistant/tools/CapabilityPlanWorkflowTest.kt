package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPlanWorkflowTest {
    private val requirements = linkedMapOf(
        "calendar.createOwned" to "Create owned calendar events",
        "calendar.deleteAllOwned" to "delete this app's events",
        "calendar.open" to "open the calendar",
        "maps.route" to "route to the next stop",
    )
    private val complete = requirements.keys + setOf("calendar.select", "maps.markReached")

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

package com.alpha.assistant.tools.generation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class NativeBehaviorCatalogWorkflowTest {
    @Test fun everyNativeAppFamilyExposesItsObservableTargets() {
        fun plan(needs: String, constants: String = "[]", inputs: String = "[]") = NeedsPlan.parse(
            """{"title":"Catalog","description":"","language":"en","inputs":$inputs,
                "needs":[$needs],"constants":$constants,"questions":[]}""")
        val plans = listOf(
            plan("""{"id":"figure","type":"figure","label":"Double","description":"Double the number",
                    "expression":{"op":"multiply","args":[{"ref":"amount"},{"number":2}]},
                    "example":{"inputs":{"amount":2},"result":4}},
                {"id":"counter","type":"counter","label":"Counter","description":"Count"},
                {"id":"up","type":"stopwatch","label":"Stopwatch","description":"Measure elapsed time"},
                {"id":"down","type":"countdown","label":"Countdown","description":"Count down","uses":{"length":"seconds"}}""",
                """[{"id":"seconds","value":30}]""",
                """[{"id":"name","label":"Name","type":"text","value":"Sample"},
                    {"id":"amount","label":"Amount","type":"number","value":2},
                    {"id":"slider","label":"Slider","type":"number","value":1,"min":0,"max":4,"control":"slider"},
                    {"id":"flag","label":"Flag","type":"boolean","value":false}]"""),
            plan("""{"id":"notes","type":"entries","label":"Notes","description":"Add notes"},
                {"id":"records","type":"entries","label":"Records","description":"Add names and values",
                    "fields":[{"id":"item","label":"Item","type":"text"},{"id":"amount","label":"Amount","type":"number"}]},
                {"id":"log","type":"quick_log","label":"Log","description":"Log a fixed amount","uses":{"amount":"amount"}},
                {"id":"dual","type":"quick_log","label":"Dual","description":"Log two amounts",
                    "uses":{"amount":"amount","alternateAmount":"alternate"}},
                {"id":"ledger","type":"points_ledger","label":"Ledger","description":"Track two teams",
                    "uses":{"team1":"team1","team2":"team2","earn1":"amount","earn2":"alternate","cost":"cost",
                    "earn1Label":"quiz","earn2Label":"puzzle","spendLabel":"hint"}},
                {"id":"reading","type":"reading_status","label":"Readings","description":"Classify readings",
                    "bands":[{"label":"Low"},{"label":"High","min":10}]}""",
                """[{"id":"amount","value":3},{"id":"alternate","value":2},{"id":"cost","value":5},
                    {"id":"team1","value":"Amber"},{"id":"team2","value":"Chalk"},{"id":"quiz","value":"Quiz"},
                    {"id":"puzzle","value":"Puzzle"},{"id":"hint","value":"Hint"}]"""),
            plan("""{"id":"fixed","type":"itinerary","label":"Fixed","description":"Schedule stops",
                    "uses":{"start":"start"},"stops":[{"name":"Park","minutes":15}]},
                {"id":"editable","type":"editable_itinerary","label":"Editable","description":"Edit stops",
                    "uses":{"duration":"window"}},
                {"id":"due","type":"due_items","label":"Due","description":"Track deadlines",
                    "uses":{"window":"days"},"items":[{"name":"Check filter","offsetDays":0}]},
                {"id":"ideas","type":"idea_deck","label":"Ideas","description":"Pick a break",
                    "ideas":[{"name":"Walk","minutes":5,"effort":"low"}]},
                {"id":"text","type":"content","label":"Read","description":"Display text","uses":{"body":"body"}},
                {"id":"choice","type":"choice_exercise","label":"Choose","description":"Pick one answer",
                    "uses":{"option1":"yes","option2":"no","correct":"correct"}}""",
                """[{"id":"start","value":540},{"id":"window","value":60},{"id":"days","value":3},
                    {"id":"body","value":"A caption"},{"id":"yes","value":"Yes"},{"id":"no","value":"No"},{"id":"correct","value":1}]""")
        )
        val expected = mapOf(
            "name" to setOf("value"), "amount" to setOf("value"), "slider" to setOf("value"), "flag" to setOf("value"),
            "figure" to setOf("value"), "counter" to setOf("value"), "up" to setOf("value"), "down" to setOf("value", "complete"),
            "notes" to setOf("records"), "records" to setOf("records"),
            "log" to setOf("today", "count", "yesterday", "history"), "dual" to setOf("today", "count", "yesterday", "history"),
            "ledger" to setOf("balance1", "balance2", "history", "denied"),
            "reading" to setOf("latest", "status", "history"),
            "fixed" to setOf("end", "stops"), "editable" to setOf("end", "remaining", "stops"),
            "due" to setOf("overdue", "upcoming", "items"), "ideas" to setOf("available", "completed", "ideas"),
            "text" to setOf("body"), "choice" to setOf("correct", "incorrect")
        )
        val clock = PartClockSample(LocalDate.of(2030, 1, 1), 540)
        val requests = plans.flatMap { ToolParts.requests(it, clock = clock) }
        assertEquals(expected.keys, requests.map(PartRequest::id).toSet())
        for (request in requests) {
            val part = request.candidates.single()
            val targets = part.behaviorTargets(request.id)
            val instance = partInstanceId(request.id)
            assertEquals(request.id, part.fragment.handlers.keys.map { it.removePrefix("${instance}_") }.toSet(),
                targets.actions.keys)
            assertEquals(request.id, expected.getValue(request.id), targets.observations.keys.map { it.member }.toSet())
            for ((target, path) in targets.observations) {
                val node = path.fold(part.fragment.full) { parent, index -> parent.getJSONArray("children").getJSONObject(index) }
                assertNotNull("${request.id}.${target.member}", behaviorUiObservation(node, part.fragment.initial, target.kind, native = true))
            }
        }
    }
}

package com.alpha.assistant.cloud

import com.alpha.assistant.data.MessageProvenance
import com.alpha.assistant.tools.ShownControl
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.liveChatModel
import com.alpha.assistant.tools.liveCheck
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * The paid probe of the routing family (stage 5 live check, L2): each case is one spoken request with the stage that the live check saw, sent
 * through the product path (`CloudChatProvider` with the routing decision of a voice screen) to the chat model of the account. Each case runs
 * [RUNS] times, and the probe stops before a run when [CAP] chat calls are spent. The result is a table of the expected and the got decision.
 * It records and does not judge: the controller reads the table. `run.py live-check` starts each method with the one-use account.
 */
class LiveRoutingProbeTest {
    @Test fun allCases() = check(CASES)
    @Test fun tableOfScene() = check(CASES.filter { it.name == "tableOfScene" })
    @Test fun chartOfScene() = check(CASES.filter { it.name == "chartOfScene" })
    @Test fun largerCircles() = check(CASES.filter { it.name == "largerCircles" })
    @Test fun compareEmpty() = check(CASES.filter { it.name == "compareEmpty" })
    @Test fun heartEmpty() = check(CASES.filter { it.name == "heartEmpty" })
    @Test fun nextStep() = check(CASES.filter { it.name == "nextStep" })

    /** [kinds] are the kinds that pass, and [family] the family that passes, or null when the family does not count. */
    private class Case(val name: String, val user: String, val screen: ShownScreen?, val kinds: Set<String>, val family: String?,
        val context: List<CloudTurn> = emptyList())

    private fun check(cases: List<Case>) = liveCheck("LiveRoutingProbeTest", missing = { null }, model = { it.liveChatModel() }) { account, model ->
        var calls = 0
        val provider = CloudChatProvider({ account }, Endpoints.PRODUCTION, chatModel = { checkNotNull(model) }, onHttpCall = { calls++ })
        val rows = JSONArray()
        for (case in cases) repeat(RUNS) { run ->
            val row = JSONObject().put("case", case.name).put("run", run + 1)
                .put("expected", "${case.kinds.joinToString("|")} family=${case.family ?: "any"}")
            rows.put(row)
            // A run can take a correction call, so the probe stops one call before the cap.
            if (calls >= CAP - 1) { row.put("got", JSONObject.NULL).put("pass", JSONObject.NULL).put("failure", "cap"); return@repeat }
            // The request of a spoken turn as ShellViewModel sends it: the earlier turns, the shown screen, and no search.
            val turn = CloudTurn("user", case.user)
            val instructions = ChatToolReply.instructions(available = true)
            val request = CloudRequest(case.context + turn, false, instructions, chatDecision = true, voiceScreen = true,
                shownScreen = case.screen?.line(), spokenContext = case.context, allowChatImage = true, allowDevicePlan = true, allowMemoryFact = true,
                decisionContext = ChatDecisionContext(turn, case.context, instructions, MessageProvenance.CLEAN))
            val events = mutableListOf<CloudEvent>()
            val failure = runCatching { runBlocking { provider.respond(account.key, request, CloudSession()) { events += it } } }.exceptionOrNull()
            val decision = (events.lastOrNull { it is CloudEvent.RoundComplete } as? CloudEvent.RoundComplete)?.let {
                runCatching { ChatToolReply.decode(it.text, allowScreen = true, allowPress = case.screen != null) }.getOrNull()
            }
            row.put("got", decision?.let { "${it.kind} family=${it.family}" } ?: JSONObject.NULL)
                .put("brief", decision?.brief ?: JSONObject.NULL).put("text", decision?.text ?: JSONObject.NULL)
                .put("pass", decision != null && decision.kind in case.kinds && (case.family == null || decision.family == case.family))
                .put("failure", (failure as? CloudFailure)?.code ?: failure?.javaClass?.simpleName ?: JSONObject.NULL)
        }
        JSONObject().put("httpCalls", calls).put("rows", rows).toString()
    }

    private companion object {
        const val RUNS = 2
        const val CAP = 24
        /** The explain scene of the live check (evidence/one-scene/live-5/live-5-uxData, step 1): Earth and Mars as two circles and a choice. */
        val PLANETS_SCENE = """
            scene("Земля и Марс", lang="ru", palette="blue")
            control = choice("Сравнить", ["Размер", "Атмосфера", "Поверхность"], "Размер")
            s1 = text(75, 25, "Земля", "ink", 16)
            s2 = text(225, 25, "Марс", "ink", 16)
            s3 = circle(75, 90, 48, "water")
            s4 = circle(225, 90, 26, "land")
            s5 = text(75, 158, ${'$'}/v == "Размер" ? "12 742 км" : ${'$'}/v == "Атмосфера" ? "Плотная" : "Океаны и суша", "ink", 13)
            s6 = text(225, 158, ${'$'}/v == "Размер" ? "6 779 км" : ${'$'}/v == "Атмосфера" ? "Разреженная" : "Сухая и каменистая", "ink", 13)
            s7 = text(150, 195, ${'$'}/v == "Размер" ? "Диаметр планет" : ${'$'}/v == "Атмосфера" ? "Атмосфера" : "Поверхность", "ink", 14)
            card = card("Два разных мира", ${'$'}/v == "Размер" ? "Земля почти вдвое шире Марса." : ${'$'}/v == "Атмосфера" ? "На Земле много азота и кислорода. На Марсе атмосфера тонкая и состоит в основном из углекислого газа." : "На Земле есть жидкая вода на поверхности. Марс сегодня холодный и сухой.")
            say = "Выбери характеристику и сравни Землю с Марсом."
        """.trimIndent()
        val SCENE get() = ShownScreen("scene", "Земля и Марс", PLANETS_SCENE,
            listOf("Размер", "Атмосфера", "Поверхность").map { ShownControl("Сравнить: $it", "v_field", it) }, listOf("Сравнить" to "Размер"))
        val PLANETS_TALK = listOf(CloudTurn("user", "Сравни Землю и Марс"), CloudTurn("assistant", "Выбери характеристику и сравни Землю с Марсом."))
        val CASES get() = listOf(
            Case("tableOfScene", "Покажи планеты таблицей", SCENE, setOf("refine", "screen"), "data", PLANETS_TALK),
            Case("chartOfScene", "Покажи это графиком", SCENE, setOf("refine", "screen"), "data", PLANETS_TALK),
            Case("largerCircles", "Сделай круги больше", SCENE, setOf("refine"), "explain", PLANETS_TALK),
            Case("compareEmpty", "Сравни Землю и Марс", null, setOf("screen"), "data"),
            Case("heartEmpty", "Объясни, как работает сердце", null, setOf("screen"), "explain"),
            Case("nextStep", "Дальше", STEPS_SCENE, setOf("press"), null, SHIP_TALK),
        )
    }
}

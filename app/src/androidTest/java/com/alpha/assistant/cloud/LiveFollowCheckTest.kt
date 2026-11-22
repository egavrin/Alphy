package com.alpha.assistant.cloud

import com.alpha.assistant.tools.SceneStep
import com.alpha.assistant.tools.ShownControl
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.liveChatModel
import com.alpha.assistant.tools.liveCheck
import com.alpha.assistant.ui.CONTINUE_LABEL
import com.alpha.assistant.ui.NAIL
import com.alpha.assistant.ui.NARRATION_LINES
import com.alpha.assistant.ui.STEPPED
import com.alpha.assistant.ui.STEPS
import com.alpha.assistant.ui.STEP_CONTROL
import com.alpha.assistant.ui.shipTemplate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** The talk before the ship scene, for the follow check and the routing probe. */
internal val SHIP_TALK = listOf(CloudTurn("user", "Почему корабль не тонет?"), CloudTurn("assistant", "Смотри на экран: корабль полый и вытесняет много воды."))
/** The stepped ship scene at step 1, with the continue control, as a follow call or a routing call reads it. */
internal val STEPS_SCENE get() = ShownScreen("scene", "Почему корабль не тонет?", STEPPED + "\n" + NARRATION_LINES.joinToString("\n"),
    STEPS.map { ShownControl("$STEP_CONTROL: $it", "v_field", it) } + ShownControl(CONTINUE_LABEL, ShownScreen.CONTINUE_ACTION, null),
    listOf(STEP_CONTROL to STEPS[0]), SceneStep(1, STEPS.size, STEPS[0]), stepControl = STEP_CONTROL, steps = STEPS)

/**
 * The paid check of the follow instructions (plan "one scene", stage 3 fix list item 8): each case is the request of `followTurn` for one
 * voice-only turn, sent through the product path (`CloudChatProvider` with `follow = true`) to the chat model of the account. Each case runs
 * [RUNS] times. The result is a table of the expected and the got decision. It records and does not judge: the controller reads the table.
 * `run.py live-check` starts each method with the one-use account.
 */
class LiveFollowCheckTest {
    @Test fun allCases() = check(CASES)
    @Test fun smallTalk() = check(CASES.filter { it.name == "smallTalk" })
    @Test fun screenAnswered() = check(CASES.filter { it.name == "screenAnswered" })
    @Test fun screenWaiting() = check(CASES.filter { it.name == "screenWaiting" })
    @Test fun screenCut() = check(CASES.filter { it.name == "screenCut" })
    /** Block 2 plan, V2: the new case with the two cases that the new sentence of the instructions can change. */
    @Test fun followStart() = check(CASES.filter { it.name in setOf("screenCut", "screenAnswered", "screenWaiting") })
    @Test fun sceneAnswers() = check(CASES.filter { it.name == "sceneAnswers" })
    @Test fun markOption() = check(CASES.filter { it.name == "markOption" })
    @Test fun pressOption() = check(CASES.filter { it.name == "pressOption" })
    @Test fun continueSteps() = check(CASES.filter { it.name == "continueSteps" })
    @Test fun clearSubject() = check(CASES.filter { it.name == "clearSubject" })
    /** Stage 5 live check, L1: a voice-only comparison is a screen of the family data. */
    @Test fun compareData() = check(CASES.filter { it.name == "compareData" })

    private class Case(val name: String, val user: String, val said: String, val screen: ShownScreen?, val kind: String, val target: String,
        val answered: Boolean, val context: List<CloudTurn> = emptyList(), val family: String = "explain")

    private fun check(cases: List<Case>) = liveCheck("LiveFollowCheckTest", missing = { null }, model = { it.liveChatModel() }) { account, model ->
        var calls = 0
        val provider = CloudChatProvider({ account }, Endpoints.PRODUCTION, chatModel = { checkNotNull(model) }, onHttpCall = { calls++ })
        val rows = JSONArray()
        for (case in cases) repeat(RUNS) { run ->
            val request = CloudRequest(listOf(CloudTurn("user", case.user), CloudTurn("assistant", case.said)), false, "",
                shownScreen = case.screen?.line(), spokenContext = case.context, follow = true)
            var follow: CloudEvent.Follow? = null
            val failure = runCatching { runBlocking { provider.respond(account.key, request, CloudSession()) { if (it is CloudEvent.Follow) follow = it } } }
                .exceptionOrNull()
            val got = follow
            val decoded = got?.let { FollowDecision.decode(it, case.screen?.controls?.map { control -> control.label }.orEmpty()) }
            rows.put(JSONObject().put("case", case.name).put("run", run + 1)
                .put("expected", "${case.kind} ${case.target} answered=${case.answered} family=${case.family}".replace("  ", " "))
                .put("got", got?.let { "${it.kind} ${it.target} answered=${it.answered} family=${it.family}".replace("  ", " ") } ?: JSONObject.NULL)
                .put("brief", got?.brief ?: JSONObject.NULL).put("decoded", decoded?.kind ?: JSONObject.NULL)
                // The family chooses the call of a screen only, so only a screen case compares it (stage 5 live check, L1).
                .put("pass", decoded?.kind == case.kind && got?.target == case.target && got?.answered == case.answered &&
                    (case.kind != "screen" || got?.family == case.family))
                .put("failure", (failure as? CloudFailure)?.code ?: failure?.javaClass?.simpleName ?: JSONObject.NULL))
        }
        JSONObject().put("httpCalls", calls).put("rows", rows).toString()
    }

    private companion object {
        const val RUNS = 2
        /** The ship scene with a choice of two objects. */
        val CHOICE_SCENE get() = ShownScreen("scene", "Почему корабль не тонет?", shipTemplate(),
            listOf("Корабль", NAIL).map { ShownControl("$STEP_CONTROL: $it", "v_field", it) }, listOf(STEP_CONTROL to "Корабль"))
        val CASES get() = listOf(
            Case("smallTalk", "Как дела?", "Отлично, спасибо! А у тебя как?", null, "none", "", true),
            Case("screenAnswered", "Почему небо голубое?",
                "Солнечный свет рассеивается в воздухе, а синий свет рассеивается сильнее всего, поэтому небо голубое.", null, "screen", "", true),
            Case("screenWaiting", "Почему небо голубое?", "Сейчас объясню.", null, "screen", "", false),
            // Block 2 plan, V2: the call starts 2,500 ms after the voice began, so it sees a cut sentence that begins the answer.
            Case("screenCut", "Почему небо голубое?", "Солнечный свет рассеивается в воздухе, и синий свет рассеивается", null, "screen", "", true),
            Case("sceneAnswers", "Так почему корабль всё-таки не тонет?", "Он полый внутри и вытесняет много воды, поэтому вода его держит.",
                STEPS_SCENE, "none", "", true, SHIP_TALK),
            Case("markOption", "А что из этого тонет?", "Тонет гвоздь: он сплошной и тяжёлый для своего размера.", CHOICE_SCENE, "mark",
                "$STEP_CONTROL: $NAIL", true, SHIP_TALK),
            Case("pressOption", "Покажи гвоздь.", "Смотри, вот гвоздь.", CHOICE_SCENE, "press", "$STEP_CONTROL: $NAIL", true, SHIP_TALK),
            // The words only promise the walk, so answered is false. A press does not use answered.
            Case("continueSteps", "Продолжай", "Хорошо, идём дальше.", STEPS_SCENE, "press", CONTINUE_LABEL, false, SHIP_TALK),
            Case("clearSubject", "Ладно, хватит про корабли. Расскажи анекдот.",
                "Встречаются два кота, и один говорит: «Мяу». А второй отвечает: «Мяу-мяу».", STEPS_SCENE, "clear", "", true, SHIP_TALK),
            // Stage 5 live check, L1: the voice answered a comparison with no hand-off, and the follow screen is a data form.
            Case("compareData", "Сравни Землю и Марс", "Земля почти вдвое шире Марса, у неё плотная атмосфера и жидкая вода, а Марс холодный и сухой.",
                null, "screen", "", true, family = "data"),
        )
    }
}

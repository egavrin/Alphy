package com.alpha.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.CloudGenerationStage
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.data.Source
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.ShownScreen
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.send
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.INPUT
import com.alpha.assistant.voice.LiveConnection
import com.alpha.assistant.voice.LiveListener
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveTransport
import com.alpha.assistant.voice.OUTPUT
import com.alpha.assistant.voice.WavFeeder
import com.alpha.assistant.voice.wavSamples
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A recorded request: its file in the test assets `voice/` and its words. */
internal class Speech(val fixture: String, val words: String)

/**
 * One step of a dialogue as data: what the person says ([speech], none for the picture of the stage before the request), whether
 * the voice model is expected to hand it off ([handOff], reported), whether the stage must show an app after it ([screen], asserted),
 * and the expected start of the request text of the hand-off ([requestStarts], reported). [goal]: the element of the owner's talk
 * that the step holds (lane V). [touch]: the driver taps a button of the stage app [TOUCH_MS] after the end of the speech, as the
 * person who changes a value while the change runs.
 */
internal class DialogueStep(val speech: Speech?, val handOff: Boolean, val screen: Boolean, val requestStarts: String? = null,
    val goal: String? = null, val touch: Boolean = false)

/**
 * A dialogue. [maxCalls] is a hard cost stop: the chat call after it is refused before it is sent. An [adaptive] dialogue (lane V)
 * answers a question back with «Да» and a spoken failure with a bare retry, lets the full generation run, gives a step [FULL_STEP_MS],
 * and judges each step by [PASS_BAR].
 * A dialogue of [newTalks] says each request as the first turn of a new chat, with a new voice session.
 */
internal class Dialogue(val name: String, val steps: List<DialogueStep>, val maxCalls: Int = MAX_CALLS, val adaptive: Boolean = false,
    val newTalks: Boolean = false)

/** A cost guard: the early plan call and the new screen after a failed change let a working dialogue make 11 calls or more. */
private const val MAX_CALLS = 14

/**
 * The cap of each template dialogue (plan of 2026-11-21, Tasks 10b and 19), from the code: a first screen makes 2 chat calls (the
 * routing call and the early template call), a spoken change 2, a request to learn 3 (the template call refuses, then the free plan),
 * and a later screen 2. One repair in each of the 4 steps, two inserted steps of at most 3 calls, and one full generation of 4 calls:
 * 9 + 4 + 6 + 4 = 23. Six template dialogues and [NO_SCREEN_CALLS] stay inside the 160 chat calls of the live check: 6 × 23 + 20 = 158.
 */
private const val TEMPLATE_CALLS = 23

/**
 * The cap of the no-screen probe: each of its 6 first turns makes at most 3 chat calls (the routing call, the early template call, and
 * the free plan that the early plan starts after a template refusal), and 2 calls more for one repair.
 */
private const val NO_SCREEN_CALLS = 20

/**
 * The cap of each adaptive dialogue, from the code: a change makes at most 9 chat calls (the routing call, a change by lines and its
 * repair, a new screen and its repair, and up to 4 calls of the full generation, whose agent loop took 1 to 2 calls in 25 measured
 * creates). A first screen makes at most 7 (no change by lines). A search runs inside the routing call. Three steps and at most two
 * inserted steps: 7 + 4 × 9 = 43.
 */
private const val ADAPTIVE_CALLS = 43

/** At most this many inserted steps (a «Да» or a retry) in one adaptive dialogue, which [ADAPTIVE_CALLS] counts. */
private const val MAX_INSERTED = 2

/** The bare retry after a spoken failure and the short «Да» after a question back (lane V). */
private val RETRY = Speech("retry", "Попробуй ещё раз.")
private val YES = Speech("da", "Да.")

/**
 * The pass bar of an adaptive dialogue (lane V), written before its first paid run. [stepOutcome] and [judge] apply it, and each run
 * writes it to its report.
 */
internal const val PASS_BAR = "Each step ends in a changed stage (a new app, a new revision of the stage app, or a press that ran) or in " +
    "a spoken reason (the last line of Alphy in the step is a host line, not a reply, and the voice spoke after it). Each step asks to " +
    "change or to see something, so a step that ends with a plain spoken text fails. A message with the status error or a session " +
    "error on the stage fails the step. A question back passes only when the «Да» right after it passes. An element of the talk that " +
    "the model did not reach is recorded as not exercised, never as passed. A step with the goal answer asks for no screen: it passes " +
    "when it ends in a plain spoken text or a question back, and it fails when a frame of a preview stays on the stage. It gets no " +
    "inserted step."

private fun dialogue(name: String, before: Speech?, request: Speech, press: Speech, change: Speech, requestStarts: String? = null) =
    Dialogue(name, listOf(DialogueStep(before, handOff = false, screen = false), DialogueStep(request, true, true, requestStarts),
        DialogueStep(press, true, true), DialogueStep(change, true, true)))

/** A request of an adaptive dialogue: it asks to see or to change the stage. */
internal fun ask(fixture: String, words: String, goal: String, touch: Boolean = false) =
    DialogueStep(Speech(fixture, words), handOff = true, screen = true, goal = goal, touch = touch)

/**
 * A template dialogue (plan of 2026-11-21, Task 10b): a request to explain a thing, a spoken change of that screen ([changeGoal]
 * `search` asks for fresh facts), a request to learn with a check, which the template prompt refuses, and a request to explain a
 * different thing, whose screen replaces the shown one. The fixture of each step is `<name>-<goal>`, and `<name>-next` for the last.
 */
private fun template(name: String, explain: String, change: String, learn: String, next: String, changeGoal: String = "change") =
    Dialogue(name, listOf(ask("$name-explain", explain, "screen"), ask("$name-$changeGoal", change, changeGoal), ask("$name-learn", learn, "learn"),
        ask("$name-next", next, "screen")), TEMPLATE_CALLS, adaptive = true)

/** A first turn of the no-screen probe: the person wants a spoken answer, and the stage must stay empty. */
private fun answer(fixture: String, words: String) = DialogueStep(Speech(fixture, words), handOff = true, screen = false, goal = "answer")

/**
 * The three live dialogues of design part 4: «Шаг 1» the stage, «Шаг 2» the request, «Шаг 3» a press, «Шаг 4» a change. Then the
 * three adaptive dialogues of lane V on new subjects, which hold the failure classes of the owner's talk of 2026-11-20.
 */
internal val DIALOGUES = listOf(
    dialogue("heart", null, Speech("explain-heart", "Объясни, как работает сердце."), Speech("heart-press", "Покажи следующий шаг."),
        Speech("heart-change", "Подпиши камеры сердца.")),
    // The plain question gives the time to the first words with no screen and tests task Db0 live, as in form J.
    dialogue("moon", Speech("ask-largest-planet", "Какая планета самая большая в Солнечной системе?"),
        Speech("explain-moon", "Объясни, почему Луна всегда повёрнута к Земле одной стороной."), Speech("moon-press", "А если Луна не вращается?"),
        Speech("moon-change", "Покажи орбиту крупнее."), requestStarts = "Объясни"),
    dialogue("japanese", null, Speech("teach-japanese", "Научи меня считать до десяти по-японски."), Speech("japanese-press", "Дальше."),
        Speech("japanese-change", "Добавь, как произносится каждая цифра.")),
    // The kit cannot make motion that goes on by itself, so step 2 needs the full generation. Step 3 changes the moving app.
    Dialogue("pendulum", listOf(ask("pendulum-show", "Покажи, как качается маятник.", "screen"),
        ask("pendulum-motion", "Пусть маятник качается сам, без остановки.", "motion"),
        ask("pendulum-longer", "Сделай нить маятника вдвое длиннее.", "moving")), ADAPTIVE_CALLS, adaptive = true),
    Dialogue("volcano", listOf(ask("volcano-explain", "Объясни, как устроен вулкан.", "screen"),
        ask("volcano-search", "Найди свежие новости об извержении Этны и добавь их на экран.", "search"),
        ask("volcano-replace", "Замени картинку целиком на схему гейзера.", "replace")), ADAPTIVE_CALLS, adaptive = true),
    // The talk starts in English and goes on in Russian, as the owner's talk on his fold did, so this is the run at the fold size.
    Dialogue("water", listOf(ask("water-show", "Show me how the water cycle works.", "english"),
        ask("water-label", "Подпиши на картинке каждый этап.", "touch", touch = true),
        ask("water-replace", "Замени картинку целиком: покажи, как образуется облако.", "replace")), ADAPTIVE_CALLS, adaptive = true),
    // The template dialogues of the plan of 2026-11-21 on subjects of no earlier run. tpl-e is in English. The controller runs tpl-c and
    // tpl-f at the fold size. tpl-d to tpl-f are the stream dialogues of Task 19.
    template("tpl-a", "Объясни, почему магнит притягивает железо.", "Нарисуй стрелками, куда тянет магнит.",
        "Дай мне пару задачек про магниты, чтобы я себя проверил.", "А теперь объясни, откуда берётся эхо."),
    template("tpl-b", "Объясни, как образуется ураган.", "Найди свежие новости о последнем урагане и добавь их на экран.",
        "Устрой мне небольшой тест про ветры, чтобы проверить себя.", "А теперь объясни, почему гром слышно позже, чем видно молнию.",
        changeGoal = "search"),
    template("tpl-c", "Объясни, как работают передачи на велосипеде.", "Сделай заднюю звёздочку побольше.",
        "Дай мне несколько задач про передачи и проверь мои ответы.", "А теперь объясни, как устроены часы со стрелками."),
    template("tpl-d", "Объясни, как гусеница превращается в бабочку.", "Добавь, сколько дней длится каждая стадия.",
        "Задай мне вопросы про насекомых и проверь мои ответы.", "А теперь объясни, откуда у дерева годовые кольца."),
    template("tpl-e", "Explain how an airplane stays in the air.", "Add an arrow that shows the lift.",
        "Give me a few practice questions about flight and check my answers.", "Now explain how a parachute slows you down."),
    template("tpl-f", "Объясни, что такое площадь фигуры.", "Сделай поле побольше, клеток на сто.",
        "Дай мне задачки на площадь и проверь, правильно ли я решаю.", "А теперь объясни, как устроены римские цифры."),
    // The probe of Task 19: six first turns that need no screen, two greetings, two short facts, and two questions about the assistant.
    Dialogue("no-screen", listOf(answer("no-screen-hello", "Здравствуй, как настроение?"), answer("no-screen-evening", "Добрый вечер, Альфи!"),
        answer("no-screen-leap-year", "Сколько дней в високосном году?"), answer("no-screen-ocean", "Какой океан самый большой?"),
        answer("no-screen-name", "А как тебя зовут?"), answer("no-screen-skills", "Что ты умеешь делать?")), NO_SCREEN_CALLS,
        adaptive = true, newTalks = true),
).associateBy { it.name }

/** The rule of the kit for a template file (`TEMPLATE_HEAD` in kit/src/template.js), applied to its first line that is not empty. */
private val TEMPLATE_HEAD = Regex("""^\s*(scene|items|cells)\s*\(""")

/** True when [appFile] is a template file, not a free app file. */
internal fun isTemplateAppFile(appFile: String?) = appFile?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let(TEMPLATE_HEAD::containsMatchIn) == true

/**
 * The outcome of one step of an adaptive dialogue, from its structure, not from its words: `error` (a message failed, or the stage
 * showed a session error), `changed` (a new app or a new revision on the stage), `pressed` (a press ran: Alphy's last line is a reply,
 * not the host line of a failed press), `question` (the kind is clarify, Alphy's last line asks back, or the voice asked with no line
 * of Alphy), `reason` (Alphy's last line is a host line and the voice spoke after it), or `plain`. [lines]: the classes of
 * [DialogueRecorder.lineOf].
 */
internal fun stepOutcome(change: String, kind: String?, error: Boolean, lines: List<String>, spokeAfterLast: Boolean, voiceAsked: Boolean): String {
    val last = lines.lastOrNull()
    return when {
        error -> "error"
        change == "new" || change == "revision" -> "changed"
        kind == "press" && (last == "answer" || last == "question") -> "pressed"
        kind == "clarify" || last == "question" || last == null && voiceAsked -> "question"
        last == "host" && spokeAfterLast -> "reason"
        else -> "plain"
    }
}

/** The step that an adaptive dialogue inserts after an [outcome]: the bare retry after a spoken failure, «Да» after a question back. */
internal fun followUp(outcome: String): Speech? = when (outcome) {
    "error", "reason" -> RETRY
    "question" -> YES
    else -> null
}

/**
 * Puts the verdict of [PASS_BAR] (`passed` or `failed`) into each row of an adaptive dialogue. Returns, for each of [goals] and the
 * inserted `retry` and `yes`, the verdicts and steps of the rows that exercised it, or `not exercised`. A request for motion counts
 * only when the full generation ran in its step. A change while the stage moves
 * (`moving`) and a change while the person changes a value (`touch`) count only with a state commit of the stage app after the
 * hand-off and before the change, and a search only with sources.
 */
internal fun judge(rows: List<JSONObject>, goals: List<String>): JSONObject {
    fun passed(row: JSONObject?) = if (row?.optString("goal") == "answer") row.optString("outcome") in setOf("plain", "question") &&
        !row.optBoolean("previewLeft") else row?.optString("outcome") in setOf("changed", "pressed", "reason")
    rows.forEachIndexed { i, row ->
        val yes = rows.getOrNull(i + 1)?.takeIf { it.optString("goal") == "yes" }
        row.put("verdict", if (passed(row) || row.optString("outcome") == "question" && passed(yes)) "passed" else "failed")
    }
    // The goals of the plan of 2026-11-21: a spoken change keeps a template file, a request to learn gets a free plan, and a turn with
    // no screen gets a spoken answer with no app on the stage.
    fun change(row: JSONObject) = row.optJSONObject("screen")?.optString("change")
    val evidence = mapOf<String, (JSONObject) -> Boolean>("motion" to { it.optBoolean("fullGeneration") }, "moving" to { it.optBoolean("stateCommit") },
        "touch" to { it.has("touched") && it.optBoolean("stateCommit") }, "search" to { it.optInt("sources") > 0 },
        "change" to { it.optBoolean("appFileChanged") && it.optBoolean("templateFile") },
        "learn" to { change(it) in setOf("new", "revision") && !it.optBoolean("templateFile") },
        "answer" to { it.optString("spoke").isNotBlank() && it.optJSONObject("screen")?.optBoolean("shown") == false })
    return JSONObject().apply {
        for (goal in goals + listOf("retry", "yes")) {
            val exercised = rows.filter { it.optString("goal") == goal && evidence[goal]?.invoke(it) != false }
            put(goal, exercised.joinToString { "${it.getString("verdict")} at step ${it.getInt("step")}" }.ifEmpty { "not exercised" })
        }
    }
}

/**
 * What the chat model answers in one step: the routing decision, and the reply of each generation call as (stage, reply) in order. The
 * routing call finds [sources] first, as a search does, and gives its decision after [decisionMs].
 */
internal class StepReplies(val decision: String, val calls: List<Pair<String, Scripted>> = emptyList(), val sources: List<Source> = emptyList(),
    val decisionMs: Long = 0)

/**
 * A chat model that answers by the step of the dialogue, not by the order of the calls, so a plan call beside the routing call and a
 * repair call get the text of their step. A generation call with no reply left waits until it is cancelled. A step with no template reply
 * answers each template call with [NO_TEMPLATE], as the template prompt answers a request that no template fits.
 */
internal class ScriptedChat(private val steps: Map<Int, StepReplies>, private val step: () -> Int) : ChatProvider {
    private val used = HashMap<Pair<Int, String>, Int>()

    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        val number = step()
        val replies = steps[number]
        val generation = request.generationStage
        if (generation == null) {
            replies?.sources?.takeIf { it.isNotEmpty() }?.let { emit(CloudEvent.Search("found", it)) }
            delay(replies?.decisionMs ?: 0)
            return emit(CloudEvent.RoundComplete(replies?.decision ?: chatDecision("answer", "Ответ без экрана."), false))
        }
        val index = synchronized(used) { used.merge(number to generation.name, 1, Int::plus)!! - 1 }
        val calls = replies?.calls.orEmpty().filter { it.first == generation.name }
        val reply = calls.getOrNull(index)?.second
            ?: if (calls.isEmpty() && generation == CloudGenerationStage.APP_FILE_TEMPLATE) Scripted.Text(NO_TEMPLATE) else awaitCancellation()
        reply.send(emit)
    }

    companion object {
        const val NO_TEMPLATE = """outside("other")"""
    }
}

/** The replies of each step of a stored run (`replay.json`), for a replay of its screens at another display size. */
internal fun storedReplies(stored: JSONObject): Map<Int, StepReplies> = stored.getJSONArray("steps").let { steps ->
    (0 until steps.length()).map { steps.getJSONObject(it) }.filter { it.has("decision") }.associate { step ->
        val calls = step.getJSONArray("calls")
        step.getInt("step") to StepReplies(step.getString("decision"), (0 until calls.length()).map {
            calls.getJSONObject(it).let { call -> call.getString("stage") to Scripted.Text(call.getString("reply")) }
        })
    }
}

/**
 * One dialogue on the shell with the voice mode (plan D, Dd3). Each step says its recorded request through a [WavFeeder], waits until
 * the hand-off ends and the voice listens for 2 s (60 s at most, [FULL_STEP_MS] in an adaptive dialogue), and takes the picture
 * `step-N.png`. Then it checks the hard assertions of the plan. After each step it writes [reportName], `events.log`, and
 * `replay.json` (the routing decision and the generation replies of each step, for a replay); at the end, also on a failure, it draws
 * `sheet.png`. [stored]: the `replay.json` of the run whose words a replay puts under its pictures (ruling OD-D2 b). The files hold no
 * key and no header. An adaptive dialogue inserts a step after an outcome of [followUp], runs all its steps, and throws at the end
 * when a step fails [PASS_BAR]. The hard stops of [hard] (no voice mode, the call cap, a late hand-off, an empty stage after a step
 * with no inserted step) still end it at once, also after a spoken reason.
 */
internal class DialogueRun(private val dialogue: Dialogue, private val directory: File, private val mode: String,
    private val reportName: String = "report.json", private val count: Int = dialogue.steps.size, private val stored: JSONObject? = null) {
    /** The step that runs now, from 1. The scripted chat and the store read it. */
    @Volatile var step = 0; private set
    private val robot = ShellRobot("dialogue-${dialogue.name}")
    private val fixture = ShellFixture("dialogue-${dialogue.name}")
    private val recorder = DialogueRecorder()
    private val audio = FakeLiveAudio(realTime = true)
    private val feeder = WavFeeder(audio)
    private val heard = CopyOnWriteArrayList<Heard>()
    /** The voice seconds that each voice session reported, by its number: a dialogue of new talks has one session for each step. */
    private val sessionSeconds = ConcurrentHashMap<Int, Double>()
    private val sessions = AtomicInteger()
    private val voiceSeconds get() = sessionSeconds.values.takeIf { it.isNotEmpty() }?.sum()
    private val decisions = ConcurrentHashMap<Int, String>()
    private val replies = ConcurrentHashMap<Int, MutableList<Pair<String, String>>>()
    private val appFiles = ConcurrentHashMap<Int, String>()
    private val rows = ArrayList<JSONObject>()
    private val checks = JSONArray()
    private val report = JSONObject().put("dialogue", dialogue.name).put("mode", mode).put("phase", "running")
    private val made = AtomicInteger()
    /** A chat call over [Dialogue.maxCalls] was refused, so the step stops waiting. */
    @Volatile private var capped = false
    /** The name of the first session error that the stage showed in this step. */
    private var stageError: String? = null
    private val sessionErrors by lazy {
        listOf(R.string.tool_session_stopped, R.string.tool_session_locked, R.string.tool_javascript_timeout, R.string.tool_session_save_failed)
            .associate { robot.text(it) to fixture.context.resources.getResourceEntryName(it) }
    }

    /** A transcript of the person ([HEARD]) or of the voice ([SPOKE]), or a commentary of Alphy to the voice ([ALPHY]). */
    private class Heard(val atNanos: Long, val who: String, val text: String)

    /** Runs the steps on [transport] and [chat]. Returns the report. A failed hard assertion throws after the files are written. */
    fun run(transport: LiveTransport, chat: ChatProvider, endpoints: Endpoints): JSONObject {
        check(directory.isDirectory || directory.mkdirs())
        fixture.install(chat = recorder.chat(keep(chat)), liveVoice = fixture.liveVoice(recorder.transport(listen(transport)), audio, endpoints))
        try {
            withPlanStep(fixture.context, AiProvider.OPENAI, true) { fixture.launch().use(::steps) }
        } finally {
            feeder.stop()
            recorder.close()
            write()
            sheet()
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
            fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
        }
        return report
    }

    private fun steps(scenario: ActivityScenario<CloudTestActivity>) {
        val model = checkNotNull(fixture.model)
        try {
            robot.waitUntil { !model.loading }
            // The kind screen needs the plan step, so a run without it would fail only after the paid calls of «Шаг 2».
            check(main { model.tools.planStep() } != null) { "The plan step is off, so the routing call offers no screen." }
            scenario.onActivity { activity ->
                val c = activity.resources.configuration
                report.put("display", JSONObject().put("widthDp", c.screenWidthDp).put("heightDp", c.screenHeightDp)
                    .put("smallestWidthDp", c.smallestScreenWidthDp).put("densityDpi", c.densityDpi).put("fontScale", c.fontScale.toDouble()))
                model.startLiveVoice()
            }
            robot.waitUntil(15_000) { main { model.liveVoice.phase } == LivePhase.LISTENING }
            recorder.watch(model)
            feeder.start()
            var spoken = 0
            var inserted = 0
            val queue = ArrayDeque(dialogue.steps.take(count))
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (dialogue.newTalks && step > 0) newTalk(scenario, model)
                step++
                stageError = null
                val began = SystemClock.elapsedRealtimeNanos()
                val before = main { stage(model) }
                val beforeFile = appFile(model)
                val users = main { messages(model).filter { it.role == "user" }.map { it.id }.toSet() }
                val errors = main { messages(model).filter { it.status == "error" }.map { it.id }.toSet() }
                val end = current.speech?.let { speech ->
                    val samples = wavSamples(robot.instrumentation.context.assets.open("voice/${speech.fixture}.wav").use { it.readBytes() })
                    feeder.say(samples).also(recorder::speechEnd)
                }
                val touched = if (current.touch && end != null) touch(model, end) else null
                val late = settle(scenario, model, end)
                picture(model)
                val after = main { stage(model) }
                val times = end?.let { recorder.report().getJSONArray("requests").getJSONObject(spoken++) } ?: JSONObject()
                val request = main { messages(model).firstOrNull { it.role == "user" && it.id !in users } }
                val window = recorder.marks().filter { it.atNanos >= began }
                val change = when {
                    after == null -> "none"; after == before -> "same"; after.first == before?.first -> "revision"; else -> "new"
                }
                val row = times.put("step", step).put("fixture", current.speech?.fixture).put("said", words(began, HEARD))
                    .put("spoke", words(began, SPOKE)).put("request", request?.text).put("handOffs", window.count { it.name == "delegation" })
                    .put("screen", JSONObject().put("shown", after != null).put("change", change)).put("picture", "step-$step.png")
                    // The evidence of the goals change, learn, and answer (judge): the stored app file of the same stage app changed, it is
                    // a template file, and a preview frame is still on the stage.
                    .put("appFileChanged", after != null && after.first == before?.first && appFiles[step] != beforeFile)
                    .put("templateFile", isTemplateAppFile(appFiles[step])).put("previewLeft", main { model.tools.stagePreviews.current != null })
                var next: DialogueStep? = null
                if (dialogue.adaptive) {
                    val reply = request?.let { asked -> main { messages(model).dropWhile { it.id != asked.id }.firstOrNull { it.role == "assistant" } } }
                    val error = stageError != null || main { messages(model).any { it.status == "error" && it.id !in errors } }
                    val lines = times.getJSONArray("lines").let { a -> (0 until a.length()).map(a::getString) }
                    val voiceAsked = row.getInt("handOffs") == 0 && row.getString("spoke").trimEnd().endsWith("?")
                    val outcome = stepOutcome(change, times.optString("kind").ifEmpty { null }, error, lines, times.getBoolean("spokeAfterLastLine"), voiceAsked)
                    row.put("goal", current.goal).put("outcome", outcome).put("alphy", JSONArray(heard.filter { it.who == ALPHY && it.atNanos >= began }.map { it.text }))
                        .put("search", decisions[step]?.let { runCatching { JSONObject(it).optString("search") }.getOrNull() })
                        .put("sources", reply?.sources?.size ?: 0).put("message", reply?.let { JSONObject().put("status", it.status).put("errorCode", it.errorCode) })
                        .put("stageError", stageError).put("touched", touched)
                    // At most one inserted step after each step of the script, and at most MAX_INSERTED in the dialogue.
                    next = followUp(outcome)?.takeIf { current.goal !in setOf("retry", "yes", "answer") && inserted < MAX_INSERTED }
                        ?.let { DialogueStep(it, handOff = true, screen = current.screen, goal = if (it == RETRY) "retry" else "yes") }
                    next?.let { queue.addFirst(it); inserted++ }
                }
                rows += row
                if (end != null) expect("${if (current.handOff) "a" else "no"} hand-off at step $step",
                    window.any { it.name == "delegation" } == current.handOff)
                current.requestStarts?.let { expect("the request text starts with «$it» at step $step", request?.text?.startsWith(it) == true) }
                write()
                // A failed step that gets a retry may leave the stage empty until the retry.
                hard(model, current.screen && next == null, late)?.let { reason ->
                    report.put("failedStep", JSONObject().put("step", step).put("reason", reason))
                    throw AssertionError("Шаг $step: $reason")
                }
            }
            rows.firstOrNull { it.optString("verdict") == "failed" }?.let { row ->
                val reason = "the pass bar: the step ended with ${row.getString("outcome")}"
                report.put("failedStep", JSONObject().put("step", row.getInt("step")).put("reason", reason))
                throw AssertionError("Шаг ${row.getInt("step")}: $reason")
            }
            report.put("phase", "complete")
        } catch (failure: Throwable) {
            report.put("phase", "failed")
            if (!report.has("failedStep")) report.put("failedStep", JSONObject().put("step", step).put("reason", failure.toString().take(300)))
            throw failure
        } finally {
            scenario.onActivity { model.endLiveVoice() }
            // The session reports its voice seconds when it closes.
            val closing = SystemClock.uptimeMillis()
            while (main { model.liveVoice.phase } != LivePhase.IDLE && SystemClock.uptimeMillis() - closing < 5_000) SystemClock.sleep(100)
        }
    }

    /**
     * Waits until the step has ended, at most [STEP_MS] ([FULL_STEP_MS] in an adaptive dialogue) after the end of its speech: the feeder
     * has sent the request, its hand-off came or [HAND_OFF_MS] passed, no hand-off works, the voice spoke after the last commentary (or
     * [VOICE_MS] passed), and the voice listens. These hold together for [QUIET_MS]. The wait does not depend on the kind of the routing
     * decision. A refused call ends the wait. An adaptive dialogue also notes the first session error on the stage, which can show for a
     * moment only. Returns true when a hand-off still works at the end of the limit.
     */
    private fun settle(scenario: ActivityScenario<CloudTestActivity>, model: ShellViewModel, end: Long?): Boolean {
        val start = end ?: SystemClock.elapsedRealtimeNanos()
        val deadline = start + (if (dialogue.adaptive) FULL_STEP_MS else STEP_MS) * 1_000_000
        var since = SystemClock.elapsedRealtimeNanos()
        while (SystemClock.elapsedRealtimeNanos() < deadline && !capped) {
            if (dialogue.adaptive && stageError == null) robot.composedIn(scenario, STAGE) { config ->
                config.getOrNull(SemanticsProperties.Text)?.firstNotNullOfOrNull { sessionErrors[it.text.trimEnd()] }?.also { stageError = it } != null
            }
            val now = SystemClock.elapsedRealtimeNanos()
            val marks = recorder.marks()
            val commentary = marks.lastOrNull { it.name == "commentary" && it.atNanos > start }?.atNanos
            val answered = commentary == null || marks.any { it.name == "voice_out" && it.atNanos > commentary } ||
                now - commentary > VOICE_MS * 1_000_000
            val handedOff = end == null || marks.any { it.name == "delegation" && it.atNanos > end } || now - end > HAND_OFF_MS * 1_000_000
            val quiet = feeder.idle && handedOff && answered && main { !model.liveVoiceWorking && model.liveVoice.phase == LivePhase.LISTENING }
            if (!quiet) since = now else if (now - since >= QUIET_MS * 1_000_000) break
            SystemClock.sleep(100)
        }
        val late = !capped && SystemClock.elapsedRealtimeNanos() >= deadline && main { model.liveVoiceWorking }
        // The stage draws the app in a later frame than the model names it.
        if (main { model.stageToolId } != null) {
            val composed = SystemClock.uptimeMillis() + 5_000
            while (robot.composedIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.TestTag) == CHARACTER } &&
                SystemClock.uptimeMillis() < composed) SystemClock.sleep(100)
        }
        robot.awaitFrames(5)
        return late
    }

    /**
     * Taps the first button of the stage app that shows on the screen, [TOUCH_MS] after the planned [end] of the speech, so the tap
     * comes after the hand-off and while the change runs. Returns its label, or null when no button of the app shows.
     */
    private fun touch(model: ShellViewModel, end: Long): String? {
        SystemClock.sleep(maxOf(0L, (end - SystemClock.elapsedRealtimeNanos()) / 1_000_000 + TOUCH_MS))
        val buttons = main { model.stageToolId?.let { id -> model.tools.tools.firstOrNull { it.id == id } }?.let(ShownScreen::of) }
            ?.controls.orEmpty().filter { it.value == null }
        return buttons.firstNotNullOfOrNull { button ->
            generateSequence(robot.find(button.label)) { it.parent }.firstOrNull { it.isClickable }
                ?.takeIf { it.isVisibleToUser && it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }?.let { button.label }
        }
    }

    private fun picture(model: ShellViewModel) {
        val shot = checkNotNull(robot.automation.takeScreenshot())
        try {
            File(directory, "step-$step.png").outputStream().use { check(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { shot.recycle() }
        // The app file of the stage after the step, for a replay and for the reader of the run.
        appFile(model)?.let { appFiles[step] = it }
    }

    /** The stored app file of the app that the stage shows, or null. */
    private fun appFile(model: ShellViewModel) = main { stage(model)?.let { (id, _) -> model.tools.tools.firstOrNull { it.id == id } } }
        ?.let { tool -> runCatching { ToolSpec.parsePersisted(tool.spec).appFile }.getOrNull() }

    /** Opens a new chat, which ends the voice mode, and starts the voice mode again, so the next request is the first turn of a new talk. */
    private fun newTalk(scenario: ActivityScenario<CloudTestActivity>, model: ShellViewModel) {
        scenario.onActivity { model.select(null) }
        robot.waitUntil(5_000) { main { model.liveVoice.phase } == LivePhase.IDLE }
        scenario.onActivity { model.startLiveVoice() }
        robot.waitUntil(15_000) { main { model.liveVoice.phase } == LivePhase.LISTENING }
    }

    /**
     * The first hard assertion of the plan that fails after this step, or null. In an adaptive dialogue a failed message and the full
     * generation are results of the step ([PASS_BAR]), not hard stops.
     */
    private fun hard(model: ShellViewModel, screen: Boolean, late: Boolean): String? {
        val marks = recorder.marks()
        val calls = marks.count { it.name == "call_start" }
        return main {
            val failed = messages(model).firstOrNull { it.status == "error" }
            when {
                !model.liveVoice.active -> "the voice mode is not active"
                calls > dialogue.maxCalls -> "$calls chat calls, more than ${dialogue.maxCalls}"
                late -> "the hand-off still works at the end of the step limit"
                !dialogue.adaptive && failed != null -> "a message has the status error (${failed.errorCode})"
                !dialogue.adaptive && marks.any { it.name == "call_start" && it.detail == "AGENT" } -> "a chat call had the stage AGENT"
                screen && model.stageToolId == null -> "no screen on the stage"
                else -> null
            }
        }
    }

    private fun expect(name: String, pass: Boolean) { checks.put(JSONObject().put("check", name).put("pass", pass)) }

    /** The transcript text of [who] since [from]. */
    private fun words(from: Long, who: String) = heard.filter { it.who == who && it.atNanos >= from }.joinToString("") { it.text }.trim()

    private fun stage(model: ShellViewModel) = model.stageToolId?.let { id -> model.tools.tools.firstOrNull { it.id == id }?.let { id to it.revision } }

    private fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    /** Keeps the routing decision and the generation replies of each step. A replay gives them back. Refuses each call over the cap. */
    private fun keep(inner: ChatProvider) = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
            if (made.incrementAndGet() > dialogue.maxCalls) { capped = true; throw CloudFailure("call_cap", "The dialogue used its chat calls.") }
            val number = step
            val text = StringBuilder()
            var completed: String? = null
            inner.respond(key, request, session) { event ->
                if (event is CloudEvent.Text) text.append(event.value)
                if (event is CloudEvent.RoundComplete) completed = event.text
                emit(event)
            }
            val reply = completed ?: text.toString()
            val stage = request.generationStage?.name
            if (stage == null) decisions[number] = reply
            else synchronized(replies) { replies.getOrPut(number) { ArrayList() } += stage to reply }
        }
    }

    /** Keeps the transcript of the person and of the voice, each commentary of Alphy, and the voice seconds of the session. */
    private fun listen(inner: LiveTransport) = LiveTransport { url, key, listener ->
        val session = sessions.incrementAndGet()
        val connection = inner.connect(url, key, object : LiveListener {
            override fun onText(text: String) {
                if ("transcript.delta" in text || "\"usage\"" in text) runCatching { JSONObject(text) }.getOrNull()?.let { event ->
                    when (val type = event.optString("type")) {
                        INPUT, OUTPUT -> heard += Heard(SystemClock.elapsedRealtimeNanos(), if (type == INPUT) HEARD else SPOKE, event.optString("delta"))
                        else -> event.optJSONObject("usage")?.optDouble("seconds")?.takeIf { !it.isNaN() }?.let { sessionSeconds[session] = it }
                    }
                }
                listener.onText(text)
            }
            override fun onFailure(status: Int?, message: String) = listener.onFailure(status, message)
            override fun onClosed() = listener.onClosed()
        })
        object : LiveConnection by connection {
            override fun send(text: String): Boolean {
                if ("\"session.commentary.append\"" in text.take(60)) runCatching { JSONObject(text).optString("content") }.getOrNull()
                    ?.let { heard += Heard(SystemClock.elapsedRealtimeNanos(), ALPHY, it) }
                return connection.send(text)
            }
        }
    }

    private fun write() {
        val clock = recorder.report()
        if (dialogue.adaptive) report.put("passBar", PASS_BAR).put("maxCalls", dialogue.maxCalls)
            .put("coverage", judge(rows, dialogue.steps.take(count).mapNotNull { it.goal }))
        report.put("steps", JSONArray(rows)).put("checks", checks).put("calls", clock.getJSONObject("calls"))
            .put("chatCalls", clock.getInt("chatCalls")).put("voiceSeconds", voiceSeconds)
        stored?.let { report.put("wordsFrom", it.optString("source")) }
        atomic(reportName, report.toString(2))
        val first = recorder.marks().firstOrNull()?.atNanos ?: 0L
        val lines = recorder.marks().map { it.atNanos to "${it.name}${it.detail?.let { d -> " $d" } ?: ""}" } +
            heard.map { it.atNanos to "${it.who} «${it.text}»" }
        atomic("events.log", lines.sortedBy { it.first }.joinToString("\n", postfix = "\n") { (at, line) ->
            "%9.3f s  %s".format((at - first) / 1e9, line)
        })
        atomic("replay.json", JSONObject().put("dialogue", dialogue.name).put("source", directory.name).put("steps", JSONArray(rows.map { row ->
            val number = row.getInt("step")
            JSONObject().put("step", number).put("said", row.optString("said")).put("spoke", row.optString("spoke"))
                .put("decision", decisions[number]).put("appFile", appFiles[number])
                .put("calls", JSONArray(synchronized(replies) { replies[number].orEmpty().toList() }.map { (stage, reply) ->
                    JSONObject().put("stage", stage).put("reply", reply)
                }))
        })).toString(2))
    }

    private fun atomic(name: String, text: String) = File(directory, "$name.tmp").run { writeText(text); check(renameTo(File(directory, name))) }

    /**
     * The sheet of the steps: a replay puts the words of the stored run under its pictures. An adaptive dialogue has a cell for each
     * step that ran, and the outcome and verdict of each step under its words.
     */
    private fun sheet() {
        val said = stored?.getJSONArray("steps")
        val total = if (dialogue.adaptive) maxOf(step, said?.length() ?: 0) else dialogue.steps.size
        val captions = (1..total).map { number ->
            val row = said?.let { (0 until it.length()).map(it::getJSONObject).firstOrNull { s -> s.getInt("step") == number } }
                ?: rows.firstOrNull { it.getInt("step") == number }
            val result = rows.firstOrNull { it.getInt("step") == number }?.takeIf { it.has("outcome") }
            listOfNotNull("Человек: " + (row?.optString("said")?.ifBlank { null }?.let { "«$it»" } ?: "—"),
                "Голос: " + (row?.optString("spoke")?.ifBlank { null }?.let { "«${it.take(SPOKEN_CHARS)}${if (it.length > SPOKEN_CHARS) "…" else ""}»" } ?: "—"),
                result?.let { "Итог: ${it.getString("outcome")}, ${it.optString("verdict")}" })
        }
        val wide = (report.optJSONObject("display")?.optInt("smallestWidthDp") ?: 0) >= 600
        drawSheet((1..total).map { File(directory, "step-$it.png").takeIf(File::isFile) }, captions, if (wide) 2 else 4,
            File(directory, "sheet.png"))
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        robot.instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val STAGE = "dialogue_stage"
        const val CHARACTER = "live_voice_character"
        const val STEP_MS = 60_000L
        /**
         * A step of an adaptive dialogue: a failed fast path (about 20 s) and the full generation (21 to 60 s in 25 measured creates,
         * 115 s at worst). Five steps stay below the 1,500 s limit of `run.py live-check`.
         */
        const val FULL_STEP_MS = 150_000L
        /** The paid probe got each hand-off about 1 s after the end of speech (b2-report.md, form J). */
        const val HAND_OFF_MS = 8_000L
        const val VOICE_MS = 10_000L
        const val QUIET_MS = 2_000L
        /** After the hand-off (about 1 s) and inside the routing call (2 to 5 s), so the state commit comes while the change runs. */
        const val TOUCH_MS = 2_000L
        const val SPOKEN_CHARS = 280
        const val HEARD = "heard"
        const val SPOKE = "spoke"
        const val ALPHY = "alphy"
    }
}

/**
 * Draws [pictures] side by side, or in a 2 by 2 grid when [columns] is 2, with «Шаг N» above each and [captions] under it. The text
 * size suits a view of the sheet at about 1600 px wide. A long text wraps inside its cell and ends with «…» after 9 lines.
 */
internal fun drawSheet(pictures: List<File?>, captions: List<List<String>>, columns: Int, out: File) {
    val gap = 28
    val cell = (1600 - (columns + 1) * gap) / columns
    val width = columns * cell + (columns + 1) * gap
    val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
    val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = Color.rgb(34, 34, 34) }
    val images = pictures.map { file -> file?.let { scaled(it, cell) } }
    val texts = captions.map { lines ->
        lines.map { StaticLayout.Builder.obtain(it, 0, it.length, body, cell).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(9).setEllipsize(TextUtils.TruncateAt.END).build() }
    }
    val headingHeight = 56
    val rows = pictures.indices.chunked(columns)
    // A missing picture (a step that did not run) keeps the height of the pictures of this display, or of a phone with none.
    val missing = images.firstNotNullOfOrNull { it?.height } ?: (cell * 2)
    val pictureHeights = rows.map { row -> row.maxOf { images[it]?.height ?: missing } }
    val textHeights = rows.map { row -> row.maxOf { i -> texts[i].sumOf { it.height + 10 } } }
    val sheet = Bitmap.createBitmap(width, rows.indices.sumOf { headingHeight + pictureHeights[it] + 16 + textHeights[it] + gap } + gap,
        Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet).apply { drawColor(Color.WHITE) }
    val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(200, 200, 200) }
    var top = gap
    rows.forEachIndexed { r, row ->
        row.forEachIndexed { c, i ->
            val left = gap + c * (cell + gap)
            canvas.drawText("Шаг ${i + 1}", left.toFloat(), top + 40f, heading)
            val image = images[i]
            val box = RectF(left.toFloat(), (top + headingHeight).toFloat(), (left + cell).toFloat(),
                (top + headingHeight + (image?.height ?: pictureHeights[r])).toFloat())
            if (image != null) canvas.drawBitmap(image, null, box, null)
            else canvas.drawText("нет снимка", box.left + 20, box.top + 50, body)
            canvas.drawRect(box, frame)
            var y = top + headingHeight + pictureHeights[r] + 16
            for (layout in texts[i]) {
                canvas.save(); canvas.translate(left.toFloat(), y.toFloat()); layout.draw(canvas); canvas.restore()
                y += layout.height + 10
            }
        }
        top += headingHeight + pictureHeights[r] + 16 + textHeights[r] + gap
    }
    out.outputStream().use { check(sheet.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    sheet.recycle()
    images.forEach { it?.recycle() }
}

/** The picture of [file] at [width] px wide, decoded at a smaller size first so four screenshots fit in the heap. */
private fun scaled(file: File, width: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return Bitmap.createScaledBitmap(decoded, width, decoded.height * width / decoded.width, true).also { if (it !== decoded) decoded.recycle() }
}

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.INPUT
import com.alpha.assistant.voice.LiveListener
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveTransport
import com.alpha.assistant.voice.OUTPUT
import com.alpha.assistant.voice.WavFeeder
import com.alpha.assistant.voice.wavSamples
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A recorded request: its file in the test assets `voice/` and its words. */
internal class Speech(val fixture: String, val words: String)

/**
 * One step of a dialogue as data: what the person says ([speech], none for the picture of the stage before the request), whether
 * the voice model is expected to hand it off ([handOff], reported), whether the stage must show an app after it ([screen], asserted),
 * and the expected start of the request text of the hand-off ([requestStarts], reported).
 */
internal class DialogueStep(val speech: Speech?, val handOff: Boolean, val screen: Boolean, val requestStarts: String? = null)

internal class Dialogue(val name: String, val steps: List<DialogueStep>)

private fun dialogue(name: String, before: Speech?, request: Speech, press: Speech, change: Speech, requestStarts: String? = null) =
    Dialogue(name, listOf(DialogueStep(before, handOff = false, screen = false), DialogueStep(request, true, true, requestStarts),
        DialogueStep(press, true, true), DialogueStep(change, true, true)))

/** The three live dialogues of design part 4: «Шаг 1» the stage, «Шаг 2» the request, «Шаг 3» a press, «Шаг 4» a change. */
internal val DIALOGUES = listOf(
    dialogue("heart", null, Speech("explain-heart", "Объясни, как работает сердце."), Speech("heart-press", "Покажи следующий шаг."),
        Speech("heart-change", "Подпиши камеры сердца.")),
    // The plain question gives the time to the first words with no screen and tests task Db0 live, as in form J.
    dialogue("moon", Speech("ask-largest-planet", "Какая планета самая большая в Солнечной системе?"),
        Speech("explain-moon", "Объясни, почему Луна всегда повёрнута к Земле одной стороной."), Speech("moon-press", "А если Луна не вращается?"),
        Speech("moon-change", "Покажи орбиту крупнее."), requestStarts = "Объясни"),
    dialogue("japanese", null, Speech("teach-japanese", "Научи меня считать до десяти по-японски."), Speech("japanese-press", "Дальше."),
        Speech("japanese-change", "Добавь, как произносится каждая цифра.")),
).associateBy { it.name }

/** What the chat model answers in one step: the routing decision, and the reply of each generation call as (stage, text) in order. */
internal class StepReplies(val decision: String, val calls: List<Pair<String, String>> = emptyList())

/**
 * A chat model that answers by the step of the dialogue, not by the order of the calls, so a plan call beside the routing call and a
 * repair call get the text of their step. A generation call with no reply left waits until it is cancelled.
 */
internal class ScriptedChat(private val steps: Map<Int, StepReplies>, private val step: () -> Int) : ChatProvider {
    private val used = HashMap<Pair<Int, String>, Int>()

    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        val number = step()
        val replies = steps[number]
        val stage = request.generationStage?.name
            ?: return emit(CloudEvent.RoundComplete(replies?.decision ?: chatDecision("answer", "Ответ без экрана."), false))
        val index = synchronized(used) { used.merge(number to stage, 1, Int::plus)!! - 1 }
        val text = replies?.calls?.filter { it.first == stage }?.getOrNull(index)?.second ?: awaitCancellation()
        emit(CloudEvent.Text(text))
        emit(CloudEvent.RoundComplete(text, false))
    }
}

/** The replies of each step of a stored run (`replay.json`), for a replay of its screens at another display size. */
internal fun storedReplies(stored: JSONObject): Map<Int, StepReplies> = stored.getJSONArray("steps").let { steps ->
    (0 until steps.length()).map { steps.getJSONObject(it) }.filter { it.has("decision") }.associate { step ->
        val calls = step.getJSONArray("calls")
        step.getInt("step") to StepReplies(step.getString("decision"),
            (0 until calls.length()).map { calls.getJSONObject(it).let { call -> call.getString("stage") to call.getString("reply") } })
    }
}

/**
 * One dialogue on the shell with the voice mode (plan D, Dd3). Each step says its recorded request through a [WavFeeder], waits until
 * the hand-off ends and the voice listens for 2 s (60 s at most), and takes the picture `step-N.png`. Then it checks the hard
 * assertions of the plan. After each step it writes [reportName], `events.log`, and `replay.json` (the routing decision and the
 * generation replies of each step, for a replay); at the end, also on a failure, it draws `sheet.png`. [stored]: the `replay.json` of
 * the run whose words a replay puts under its pictures (ruling OD-D2 b). The files hold no key and no header.
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
    @Volatile private var voiceSeconds: Double? = null
    private val decisions = ConcurrentHashMap<Int, String>()
    private val replies = ConcurrentHashMap<Int, MutableList<Pair<String, String>>>()
    private val appFiles = ConcurrentHashMap<Int, String>()
    private val rows = ArrayList<JSONObject>()
    private val checks = JSONArray()
    private val report = JSONObject().put("dialogue", dialogue.name).put("mode", mode).put("phase", "running")

    private class Heard(val atNanos: Long, val input: Boolean, val text: String)

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
            for ((index, current) in dialogue.steps.take(count).withIndex()) {
                step = index + 1
                val began = SystemClock.elapsedRealtimeNanos()
                val before = main { stage(model) }
                val users = main { messages(model).filter { it.role == "user" }.map { it.id }.toSet() }
                val end = current.speech?.let { speech ->
                    val samples = wavSamples(robot.instrumentation.context.assets.open("voice/${speech.fixture}.wav").use { it.readBytes() })
                    feeder.say(samples).also(recorder::speechEnd)
                }
                settle(scenario, model, end)
                picture(model)
                val after = main { stage(model) }
                val times = end?.let { recorder.report().getJSONArray("requests").getJSONObject(spoken++) } ?: JSONObject()
                val request = main { messages(model).firstOrNull { it.role == "user" && it.id !in users }?.text }
                val window = recorder.marks().filter { it.atNanos >= began }
                rows += times.put("step", step).put("fixture", current.speech?.fixture).put("said", words(began, input = true))
                    .put("spoke", words(began, input = false)).put("request", request).put("handOffs", window.count { it.name == "delegation" })
                    .put("screen", JSONObject().put("shown", after != null).put("change", when {
                        after == null -> "none"; after == before -> "same"; after.first == before?.first -> "revision"; else -> "new"
                    })).put("picture", "step-$step.png")
                if (end != null) expect("${if (current.handOff) "a" else "no"} hand-off at step $step",
                    window.any { it.name == "delegation" } == current.handOff)
                current.requestStarts?.let { expect("the request text starts with «$it» at step $step", request?.startsWith(it) == true) }
                write()
                hard(model, current)?.let { reason ->
                    report.put("failedStep", JSONObject().put("step", step).put("reason", reason))
                    throw AssertionError("Шаг $step: $reason")
                }
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
     * Waits until the step has ended, at most [STEP_MS] after the end of its speech: the feeder has sent the request, its hand-off came
     * or [HAND_OFF_MS] passed, no hand-off works, the voice spoke after the last commentary (or [VOICE_MS] passed), and the voice
     * listens. These hold together for [QUIET_MS]. The wait does not depend on the kind of the routing decision.
     */
    private fun settle(scenario: ActivityScenario<CloudTestActivity>, model: ShellViewModel, end: Long?) {
        val start = end ?: SystemClock.elapsedRealtimeNanos()
        val deadline = start + STEP_MS * 1_000_000
        var since = SystemClock.elapsedRealtimeNanos()
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
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
        // The stage draws the app in a later frame than the model names it.
        if (main { model.stageToolId } != null) {
            val composed = SystemClock.uptimeMillis() + 5_000
            while (robot.composedIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.TestTag) == CHARACTER } &&
                SystemClock.uptimeMillis() < composed) SystemClock.sleep(100)
        }
        robot.awaitFrames(5)
    }

    private fun picture(model: ShellViewModel) {
        val shot = checkNotNull(robot.automation.takeScreenshot())
        try {
            File(directory, "step-$step.png").outputStream().use { check(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { shot.recycle() }
        // The app file of the stage after the step, for a replay and for the reader of the run.
        main { stage(model)?.let { (id, _) -> model.tools.tools.firstOrNull { it.id == id } } }
            ?.let { tool -> runCatching { ToolSpec.parsePersisted(tool.spec).appFile }.getOrNull() }?.let { appFiles[step] = it }
    }

    /** The first hard assertion of the plan that fails after this step, or null. */
    private fun hard(model: ShellViewModel, step: DialogueStep): String? {
        val marks = recorder.marks()
        val calls = marks.count { it.name == "call_start" }
        return main {
            val failed = messages(model).firstOrNull { it.status == "error" }
            when {
                !model.liveVoice.active -> "the voice mode is not active"
                failed != null -> "a message has the status error (${failed.errorCode})"
                marks.any { it.name == "call_start" && it.detail == "AGENT" } -> "a chat call had the stage AGENT"
                calls > MAX_CALLS -> "$calls chat calls, more than $MAX_CALLS"
                step.screen && model.stageToolId == null -> "no screen on the stage"
                else -> null
            }
        }
    }

    private fun expect(name: String, pass: Boolean) { checks.put(JSONObject().put("check", name).put("pass", pass)) }

    /** The transcript text of the person ([input]) or of the voice since [from]. */
    private fun words(from: Long, input: Boolean) = heard.filter { it.input == input && it.atNanos >= from }.joinToString("") { it.text }.trim()

    private fun stage(model: ShellViewModel) = model.stageToolId?.let { id -> model.tools.tools.firstOrNull { it.id == id }?.let { id to it.revision } }

    private fun messages(model: ShellViewModel) = model.snapshot.selected?.messages.orEmpty()

    /** Keeps the routing decision and the generation replies of each step. A replay gives them back. */
    private fun keep(inner: ChatProvider) = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
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

    /** Keeps the transcript of the person and of the voice, and the voice seconds of the session. */
    private fun listen(inner: LiveTransport) = LiveTransport { url, key, listener ->
        inner.connect(url, key, object : LiveListener {
            override fun onText(text: String) {
                if ("transcript.delta" in text || "\"usage\"" in text) runCatching { JSONObject(text) }.getOrNull()?.let { event ->
                    when (val type = event.optString("type")) {
                        INPUT, OUTPUT -> heard += Heard(SystemClock.elapsedRealtimeNanos(), type == INPUT, event.optString("delta"))
                        else -> event.optJSONObject("usage")?.optDouble("seconds")?.takeIf { !it.isNaN() }?.let { voiceSeconds = it }
                    }
                }
                listener.onText(text)
            }
            override fun onFailure(status: Int?, message: String) = listener.onFailure(status, message)
            override fun onClosed() = listener.onClosed()
        })
    }

    private fun write() {
        val clock = recorder.report()
        report.put("steps", JSONArray(rows)).put("checks", checks).put("calls", clock.getJSONObject("calls"))
            .put("chatCalls", clock.getInt("chatCalls")).put("voiceSeconds", voiceSeconds)
        stored?.let { report.put("wordsFrom", it.optString("source")) }
        atomic(reportName, report.toString(2))
        val first = recorder.marks().firstOrNull()?.atNanos ?: 0L
        val lines = recorder.marks().map { it.atNanos to "${it.name}${it.detail?.let { d -> " $d" } ?: ""}" } +
            heard.map { it.atNanos to "${if (it.input) "heard" else "spoke"} «${it.text}»" }
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

    /** The sheet of the steps: a replay puts the words of the stored run under its pictures. */
    private fun sheet() {
        val said = stored?.getJSONArray("steps")
        val captions = (1..dialogue.steps.size).map { number ->
            val row = said?.let { (0 until it.length()).map(it::getJSONObject).firstOrNull { s -> s.getInt("step") == number } }
                ?: rows.firstOrNull { it.getInt("step") == number }
            listOf("Человек: " + (row?.optString("said")?.ifBlank { null }?.let { "«$it»" } ?: "—"),
                "Голос: " + (row?.optString("spoke")?.ifBlank { null }?.let { "«${it.take(SPOKEN_CHARS)}${if (it.length > SPOKEN_CHARS) "…" else ""}»" } ?: "—"))
        }
        val wide = (report.optJSONObject("display")?.optInt("smallestWidthDp") ?: 0) >= 600
        drawSheet((1..dialogue.steps.size).map { File(directory, "step-$it.png").takeIf(File::isFile) }, captions, if (wide) 2 else 4,
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
        /** The paid probe got each hand-off about 1 s after the end of speech (b2-report.md, form J). */
        const val HAND_OFF_MS = 8_000L
        const val VOICE_MS = 10_000L
        const val QUIET_MS = 2_000L
        /** The cap of the plan (values that this plan proposes). */
        const val MAX_CALLS = 10
        const val SPOKEN_CHARS = 280
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

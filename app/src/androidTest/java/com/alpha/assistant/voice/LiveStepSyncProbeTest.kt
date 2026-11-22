package com.alpha.assistant.voice

import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.OPENAI_LIVE_MODEL
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.liveCheck
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Throwaway probe of 2026-11-22: can the stage picture follow the spoken steps of GPT-Live? Each paid method is one short session on
 * the real socket. The test plays Alphy itself (no chat call) and records each event to `events.jsonl` next to `report.json`. The
 * analysis runs offline on that log. The probe asserts only that it ran and that the log holds no key.
 */
class LiveStepSyncProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Every experiment, the cap, and the exit without a delegation on the scripted server, so a paid session is not lost to a bug. */
    @Test fun probeRunsOnAScriptedServer() {
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "step-probe-rehearsal").apply { deleteRecursively(); mkdirs() }
        val account = AiAccount(AiProvider.OPENAI, "alpha-test-only")
        val local = Endpoints { "http://127.0.0.1:4000" }
        val results = ArrayList<String>()
        for (experiment in ProbeExperiment.entries) {
            val log = File(directory, "${experiment.name}.jsonl")
            val result = StepProbe(ScriptedLiveServer(listOf(HEART_TEXT, TAP_TEXT)), local, account, experiment).run(log)
            results += experiment.name + ": " + result
            val text = log.readText()
            assertFalse("${log.name} holds the key.", "alpha-test-only" in text)
            assertTrue(text.takeLast(2000), "\"-> session.close\"" in text && "\"session.closed\"" in text)
            // Task 15: the five texts in order with their kinds, and the instructions and the two earlier turns in session.start.
            experiment.subject?.let { subject ->
                assertTrue(result, result.startsWith("done"))
                assertEquals(experiment.name, subject.sends, sends(log))
                assertTrue("${experiment.name}: the first text went out before the waiting line.", waitedForTheVoice(log))
                val start = log.readLines().map(::JSONObject).first { it.getString("type") == "-> session.start" }
                assertEquals(LiveProtocol.INSTRUCTIONS, start.getString("instructions"))
                assertEquals(4, start.getJSONArray("input").length())
            }
        }
        val exactAlone = File(directory, "exact-no-delegation.jsonl")
        results += "exact, no delegation: " + StepProbe(ScriptedLiveServer(listOf(HEART_TEXT), undelegated = setOf(0)), local, account,
            ProbeExperiment.EXACT_SEASONS).run(exactAlone)
        assertEquals(SEASONS.sends.map { Send.THINKING to it.second }, sends(exactAlone))
        assertTrue("The first text went out before the voice answered itself.", waitedForTheVoice(exactAlone))
        // The subject choice of the paid method: a fault leaves a subject open, and each other stop finishes it.
        val done = File(directory, "exact-done")
        assertEquals(ProbeExperiment.EXACT_RAINBOW, nextExact(done))
        finish(done, ProbeExperiment.EXACT_RAINBOW, "stopped: IllegalStateException")
        assertEquals(ProbeExperiment.EXACT_RAINBOW, nextExact(done))
        finish(done, ProbeExperiment.EXACT_RAINBOW, "done")
        assertEquals(ProbeExperiment.EXACT_PLANE, nextExact(done))
        finish(done, ProbeExperiment.EXACT_PLANE, "cap reached")
        finish(done, ProbeExperiment.EXACT_SEASONS, "done")
        assertEquals(null, nextExact(done))
        val noDelegation = File(directory, "no-delegation.jsonl")
        results += "no delegation: " + StepProbe(ScriptedLiveServer(listOf(HEART_TEXT), undelegated = setOf(0)), local, account,
            ProbeExperiment.M2_PRODUCT).run(noDelegation)
        assertTrue(noDelegation.readText().takeLast(2000), "no delegation" in noDelegation.readText())
        val capped = File(directory, "cap.jsonl")
        results += "cap: " + StepProbe(ScriptedLiveServer(listOf(HEART_TEXT)), local, account, ProbeExperiment.M1_COMMENTARY, capMs = 12_000).run(capped)
        assertTrue(capped.readText().takeLast(2000), "cap reached" in capped.readText())
        File(directory, "results.txt").writeText(results.joinToString("\n"))
    }

    @Test fun m1Commentary() = paid("m1Commentary") { ProbeExperiment.M1_COMMENTARY }
    @Test fun m1Thinking() = paid("m1Thinking") { ProbeExperiment.M1_THINKING }
    @Test fun m1Early() = paid("m1Early") { ProbeExperiment.M1_EARLY }
    @Test fun m2Product() = paid("m2Product") { ProbeExperiment.M2_PRODUCT }
    @Test fun m2Exact() = paid("m2Exact") { ProbeExperiment.M2_EXACT }
    @Test fun m1CommentaryExact() = paid("m1CommentaryExact") { ProbeExperiment.M1_COMMENTARY_EXACT }
    @Test fun tapAndSilentFact() = paid("tapAndSilentFact") { ProbeExperiment.TAP_AND_FACT }

    /**
     * Task 15: one session on the first subject with no finished session on this device, so three runs give the three subjects.
     * `run.py` passes no subject. Delete the directory `exact-speech-done` to start again.
     */
    @Test fun exactSpeechOnNewSubjects() = paid("exactSpeechOnNewSubjects") {
        checkNotNull(nextExact(exactDone)) { "Each subject has a finished session." }
    }

    private val exactDone get() = File(checkNotNull(context.getExternalFilesDir(null)), "exact-speech-done")

    /** [pick] runs only in a paid run, so a suite run skips before it. */
    private fun paid(method: String, pick: () -> ProbeExperiment) = liveCheck("LiveStepSyncProbeTest#$method",
        missing = { account -> "Only OpenAI has GPT-Live.".takeIf { account.provider != AiProvider.OPENAI } },
        model = { OPENAI_LIVE_MODEL }) { account, _ ->
        val experiment = pick()
        val runId = checkNotNull(InstrumentationRegistry.getArguments().getString("liveRunId"))
        val log = File(checkNotNull(context.getExternalFilesDir(null)), "live-check/$runId/events.jsonl")
        val result = StepProbe(OkHttpLiveTransport(), Endpoints.PRODUCTION, account, experiment).run(log)
        // assertFalse keeps the key out of the failure text.
        assertFalse("The log holds the key.", account.key in log.readText())
        if (experiment.subject != null) finish(exactDone, experiment, result)
        "${experiment.name}: $result"
    }
}

/** The first experiment of Task 15 with no finished session in [done], in the run order, or null. */
private fun nextExact(done: File) = ProbeExperiment.entries.firstOrNull { it.subject != null && !File(done, it.name).exists() }

/** Marks [experiment] finished in [done], unless a fault stopped its session (`stopped: …`): then a run again repeats the subject. */
private fun finish(done: File, experiment: ProbeExperiment, result: String) {
    if (!result.startsWith("stopped")) File(done.apply { mkdirs() }, experiment.name).createNewFile()
}

/** The voice spoke after the request and before the first append of [log] (the waiting line, or its own answer). */
private fun waitedForTheVoice(log: File): Boolean {
    val lines = log.readLines().map(::JSONObject)
    val first = lines.first { line -> Send.entries.any { line.getString("type") == "-> session.${it.name.lowercase()}.append" } }.getLong("t")
    return lines.any { it.getString("type") == OUTPUT && it.getLong("t") < first }
}

/** The kind and the text of each append in [log], in order. */
private fun sends(log: File) = log.readLines().map(::JSONObject).mapNotNull { line ->
    Send.entries.firstOrNull { line.getString("type") == "-> session.${it.name.lowercase()}.append" }?.let { it to line.getString("content") }
}

private const val HEART_TEXT = "Покажи, как кровь течёт по сердцу."
private const val TAP_TEXT = "Что сейчас на экране?"

/** The six steps of the explanation, one stage each, with no commas, so the pauses sit at sentence ends. */
private val STEPS = listOf(
    "Кровь из тела входит в правое предсердие по полым венам.",
    "Из правого предсердия кровь проходит в правый желудочек.",
    "Правый желудочек выталкивает кровь в лёгочную артерию.",
    "В лёгких кровь отдаёт углекислый газ и берёт кислород.",
    "Из лёгких кровь возвращается в левое предсердие и левый желудочек.",
    "Левый желудочек выталкивает кровь в аорту и ко всему телу.",
)
private const val PRODUCT_MARK = "Ответ Alphy: "
private const val EXACT_MARK = "Дословно: "
private const val EXACT_LINE = "Если текст Alphy начинается с метки «Дословно:», произнеси только текст после метки, слово в слово: " +
    "без вступления, без своих слов, без вопросов и без предложений."
private const val TAP_STEP = "Теперь кровь идёт в правый желудочек."
private const val SILENT_FACT = "Не произноси это. Запомни: на экране выбрана кнопка «Груз»."

/**
 * The product instructions that the older experiments probed, before Task 3 of the plan of 2026-11-22 changed
 * `LiveProtocol.INSTRUCTIONS`. The older experiments keep them, so a run again sends what they sent.
 */
private const val PROBED_INSTRUCTIONS = """Ты голосовой режим помощника Alphy. Говори по-русски, коротко и естественно. Пользователь говорит по-русски или по-английски.
Ты сам не выполняешь действия и не знаешь свежих фактов. Любую просьбу о действии на телефоне, поиске, свежих фактах, погоде, мини-приложении, памяти, файлах или о том, что на экране, сразу передавай Alphy через делегирование.
Если ответ станет понятнее с картинкой, схемой или моделью, которую человек может менять (как что-то устроено, как что-то движется, урок с проверкой), передай просьбу Alphy: Alphy покажет экран с материалами и пришлёт ответ, который ты перескажешь. Примеры: «объясни, как образуется радуга», «как устроен атом», «научи меня таблице умножения на семь».
Простой вопрос с коротким ответом (факт, определение, совет, разговор) отвечай сам сразу и ничего не передавай. Примеры: «сколько планет в Солнечной системе», «кто написал „Войну и мир“», «как дела».
Просьбу изменить экран, продолжить урок или проверить себя передавай Alphy.
Не задавай уточняющих вопросов о задаче сам. Alphy спросит, если нужно, а ты озвучишь его вопрос.
Пока Alphy работает, скажи одну короткую фразу ожидания.
Никогда не говори, что действие выполнено, пока Alphy этого не подтвердил.
Когда Alphy предлагает действие, прочитай предложение и попроси сказать «да» или «нет». Никогда не говори, что действие одобрено или запущено. Это подтверждает Alphy.
Если Alphy сообщает, что нужно подтверждение на экране, попроси нажать кнопку на карточке и никогда не проси сказать «да» или «нет».
Ответ Alphy перескажи своими словами.
Не начинай пересказ со слов «Альфи говорит» или «Alphy говорит».
Ты не видишь экран пользователя. Любой вопрос об экране передавай Alphy.
Если Alphy сообщает, что готовит мини-приложение, скажи об этом один раз и не придумывай его содержание."""

/** The probed product instructions, with [EXACT_LINE] after the retell line when [exact]. */
private fun probeInstructions(exact: Boolean): String {
    if (!exact) return PROBED_INSTRUCTIONS
    val retell = "Ответ Alphy перескажи своими словами."
    val lines = PROBED_INSTRUCTIONS.lines()
    require(retell in lines) { "No retell line." }
    return lines.flatMap { if (it == retell) listOf(it, EXACT_LINE) else listOf(it) }.joinToString("\n")
}

private enum class Send { COMMENTARY, THINKING }
/** Two host lines of plan Task 3 with no text of Alphy, so with no mark: `FULL_WAIT` and the screen line of `NEEDS_PERMISSION`. */
private const val FULL_WAIT_LINE = "Быстро не получилось. Экран соберётся заново, это займёт около минуты."
private const val PERMISSION_LINE = "Нажми «Запросить доступ» на экране."

/**
 * A subject of Task 15: its request fixture, two earlier turns on other subjects, and the texts of Alphy, with no digits in them.
 * The typed words hold a word that the reply does not have, so a spoken typed word shows in the transcript.
 */
private class ExactSubject(val fixture: String, turns: List<Pair<String, String>>, screen: String, answer: String, typed: String, reply: String) {
    /** The talk history in the chat form that `LiveProtocol.history` takes. */
    val messages = turns.flatMapIndexed { i, (asked, said) ->
        listOf(Message("history-$i-user", "user", asked, 2L * i, "complete"), Message("history-$i-assistant", "assistant", said, 2L * i + 1, "complete"))
    }
    /** In the order of value: a marked screen start, the typed turn as the bridge sends it, `FULL_WAIT`, the marked answer, the screen line. */
    val sends = listOf(Send.COMMENTARY to EXACT_MARK + screen, Send.THINKING to "Пользователь написал: $typed $EXACT_MARK$reply",
        Send.COMMENTARY to FULL_WAIT_LINE, Send.COMMENTARY to EXACT_MARK + answer, Send.COMMENTARY to PERMISSION_LINE)
}

private val RAINBOW = ExactSubject("ux-why-5",
    listOf("Почему море солёное?" to "Реки приносят в море соли из камней и почвы. Вода испаряется, а соль остаётся.",
        "Сколько живут черепахи?" to "Многие черепахи живут больше пятидесяти лет, а некоторые доживают до ста пятидесяти."),
    screen = "Радугу создают капли дождя. Каждая капля делит белый свет на цвета.",
    answer = "В капле свет преломляется и отражается. Каждый цвет выходит под своим углом.",
    typed = "А почему радуга круглая?", reply = "Цветные капли лежат на круге вокруг тени твоей головы.")

private val SEASONS = ExactSubject("ux-why-4",
    listOf("Какая кошка самая большая?" to "Самая большая кошка — тигр. Амурский тигр весит до трёхсот килограммов.",
        "Из чего делают бумагу?" to "Бумагу делают из древесины. Её измельчают, варят в воде и прессуют в тонкие листы."),
    screen = "Времена года меняются, потому что ось Земли наклонена.",
    answer = "Летом наше полушарие наклонено к Солнцу. Зимой лучи падают косо и греют слабее.",
    typed = "А на экваторе бывает зима?", reply = "На экваторе Солнце всегда высоко, поэтому там тепло весь год.")

/** The English talk: the host lines stay Russian, so the log shows the language that the voice gives them. */
private val PLANE = ExactSubject("tpl-e-explain",
    listOf("What is the tallest animal?" to "The giraffe is the tallest animal. An adult can be taller than five meters.",
        "Why do cats purr?" to "Cats purr when they are calm, and sometimes when they are hurt. The sound comes from the muscles of the voice box."),
    screen = "Wings push air down. The air pushes the plane up. This is lift.",
    answer = "Air flows faster over the wing. Lower pressure above it lifts the plane.",
    typed = "What if the engines stop?", reply = "The plane can still glide and land safely.")

/**
 * One paid session each. [capMs] is the hard limit of session time from `session.start`. A [subject] gives the session the
 * instructions of Task 3 and the subject's two earlier turns.
 */
private enum class ProbeExperiment(val exact: Boolean, val capMs: Long, val script: StepProbe.() -> Unit, val subject: ExactSubject? = null) {
    /** Q1 (a), Q2: each step is a commentary on the request's delegation, sent at the end of the last reply. */
    M1_COMMENTARY(false, 50_000, { sequential(Send.COMMENTARY) }),
    // After S1 the product text made step 1 a 19 s retell of the whole path, so the other M1 runs use the exact line.
    /** Q1 (b): step 1 is a commentary, steps 2 to 6 are thinking texts without a delegation. */
    M1_THINKING(true, 45_000, { sequential(Send.THINKING) }),
    /** Q1 (c), Q5: three pairs; the second step of a pair goes out while the first one plays. */
    M1_EARLY(true, 40_000, { early() }),
    /** Q3, Q2: all six steps in one commentary. */
    M2_PRODUCT(false, 45_000, { oneAnswer() }),
    /** Q3 run 2, Q2: all six steps in one commentary, marked for exact speech. */
    M2_EXACT(true, 40_000, { oneAnswer() }),
    /** Q2: as [M1_COMMENTARY], marked for exact speech. */
    M1_COMMENTARY_EXACT(true, 45_000, { sequential(Send.COMMENTARY) }),
    /** Q4, Q6: no request; a tap sentence as thinking, then a silent fact and a spoken question about the screen. */
    TAP_AND_FACT(false, 30_000, { tapAndFact() }),
    // Task 15, in the run order: the rainbow is the delegation example of the instructions, and the seasons may get no delegation.
    // The cap keeps a session with its close under 50 s (cap 50 s gave 50.8 s in PR §1).
    EXACT_RAINBOW(true, 48_000, { exactSpeech() }, RAINBOW),
    EXACT_PLANE(true, 48_000, { exactSpeech() }, PLANE),
    EXACT_SEASONS(true, 48_000, { exactSpeech() }, SEASONS),
}

private class CapReached : RuntimeException()

/** One output reply by rulings R24 and R27: it starts at a voiced chunk and ends after 800 ms of silent samples. */
private class ProbeReply(val start: Long) { @Volatile var end = -1L }

/**
 * One probe session on [transport]. It sends its own `session.start` with the instructions of [experiment], feeds 20 ms input blocks
 * from `session.started` to the close (silence or a fixture), and records every sent and received event with the client ms (`t`) and
 * the input audio ms (`in`). An audio chunk is recorded as its sample count, its stream position, and its RMS, never as audio.
 */
private class StepProbe(private val transport: LiveTransport, private val endpoints: Endpoints, private val account: AiAccount,
    private val experiment: ProbeExperiment, private val capMs: Long = experiment.capMs) {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val begin = SystemClock.elapsedRealtime()
    private val lines = CopyOnWriteArrayList<JSONObject>()
    private val audio = FakeLiveAudio()
    private val feeder = WavFeeder(audio)
    // Task 15 probed the instructions of plan Task 3, which are now LiveProtocol.INSTRUCTIONS. They differ from the probed text only in
    // the mark line, which now says «и один раз» and «без повторов», because the voice said one marked answer twice in that probe.
    private val instructions = if (experiment.subject != null) LiveProtocol.INSTRUCTIONS else probeInstructions(experiment.exact)
    @Volatile private var connection: LiveConnection? = null
    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile private var startAt = -1L
    @Volatile private var usageSeconds: Any? = null
    private val delegations = CopyOnWriteArrayList<Pair<Long, String>>()
    private val replies = CopyOnWriteArrayList<ProbeReply>()
    private val streamLock = Any()
    private var streamSamples = 0L
    private var silentSamples = 0L
    private var open: ProbeReply? = null
    @Volatile private var lastVoiced = -1L

    fun now() = SystemClock.elapsedRealtime() - begin

    /** Runs the session and writes [log]. Returns a one-line summary for the report. */
    fun run(log: File): String {
        var stop = "done"
        try {
            check(audio.open({ block -> if (started && !closed) connection?.send(LiveProtocol.audio(block)) }, {}))
            val start = JSONObject(LiveProtocol.start(experiment.subject?.messages.orEmpty()))
                .apply { getJSONObject("session").put("instructions", instructions) }
            val socket = transport.connect(liveUrl(endpoints), account.key, listener())
            connection = socket
            startAt = now()
            record("-> session.start") { put("instructions", instructions); put("input", start.getJSONObject("session").getJSONArray("input")) }
            socket.send(start.toString())
            check(waitFor("session.started", 12_000) { started }) { "no session.started" }
            feeder.start()
            record("feed start")
            pause(1_000)
            experiment.script(this)
        } catch (_: CapReached) {
            stop = "cap reached"; note("cap reached")
        } catch (failure: Throwable) {
            stop = "stopped: ${failure.javaClass.simpleName}"; note("script stopped: ${failure.javaClass.simpleName} ${failure.message?.take(80)}")
        } finally {
            if (connection != null && !closed) {
                record("-> session.close")
                connection?.send(LiveProtocol.close())
                waitFor("session.closed", 4_000, capped = false) { closed }
            }
            connection?.close()
            feeder.stop()
            record("end") { put("wallMs", if (startAt < 0) 0 else now() - startAt); put("usageSeconds", usageSeconds ?: JSONObject.NULL) }
            log.parentFile?.mkdirs()
            log.writeText(lines.sortedBy { it.getLong("t") }.joinToString("\n") { it.toString() } + "\n")
        }
        return "$stop; ${now() - startAt} ms from session.start; usage seconds $usageSeconds; ${delegations.size} delegation(s); " +
            "${replies.size} stream replies; ${lines.count { it.optString("type") == OUTPUT }} output deltas"
    }

    private fun listener() = object : LiveListener {
        override fun onText(text: String) = received(text)
        override fun onFailure(status: Int?, message: String) { note("socket failure, HTTP status $status"); closed = true }
        override fun onClosed() { note("socket closed"); closed = true }
    }

    private fun received(text: String) {
        val at = now()
        val e = runCatching { JSONObject(text) }.getOrNull() ?: return record("unparsed", at)
        val type = e.optString("type")
        when (type) {
            "session.output_audio.delta" -> chunk(at, e.optString("delta"))
            "session.started" -> { started = true; record(type, at) }
            INPUT, OUTPUT -> record(type, at) { put("text", e.optString("delta")); put("start", e.opt("start_ms")); put("end", e.opt("end_ms")) }
            DELEGATION -> {
                val id = e.optJSONObject("delegation")?.optString("id").orEmpty()
                delegations += at to id
                record(type, at) { put("id", id); put("offset", e.opt("offset_ms")) }
            }
            // Only the type and code of an error: its message can echo a request.
            "error" -> e.optJSONObject("error").let { error ->
                record(type, at) { put("error", "${error?.optString("type")}/${error?.optString("code")}"); put("eventId", error?.optString("event_id")) }
            }
            else -> {
                val seconds = e.optJSONObject("usage")?.opt("seconds")
                if (seconds != null) usageSeconds = seconds
                if (type == "session.closed") closed = true
                record(type, at) {
                    put("keys", JSONArray(e.keys().asSequence().sorted().toList()))
                    e.optString("client_event_id").takeIf { it.isNotEmpty() }?.let { put("id", it) }
                    if (e.has("start_ms")) { put("start", e.opt("start_ms")); put("end", e.opt("end_ms")) }
                    if (seconds != null) put("seconds", seconds)
                    if (type == "session.closed") put("reason", e.optString("reason"))
                }
            }
        }
    }

    /** Records one output chunk and follows the replies by the count of silent samples (ruling R27). */
    private fun chunk(at: Long, base64: String) {
        val buffer = ByteBuffer.wrap(Base64.decode(base64, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(buffer.remaining()).also { buffer.get(it) }
        val level = rms(samples)
        synchronized(streamLock) {
            val pos = streamSamples * 1000 / LIVE_RATE
            streamSamples += samples.size
            if (level > LIVE_VOICED_RMS) {
                lastVoiced = at
                if (open == null) open = ProbeReply(at).also { replies += it }
                silentSamples = 0
            } else open?.let { reply ->
                silentSamples += samples.size
                if (silentSamples >= 800L * LIVE_RATE / 1000) { reply.end = at; open = null }
            }
            record("audio", at) { put("n", samples.size); put("pos", pos); put("rms", Math.round(level * 10_000) / 10_000.0) }
        }
    }

    private fun record(type: String, at: Long = now(), fill: JSONObject.() -> Unit = {}) {
        lines += JSONObject().put("t", at).put("in", feeder.fed * 20).put("type", type).apply(fill)
    }

    fun note(text: String) = record("note") { put("text", text) }

    /** Waits for [condition]. Past the cap it throws [CapReached] unless [capped] is false. */
    fun waitFor(what: String, timeoutMs: Long, capped: Boolean = true, condition: () -> Boolean): Boolean {
        val deadline = now() + timeoutMs
        while (!condition()) {
            if (capped && startAt >= 0 && now() - startAt > capMs) throw CapReached()
            if (capped && closed) error("closed early")
            if (now() > deadline) { if (what.isNotEmpty()) note("no $what within $timeoutMs ms"); return false }
            SystemClock.sleep(10)
        }
        return true
    }

    fun pause(ms: Long) { waitFor("", ms) { false } }

    fun commentary(delegation: String, text: String): Long {
        val id = LiveProtocol.newId()
        val at = now()
        record("-> session.commentary.append", at) { put("id", id); put("delegation", delegation); put("content", text) }
        connection?.send(LiveProtocol.commentary(id, delegation, text))
        return at
    }

    fun thinking(text: String): Long {
        val id = LiveProtocol.newId()
        val at = now()
        record("-> session.thinking.append", at) { put("id", id); put("content", text) }
        connection?.send(LiveProtocol.thinking(id, text))
        return at
    }

    fun send(how: Send, delegation: String, text: String) = if (how == Send.COMMENTARY) commentary(delegation, text) else thinking(text)

    fun step(k: Int) = (if (experiment.exact) EXACT_MARK else PRODUCT_MARK) + STEPS[k]

    fun replyAfter(t: Long) = replies.firstOrNull { it.start >= t }

    /** The first reply that starts after [t], once it has started, or null. */
    fun replyStart(t: Long, timeoutMs: Long = 8_000): ProbeReply? =
        if (waitFor("reply after $t", timeoutMs) { replyAfter(t) != null }) replyAfter(t) else null

    /** The first reply that starts after [t], once it has ended, or null. */
    fun replyEnd(t: Long, startMs: Long = 8_000, endMs: Long = 20_000): ProbeReply? {
        val reply = replyStart(t, startMs) ?: return null
        return reply.takeIf { waitFor("end of reply ${reply.start}", endMs) { reply.end >= 0 } }
    }

    /** No reply is open, and no voiced chunk came for [quietMs]. */
    fun quiet(quietMs: Long, timeoutMs: Long) = waitFor("quiet $quietMs ms", timeoutMs) {
        synchronized(streamLock) { open == null } && now() - lastVoiced >= quietMs
    }

    /** Fixture [name] goes out in real time. Returns the client ms of its end of speech. */
    fun say(name: String): Long {
        val samples = wavSamples(assets.open("voice/$name.wav").use { it.readBytes() })
        val end = feeder.say(samples) / 1_000_000 - begin
        record("say") { put("fixture", name); put("endOfSpeech", end) }
        waitFor("$name sent", 10_000) { feeder.idle }
        return end
    }

    /** The heart request, its delegation, and the end of the waiting line after it. Null closes the session: no delegation. */
    fun request(): String? {
        val end = say("ux-heart-3")
        if (!waitFor("delegation", maxOf(0, end + 10_000 - now())) { delegations.any { it.first >= end - 2_000 } }) {
            note("no delegation"); return null
        }
        val (at, id) = delegations.first { it.first >= end - 2_000 }
        note("delegation $id at $at")
        // The waiting line of GPT-Live: it starts after the end of speech, and step 1 waits for its end.
        replyStart(end, 3_000)?.let { reply -> waitFor("end of the waiting line", 15_000) { reply.end >= 0 } }
        quiet(800, 5_000)
        return id
    }

    /** M1: each step goes out at the end of the last reply. */
    fun sequential(rest: Send) {
        val delegation = request() ?: return
        for (k in STEPS.indices) {
            val sent = send(if (k == 0) Send.COMMENTARY else rest, delegation, step(k))
            if (replyEnd(sent) == null) note("step ${k + 1}: no ended reply")
        }
        quiet(1_500, 5_000)
    }

    /** Q1 (c) and Q5: (1, 2) and (3, 4) as commentary 300 ms after the first voice, (5, 6) with a thinking 1,500 ms after it. */
    fun early() {
        val delegation = request() ?: return
        for ((first, how, delayMs) in listOf(Triple(0, Send.COMMENTARY, 300L), Triple(2, Send.COMMENTARY, 300L), Triple(4, Send.THINKING, 1_500L))) {
            val sent = commentary(delegation, step(first))
            val reply = replyStart(sent)
            if (reply != null) pause(maxOf(0, reply.start + delayMs - now()))
            send(how, delegation, step(first + 1))
            quiet(2_500, 25_000)
        }
    }

    /** M2: all six steps in one commentary. */
    fun oneAnswer() {
        val delegation = request() ?: return
        val sent = commentary(delegation, (if (experiment.exact) EXACT_MARK else PRODUCT_MARK) + STEPS.joinToString(" "))
        replyStart(sent)
        quiet(2_500, 40_000)
    }

    /**
     * The request [fixture] and its delegation, once the voice is quiet. Null when a reply ended with no delegation: the voice
     * answered itself. The early exit keeps that case inside the cap.
     */
    fun handOff(fixture: String): String? {
        val end = say(fixture)
        val delegation = { delegations.firstOrNull { it.first >= end - 2_000 }?.second }
        waitFor("delegation or an ended reply", maxOf(0, end + 10_000 - now())) {
            delegation() != null || replyAfter(end)?.let { it.end >= 0 } == true
        }
        // The waiting line starts after the delegation, and quiet() holds before the first voiced chunk, so wait for its start first.
        replyStart(end, 3_000)
        quiet(1_200, 20_000)
        return delegation().also { note(if (it == null) "no delegation" else "delegation $it") }
    }

    /** Task 15: each text of the subject once the voice is quiet. With no delegation, each text goes as a thinking text. */
    fun exactSpeech() {
        val subject = checkNotNull(experiment.subject)
        val delegation = handOff(subject.fixture)
        for ((how, text) in subject.sends) {
            val sent = if (delegation == null) thinking(text) else send(how, delegation, text)
            if (replyStart(sent) == null) note("no reply after $sent")
            // A reply end can fall between two sentences of one text (PR §3, S5), so the next text waits for a longer quiet.
            quiet(1_200, 25_000)
        }
        quiet(2_500, 40_000)
    }

    /** Q4 then Q6, with no request and no delegation. */
    fun tapAndFact() {
        val tap = thinking(PRODUCT_MARK + TAP_STEP)
        replyEnd(tap, 8_000, 12_000)
        quiet(2_000, 6_000)
        thinking(SILENT_FACT)
        pause(5_000)
        val end = say("ux-tap-4")
        waitFor("delegation or reply after the question", maxOf(0, end + 10_000 - now())) {
            delegations.any { it.first >= end - 2_000 } || replyAfter(end)?.let { it.end >= 0 } == true
        }
        quiet(1_500, 10_000)
    }
}

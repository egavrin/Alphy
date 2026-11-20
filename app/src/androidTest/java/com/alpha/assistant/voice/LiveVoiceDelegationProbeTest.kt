package com.alpha.assistant.voice

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.OPENAI_LIVE_MODEL
import com.alpha.assistant.tools.liveCheck
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The delegation probe of voice plan task B2: does GPT-Live hand a spoken request to explain, to show, or to teach a thing off to
 * Alphy, and does it also answer by itself? Form J asks whether GPT-Live chooses the hand-off by itself: four plain questions and four
 * visual requests. One paid session for each form says its requests one after the other, with the real
 * socket and the fake host of [LiveVoiceDialogTest] (no chat call). `probe.json` next to `events.log` records each request. The app's
 * instructions stay unchanged: [Instructed] gives the session the text of the form. The probe asserts only that it ran and that no
 * record holds the key, because what GPT-Live does is the measurement.
 */
class LiveVoiceDelegationProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Each form on the scripted server, with one request that gets no delegation, so a paid session is not lost to a probe fault. */
    @Test fun probeRunsOnAScriptedServer() {
        for (form in ProbeForm.entries) {
            val log = File(context.cacheDir, "probe-${form.name}.log")
            val record = File(context.cacheDir, "probe-${form.name}.json")
            val server = ScriptedLiveServer(form.requests.map { it.text }, undelegated = setOf(2))
            DelegationProbe(form, server, Endpoints { "http://127.0.0.1:4000" }, AiAccount(AiProvider.OPENAI, "alpha-test-only")).run(log, record)
            val json = JSONObject(record.readText())
            assertEquals(form.instructions, json.getString("instructionsSent"))
            val rows = json.getJSONArray("rows")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val delegations = row.getJSONArray("delegations")
                if (i == 2) {
                    assertEquals(row.toString(), 0, delegations.length())
                    assertTrue(row.toString(), "Отвечу сама" in row.getString("saidBeforeDelegation"))
                    continue
                }
                assertEquals(row.toString(), form.requests[i].text, delegations.getJSONObject(0).getString("request"))
                assertEquals(row.toString(), if (form.requests[i].plain) "plain" else "visual", row.getString("kind"))
                assertEquals(row.toString(), form.comments, row.has("commentary"))
                if (form.comments) assertTrue(row.toString(), "экран" in row.getString("saidAfterCommentary"))
            }
            for (file in listOf(log, record)) assertFalse("${file.name} holds the key.", "alpha-test-only" in file.readText())
        }
    }

    @Test fun formAAnswersAndHandsOff() = paid("formAAnswersAndHandsOff", ProbeForm.A)

    @Test fun formAAdjustedAnswersAndHandsOff() = paid("formAAdjustedAnswersAndHandsOff", ProbeForm.A2)

    @Test fun formAWaitingLineChangedAnswersAndHandsOff() = paid("formAWaitingLineChangedAnswersAndHandsOff", ProbeForm.A3)

    @Test fun formBHandsOffOnly() = paid("formBHandsOffOnly", ProbeForm.B)

    @Test fun formJChoosesHandOffs() = paid("formJChoosesHandOffs", ProbeForm.J)

    private fun paid(method: String, form: ProbeForm) = liveCheck("LiveVoiceDelegationProbeTest#$method",
        missing = { account -> "Only OpenAI has GPT-Live.".takeIf { account.provider != AiProvider.OPENAI } },
        model = { OPENAI_LIVE_MODEL }) { account, _ ->
        val runId = checkNotNull(InstrumentationRegistry.getArguments().getString("liveRunId"))
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "live-check/$runId")
        val log = File(directory, "events.log")
        val record = File(directory, "probe.json")
        val result = DelegationProbe(form, OkHttpLiveTransport(), Endpoints.PRODUCTION, account).run(log, record)
        // assertFalse keeps the key out of the failure text.
        assertFalse("A record holds the key.", account.key in log.readText() || account.key in record.readText())
        result
    }
}

/** Today's instructions with [added] after the delegation line, where the draft of the voice plan puts its lines. */
private fun probeInstructions(vararg added: String) = LiveProtocol.INSTRUCTIONS.lines().let { (it.take(2) + added + it.drop(2)).joinToString("\n") }

/** The text with its line [old] replaced by [new]. The line must exist, so a changed app text cannot make the probe send the old line. */
private fun String.replaceLine(old: String, new: String) = lines().also { require(old in it) { "No line «$old»" } }
    .joinToString("\n") { if (it == old) new else it }

/**
 * [minRoundMs]: a round ends no earlier than this after the end of speech, so a late answer or hand-off is not cut off.
 * [requests]: the spoken requests in their order. [comments]: the host answers each hand-off with the commentary of its request.
 */
private enum class ProbeForm(val instructions: String, val minRoundMs: Long = 6_000, val requests: List<ProbeRequest> = PROBE_REQUESTS,
    val comments: Boolean = false) {
    /** GPT-Live answers at once by itself and also hands the request off for a screen. */
    A(probeInstructions("Просьбу объяснить, показать, как что-то устроено, или научить чему-то: коротко ответь сам и сразу передай " +
        "просьбу Alphy, чтобы Alphy показал рядом экран с материалами. Не описывай экран сам.")),
    /** Form (a) again, worded once more after the first session: GPT-Live delegated each request but only promised an answer. */
    A2(probeInstructions("Просьбу объяснить, показать, как что-то устроено, или научить чему-то сразу передай Alphy, чтобы Alphy " +
        "показал рядом экран с материалами. Вместо фразы ожидания сразу сам ответь по существу в двух-трёх коротких фразах: " +
        "общеизвестное ты знаешь, ждать Alphy для этого не нужно. Не обещай объяснить позже и не описывай экран сам.")),
    /**
     * Form (a) with today's waiting line changed (the controller's wording 3), with 15 s rounds, so an answer of two to four
     * sentences ends before the next request.
     */
    A3(probeInstructions("Просьбу объяснить, показать, как что-то устроено, или научить чему-то сразу передай Alphy, чтобы рядом " +
        "появился экран с материалами, и тут же, не дожидаясь Alphy, сам объясни по существу в двух–четырёх предложениях. Экран сам не описывай.")
        .replaceLine("Пока Alphy работает, скажи одну короткую фразу ожидания.",
            "Пока Alphy выполняет действие, ищет или готовит мини-приложение, скажи одну короткую фразу ожидания. Для просьбы объяснить " +
                "или научить фраза ожидания не нужна: вместо неё ты сразу объясняешь сам."), minRoundMs = 15_000),
    /** GPT-Live only hands the request off and retells the answer of Alphy. */
    B(probeInstructions(
        "Просьбу объяснить, показать, как что-то устроено, или научить чему-то тоже сразу передавай Alphy: Alphy покажет рядом экран с материалами.",
        "Если Alphy прислал ответ и сообщил, что экран появится, перескажи ответ своими словами. Не описывай экран сам.",
        "Просьбу изменить экран, продолжить урок или проверить себя тоже передавай Alphy."), comments = true),
    /** GPT-Live chooses by itself: a hand-off for an answer that a screen makes clearer, an own answer for a plain question. */
    J(probeInstructions(
        "Если ответ станет понятнее с картинкой, схемой или моделью, которую человек может менять (как что-то устроено, как что-то " +
            "движется, урок с проверкой), передай просьбу Alphy: Alphy покажет экран с материалами и пришлёт ответ, который ты " +
            "перескажешь. Примеры: «объясни, как работает сердце», «почему Луна повёрнута к Земле одной стороной», «научи меня " +
            "считать до десяти по-японски».",
        "Простой вопрос с коротким ответом (факт, определение, совет, разговор) отвечай сам сразу и ничего не передавай. Примеры: " +
            "«сколько планет в Солнечной системе», «кто написал „Войну и мир“», «как дела».",
        "Просьбу изменить экран, продолжить урок или проверить себя передавай Alphy."),
        minRoundMs = 12_000, requests = PROBE_J_REQUESTS, comments = true),
}

/** A spoken request: its fixture, its words, the one-sentence commentary of Alphy, and whether it is a plain question. */
private class ProbeRequest(val fixture: String, val text: String, val answer: String, val plain: Boolean = false)

private val PROBE_REQUESTS = listOf(
    ProbeRequest("explain-moon", "Объясни, почему Луна всегда повёрнута к Земле одной стороной.",
        "Ответ Alphy: Луна делает один оборот вокруг своей оси за то же время, за какое обходит Землю, поэтому к нам всегда " +
            "обращена одна её сторона, а экран с материалами появится рядом."),
    ProbeRequest("teach-japanese", "Научи меня считать до десяти по-японски.",
        "Ответ Alphy: по-японски от одного до десяти считают так: ити, ни, сан, си, го, року, сити, хати, кю, дзю, а экран с " +
            "материалами появится рядом."),
    ProbeRequest("show-rainbow", "Покажи, как работает радуга.",
        "Ответ Alphy: радуга появляется, когда солнечный свет преломляется и отражается в каплях дождя и распадается на цвета, а " +
            "экран с материалами появится рядом."),
    ProbeRequest("explain-atom", "Объясни, как устроен атом.",
        "Ответ Alphy: атом состоит из маленького тяжёлого ядра из протонов и нейтронов и из электронов вокруг него, а экран с " +
            "материалами появится рядом."),
    ProbeRequest("teach-times-seven", "Научи меня таблице умножения на семь.",
        "Ответ Alphy: семью один — семь, семью два — четырнадцать, семью три — двадцать один, и так до семью десять — семьдесят, а " +
            "экран с материалами появится рядом."),
)

/** Form J: plain, visual, plain, visual, visual, plain, visual, plain. No plain question is an example of the form J text. */
private val PROBE_J_REQUESTS = listOf(
    ProbeRequest("ask-largest-planet", "Какая планета самая большая в Солнечной системе?",
        "Ответ Alphy: самая большая планета Солнечной системы — Юпитер, а экран с материалами появится рядом.", plain = true),
    PROBE_REQUESTS[0],
    ProbeRequest("ask-photosynthesis", "Что такое фотосинтез, в двух словах?",
        "Ответ Alphy: фотосинтез — это когда растения на свету делают из углекислого газа и воды сахар и выделяют кислород, а экран " +
            "с материалами появится рядом.", plain = true),
    PROBE_REQUESTS[1],
    PROBE_REQUESTS[2],
    ProbeRequest("ask-seven-times-eight", "Сколько будет семью восемь?",
        "Ответ Alphy: семью восемь — пятьдесят шесть, а экран с материалами появится рядом.", plain = true),
    PROBE_REQUESTS[3],
    ProbeRequest("ask-fall-asleep", "Посоветуй, как быстрее заснуть.",
        "Ответ Alphy: чтобы быстрее заснуть, за час до сна отложи телефон, проветри комнату и ложись в одно и то же время, а экран " +
            "с материалами появится рядом.", plain = true),
)

/** The output is quiet for this long before the next request, so the next request is no barge-in. */
private const val PROBE_QUIET_MS = 2_500L
/** Without a delegation, a round waits this long after the end of speech. */
private const val PROBE_NO_DELEGATION_MS = 15_000L
/** Form (b) waits this long after its commentary for GPT-Live to speak. */
private const val PROBE_COMMENTARY_WAIT_MS = 20_000L
/** The longest round, after the end of speech. */
private const val PROBE_ROUND_CAP_MS = 45_000L

/** The test seam of the probe: it replaces the instructions of `session.start`, so the app's instructions stay unchanged. */
private class Instructed(private val inner: LiveTransport, private val instructions: String) : LiveTransport {
    /** The instructions of the `session.start` that went out. */
    @Volatile var sent: String? = null; private set

    override fun connect(url: String, key: String, listener: LiveListener): LiveConnection {
        val connection = inner.connect(url, key, listener)
        return object : LiveConnection {
            override fun send(text: String): Boolean {
                if ("\"session.start\"" !in text.take(40)) return connection.send(text)
                val start = JSONObject(text).apply { getJSONObject("session").put("instructions", instructions) }
                sent = start.getJSONObject("session").getString("instructions")
                return connection.send(start.toString())
            }
            override fun close() = connection.close()
        }
    }
}

/**
 * One session of the probe on [LiveDialog]. The host answers each hand-off as it arrives: in form (a) with nothing, as the product of
 * form (a) speaks nothing for a screen; in form (b) with the one-sentence commentary of the request, sent at once. A delegation belongs
 * to the last request queued before it arrived. The next request waits until the output is quiet.
 */
private class DelegationProbe(private val form: ProbeForm, transport: LiveTransport, endpoints: Endpoints, account: AiAccount) {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val instructed = Instructed(transport, form.instructions)
    private val dialog = LiveDialog(instructed, endpoints, account, 0)
    private val rows = ArrayList<JSONObject>()
    /** The dialog ms at which each request was queued. */
    private val asked = ArrayList<Long>()
    /** Delegation id to (dialog ms, request text) of its hand-off to the host. */
    private val handOffs = HashMap<String, Pair<Long, String>>()
    /** Request index to (dialog ms of the send, client event id) of its commentary. */
    private val comments = HashMap<Int, Pair<Long, String>>()

    /** Runs the session, writes [record] after each request and at the end, and returns the summary of the rows. */
    fun run(log: File, record: File): String {
        val outcome = dialog.run(log) {
            val started = dialog.start()
            dialog.note("probe form ${form.name}: session.start instructions «${instructed.sent}»")
            if (started) {
                dialog.listen()
                SystemClock.sleep(1_000)
                form.requests.forEachIndexed { i, request -> round(i, request); write(record, null) }
            }
        }
        write(record, outcome)
        val summary = rows.joinToString("\n") { summary(it) }
        assertTrue("$summary\n${outcome.summary}", outcome.failures.isEmpty() && rows.size == form.requests.size)
        return summary
    }

    private fun round(i: Int, request: ProbeRequest) {
        val samples = wavSamples(assets.open("voice/${request.fixture}.wav").use { it.readBytes() })
        dialog.feeder.maxLagMs = 0
        val queued = dialog.now()
        asked += queued
        val end = dialog.feeder.say(samples) / 1_000_000 - dialog.begin
        dialog.note("probe request ${i + 1} ${request.fixture}: queued, end of speech at $end")
        var reason = "cap"
        while (dialog.now() < end + PROBE_ROUND_CAP_MS) {
            answer()
            if (dialog.host.ended != null) { reason = "ended"; break }
            if (settled(i, end)) { reason = "settled"; break }
            SystemClock.sleep(20)
        }
        answer()
        rows += row(i, request, queued, end, dialog.now(), reason)
        dialog.note("probe request ${i + 1} round end: $reason")
    }

    /** Takes each hand-off as it arrives. Forms (b) and J answer the first hand-off of each request with its commentary. */
    private fun answer() {
        while (true) {
            val (id, request) = dialog.host.delegations.poll() ?: return
            handOffs[id] = dialog.now() to request
            val owner = owner(arrival(id))
            if (form.comments && owner !in comments) comments[owner] = dialog.comment(id, form.requests[owner].answer)
        }
    }

    private fun settled(i: Int, end: Long): Boolean {
        val now = dialog.now()
        val delegated = delegations(i).isNotEmpty()
        val comment = comments[i]?.first
        // A commenting form waits for its commentary to go out and to be spoken, or for its wait to end.
        val answered = !form.comments || !delegated ||
            comment != null && (dialog.outputs(comment).isNotEmpty() || now - comment >= PROBE_COMMENTARY_WAIT_MS)
        return dialog.feeder.idle && now - end >= form.minRoundMs && now - dialog.lastOutput() >= PROBE_QUIET_MS &&
            (delegated || now - end >= PROBE_NO_DELEGATION_MS) && answered
    }

    private fun arrival(id: String) = dialog.events.firstOrNull { it.type == DELEGATION && it.id == id }?.at ?: dialog.now()
    private fun owner(at: Long) = maxOf(0, asked.indexOfLast { it <= at })
    private fun delegations(i: Int) = dialog.events.filter { it.type == DELEGATION && owner(it.at) == i }

    /** The record of one request. Times are dialog ms; the delays count from the end of the request's speech. */
    private fun row(i: Int, request: ProbeRequest, queued: Long, end: Long, until: Long, reason: String): JSONObject {
        val delegations = delegations(i).filter { it.at <= until }
        val first = delegations.firstOrNull()?.at
        val comment = comments[i]
        val voiced = dialog.voiced.filter { it in queued..until }
        val voice = voiced.firstOrNull()
        fun said(from: Long, to: Long) = dialog.text(dialog.outputs(from, to - 1))
        val order = when {
            voice == null && first == null -> "neither"
            first == null -> "voice only"
            voice == null -> "delegation only"
            voice < first -> "voice first"
            else -> "delegation first"
        }
        return JSONObject().put("index", i + 1).put("fixture", request.fixture).put("kind", if (request.plain) "plain" else "visual")
            .put("said", request.text)
            .put("queuedAt", queued).put("endOfSpeechAt", end).put("roundEndAt", until).put("roundEnd", reason)
            .put("feederMaxLagMs", dialog.feeder.maxLagMs)
            .put("heard", dialog.text(dialog.events.filter { it.type == INPUT && it.at in queued..until }))
            .put("delegations", JSONArray(delegations.map { d ->
                JSONObject().put("id", d.id).put("arrivedAt", d.at).put("afterEndMs", d.at - end).put("offsetMs", d.startMs)
                    .put("handOffAt", handOffs[d.id]?.first).put("request", handOffs[d.id]?.second)
            }))
            .put("order", order)
            .put("firstVoiceAfterEndMs", voice?.minus(end))
            .put("firstDelegationAfterEndMs", first?.minus(end))
            .put("voicedChunksBeforeDelegation", voiced.count { first == null || it < first })
            .put("saidBeforeDelegation", said(queued, first ?: (until + 1)))
            .put("saidAfterDelegation", first?.let { said(it, comment?.first ?: (until + 1)) })
            .put("commentary", comment?.let { (at, eventId) ->
                JSONObject().put("text", request.answer).put("sentAt", at).put("sentAfterEndMs", at - end)
                    .put("appendedAt", dialog.events.firstOrNull { it.type == APPENDED && it.id == eventId }?.at)
            })
            .put("saidAfterCommentary", comment?.let { said(it.first, until + 1) })
            .put("firstVoiceAfterCommentaryMs", comment?.let { c -> voiced.firstOrNull { it >= c.first }?.minus(c.first) })
            .put("firstSaidAfterCommentaryMs", comment?.let { c -> dialog.outputs(c.first, until).firstOrNull()?.at?.minus(c.first) })
            .put("errors", JSONArray(dialog.events.filter { it.type == "error" && it.at in queued..until }.map { it.text }))
    }

    private fun summary(row: JSONObject): String {
        val d = row.getJSONArray("delegations")
        val first = if (d.length() > 0) d.getJSONObject(0) else null
        return "${row.getInt("index")} ${row.getString("fixture")} (${row.getString("roundEnd")}, ${row.getString("order")}): " +
            "first voice +${row.opt("firstVoiceAfterEndMs")} ms; ${d.length()} delegation(s), first +${row.opt("firstDelegationAfterEndMs")} ms " +
            "«${first?.optString("request")}»; before «${row.getString("saidBeforeDelegation")}»; after «${row.optString("saidAfterDelegation")}»" +
            (row.optJSONObject("commentary")?.let { "; commentary voice +${row.opt("firstVoiceAfterCommentaryMs")} ms «${row.optString("saidAfterCommentary")}»" } ?: "")
    }

    private fun write(record: File, outcome: Outcome?) {
        val json = JSONObject().put("form", form.name).put("instructionsSent", instructed.sent).put("rows", JSONArray(rows))
        outcome?.let { json.put("failures", JSONArray(it.failures)).put("dialogSummary", it.summary) }
        record.writeText(json.toString(2))
    }
}

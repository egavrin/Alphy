package com.alpha.assistant.voice

import com.alpha.assistant.data.Message
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GPT-Live wire protocol of spec 5.3 and 5.5.5 without a network. */
class VoiceModeProtocolTest {
    private fun message(i: Int, status: String = "complete") =
        Message("id$i", if (i % 2 == 0) "user" else "assistant", "m$i".padEnd(300, 'x'), i.toLong(), status)

    private fun JSONArray.item(i: Int) = getJSONObject(i).getJSONArray("content").getJSONObject(0)

    @Test fun startHasTheSpecFields() {
        val start = JSONObject(LiveProtocol.start(emptyList()))
        assertEquals("session.start", start.getString("type"))
        assertTrue(start.getString("event_id").isNotEmpty())
        val session = start.getJSONObject("session")
        assertEquals("gpt-live-1", session.getString("model"))
        val format = session.getJSONObject("audio").getJSONObject("format")
        assertEquals("audio/pcm", format.getString("type"))
        assertEquals(16000, format.getInt("rate"))
        assertEquals("marin", session.getJSONObject("audio").getJSONObject("output").getString("voice"))
        assertEquals(LiveProtocol.INSTRUCTIONS, session.getString("instructions"))
        assertFalse(session.has("delegation"))
    }

    /** Ruling R25: an on-screen confirmation is a tap on the button that the text names, never a spoken answer. */
    @Test fun onScreenConfirmationGoesToTheCard() {
        val lines = LiveProtocol.INSTRUCTIONS.lines()
        val rule = lines.indexOf("Если нужно подтверждение на экране, назови кнопку, как в тексте, и никогда не проси сказать «да» или «нет».")
        assertTrue("The on-screen rule follows the spoken proposal rule", rule > 0 && lines[rule - 1].startsWith("Когда тебе предлагают действие"))
    }

    /**
     * Plan of 2026-11-22, decision 7: the voice says the text after the mark word for word and once (the probe of Task 15 heard one
     * marked answer twice). No line names Alphy, asks for a retell, or fixes the language of the talk.
     */
    @Test fun theInstructionsHoldTheExactLine() {
        assertTrue(LiveProtocol.INSTRUCTIONS, "Если в тексте есть метка «Дословно:», произнеси только текст после метки, слово в слово и один раз: " +
            "без вступления, без своих слов, без повторов, без вопросов и без предложений." in LiveProtocol.INSTRUCTIONS.lines())
        for (word in listOf("Alphy", "перескажи", "по-русски")) assertFalse(word, word in LiveProtocol.INSTRUCTIONS)
    }

    /** Task 3 fix round 2: a paid check heard the voice explain on after a short marked text, so the next line tells it to stop. */
    @Test fun theInstructionsHoldTheStopLine() {
        val lines = LiveProtocol.INSTRUCTIONS.lines()
        val mark = lines.indexOfFirst { it.startsWith("Если в тексте есть метка «Дословно:»") }
        assertTrue(LiveProtocol.INSTRUCTIONS, mark >= 0)
        assertEquals("После этого текста замолчи и жди: не объясняй и не продолжай сам, даже если ответ кажется неполным.", lines.getOrNull(mark + 1))
    }

    /** Mark fix (live record live-2b-uxHeart): the voice said the mark word aloud and began own sentences with it, so the line after the stop line forbids both. */
    @Test fun theInstructionsKeepTheMarkWordSilent() {
        val lines = LiveProtocol.INSTRUCTIONS.lines()
        val stop = lines.indexOfFirst { it.startsWith("После этого текста замолчи и жди:") }
        assertTrue(LiveProtocol.INSTRUCTIONS, stop >= 0)
        assertEquals("Слово «Дословно» — служебная метка. Никогда не произноси его вслух и не начинай с него свои фразы.", lines.getOrNull(stop + 1))
    }

    /**
     * Plan D, Db3 (form J, ruling OD-D5): after the delegation line, the session start gives the choice between a hand-off for a
     * screen and an own short answer. The examples are not the topics of the live checks.
     */
    @Test fun instructionsGiveTheChoiceOfAHandOff() {
        val sent = JSONObject(LiveProtocol.start(emptyList())).getJSONObject("session").getString("instructions")
        assertEquals(listOf(
            "Если ответ станет понятнее с картинкой, схемой или моделью, которую человек может менять (как что-то устроено, " +
                "как что-то движется, урок с проверкой), передай просьбу через делегирование. " +
                "Примеры: «объясни, как образуется радуга», «как устроен атом», «научи меня таблице умножения на семь».",
            "Простой вопрос с коротким ответом (факт, определение, совет, разговор) отвечай сам сразу и ничего не передавай. " +
                "Примеры: «сколько планет в Солнечной системе», «кто написал „Войну и мир“», «как дела».",
            "Просьбу изменить экран, продолжить урок или проверить себя передавай через делегирование.",
        ), sent.lines().subList(2, 5))
        assertTrue(listOf("сердце", "Луна", "японски").none { it in sent })
    }

    /** Plan D, Da1: the stage shows no text answer, so the voice does not send the person to the screen for it. */
    @Test fun instructionsDoNotSayTheFullAnswerIsOnTheScreen() {
        assertFalse("Полный ответ пользователь видит на экране." in LiveProtocol.INSTRUCTIONS)
    }

    @Test fun historyKeepsNewestCompleteTextWithinLimits() {
        val items = LiveProtocol.history((0 until 200).map { message(it) })
        assertTrue(items.length() <= 128)
        val chars = (0 until items.length()).sumOf { items.item(it).getString("text").length }
        assertTrue(chars + LiveProtocol.INSTRUCTIONS.length <= 18432)
        val last = items.item(items.length() - 1)
        assertTrue(last.getString("text").startsWith("m199"))
        assertEquals("output_text", last.getString("type"))
        assertEquals("input_text", items.item(items.length() - 2).getString("type"))
    }

    @Test fun historySkipsAnOversizeNewestMessageAndKeepsOlderOnes() {
        val huge = Message("big", "user", "x".repeat(20000), 9, "complete")
        val items = LiveProtocol.history(listOf(message(0), message(1), huge))
        assertEquals(2, items.length())
    }

    @Test fun historySkipsUnfinishedMessages() {
        val items = LiveProtocol.history(listOf(message(0), message(1, "error"), message(2, "queued"), message(3, "streaming"), message(4)))
        assertEquals(2, items.length())
    }

    /** The chat saves a sent request as `sent`, so a user message counts at `sent` or `complete` (ruling R13). */
    @Test fun historyKeepsSentUserMessages() {
        val items = LiveProtocol.history(listOf(message(0, "sent"), message(1), message(2, "pending"), message(3, "error"),
            message(4, "queued"), message(5, "sent"), message(6)))
        assertEquals(listOf("m0", "m1", "m6"), (0 until items.length()).map { items.item(it).getString("text").take(2) })
    }

    @Test fun audioIsBase64LittleEndian() {
        val json = JSONObject(LiveProtocol.audio(shortArrayOf(1, -2)))
        assertEquals("session.input_audio.append", json.getString("type"))
        assertEquals("AQD+/w==", json.getString("audio"))
    }

    @Test fun clientEventsCarryTheirIds() {
        val c = JSONObject(LiveProtocol.commentary("e1", "d1", "text"))
        assertEquals("session.commentary.append", c.getString("type"))
        assertEquals("e1", c.getString("event_id"))
        assertEquals("d1", c.getString("delegation_id"))
        assertTrue(JSONObject(LiveProtocol.thinking("e2", "t")).isNull("delegation_id"))
        assertEquals("session.input_audio.mute", JSONObject(LiveProtocol.mute("e3")).getString("type"))
        assertEquals("session.input_audio.unmute", JSONObject(LiveProtocol.unmute("e4")).getString("type"))
        assertEquals("session.close", JSONObject(LiveProtocol.close()).getString("type"))
    }

    @Test fun parseReadsTheSpikeEvents() {
        fun p(s: String) = LiveProtocol.parse(s)
        assertEquals(LiveEvent.Started("live_u1_x"), p("""{"type":"session.started","event_id":"event_1","session":{"id":"live_u1_x","model":"gpt-live-1","status":"active","input":[]}}"""))
        assertEquals(listOf<Short>(1, -2), (p("""{"type":"session.output_audio.delta","delta":"AQD+/w=="}""") as LiveEvent.OutputAudio).samples.toList())
        assertEquals(LiveEvent.InputTranscript("Поставь таймер", 2000, 3000), p("""{"type":"session.input_transcript.delta","delta":"Поставь таймер","start_ms":2000,"end_ms":3000}"""))
        assertEquals(LiveEvent.OutputTranscript("Сейчас поставлю", 4100, 4900), p("""{"type":"session.output_transcript.delta","delta":"Сейчас поставлю","start_ms":4100,"end_ms":4900}"""))
        assertEquals(LiveEvent.Delegation("item_abc", 3200), p("""{"type":"session.delegation.created","offset_ms":3200,"delegation":{"id":"item_abc","type":"delegation","target":"client"}}"""))
        assertEquals(LiveEvent.Appended("commentary_1"), p("""{"type":"session.commentary.appended","start_ms":2600,"end_ms":2800,"event_id":"event_x","client_event_id":"commentary_1"}"""))
        assertEquals(LiveEvent.Closed("close_requested"), p("""{"type":"session.closed","reason":"close_requested","session":{"id":"s","status":"active"},"usage":{"seconds":28}}"""))
        assertEquals(LiveEvent.Closed("expired"), p("""{"type":"session.closed","reason":"expired"}"""))
        assertEquals(LiveEvent.Failure("Invalid value", "commentary_1"), p("""{"type":"error","error":{"type":"invalid_request_error","message":"Invalid value","event_id":"commentary_1"}}"""))
        assertNull((p("""{"type":"error"}""") as LiveEvent.Failure).clientEventId)
        assertEquals(LiveEvent.Other, p("""{"type":"session.usage.updated","usage":{"seconds":15}}"""))
        assertEquals(LiveEvent.Other, p("not json"))
        assertEquals(LiveEvent.Other, p("""{"a":1}"""))
    }

    @Test fun spokenCutsLongMarkdownAtSentenceEnd() {
        val block = "# Title\n\nSome **bold** text with a [link](https://example.com) and `code`. Next sentence is here!\n\n```kotlin\nval x = 1\n```\n\n"
        val md = block.repeat(25) + "tail"
        assertTrue(md.length > 3000)
        val out = LiveProtocol.spoken(md)
        assertTrue(out.length <= 1200)
        assertTrue(out.last() in ".!?")
        listOf("`", "*", "#", "[", "](").forEach { assertFalse(it, out.contains(it)) }
    }

    @Test fun spokenCutsAtAWordWithoutSentenceEnd() {
        val out = LiveProtocol.spoken("слово ".repeat(500), max = 100)
        assertTrue(out.length <= 100)
        assertTrue(out.endsWith("."))
        assertEquals("", LiveProtocol.spoken("   "))
    }

    @Test fun spokenKeepsShortTextWhole() {
        assertEquals("Готово. Сейчас погода.", LiveProtocol.spoken("Готово. Сейчас погода"))
        assertEquals("Ровно здесь.", LiveProtocol.spoken("Ровно здесь.", max = 12))
    }

    @Test fun spokenDoesNotCutInsideADecimalOrEllipsis() {
        val out = LiveProtocol.spoken("Курс 3.5 процента и еще... " + "слово ".repeat(50), max = 24)
        assertTrue(out, out.endsWith("."))
        assertFalse(out, out.endsWith("3."))
        assertTrue(out, out.startsWith("Курс 3.5"))
    }

    /** Plan "one scene", K1: the start holds one trigger for each transcript side with the questions of the decisions probe. */
    @Test fun theStartHoldsTheTwoTriggers() {
        assertFalse(JSONObject(LiveProtocol.start(emptyList())).getJSONObject("session").has("triggers"))
        val triggers = JSONObject(LiveProtocol.start(emptyList(), LiveProtocol.triggers())).getJSONObject("session").getJSONArray("triggers")
        fun questions(i: Int) = triggers.getJSONObject(i).also { assertEquals("decisions", it.getString("type")) }
            .getJSONObject("decisions").getJSONArray("questions").let { list -> List(list.length()) { list.getJSONObject(it) } }
        fun choices(question: JSONObject) = question.getJSONArray("choices").let { list -> List(list.length()) { list.getJSONObject(it).getString("value") } }
        assertEquals(listOf("session.input_transcript.delta", "session.output_transcript.delta"), List(triggers.length()) { triggers.getJSONObject(it).getString("event_type") })
        assertEquals(listOf("user_command", "user_step"), questions(0).map { it.getString("name") })
        // Block 2 plan, V1: the choice continue walks the rest of the steps.
        assertEquals(listOf("next", "continue", "back", "repeat", "go_to", "none"), choices(questions(0)[0]))
        val steps = listOf("1", "2", "3", "4", "5", "6", "none")
        assertEquals(steps, choices(questions(0)[1]))
        assertEquals(listOf("spoken_step"), questions(1).map { it.getString("name") })
        assertEquals(steps, choices(questions(1)[0]))
        assertTrue((questions(0) + questions(1)).all { it.getString("type") == "choice" && it.getString("instructions").isNotBlank() })
    }

    /** K1: a decision gives the confidence as the probability of the chosen value, and each transcript piece keeps its event id. */
    @Test fun aDecisionEventGivesItsAnswers() {
        val decision = LiveProtocol.parse(liveDecision("piece_1", true, classified("user_command", "next", 0.83), classified("user_step", "none", 0.4), inputTokens = 210))
        assertEquals(LiveEvent.Decision("piece_1", true, listOf(ClassifierAnswer("user_command", "next", 0.83), ClassifierAnswer("user_step", "none", 0.4)), 210), decision)
        assertEquals(false, (LiveProtocol.parse(liveDecision("piece_2", false, classified("spoken_step", "2", 0.6))) as LiveEvent.Decision).input)
        assertEquals("piece_3", (LiveProtocol.parse(inputDelta("Дальше", 0, 300, "piece_3")) as LiveEvent.InputTranscript).eventId)
        assertEquals("piece_4", (LiveProtocol.parse(outputDelta("Гвоздь", 0, 300, "piece_4")) as LiveEvent.OutputTranscript).eventId)
        assertNull((LiveProtocol.parse(inputDelta("Дальше", 0, 300)) as LiveEvent.InputTranscript).eventId)
    }

    /** An answer with no choice (a refusal) is skipped, and the other answers of the event stay. */
    @Test fun aRefusalAnswerIsSkipped() {
        val line = """{"type":"decision.event","source_event_id":"piece_1","source_event_type":"session.input_transcript.delta","event":{"type":"decision.completed","decision":{"answers":[{"type":"refusal","name":"user_command","refusal":"no answer"},{"type":"choice","name":"user_step","choice":"2","probabilities":[{"value":"2","probability":0.7}]}],"usage":{"input_tokens":90}}}}"""
        assertEquals(LiveEvent.Decision("piece_1", true, listOf(ClassifierAnswer("user_step", "2", 0.7)), 90), LiveProtocol.parse(line))
    }

    /**
     * Real lines of the decisions probe of 2026-11-22 (run mix-mwrqudxz): the first decision event, the scene item answer, and the first
     * received input piece. The confidence of an answer is the probability of its chosen value, not its confidence field (0.44 and 0.64).
     */
    @Test fun recordedProbeLinesParse() {
        val decision = """{"t":3307,"dir":"recv","event_id":"event_EmzsghKYC6EyTZJm40ey9","type":"decision.event","delegation_id":null,"event":{"type":"decision.completed","decision":{"model":"gpt-6-luna","answers":[{"type":"choice","name":"user_command","choice":"none","probabilities":[{"value":"next","probability":0.09},{"value":"back","probability":0.14},{"value":"repeat","probability":0.14},{"value":"go_to","probability":0.08},{"value":"none","probability":0.55}],"confidence":0.44},{"type":"choice","name":"user_step","choice":"none","probabilities":[{"value":"1","probability":0.17},{"value":"2","probability":0.04},{"value":"3","probability":0.02},{"value":"4","probability":0.04},{"value":"5","probability":0.03},{"value":"6","probability":0.01},{"value":"none","probability":0.69}],"confidence":0.64}],"usage":{"input_tokens":494,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output_tokens":0,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":494}}},"source_event_id":"event_EmzsgfS6RQd8vkD0Npitj","source_event_type":"session.input_transcript.delta","trigger_id":"trg_EmzsgX4qjPkDrRPBQ5Msu"}"""
        val item = """{"t":1676,"dir":"recv","event_id":"event_EmzsfGt405MdiDEcy75UV","type":"decision.item.created","client_event_id":"item_2","item":{"type":"message","role":"user","content":[{"type":"input_text","text":"На экране сцена «Как работает сердце». Шаги сцены:\n1. Из тела: Кровь с малым содержанием кислорода возвращается из тела в сердце.\n2. В лёгкие: Сердце направляет кровь в лёгкие, где она насыщается кислородом.\n3. Из лёгких: Из лёгких насыщенная кислородом кровь возвращается в сердце.\n4. В тело: Сердце сокращается и выталкивает кровь к органам и тканям тела."}],"id":"msg_02da6e597e09347e016b02cf910f008190805516e0b1bd0a03"}}"""
        val delta = """{"t":3155,"dir":"recv","type":"session.input_transcript.delta","start_ms":1600,"end_ms":1800,"delta":" Объясни","event_id":"event_EmzsgfS6RQd8vkD0Npitj"}"""
        assertEquals(LiveEvent.Decision("event_EmzsgfS6RQd8vkD0Npitj", true,
            listOf(ClassifierAnswer("user_command", "none", 0.55), ClassifierAnswer("user_step", "none", 0.69)), 494), LiveProtocol.parse(decision))
        assertEquals(LiveEvent.ItemCreated("item_2", "msg_02da6e597e09347e016b02cf910f008190805516e0b1bd0a03"), LiveProtocol.parse(item))
        assertEquals("event_EmzsgfS6RQd8vkD0Npitj", (LiveProtocol.parse(delta) as LiveEvent.InputTranscript).eventId)
    }

    /** K2 and K8: the scene item, its created answer, and the failed client event id in each error shape. */
    @Test fun itemEventsAndErrorIds() {
        val create = JSONObject(LiveProtocol.itemCreate("event_a", "Сцена"))
        assertEquals("decision.item.create", create.getString("type"))
        val item = create.getJSONObject("item")
        assertEquals(listOf("message", "user"), listOf(item.getString("type"), item.getString("role")))
        assertEquals("input_text" to "Сцена", item.getJSONArray("content").getJSONObject(0).let { it.getString("type") to it.getString("text") })
        assertEquals("item_1", JSONObject(LiveProtocol.itemDelete("event_b", "item_1")).getString("item_id"))
        assertEquals(LiveEvent.ItemCreated("event_a", "item_9"), LiveProtocol.parse(liveItemCreated("event_a", "item_9")))
        fun id(json: String) = (LiveProtocol.parse(json) as LiveEvent.Failure).clientEventId
        assertEquals("event_c", id("""{"type":"error","error":{"message":"bad","client_event_id":"event_c"}}"""))
        assertEquals("event_d", id("""{"type":"error","client_event_id":"event_d","error":{"message":"bad"}}"""))
        assertEquals("event_e", id("""{"type":"error","error":{"message":"bad","event_id":"event_e"}}"""))
        val coded = LiveProtocol.parse("""{"type":"error","error":{"message":"bad","code":"invalid_decision","param":"session.triggers"}}""") as LiveEvent.Failure
        assertEquals("invalid_decision" to "session.triggers", coded.code to coded.param)
    }
}

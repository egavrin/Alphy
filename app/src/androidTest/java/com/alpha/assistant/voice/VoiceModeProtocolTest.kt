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
}

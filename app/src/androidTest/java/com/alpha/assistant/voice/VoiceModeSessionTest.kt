package com.alpha.assistant.voice

import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.providerFailure
import com.alpha.assistant.data.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The live voice session of spec 5.3.2, 5.4.5, 5.4.6, 5.8 and 5.8.1, with a scripted socket and scripted audio. */
class VoiceModeSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val random = Random(7)

    @After fun cancel() = scope.cancel()

    /** Records each host call as one line, in order. */
    private class Host(private val messages: List<Message>) : LiveVoiceHost {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var running = false
        override fun history() = messages
        override fun delegation(id: String, request: String) { calls += "delegation:$id:$request" }
        override fun turn(turn: UserTurn) { calls += "turn:${turn.text}:${turn.startMs}:${turn.endMs}:${turn.overlappedPlayback}" }
        override fun handOffRunning() = running
        override fun appended(eventId: String) { calls += "appended:$eventId" }
        override fun outputEnded(endMs: Long) { calls += "outputEnded:$endMs" }
        override fun ended(status: String, settingsRequired: Boolean) { calls += "ended:$status:$settingsRequired" }
    }

    private inner class Rig(timing: LiveTiming = TIMING, account: suspend () -> AiAccount? = { OPENAI }, transport: LiveTransport? = null,
        endpoints: Endpoints = LOOPBACK, history: List<Message> = emptyList()) {
        val socket = FakeLiveTransport()
        val audio = FakeLiveAudio()
        val host = Host(history)
        val session = LiveVoiceSession(context, scope, transport ?: socket, { audio }, account, endpoints, timing)

        fun start(firstSpeech: Boolean = false) = main { session.start(host, firstSpeech) }
        /** Starts the session and answers `session.started`. */
        fun started(firstSpeech: Boolean = false): Rig {
            start(firstSpeech)
            waitUntil("connect") { socket.connected }
            socket.emit(STARTED)
            waitUntil("session.started") { session.phase == LivePhase.LISTENING }
            return this
        }
        fun appends() = socket.types().count { it == "session.input_audio.append" }
        fun ends() = host.calls.filter { it.startsWith("ended:") }
        fun turns() = host.calls.filter { it.startsWith("turn:") }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    /** Polls [condition] on the main thread. Events from the test thread reach the main thread in order, so this also waits for them. */
    private fun waitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!main(condition)) {
            if (SystemClock.uptimeMillis() > deadline) fail("Timed out waiting for $what")
            SystemClock.sleep(10)
        }
    }

    private fun quiet() = random.pcmBlock(0.002)
    private fun loud() = random.pcmBlock(0.15)
    private fun text(id: Int) = context.getString(id)

    @Test fun startSendsSessionStartWithHistory() {
        val history = listOf(Message("m1", "user", "Привет", 1, "complete"), Message("m2", "assistant", "Здравствуйте", 2, "complete"))
        val rig = Rig(history = history)
        rig.start()
        waitUntil("session.start") { rig.socket.sent.isNotEmpty() }
        assertEquals("ws://127.0.0.1:4000/v1/live/sessions", rig.socket.url)
        // assertTrue keeps even the test key out of a failure message.
        assertTrue("The account key must authorize the socket", rig.socket.key == OPENAI.key)
        val first = JSONObject(rig.socket.sent.first())
        assertEquals("session.start", first.getString("type"))
        assertEquals(JSONObject(LiveProtocol.start(history)).getJSONObject("session").toString(), first.getJSONObject("session").toString())
        assertEquals(LivePhase.CONNECTING, main { rig.session.phase })
        assertTrue(main { rig.session.active })
    }

    @Test fun noAudioBeforeStarted() {
        val rig = Rig()
        rig.start()
        waitUntil("connect") { rig.socket.connected && rig.audio.opened }
        repeat(3) { rig.audio.capture(quiet()) }
        main { }
        assertEquals(LivePhase.CONNECTING, main { rig.session.phase })
        assertEquals("", main { rig.session.status })
        rig.socket.emit(STARTED)
        waitUntil("listening") { rig.session.phase == LivePhase.LISTENING }
        rig.audio.capture(quiet())
        waitUntil("append") { rig.appends() == 1 }
        // Sends keep their order, so a block from before session.started would come before this one.
        assertEquals(listOf("session.start", "session.input_audio.append"), rig.socket.types())
    }

    @Test fun outputAudioPlaysAndSetsSpeaking() {
        val rig = Rig().started()
        rig.audio.playbackActive = true
        rig.socket.emit(outputAudio(1))
        waitUntil("playback") { rig.audio.played.size == 1 }
        assertEquals(listOf<Short>(1, 1, 1, 1), rig.audio.played.single().toList())
        waitUntil("speaking") { rig.session.phase == LivePhase.SPEAKING }
        rig.audio.playbackActive = false
        rig.audio.capture(quiet())
        waitUntil("listening") { rig.session.phase == LivePhase.LISTENING }
    }

    @Test fun bargeInFlushesAndDropsUntilTheNextReply() {
        val rig = Rig(TIMING.copy(replyGapMs = 500)).started()
        rig.audio.playbackActive = true
        rig.socket.emit(outputAudio(1))
        waitUntil("first reply") { rig.audio.played.size == 1 }
        // The detector learns the echo level in its 240 ms grace, then 200 ms of loud blocks are the user.
        repeat(15) { rig.audio.capture(quiet()) }
        repeat(10) { rig.audio.capture(loud()) }
        waitUntil("flush") { rig.audio.flushes.get() == 1 }
        rig.audio.playbackActive = false
        repeat(5) { rig.audio.capture(loud()) }
        waitUntil("user speaking") { rig.session.userSpeaking }
        // The flush emptied the playback, but this turn still started over Alphy's voice.
        rig.socket.emit(inputDelta("Стоп", 100, 400))
        rig.socket.emit(outputAudio(2))
        rig.audio.capture(quiet())
        waitUntil("user stopped") { !rig.session.userSpeaking }
        rig.socket.emit(outputAudio(3))
        rig.socket.emit(outputDelta("Хорошо", 1_000, 1_500))
        main { }
        SystemClock.sleep(700)
        // A second turn before the next reply, longer after the playback than its own length, did not start over Alphy's voice.
        rig.socket.emit(inputDelta("Да", 1_600, 1_900))
        rig.socket.emit(outputAudio(4))
        rig.socket.emit(outputDelta("Понял", 2_000, 2_500))
        waitUntil("next reply") { rig.audio.played.size == 2 && rig.turns().size == 2 }
        // The playback is serial, so a dropped chunk would have played before chunk 4.
        assertEquals(listOf<Short>(1, 4), rig.audio.played.map { it[0] })
        assertEquals(1, rig.audio.flushes.get())
        assertEquals(listOf("turn:Стоп:100:400:true", "turn:Да:1600:1900:false"), rig.turns())
    }

    @Test fun muteSendsEventsAndStopsCapture() {
        val rig = Rig().started()
        rig.audio.capture(quiet())
        waitUntil("append") { rig.appends() == 1 }
        main { rig.session.mute(true) }
        assertTrue(rig.audio.captureMuted)
        assertEquals(LivePhase.MUTED, main { rig.session.phase })
        repeat(3) { rig.audio.capture(quiet()) }
        main { rig.session.mute(false) }
        assertFalse(rig.audio.captureMuted)
        rig.audio.capture(quiet())
        waitUntil("append after unmute") { rig.appends() == 2 }
        assertEquals(listOf("session.start", "session.input_audio.append", "session.input_audio.mute", "session.input_audio.unmute",
            "session.input_audio.append"), rig.socket.types())
        assertEquals(LivePhase.LISTENING, main { rig.session.phase })
    }

    @Test fun awayMutesAndFlushesAndReturnUnmutes() {
        val rig = Rig().started()
        main { rig.session.away(true) }
        assertEquals(1, rig.audio.flushes.get())
        assertTrue(rig.audio.captureMuted)
        assertEquals(LivePhase.MUTED, main { rig.session.phase })
        main { rig.session.away(false) }
        assertFalse(rig.audio.captureMuted)
        assertEquals(LivePhase.LISTENING, main { rig.session.phase })
        assertEquals(listOf("session.start", "session.input_audio.mute", "session.input_audio.unmute"), rig.socket.types())
        // The flush is not a barge-in, so the first turn after the return did not start over Alphy's voice.
        rig.socket.emit(inputDelta("Привет", 0, 500))
        rig.socket.emit(outputDelta("Привет!", 600, 900))
        waitUntil("turn") { rig.turns().isNotEmpty() }
        assertEquals(listOf("turn:Привет:0:500:false"), rig.turns())
        // The return does not undo the user's own mute.
        main { rig.session.mute(true); rig.session.away(true); rig.session.away(false) }
        assertTrue(rig.audio.captureMuted)
        assertEquals(LivePhase.MUTED, main { rig.session.phase })
        assertEquals(listOf("session.start", "session.input_audio.mute", "session.input_audio.unmute", "session.input_audio.mute"),
            rig.socket.types())
    }

    @Test fun endsFollowTheSpecTable() {
        val connection = text(R.string.live_voice_connection)
        val openAiOnly = text(R.string.live_voice_openai_only)
        class Case(val name: String, val status: String, val settingsRequired: Boolean = false, val closeSent: Boolean = true,
            val audioCloses: Int = 1, val socketCloses: Int = 1, val account: suspend () -> AiAccount? = { OPENAI },
            val endpoints: Endpoints = LOOPBACK, val focusGranted: Boolean = true, val timing: LiveTiming = TIMING,
            val started: Boolean = true, val trigger: (Rig) -> Unit = { })
        val cases = listOf(
            Case("no session.started", connection, timing = TIMING.copy(startedMs = 300), started = false) { },
            Case("expired", text(R.string.live_voice_expired), closeSent = false) { it.socket.emit(closed("expired")) },
            Case("content", text(R.string.live_voice_content), closeSent = false) { it.socket.emit(closed("content")) },
            Case("connection_lost", connection, closeSent = false) { it.socket.emit(closed("connection_lost")) },
            Case("socket failure", connection, closeSent = false) { it.socket.fail(null) },
            Case("socket closed", connection, closeSent = false) { it.socket.closeRemote() },
            Case("refused key", providerFailure(AiProvider.OPENAI, 401, null, false).message, settingsRequired = true, closeSent = false) {
                it.socket.fail(401)
            },
            Case("error event", text(R.string.live_voice_failed)) {
                it.socket.emit("""{"type":"error","error":{"message":"Invalid key alpha-test-only"}}""")
            },
            Case("focus loss", text(R.string.live_voice_focus_lost)) { it.audio.loseFocus() },
            Case("no account", openAiOnly, settingsRequired = true, closeSent = false, audioCloses = 0, socketCloses = 0,
                account = { null }, started = false),
            Case("other provider", openAiOnly, settingsRequired = true, closeSent = false, audioCloses = 0, socketCloses = 0,
                account = { AiAccount(AiProvider.ANTHROPIC, "alpha-test-only") }, started = false),
            Case("account read failure", "Ключ не прочитан.", settingsRequired = true, closeSent = false, audioCloses = 0, socketCloses = 0,
                account = { throw CloudFailure("key_read", "Ключ не прочитан.") }, started = false),
            Case("focus denied", text(R.string.live_voice_focus_lost), closeSent = false, socketCloses = 0, focusGranted = false, started = false),
            Case("start error", text(R.string.live_voice_failed), closeSent = false, audioCloses = 0, socketCloses = 0,
                endpoints = Endpoints { "https://example.invalid" }, started = false),
        )
        for (case in cases) {
            val rig = Rig(case.timing, case.account, endpoints = case.endpoints)
            rig.audio.focusGranted = case.focusGranted
            if (case.started) rig.started() else rig.start()
            case.trigger(rig)
            waitUntil(case.name) { !rig.session.active }
            // A late socket close after the end must not end the session again.
            if (rig.socket.connected) rig.socket.closeRemote()
            main { }
            assertEquals(case.name, listOf("ended:${case.status}:${case.settingsRequired}"), rig.ends())
            assertEquals(case.name, case.status, main { rig.session.status })
            assertEquals(case.name, LivePhase.IDLE, main { rig.session.phase })
            assertEquals(case.name, case.audioCloses, rig.audio.closes.get())
            assertEquals(case.name, case.socketCloses, rig.socket.closes.get())
            assertEquals(case.name, case.closeSent, "session.close" in rig.socket.types())
        }
    }

    @Test fun overlapFollowsTheTurnStart() {
        val rig = Rig().started()
        rig.audio.playbackActive = true
        repeat(3) { rig.audio.capture(quiet()) }
        main { }
        rig.audio.playbackActive = false
        rig.audio.capture(quiet())
        main { }
        SystemClock.sleep(500)
        // A delta arrives after its own end: this 800 ms phrase began while the playback still had audio.
        rig.socket.emit(inputDelta("Да", 1_000, 1_800))
        rig.socket.emit(outputDelta("Хорошо", 2_000, 2_300))
        // This 200 ms phrase began after the playback stopped.
        rig.socket.emit(inputDelta("Нет", 3_000, 3_200))
        rig.socket.emit(outputDelta("Ладно", 3_400, 3_600))
        waitUntil("turns") { rig.turns().size == 2 }
        assertEquals(listOf("turn:Да:1000:1800:true", "turn:Нет:3000:3200:false"), rig.turns())
    }

    @Test fun silenceCountsFromTheEndOfAlphysReply() {
        val timing = TIMING.copy(silenceMs = 400, replyGapMs = 200)
        val rig = Rig(timing).started()
        repeat(8) { rig.socket.emit(outputAudio(1)); SystemClock.sleep(100) }
        assertTrue("Arriving output audio keeps the voice mode", main { rig.session.active })
        // The buffered reply still plays after its last chunk.
        rig.audio.playbackActive = true
        SystemClock.sleep(800)
        assertTrue("Playback keeps the voice mode", main { rig.session.active })
        rig.audio.playbackActive = false
        waitUntil("silence end") { !rig.session.active }
        assertEquals(listOf("ended:${text(R.string.live_voice_no_speech)}:false"), rig.ends())
        // Without speech, the mode ends silenceMs after the output end, which comes replyGapMs after the last chunk.
        val quiet = Rig(timing).started()
        quiet.socket.emit(outputAudio(1))
        val last = SystemClock.uptimeMillis()
        SystemClock.sleep(450)
        assertTrue("The count starts again at the output end", main { quiet.session.active })
        waitUntil("silence end after the reply") { !quiet.session.active }
        assertTrue(SystemClock.uptimeMillis() - last >= timing.replyGapMs + timing.silenceMs)
    }

    @Test fun silenceEndsOnlyWithoutRunningHandOff() {
        val rig = Rig(TIMING.copy(silenceMs = 400))
        rig.host.running = true
        rig.started()
        SystemClock.sleep(1_000)
        assertTrue("A running hand-off keeps the voice mode", main { rig.session.active })
        rig.host.running = false
        waitUntil("silence end") { !rig.session.active }
        assertEquals(listOf("ended:${text(R.string.live_voice_no_speech)}:false"), rig.ends())
    }

    @Test fun firstSpeechTimerOnlyForButtonStart() {
        val button = Rig(TIMING.copy(firstSpeechMs = 300)).started(firstSpeech = true)
        val plain = Rig(TIMING.copy(firstSpeechMs = 300)).started()
        val spoken = Rig(TIMING.copy(firstSpeechMs = 600)).started(firstSpeech = true)
        repeat(5) { spoken.audio.capture(quiet()) }
        repeat(10) { spoken.audio.capture(loud()) }
        waitUntil("speech") { spoken.session.userSpeaking }
        waitUntil("first speech end") { !button.session.active }
        assertEquals(listOf("ended:${text(R.string.live_voice_no_first_speech)}:false"), button.ends())
        SystemClock.sleep(900)
        assertTrue("Only a button start has the first speech timer", main { plain.session.active })
        assertTrue("Speech stops the first speech timer", main { spoken.session.active })
    }

    @Test fun delegationWaitsForTheRequestText() {
        val rig = Rig().started()
        rig.socket.emit(delegation("d1", 3_000))
        rig.socket.emit(inputDelta("Поставь таймер", 1_000, 2_500))
        waitUntil("delegation") { rig.host.calls.size == 2 }
        assertEquals(listOf("turn:Поставь таймер:1000:2500:false", "delegation:d1:Поставь таймер"), rig.host.calls)
        // Without text in the wait, the host gets a blank request and answers it.
        rig.socket.emit(delegation("d2", 5_000))
        waitUntil("blank delegation") { rig.host.calls.size == 3 }
        assertEquals("delegation:d2:", rig.host.calls.last())
    }

    @Test fun turnsEndAtOutputDelegationOrGap() {
        val rig = Rig(TIMING.copy(turnGapMs = 300)).started()
        rig.audio.playbackActive = true
        rig.socket.emit(inputDelta("Привет", 0, 800))
        main { }
        rig.audio.playbackActive = false
        rig.socket.emit(outputDelta("Привет!", 1_000, 1_600))
        // The next phrase arrives later after the playback than its own length.
        SystemClock.sleep(400)
        rig.socket.emit(inputDelta("Как", 2_000, 2_300))
        rig.socket.emit(inputDelta(" дела", 2_300, 2_600))
        waitUntil("gap turn") { rig.host.calls.size == 2 }
        // The answer after the gap starts the next request.
        rig.socket.emit(outputDelta("Хорошо.", 2_700, 2_900))
        // Shorter than the 400 ms sleep plus the 300 ms turn gap since the playback stopped.
        rig.socket.emit(inputDelta("Поставь таймер", 3_000, 3_400))
        rig.socket.emit(delegation("d1", 4_000))
        waitUntil("delegation") { rig.host.calls.size == 4 }
        assertEquals(listOf("turn:Привет:0:800:true", "turn:Как дела:2000:2600:false", "turn:Поставь таймер:3000:3400:false",
            "delegation:d1:Поставь таймер"), rig.host.calls)
    }

    @Test fun commentaryAckAndOutputEndReachTheHost() {
        val rig = Rig().started()
        val id = main { rig.session.commentary("d1", "Ответ Alphy: готово.") }
        assertTrue(id.isNotBlank())
        val commentary = JSONObject(rig.socket.sent.last())
        assertEquals("session.commentary.append", commentary.getString("type"))
        assertEquals(id, commentary.getString("event_id"))
        assertEquals("d1", commentary.getString("delegation_id"))
        assertEquals("Ответ Alphy: готово.", commentary.getString("content"))
        rig.socket.emit("""{"type":"session.commentary.appended","client_event_id":"$id"}""")
        waitUntil("appended") { rig.host.calls.isNotEmpty() }
        rig.socket.emit(outputDelta("Готово", 4_000, 4_500))
        rig.socket.emit(outputAudio(1))
        rig.socket.emit(outputDelta(".", 4_500, 5_000))
        rig.socket.emit(outputAudio(2))
        waitUntil("output end") { rig.host.calls.size == 2 }
        SystemClock.sleep(700)
        assertEquals(listOf("appended:$id", "outputEnded:5000"), rig.host.calls)
        main { rig.session.thinking("Пользователь написал: привет.") }
        assertEquals("session.thinking.append", rig.socket.types().last())
    }

    @Test fun rejectedAppendKeepsTheMode() {
        val rig = Rig().started()
        val commentary = main { rig.session.commentary("d1", "Ответ Alphy: готово.") }
        main { rig.session.thinking("Пользователь написал: привет.") }
        val thinking = JSONObject(rig.socket.sent.last()).getString("event_id")
        rig.socket.emit(failure(commentary))
        rig.socket.emit(failure(thinking))
        main { }
        assertTrue("A rejected append keeps the voice mode", main { rig.session.active })
        assertEquals("", main { rig.session.status })
        assertFalse("session.close" in rig.socket.types())
        assertTrue(rig.host.calls.isEmpty())
        // An error for an id that is not an append of this session is a session error.
        rig.socket.emit(failure("event_unknown"))
        waitUntil("unknown id end") { !rig.session.active }
        assertEquals(listOf("ended:${text(R.string.live_voice_failed)}:false"), rig.ends())
        assertTrue("session.close" in rig.socket.types())
    }

    @Test fun sessionRunsOverTheRealTransport() {
        val received = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val type = JSONObject(text).getString("type")
                    received += type
                    if (type == "session.start") webSocket.send(STARTED)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }).build())
            server.start()
            val rig = Rig(transport = OkHttpLiveTransport(), endpoints = Endpoints { server.url("/").toString().trimEnd('/') })
            rig.start()
            assertEquals("session.start", received.poll(5, TimeUnit.SECONDS))
            waitUntil("session.started") { rig.session.phase == LivePhase.LISTENING }
            rig.audio.capture(quiet())
            assertEquals("session.input_audio.append", received.poll(5, TimeUnit.SECONDS))
            main { rig.session.stop() }
            assertEquals("session.close", received.poll(5, TimeUnit.SECONDS))
            assertEquals(1, rig.audio.closes.get())
            SystemClock.sleep(300)
            assertEquals(listOf("ended::false"), rig.host.calls)
        }
    }

    @Test fun stopSendsCloseAndReleasesAudio() {
        val rig = Rig().started()
        main { rig.session.stop() }
        assertEquals("session.close", rig.socket.types().last())
        assertEquals(1, rig.socket.closes.get())
        assertEquals(1, rig.audio.closes.get())
        assertEquals(listOf("ended::false"), rig.host.calls)
        assertFalse(main { rig.session.active })
        assertEquals(LivePhase.IDLE, main { rig.session.phase })
        // A second stop and late calls release and send nothing.
        val sent = rig.socket.sent.size
        main { rig.session.stop(); rig.session.commentary("d1", "Поздний ответ"); rig.session.thinking("Поздно"); rig.session.mute(true) }
        rig.socket.closeRemote()
        main { }
        assertEquals(sent, rig.socket.sent.size)
        assertEquals(1, rig.socket.closes.get())
        assertEquals(1, rig.audio.closes.get())
        assertEquals(1, rig.host.calls.size)
    }

    private companion object {
        const val STARTED = """{"type":"session.started","session":{"id":"live_1"}}"""
        val OPENAI = AiAccount(AiProvider.OPENAI, "alpha-test-only")
        val LOOPBACK = Endpoints { "http://127.0.0.1:4000" }
        val TIMING = LiveTiming(startedMs = 5_000, silenceMs = 30_000, firstSpeechMs = 30_000, requestWaitMs = 400, turnGapMs = 30_000,
            replyGapMs = 300)

        /** Four samples of [value], so a test can tell the chunks apart. */
        fun outputAudio(value: Short): String {
            val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4) { putShort(value) } }.array()
            return JSONObject().put("type", "session.output_audio.delta").put("delta", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString()
        }
        fun inputDelta(delta: String, startMs: Long, endMs: Long) = transcript("session.input_transcript.delta", delta, startMs, endMs)
        fun outputDelta(delta: String, startMs: Long, endMs: Long) = transcript("session.output_transcript.delta", delta, startMs, endMs)
        fun transcript(type: String, delta: String, startMs: Long, endMs: Long) =
            JSONObject().put("type", type).put("delta", delta).put("start_ms", startMs).put("end_ms", endMs).toString()
        fun delegation(id: String, offsetMs: Long) =
            JSONObject().put("type", "session.delegation.created").put("offset_ms", offsetMs).put("delegation", JSONObject().put("id", id)).toString()
        fun closed(reason: String) = JSONObject().put("type", "session.closed").put("reason", reason).toString()
        fun failure(eventId: String) = JSONObject().put("type", "error")
            .put("error", JSONObject().put("message", "Rejected append").put("event_id", eventId)).toString()
    }
}

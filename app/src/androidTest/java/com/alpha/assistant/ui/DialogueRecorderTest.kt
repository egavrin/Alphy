package com.alpha.assistant.ui

import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.tools.AgentScriptProvider
import com.alpha.assistant.tools.Scripted
import com.alpha.assistant.tools.appfile.plan as appFilePlan
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.ScriptedLiveServer
import com.alpha.assistant.voice.WavFeeder
import com.alpha.assistant.voice.wavSamples
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan D, Dd2: one clock of a scripted voice talk, from the end of the recorded speech to the screen on the stage. */
class DialogueRecorderTest {
    private val fixture = ShellFixture("dialogue-recorder")
    private val robot = ShellRobot("dialogue-recorder")
    @After fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
    }

    @Test fun oneClockFromSpeechToScreen() = withPlanStep(fixture.context, AiProvider.OPENAI, true) {
        val recorder = DialogueRecorder()
        val audio = FakeLiveAudio(realTime = true)
        val feeder = WavFeeder(audio)
        val plans = AgentScriptProvider(listOf(Scripted.Text(appFilePlan("lesson"))))
        val chat = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) =
                if (request.generationStage != null) plans.respond(key, request, session, emit)
                else emit(CloudEvent.RoundComplete(JSONObject(chatDecision("screen", ANSWER, "The Moon turns once on each orbit"))
                    .put("show", false).toString(), false))
        }
        fixture.install(chat = recorder.chat(chat),
            liveVoice = fixture.liveVoice(recorder.transport(ScriptedLiveServer(listOf(REQUEST))), audio, Endpoints { "http://127.0.0.1:4000" }))
        fixture.launch().use { scenario ->
            val model = checkNotNull(fixture.model)
            robot.waitUntil { !model.loading }
            scenario.onActivity { model.startLiveVoice() }
            robot.waitUntil(15_000) { model.liveVoice.phase == LivePhase.LISTENING }
            recorder.watch(model)
            feeder.start()
            try {
                val samples = wavSamples(robot.instrumentation.context.assets.open("voice/explain-moon.wav").use { it.readBytes() })
                recorder.speechEnd(feeder.say(samples))
                robot.waitUntil(60_000) { recorder.marks().map { it.name }.containsAll(listOf("voice_out", "screen_visible")) }
                val marks = recorder.marks()
                fun at(name: String, detail: String? = null) =
                    checkNotNull(marks.firstOrNull { it.name == name && (detail == null || it.detail == detail) }) { "No $name $detail in $marks" }.atNanos
                val order = listOf(at("speech_end"), at("delegation"), at("call_start", "routing"), at("commentary"), at("voice_out"))
                assertEquals(marks.toString(), order.sorted(), order)
                assertTrue(marks.toString(), at("call_end", "APP_FILE") < at("screen_visible"))
                assertTrue(marks.toString(), marks.zipWithNext().all { (a, b) -> a.atNanos <= b.atNanos })
                val requests = recorder.report().getJSONArray("requests")
                assertEquals(requests.toString(), 1, requests.length())
                val request = requests.getJSONObject(0)
                assertEquals("screen", request.getString("kind"))
                assertTrue(request.toString(), request.getLong("speechEndToScreenMs") > request.getLong("speechEndToDelegationMs"))
                // The clock holds names only: no word of the request or of the answer.
                val recorded = marks.toString() + recorder.report()
                assertFalse(recorded, listOf("Луна", "Moon", "alpha-test-only").any { it in recorded })
            } finally {
                feeder.stop()
                recorder.close()
                scenario.onActivity { model.endLiveVoice() }
            }
        }
    }

    private companion object {
        const val REQUEST = "Объясни, почему Луна всегда повёрнута к Земле одной стороной."
        const val ANSWER = "Луна делает один оборот вокруг оси за один круг вокруг Земли."
    }
}

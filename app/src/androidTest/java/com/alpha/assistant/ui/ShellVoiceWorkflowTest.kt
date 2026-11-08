package com.alpha.assistant.ui

import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.voice.VoiceSession
import com.offlineassistant.app.voice.AudioTranscription
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ShellVoiceWorkflowTest {
    private val fixture = ShellFixture("shell-voice")
    private val robot = ShellRobot("shell-voice")
    @After fun remove() = fixture.remove()

    @Test fun continuousVoiceStopsWhenLeavingVoiceSettings() {
        installVoiceFixture()
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.allowMicrophone(scenario)
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.click(robot.text(R.string.settings_voice))
            robot.click(robot.text(R.string.voice_start))
            val model = checkNotNull(fixture.model)
            robot.waitFor(robot.text(R.string.voice_stop))
            assertTrue(model.voice.active)
            robot.back()
            robot.waitUntil { !model.voice.active && model.voice.phase == "idle" }
            assertTrue(robot.composed(scenario, "settings-menu"))
        }
    }

    @Test fun continuousVoiceResumesAfterDesktopSendAndTextReply() {
        fixture.provider.gate = CompletableDeferred()
        val captures = AtomicInteger()
        val followUp = CompletableDeferred<AudioTranscription>()
        installVoiceFixture {
            if (captures.incrementAndGet() == 1) question else followUp.await()
        }
        fixture.launch().use { scenario ->
            val model = submitVoiceQuestion(scenario)
            robot.automation.waitForIdle(500, 5000)
            robot.waitFor(checkNotNull(question.text))
            // The Desktop entry leaves composition at the end of the transition.
            robot.waitUntil { !robot.composed(scenario, "desktop") }
            assertTrue(model.voice.active)
            assertEquals("waiting", model.voice.phase)
            fixture.provider.gate!!.complete(Unit)
            robot.waitFor("Fixture response")
            robot.waitUntil { model.voice.phase == "listening" && captures.get() == 2 }
            followUp.complete(AudioTranscription("Следующий вопрос", null, 0))
            robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == "Следующий вопрос" }
            assertEquals(1, fixture.provider.calls)
        }
    }

    @Test fun stoppingWhileWaitingPreventsCaptureAfterTheTextReply() {
        fixture.provider.gate = CompletableDeferred()
        val captures = AtomicInteger()
        installVoiceFixture { captures.incrementAndGet(); question }
        fixture.launch().use { scenario ->
            val model = submitVoiceQuestion(scenario)
            robot.waitUntil { model.voice.phase == "waiting" }
            robot.click(robot.text(R.string.voice_stop))
            fixture.provider.gate!!.complete(Unit)
            robot.waitFor("Fixture response")
            robot.waitUntil { model.activeConversationId == null }
            assertEquals("idle", model.voice.phase)
            assertEquals(1, captures.get())
        }
    }

    @Test fun failedRequestStopsContinuousCapture() {
        fixture.provider.failure = java.io.IOException("Voice fixture request failed")
        val captures = AtomicInteger()
        installVoiceFixture { captures.incrementAndGet(); question }
        fixture.launch().use { scenario ->
            val model = submitVoiceQuestion(scenario)
            robot.waitUntil { model.snapshot.selected?.messages?.lastOrNull()?.status == "error" }
            assertEquals("idle", model.voice.phase)
            assertEquals(1, captures.get())
        }
    }

    private fun submitVoiceQuestion(scenario: ActivityScenario<CloudTestActivity>): ShellViewModel {
        robot.allowMicrophone(scenario)
        val model = checkNotNull(fixture.model)
        robot.waitUntil { !model.loading && robot.composed(scenario, "desktop") }
        scenario.onActivity { model.startVoice(oneShot = false) }
        robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == question.text }
        scenario.onActivity { model.send() }
        return model
    }

    private val question = AudioTranscription("Голосовой вопрос", null, 0)

    private fun installVoiceFixture(capture: suspend () -> AudioTranscription = { question }) {
        fixture.install(voice = { app, scope, transcript ->
            VoiceSession(app, scope, transcript, prepareModels = {}, captureAudio = capture)
        })
    }

}

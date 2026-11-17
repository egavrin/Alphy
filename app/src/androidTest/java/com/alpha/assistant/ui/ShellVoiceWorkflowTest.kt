package com.alpha.assistant.ui

import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.voice.FakeSpeechInput
import com.alpha.assistant.voice.SpeechInput
import com.alpha.assistant.voice.VoiceSession
import com.alpha.assistant.voice.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        val followUp = CompletableDeferred<String?>()
        installVoiceFixture {
            if (captures.incrementAndGet() == 1) question else followUp.await()
        }
        fixture.launch().use { scenario ->
            val model = submitVoiceQuestion(scenario)
            robot.automation.waitForIdle(500, 5000)
            robot.waitFor(question)
            // The Desktop entry leaves composition at the end of the transition.
            robot.waitUntil { !robot.composed(scenario, "desktop") }
            assertTrue(model.voice.active)
            assertEquals("waiting", model.voice.phase)
            fixture.provider.gate!!.complete(Unit)
            robot.waitFor("Fixture response")
            robot.waitUntil { model.voice.phase == "listening" && captures.get() == 2 }
            followUp.complete("Следующий вопрос")
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

    @Test fun clearingTheKeyStopsContinuousVoice() = keyChangeStopsVoice { it.clearKey() }

    @Test fun replacingTheKeyStopsContinuousVoice() = keyChangeStopsVoice { it.saveKey("sk-proj-replacement-test-only", AiProvider.OPENAI) {} }

    /** A continuous session listens in the paired layout while [change] edits the account. The session ends and its input gets cancel(). */
    private fun keyChangeStopsVoice(change: (ShellViewModel) -> Unit) {
        val input = object : SpeechInput {
            @Volatile var listening = false
            val cancels = AtomicInteger()
            override suspend fun capture(oneShot: Boolean, onLevel: (Float) -> Unit, onRecognizing: () -> Unit): String? { listening = true; awaitCancellation() }
            override fun finish() = Unit
            override fun cancel() { cancels.incrementAndGet() }
        }
        fixture.install(voice = { app, scope, transcript -> VoiceSession(app, scope, transcript, VoiceSettings(app, fixture.voiceStore)) { input } })
        fixture.launch().use { scenario ->
            val model = desktopModel(scenario)
            scenario.onActivity { model.startVoice(oneShot = false) }
            robot.waitUntil { model.voice.active && input.listening }
            scenario.onActivity { change(model) }
            robot.waitUntil { !model.voice.active && model.voice.phase == "idle" }
            assertEquals(1, input.cancels.get())
        }
    }

    @Test fun preparingShowsUntilCaptureStarts() = heldVoiceStart { _, model, ready ->
        robot.waitFor(robot.text(R.string.voice_preparing))
        assertFalse(model.voice.active)
        ready.countDown()
        robot.waitUntil { model.snapshot.draft.text == question }
    }

    @Test fun secondStartWhilePreparingChangesNothing() = heldVoiceStart { scenario, model, ready ->
        val course = CourseComposerState(4096, {}, {})
        scenario.onActivity { model.startComposerVoice(oneShot = true, destination = course) }
        assertTrue(model.voiceBelongsTo(null))
        ready.countDown()
        robot.waitUntil { model.snapshot.draft.text == question }
        assertTrue(course.draft.text.isEmpty())
    }

    /** Starts one-shot voice input whose account read waits for `ready`, so [block] runs before the capture starts. */
    private fun heldVoiceStart(block: (ActivityScenario<CloudTestActivity>, ShellViewModel, CountDownLatch) -> Unit) {
        val entered = CountDownLatch(1)
        val ready = CountDownLatch(1)
        fixture.install(voice = { app, scope, transcript ->
            VoiceSession(app, scope, transcript, VoiceSettings(app, fixture.voiceStore)) { entered.countDown(); ready.await(10, TimeUnit.SECONDS); FakeSpeechInput { question } }
        })
        try {
            fixture.launch().use { scenario ->
                val model = desktopModel(scenario)
                scenario.onActivity { model.startVoice() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                block(scenario, model, ready)
            }
        } finally { ready.countDown() }
    }

    /** The loaded shell on the Desktop with the microphone permission. */
    private fun desktopModel(scenario: ActivityScenario<CloudTestActivity>): ShellViewModel {
        robot.allowMicrophone(scenario)
        val model = checkNotNull(fixture.model)
        robot.waitUntil { !model.loading && robot.composed(scenario, "desktop") }
        return model
    }

    private fun submitVoiceQuestion(scenario: ActivityScenario<CloudTestActivity>): ShellViewModel {
        val model = desktopModel(scenario)
        scenario.onActivity { model.startVoice(oneShot = false) }
        robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == question }
        scenario.onActivity { model.send() }
        return model
    }

    private val question = "Голосовой вопрос"

    private fun installVoiceFixture(capture: suspend () -> String? = { question }) {
        fixture.install(voice = { app, scope, transcript ->
            VoiceSession(app, scope, transcript, VoiceSettings(app, fixture.voiceStore)) { FakeSpeechInput(capture) }
        })
    }

}

package com.alpha.assistant.ui

import com.alpha.assistant.R

import com.alpha.assistant.voice.VoiceSession
import com.offlineassistant.app.voice.AudioTranscription
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun continuousVoiceSurvivesDesktopSend() {
        fixture.provider.gate = CompletableDeferred()
        installVoiceFixture()
        fixture.launch().use { scenario ->
            robot.allowMicrophone(scenario)
            val model = fixture.model!!
            robot.waitUntil { !model.loading }
            robot.instrumentation.runOnMainSync { model.startVoice(oneShot = false) }
            robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == "Голосовой вопрос" }
            robot.waitUntil { robot.composed(scenario, "desktop") }
            robot.instrumentation.runOnMainSync { model.send() }
            robot.automation.waitForIdle(500, 5000)
            robot.waitFor("Голосовой вопрос")
            // The Desktop entry leaves composition at the end of the transition.
            robot.waitUntil { !robot.composed(scenario, "desktop") }
            assertTrue(model.voice.active)
            assertEquals("waiting", model.voice.phase)
            fixture.provider.gate!!.complete(Unit)
            // Only an active session reaches the speech step, and the fake models have no synthesizer.
            robot.waitUntil { model.voice.status == "Не удалось озвучить ответ. Текст ответа сохранён в чате." }
        }
    }
    private fun installVoiceFixture() {
        fixture.install(voice = { app, scope, transcript ->
            VoiceSession(app, scope, transcript, prepareModels = {}, captureAudio = { AudioTranscription("Голосовой вопрос", null, 0) })
        })
    }

}

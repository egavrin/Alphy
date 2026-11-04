package com.alpha.assistant.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
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

    // During the fade, the Chat entry covers the Desktop entry, and the accessibility tree omits covered nodes.
    // So the test reads the composed nodes. The greeting leaves composition with the Desktop entry at the end of the transition.
    private fun desktopComposed(scenario: ActivityScenario<*>): Boolean {
        fun root(view: View): ViewRootForTest? = view as? ViewRootForTest
            ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
        var composed = false
        scenario.onActivity { activity ->
            composed = root(activity.window.decorView)!!.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                .any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Привет." } == true }
        }
        return composed
    }

    @Test fun continuousVoiceSurvivesDesktopSend() {
        fixture.provider.gate = CompletableDeferred()
        fixture.install(voice = { app, scope, transcript ->
            VoiceSession(app, scope, transcript, prepareModels = {}, captureAudio = { AudioTranscription("Голосовой вопрос", null, 0) })
        })
        fixture.launch().use { scenario ->
            robot.allowMicrophone(scenario)
            val model = fixture.model!!
            robot.waitUntil { !model.loading }
            robot.instrumentation.runOnMainSync { model.startVoice(oneShot = false) }
            robot.waitUntil { model.voice.phase == "review" && model.snapshot.draft.text == "Голосовой вопрос" }
            robot.instrumentation.runOnMainSync { model.send() }
            robot.automation.waitForIdle(500, 5000)
            robot.waitFor("Голосовой вопрос")
            robot.waitUntil { !desktopComposed(scenario) }
            assertTrue(model.voice.active)
            assertEquals("waiting", model.voice.phase)
            fixture.provider.gate!!.complete(Unit)
            // Only an active session reaches the speech step, and the fake models have no synthesizer.
            robot.waitUntil { model.voice.status == "Не удалось озвучить ответ. Текст ответа сохранён в чате." }
        }
    }
}

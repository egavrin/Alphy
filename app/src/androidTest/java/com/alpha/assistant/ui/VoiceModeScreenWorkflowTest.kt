package com.alpha.assistant.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.agent.AgentPhase
import com.alpha.assistant.agent.PhoneActionEvidence
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.providerFailure
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/** The voice mode screen over the full chat (spec 5.1.2, 5.2, 5.7, 5.8, 5.8.1): the real shell with a scripted GPT-Live socket and audio. */
class VoiceModeScreenWorkflowTest {
    private val fixture = ShellFixture("voice-mode-screen")
    private val robot = ShellRobot("voice-mode-screen")
    private val socket = FakeLiveTransport()
    private val audio = FakeLiveAudio()
    @After fun remove() {
        fixture.remove()
        fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
    }

    /** Spec 5.1.1 and 5.1.2: the voice mode button replaces the disabled send button for an OpenAI account. */
    @Test fun buttonOnlyForOpenAiWithEmptyField() {
        install()
        fixture.launch().use { scenario ->
            val model = ready(scenario)
            robot.waitFor(voiceButton)
            assertNull(robot.find(send))
            robot.edit(robot.text(R.string.composer_label), "Привет")
            robot.waitFor(send)
            assertNull(robot.find(voiceButton))
            scenario.onActivity { model.editDraft(model.snapshot.draft.copy(text = "")) }
            robot.waitFor(voiceButton)
            // The key panel saves a DeepSeek key, so accountProvider becomes DEEPSEEK.
            scenario.onActivity { model.saveKey(fakeDeepSeekKey(), AiProvider.DEEPSEEK) {} }
            robot.waitUntil { model.accountProvider == AiProvider.DEEPSEEK }
            robot.waitFor(send)
            assertNull(robot.find(voiceButton))
        }
    }

    /** Spec 5.2.3 to 5.2.5: the card of the hand-off, the status line, and the microphone and end controls. */
    @Test fun layerShowsCardStatusAndControls() {
        fixture.provider.gate = CompletableDeferred()
        install()
        voiceMode { scenario, _ ->
            robot.waitFor(robot.text(R.string.live_voice_listening))
            socket.say("Какая погода", "d1", 100)
            robot.waitFor(robot.text(R.string.live_voice_working))
            checkNotNull(fixture.provider.gate).complete(Unit)
            robot.waitUntil { inLayer(scenario, "Fixture response") }
            robot.waitFor(robot.text(R.string.live_voice_listening))
            robot.waitFor(robot.text(R.string.live_voice_end))
            robot.click(robot.text(R.string.live_voice_mute))
            robot.waitFor(robot.text(R.string.live_voice_muted))
            robot.waitFor(robot.text(R.string.live_voice_unmute))
            assertTrue(audio.captureMuted)
        }
    }

    /** Spec 5.8: an end closes the layer, and the composer status line shows the end status. */
    @Test fun endStatusShowsInTheComposer() {
        install()
        voiceMode { scenario, model ->
            socket.closeRemote()
            robot.waitUntil { !robot.composed(scenario, LAYER) }
            robot.waitFor(robot.text(R.string.live_voice_connection))
            assertFalse(model.liveVoice.active)
            assertNull(robot.find(robot.text(R.string.open_settings)))
            // Spec 5.8: a refused key ends the mode with the provider text and the «Сервис ИИ» link.
            start(model)
            socket.fail(401)
            robot.waitUntil { !robot.composed(scenario, LAYER) }
            robot.waitFor(providerFailure(AiProvider.OPENAI, 401, null, false).message)
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
        }
    }

    /** The hand-off of a phone plan shows the plan card in the layer, as the chat does. */
    @Test fun phonePlanCardShowsInTheLayer() {
        fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence(PLAN_REQUEST, PLAN_REQUEST))
        fixture.provider.agentProposal = ADD
        install()
        voiceMode { scenario, model ->
            socket.say(PLAN_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { model.agent.plan?.phase == AgentPhase.REVIEW }
            robot.waitUntil { inLayer(scenario, robot.text(R.string.agent_plan_title)) }
            assertTrue(inLayer(scenario, robot.text(R.string.agent_execute)))
        }
    }

    /** Spec 5.2.1 and the 5.8 table: ✕ and Back end the mode without a status, and the chat under the layer shows the saved turns. */
    @Test fun endClosesTheLayer() {
        install()
        voiceMode { scenario, model ->
            socket.say("Какая погода", "d1", 100)
            robot.waitUntil { inLayer(scenario, "Fixture response") }
            val chat = checkNotNull(model.snapshot.selectedId)
            robot.click(robot.text(R.string.live_voice_end))
            robot.waitUntil { !model.liveVoice.active }
            assertEquals("", model.liveVoice.status)
            robot.waitUntil { !robot.composed(scenario, LAYER) }
            robot.waitFor("Какая погода")
            robot.waitFor("Fixture response")
            start(model)
            robot.back()
            robot.waitUntil { !model.liveVoice.active }
            assertEquals("", model.liveVoice.status)
            // The chat's own Back selects no chat, so the same chat shows that only the layer took Back.
            assertEquals(chat, model.snapshot.selectedId)
            robot.waitUntil { !robot.composed(scenario, LAYER) }
            robot.waitFor("Fixture response")
        }
    }

    /** Review Focus 1: a configuration change keeps the session and its socket. */
    @Test fun rotationKeepsTheSession() {
        install()
        voiceMode { scenario, model ->
            scenario.recreate()
            robot.waitFor(robot.text(R.string.live_voice_end))
            assertTrue(model.liveVoice.active)
            assertEquals(0, socket.closes.get())
            assertFalse("session.close" in socket.types())
            robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
            assertFalse(audio.captureMuted)
        }
    }

    /**
     * Spec 5.8.1: an app that Alphy opens mutes the microphone and keeps the session, and Home ends it. The Clock start is blocked
     * without a result, so no timer exists on the device. ActivityScenario then moves Alphy through the real pause and stop that
     * Clock causes.
     */
    @Test fun homeEndsButAlphyScreenOnlyMutes() {
        fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence(TIMER_REQUEST, "five-minute"))
        fixture.provider.agentProposal = TIMER
        install()
        voiceMode { scenario, model ->
            socket.say(TIMER_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { model.agent.plan?.phase == AgentPhase.REVIEW }
            blocked(AlarmClock.ACTION_SET_TIMER) { clock ->
                robot.reveal(robot.text(R.string.agent_execute))
                robot.click(robot.text(R.string.agent_execute))
                robot.waitUntil { clock.hits == 1 }
                // Clock comes to the front within the 5 s of the expectation: Alphy pauses and stops.
                awayAndBack(scenario, model, Lifecycle.State.CREATED)
            }
            home()
            robot.waitUntil { !model.liveVoice.active }
            assertEquals("", model.liveVoice.status)
        }
    }

    /**
     * Spec 5.8.1: the permission dialog of a plan card pauses Alphy without a stop, so it mutes and keeps the session. The request is
     * blocked without a result, so no permission changes. The card asks only while CAMERA is not granted, as on the test AVD.
     */
    @Test fun permissionDialogOnlyMutes() {
        assumeFalse("CAMERA is granted, so the plan card shows no permission request",
            fixture.context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence(SELFIE_REQUEST, "selfie"))
        fixture.provider.agentProposal = SELFIE
        install()
        voiceMode { scenario, model ->
            socket.say(SELFIE_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { model.agent.plan?.phase == AgentPhase.NEEDS_PERMISSION }
            blocked(REQUEST_PERMISSIONS) { dialog ->
                robot.reveal(robot.text(R.string.agent_permission_allow))
                robot.click(robot.text(R.string.agent_permission_allow))
                robot.waitUntil { dialog.hits == 1 }
                awayAndBack(scenario, model, Lifecycle.State.STARTED)
            }
        }
    }

    /** The expectation lapses after 5 s, so a stop without a launch ends the mode. */
    @Test fun staleExpectationDoesNotKeepTheSession() {
        install()
        voiceMode { scenario, model ->
            scenario.onActivity { model.expectAlphyScreen() }
            SystemClock.sleep(5_100)
            home()
            robot.waitUntil { !model.liveVoice.active }
            assertEquals("", model.liveVoice.status)
        }
    }

    /** The 5.8 table: the screen off ends the mode also while an expected screen keeps Alphy stopped. */
    @Test fun screenOffEndsTheModeWhileAway() {
        val power = fixture.context.getSystemService(PowerManager::class.java)
        install()
        voiceMode { scenario, model ->
            scenario.onActivity { model.expectAlphyScreen() }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(model.liveVoice.active)
            try {
                robot.shell("input keyevent KEYCODE_SLEEP")
                robot.waitUntil { !model.liveVoice.active }
                assertEquals("", model.liveVoice.status)
            } finally {
                robot.shell("input keyevent KEYCODE_WAKEUP")
                robot.shell("wm dismiss-keyguard")
                robot.waitUntil { power.isInteractive }
            }
        }
    }

    /** Spec 5.2.6: FLAG_KEEP_SCREEN_ON while the layer shows. */
    @Test fun screenStaysOn() {
        install()
        fixture.launch().use { scenario ->
            val model = ready(scenario)
            assertFalse(keepScreenOn(scenario))
            start(model)
            robot.waitUntil { keepScreenOn(scenario) }
            robot.click(robot.text(R.string.live_voice_end))
            robot.waitUntil { !keepScreenOn(scenario) }
        }
    }

    private val voiceButton get() = robot.text(R.string.live_voice)
    private val send get() = robot.text(R.string.composer_send)

    private fun install() = fixture.install(
        liveVoice = fixture.liveVoice(socket, audio))

    private fun ready(scenario: ActivityScenario<CloudTestActivity>): ShellViewModel {
        robot.allowMicrophone(scenario)
        val model = checkNotNull(fixture.model)
        robot.waitUntil { !model.loading && model.accountProvider == AiProvider.OPENAI }
        return model
    }

    /** Launches the shell and starts the voice mode with the composer button. */
    private fun voiceMode(test: (ActivityScenario<CloudTestActivity>, ShellViewModel) -> Unit) = fixture.launch().use { scenario ->
        val model = ready(scenario)
        start(model)
        test(scenario, model)
    }

    /** Taps the voice mode button and answers `session.started`. */
    private fun start(model: ShellViewModel) {
        val starts = socket.types().count { it == "session.start" }
        robot.click(voiceButton)
        robot.waitUntil { socket.types().count { it == "session.start" } == starts + 1 }
        socket.emit(LIVE_STARTED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
    }

    /** Alphy goes to [state] while the expected screen is in front: muted and flushed, still active. Back in front, it unmutes. */
    private fun awayAndBack(scenario: ActivityScenario<CloudTestActivity>, model: ShellViewModel, state: Lifecycle.State) {
        val flushes = audio.flushes.get()
        scenario.moveToState(state)
        robot.instrumentation.runOnMainSync { }
        assertTrue(model.liveVoice.active)
        assertEquals(LivePhase.MUTED, model.liveVoice.phase)
        assertTrue(audio.captureMuted)
        assertTrue(audio.flushes.get() > flushes)
        scenario.moveToState(Lifecycle.State.RESUMED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
        assertFalse(audio.captureMuted)
    }

    /** Runs [block] while each start of an activity for [action] is blocked without a result, so it causes no pause. */
    private fun blocked(action: String, block: (Instrumentation.ActivityMonitor) -> Unit) {
        val monitor = Instrumentation.ActivityMonitor(IntentFilter(action), null, true)
        robot.instrumentation.addMonitor(monitor)
        try { block(monitor) } finally { robot.instrumentation.removeMonitor(monitor) }
    }

    private fun home() = assertTrue(robot.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))

    /** A composed text inside the layer. The chat under the layer has the same messages. */
    private fun inLayer(scenario: ActivityScenario<CloudTestActivity>, text: String) =
        robot.composedTextFit(scenario, LAYER).any { it.first.trimEnd() == text }

    private fun keepScreenOn(scenario: ActivityScenario<CloudTestActivity>): Boolean {
        fun any(view: View): Boolean = view.keepScreenOn || (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).any { any(group.getChildAt(it)) } } == true
        var on = false
        scenario.onActivity { on = any(it.window.decorView) }
        return on
    }

    private companion object {
        const val LAYER = "live_voice_layer"
        const val PLAN_REQUEST = "Change my memory of my favorite color to green"
        const val ADD = """{"steps":[{"function":"memory.write","args":{"text":"Favorite color is green"}}]}"""
        const val TIMER_REQUEST = "Set a five-minute timer"
        const val TIMER = """{"steps":[{"function":"clock.setTimer","args":{"seconds":300}}]}"""
        const val SELFIE_REQUEST = "Take a selfie."
        /** The action of the system permission dialog (PackageManager.ACTION_REQUEST_PERMISSIONS, a system API). */
        const val REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"
        const val SELFIE = """{"steps":[{"function":"camera.capture","args":{"lens":"front"}}]}"""
    }
}

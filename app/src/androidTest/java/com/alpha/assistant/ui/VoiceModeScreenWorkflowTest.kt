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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.agent.AgentPhase
import com.alpha.assistant.agent.PhoneActionEvidence
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.providerFailure
import com.alpha.assistant.tools.withPlanStep
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.outputAudio
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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

    /**
     * Spec 5.2.3 to 5.2.5: the card of the hand-off, the status line, and the microphone and end controls. Spec 5.1.4: the mode starts
     * with no selected chat, and the card shows the reply in the chat that the first hand-off creates.
     */
    @Test fun layerShowsCardStatusAndControls() {
        fixture.provider.gate = CompletableDeferred()
        install()
        voiceMode { scenario, model ->
            assertNull(model.snapshot.selectedId)
            robot.waitFor(robot.text(R.string.live_voice_listening))
            // The chat composer under the layer keeps its voice mode button, but the button is out of the accessibility tree.
            assertTrue(robot.composedNode(scenario) { it.getOrNull(SemanticsProperties.ContentDescription)?.contains(voiceButton) == true })
            assertNull(robot.find(voiceButton))
            socket.say("Какая погода", "d1", 100)
            robot.waitFor(robot.text(R.string.live_voice_working))
            checkNotNull(fixture.provider.gate).complete(Unit)
            // Plan D: the voice retells a text answer, and the stage shows no card for it.
            robot.waitUntil { "d1" to "Ответ Alphy: Fixture response." in socket.commentary() }
            assertFalse(inStage(scenario, "Fixture response"))
            assertNotNull(model.snapshot.selectedId)
            robot.waitFor(robot.text(R.string.live_voice_listening))
            robot.waitFor(robot.text(R.string.live_voice_end))
            robot.click(robot.text(R.string.live_voice_mute))
            robot.waitFor(robot.text(R.string.live_voice_mic_off))
            robot.waitFor(robot.text(R.string.live_voice_unmute))
            assertTrue(audio.captureMuted)
        }
    }

    /**
     * Spec 5.8: an end closes the layer, and the composer status line shows the end status. Review I2: after a key fix through
     * «Открыть настройки», the chat shows no stale status and no link.
     */
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
            val failure = providerFailure(AiProvider.OPENAI, 401, null, false).message
            robot.waitFor(failure)
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            val saved = AtomicBoolean()
            scenario.onActivity { model.saveKey("sk-proj-replacement-test-only", AiProvider.OPENAI) { saved.set(it) } }
            robot.waitUntil { saved.get() }
            robot.back()
            robot.waitFor(voiceButton)
            assertEquals("", model.liveVoice.status)
            assertNull(robot.find(failure))
            assertNull(robot.find(robot.text(R.string.open_settings)))
        }
    }

    /** Review I2: a chat change and a new chat clear the end status, as they clear the one-shot voice status. */
    @Test fun chatChangeClearsTheEndStatus() {
        fixture.seedChat("a", "Первый", "Привет")
        install()
        voiceMode { scenario, model ->
            listOf<(ShellViewModel) -> Unit>({ it.select("a") }, { it.select(null) }).forEachIndexed { index, change ->
                if (index > 0) start(model)
                socket.closeRemote()
                robot.waitFor(robot.text(R.string.live_voice_connection))
                scenario.onActivity { change(model) }
                robot.waitGone(robot.text(R.string.live_voice_connection))
                assertEquals("", model.liveVoice.status)
            }
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

    /** Plan D, Da1 and Da2 ruling 2: before the first hand-off, the stage holds the character and no app, and only the strip holds the status. */
    @Test fun stageShowsTheCharacterBeforeTheFirstScreen() {
        install()
        voiceMode { scenario, model ->
            robot.waitUntil { inStrip(scenario, robot.text(R.string.live_voice_listening)) }
            assertTrue(robot.composedIn(scenario, STAGE) { it.getOrNull(SemanticsProperties.TestTag) == CHARACTER })
            assertFalse(inStage(scenario, robot.text(R.string.live_voice_listening)))
            assertFalse(robot.composedIn(scenario, STRIP) { it.getOrNull(SemanticsProperties.TestTag) == CHARACTER })
            assertNull(model.stageToolId)
        }
    }

    /** Review focus 1: the phone plan of a later hand-off shows its card on the stage above the app, and nothing runs without a tap. */
    @Test fun aPlanCardShowsAboveTheScreen() = withPlanStep(fixture.context, AiProvider.OPENAI, false) {
        fixture.provider.createApp = true
        install()
        voiceMode { scenario, model ->
            socket.say("Сделай счётчик", "d1", 100)
            robot.waitUntil(60_000) { model.stageToolId != null }
            val app = model.stageToolId
            robot.waitUntil { inStage(scenario, "Fixture") }
            fixture.provider.createApp = false
            fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence(PLAN_REQUEST, PLAN_REQUEST))
            fixture.provider.agentProposal = ADD
            socket.say(PLAN_REQUEST, "d2", 2_000)
            robot.waitUntil(60_000) { model.agent.plan?.phase == AgentPhase.REVIEW }
            robot.waitUntil { inStage(scenario, robot.text(R.string.agent_plan_title)) && inStage(scenario, robot.text(R.string.agent_execute)) }
            assertTrue(robot.bounds(robot.text(R.string.agent_plan_title)).top < robot.bounds("Fixture").top)
            robot.awaitFrames(5)
            assertEquals(AgentPhase.REVIEW, model.agent.plan?.phase)
            assertEquals(app, model.stageToolId)
        }
    }

    /** Plan D, Da1: the first line of the strip holds the status and the microphone state, and the strip holds mute and end. */
    @Test fun stripShowsStatusMicStateAndButtons() {
        install()
        voiceMode { scenario, _ ->
            robot.waitUntil { inStrip(scenario, robot.text(R.string.live_voice_listening)) && inStrip(scenario, robot.text(R.string.live_voice_mic_on)) }
            assertTrue(stripButton(scenario, robot.text(R.string.live_voice_mute)))
            assertTrue(stripButton(scenario, robot.text(R.string.live_voice_end)))
            robot.click(robot.text(R.string.live_voice_mute))
            robot.waitUntil { inStrip(scenario, robot.text(R.string.live_voice_mic_off)) }
            assertTrue(audio.captureMuted)
            assertTrue(stripButton(scenario, robot.text(R.string.live_voice_unmute)))
        }
    }

    /** Plan D, Da1: a mute before `session.started` turns the microphone off, so the strip says so while the mode connects. */
    @Test fun muteWhileConnectingShowsTheMicrophoneOff() {
        install()
        fixture.launch().use { scenario ->
            val model = ready(scenario)
            robot.click(voiceButton)
            robot.waitUntil { "session.start" in socket.types() }
            assertEquals(LivePhase.CONNECTING, model.liveVoice.phase)
            robot.click(robot.text(R.string.live_voice_mute))
            robot.waitUntil { inStrip(scenario, robot.text(R.string.live_voice_mic_off)) }
            assertTrue(audio.captureMuted)
            socket.emit(LIVE_STARTED)
            robot.waitUntil { model.liveVoice.phase == LivePhase.MUTED }
            assertTrue(inStrip(scenario, robot.text(R.string.live_voice_mic_off)))
        }
    }

    /** Plan D, Da1: the stage takes the whole width of the layer and all the height above the strip. */
    @Test fun stageFillsTheSpaceAboveTheStrip() {
        install()
        voiceMode { scenario, _ ->
            robot.waitUntil { robot.composed(scenario, STAGE) && robot.composed(scenario, STRIP) }
            val stage = robot.composedBounds(scenario, STAGE).first
            val strip = robot.composedBounds(scenario, STRIP).first
            val layer = robot.composedBounds(scenario, LAYER).first
            val dp = fixture.context.resources.displayMetrics.density
            assertTrue("$stage ends at the strip $strip", abs(stage.bottom - strip.top) <= dp)
            assertTrue("$stage spans the layer $layer", abs(stage.width() - layer.width()) <= dp)
            if (layer.width() > 668 * dp) assertTrue("$stage is wider than 620 dp", stage.width() > 620 * dp)
        }
    }

    /** Spec 5.2.1 and the 5.8 table: ✕ and Back end the mode without a status, and the chat under the layer shows the saved turns. */
    @Test fun endClosesTheLayer() {
        install()
        voiceMode { scenario, model ->
            socket.say("Какая погода", "d1", 100)
            robot.waitUntil { socket.commentary().isNotEmpty() }
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

    /**
     * Review Focus 1 and ruling R20: a configuration change keeps the session and its socket. Alphy speaks across the recreate, and
     * the reply is not flushed, and GPT-Live gets no mute or unmute.
     */
    @Test fun rotationKeepsTheSession() {
        install()
        voiceMode { scenario, model ->
            audio.playbackActive = true
            socket.emit(outputAudio(1))
            robot.waitUntil { model.liveVoice.phase == LivePhase.SPEAKING }
            val flushes = audio.flushes.get()
            val sent = socket.sent.size
            scenario.recreate()
            robot.waitFor(robot.text(R.string.live_voice_end))
            robot.awaitFrames(2)
            assertTrue(model.liveVoice.active)
            assertEquals(0, socket.closes.get())
            assertFalse("session.close" in socket.types())
            assertEquals(flushes, audio.flushes.get())
            val events = socket.types().drop(sent)
            assertFalse("$events", "session.input_audio.mute" in events || "session.input_audio.unmute" in events)
            assertEquals(LivePhase.SPEAKING, model.liveVoice.phase)
            assertFalse(audio.captureMuted)
        }
    }

    /** Review check: in landscape with the keyboard up, the microphone and end controls of the layer stay above the keyboard. */
    @Test fun landscapeKeyboardKeepsTheControls() {
        install()
        voiceMode { scenario, _ ->
            robot.assertAboveLandscapeKeyboard(scenario, listOf(R.string.live_voice_mute, R.string.live_voice_end).map { robot.text(it) }) {
                assertTrue(robot.field(robot.text(R.string.composer_label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
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
            // Clock comes to the front within the 5 s of the expectation: Alphy pauses and stops.
            openBlocked(scenario, model, AlarmClock.ACTION_SET_TIMER, R.string.agent_execute, Lifecycle.State.CREATED)
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
            openBlocked(scenario, model, REQUEST_PERMISSIONS, R.string.agent_permission_allow, Lifecycle.State.STARTED)
        }
    }

    /**
     * Ruling R18 and spec 5.8.1: Clock, which an alarm sequence of a phone plan opens, keeps the mode as the timer step does. The
     * alarm start is blocked without a result, so no alarm exists on the device. A stop without the expectation would end the mode.
     */
    @Test fun alarmSequenceOnlyMutes() {
        fixture.provider.devicePlanSteps = listOf(PhoneActionEvidence(ALARM_REQUEST, "7:00 AM"), PhoneActionEvidence(ALARM_REQUEST, "9:00 AM"))
        fixture.provider.agentProposal = ALARMS
        install()
        voiceMode { scenario, model ->
            socket.say(ALARM_REQUEST, "d1", 100)
            robot.waitUntil(60_000) { model.agent.plan?.phase == AgentPhase.REVIEW }
            openBlocked(scenario, model, AlarmClock.ACTION_SET_ALARM, R.string.agent_execute, Lifecycle.State.CREATED)
        }
    }

    /**
     * Ruling R19: a voice mode start with the microphone granted clears the guidance of an earlier denial, so it does not come back
     * after the end. The denial calls the model function of the denied start, because a real RECORD_AUDIO revocation stops the test process.
     */
    @Test fun liveStartClearsStaleMicrophoneGuidance() {
        install()
        fixture.launch().use { scenario ->
            val model = ready(scenario)
            scenario.onActivity { model.denyVoicePermission(null) }
            robot.waitFor(robot.text(R.string.voice_mic_allow))
            start(model)
            robot.click(robot.text(R.string.live_voice_end))
            robot.waitUntil { !robot.composed(scenario, LAYER) }
            robot.waitFor(voiceButton)
            assertFalse(model.voice.permissionRequired)
            assertEquals("", model.voice.status)
            assertNull(robot.find(robot.text(R.string.voice_mic_allow)))
            assertNull(robot.find(robot.text(R.string.voice_mic_permission)))
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

    /**
     * Taps [button] of the plan card while each start of an activity for [action] is blocked without a result, so the start causes
     * no pause. Then Alphy goes to [state] and back, as the opened screen makes it.
     */
    private fun openBlocked(scenario: ActivityScenario<CloudTestActivity>, model: ShellViewModel, action: String, button: Int,
        state: Lifecycle.State) {
        val monitor = Instrumentation.ActivityMonitor(IntentFilter(action), null, true)
        robot.instrumentation.addMonitor(monitor)
        try {
            robot.reveal(robot.text(button))
            robot.click(robot.text(button))
            robot.waitUntil { monitor.hits == 1 }
            awayAndBack(scenario, model, state)
        } finally { robot.instrumentation.removeMonitor(monitor) }
    }

    private fun home() = assertTrue(robot.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))

    /** A composed text inside the layer. The chat under the layer has the same messages. */
    private fun inLayer(scenario: ActivityScenario<CloudTestActivity>, text: String) =
        robot.composedTextFit(scenario, LAYER).any { it.first.trimEnd() == text }

    private fun inStage(scenario: ActivityScenario<CloudTestActivity>, text: String) = robot.composedTextIn(scenario, STAGE, text)
    private fun inStrip(scenario: ActivityScenario<CloudTestActivity>, text: String) = robot.composedTextIn(scenario, STRIP, text)
    private fun stripButton(scenario: ActivityScenario<CloudTestActivity>, label: String) =
        robot.composedIn(scenario, STRIP) { it.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }


    private fun keepScreenOn(scenario: ActivityScenario<CloudTestActivity>): Boolean {
        fun any(view: View): Boolean = view.keepScreenOn || (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).any { any(group.getChildAt(it)) } } == true
        var on = false
        scenario.onActivity { on = any(it.window.decorView) }
        return on
    }

    private companion object {
        const val LAYER = "live_voice_layer"
        const val STAGE = "dialogue_stage"
        const val STRIP = "dialogue_strip"
        const val CHARACTER = "live_voice_character"
        const val PLAN_REQUEST = "Change my memory of my favorite color to green"
        const val ADD = """{"steps":[{"function":"memory.write","args":{"text":"Favorite color is green"}}]}"""
        const val TIMER_REQUEST = "Set a five-minute timer"
        const val TIMER = """{"steps":[{"function":"clock.setTimer","args":{"seconds":300}}]}"""
        const val SELFIE_REQUEST = "Take a selfie."
        /** The action of the system permission dialog (PackageManager.ACTION_REQUEST_PERMISSIONS, a system API). */
        const val REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"
        const val SELFIE = """{"steps":[{"function":"camera.capture","args":{"lens":"front"}}]}"""
        const val ALARM_REQUEST = "Set alarms for 7:00 AM and 9:00 AM."
        const val ALARMS = """{"steps":[{"function":"clock.setAlarm","args":{"hour":7,"minute":0}},{"function":"clock.setAlarm","args":{"hour":9,"minute":0}}]}"""
    }
}

package com.alpha.assistant.surfaces

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.alpha.assistant.AssistantFixtureApplication
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.ScreenContext
import com.alpha.assistant.ui.KeyStatus
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import com.alpha.assistant.ui.assistIntent
import com.alpha.assistant.ui.beginCapture
import com.alpha.assistant.ui.fakeDeepSeekKey
import com.alpha.assistant.voice.FakeLiveAudio
import com.alpha.assistant.voice.FakeLiveTransport
import com.alpha.assistant.voice.LIVE_STARTED
import com.alpha.assistant.voice.LivePhase
import com.alpha.assistant.voice.LiveTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The assistant button mode (spec 5.11): the real sheet with a scripted GPT-Live socket and scripted audio. */
@RunWith(AndroidJUnit4::class)
class AssistantLiveVoiceWorkflowTest {
    private val robot = ShellRobot("assistant-live-voice")
    private val app = robot.instrumentation.targetContext.applicationContext as AssistantFixtureApplication
    private val fixture = ShellFixture("assistant-live-voice")
    private val socket = FakeLiveTransport()
    private val audio = FakeLiveAudio()
    @Volatile private var shared: ShellViewModel? = null
    private val model get() = checkNotNull(shared)
    private val composer get() = robot.text(R.string.composer_label)

    @After fun remove() {
        robot.instrumentation.runOnMainSync {
            val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap(lifecycle::getActivitiesInStage)
                .filter { it is MainActivity || it is AssistantActivity }.forEach { it.finish() }
            // The message owns its screenshot file, and the fixture removes only the databases.
            shared?.snapshot?.conversations.orEmpty().flatMap { it.messages }.flatMap { it.attachments }.filter { it.automaticScreen }
                .forEach { AssistContext.mediaFile(app, Uri.parse(it.uri)).delete() }
            AssistantCapture.current?.id?.let(AssistantCapture::clear)
            app.removeAssistantFixture()
        }
        fixture.remove()
        fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        fixture.context.deleteDatabase("${fixture.store.databaseName}-agent.db")
    }

    /** Spec 5.11.2 and 5.11.5: an OpenAI press starts the voice mode without a tap, in a new chat, with the screen rows in the voice view. */
    @Test fun pressWithOpenAiStartsAtOnce() {
        install()
        press(capture("Screen")).use { scenario ->
            robot.waitUntil { starts() == 1 && model.liveVoice.active }
            assertNull(model.snapshot.selectedId)
            robot.waitFor(robot.text(R.string.live_voice_end))
            // Spec 5.11.3: the screen rows stay above the composer row of the voice view, with their remove controls.
            robot.waitUntil { inLayer(scenario, robot.text(R.string.assistant_screen_text)) && inLayer(scenario, robot.text(R.string.assistant_image_attached)) }
            // The voice view with the rows fits in the sheet: each control is a full target inside it.
            val sheet = robot.composedBounds(scenario, "assistant-sheet").first
            for (id in listOf(R.string.live_voice_end, R.string.live_voice_mute, R.string.assistant_discard_capture, R.string.assistant_image_remove)) {
                val bounds = robot.assertTarget(robot.text(id))
                assertTrue("${robot.text(id)} at $bounds must be inside the sheet $sheet", sheet.contains(bounds))
            }
        }
    }

    /** Spec 5.11.2 and 5.11.8: with another provider, the sheet opens as today and no session starts. */
    @Test fun pressWithOtherProviderOpensTheSheet() {
        install(AiProvider.DEEPSEEK)
        press(capture("Screen")).use { scenario ->
            robot.waitUntil { !model.loading && model.keyStatus != KeyStatus.Checking }
            robot.waitFor(composer)
            robot.waitFor(robot.text(R.string.assistant_image_attached))
            robot.awaitFrames(5)
            assertFalse(model.liveVoice.active)
            assertEquals(0, starts())
            // A start would end at once with «only OpenAI», so the status shows that no start happened.
            assertEquals("", model.liveVoice.status)
            assertFalse(robot.composed(scenario, LAYER))
            assertNull(robot.find(robot.text(R.string.live_voice)))
        }
    }

    /**
     * Spec 5.11.4: the first hand-off saves the kept screen context and the screenshot, and the second has neither. A screen that the
     * user removes before the first hand-off of the next press is not attached.
     */
    @Test fun firstHandOffCarriesTheKeptScreen() {
        install()
        press(capture("Kept screen")).use {
            listening(1)
            socket.say("Что на экране?", "d1", 100)
            val first = user("Что на экране?")
            assertEquals("Kept screen", first.screenContext?.visibleText)
            assertTrue(first.attachments.single().automaticScreen)
            // The capture is discarded after the first hand-off, as after a typed assistant request.
            robot.waitGone(robot.text(R.string.assistant_image_attached))
            socket.say("А теперь?", "d2", 3_000)
            val second = user("А теперь?")
            assertNull(second.screenContext)
            assertTrue(second.attachments.isEmpty())

            app.startActivity(intent(capture("Removed screen")))
            listening(2)
            robot.click(robot.text(R.string.assistant_discard_capture))
            robot.waitGone(robot.text(R.string.assistant_screen_text))
            socket.say("Что здесь?", "d3", 100)
            val third = user("Что здесь?")
            assertNull(third.screenContext)
            // The user kept the screenshot.
            assertTrue(third.attachments.single().automaticScreen)

            // A capture with both rows removed is not in progress, so the hand-off does not wait for it.
            app.startActivity(intent(capture("Nothing kept")))
            listening(3)
            robot.click(robot.text(R.string.assistant_discard_capture))
            robot.click(robot.text(R.string.assistant_image_remove))
            robot.waitGone(robot.text(R.string.assistant_image_attached))
            val said = SystemClock.uptimeMillis()
            socket.say("А тут?", "d4", 100)
            val fourth = user("А тут?")
            assertTrue("The hand-off must not wait", SystemClock.uptimeMillis() - said < 2_000)
            assertNull(fourth.screenContext)
            assertTrue(fourth.attachments.isEmpty())
        }
    }

    /** Spec 5.11.2: the first hand-off waits up to 2 s for a capture in progress, then goes without it. */
    @Test fun firstHandOffWaitsForALateCapture() {
        install()
        val late = capture(null, withImage = false)
        press(late).use {
            listening(1)
            socket.say("Что на экране?", "d1", 100)
            SystemClock.sleep(1_000)
            assertTrue("The hand-off must wait for the capture", users().isEmpty())
            robot.instrumentation.runOnMainSync { AssistantCapture.updateScreen(late, ScreenContext(visibleText = "Late screen")) }
            assertEquals("Late screen", user("Что на экране?").screenContext?.visibleText)

            val tooLate = capture(null, withImage = false)
            app.startActivity(intent(tooLate))
            listening(2)
            val said = SystemClock.uptimeMillis()
            socket.say("А сейчас?", "d2", 100)
            assertNull(user("А сейчас?").screenContext)
            assertTrue("The hand-off must wait 2 s", SystemClock.uptimeMillis() - said >= 2_000)
            SystemClock.sleep(maxOf(0, said + 3_000 - SystemClock.uptimeMillis()))
            robot.instrumentation.runOnMainSync { AssistantCapture.updateScreen(tooLate, ScreenContext(visibleText = "Too late")) }
            robot.awaitFrames(2)
            assertNull(user("А сейчас?").screenContext)
            assertNull(AssistantCapture.current?.screen)
        }
    }

    /** Spec 5.11.2: no speech after the press ends the mode, and the sheet stays with its normal composer and the end status. */
    @Test fun silenceAfterPressKeepsTheSheet() {
        install(timing = LiveTiming(firstSpeechMs = 2_000))
        press(capture("Silent screen")).use { scenario ->
            listening(1)
            robot.waitFor(robot.text(R.string.live_voice_no_first_speech))
            assertFalse(model.liveVoice.active)
            robot.waitFor(composer)
            robot.waitUntil { !robot.composed(scenario, LAYER) }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            // The capture stays for a typed request, and the press does not start the mode again.
            robot.waitFor(robot.text(R.string.assistant_image_attached))
            robot.awaitFrames(5)
            assertEquals(1, starts())
        }
    }

    /** Spec 5.11.2 and 5.11.7: ✕ returns to the sheet. The close button, onHide, onLockscreenShown, and openApp end the mode. */
    @Test fun sheetEndsTheMode() {
        install()
        press(capture("First")).use { scenario ->
            listening(1)
            robot.click(robot.text(R.string.live_voice_end))
            robot.waitUntil { !model.liveVoice.active }
            robot.waitFor(composer)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            robot.awaitFrames(5)
            assertEquals(1, starts())
            // The voice mode button of the sheet composer starts it again.
            robot.click(robot.text(R.string.live_voice))
            listening(2)
            robot.click(robot.text(R.string.assistant_close))
            robot.waitUntil { !model.liveVoice.active && scenario.state == Lifecycle.State.DESTROYED }
        }
        // onHide and onLockscreenShown call endSession(), which releases the capture of the press. A test-built session has no
        // system token, and its finish() throws, so the test makes the same release call.
        val hidden = capture("Hidden")
        press(hidden).use { scenario ->
            listening(3)
            robot.instrumentation.runOnMainSync { AssistantCapture.release(hidden) }
            robot.waitUntil { !model.liveVoice.active && !robot.composed(scenario, LAYER) }
        }
        press(capture("Open in app")).use {
            listening(4)
            robot.click(robot.text(R.string.menu))
            robot.click(robot.text(R.string.assistant_open_app))
            robot.waitUntil { !model.liveVoice.active && inStage(Stage.RESUMED) { it is MainActivity } }
        }
    }

    /**
     * Spec 5.11.2: a denied microphone permission shows the guidance and starts no session. RECORD_AUDIO must not be granted, and a
     * revocation stops the test process, so this test runs after `pm revoke`. The system dialog is blocked with a denial result.
     */
    @Test fun noPermissionNoSession() {
        assumeFalse("RECORD_AUDIO is granted", fixture.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED)
        install()
        // An IntentFilter monitor also matches a start with no action, such as the ActivityScenario bootstrap.
        val denial = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent) =
                if (intent.action == REQUEST_PERMISSIONS) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) else null
        }
        robot.instrumentation.addMonitor(denial)
        try {
            press(capture("Screen"), allow = false).use {
                robot.waitUntil { denial.hits == 1 }
                robot.waitFor(robot.text(R.string.voice_mic_permission))
                robot.waitFor(robot.text(R.string.voice_mic_allow))
                assertFalse(model.liveVoice.active)
                assertEquals(0, starts())
                assertFalse(socket.connected)
            }
        } finally { robot.instrumentation.removeMonitor(denial) }
    }

    /** Spec 5.11.6: a press while the assistant is unavailable shows the unlock prompt and starts no session. */
    @Test fun lockedPressStartsNoSession() {
        install()
        val previousLock = robot.shell("locksettings get-disabled")
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        try {
            robot.shell("locksettings set-disabled false")
            robot.shell("input keyevent KEYCODE_SLEEP")
            robot.shell("input keyevent KEYCODE_WAKEUP")
            robot.waitUntil { keyguard.isKeyguardLocked }
            press(capture("Locked screen"), allow = false).use {
                robot.waitFor(robot.text(R.string.assistant_unlock_title))
                robot.awaitFrames(5)
                assertFalse(shared?.liveVoice?.active == true)
                assertEquals(0, starts())
            }
        } finally {
            robot.shell("input keyevent KEYCODE_WAKEUP")
            robot.shell("wm dismiss-keyguard")
            if (previousLock == "true" || previousLock == "false") robot.shell("locksettings set-disabled $previousLock")
        }
    }

    /**
     * Task 8 carry: the shell activity paused or stopped behind the sheet must not mute or end the mode that the sheet started, and it
     * draws no voice view of its own.
     */
    @Test fun shellBehindTheSheetKeepsTheMode() {
        install()
        ActivityScenario.launch(MainActivity::class.java).use { shell ->
            robot.allowMicrophone(shell)
            robot.waitUntil { shared?.loading == false }
            // RESUMED leaves the shell paused under the translucent sheet. CREATED stops it first.
            listOf(Lifecycle.State.RESUMED, Lifecycle.State.CREATED).forEachIndexed { index, state ->
                shell.moveToState(state)
                app.startActivity(intent(capture("Over the shell $index")))
                listening(index + 1)
                robot.awaitFrames(5)
                assertEquals(LivePhase.LISTENING, model.liveVoice.phase)
                assertFalse(audio.captureMuted)
                assertFalse(robot.composed(shell, LAYER))
                robot.click(robot.text(R.string.assistant_close))
                robot.waitUntil { !model.liveVoice.active && !inStage(Stage.RESUMED) { it is AssistantActivity } }
            }
        }
    }

    private fun install(provider: AiProvider = AiProvider.OPENAI, timing: LiveTiming = LiveTiming()) {
        check(fixture.keys.save(if (provider == AiProvider.OPENAI) "alpha-test-only" else fakeDeepSeekKey(), provider).isSuccess)
        robot.instrumentation.runOnMainSync {
            app.installAssistantFixture {
                ShellViewModel(it, fixture.provider, fixture.store, fixture.keys, liveVoiceFactory = fixture.liveVoice(socket, audio, timing),
                    legacy = fixture.legacy, models = fixture.models, imageStore = fixture.imageStore, voiceStore = fixture.voiceStore)
                    .also { model -> shared = model }
            }
        }
    }

    /** A null [text] leaves the capture empty, as before `onHandleAssist`. */
    private fun capture(text: String?, withImage: Boolean = true) = beginCapture(text?.let { ScreenContext(visibleText = it) }, withImage)

    private fun intent(invocation: String) = assistIntent(app, invocation)

    /** An assistant button press of [invocation]. [allow] answers a microphone request, as a user who allows it. */
    private fun press(invocation: String, allow: Boolean = true): ActivityScenario<AssistantActivity> =
        ActivityScenario.launch<AssistantActivity>(intent(invocation)).also { if (allow) robot.allowMicrophone(it) }

    private fun starts() = socket.types().count { it == "session.start" }

    /** Waits for the [count]th start and answers `session.started`. */
    private fun listening(count: Int) {
        robot.waitUntil { starts() == count }
        socket.emit(LIVE_STARTED)
        robot.waitUntil { model.liveVoice.phase == LivePhase.LISTENING }
    }

    private fun users(): List<Message> {
        var result = emptyList<Message>()
        robot.instrumentation.runOnMainSync { result = model.snapshot.conversations.flatMap { it.messages }.filter { it.role == "user" } }
        return result
    }

    private fun user(text: String): Message {
        robot.waitUntil { users().any { it.text == text } }
        return users().single { it.text == text }
    }

    private fun inLayer(scenario: ActivityScenario<AssistantActivity>, text: String) =
        robot.composedTextFit(scenario, LAYER).any { it.first.trimEnd() == text }

    private fun inStage(stage: Stage, match: (Activity) -> Boolean): Boolean {
        var found = false
        robot.instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).any(match) }
        return found
    }

    private companion object {
        const val LAYER = "live_voice_layer"
        /** The action of the system permission dialog (PackageManager.ACTION_REQUEST_PERMISSIONS, a system API). */
        const val REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"
    }
}

package com.alpha.assistant.voice

import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import com.offlineassistant.app.voice.AudioTranscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Failure cases: Stop discards recognition, Cancel loses a draft, a late result
 * replaces edited text or another conversation, or Send submits more than once. */
class InlineVoiceWorkflowTest {
    private val diagnostics = UiFailureDiagnostics("voice")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val actions = StringBuilder()
    private var voiceState: () -> String = { "uninitialized" }

    @Test fun diagnosticFailuresPreserveFailedClickAssertion() {
        for (failure in listOf(java.io.IOException("Fixture write failed"), IllegalStateException("Fixture activity closed"))) {
            var captures = 0
            val assertion = assertThrows(AssertionError::class.java) {
                assertClickResult(false) { captures++; throw failure }
            }
            assertEquals(1, captures)
            assertNull(assertion.cause)
        }
        assertClickResult(true) { fail("Successful clicks must not capture failure diagnostics") }
    }

    @Test fun stopReviewSendCancelAndLateResultsPreserveDrafts() {
        val context = instrumentation.targetContext
        val fixture = "inline-voice-${UUID.randomUUID()}"
        val store = ShellStore(context, "$fixture.db")
        val keys = SecureKeyStore(context, fixture, fixture)
        val attachment = Attachment("content://fixture/review", "Reviewed note", "text/plain", contextText = "Local note")
        val original = Draft("Keep my question", listOf(attachment))
        store.saveNewDraft(original)
        lateinit var model: ShellViewModel
        var result = CompletableDeferred<AudioTranscription>()
        var preparations = 0
        CloudTestActivity.factory = { app ->
            ShellViewModel(app, object : ChatProvider {
                override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
                    error("This fixture must not call a provider")
                }
            }, store, keys, voiceFactory = { application, scope, transcript ->
                VoiceSession(application, scope, transcript,
                    prepareModels = { preparations++ },
                    captureAudio = { val pending = result; withContext(NonCancellable) { pending.await() } })
            }).also { model = it }
        }
        try {
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                voiceState = {
                    var state = ""
                    scenario.onActivity { activity ->
                        state = "active=${model.voice.active} phase=${model.voice.phase} preparations=$preparations " +
                            "permission=${context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)} lifecycle=${activity.lifecycle.currentState}"
                    }
                    state
                }
                diagnostics.state = {
                    "voiceActive=${model.voice.active} voicePhase=${model.voice.phase} " +
                        "requestBusy=${model.activeConversationId != null} attachmentBusy=${model.attachmentBusy} loading=${model.loading}"
                }
                waitUntil { !model.loading }
                click("Голосовой запрос")
                allowMicrophone()
                waitUntil { model.voice.phase == "listening" }
                click("Отменить запись")
                result.complete(AudioTranscription("Late canceled text", null, 0))
                waitUntil { !model.voice.active }
                assertEquals(original, model.snapshot.draft)
                assertTrue("Cancel must not leave a persistent status", model.voice.status.isBlank())
                assertFalse("Cancel must not display an idle status", model.voice.hasAttempted)
                waitUntil { find(diagnostics.freshRoot(), "Сообщение ассистенту") != null }
                assertNull(find(diagnostics.freshRoot(), "Voice session stopped · microphone off"))
                scenario.onActivity { model.select(null) }
                assertEquals(original, model.snapshot.draft)
                assertTrue("Navigation must not restore cancellation status", model.voice.status.isBlank())

                result = CompletableDeferred()
                click("Голосовой запрос")
                waitUntil { model.voice.phase == "listening" }
                click("Завершить запись")
                assertEquals("recognizing", model.voice.phase)
                result.complete(AudioTranscription("Распознанный текст", null, 0))
                waitUntil { !model.voice.active && model.snapshot.draft.text == "Keep my question\nРаспознанный текст" }
                assertTrue(model.snapshot.conversations.isEmpty())
                assertEquals(original.attachments, model.snapshot.draft.attachments)
                val composer = ShellRobot("voice-draft")
                composer.edit("Сообщение ассистенту", "Keep my question\nПроверенный текст")
                click(composer.text(com.alpha.assistant.R.string.composer_send))
                waitUntil { model.snapshot.selected?.messages?.count { it.role == "user" } == 1 }
                val user = model.snapshot.selected!!.messages.single { it.role == "user" }
                assertEquals("Keep my question\nПроверенный текст", user.text)
                assertEquals(original.attachments, user.attachments)
                assertFalse(model.voice.active)
                scenario.onActivity { model.voice.replyCompleted() }
                assertFalse(model.voice.active)

                waitUntil { model.activeConversationId == null }
                scenario.onActivity { model.select(null); model.editDraft(original) }
                result = CompletableDeferred()
                click("Голосовой запрос")
                waitUntil { model.voice.phase == "listening" }
                val changed = original.copy(text = "User edited this draft")
                scenario.onActivity { model.editDraft(changed) }
                result.complete(AudioTranscription("Stale replacement", null, 0))
                waitUntil { !model.voice.active }
                assertEquals(changed, model.snapshot.draft)

                result = CompletableDeferred()
                click("Голосовой запрос")
                waitUntil { model.voice.phase == "listening" }
                val sentId = model.snapshot.conversations.single().id
                scenario.onActivity { model.select(sentId) }
                val selectedDraft = model.snapshot.draft
                result.complete(AudioTranscription("Wrong conversation", null, 0))
                waitUntil { !model.voice.active }
                assertEquals(selectedDraft, model.snapshot.draft)
                assertEquals(changed, model.snapshot.newDraft)

                result = CompletableDeferred()
                click("Голосовой запрос")
                waitUntil { model.voice.phase == "listening" }
                click("Отправить голосовой текст")
                scenario.onActivity { model.sendVoice() }
                assertEquals("recognizing", model.voice.phase)
                result.complete(AudioTranscription("Отправь сразу", null, 0))
                waitUntil { model.snapshot.selected?.messages?.count { it.role == "user" } == 2 }
                assertEquals("Отправь сразу", model.snapshot.selected!!.messages.last { it.role == "user" }.text)
                assertEquals(5, preparations)
                File(context.getExternalFilesDir(null), "inline-voice-actions.txt").writeText(actions.toString() + voiceState())
                scenario.onActivity { model.voice.permissionDenied(); model.voice.stop() }
                assertTrue("Permission recovery must remain available", model.voice.permissionRequired)
                waitUntil { find(diagnostics.freshRoot(), "Разрешите микрофон в настройках Android и повторите запись.") != null }
                File(context.getExternalFilesDir(null), "inline-voice-workflow.txt").writeText(
                    "Revision: ${InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied")}\n" +
                    "Command: adb -s emulator-5556 shell am instrument -w -r -e class com.alpha.assistant.voice.InlineVoiceWorkflowTest com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner\n" +
                    "Inputs: isolated draft/attachment and controlled local transcripts; no cloud credentials.\n" +
                    "Expected and observed: cancel preserves draft; Stop creates review; Send creates one user message; late results preserve edits and conversation.\n")
            }
        } finally {
            result.complete(AudioTranscription(null, "Fixture closed", 0))
            CloudTestActivity.factory = null
            store.close(); context.deleteDatabase("$fixture.db"); context.deleteDatabase("$fixture.db-tools.db")
        }
    }

    @Test fun stopKeepsSpeechCapturedBeforeModelPreparation() {
        val context = instrumentation.targetContext
        val id = "cold-voice-${UUID.randomUUID()}"
        val store = ShellStore(context, "$id.db")
        val keys = SecureKeyStore(context, id, id)
        val wav = File(context.cacheDir, "$id.wav")
        instrumentation.context.assets.open("voice/russian.wav").use { input -> wav.outputStream().use(input::copyTo) }
        val wave = com.k2fsa.sherpa.onnx.WaveReader.readWave(wav.absolutePath)
        assertEquals(16000, wave.sampleRate)
        val allowPreparation = CountDownLatch(1)
        val buffered = CountDownLatch(1)
        val captureEnded = CompletableDeferred<Unit>()
        lateinit var model: ShellViewModel
        CloudTestActivity.factory = { app ->
            ShellViewModel(app, store = store, keys = keys, voiceFactory = { application, scope, transcript ->
                VoiceSession(application, scope, transcript,
                    prepareModels = { assertTrue(allowPreparation.await(15, TimeUnit.SECONDS)) },
                    capturePcmForTest = { offer ->
                        for (start in wave.samples.indices step 1600) {
                            offer(ShortArray(minOf(1600, wave.samples.size - start)) { offset ->
                                (wave.samples[start + offset] * 32767f).toInt().toShort()
                            })
                        }
                        buffered.countDown()
                        captureEnded.await()
                        true
                    })
            }).also { model = it }
        }
        try {
            ActivityScenario.launch(CloudTestActivity::class.java).use {
                waitUntil { !model.loading }
                click("Голосовой запрос")
                allowMicrophone()
                assertTrue("Capture must continue while preparation is blocked", buffered.await(5, TimeUnit.SECONDS))
                assertTrue(allowPreparation.count > 0)
                assertNull(find(diagnostics.freshRoot(), "Подготовка T-one · распознавание на устройстве"))
                diagnostics.capture("recording-while-model-loads")
                click("Завершить запись")
                captureEnded.complete(Unit)
                assertEquals("recognizing", model.voice.phase)
                assertTrue(model.snapshot.draft.text.isEmpty())
                allowPreparation.countDown()
                val robot = ShellRobot("cold-voice")
                robot.waitUntil(20_000) { !model.voice.active && "таймер" in model.snapshot.draft.text.lowercase() }
                assertTrue(model.snapshot.conversations.isEmpty())
                assertTrue(robot.field("Сообщение ассистенту").isEditable)
                robot.diagnostics.capture("cold-voice-draft")
            }
        } finally {
            allowPreparation.countDown()
            captureEnded.complete(Unit)
            CloudTestActivity.factory = null
            store.close()
            wav.delete()
            context.deleteDatabase("$id.db")
        }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { return it } }
        return null
    }
    private fun click(label: String) {
        diagnostics.target(label)
        automation.waitForIdle(100, 3000)
        var node: AccessibilityNodeInfo? = null
        waitUntil {
            // The recorder replaces the composer, so a cached microphone can refer to a removed node.
            node = find(diagnostics.freshRoot(), label)
            while (node != null && !node!!.isClickable) node = node!!.parent
            node?.let { it.isVisibleToUser && it.isEnabled && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } } == true
        }
        val identity = node?.hashCode()
        val clicked = diagnostics.click(label,node)
        actions.appendLine("action=${label} cachedIdentity=$identity result=$clicked")
        assertClickResult(clicked) {
            val refreshed = node?.refresh()
            val current = find(diagnostics.freshRoot(), label)
            actions.appendLine("failedNodeRefresh=$refreshed currentIdentity=${current?.hashCode()} sameNode=${node == current}")
            actions.appendLine(voiceState())
            File(instrumentation.targetContext.getExternalFilesDir(null), "inline-voice-actions.txt").writeText(actions.toString())
        }
        automation.waitForIdle(100, 3000)
    }
    private fun assertClickResult(clicked: Boolean, captureFailure: () -> Unit) {
        if (!clicked) runCatching(captureFailure).onFailure {
            println("VOICE_DIAGNOSTIC captureFailure=${it.javaClass.name}")
        }
        assertTrue(clicked)
    }
    private fun allowMicrophone() {
        fun allow(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.viewIdResourceName?.endsWith(":id/permission_allow_foreground_only_button") == true)
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            repeat(node.childCount) { if (allow(node.getChild(it))) return true }
            return false
        }
        waitUntil {
            allow(automation.rootInActiveWindow)
            instrumentation.targetContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    private fun waitUntil(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(50) }
        diagnostics.capture("wait-timeout")
        fail("Workflow condition did not complete within 10 seconds")
    }
}

package com.alpha.assistant.voice

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.ShellViewModel
import com.offlineassistant.app.voice.AudioTranscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Failure cases: Stop discards recognition, Cancel loses a draft, a late result
 * replaces edited text or another conversation, or Send submits more than once. */
class InlineVoiceWorkflowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

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
                    prepareModels = { speech -> assertFalse("One-shot must not prepare TTS", speech); preparations++ },
                    captureAudio = { val pending = result; withContext(NonCancellable) { pending.await() } })
            }).also { model = it }
        }
        try {
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
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
                waitUntil { find(automation.rootInActiveWindow, "Сообщение ассистенту") != null }
                assertNull(find(automation.rootInActiveWindow, "Voice session stopped · microphone off"))
                scenario.onActivity { model.select(null) }
                assertEquals(original, model.snapshot.draft)
                assertTrue("Navigation must not restore cancellation status", model.voice.status.isBlank())

                result = CompletableDeferred()
                click("Голосовой запрос")
                waitUntil { model.voice.phase == "listening" }
                click("Завершить запись")
                assertEquals("recognizing", model.voice.phase)
                result.complete(AudioTranscription("Распознанный текст", null, 0))
                waitUntil { model.voice.phase == "review" }
                assertEquals(original, model.snapshot.draft)
                scenario.onActivity { model.voice.editTranscript("Проверенный текст"); model.sendVoice(); model.sendVoice() }
                waitUntil { model.snapshot.selected?.messages?.count { it.role == "user" } == 1 }
                val user = model.snapshot.selected!!.messages.single { it.role == "user" }
                assertEquals("Keep my question\nПроверенный текст", user.text)
                assertEquals(original.attachments, user.attachments)
                assertFalse(model.voice.active)
                scenario.onActivity { model.voice.reply("No one-shot restart") }
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
                assertEquals(4, preparations)
                scenario.onActivity { model.voice.permissionDenied(); model.voice.stop() }
                assertTrue("Permission recovery must remain available", model.voice.permissionRequired)
                waitUntil { find(automation.rootInActiveWindow, "Разрешите микрофон в настройках Android и повторите запись.") != null }
                File(context.getExternalFilesDir(null), "inline-voice-workflow.txt").writeText(
                    "Revision: ${InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied")}\n" +
                    "Command: connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.alpha.assistant.voice.InlineVoiceWorkflowTest\n" +
                    "Inputs: isolated draft/attachment and controlled local transcripts; no cloud credentials.\n" +
                    "Expected and observed: cancel preserves draft; Stop creates review; Send creates one user message; late results preserve edits and conversation.\n")
            }
        } finally {
            result.complete(AudioTranscription(null, "Fixture closed", 0))
            CloudTestActivity.factory = null
            store.close(); context.deleteDatabase("$fixture.db"); context.deleteDatabase("$fixture.db-tools.db")
        }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { return it } }
        return null
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        waitUntil { node = find(automation.rootInActiveWindow, label); node != null }
        while (node != null && !node!!.isClickable) node = node!!.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        automation.waitForIdle(100, 3000)
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
        fail("Workflow condition did not complete within 10 seconds")
    }
}

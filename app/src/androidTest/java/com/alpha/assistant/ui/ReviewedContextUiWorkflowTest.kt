package com.alpha.assistant.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.surfaces.AssistContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Black-box workflow: reviewed paste survives recreation, cancellation preserves
 * existing attachments, notes sharing remains explicit, and menu access does not send. */
class ReviewedContextUiWorkflowTest {
    private val diagnostics = UiFailureDiagnostics("reviewed-context")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    @Test fun reviewedPasteAndMenuPreserveTheOriginatingDraft() {
        val context = instrumentation.targetContext
        val fixture = "reviewed-context-${UUID.randomUUID()}"
        val store = ShellStore(context, "$fixture.db")
        val keys = SecureKeyStore(context, fixture, fixture)
        val uri = AssistContext.cameraUri(context)
        val image = AssistContext.cameraFile(context, uri)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val original = Draft("Preserve my question", listOf(AssistContext.validatedCapture(context, uri)))
        val chat = Conversation("context-fixture", "Context fixture", 1, 1, original,
            listOf(Message("user", "user", "Text selected for my note", 1, "complete")))
        store.saveConversation(chat); store.saveSetting("selected", chat.id)
        var sends = 0
        CloudTestActivity.factory = { app -> ShellViewModel(app, object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { sends++ }
        }, store, keys) }
        try {
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                val menu = Rect().also { waitFor("Меню").getBoundsInScreen(it) }
                val identity = Rect().also { waitFor("Alphy").getBoundsInScreen(it) }
                assertTrue("Menu is on the first row at the left", menu.left < identity.left && menu.top < identity.bottom)
                click("Добавить контекст")
                click("Вставить текст из заметок или другого приложения")
                edit("Выбранный текст · до 8000 символов", "Explicitly selected note content")
                scenario.recreate()
                assertEquals("Explicitly selected note content", field("Выбранный текст · до 8000 символов").text.toString())
                click("Прикрепить проверенный текст")
                waitUntil { store.load().draft.attachments.size == 2 }
                val reviewed = store.load().draft
                assertEquals(original.id, reviewed.id)
                assertEquals(original.text, reviewed.text)
                assertEquals(original.attachments.single(), reviewed.attachments.first())
                assertEquals("Explicitly selected note content", reviewed.attachments.last().contextText)
                click("Добавить контекст"); click("Отмена")
                assertEquals(reviewed, store.load().draft)
                click("Выбрать текст для заметки"); waitFor("Передать выбранный текст в заметки"); click("Закрыть")
                assertEquals(reviewed, store.load().draft)
                click("Меню"); click("Разрешения и функции")
                waitFor("Доступ Android")
                assertEquals(reviewed, store.load().draft)
                assertEquals(0, sends)
                val evidence = File(context.getExternalFilesDir(null), "reviewed-context-evidence").apply { mkdirs() }
                checkNotNull(automation.takeScreenshot()).let { screenshot ->
                    File(evidence, "permissions.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    screenshot.recycle()
                }
                File(evidence, "workflow.txt").writeText("Tested revision: ${InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied")}\nCommand: connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.alpha.assistant.ui.ReviewedContextUiWorkflowTest\nInput: isolated persisted draft, image, reviewed pasted text.\nExpected and observed: same draft identity and original image; one reviewed text; no cloud sends.\n")
            }
        } finally {
            CloudTestActivity.factory = null
            store.close(); image.delete()
            context.deleteDatabase("$fixture.db"); context.deleteDatabase("$fixture.db-tools.db")
        }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.trimEnd() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { return it } }
        return null
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        diagnostics.target(label)
        var match: AccessibilityNodeInfo? = null
        waitUntil {
            val root = diagnostics.freshRoot()
            match = find(root, label)
            if (match?.isVisibleToUser == true) return@waitUntil true
            if (label == "Выбрать текст для заметки") {
                var chat = find(root, "Text selected for my note")
                while (chat != null && chat.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) chat = chat.parent
                if (chat != null) {
                    assertTrue("Scroll the originating message", chat.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    automation.waitForIdle(100, 2000)
                }
            }
            false
        }
        return checkNotNull(match)
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Clickable action: $label", diagnostics.click(label,node))
        automation.waitForIdle(100, 3000)
    }
    private fun field(label: String): AccessibilityNodeInfo {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> editable(child)?.let { return it } } }
            return null
        }
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) { editable(node)?.let { return it }; node = node.parent }
        error("No editable field: $label")
    }
    private fun edit(label: String, text: String) {
        assertTrue(field(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        waitUntil { field(label).text?.toString() == text }
    }
    private fun waitUntil(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(50) }
        diagnostics.capture("wait-timeout")
        fail("UI condition did not complete within 10 seconds")
    }
}

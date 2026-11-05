package com.alpha.assistant.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
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
    private val robot = ShellRobot("reviewed-context")

    /** Wrong MIME, malformed UTF-8, excess text, cancellation, and a stale result
     * leave the draft intact. Valid text requires review and a separate attach action. */
    @Test fun fileResultsRequireReviewAndPreserveTheDraft() {
        val fixture = ShellFixture("context-file")
        val resolver = fixture.context.contentResolver
        val documents = mutableListOf<Uri>()
        fun document(extension: String, mime: String, bytes: ByteArray): Uri {
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "context-${UUID.randomUUID()}.$extension")
                put(MediaStore.Downloads.MIME_TYPE, mime)
            }))
            documents += uri
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            return uri
        }
        val original = Draft("Мой день из календаря ежедневно 09:00")
        val other = Draft("Other draft")
        val tooLong = "Ж".repeat(8001).toByteArray()
        val limit = "Ж".repeat(8000)
        fixture.store.saveNewDraft(original)
        fixture.install()
        try {
            fixture.launch().use { scenario ->
                val supported = "Выберите JPEG, PNG, GIF, WebP или текст UTF-8 до 8000 символов."
                fun rejectFile(uri: Uri) {
                    pick("Файл", Intent.ACTION_OPEN_DOCUMENT, uri)
                    waitFor(supported)
                    assertEquals(original, fixture.store.load().draft)
                }
                fun cancelPick(label: String, action: String) {
                    pick(label, action)
                    // The previous error must clear, and the source must accept input again.
                    waitUntil {
                        val root = diagnostics.freshRoot()
                        if (find(root, supported) != null) return@waitUntil false
                        val source = clickTarget(find(root, label))
                        source?.isEnabled == true && source.isVisibleToUser &&
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK in source.actionList
                    }
                    assertEquals(original, fixture.store.load().draft)
                }
                click("Добавить контекст")
                click("Камера")
                waitFor("Открыть камеру")
                click("К источникам")
                click("Календарь")
                waitFor("Календарь · только чтение")
                click("К источникам")
                rejectFile(document("pdf", "application/pdf", "%PDF-1.4".toByteArray()))
                cancelPick("Фото", MediaStore.ACTION_PICK_IMAGES)
                rejectFile(document("txt", "text/plain", byteArrayOf(0xc3.toByte(), 0x28)))
                cancelPick("Файл", Intent.ACTION_OPEN_DOCUMENT)
                rejectFile(document("txt", "text/plain", tooLong))
                cancelPick("Файл", Intent.ACTION_OPEN_DOCUMENT)

                val valid = document("txt", "text/plain", limit.toByteArray())
                pick("Файл", Intent.ACTION_OPEN_DOCUMENT, valid) { fixture.model!!.editDraft(other) }
                waitFor("Черновик изменился. Откройте добавление контекста в нужном чате ещё раз.")
                waitUntil { fixture.store.load().draft == other }
                click("Отмена")
                scenario.onActivity { fixture.model!!.editDraft(original) }

                click("Добавить контекст")
                pick("Файл", Intent.ACTION_OPEN_DOCUMENT, valid)
                assertEquals(limit, field("Выбранный текст · до 8000 символов").text.toString())
                assertEquals(original, fixture.store.load().draft)
                click("Отмена")
                assertEquals(original, fixture.store.load().draft)

                click("Добавить контекст")
                pick("Файл", Intent.ACTION_OPEN_DOCUMENT, valid)
                val excerpt = "Фрагмент для проверки"
                edit("Выбранный текст · до 8000 символов", excerpt)
                waitFor("${excerpt.length} / 8000 · проверьте и при необходимости сократите текст")
                click("Прикрепить проверенный текст")
                waitUntil { fixture.store.load().draft.attachments.size == 1 }
                val reviewed = fixture.store.load().draft
                assertEquals(original, reviewed.copy(attachments = emptyList()))
                assertEquals(excerpt, reviewed.attachments.single().contextText)

                click("Добавить контекст")
                click("Мини-приложение")
                assertEquals(original.text, field("Request for a mini-app").text.toString())
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                click("Добавить контекст")
                click("Автоматизация")
                waitFor("Локальный шаблон «Мой день». Выбери календарь, период, инструмент и список. Проверь все параметры. Запрос не передавался в облако.")
                assertEquals(reviewed, fixture.store.load().draft)
                assertEquals(0, fixture.provider.calls)
            }
        } finally {
            documents.forEach { resolver.delete(it, null, null) }
            fixture.remove()
        }
    }

    private fun pick(label: String, action: String, uri: Uri? = null, beforeResult: () -> Unit = {}) {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != action) return null
                beforeResult()
                return Instrumentation.ActivityResult(
                    if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    uri?.let { Intent().setData(it) },
                )
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            click(label)
            waitUntil { monitor.hits == 1 }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

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
                val note = "Text selected for my note"
                val message = robot.find(note)?.takeIf { it.isVisibleToUser } ?: robot.reveal(note)
                val actions = generateSequence(message) { it.parent }.first { it.isLongClickable }
                assertTrue(actions.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                click("Выбрать текст для заметки"); waitFor("Передать выбранный текст в заметки"); click("Закрыть")
                assertEquals(reviewed, store.load().draft)
                robot.openDrawer(scenario); robot.click(robot.text(R.string.access)); robot.awaitDrawerClosed(scenario)
                waitFor(robot.text(R.string.access))
                assertEquals(reviewed, store.load().draft)
                assertEquals(0, sends)
                val evidence = File(context.getExternalFilesDir(null), "reviewed-context-evidence").apply { mkdirs() }
                checkNotNull(automation.takeScreenshot()).let { screenshot ->
                    File(evidence, "permissions.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    screenshot.recycle()
                }
                File(evidence, "workflow.txt").writeText("Tested revision: ${InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied")}\nCommand: python3 tools/compose_acceptance/run.py suite --label LABEL com.alpha.assistant.ui.ReviewedContextUiWorkflowTest\nInput: isolated persisted draft, image, reviewed pasted text.\nExpected and observed: same draft identity and original image; one reviewed text; no cloud sends.\n")
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
            match = find(diagnostics.freshRoot(), label)
            match?.isVisibleToUser == true
        }
        return checkNotNull(match)
    }
    private fun clickTarget(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }

    private fun click(label: String) {
        assertTrue("Clickable action: $label", diagnostics.click(label, clickTarget(waitFor(label))))
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

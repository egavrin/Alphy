package com.alpha.assistant.ui

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.cloud.*
import org.junit.Before
import org.junit.After
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Black-box device workflow. UiAutomation avoids Espresso's removed Android 17 input API. */
class ShellWorkflowTest {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureName = "shell-workflow-${UUID.randomUUID()}"
    private lateinit var store: ShellStore
    private lateinit var keys: SecureKeyStore
    @Before fun isolatedFixture() {
        store = ShellStore(context, "$fixtureName.db")
        keys = SecureKeyStore(context, fixtureName, fixtureName)
        check(keys.save("alpha-test-only").isSuccess)
        CloudTestActivity.factory = { app -> ShellViewModel(app, object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { emit(CloudEvent.RoundComplete("""{"kind":"answer","text":"Navigation fixture response","brief":"","targetId":null,"search":"none"}""",false)) }
        }, store, keys) }
    }
    @After fun removeFixture() {
        CloudTestActivity.factory = null
        SystemClock.sleep(150)
        store.close(); context.deleteDatabase("$fixtureName.db"); context.deleteDatabase("$fixtureName.db-tools.db"); keys.clear(); context.deleteSharedPreferences(fixtureName)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(fixtureName) }
    }
    // Failure cases: duplicate or bottom navigation, clipped touch targets, obsolete composer controls,
    // keyboard-blocked navigation, or a lost draft after the header opens a sheet or history.
    private fun assertHeaderAndComposer(statusBarBottom: Int = 0, keyboardTop: Int? = null) {
        val input = Rect().also { waitFor("Сообщение ассистенту").getBoundsInScreen(it) }
        for (label in listOf("Меню", "История")) {
            var node: AccessibilityNodeInfo? = waitFor(label)
            while (node != null && !node.isClickable) node = node.parent
            assertNotNull("Accessible header action $label", node)
            val bounds = Rect().also { node!!.getBoundsInScreen(it) }
            assertTrue("Header action remains visible: $label", node!!.isVisibleToUser)
            assertTrue("Header below status icons: $label", bounds.top >= statusBarBottom)
            assertTrue("Header action above composer: $label", bounds.bottom <= input.top)
            val minSize = (48 * context.resources.displayMetrics.density).toInt()
            assertTrue("48dp touch target: $label", bounds.width() >= minSize && bounds.height() >= minSize)
            fun count(current: AccessibilityNodeInfo?): Int {
                if (current == null) return 0
                var found = if (current.text?.toString() == label) 1 else 0
                repeat(current.childCount) { found += count(current.getChild(it)) }
                return found
            }
            assertEquals("One navigation action: $label", 1, count(automation.rootInActiveWindow))
        }
        for (label in listOf("Веб-поиск DeepSeek", "Облако", "Облачная обработка",
            "DeepSeek processes this chat and current attachments. Widget changes also send that tool’s code and data. History stays on this device.")) {
            assertNull("Removed composer content: $label", find(automation.rootInActiveWindow, label))
        }
        fun checkbox(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.className?.toString() == "android.widget.CheckBox") return true
            repeat(node.childCount) { if (checkbox(node.getChild(it))) return true }
            return false
        }
        assertFalse("No search checkbox", checkbox(automation.rootInActiveWindow))
        waitFor("Добавить контекст"); waitFor("Голосовой запрос"); waitFor("Отправить сообщение")
        if (keyboardTop != null) {
            val send = Rect().also { waitFor("Отправить сообщение").getBoundsInScreen(it) }
            val maxGap = (32 * context.resources.displayMetrics.density).toInt()
            assertTrue("Composer stays above the keyboard", send.bottom <= keyboardTop)
            assertTrue("Composer is adjacent to the keyboard: gap=${keyboardTop - send.bottom}", keyboardTop - send.bottom <= maxGap)
        }
    }
    private fun screenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "shell-header-evidence").apply { mkdirs() }
        val bitmap = checkNotNull(automation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @Test fun draftsHistoryAndNavigation() {
        ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
            assertHeaderAndComposer()
            screenshot("header-and-composer")
            edit("Сообщение ассистенту", "Shell workflow message")
            click("Отправить сообщение")
            assertTrue(waitFor("Navigation fixture response").isVisibleToUser)
            edit("Сообщение ассистенту", "Existing conversation draft")
            field("Сообщение ассистенту").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            automation.waitForIdle(100, 3000)
            var keyboardTop = 0
            var statusBarBottom = 0
            waitUntil {
                scenario.onActivity { activity ->
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                        val screenBottom = if (android.os.Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds.bottom
                            else android.content.res.Resources.getSystem().displayMetrics.heightPixels
                        keyboardTop = screenBottom - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        statusBarBottom = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                    }
                }
                keyboardTop > 0
            }
            screenshot("header-with-keyboard")
            val evidence = File(context.getExternalFilesDir(null), "shell-header-evidence").apply { mkdirs() }
            File(evidence, "keyboard-frame.txt").writeText("Tested revision: ${InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied")}\nCommand: connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.alpha.assistant.ui.ShellWorkflowTest\nInput: focus the existing chat draft with the keyboard enabled.\nExpected: visible header below status icons; composer gap at most 32dp.\nObserved insets: statusBarBottom=$statusBarBottom; keyboardTop=$keyboardTop\n")
            assertHeaderAndComposer(statusBarBottom, keyboardTop)
            click("Меню")
            click("Новый чат")
            assertEquals("", field("Сообщение ассистенту").text?.toString().orEmpty())
            edit("Сообщение ассистенту", "Separate new draft")
            click("История")
            edit("Поиск разговоров", "Shell workflow")
            click("Shell workflow message")
            assertEquals("Existing conversation draft", field("Сообщение ассистенту").text.toString())
            scenario.recreate()
            assertEquals("Existing conversation draft", field("Сообщение ассистенту").text.toString())
            click("Меню")
            click("Новый чат")
            assertEquals("Separate new draft", field("Сообщение ассистенту").text.toString())
        }
    }

    private fun edit(label: String, text: String) {
        val node = field(label)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        waitUntil { field(label).text?.toString() == text }
    }
    private fun field(label: String): AccessibilityNodeInfo {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> editable(child)?.let { return it } } }
            return null
        }
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null) {
            editable(node)?.let { return it }
            node = node.parent
        }
        error("No editable node for $label")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("No clickable node for $label", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        automation.waitForIdle(100, 3000)
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitUntil { result = find(automation.rootInActiveWindow, label); result != null }
        return result!!
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.trimEnd() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { match -> return match } }
        return null
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("UI condition did not complete within 10 seconds")
    }
}

package com.alpha.assistant.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { emit(CloudEvent.Text("Navigation fixture response")) }
        }, store, keys) }
    }
    @After fun removeFixture() {
        CloudTestActivity.factory = null
        SystemClock.sleep(150)
        store.close(); context.deleteDatabase("$fixtureName.db"); context.deleteDatabase("$fixtureName.db-tools.db"); keys.clear(); context.deleteSharedPreferences(fixtureName)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(fixtureName) }
    }
    @Test fun draftsHistoryAndNavigation() {
        ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
            edit("Сообщение ассистенту", "Shell workflow message")
            click("Отправить сообщение")
            click("Отправить")
            waitFor("Navigation fixture response")
            edit("Сообщение ассистенту", "Existing conversation draft")
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
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
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

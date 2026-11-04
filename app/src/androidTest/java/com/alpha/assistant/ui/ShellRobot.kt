package com.alpha.assistant.ui

import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import com.alpha.assistant.voice.VoiceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.UUID

internal typealias VoiceFactory = (Application, CoroutineScope, (String) -> Unit) -> VoiceSession

internal class FixtureProvider(private val reply: String = "Fixture response") : ChatProvider {
    @Volatile var calls = 0
    @Volatile var failure: Exception? = null              // thrown after the count
    @Volatile var gate: CompletableDeferred<Unit>? = null // awaited before the answer
    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        calls++
        failure?.let { throw it }
        gate?.await()
        emit(CloudEvent.RoundComplete("""{"kind":"answer","text":${JSONObject.quote(reply)},"brief":"","targetId":null,"search":"none"}""", false))
    }
}

/** An isolated shell database, key, and provider for one test. */
internal class ShellFixture(prefix: String) {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val name: String = "$prefix-${UUID.randomUUID()}"
    val store: ShellStore = ShellStore(context, "$name.db")
    val keys: SecureKeyStore = SecureKeyStore(context, name, name)
    val provider: FixtureProvider = FixtureProvider()
    @Volatile var model: ShellViewModel? = null

    fun install(saveKey: Boolean = true, voice: VoiceFactory? = null) {
        if (saveKey) check(keys.save("alpha-test-only").isSuccess)
        CloudTestActivity.factory = { app -> ShellViewModel(app, provider, store, keys, voiceFactory = voice).also { model = it } }
    }
    fun seedChat(id: String, title: String, message: String, selected: Boolean = false, updatedAt: Long = 1L) {
        store.saveConversation(Conversation(id, title, updatedAt, updatedAt, messages = listOf(Message("$id-u", "user", message, updatedAt, "sent"))))
        if (selected) store.saveSetting("selected", id)
    }
    fun launch(): ActivityScenario<CloudTestActivity> = ActivityScenario.launch(CloudTestActivity::class.java)
    fun remove() {
        CloudTestActivity.factory = null
        SystemClock.sleep(150)
        store.close(); context.deleteDatabase("$name.db"); context.deleteDatabase("$name.db-tools.db"); context.deleteDatabase("$name.db-tools.db-automations.db"); keys.clear(); context.deleteSharedPreferences(name)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
    }
}

/** Black-box UiAutomation helpers. Each lookup reads a fresh tree, so a poll never reads stale cached nodes. */
internal class ShellRobot(fixture: String) {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation: UiAutomation = instrumentation.uiAutomation
    val diagnostics: UiFailureDiagnostics = UiFailureDiagnostics(fixture)

    // Without arguments, the text is not formatted, as stringResource(id) does.
    fun text(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) instrumentation.targetContext.getString(id) else instrumentation.targetContext.getString(id, *args)
    fun find(label: String): AccessibilityNodeInfo? = find(diagnostics.freshRoot(), label)
    fun waitFor(label: String): AccessibilityNodeInfo {
        diagnostics.target(label)
        var result: AccessibilityNodeInfo? = null
        waitUntil { result = find(label); result != null }
        return result!!
    }
    fun waitGone(label: String) {
        diagnostics.target(label)
        waitUntil { find(label) == null }
    }
    fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        diagnostics.capture("wait-timeout")
        fail("UI condition did not complete within 10 seconds")
    }
    // Compose UI 1.12.1 marks only an enabled field as editable, so a field that is disabled for a short time needs a wait.
    fun field(label: String): AccessibilityNodeInfo {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> editable(child)?.let { return it } } }
            return null
        }
        diagnostics.target(label)
        var result: AccessibilityNodeInfo? = null
        waitUntil {
            var node = find(label)
            while (node != null && result == null) {
                result = editable(node)
                node = node.parent
            }
            result != null
        }
        return result!!
    }
    fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("No clickable node for $label", node)
        assertTrue(diagnostics.click(label,node))
        automation.waitForIdle(100, 3000)
    }
    fun edit(label: String, value: String) {
        val node = field(label)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        waitUntil { field(label).text?.toString() == value }
    }
    fun shell(command: String): String =
        automation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.trimEnd() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { match -> return match } }
        return null
    }
}

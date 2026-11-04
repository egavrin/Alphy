package com.alpha.assistant.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
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
        synchronized(this) { calls++ }
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
    /** Gives app strings only. A test reads a library label from the activity resources. */
    fun text(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) instrumentation.targetContext.getString(id) else instrumentation.targetContext.getString(id, *args)
    fun find(label: String): AccessibilityNodeInfo? = find(diagnostics.freshRoot(), label)
    fun waitFor(label: String): AccessibilityNodeInfo {
        diagnostics.target(label)
        var result: AccessibilityNodeInfo? = null
        waitUntil { result = find(label); result != null }
        return result!!
    }
    // A null root or another app's window is not proof that the label left the app.
    fun waitGone(label: String) {
        diagnostics.target(label)
        waitUntil {
            val root = diagnostics.freshRoot()
            root?.packageName?.toString() == instrumentation.targetContext.packageName && find(root, label) == null
        }
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
    // The climb stops at the first ancestor that holds a field and takes the first field in it.
    fun field(label: String): AccessibilityNodeInfo {
        fun textField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.toString() == "android.widget.EditText") return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> textField(child)?.let { return it } } }
            return null
        }
        diagnostics.target(label)
        var result: AccessibilityNodeInfo? = null
        waitUntil {
            var node = find(label)
            var nearest: AccessibilityNodeInfo? = null
            while (node != null && nearest == null) {
                nearest = textField(node)
                node = node.parent
            }
            result = nearest?.takeIf { it.isEditable }
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
    fun back() {
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        automation.waitForIdle(100, 3000)
    }
    fun secure(scenario: ActivityScenario<*>): Boolean {
        var flags = 0
        scenario.onActivity { flags = it.window.attributes.flags }
        return (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }
    /** [composed] and [composedText] read the composed nodes, also the nodes that a covering NavDisplay entry hides from accessibility. */
    fun composed(scenario: ActivityScenario<*>, tag: String): Boolean = composedNode(scenario) { it.getOrNull(SemanticsProperties.TestTag) == tag }
    fun composedText(scenario: ActivityScenario<*>, text: String): Boolean =
        composedNode(scenario) { config -> config.getOrNull(SemanticsProperties.Text)?.any { it.text.trimEnd() == text } == true }
    // The OPPO device blocks shell grants, so the helper answers the permission dialog.
    fun allowMicrophone(scenario: ActivityScenario<*>) {
        fun granted() = instrumentation.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted()) return
        scenario.onActivity { it.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 0) }
        fun allow(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.viewIdResourceName?.endsWith(":id/permission_allow_foreground_only_button") == true)
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            repeat(node.childCount) { if (allow(node.getChild(it))) return true }
            return false
        }
        waitUntil { allow(diagnostics.freshRoot()); granted() }
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

    private fun composedNode(scenario: ActivityScenario<*>, match: (SemanticsConfiguration) -> Boolean): Boolean {
        fun root(view: View): ViewRootForTest? = view as? ViewRootForTest
            ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
        var composed = false
        scenario.onActivity { activity ->
            composed = root(activity.window.decorView)!!.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).any { match(it.config) }
        }
        return composed
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.trimEnd() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { match -> return match } }
        return null
    }
}

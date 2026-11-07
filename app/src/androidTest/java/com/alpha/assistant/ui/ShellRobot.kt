package com.alpha.assistant.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.cloud.ChatProvider
import com.alpha.assistant.cloud.CloudEvent
import com.alpha.assistant.cloud.CloudRequest
import com.alpha.assistant.cloud.CloudSession
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.data.SecureKeyStore
import com.alpha.assistant.data.ShellStore
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import com.alpha.assistant.tools.replyWithFixturePlan
import com.alpha.assistant.voice.VoiceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal typealias VoiceFactory = (Application, CoroutineScope, (String) -> Unit) -> VoiceSession

internal class FixtureProvider(private val reply: String = "Fixture response") : ChatProvider {
    @Volatile var calls = 0
    var automationDraft: String? = null
    @Volatile var failure: Exception? = null              // thrown after the count
    @Volatile var gate: CompletableDeferred<Unit>? = null // awaited before the answer
    override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) {
        synchronized(this) { calls++ }
        failure?.let { throw it }
        gate?.await()
        automationDraft?.let { arguments ->
            val definition = com.alpha.assistant.automations.AutomationDraftTool.bind(arguments, checkNotNull(request.automationDraftChatId))
            emit(CloudEvent.RoundComplete("", false, com.alpha.assistant.data.MessageProvenance.CLEAN, automationDraft = definition.json))
            return
        }
        if (request.replyWithFixturePlan(emit)) return
        if (request.generationStage != null) {
            emit(CloudEvent.Text("""{"formatVersion":3,"title":"Fixture","description":"","capabilities":[],"stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"text","text":"Count","bind":"count"},"source":"return {state:input.state};"}"""))
            return
        }
        emit(CloudEvent.RoundComplete("""{"kind":"answer","text":${JSONObject.quote(reply)},"brief":"","search":"none"}""", false))
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

/** Sends Back events to a dispatcher, as the system Back input does. Call it on the main thread. */
internal class TestBackInput : NavigationEventInput() {
    fun start(event: NavigationEvent) = dispatchOnBackStarted(event)
    fun progress(event: NavigationEvent) = dispatchOnBackProgressed(event)
    fun complete() = dispatchOnBackCompleted()
}

/** Black-box UiAutomation helpers. On API 34 and later, each lookup reads a fresh tree, so a poll never reads stale cached nodes. */
internal class ShellRobot(fixture: String) {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation: UiAutomation = instrumentation.uiAutomation
    val diagnostics: UiFailureDiagnostics = UiFailureDiagnostics(fixture)

    // Without arguments, the text is not formatted, as stringResource(id) does.
    /** Gives app strings only. A test reads a library label from the activity resources. */
    fun text(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) instrumentation.targetContext.getString(id) else instrumentation.targetContext.getString(id, *args)
    fun find(label: String): AccessibilityNodeInfo? = find(diagnostics.freshRoot(), label)
    /** The text and contentDescription values of all nodes in the active window. A node outside a scroll viewport can be absent. */
    fun texts(): List<String> = texts(diagnostics.freshRoot())
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
    fun waitUntil(condition: () -> Boolean) = waitUntil(10_000, condition)
    fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        diagnostics.capture("wait-timeout")
        fail("UI condition did not complete within ${timeoutMs / 1000} seconds")
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
        diagnostics.target(label)
        var node: AccessibilityNodeInfo? = null
        waitUntil {
            node = generateSequence(find(label)) { it.parent }.firstOrNull { it.isClickable }
            node?.let { it.isVisibleToUser && it.isEnabled && AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK in it.actionList } == true
        }
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
    // The scrim gets its label only after the drawer settles open, and loses it only after the drawer settles closed.
    fun openDrawer(scenario: ActivityScenario<*>) {
        click(text(R.string.menu))
        awaitDrawerOpen(scenario)
    }
    fun awaitDrawerOpen(scenario: ActivityScenario<*>) = waitFor(closeDrawerLabel(scenario))
    fun awaitDrawerClosed(scenario: ActivityScenario<*>) = waitGone(closeDrawerLabel(scenario))
    /** The top of the keyboard in screen pixels, or null while the keyboard is hidden. */
    fun keyboardTop(scenario: ActivityScenario<*>): Int? {
        var top: Int? = null
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(decor)
            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
                top = origin[1] + decor.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
        }
        return top
    }
    /** Returns the screen bounds of the checked clickable node. */
    fun assertTarget(label: String): Rect {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("No clickable node for $label", node)
        val bounds = Rect().also { node!!.getBoundsInScreen(it) }
        // The safe area is the window without the system bars and the display cutout, as ShellWorkflowTest bounds the status bar.
        val safe = Rect()
        instrumentation.runOnMainSync {
            val decor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().window.decorView
            val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            safe.set(origin[0] + insets.left, origin[1] + insets.top, origin[0] + decor.width - insets.right, origin[1] + decor.height - insets.bottom)
        }
        val minSize = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        assertTrue("Target is visible: $label", node!!.isVisibleToUser)
        assertTrue("Target $bounds is inside the safe area $safe: $label", safe.contains(bounds))
        assertTrue("48dp touch target $bounds: $label", bounds.width() >= minSize && bounds.height() >= minSize)
        return bounds
    }
    fun bounds(label: String): Rect = Rect().also { waitFor(label).getBoundsInScreen(it) }
    fun rotate(scenario: ActivityScenario<*>, landscape: Boolean) {
        scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        waitUntil {
            var orientation = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity { orientation = it.resources.configuration.orientation }
            val root = Rect().also { diagnostics.freshRoot()?.getBoundsInScreen(it) }
            orientation == expected && if (landscape) root.width() > root.height() else root.height() > root.width()
        }
    }
    /** Runs [block] with a test Back input on the activity dispatcher, and removes the input after the block. */
    fun <T> withBackInput(scenario: ActivityScenario<*>, block: (TestBackInput) -> T): T {
        val input = TestBackInput()
        scenario.onActivity { (it as NavigationEventDispatcherOwner).navigationEventDispatcher.addInput(input) }
        try { return block(input) } finally { scenario.onActivity { (it as NavigationEventDispatcherOwner).navigationEventDispatcher.removeInput(input) } }
    }
    /** Waits for [count] Choreographer frames. A frame count is a condition, not a fixed sleep. */
    fun awaitFrames(count: Int) {
        val frames = CountDownLatch(count)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    frames.countDown()
                    if (frames.count > 0) Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        assertTrue("$count frames did not pass within 10 seconds", frames.await(10, TimeUnit.SECONDS))
    }
    /** The visible right edge of the Search row. Read on the main thread. */
    fun drawerRowRight(activity: Activity): Float = nodes(activity, merged = true).firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.trimEnd() == text(R.string.search) } == true &&
            SemanticsActions.OnClick in node.config && node.boundsInRoot.width > 0f
    }?.boundsInRoot?.right ?: 0f
    /** Main thread only. The scrim has its label only while the drawer is settled open. */
    fun scrimLabeled(activity: Activity): Boolean = activity.getString(androidx.compose.ui.R.string.close_drawer).let { label ->
        nodes(activity).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
    }
    /** [composed] and [composedText] read the composed nodes, also the nodes that a covering NavDisplay entry hides from accessibility. */
    fun composed(scenario: ActivityScenario<*>, tag: String): Boolean = composedNode(scenario) { it.getOrNull(SemanticsProperties.TestTag) == tag }
    fun composedText(scenario: ActivityScenario<*>, text: String): Boolean =
        composedNode(scenario) { config -> config.getOrNull(SemanticsProperties.Text)?.any { it.text.trimEnd() == text } == true }
    fun composedLink(scenario: ActivityScenario<*>, label: String, url: String): Boolean =
        composedNode(scenario) { config -> config.getOrNull(SemanticsProperties.Text)?.any { text ->
            text.getLinkAnnotations(0, text.length).any {
                (it.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url == url &&
                    text.text.substring(it.start, it.end) == label
            }
        } == true }
    /** The window bounds of the composed node with [tag] and the width of its Compose root, in pixels. */
    fun composedBounds(scenario: ActivityScenario<*>, tag: String): Pair<Rect, Int> {
        var result: Pair<Rect, Int>? = null
        scenario.onActivity { activity ->
            val node = nodes(activity).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            val bounds = node.boundsInWindow
            result = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt()) to
                node.layoutInfo.coordinates.findRootCoordinates().size.width
        }
        return checkNotNull(result)
    }
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
        enter(label, value)
        waitUntil { field(label).text?.toString() == value }
    }
    // A password field shows dots, so this helper does not compare the text.
    fun enter(label: String, value: String) {
        assertTrue(field(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        automation.waitForIdle(100, 3000)
    }
    /** Scrolls a visible container that supports the requested direction. */
    fun scrollForward(requireBoundsChange: Boolean = true): Boolean =
        scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, requireBoundsChange)

    private fun scroll(action: Int, requireBoundsChange: Boolean = true): Boolean {
        fun containers(node: AccessibilityNodeInfo?, path: List<Int> = emptyList()): Sequence<List<Int>> = sequence {
            if (node == null || !node.isVisibleToUser) return@sequence
            if (node.isScrollable && node.actionList.any { it.id == action }) yield(path)
            repeat(node.childCount) { yieldAll(containers(childOrNull(node, it), path + it)) }
        }
        fun current(path: List<Int>): AccessibilityNodeInfo? =
            path.fold(diagnostics.freshRoot()) { node, index -> node?.let { childOrNull(it, index) } }
        val paths = containers(diagnostics.freshRoot()).toList()
        for (path in paths) {
            val node = current(path) ?: continue
            val before = childBounds(node)
            if (node.performAction(action)) {
                awaitScrollEnd { current(path)?.let(::childBounds).orEmpty() }
                if (!requireBoundsChange || current(path)?.let(::childBounds) != before) return true
            }
        }
        return false
    }
    /** Scroll to [label]. A null direction permits searching both ways after a layout change. */
    fun reveal(label: String, forward: Boolean? = true): AccessibilityNodeInfo {
        diagnostics.target(label)
        var node = find(label)
        val directions = if (forward == null) listOf(true, false) else listOf(forward)
        for (down in directions) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            repeat(10) { if (node == null) { scroll(action); node = find(label) } }
        }
        val target = node?.let(::revealTarget)
        if (target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) == true) {
            // Android can accept this action in a stationary dialog. Sample the target even when it has no scroll container.
            awaitScrollEnd {
                val current = find(label)?.let(::revealTarget) ?: return@awaitScrollEnd null
                val container = generateSequence(current) { it.parent }.firstOrNull { it.isScrollable }
                listOf(Rect().also(current::getBoundsInScreen)) + container?.let(::childBounds).orEmpty()
            }
        }
        return waitFor(label)
    }

    private fun revealTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: node

    fun shell(command: String): String =
        automation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }

    private fun childOrNull(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = try {
        if (index < node.childCount) node.getChild(index) else null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun childBounds(node: AccessibilityNodeInfo): List<Rect> =
        List(node.childCount) { index -> Rect().also { childOrNull(node, index)?.getBoundsInScreen(it) } }

    // A scroll action returns before its animation starts, and waitForIdle can return before the content moves.
    // After two frames the animation runs. Wait for two reads of the relevant bounds to agree.
    private fun awaitScrollEnd(readBounds: () -> List<Rect>?) {
        awaitFrames(2)
        var last: List<Rect>? = null
        waitUntil {
            val content = readBounds()
            (content != null && content == last).also { last = content }
        }
    }
    // A library label comes from the activity resources, which have the Russian app locale.
    private fun closeDrawerLabel(scenario: ActivityScenario<*>): String {
        var label = ""
        scenario.onActivity { label = it.getString(androidx.compose.ui.R.string.close_drawer) }
        return label
    }
    private fun composedNode(scenario: ActivityScenario<*>, match: (SemanticsConfiguration) -> Boolean): Boolean {
        var composed = false
        scenario.onActivity { activity -> composed = nodes(activity).any { match(it.config) } }
        return composed
    }
    private fun nodes(activity: Activity, merged: Boolean = false): List<SemanticsNode> {
        fun root(view: View): ViewRootForTest? = view as? ViewRootForTest
            ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
        return root(activity.window.decorView)!!.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged)
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.trimEnd() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(childOrNull(node, it), label)?.let { match -> return match } }
        return null
    }
    private fun texts(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) + (0 until node.childCount).flatMap { texts(node.getChild(it)) }
}

package com.alpha.assistant.ui

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import android.os.Bundle
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
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
    private val diagnostics = UiFailureDiagnostics("shell")
    private var keyboardComparison = "Assertion bounds: not sampled"
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
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { emit(CloudEvent.RoundComplete("""{"kind":"answer","text":"Navigation fixture response","brief":"","search":"none"}""",false)) }
        }, store, keys).openSeededChat(store) }
    }
    @After fun removeFixture() {
        CloudTestActivity.factory = null
        SystemClock.sleep(150)
        store.close(); context.deleteDatabase("$fixtureName.db"); context.deleteDatabase("$fixtureName.db-tools.db"); keys.clear(); context.deleteSharedPreferences(fixtureName)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(fixtureName) }
    }
    // Failure cases: duplicate or bottom navigation, clipped touch targets, obsolete composer controls,
    // keyboard-blocked navigation, or a lost draft after the top bar or the drawer changes the route.
    private fun assertHeaderAndComposer(statusBarBottom: Int = 0, keyboardTop: Int? = null, home: Boolean = false) {
        val input = Rect().also { waitFor("Сообщение ассистенту").getBoundsInScreen(it) }
        val newChat = context.getString(R.string.new_chat)
        if (home) assertNull(find(diagnostics.freshRoot(), newChat))
        for (label in if (home) listOf(context.getString(R.string.menu)) else listOf(context.getString(R.string.menu), newChat)) {
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
                var found = if (current.text?.toString() == label || current.contentDescription?.toString() == label) 1 else 0
                repeat(current.childCount) { found += count(current.getChild(it)) }
                return found
            }
            assertEquals("One navigation action: $label", 1, count(diagnostics.freshRoot()))
        }
        for (label in listOf("Веб-поиск DeepSeek", "Облако", "Облачная обработка",
            "DeepSeek processes this chat and current attachments. Widget changes also send that tool’s code and data. History stays on this device.")) {
            assertNull("Removed composer content: $label", find(diagnostics.freshRoot(), label))
        }
        fun checkbox(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.className?.toString() == "android.widget.CheckBox") return true
            repeat(node.childCount) { if (checkbox(node.getChild(it))) return true }
            return false
        }
        assertFalse("No search checkbox", checkbox(diagnostics.freshRoot()))
        waitFor("Добавить контекст"); waitFor("Голосовой запрос"); waitFor("Отправить сообщение")
        if (keyboardTop != null) {
            val send = Rect().also { waitFor("Отправить сообщение").getBoundsInScreen(it) }
            val maxGap = (32 * context.resources.displayMetrics.density).toInt()
            keyboardComparison = "Assertion uptimeMs=${SystemClock.uptimeMillis()} keyboardTop=$keyboardTop sendBounds=$send maxGap=$maxGap"
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
        val robot = ShellRobot("shell")
        ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
            assertHeaderAndComposer(home = true)
            screenshot("header-and-composer")
            edit("Сообщение ассистенту", "Shell workflow message")
            click("Отправить сообщение")
            assertTrue(waitFor("Navigation fixture response").isVisibleToUser)
            // The Desktop entry leaves composition at the end of the push transition.
            waitUntil { !robot.composed(scenario, "desktop") }
            edit("Сообщение ассистенту", "Existing conversation draft")
            val imeFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                decor.viewTreeObserver.addOnPreDrawListener {
                    val insets = ViewCompat.getRootWindowInsets(decor)
                    val bounds = Rect().also { rect -> val location = IntArray(2); decor.getLocationOnScreen(location); rect.set(location[0], location[1], location[0] + decor.width, location[1] + decor.height) }
                    imeFrames += "uptimeMs=${SystemClock.uptimeMillis()} imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} " +
                        "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} decorBounds=$bounds animation=unknown"
                    if (imeFrames.size > 30) imeFrames.removeAt(0)
                    true
                }
                diagnostics.state = {
                    var current = "unknown"
                    scenario.onActivity { currentActivity ->
                        val currentDecor = currentActivity.window.decorView
                        val insets = ViewCompat.getRootWindowInsets(currentDecor)
                        val bounds = Rect().also { rect -> val location = IntArray(2); currentDecor.getLocationOnScreen(location); rect.set(location[0], location[1], location[0] + currentDecor.width, location[1] + currentDecor.height) }
                        current = "uptimeMs=${SystemClock.uptimeMillis()} imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} " +
                            "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} decorBounds=$bounds animation=unknown"
                    }
                    "$keyboardComparison\nCurrent insets: $current\nPassive frame samples:\n${imeFrames.joinToString("\n")}\nSettled animation: unknown"
                }
            }
            val imeAnimating = java.util.concurrent.atomic.AtomicBoolean(false)
            scenario.onActivity { activity ->
                ViewCompat.setWindowInsetsAnimationCallback(activity.window.decorView,
                    object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating.set(true)
                        }
                        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>): WindowInsetsCompat = insets
                        override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating.set(false)
                        }
                    })
            }
            field("Сообщение ассистенту").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            automation.waitForIdle(100, 3000)
            var keyboardTop = 0
            var statusBarBottom = 0
            var previousLayout = ""
            var stableSince = SystemClock.uptimeMillis()
            waitUntil {
                var layoutReady = false
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(decor)
                    if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                        val screenBottom = if (android.os.Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds.bottom
                            else android.content.res.Resources.getSystem().displayMetrics.heightPixels
                        keyboardTop = screenBottom - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        statusBarBottom = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                        layoutReady = !decor.isLayoutRequested && !imeAnimating.get()
                    }
                }
                val send = find(diagnostics.freshRoot(), "Отправить сообщение")
                val bounds = Rect().also { send?.getBoundsInScreen(it) }
                val sample = "$keyboardTop:$statusBarBottom:$bounds"
                val now = SystemClock.uptimeMillis()
                if (!layoutReady || send == null || sample != previousLayout) stableSince = now
                previousLayout = sample
                keyboardComparison = "Settled-layout sample uptimeMs=$now imeAnimating=${imeAnimating.get()} layoutReady=$layoutReady keyboardTop=$keyboardTop sendBounds=$bounds"
                keyboardTop > 0 && layoutReady && send != null && now - stableSince >= 200
            }
            screenshot("header-with-keyboard")
            val evidence = File(context.getExternalFilesDir(null), "shell-header-evidence").apply { mkdirs() }
            File(evidence, "keyboard-frame.txt").writeText("Tested revision: ${InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied")}\nCommand: adb -s emulator-5556 shell am instrument -w -r -e class com.alpha.assistant.ui.ShellWorkflowTest#draftsHistoryAndNavigation com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner\nInput: focus the existing chat draft with the keyboard enabled.\nExpected: visible header below status icons; composer gap at most 32dp.\nObserved insets: statusBarBottom=$statusBarBottom; keyboardTop=$keyboardTop\n$keyboardComparison\n")
            try { assertHeaderAndComposer(statusBarBottom, keyboardTop) }
            catch (failure: Throwable) {
                diagnostics.capture("keyboard-assertion-failed")
                diagnostics.capture("keyboard-after-failure-capture")
                throw failure
            }
            click(robot.text(R.string.new_chat))
            // The Chat entry leaves composition at the end of the pop transition.
            waitUntil { !robot.composedText(scenario, "Navigation fixture response") }
            assertEquals("", field("Сообщение ассистенту").text?.toString().orEmpty())
            edit("Сообщение ассистенту", "Separate new draft")
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.search))
            robot.awaitDrawerClosed(scenario)
            edit("Поиск разговоров", "Shell workflow")
            click("Shell workflow message")
            assertEquals("Existing conversation draft", field("Сообщение ассистенту").text.toString())
            scenario.recreate()
            assertEquals("Existing conversation draft", field("Сообщение ассистенту").text.toString())
            click(robot.text(R.string.new_chat))
            assertEquals("Separate new draft", field("Сообщение ассистенту").text.toString())
        }
    }

    // Failure case: in landscape with the keyboard, the composer gets no height, so its field and buttons are not usable.
    @Test fun landscapeKeyboardKeepsComposerUsable() {
        val robot = ShellRobot("shell")
        store.saveConversation(Conversation("landscape", "Landscape chat", 1L, 1L, messages = listOf(Message("landscape-u", "user", "Landscape message", 1L, "sent"))))
        store.seedOpenChat("landscape")
        ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
            try {
                robot.waitFor("Landscape message")
                robot.rotate(scenario, landscape = true)
                val labels = listOf(R.string.composer_label, R.string.composer_add_context, R.string.composer_voice, R.string.composer_send).map { robot.text(it) }
                assertTrue(robot.field(labels[0]).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                robot.waitUntil { robot.keyboardTop(scenario) != null }
                robot.edit(labels[0], "Первая строка\nВторая строка\nТретья строка")
                // The keyboard inset animates, so the check waits until the keyboard top and the bounds stay the same for 500 ms.
                var last = ""
                var since = SystemClock.uptimeMillis()
                robot.waitUntil {
                    val sample = "${robot.keyboardTop(scenario)} " + labels.map { label -> robot.find(label)?.let { node -> Rect().also { node.getBoundsInScreen(it) } } }
                    if (sample != last) { last = sample; since = SystemClock.uptimeMillis() }
                    robot.keyboardTop(scenario) != null && SystemClock.uptimeMillis() - since >= 500
                }
                val keyboardTop = checkNotNull(robot.keyboardTop(scenario))
                for (label in labels) {
                    val bounds = robot.assertTarget(label)
                    assertTrue("$label $bounds ends at or above the keyboard top $keyboardTop", bounds.bottom <= keyboardTop)
                }
            } finally {
                scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
                robot.rotate(scenario, landscape = false)
            }
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
        assertTrue(diagnostics.click(label,node))
        automation.waitForIdle(100, 3000)
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        diagnostics.target(label)
        var result: AccessibilityNodeInfo? = null
        // A fresh root holds no cached node of an entry that a transition covers or removes.
        waitUntil { result = find(diagnostics.freshRoot(), label); result != null }
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
        diagnostics.capture("wait-timeout")
        fail("UI condition did not complete within 10 seconds")
    }
}

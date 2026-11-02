package com.alpha.assistant.chat

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.ShellViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Renders real chat messages. HTTP links are clickable; unsafe schemes and embedded images cannot launch or fetch. */
@RunWith(AndroidJUnit4::class)
class MarkdownWorkflowTest {
    @Test fun chatMarkdownKeepsStoredSourceAndRendersNativeTextAndSafeLinks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val name = "markdown-${UUID.randomUUID()}"
        val store = ShellStore(app,"$name.db")
        val keys = SecureKeyStore(app,name,name)
        val markdown = "# Heading\n\n**Bold** and *italic* with `code`.\n\n- First\n- Second\n\n> A quote\n\n```kotlin\nval count = 1\n```\n\n[Source](https://example.com/facts) [Unsafe](javascript:alert(1))\n\n![Remote image](https://example.com/image.png)"
        store.saveConversation(Conversation("chat","Markdown",1,1,messages = listOf(Message("m","assistant",markdown,1,"complete"))))
        store.saveSetting("selected","chat")
        lateinit var model: ShellViewModel
        val provider = object : ChatProvider {
            override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) = error("No request expected")
        }
        CloudTestActivity.factory = { ShellViewModel(it,provider,store,keys).also { created -> model = created } }
        val scenario = ActivityScenario.launch(CloudTestActivity::class.java)
        fun collect(node: android.view.accessibility.AccessibilityNodeInfo?): String {
            if(node == null) return ""
            return node.text?.toString().orEmpty() + "\n" + (0 until node.childCount).joinToString("\n") { collect(node.getChild(it)) }
        }
        try {
            val deadline = SystemClock.uptimeMillis() + 10000
            var text = ""
            while(SystemClock.uptimeMillis() < deadline) {
                text = collect(instrumentation.uiAutomation.rootInActiveWindow)
                if(text.contains("Heading") && text.contains("First")) break
                SystemClock.sleep(50)
            }
            assertTrue(text.contains("Heading")); assertFalse(text.contains("# Heading"))
            assertTrue(text.contains("Bold")); assertFalse(text.contains("**Bold**"))
            assertTrue(text.contains("First")); assertEquals(markdown,model.snapshot.selected!!.messages.single().text)
            // The native link policy is also used by the renderer, without ACTION_VIEW for unsupported schemes.
            assertTrue(validSourceUrl("https://example.com/facts"))
            assertFalse(validSourceUrl("javascript:alert(1)")); assertFalse(validSourceUrl("intent://device"))
        } finally {
            scenario.close(); CloudTestActivity.factory = null; SystemClock.sleep(150)
            store.close(); app.deleteDatabase("$name.db"); app.deleteDatabase("$name.db-tools.db")
            keys.clear(); app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }
}

package com.alpha.assistant.chat

import android.app.Application
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
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

    @Test fun chatMarkdownTablesExposeCellsAndScrollToLaterColumns() {
        val fixture = ShellFixture("markdown-table")
        val robot = ShellRobot("markdown-table")
        val malformed = "| Malformed A | Malformed B |\n| --- |"
        val markdown = """
            Table introduction.

            | **City** | Time | Forecast | Alert | Zone | Details |
            | --- | ---: | :---: | --- | --- | --- |
            | [Moscow](https://example.com/facts) | [Unsafe](javascript:alert(1)) | Cloudy | Off | Local | Evening |

        """.trimIndent() + "\n\n" + malformed
        fixture.store.saveConversation(Conversation("chat", "Markdown table", 1, 1,
            messages = listOf(Message("m", "assistant", markdown, 1, "complete"))))
        fixture.store.saveSetting("selected", "chat")
        fixture.install(saveKey = false)
        try {
            fixture.launch().use {
                robot.waitFor("Table introduction.")
                val city = robot.waitFor("City")
                assertTrue(city.isHeading)
                assertEquals(0, city.collectionItemInfo?.rowIndex)
                assertEquals(0, city.collectionItemInfo?.columnIndex)
                val moscow = robot.waitFor("Moscow")
                assertEquals(1, moscow.collectionItemInfo?.rowIndex)
                assertEquals(1, clickableSpans(moscow))
                assertEquals(0, clickableSpans(robot.waitFor("Unsafe")))
                assertTrue(robot.texts().any { text -> text.contains(malformed) })

                val table = generateSequence(city) { node -> node.parent }.first { node ->
                    node.collectionInfo?.columnCount == 6
                }
                assertEquals(2, table.collectionInfo.rowCount)
                assertTrue(table.isScrollable)
                assertFalse(robot.find("Details")?.isVisibleToUser == true)
                repeat(5) {
                    if (robot.find("Details")?.isVisibleToUser != true) {
                        assertTrue(table.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id))
                        robot.automation.waitForIdle(100, 3000)
                    }
                }
                assertTrue(robot.waitFor("Details").isVisibleToUser)
                assertTrue(robot.waitFor("Evening").isVisibleToUser)
                assertEquals(markdown, fixture.model!!.snapshot.selected!!.messages.single().text)
            }
        } finally {
            fixture.remove()
        }
    }

    private fun clickableSpans(node: AccessibilityNodeInfo): Int {
        val text = node.text as? Spanned ?: return 0
        return text.getSpans(0, text.length, ClickableSpan::class.java).size
    }
}

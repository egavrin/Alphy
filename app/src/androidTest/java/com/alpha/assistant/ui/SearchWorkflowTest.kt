package com.alpha.assistant.ui

import android.content.res.Resources
import android.graphics.Rect
import com.alpha.assistant.R
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Draft
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SearchWorkflowTest {
    private val fixture = ShellFixture("search")
    private val robot = ShellRobot("search")
    @After fun remove() = fixture.remove()

    @Test fun searchShowsMatchesAndKeepsFieldAboveKeyboard() {
        fixture.install()
        val updated = 1_793_000_000_000L
        fixture.seedChat("dated", "Dated chat", "Unrelated words. ".repeat(12) + "\nCheck COMPILER.2 in the report.", updatedAt = updated)
        fixture.seedChat("title", "Compiler.2 guide", "Other message", updatedAt = updated - 1)
        fixture.store.saveConversation(Conversation("draft", "Prototype notes", updated - 2, updated - 2,
            draft = Draft("Try compiler.2 in the draft.")))
        fixture.seedChat("other", "Unrelated chat", "Nothing to match", updatedAt = updated - 3)
        fixture.launch().use { scenario ->
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.search))
            robot.awaitDrawerClosed(scenario)
            val searchField = robot.text(R.string.search_field)
            val recent = robot.text(R.string.search_recent)
            val evidence = File(fixture.context.getExternalFilesDir(null), "search-keyboard-bounds.txt")
            evidence.writeText("Expected: focused field above the visible keyboard, with a gap of at most 24 dp.\n")
            fun checkKeyboard(state: String) {
                robot.waitUntil { robot.keyboardTop(scenario) != null && robot.field(searchField).isFocused }
                robot.awaitFrames(24)
                val keyboard = checkNotNull(robot.keyboardTop(scenario))
                val field = robot.field(searchField)
                val bounds = Rect().also(field::getBoundsInScreen)
                val maxGap = 24 * fixture.context.resources.displayMetrics.density
                evidence.appendText("$state: field=$bounds, keyboardTop=$keyboard, gap=${keyboard - bounds.bottom}px\n")
                assertTrue("Focused search field is visible: $bounds", field.isFocused && field.isVisibleToUser)
                assertTrue("Search field $bounds is directly above keyboard at $keyboard",
                    bounds.height() > 0 && bounds.bottom <= keyboard && keyboard - bounds.bottom <= maxGap)
            }
            robot.waitFor(recent)
            checkKeyboard("Recent chats")
            val date = DateFormat.getDateInstance(DateFormat.SHORT, Locale.forLanguageTag("ru")).format(Date(updated))
            robot.waitFor(date)
            robot.assertTarget(robot.text(R.string.conversation_actions, "Dated chat"))
            assertNull(robot.find("⋯"))
            assertFalse(robot.composedText(scenario, "⋯"))
            assertNull(robot.find(robot.text(R.string.new_chat)))
            robot.edit(searchField, "compiler.2")
            robot.waitFor(robot.text(R.string.search_results))
            robot.waitFor("Compiler.2 guide")
            robot.waitFor("…compiler.2 in the draft.")
            robot.waitFor("…COMPILER.2 in the report.")
            robot.waitGone("Unrelated chat")
            checkKeyboard("Results")
            robot.click(robot.text(R.string.search_clear_query))
            robot.waitFor(recent)
            robot.waitFor("Unrelated chat")
            checkKeyboard("Cleared query")
            robot.edit(searchField, "нет такого")
            robot.waitFor(robot.text(R.string.search_none))
            robot.click(robot.text(R.string.search_back))
            robot.waitGone(searchField)
        }
    }

    // Failure case: at font scale 2.0, the clear dialog cuts its text, and clear_history_kept gets no height.
    @Test fun clearDialogTextScrollsAtLargeFont() {
        val scale = robot.shell("settings get system font_scale")
        try {
            robot.shell("settings put system font_scale 2.0")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == 2f }
            fixture.install()
            fixture.seedChat("large", "Large chat", "Large message")
            fixture.launch().use { scenario ->
                robot.openDrawer(scenario)
                robot.click(robot.text(R.string.search))
                robot.awaitDrawerClosed(scenario)
                robot.click(robot.text(R.string.search_history_actions))
                robot.click(robot.text(R.string.clear_history))
                val kept = robot.reveal(robot.text(R.string.clear_history_kept))
                val bounds = Rect().also { kept.getBoundsInScreen(it) }
                assertTrue("clear_history_kept is on the screen: $bounds", kept.isVisibleToUser && bounds.height() > 0)
            }
        } finally {
            // "settings get" prints "null" when the value is unset, and "settings put … null" does not restore that state.
            robot.shell(if (scale.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $scale")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == (scale.toFloatOrNull() ?: 1f) }
        }
    }
}

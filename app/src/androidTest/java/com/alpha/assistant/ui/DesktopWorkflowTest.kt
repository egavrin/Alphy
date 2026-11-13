package com.alpha.assistant.ui

import android.content.res.Resources
import android.util.Log
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.data.Draft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class DesktopWorkflowTest {
    private val fixture = ShellFixture("desktop")
    private val robot = ShellRobot("desktop")
    @After fun remove() = fixture.remove()

    // The hour can change between composition and the check.
    private fun greetings(name: String) = (-1..1)
        .map { greetingRes((LocalTime.now().hour + it + 24) % 24) }
        .map { robot.text(R.string.greeting_named, robot.text(it), name) }

    @Test fun desktopGreetsByNameWithoutSyntheticStatusCards() {
        fixture.install(saveKey = false)
        fixture.store.saveSetting("userName", "Евгений")
        fixture.launch().use { scenario ->
            robot.waitUntil { greetings("Евгений").any { robot.find(it) != null } }
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.reveal("Здесь появятся ваши мини-приложения")
            robot.reveal("Сделай таймер для готовки")
            assertNull(robot.find(robot.text(R.string.desktop_edit)))
            assertNull(robot.find(robot.text(R.string.desktop_open_library)))
            assertNull(robot.find(robot.text(R.string.onboarding_key_title)))
            assertNull(robot.find(robot.text(R.string.now_day_title)))
            robot.assertTarget(robot.text(R.string.composer_label))
        }
    }

    @Test fun exampleChipAddsItsTextToTheComposer() {
        fixture.install(saveKey = false)
        val composer = robot.text(R.string.composer_label)
        val example = robot.text(R.string.desktop_example_list)
        fixture.launch().use {
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.edit(composer, "Молоко")
            robot.reveal(example)
            robot.click(example)
            // The chip keeps the typed text.
            robot.waitUntil { robot.field(composer).text?.toString() == "Молоко\n$example" }
            assertEquals(0, fixture.provider.calls)
        }
    }

    @Test fun desktopSendOpensChatAndBackReturns() {
        fixture.install()
        fixture.launch().use {
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.assertTarget(robot.text(R.string.composer_label))
            robot.diagnostics.capture("desktop-home")
            robot.instrumentation.waitForIdleSync()
            assertEquals(0, fixture.provider.calls)
            robot.edit(robot.text(R.string.composer_label), "Desktop question")
            robot.click(robot.text(R.string.composer_send))
            robot.waitFor("Fixture response")
            assertEquals(1, fixture.provider.calls)
            assertEquals(1, robot.texts().count { it.trim() == "Desktop question" })
            robot.diagnostics.capture("desktop-chat")
            robot.back()
            robot.waitGone("Fixture response")
            robot.waitFor(robot.text(R.string.desktop_prompt))
        }
    }

    @Test fun sendingFromNewChatStartsAnIndependentConversation() {
        fixture.install()
        val composer = robot.text(R.string.composer_label)
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.edit(composer, "First independent question")
            robot.click(robot.text(R.string.composer_send))
            robot.waitFor("Fixture response")
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.new_chat))
            robot.awaitDrawerClosed(scenario)
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.edit(composer, "Second independent question")
            robot.click(robot.text(R.string.composer_send))
            robot.waitFor("Fixture response")
            val saved = fixture.store.load().conversations
            assertEquals(2, saved.map { it.id }.distinct().size)
            assertEquals(listOf("Second independent question"),
                fixture.provider.lastRequest?.turns?.filter { it.role == "user" }?.map { it.text })
            robot.openDrawer(scenario)
            robot.click("First independent question")
            robot.waitFor("First independent question")
            robot.waitGone("Second independent question")
        }
    }

    @Test fun failedFirstSaveKeepsTheDesktopDraftOnDisk() {
        fixture.install()
        val draft = Draft("Keep this unsent question")
        fixture.store.saveNewDraft(draft)
        val database = fixture.store.writableDatabase
        database.execSQL("CREATE TRIGGER reject_first_send BEFORE INSERT ON conversations BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        try {
            fixture.launch().use {
                robot.waitFor(robot.text(R.string.desktop_prompt))
                robot.click(robot.text(R.string.composer_send))
                robot.waitFor(robot.text(R.string.save_failed))
                assertEquals(draft, fixture.store.load().newDraft)
                assertEquals(emptyList<String>(), fixture.store.load().conversations.map { it.id })
                assertEquals(0, fixture.provider.calls)
            }
        } finally {
            database.execSQL("DROP TRIGGER reject_first_send")
        }
    }

    @Test fun ordinaryAutomationRequestOpensReviewWithoutEnablingARule() {
        fixture.provider.automationDraft = """
            {"name":"Новости","trigger":{"type":"schedule","repeat":"daily","time":"07:00","zone":"device","exact":true},
            "conditions":{},"actions":[{"id":"task","capability":"agent.run","args":{"instruction":"Подготовь новости за прошлый день."}}]}
        """.trimIndent()
        fixture.install()
        fixture.launch().use {
            val request = "присылай мне каждое утро в семь утра подборку самых важных новостей за прошлый день"
            robot.edit(robot.text(R.string.composer_label), request)
            robot.click(robot.text(R.string.composer_send))
            robot.assertScreenshotShows(robot.text(R.string.automation_agent_proposal))
            assertEquals(1, fixture.provider.calls)
            robot.reveal(robot.text(R.string.cancel), null)
            robot.click(robot.text(R.string.cancel))
            robot.waitFor(robot.text(R.string.chat_automation_setup))
            com.alpha.assistant.automations.AutomationStore(fixture.context, checkNotNull(fixture.model).tools.automationDatabase).use {
                assertEquals(emptyList<com.alpha.assistant.automations.AutomationRule>(), it.rules())
            }
        }
    }

    @Test fun desktopTileShowsTheSavedValueAndOpensTheApp() {
        val spec = com.alpha.assistant.tools.ToolSpec.parse("""{"formatVersion":3,"title":"Test calculator",
            "description":"","capabilities":[],"stateSchema":{"count":"number"},"initialState":{"count":7},
            "ui":{"type":"text","text":"Result","bind":"count"},"source":"return {state:input.state};"}""")
        val tool = com.alpha.assistant.tools.ToolRecord("catalog-calculator", 1, 1, spec.json,
            spec.initialState, spec.ui.toString(), null, true, onDesktop = true)
        com.alpha.assistant.tools.ToolStore(fixture.context, "${fixture.store.databaseName}-tools.db").use {
            it.saveTool(tool, true)
        }
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            val shortcut = robot.text(R.string.desktop_open_tile, spec.title)
            robot.waitFor(shortcut)
            robot.reveal("Result: 7")
            robot.click(shortcut)
            robot.waitFor("Result: 7")
            robot.click(robot.text(R.string.tool_library_back))
            robot.waitFor(shortcut)
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.mini_apps))
            robot.awaitDrawerClosed(scenario)
            robot.waitFor(robot.text(R.string.tool_search))
            assertNull(robot.find(robot.text(R.string.tool_request)))
            robot.edit(robot.text(R.string.tool_search), "unmatched")
            robot.waitFor(robot.text(R.string.tool_no_matches))
            robot.edit(robot.text(R.string.tool_search), "calculator")
            robot.click(spec.title)
            robot.waitFor("Result: 7")
            assertNotNull(fixture.model?.tools?.selectedId)
            robot.diagnostics.capture("catalog-direct-open")
        }
    }

    @Test fun newChatAndSettingsKeepTheirSeparatePurpose() {
        fixture.install(saveKey = false)
        fixture.store.saveSetting("userName", "Евгений")
        val draft = Draft("Unsent desktop draft", listOf(
            Attachment("content://fixture/desktop", "desktop.txt", "text/plain")))
        fixture.store.saveNewDraft(draft)
        fixture.seedChat("previous", "Previous conversation", "Previous conversation text", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Previous conversation text")
            robot.openDrawer(scenario)
            assertNull(robot.find(robot.text(R.string.access)))
            robot.click(robot.text(R.string.new_chat))
            robot.awaitDrawerClosed(scenario)
            robot.waitUntil { greetings("Евгений").any { robot.find(it) != null } }
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.reveal(robot.text(R.string.desktop_grid_title))
            robot.waitGone("Previous conversation text")
            assertEquals(draft.text, robot.field(robot.text(R.string.composer_label)).text.toString())
            robot.waitFor(robot.text(R.string.composer_remove_file, "desktop.txt"))
            robot.assertTarget(robot.text(R.string.composer_label))
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.reveal(robot.text(R.string.access))
            robot.click(robot.text(R.string.access))
            robot.reveal(robot.text(R.string.access_calendar))
            robot.diagnostics.capture("permissions-in-settings")
        }
    }

    @Test fun largeTextKeepsShellUsable() {
        val scale = robot.shell("settings get system font_scale")
        try {
            robot.shell("settings put system font_scale 2.0")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == 2f }
            fixture.install()
            val name = "Александра-Валентина Константиновна"
            fixture.store.saveSetting("userName", name)
            val title = "Очень длинное название разговора, которое не помещается в одну строку меню"
            fixture.seedChat("long", title, "Long chat message")
            fixture.launch().use { scenario ->
                scenario.onActivity { assertEquals(2f, it.resources.configuration.fontScale) }
                for (id in listOf(R.string.menu, R.string.composer_label, R.string.composer_send))
                    robot.assertTarget(robot.text(id))
                assertNull(robot.find(robot.text(R.string.new_chat)))
                robot.waitUntil { greetings(name).any { robot.find(it) != null } }
                robot.openDrawer(scenario)
                robot.assertTarget(robot.text(R.string.settings))
                robot.click(title)
                robot.awaitDrawerClosed(scenario)
                robot.waitFor("Long chat message")
                try {
                    robot.rotate(scenario, landscape = true)
                    robot.openDrawer(scenario)
                    robot.assertTarget(robot.text(R.string.settings))
                } finally { robot.rotate(scenario, landscape = false) }
            }
        } finally {
            // "settings get" prints "null" when the value is unset, and "settings put … null" does not restore that state.
            robot.shell(if (scale.toFloatOrNull() == null) "settings delete system font_scale" else "settings put system font_scale $scale")
            robot.waitUntil { Resources.getSystem().configuration.fontScale == (scale.toFloatOrNull() ?: 1f) }
            assertEquals(scale, robot.shell("settings get system font_scale"))
        }
    }

    @Test fun panesStayCenteredInLandscape() {
        fixture.install()
        fixture.seedChat("wide", "Wide chat", "Wide chat message")
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            try {
                robot.rotate(scenario, landscape = true)
                robot.waitFor(robot.text(R.string.desktop_prompt))
                assertCentered(scenario, "desktop")
                assertCentered(scenario, "composer")
                robot.openDrawer(scenario)
                // In landscape, the recent chats are below the viewport of the drawer list.
                robot.reveal("Wide chat")
                robot.click("Wide chat")
                robot.awaitDrawerClosed(scenario)
                robot.waitFor("Wide chat message")
                assertCentered(scenario, "chat-content")
            } finally { robot.rotate(scenario, landscape = false) }
        }
    }

    // The node is min(620 dp, root width) wide, and its left and right margins in the root are equal.
    private fun assertCentered(scenario: ActivityScenario<*>, tag: String) {
        val (bounds, rootWidth) = robot.composedBounds(scenario, tag)
        val width = minOf(620 * robot.instrumentation.targetContext.resources.displayMetrics.density, rootWidth.toFloat())
        val right = rootWidth - bounds.right
        Log.i("DesktopWorkflowTest", "tag=$tag bounds=$bounds rootWidth=$rootWidth expectedWidth=$width left=${bounds.left} right=$right")
        assertEquals("Width of $tag", width, bounds.width().toFloat(), 1f)
        assertEquals("Margins of $tag", bounds.left.toFloat(), right.toFloat(), 1f)
    }
}

package com.alpha.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import com.alpha.assistant.surfaces.AssistantChat
import com.alpha.assistant.surfaces.AssistantActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShellNavigationWorkflowTest {
    private val fixture = ShellFixture("shell-navigation")
    private val robot = ShellRobot("shell-navigation")
    @Before fun install() = fixture.install()
    @After fun remove() = fixture.remove()

    private fun openSettings(scenario: ActivityScenario<*>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitUntil { robot.composed(scenario, "settings-pane") }
    }

    // The shell applies an entry in its first frames, so the check waits 10 frames after the Desktop shows.
    private fun assertNoEntryApplied(scenario: ActivityScenario<*>) {
        robot.waitFor(robot.text(R.string.desktop_prompt))
        robot.awaitFrames(10)
        assertFalse(robot.composed(scenario, "settings-pane"))
    }

    @Test fun backLeavesSettingsThenChatThenApp() {
        fixture.seedChat("nav", "Nav fixture", "Nav fixture message", selected = true)
        fixture.launch().use { scenario ->
            robot.waitFor("Nav fixture message")
            openSettings(scenario)
            scenario.recreate()
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            robot.back()
            robot.waitFor("Nav fixture message")
            robot.waitUntil { !robot.composed(scenario, "settings-pane") }
            robot.back()
            robot.waitGone("Nav fixture message")
            robot.waitUntil { fixture.store.load().selectedId == null }
            robot.back()
            robot.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun entryReplacesDestination() {
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null) }
            robot.waitUntil { !robot.composed(scenario, "settings-pane") }
            robot.back()
            robot.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    // Failure case: the model outlives the activity, so a new activity applies the old «Настройки» entry again.
    @Test fun newActivityAppliesNoOldEntry() {
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null, settings = true) }
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            robot.waitFor(robot.text(R.string.settings))
            robot.back()
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.waitUntil { !robot.composed(scenario, "settings-pane") }
        }
        // MainActivity gets the process-wide model, so the second launch gets the model of the first launch.
        val shared = checkNotNull(fixture.model)
        CloudTestActivity.factory = { shared }
        fixture.launch().use(::assertNoEntryApplied)
    }

    // Failure case: a launch from Recents repeats the extras of the old «Настройки» entry.
    @Test fun recentsLaunchAppliesNoEntry() {
        val app = robot.instrumentation.targetContext.applicationContext as Application
        // The fixture factory puts the fixture model into the process-wide store first, so MainActivity never gets the production store or key store.
        val shared = ViewModelProvider(AssistantChat, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(CloudTestActivity.factory)(app) as T
        })[ShellViewModel::class.java]
        try {
            assertSame("MainActivity gets the fixture model", fixture.model, shared)
            val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).putExtra("settings", true)
                .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
            ActivityScenario.launch<MainActivity>(intent).use(::assertNoEntryApplied)
        } finally { if (shared === fixture.model) AssistantChat.viewModelStore.clear() }
    }

    @Test fun selectingSettingsAfterDirectEntryReturnsToTheMenu() {
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            scenario.onActivity { checkNotNull(fixture.model).openSurface(null, settings = true) }
            robot.waitFor(robot.text(R.string.key_ready))
            if (!robot.composed(scenario, "settings-menu")) robot.click(robot.text(R.string.settings))
            robot.click(robot.text(R.string.access))
            robot.waitFor(robot.text(R.string.access_intro))
            robot.back()
            robot.waitGone(robot.text(R.string.access_intro))
            assertTrue(robot.composed(scenario, "settings-menu"))
            robot.back()
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.waitUntil { !robot.composed(scenario, "settings-pane") }
        }
    }

    @Test fun settingsAllowScreenshotsThroughBackNavigation() {
        fixture.launch().use { scenario ->
            assertFalse(robot.secure(scenario))
            openSettings(scenario)
            assertFalse(robot.secure(scenario))
            assertTrue(robot.automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            robot.waitUntil {
                assertFalse("Back navigation enabled screenshot protection", robot.secure(scenario))
                !robot.composed(scenario, "settings-pane")
            }
            robot.waitFor(robot.text(R.string.desktop_prompt))
        }
    }

    @Test fun compactAssistantShowsAutomationAndMemoryReview() {
        val app = fixture.context.applicationContext as Application
        val shared = ViewModelProvider(AssistantChat, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(CloudTestActivity.factory)(app) as T
        })[ShellViewModel::class.java]
        try {
            assertSame(fixture.model, shared)
            ActivityScenario.launch<AssistantActivity>(Intent(app, AssistantActivity::class.java)).use {
                val composer = robot.text(R.string.composer_label)
                val send = robot.text(R.string.composer_send)
                robot.edit(composer, "Создай сценарий")
                robot.click(send)
                robot.waitFor(robot.text(R.string.automation_editor_create))
                robot.reveal(robot.text(R.string.cancel), null)
                robot.click(robot.text(R.string.cancel))
                robot.waitGone(robot.text(R.string.automation_editor_create))
                robot.edit(composer, "Запомни: я предпочитаю краткие ответы")
                robot.click(send)
                robot.waitFor(robot.text(R.string.memory_text))
                robot.click(robot.text(R.string.memory_cancel))
                robot.click(robot.text(R.string.memory_discard))
                robot.waitGone(robot.text(R.string.memory_text))
                assertTrue(fixture.provider.calls == 0)
            }
        } finally { if (shared === fixture.model) AssistantChat.viewModelStore.clear() }
    }
}

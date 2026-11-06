package com.alpha.assistant.ui

import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolStore
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConsentWorkflowTest {
    private val fixture = ShellFixture("settings-consent")
    private val robot = ShellRobot("settings-consent")
    private val preferences get() = fixture.context.getSharedPreferences(fixture.name, Context.MODE_PRIVATE)
    @After fun remove() = fixture.remove()

    private fun send(text: String) {
        robot.edit(robot.text(R.string.composer_label), text)
        robot.click(robot.text(R.string.composer_send))
    }
    private fun openSettings(scenario: ActivityScenario<*>, section: Int = R.string.settings_deepseek) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitUntil { robot.secure(scenario) }
        robot.click(robot.text(section))
    }
    private fun saveKey() {
        robot.reveal(robot.text(R.string.key_label))
        robot.enter(robot.text(R.string.key_label), "alpha-test-only")
        robot.reveal(robot.text(R.string.consent_and_save))
        robot.click(robot.text(R.string.consent_and_save))
        robot.waitFor(robot.text(R.string.key_ready))
    }
    private fun dropConsent() = check(preferences.edit().remove("consent").commit())
    /** Starts a mini-app generation and returns the tool controller. */
    private fun startGeneration(): ToolController {
        val tools = fixture.model!!.tools
        robot.waitUntil {
            var started = false
            // run() ignores a call while the controller is busy, so check and start in one main-thread step.
            robot.instrumentation.runOnMainSync { if (!tools.busy) { tools.generate("Счётчик", null, null, emptyList()); started = true } }
            started
        }
        return tools
    }

    @Test fun keyEntryRecordsConsentAndEnablesCloud() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            send("Before key")
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.secure(scenario) }
            robot.waitFor(robot.text(R.string.key_missing))
            robot.waitFor(robot.text(R.string.consent_text))
            saveKey()
            // A polite live region lets TalkBack announce the new key status. The status line merges its icon and text, so its node is the parent of the text node.
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, robot.waitFor(robot.text(R.string.key_ready)).parent?.liveRegion)
            assertTrue(fixture.keys.consented())
            assertEquals("alpha-test-only", fixture.keys.read().getOrThrow())
            assertEquals(0, fixture.provider.calls)
            robot.back()
            send("After key")
            robot.waitFor("Fixture response")
            assertEquals(1, fixture.provider.calls)
        }
    }

    @Test fun storedKeyWithoutConsentAsksOnce() {
        fixture.install()
        dropConsent()
        val ciphertext = preferences.getString("ciphertext", null)
        fixture.launch().use { scenario ->
            send("Stored key")
            robot.waitFor(robot.text(R.string.cloud_consent))
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.mini_apps))
            robot.awaitDrawerClosed(scenario)
            val tools = startGeneration()
            robot.waitUntil { tools.errorCode == "consent" }
            assertEquals(0, fixture.provider.calls)
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.secure(scenario) }
            robot.waitFor(robot.text(R.string.key_needs_consent))
            robot.waitFor(robot.text(R.string.consent_text))
            robot.reveal(robot.text(R.string.consent_accept))
            robot.click(robot.text(R.string.consent_accept))
            robot.waitFor(robot.text(R.string.key_ready))
            assertEquals(ciphertext, preferences.getString("ciphertext", null))
            assertTrue(fixture.keys.consented())
            robot.back()
            send("After consent")
            robot.waitFor("Fixture response")
            assertEquals(1, fixture.provider.calls)
            send("Second request")
            robot.waitUntil { fixture.provider.calls == 2 }
        }
    }

    @Test fun settingsEntryShowsConsentForStoredKey() {
        fixture.install()
        dropConsent()
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.menu))
            robot.instrumentation.runOnMainSync { fixture.model!!.openSurface(null, settings = true) }
            robot.waitUntil { robot.secure(scenario) }
            robot.waitFor(robot.text(R.string.key_needs_consent))
            assertEquals(0, fixture.provider.calls)
        }
    }

    @Test fun rejectedKeyOpensSettings() {
        fixture.install()
        fixture.provider.failure = CloudFailure("auth", "Fixture rejected key")
        fixture.launch().use { scenario ->
            send("Rejected key")
            robot.waitFor("Fixture rejected key")
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.secure(scenario) }
            robot.waitFor(robot.text(R.string.key_ready))
        }
    }

    @Test fun deleteKeyStopsCloudUntilNewConsent() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.waitFor(robot.text(R.string.key_ready))
            robot.click(robot.text(R.string.settings_deepseek_disclosure))
            robot.waitFor(robot.text(R.string.consent_text))
            robot.reveal(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.key_delete))
            robot.waitFor(robot.text(R.string.key_delete_text))
            robot.click(robot.text(R.string.delete))
            robot.waitFor(robot.text(R.string.key_missing))
            assertNull(fixture.keys.read().getOrThrow())
            assertFalse(fixture.keys.consented())
            robot.back()
            robot.back()
            send("Without key")
            robot.waitFor(robot.text(R.string.cloud_key_missing))
            assertEquals(0, fixture.provider.calls)
            robot.click(robot.text(R.string.open_settings))
            saveKey()
            robot.back()
            send("With key again")
            robot.waitFor("Fixture response")
        }
    }

    @Test fun deleteKeyStopsRunningRequest() {
        fixture.provider.gate = CompletableDeferred()
        fixture.install()
        fixture.launch().use { scenario ->
            send("Long request")
            robot.waitUntil { fixture.provider.calls == 1 }
            openSettings(scenario)
            robot.reveal(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.delete))
            robot.waitFor(robot.text(R.string.key_missing))
            fixture.provider.gate!!.complete(Unit)
            robot.waitUntil { fixture.model!!.snapshot.selected?.messages?.lastOrNull()?.status == "cancelled" }
            robot.instrumentation.waitForIdleSync()
            assertNull(fixture.model!!.snapshot.selected?.messages?.find { it.text == "Fixture response" })
            assertEquals(1, fixture.provider.calls)
        }
    }

    // Failure case: a mini-app generation keeps the key in memory, so it runs to its end after «Удалить ключ».
    @Test fun deleteKeyStopsRunningGeneration() {
        fixture.provider.gate = CompletableDeferred()
        fixture.install()
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            val tools = startGeneration()
            robot.waitUntil { fixture.provider.calls == 1 }
            openSettings(scenario)
            robot.reveal(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.delete))
            robot.waitFor(robot.text(R.string.key_missing))
            fixture.provider.gate!!.complete(Unit)
            robot.waitUntil { !tools.busy }
            robot.instrumentation.waitForIdleSync()
            assertEquals(1, fixture.provider.calls)
            // The draft row exists before the provider call, so the check is that no generated candidate reached the store.
            ToolStore(fixture.context, "${fixture.name}.db-tools.db").use { store ->
                assertTrue(store.tools().isEmpty())
                assertTrue(store.drafts().none { it.candidate != null })
            }
        }
    }

    // Failure case: the chat card offers «Открыть настройки» for an error that the key cannot fix.
    @Test fun networkErrorOffersNoSettings() {
        fixture.install()
        fixture.store.saveConversation(Conversation("network", "Network chat", 1L, 2L, messages = listOf(
            Message("network-u", "user", "Network question", 1L, "sent"),
            Message("network-a", "assistant", "", 2L, "error", error = "Fixture network failure", errorCode = "network"))))
        fixture.store.saveSetting("selected", "network")
        fixture.launch().use { scenario ->
            robot.waitFor("Fixture network failure")
            assertFalse(robot.composedText(scenario, robot.text(R.string.open_settings)))
        }
    }

    // Failure case: a recreation keeps a typed key that the user did not save.
    @Test fun typedKeyDoesNotSurviveRecreation() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.reveal(robot.text(R.string.key_label))
            // A password field shows dots, so the check compares the field text with its text before the entry.
            fun keyText() = robot.field(robot.text(R.string.key_label)).text?.toString()
            val empty = keyText()
            robot.enter(robot.text(R.string.key_label), "alpha-test-only")
            robot.waitUntil { keyText() != empty }
            scenario.recreate()
            robot.waitUntil { robot.secure(scenario) }
            robot.reveal(robot.text(R.string.key_label))
            robot.waitUntil { keyText() == empty }
        }
    }

    // Failure case: the 40-character cut of the name keeps half of an emoji.
    @Test fun longNameKeepsWholeCharacters() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario, R.string.settings_name_label)
            robot.reveal(robot.text(R.string.settings_name_label))
            val letters = "а".repeat(39)
            robot.enter(robot.text(R.string.settings_name_label), letters + "\uD83D\uDE00")
            robot.waitUntil { fixture.store.load().userName == letters }
        }
    }

    @Test fun assistantLinkOpensSystemSettings() {
        fixture.install()
        val monitor = robot.instrumentation.addMonitor(IntentFilter(Settings.ACTION_VOICE_INPUT_SETTINGS), null, true)
        try {
            fixture.launch().use { scenario ->
                openSettings(scenario, R.string.access)
                robot.reveal(robot.text(R.string.assistant_pick_hint))
                robot.reveal(robot.text(R.string.assistant_open))
                robot.click(robot.text(R.string.assistant_open))
                robot.waitUntil { monitor.hits == 1 }
            }
        } finally { robot.instrumentation.removeMonitor(monitor) }
    }

    @Test fun nameSurvivesRecreation() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario, R.string.settings_name_label)
            robot.reveal(robot.text(R.string.settings_name_label))
            robot.edit(robot.text(R.string.settings_name_label), "Евгений")
            robot.waitUntil { fixture.store.load().userName == "Евгений" }
            scenario.recreate()
            robot.waitUntil { robot.secure(scenario) }
            robot.reveal(robot.text(R.string.settings_name_label))
            robot.waitUntil { robot.field(robot.text(R.string.settings_name_label)).text?.toString() == "Евгений" }
            robot.back()
            robot.back()
            robot.waitUntil { !robot.secure(scenario) }
            robot.openDrawer(scenario)
            robot.waitFor("Евгений")
        }
    }

    @Test fun settingsShowVoiceStart() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario, R.string.settings_voice)
            robot.reveal(robot.text(R.string.voice_start))
            robot.assertTarget(robot.text(R.string.voice_start))
        }
    }
}

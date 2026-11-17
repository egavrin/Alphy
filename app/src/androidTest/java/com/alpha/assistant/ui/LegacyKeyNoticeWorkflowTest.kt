package com.alpha.assistant.ui

import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.KeyCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The found-key cards of the two old key stores (spec 14.6). */
class LegacyKeyNoticeWorkflowTest {
    private val fixture = ShellFixture("legacy-keys")
    private val robot = ShellRobot("legacy-keys")
    @After fun remove() = fixture.remove()

    private fun openSettings(scenario: ActivityScenario<*>, section: Int? = R.string.settings_ai) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitUntil { robot.composed(scenario, "settings-pane") }
        if (section != null) robot.click(robot.text(section))
    }
    /** Taps the use button of a card. Then the open consent and the save button are in view, the provider is selected, and the old key is masked. */
    private fun useOldKey(@androidx.annotation.StringRes use: Int, provider: String) {
        robot.click(robot.text(use))
        // The consent opens above the save button and pushes it out of view, so the next check passes only after the app scrolls.
        robot.waitFor(robot.text(R.string.consent_text, provider))
        robot.awaitTarget(robot.text(R.string.consent_and_save))
        assertTrue(robot.find(robot.text(R.string.consent_text, provider))?.isVisibleToUser == true)
        // The provider field and the key field can be above the scroll position.
        robot.reveal(robot.text(R.string.key_label), forward = false)
        robot.selected(provider)
        // The message names no key, so a failure cannot print one.
        val shown = keyText().orEmpty()
        assertTrue("The key field shows the old key in plain text", shown.isNotEmpty() && shown.none(Char::isLetterOrDigit))
    }
    private fun pressSave() {
        robot.reveal(robot.text(R.string.consent_and_save))
        robot.click(robot.text(R.string.consent_and_save))
    }
    /** Saves the old key from the field. Then [account] is the account, and the old store is empty. */
    private fun saveOldKey(account: AiAccount, stored: () -> Boolean) {
        pressSave()
        robot.waitFor(robot.text(R.string.key_ready))
        assertEquals(account, fixture.keys.account().getOrThrow())
        robot.waitUntil { !stored() }
    }
    // A password field shows dots, so a check compares the field text with its text before the entry.
    private fun keyText() = robot.field(robot.text(R.string.key_label)).text?.toString()

    @Test fun deepSeekNoticeDeletesOnlyOnTap() {
        fixture.install(saveKey = false)
        fixture.seedLegacyDeepSeek()
        fixture.launch().use { scenario ->
            openSettings(scenario, R.string.settings_ai)
            robot.waitFor(robot.text(R.string.legacy_deepseek))
            assertTrue(fixture.legacy.has(AiProvider.DEEPSEEK))
            robot.click(robot.text(R.string.legacy_delete))
            robot.waitUntil { !fixture.legacy.has(AiProvider.DEEPSEEK) }
            // The card goes after the store, so the check waits for it.
            robot.waitGone(robot.text(R.string.legacy_deepseek))
        }
    }

    @Test fun deepSeekKeyBecomesAccountAfterConsent() {
        fixture.install(saveKey = false)
        fixture.seedLegacyDeepSeek(DEEPSEEK_KEY)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            useOldKey(R.string.legacy_deepseek_use, "DeepSeek")
            // The old key stays until a check and a save of that key.
            assertTrue(fixture.legacy.has(AiProvider.DEEPSEEK))
            saveOldKey(AiAccount(AiProvider.DEEPSEEK, DEEPSEEK_KEY), { fixture.legacy.has(AiProvider.DEEPSEEK) })
            robot.waitGone(robot.text(R.string.legacy_deepseek))
        }
    }

    @Test fun openRouterImageKeyBecomesAccountAfterConsent() {
        fixture.install(saveKey = false)
        fixture.seedLegacyOpenRouter(OPENROUTER_KEY)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            useOldKey(R.string.legacy_openrouter_use, "OpenRouter")
            assertTrue(fixture.legacy.has(AiProvider.OPENROUTER))
            saveOldKey(AiAccount(AiProvider.OPENROUTER, OPENROUTER_KEY), { fixture.legacy.has(AiProvider.OPENROUTER) })
            robot.waitGone(robot.text(R.string.legacy_openrouter))
        }
    }

    // Failure case: a check without network deletes the old key, so the user loses the only copy (decision 3).
    @Test fun failedCheckKeepsOldKey() {
        fixture.keyCheck = { KeyCheck.UNREACHABLE }
        fixture.install(saveKey = false)
        fixture.seedLegacyOpenRouter(OPENROUTER_KEY)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            useOldKey(R.string.legacy_openrouter_use, "OpenRouter")
            pressSave()
            robot.reveal(robot.text(R.string.key_unreachable), forward = null)
            // The status line returns to «Ключ не задан» after the whole save action.
            robot.reveal(robot.text(R.string.key_missing), forward = false)
            assertTrue(fixture.legacy.has(AiProvider.OPENROUTER))
            assertNull(fixture.keys.account().getOrThrow())
            robot.waitFor(robot.text(R.string.legacy_openrouter))
            // The key stays in the field, so a second try with network saves it and then clears the old store.
            fixture.keyCheck = { KeyCheck.ACCEPTED }
            saveOldKey(AiAccount(AiProvider.OPENROUTER, OPENROUTER_KEY), { fixture.legacy.has(AiProvider.OPENROUTER) })
        }
    }

    // Failure case: a key that the user changed after «Использовать» replaces the old key, and the old store goes (decision 3).
    @Test fun editedKeyKeepsOldKey() {
        fixture.install(saveKey = false)
        fixture.seedLegacyDeepSeek(DEEPSEEK_KEY)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            useOldKey(R.string.legacy_deepseek_use, "DeepSeek")
            robot.reveal(robot.text(R.string.key_label), forward = false)
            robot.enter(robot.text(R.string.key_label), EDITED_KEY)
            pressSave()
            robot.waitFor(robot.text(R.string.key_ready))
            assertEquals(AiAccount(AiProvider.DEEPSEEK, EDITED_KEY), fixture.keys.account().getOrThrow())
            assertTrue(fixture.legacy.has(AiProvider.DEEPSEEK))
            robot.reveal(robot.text(R.string.legacy_delete), forward = false)
            assertNull(robot.find(robot.text(R.string.legacy_deepseek_use)))
        }
    }

    // Failure case: an old store that does not decrypt loses its card, or «Использовать» puts nothing in the field without a message (decision 5).
    @Test fun unreadableOldKeyKeepsCard() {
        fixture.install(saveKey = false)
        fixture.seedUnreadableLegacyDeepSeek()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.waitFor(robot.text(R.string.key_label))
            val empty = keyText()
            robot.click(robot.text(R.string.legacy_deepseek_use))
            robot.waitFor(robot.text(R.string.key_unreadable))
            assertEquals(empty, keyText())
            assertNull(robot.find(robot.text(R.string.key_provider)))
            assertTrue(fixture.legacy.has(AiProvider.DEEPSEEK))
            robot.click(robot.text(R.string.legacy_delete))
            robot.waitUntil { !fixture.legacy.has(AiProvider.DEEPSEEK) }
            robot.waitGone(robot.text(R.string.legacy_deepseek))
            // This store has no Keystore alias, as after a restore without the Keystore. The delete still succeeds for the user.
            assertNull(robot.find(robot.text(R.string.key_delete_failed)))
        }
    }

    @Test fun accountHidesOnlyUseButton() {
        fixture.install()
        fixture.seedLegacyDeepSeek()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.waitFor(robot.text(R.string.key_ready))
            robot.waitFor(robot.text(R.string.legacy_deepseek))
            robot.assertTarget(robot.text(R.string.legacy_delete))
            assertNull(robot.find(robot.text(R.string.legacy_deepseek_use)))
        }
    }

    @Test fun bothOldKeysShowDeepSeekFirst() {
        fixture.install(saveKey = false)
        fixture.seedLegacyDeepSeek()
        fixture.seedLegacyOpenRouter()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            // Each title is a heading, so TalkBack names the key that a «Удалить» belongs to (decision 8).
            assertTrue(robot.waitFor(robot.text(R.string.legacy_deepseek)).isHeading)
            assertTrue(robot.waitFor(robot.text(R.string.legacy_openrouter)).isHeading)
            assertTrue(robot.bounds(robot.text(R.string.legacy_deepseek)).bottom <= robot.bounds(robot.text(R.string.legacy_openrouter)).top)
            // The cards come before the status line and the key field (decision 8).
            assertTrue(robot.bounds(robot.text(R.string.legacy_openrouter)).bottom <= robot.bounds(robot.text(R.string.key_missing)).top)
            robot.assertTarget(robot.text(R.string.legacy_deepseek_use))
            robot.assertTarget(robot.text(R.string.legacy_openrouter_use))
        }
    }

    private companion object {
        // Fake keys. The edited key keeps the DeepSeek format, so the selected provider stays.
        val DEEPSEEK_KEY = fakeDeepSeekKey()
        val EDITED_KEY = fakeDeepSeekKey("fedcba9876543210")
        const val OPENROUTER_KEY = "sk-or-v1-legacy-test-only"
    }
}

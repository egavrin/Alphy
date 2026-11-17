package com.alpha.assistant.ui

import android.content.IntentFilter
import android.provider.Settings
import android.view.View
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.KeyCheck
import com.alpha.assistant.data.Conversation
import com.alpha.assistant.data.Message
import com.alpha.assistant.tools.ToolController
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.agent.GenerationSettings
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SettingsConsentWorkflowTest {
    private val fixture = ShellFixture("settings-consent")
    private val robot = ShellRobot("settings-consent")
    @After fun remove() = fixture.remove()

    private fun send(text: String) {
        robot.edit(robot.text(R.string.composer_label), text)
        robot.click(robot.text(R.string.composer_send))
    }
    private fun openSettings(scenario: ActivityScenario<*>, section: Int? = R.string.settings_ai) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitUntil { robot.composed(scenario, "settings-pane") }
        if (section != null) robot.click(robot.text(section))
    }
    private fun typeKey(value: String) {
        // A second key starts below the field, so the search goes both ways.
        robot.reveal(robot.text(R.string.key_label), forward = null)
        robot.enter(robot.text(R.string.key_label), value)
        // The provider field comes with the typed key. Before it shows, the panel can be too short to scroll.
        robot.waitFor(robot.text(R.string.key_provider))
    }
    private fun pressSave() {
        robot.reveal(robot.text(R.string.consent_and_save))
        robot.click(robot.text(R.string.consent_and_save))
    }
    // A password field shows dots, so a check compares the field text with its text before the entry.
    private fun keyText() = robot.field(robot.text(R.string.key_label)).text?.toString()
    // The text node is not the button, so the state comes from the clickable ancestor.
    private fun saveEnabled(): Boolean =
        generateSequence(robot.reveal(robot.text(R.string.consent_and_save), forward = null)) { it.parent }.firstOrNull { it.isClickable }?.isEnabled == true
    private fun chooseProvider(name: String) {
        robot.reveal(robot.text(R.string.key_provider), forward = null)
        robot.assertTarget(robot.text(R.string.key_provider))
        robot.click(robot.text(R.string.key_provider))
        robot.click(name)
        robot.selected(name)
    }
    private fun saveKey(value: String = OPENAI_KEY) {
        typeKey(value)
        pressSave()
        robot.waitFor(robot.text(R.string.key_ready))
    }
    /** Gives [result] for each key check and counts the checks. */
    private fun answerChecks(result: KeyCheck): AtomicInteger = AtomicInteger().also { count ->
        fixture.keyCheck = { count.incrementAndGet(); result }
    }
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

    @Test fun theScreenshotReviewIsOffUntilTheUserTurnsItOn() {
        val settings = GenerationSettings(fixture.context)
        val before = settings.screenshotReview
        try {
            settings.screenshotReview = false
            fixture.install()
            fixture.launch().use { scenario ->
                openSettings(scenario)
                val label = robot.text(R.string.settings_screenshot_review)
                robot.reveal(label)
                robot.waitFor(robot.text(R.string.settings_screenshot_review_hint))
                // The switch row shows the stored value. A click acts on the shown value, so each check waits for both.
                fun shown() = generateSequence(robot.find(label)) { it.parent }.firstOrNull { it.isCheckable }?.isChecked
                robot.waitUntil { shown() == false }
                robot.click(label)
                robot.waitUntil { settings.screenshotReview && shown() == true }
                robot.click(label)
                robot.waitUntil { !settings.screenshotReview && shown() == false }
            }
        } finally { settings.screenshotReview = before }
    }

    @Test fun keyEntryRecordsConsentAndEnablesCloud() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            send("Before key")
            // The key error shows the retry action without a long press.
            robot.waitFor(robot.text(R.string.chat_message_retry))
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            robot.waitFor(robot.text(R.string.key_missing))
            typeKey(OPENAI_KEY)
            robot.reveal(robot.text(R.string.consent_text, "OpenAI"))
            pressSave()
            // A polite live region lets TalkBack announce the new key status. The status line merges its icon and text, so its node is the parent of the text node.
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, robot.waitFor(robot.text(R.string.key_ready)).parent?.liveRegion)
            assertTrue(fixture.keys.consented())
            assertEquals(AiAccount(AiProvider.OPENAI, OPENAI_KEY), fixture.keys.account().getOrThrow())
            assertEquals(0, fixture.provider.calls)
            robot.back()
            robot.click(robot.text(R.string.chat_message_retry))
            robot.waitFor("Fixture response")
            assertEquals(1, fixture.provider.calls)
            assertEquals("Before key", fixture.provider.lastRequest?.turns?.lastOrNull { it.role == "user" }?.text)
        }
    }

    @Test fun rejectedKeyOpensSettings() {
        fixture.install()
        fixture.provider.failure = CloudFailure("auth", "Fixture rejected key")
        fixture.launch().use { scenario ->
            send("Rejected key")
            robot.waitFor("Fixture rejected key")
            robot.click(robot.text(R.string.open_settings))
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            robot.waitFor(robot.text(R.string.key_ready))
        }
    }

    @Test fun offlineChatErrorNamesTheAccountProvider() {
        fixture.install()
        fixture.provider.failure = java.io.IOException("Fixture network down")
        fixture.launch().use {
            send("Offline question")
            robot.waitFor(robot.text(R.string.chat_offline, "OpenAI"))
            assertTrue(robot.texts().none { "DeepSeek" in it })
        }
    }

    @Test fun deleteKeyStopsCloudUntilNewConsent() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.waitFor(robot.text(R.string.key_ready))
            robot.reveal(robot.text(R.string.ai_disclosure, "OpenAI"))
            robot.click(robot.text(R.string.ai_disclosure, "OpenAI"))
            // The text opens under the visible title. A scroll before it composes would pass it.
            robot.waitFor(robot.text(R.string.consent_text, "OpenAI"))
            robot.reveal(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.key_delete))
            robot.assertScreenshotShows(robot.text(R.string.key_delete_text))
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
        fixture.store.seedOpenChat("network")
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
            robot.reveal(robot.text(R.string.key_label), forward = null)
            val empty = keyText()
            robot.enter(robot.text(R.string.key_label), OPENAI_KEY)
            robot.waitUntil { keyText() != empty }
            scenario.recreate()
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            robot.reveal(robot.text(R.string.key_label), forward = null)
            robot.waitUntil { keyText() == empty }
        }
    }

    // Failure case: the 40-character cut of the name keeps half of an emoji.
    @Test fun longNameKeepsWholeCharacters() {
        fixture.install()
        fixture.launch().use { scenario ->
            openSettings(scenario, section = null)
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
                openSettings(scenario, section = null)
                robot.reveal(robot.text(R.string.assistant_title))
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
            openSettings(scenario, section = null)
            robot.reveal(robot.text(R.string.settings_name_label))
            robot.edit(robot.text(R.string.settings_name_label), "Евгений")
            robot.waitUntil { fixture.store.load().userName == "Евгений" }
            scenario.recreate()
            robot.waitUntil { robot.composed(scenario, "settings-pane") }
            robot.reveal(robot.text(R.string.settings_name_label))
            robot.waitUntil { robot.field(robot.text(R.string.settings_name_label)).text?.toString() == "Евгений" }
            robot.back()
            robot.waitUntil { !robot.composed(scenario, "settings-pane") }
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

    @Test fun detectsProviderAndShowsCapabilities() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            typeKey("sk-ant-api03-abc")
            robot.selected("Anthropic")
            robot.reveal(robot.text(R.string.ai_feature_images_unavailable, "Anthropic"))
            robot.reveal(robot.text(R.string.ai_feature_voice_phone))
            // The phone recognizes the speech for an Anthropic key, so no recording leaves the phone (spec 12A.4).
            robot.reveal(robot.text(R.string.capability_voice_input_phone))
            assertTrue(robot.composedText(scenario, robot.text(R.string.consent_text, "Anthropic")))
            assertFalse(robot.composedText(scenario, robot.text(R.string.consent_voice_input)))
            robot.reveal(robot.text(R.string.ai_feature_vision_detail))
            assertNull(robot.find(robot.text(R.string.image_enable)))
            assertNull(fixture.keys.account().getOrThrow())
        }
    }

    @Test fun unknownPrefixNeedsProviderChoice() {
        val checks = answerChecks(KeyCheck.ACCEPTED)
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            typeKey("abc123")
            robot.reveal(robot.text(R.string.key_provider_choose))
            assertFalse(saveEnabled())
            chooseProvider("DeepSeek")
            robot.waitUntil { saveEnabled() }
            pressSave()
            robot.waitFor(robot.text(R.string.key_ready))
            assertEquals(1, checks.get())
            assertEquals(AiAccount(AiProvider.DEEPSEEK, "abc123"), fixture.keys.account().getOrThrow())
        }
    }

    @Test fun rejectedKeyIsNotSaved() {
        answerChecks(KeyCheck.REJECTED)
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            typeKey(OPENAI_KEY)
            pressSave()
            robot.reveal(robot.text(R.string.key_rejected, "OpenAI"), forward = null)
            // A failed check keeps the message under the button in view, so the status line is above it.
            robot.reveal(robot.text(R.string.key_missing), forward = false)
            assertNull(fixture.keys.account().getOrThrow())
        }
    }

    // Failure case: the next key edit replaces the provider that the user chose, so the key goes to the detected one (spec 6.4).
    @Test fun manualChoiceSurvivesKeyEdit() {
        val providers = java.util.concurrent.CopyOnWriteArrayList<AiProvider>()
        fixture.keyCheck = { providers += it.provider; KeyCheck.ACCEPTED }
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            typeKey("sk-abc")
            robot.selected("OpenAI")
            chooseProvider("DeepSeek")
            val before = keyText()
            typeKey("sk-abcd")
            // The field shows one more dot after the edit, so the provider below it is the state after the edit.
            robot.waitUntil { keyText() != before }
            robot.selected("DeepSeek")
            pressSave()
            robot.waitFor(robot.text(R.string.key_ready))
            assertEquals(listOf(AiProvider.DEEPSEEK), providers.toList())
            assertEquals(AiProvider.DEEPSEEK, fixture.keys.account().getOrThrow()?.provider)
        }
    }

    // Failure case: a check without network loses the typed key or saves a key that nobody checked.
    @Test fun offlineCheckKeepsTypedKey() = assertFailedCheckKeepsKey { KeyCheck.UNREACHABLE }

    // Failure case: an exception in the check stops the app, or its text, which can hold the key, shows on screen.
    @Test fun failingCheckKeepsTypedKey() = assertFailedCheckKeepsKey { throw IllegalStateException(CHECK_FAILURE) }

    private fun assertFailedCheckKeepsKey(check: suspend (AiAccount) -> KeyCheck) {
        fixture.keyCheck = check
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.reveal(robot.text(R.string.key_label), forward = null)
            val empty = keyText()
            typeKey(OPENAI_KEY)
            robot.waitUntil { keyText() != empty }
            pressSave()
            robot.reveal(robot.text(R.string.key_unreachable), forward = null)
            robot.reveal(robot.text(R.string.key_label), forward = null)
            robot.waitUntil { keyText() != empty }
            assertNull(fixture.keys.account().getOrThrow())
            assertFalse(fixture.keys.consented())
            assertNull(robot.find(CHECK_FAILURE))
        }
    }

    @Test fun limitedKeyIsSavedWithNotice() {
        answerChecks(KeyCheck.LIMITED)
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            saveKey()
            robot.reveal(robot.text(R.string.key_limited), forward = null)
            assertEquals(AiAccount(AiProvider.OPENAI, OPENAI_KEY), fixture.keys.account().getOrThrow())
        }
    }

    @Test fun noCreditKeyIsSavedWithNotice() {
        answerChecks(KeyCheck.NO_CREDIT)
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            saveKey(DEEPSEEK_KEY)
            robot.reveal(robot.text(R.string.key_no_credit, "DeepSeek"), forward = null)
            assertEquals(AiAccount(AiProvider.DEEPSEEK, DEEPSEEK_KEY), fixture.keys.account().getOrThrow())
        }
    }

    // User decision 2026-11-17: DeepSeek has no cloud voice, so its rows and consent match OpenAI and Anthropic.
    @Test fun deepSeekKeyShowsPhoneVoiceOnly() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            typeKey(DEEPSEEK_KEY)
            robot.selected("DeepSeek")
            robot.reveal(robot.text(R.string.ai_feature_images_unavailable, "DeepSeek"))
            robot.reveal(robot.text(R.string.ai_feature_voice_phone))
            assertFalse(robot.composedText(scenario, robot.text(R.string.ai_feature_voice_any)))
            // No cloud voice, so the consent names no reply text for voice.
            assertFalse(robot.composedText(scenario, robot.text(R.string.consent_voice)))
            // DeepSeek recognizes the speech, so the consent names the recording (spec 14.7).
            robot.reveal(robot.text(R.string.consent_voice_input), forward = null)
            robot.reveal(robot.text(R.string.capability_voice_input_cloud, "DeepSeek"), forward = null)
        }
    }

    @Test fun adminKeyBlockedBeforeCheck() {
        val checks = answerChecks(KeyCheck.ACCEPTED)
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            typeKey("sk-ant-admin01-x")
            robot.selected("Anthropic")
            pressSave()
            robot.reveal(robot.text(R.string.key_admin), forward = null)
            typeKey("sk-ant-api03-ключ")
            pressSave()
            robot.reveal(robot.text(R.string.key_format), forward = null)
            assertEquals(0, checks.get())
            assertNull(fixture.keys.account().getOrThrow())
        }
    }

    // Failure case: a manual choice sends an Anthropic admin key to a different provider (spec 6.5).
    @Test fun adminKeyBlockedForChosenProvider() {
        val checks = answerChecks(KeyCheck.ACCEPTED)
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            typeKey("sk-ant-admin01-x")
            chooseProvider("OpenAI")
            pressSave()
            robot.reveal(robot.text(R.string.key_admin), forward = null)
            assertEquals(0, checks.get())
            assertNull(fixture.keys.account().getOrThrow())
        }
    }

    @Test fun replacingProviderKeepsConversations() {
        fixture.install(saveKey = false)
        fixture.seedChat("kept", "Kept chat", "Kept message")
        fixture.launch().use { scenario ->
            openSettings(scenario)
            saveKey()
            val first = fixture.keys.consentIdentity()
            assertNotNull(first)
            saveKey(OPENROUTER_KEY)
            robot.waitUntil { fixture.keys.account().getOrNull()?.provider == AiProvider.OPENROUTER }
            assertNotEquals(first, fixture.keys.consentIdentity())
            assertTrue(fixture.keys.consented())
            // The OpenRouter consent adds the routing line and the reply text for cloud voice.
            robot.reveal(robot.text(R.string.ai_disclosure, "OpenRouter"), forward = null)
            robot.click(robot.text(R.string.ai_disclosure, "OpenRouter"))
            robot.reveal(robot.text(R.string.consent_openrouter))
            robot.reveal(robot.text(R.string.consent_voice))
            robot.reveal(robot.text(R.string.consent_voice_input))
            assertTrue(fixture.model!!.snapshot.conversations.any { it.id == "kept" })
            assertTrue(fixture.store.load().conversations.any { it.id == "kept" })
        }
    }

    @Test fun guideListsProvidersByCapability() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.reveal(robot.text(R.string.key_help))
            for (p in listOf(AiProvider.OPENROUTER, AiProvider.OPENAI, AiProvider.DEEPSEEK, AiProvider.ANTHROPIC))
                assertTrue(robot.composedLink(scenario, p.displayName, p.profile.keyPage))
            // Each row is the touch target of its link (decision 5), and its gap text stays a separate text.
            fun row(gap: String) {
                robot.reveal(gap)
                robot.assertTarget(gap)
            }
            row(robot.text(R.string.key_guide_all))
            row(robot.text(R.string.key_guide_without_one, robot.text(R.string.key_guide_voice)))
            // DeepSeek and Anthropic share one gap text, so the text next to each link is checked in the row order.
            row(robot.text(R.string.key_guide_without_two, robot.text(R.string.key_guide_images), robot.text(R.string.key_guide_voice)))
            val texts = robot.texts()
            for (p in listOf(AiProvider.OPENROUTER, AiProvider.OPENAI, AiProvider.DEEPSEEK, AiProvider.ANTHROPIC)) {
                val gaps = keyGuideGaps(p.profile).map { robot.text(it) }
                val expected = when (gaps.size) {
                    0 -> robot.text(R.string.key_guide_all)
                    1 -> robot.text(R.string.key_guide_without_one, gaps[0])
                    else -> robot.text(R.string.key_guide_without_two, gaps[0], gaps[1])
                }
                val link = texts.indexOfFirst { it.startsWith(p.displayName) && it.contains("—") }
                assertTrue("$p in $texts", link >= 0)
                assertEquals(p.name, expected, texts[link + 1])
            }
        }
    }

    @Test fun guideHiddenOnceKeySaved() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            openSettings(scenario)
            robot.reveal(robot.text(R.string.key_help))
            saveKey(OPENROUTER_KEY)
            robot.waitUntil { !robot.composedText(scenario, robot.text(R.string.key_help)) }
            robot.reveal(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.key_delete))
            robot.click(robot.text(R.string.delete))
            robot.waitFor(robot.text(R.string.key_missing))
            robot.reveal(robot.text(R.string.key_help))
        }
    }

    // The image switch moved from «Изображения» into «Что доступно» (spec 14.1).
    @Test fun imageSwitchFollowsTheAccount() {
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            val settings = checkNotNull(fixture.model).imageGeneration.settings
            openSettings(scenario)
            saveKey(OPENROUTER_KEY)
            robot.reveal(robot.text(R.string.ai_feature_images_paid), forward = null)
            robot.reveal(robot.text(R.string.image_enable), forward = null)
            robot.assertTarget(robot.text(R.string.image_enable))
            robot.click(robot.text(R.string.image_enable))
            robot.waitUntil { settings.enabled }
        }
    }

    private companion object {
        // Fake keys. Each prefix selects one provider (spec 6.3).
        const val OPENAI_KEY = "sk-proj-alpha-test-only"
        const val OPENROUTER_KEY = "sk-or-v1-alpha-test-only"
        val DEEPSEEK_KEY = fakeDeepSeekKey()
        const val CHECK_FAILURE = "Fixture key check failure"
    }
}

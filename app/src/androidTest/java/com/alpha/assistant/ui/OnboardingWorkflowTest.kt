package com.alpha.assistant.ui

import com.alpha.assistant.R
import com.alpha.assistant.cloud.AiProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class OnboardingWorkflowTest {
    private val fixture = ShellFixture("onboarding")
    private val robot = ShellRobot("onboarding")
    @After fun remove() = fixture.remove()

    // The hour can change between composition and the check.
    private fun greetings(name: String) = (-1..1)
        .map { greetingRes((LocalTime.now().hour + it + 24) % 24) }
        .map { robot.text(R.string.greeting_named, robot.text(it), name) }

    @Test fun firstRunLeadsFromNameAndKeyToAnExampleRequest() {
        fixture.install(saveKey = false, onboarding = true)
        val composer = robot.text(R.string.composer_label)
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.onboarding_welcome_title))
            robot.waitFor(robot.text(R.string.onboarding_step, 1, 3))
            assertNull(robot.find(robot.text(R.string.desktop_prompt)))
            robot.diagnostics.capture("onboarding-welcome")
            robot.edit(robot.text(R.string.settings_name_label), "Анна")
            robot.click(robot.text(R.string.onboarding_next))
            robot.waitFor(robot.text(R.string.onboarding_step, 2, 3))
            robot.waitFor(robot.text(R.string.key_missing))
            for (provider in listOf(AiProvider.OPENROUTER, AiProvider.OPENAI, AiProvider.DEEPSEEK, AiProvider.ANTHROPIC))
                assertTrue(robot.composedLink(scenario, provider.displayName, provider.profile.keyPage))
            robot.diagnostics.capture("onboarding-key")
            robot.reveal(robot.text(R.string.key_label), forward = null)
            robot.enter(robot.text(R.string.key_label), "sk-or-v1-alpha-test-only")
            robot.selected("OpenRouter")
            robot.reveal(robot.text(R.string.consent_and_save))
            robot.click(robot.text(R.string.consent_and_save))
            robot.waitFor(robot.text(R.string.key_ready))
            assertTrue(fixture.keys.consented())
            // A saved key does not end the flow before its last step.
            robot.waitFor(robot.text(R.string.onboarding_step, 2, 3))
            robot.reveal(robot.text(R.string.onboarding_next))
            robot.click(robot.text(R.string.onboarding_next))
            robot.waitFor(robot.text(R.string.onboarding_ready_title))
            robot.diagnostics.capture("onboarding-ready")
            val example = robot.text(R.string.desktop_example_timer)
            robot.click(example)
            robot.waitUntil { greetings("Анна").any { robot.find(it) != null } }
            robot.waitUntil { robot.field(composer).text?.toString() == example }
            assertNull(robot.find(robot.text(R.string.onboarding_ready_title)))
            robot.waitUntil { fixture.store.load().onboarded }
            assertEquals(0, fixture.provider.calls)
            robot.click(robot.text(R.string.composer_send))
            robot.waitFor("Fixture response")
            assertEquals(example, fixture.provider.lastRequest?.turns?.lastOrNull { it.role == "user" }?.text)
        }
    }

    @Test fun laterOpensTheDesktopAndTheFlowDoesNotReturn() {
        fixture.install(saveKey = false, onboarding = true)
        fixture.launch().use {
            robot.click(robot.text(R.string.onboarding_next))
            robot.waitFor(robot.text(R.string.onboarding_step, 2, 3))
            robot.reveal(robot.text(R.string.onboarding_later))
            robot.click(robot.text(R.string.onboarding_later))
            robot.waitFor(robot.text(R.string.desktop_prompt))
            robot.reveal(robot.text(R.string.desktop_empty))
            robot.waitUntil { fixture.store.load().onboarded }
        }
        fixture.launch().use {
            robot.waitFor(robot.text(R.string.desktop_prompt))
            assertNull(robot.find(robot.text(R.string.onboarding_welcome_title)))
        }
    }

    @Test fun backReturnsToThePreviousStep() {
        fixture.install(saveKey = false, onboarding = true)
        fixture.launch().use {
            robot.click(robot.text(R.string.onboarding_next))
            robot.waitFor(robot.text(R.string.onboarding_step, 2, 3))
            robot.back()
            robot.waitFor(robot.text(R.string.onboarding_step, 1, 3))
            robot.waitFor(robot.text(R.string.onboarding_welcome_title))
        }
    }

    /** Saves [key] in step 2 and opens step 3. */
    private fun saveKeyAndContinue(key: String, provider: String) {
        robot.click(robot.text(R.string.onboarding_next))
        robot.reveal(robot.text(R.string.key_label))
        robot.enter(robot.text(R.string.key_label), key)
        // The provider field comes with the typed key. Before it shows, the step can be too short to scroll.
        robot.selected(provider)
        robot.reveal(robot.text(R.string.consent_and_save))
        robot.click(robot.text(R.string.consent_and_save))
        robot.waitFor(robot.text(R.string.key_ready))
        robot.reveal(robot.text(R.string.onboarding_next))
        robot.click(robot.text(R.string.onboarding_next))
    }

    @Test fun welcomeListsVisionAndVoice() {
        fixture.install(saveKey = false, onboarding = true)
        fixture.launch().use {
            robot.waitFor(robot.text(R.string.onboarding_welcome_title))
            val rows = listOf(R.string.onboarding_feature_answers, R.string.onboarding_feature_apps, R.string.onboarding_feature_vision,
                R.string.onboarding_feature_voice, R.string.onboarding_feature_more).map { robot.bounds(robot.text(it)).top }
            assertEquals(rows.sorted(), rows)
        }
    }

    @Test fun firstRunShowsOldKeyCard() {
        fixture.install(saveKey = false, onboarding = true)
        fixture.seedLegacyDeepSeek()
        fixture.launch().use {
            robot.click(robot.text(R.string.onboarding_next))
            robot.waitFor(robot.text(R.string.onboarding_step, 2, 3))
            assertTrue(robot.bounds(robot.text(R.string.legacy_deepseek)).bottom <= robot.bounds(robot.text(R.string.key_label)).top)
            robot.click(robot.text(R.string.legacy_deepseek_use))
            robot.selected("DeepSeek")
            robot.reveal(robot.text(R.string.consent_text, "DeepSeek"))
            robot.reveal(robot.text(R.string.consent_and_save))
            robot.click(robot.text(R.string.consent_and_save))
            robot.waitFor(robot.text(R.string.key_ready))
            assertEquals(AiProvider.DEEPSEEK, fixture.keys.account().getOrThrow()?.provider)
            robot.waitUntil { !fixture.legacy.has(AiProvider.DEEPSEEK) }
            robot.reveal(robot.text(R.string.onboarding_next))
            robot.click(robot.text(R.string.onboarding_next))
            robot.waitFor(robot.text(R.string.onboarding_ready_title))
        }
    }

    @Test fun readyStepNamesMissingImages() {
        fixture.install(saveKey = false, onboarding = true)
        fixture.launch().use {
            saveKeyAndContinue("sk-ant-api03-alpha-test-only", "Anthropic")
            robot.waitFor(robot.text(R.string.onboarding_ready_no_images, "Anthropic"))
            robot.waitFor(robot.text(R.string.onboarding_ready_voice))
        }
    }

    @Test fun readyStepWithOpenRouterHasNoImageLine() {
        fixture.install(saveKey = false, onboarding = true)
        fixture.launch().use {
            saveKeyAndContinue("sk-or-v1-alpha-test-only", "OpenRouter")
            // The voice line shows with the other step 3 lines, so the step is complete before the check.
            robot.waitFor(robot.text(R.string.onboarding_ready_voice))
            assertNull(robot.find(robot.text(R.string.onboarding_ready_no_images, "OpenRouter")))
        }
    }

    @Test fun returningUserSeesNoOnboarding() {
        fixture.install(saveKey = false, onboarding = true)
        fixture.store.saveSetting("onboarded", "1")
        fixture.seedLegacyDeepSeek()
        fixture.launch().use { scenario ->
            robot.waitFor(robot.text(R.string.desktop_prompt))
            assertNull(robot.find(robot.text(R.string.onboarding_welcome_title)))
            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.click(robot.text(R.string.settings_ai))
            robot.waitFor(robot.text(R.string.legacy_deepseek))
        }
    }

    @Test fun savedKeySkipsTheFlow() {
        fixture.install(onboarding = true)
        fixture.launch().use {
            robot.waitFor(robot.text(R.string.desktop_prompt))
            assertNull(robot.find(robot.text(R.string.onboarding_welcome_title)))
        }
    }
}

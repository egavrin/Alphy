package com.alpha.assistant.ui

import com.alpha.assistant.R
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
            assertTrue(robot.composedLink(scenario, robot.text(R.string.key_help), "https://platform.deepseek.com/api_keys"))
            robot.diagnostics.capture("onboarding-key")
            robot.reveal(robot.text(R.string.key_label))
            robot.enter(robot.text(R.string.key_label), "alpha-test-only")
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
            robot.waitUntil { fixture.store.hasSetting("onboarded") }
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
            robot.waitUntil { fixture.store.hasSetting("onboarded") }
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

    @Test fun savedKeySkipsTheFlow() {
        fixture.install(onboarding = true)
        fixture.launch().use {
            robot.waitFor(robot.text(R.string.desktop_prompt))
            assertNull(robot.find(robot.text(R.string.onboarding_welcome_title)))
        }
    }
}

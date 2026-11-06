package com.alpha.assistant.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.memory.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryShellWorkflowTest {
    private val robot = ShellRobot("memory-shell")

    @Test fun editsRemainLocalAndBackReturnsToTheOriginalChat() = withFixture { fixture ->
        fixture.seedChat("memory-chat", "Memory discussion", "Original memory discussion", selected = true)
        fixture.install()
        fixture.launch().use { scenario ->
            robot.waitFor("Original memory discussion")
            val composer = robot.text(R.string.composer_label)
            val factEditor = robot.text(R.string.memory_text)
            val save = robot.text(R.string.memory_save)
            robot.edit(composer, "Keep this chat draft")

            openMemory(scenario)
            robot.waitFor(robot.text(R.string.memory_empty))
            robot.click(robot.text(R.string.memory_add))
            robot.edit(factEditor, "Initial local fact")
            robot.click(save)
            robot.waitFor("Initial local fact")

            robot.click(robot.text(R.string.memory_edit_fact, 1))
            assertEquals("Initial local fact", robot.field(factEditor).text.toString())
            robot.edit(factEditor, UPDATED_FACT)
            robot.openDrawer(scenario)
            robot.back()
            robot.awaitDrawerClosed(scenario)
            assertEquals(UPDATED_FACT, robot.field(factEditor).text.toString())
            robot.waitGone(robot.text(R.string.memory_discard_title))

            scenario.recreate()
            assertEquals(UPDATED_FACT, robot.field(factEditor).text.toString())
            robot.back()
            robot.waitFor(robot.text(R.string.memory_discard_title))
            robot.click(robot.text(R.string.memory_keep_editing))
            assertEquals(UPDATED_FACT, robot.field(factEditor).text.toString())
            robot.click(save)
            robot.waitFor(UPDATED_FACT)
            robot.waitFor(robot.text(R.string.memory_edit_fact, 1))
            robot.diagnostics.capture("saved-after-recreation")

            robot.back()
            robot.waitFor(robot.text(R.string.settings))
            robot.back()
            robot.waitFor("Original memory discussion")
            assertEquals("Keep this chat draft", robot.field(composer).text.toString())

            robot.openDrawer(scenario)
            robot.click(robot.text(R.string.settings))
            robot.awaitDrawerClosed(scenario)
            robot.click(robot.text(R.string.settings_deepseek))
            robot.reveal(robot.text(R.string.key_label))
            assertTrue(robot.secure(scenario))
            robot.back()
            robot.back()
            robot.waitFor("Original memory discussion")
            robot.waitUntil { !robot.secure(scenario) }
        }
        assertEquals(0, fixture.provider.calls)
        val closedStore = checkNotNull(fixture.model).memoryStore
        assertEquals("The memory store is closed.", runCatching { closedStore.list() }.exceptionOrNull()?.message)
        MemoryStore(fixture.context, databaseName(fixture)).use { store ->
            val fact = store.list().single()
            assertEquals(UPDATED_FACT, fact.text)
            assertEquals(2L, fact.revision)
        }
    }

    @Test fun aSecondShellShowsItsOwnEmptyMemory() = withFixture { seeded ->
        MemoryStore(seeded.context, databaseName(seeded)).use { it.saveExplicitFact(UPDATED_FACT) }
        withFixture { empty ->
            empty.install()
            empty.launch().use { scenario ->
                robot.waitUntil { robot.composed(scenario, "desktop") }
                openMemory(scenario)
                robot.waitFor(robot.text(R.string.memory_empty))
                robot.waitGone(UPDATED_FACT)
                robot.back()
                robot.waitGone(robot.text(R.string.memory_empty))
                robot.waitFor(robot.text(R.string.settings))
                robot.back()
                robot.waitUntil { robot.composed(scenario, "desktop") }
            }
            assertEquals(0, empty.provider.calls)
        }
        MemoryStore(seeded.context, databaseName(seeded)).use { store ->
            assertEquals(listOf(UPDATED_FACT), store.list().map { it.text })
        }
    }

    @Test fun explicitMemoryReviewStaysInTheCurrentChat() = withFixture { fixture ->
        fixture.seedChat("memory-chat", "Memory discussion", "Original memory discussion", selected = true)
        fixture.install(saveKey = false)
        fixture.launch().use { scenario ->
            robot.waitFor("Original memory discussion")
            val fact = "Мне подходят короткие ответы"
            robot.edit(robot.text(R.string.composer_label), "Запомни: $fact")
            robot.click(robot.text(R.string.composer_send))
            assertEquals(fact, robot.field(robot.text(R.string.memory_text)).text.toString())
            assertTrue(robot.composed(scenario, "chat"))
            robot.click(robot.text(R.string.memory_save))
            robot.waitFor("Original memory discussion")
            assertEquals("memory-chat", fixture.model?.snapshot?.selectedId)
            robot.waitUntil { fixture.model?.snapshot?.draft?.text == "" }
            assertEquals(0, fixture.provider.calls)
            MemoryStore(fixture.context, databaseName(fixture)).use { assertEquals(listOf(fact), it.list().map { row -> row.text }) }
        }
    }

    @Test fun memoryIsReachableInAShortWindowWithLargeText() = withFixture { fixture ->
        fixture.install()
        fixture.launch().use { scenario ->
            scenario.onActivity { activity ->
                val model = checkNotNull(fixture.model)
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                        Box(Modifier.widthIn(max = 390.dp).fillMaxWidth().height(320.dp)) {
                            AlphaTheme { AlphaShell(model) }
                        }
                    }
                }
            }
            robot.waitUntil { robot.composed(scenario, "desktop") }
            openMemory(scenario)
            val title = robot.reveal(robot.text(R.string.memory_title), forward = null)
            assertTrue(title.isHeading)
            assertEquals(1, robot.texts().count { it == robot.text(R.string.memory_title) })
            robot.assertTarget(robot.text(R.string.menu))
            robot.assertTarget(robot.text(R.string.settings))
            robot.reveal(robot.text(R.string.memory_empty))
            robot.diagnostics.capture("short-window-large-text")
            robot.back()
            robot.waitFor(robot.text(R.string.settings))
            robot.back()
            robot.waitUntil { robot.composed(scenario, "desktop") }
        }
        assertEquals(0, fixture.provider.calls)
    }

    private fun openMemory(scenario: ActivityScenario<CloudTestActivity>) {
        val title = robot.text(R.string.memory_title)
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.reveal(title)
        robot.click(title)
        robot.reveal(robot.text(R.string.memory_add), forward = null)
    }

    private fun withFixture(test: (ShellFixture) -> Unit) {
        val fixture = ShellFixture("memory-shell")
        try {
            test(fixture)
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase(databaseName(fixture))
        }
    }

    private fun databaseName(fixture: ShellFixture) = "${fixture.store.databaseName}-memory.db"

    private companion object {
        const val UPDATED_FACT = "Revised local memory fact"
    }
}

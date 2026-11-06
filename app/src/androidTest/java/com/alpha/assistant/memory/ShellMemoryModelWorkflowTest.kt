package com.alpha.assistant.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellMemoryModelWorkflowTest {
    @Test fun pageRetainsDraftsAndKeepsSavedFactsInItsOwnDatabase() {
        val first = ShellFixture("memory-model-first")
        val second = ShellFixture("memory-model-second")
        val robot = ShellRobot("memory-model")
        val firstDatabase = "${first.store.databaseName}-memory.db"
        val secondDatabase = "${second.store.databaseName}-memory.db"
        try {
            val original = MemoryStore(first.context, firstDatabase).use {
                it.saveExplicitFact("Мой город — Казань.")
            }
            val saved = original.copy(text = "Мой город — Москва.", revision = original.revision + 1)
            first.install(saveKey = false)
            val retiredStore = first.launch().use { scenario ->
                val model = checkNotNull(first.model)
                val page = model.memoryPage
                scenario.onActivity { page.refresh() }
                robot.waitUntil { page.facts == listOf(original) && !page.busy }

                scenario.onActivity {
                    page.openEditor(original)
                    page.changeDraft(saved.text)
                }
                scenario.recreate()
                scenario.onActivity {
                    assertSame(page, first.model?.memoryPage)
                    assertEquals(saved.text, page.draft?.text)
                    page.saveDraft()
                }
                robot.waitUntil { page.facts == listOf(saved) && !page.busy }
                assertEquals(0, first.provider.calls)
                model.memoryStore
            }
            val closed = assertThrows(IllegalStateException::class.java) { retiredStore.list() }
            assertEquals("The memory store is closed.", closed.message)

            second.install(saveKey = false)
            second.launch().use { scenario ->
                val page = checkNotNull(second.model).memoryPage
                scenario.onActivity { page.refresh() }
                robot.waitUntil { page.facts == emptyList<MemoryFact>() && !page.busy }
                assertEquals(0, second.provider.calls)
            }
            MemoryStore(first.context, firstDatabase).use { assertEquals(listOf(saved), it.list()) }
        } finally {
            second.remove()
            first.remove()
            first.context.deleteDatabase(firstDatabase)
            second.context.deleteDatabase(secondDatabase)
        }
    }
}

package com.alpha.assistant.memory

import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryUiWorkflowTest {
    private val fixture = ShellFixture("memory-ui")
    private val robot = ShellRobot("memory-ui")
    private val name = "${fixture.name}-memory.db"
    private val store = MemoryStore(fixture.context, name)

    @After fun remove() {
        store.close()
        fixture.context.deleteDatabase(name)
        fixture.remove()
    }

    @Test fun emptyAddEditDeleteAndCancelStayLocal() = workflow {
        waitFor(R.string.memory_empty)
        click(R.string.memory_add)
        assertSaveDisabled()
        edit("Discard this draft")
        click(R.string.memory_cancel)
        waitFor(R.string.memory_discard_title)
        click(R.string.memory_keep_editing)
        assertDraft("Discard this draft")
        click(R.string.memory_cancel)
        click(R.string.memory_discard)
        waitFor(R.string.memory_empty)
        assertTrue(store.list().isEmpty())

        val original = "  Первая строка\nВторая строка  "
        click(R.string.memory_add)
        edit(original)
        click(R.string.memory_save)
        robot.waitUntil { original in robot.texts() }
        val fact = store.list().single()
        assertEquals(original, fact.text)

        click(R.string.memory_edit_fact, 1)
        assertDraft(original)
        val updated = "Изменённый факт"
        edit(updated)
        click(R.string.memory_save)
        robot.waitFor(updated)
        assertEquals(fact.copy(text = updated, revision = fact.revision + 1), store.list().single())

        click(R.string.memory_delete_fact, 1)
        waitFor(R.string.memory_delete_title)
        click(R.string.memory_cancel)
        waitFor(R.string.memory_add)
        assertEquals(updated, store.list().single().text)
        click(R.string.memory_delete_fact, 1)
        click(R.string.memory_delete)
        waitFor(R.string.memory_empty)
        assertTrue(store.list().isEmpty())
    }

    @Test fun staleEditKeepsDraftAndRequiresReviewOfLatestRevision() {
        val original = store.saveExplicitFact("Initial revision")
        workflow {
            click(R.string.memory_edit_fact, 1)
            edit("My revised text")
            val latest = MemoryStore(fixture.context, name).use {
                it.edit(original.id, original.revision, "Concurrent revision")
            }
            click(R.string.memory_save)
            waitFor(R.string.memory_stale_edit)
            robot.waitFor(latest.text)
            assertDraft("My revised text")
            assertEquals(latest, store.list().single())
            assertSaveDisabled()

            click(R.string.memory_use_latest)
            assertDraft("My revised text")
            assertEquals(latest, store.list().single())
            click(R.string.memory_save)
            robot.waitFor("My revised text")
            assertEquals(latest.copy(text = "My revised text", revision = latest.revision + 1), store.list().single())
        }
    }

    @Test fun deletedEditTargetCanOnlyBecomeANewFactByExplicitChoice() {
        val original = store.saveExplicitFact("To be removed")
        workflow {
            click(R.string.memory_edit_fact, 1)
            edit("Retain my draft")
            MemoryStore(fixture.context, name).use { it.delete(original.id, original.revision) }
            click(R.string.memory_save)
            waitFor(R.string.memory_missing_edit)
            assertDraft("Retain my draft")
            assertTrue(store.list().isEmpty())
            click(R.string.memory_convert_draft)
            assertTrue(store.list().isEmpty())
            click(R.string.memory_save)
            robot.waitFor("Retain my draft")
            assertTrue(store.list().single().id != original.id)
        }
    }

    @Test fun deleteConfirmationDoesNotApplyToAConcurrentRevision() {
        val original = store.saveExplicitFact("Before deletion")
        workflow {
            click(R.string.memory_delete_fact, 1)
            val latest = MemoryStore(fixture.context, name).use {
                it.edit(original.id, original.revision, "Keep this revision")
            }
            click(R.string.memory_delete)
            waitFor(R.string.memory_stale_delete)
            robot.waitFor(latest.text)
            assertEquals(latest, store.list().single())
        }
    }

    @Test fun failedWriteKeepsDraftAcrossActivityRecreationAndAllowsRetry() = workflow { scenario ->
        waitFor(R.string.memory_empty)
        rejectWrites()
        click(R.string.memory_add)
        edit("An unsaved memory fact")
        click(R.string.memory_save)
        waitFor(R.string.memory_save_failed)
        assertFalse(robot.texts().any { it.contains("private fixture error") })
        assertTrue(store.list().isEmpty())
        scenario.recreate()
        mount(scenario)
        waitFor(R.string.memory_save_failed)
        assertDraft("An unsaved memory fact")

        database().use { it.execSQL("DROP TRIGGER fixture_reject_write") }
        click(R.string.memory_save)
        robot.waitFor("An unsaved memory fact")
        assertEquals("An unsaved memory fact", store.list().single().text)
    }

    @Test fun failedLoadIsDistinctFromEmptyAndCanRetry() {
        MemoryStore(fixture.context, name).use { it.saveExplicitFact("Already saved") }
        val validVersion = database().use { database -> database.version.also { database.version = 200 } }
        workflow {
            waitFor(R.string.memory_load_failed)
            assertFalse(robot.texts().any { it.contains("Unknown memory database") })
            assertFalse(robot.texts().contains(robot.text(R.string.memory_empty)))
            database().use { it.version = validVersion }
            click(R.string.memory_retry)
            robot.waitFor("Already saved")
        }
    }

    @Test fun longTextAndNativeAccessibility() {
        val fact = store.saveExplicitFact("Начало\n${"длинный текст ".repeat(55)}\nКонец")
        workflow { scenario ->
            assertTrue(waitFor(R.string.memory_title).isHeading)
            robot.assertTarget(robot.text(R.string.memory_add))
            val edit = robot.text(R.string.memory_edit_fact, 1)
            val delete = robot.text(R.string.memory_delete_fact, 1)
            robot.reveal(edit)
            robot.assertTarget(edit)
            robot.reveal(delete)
            robot.assertTarget(delete)
            robot.reveal(edit)
            robot.click(edit)
            assertDraft(fact.text)
            scenario.recreate()
            mount(scenario)
            assertDraft(fact.text)
            click(R.string.memory_cancel)
            assertEquals(fact, store.list().single())
        }
    }

    @Test fun prefillPreservesExactTextAndSavesAcrossRecreation() = workflow { scenario ->
        val original = "  Первая строка\nВторая строка  "
        waitFor(R.string.memory_empty)
        withPage(scenario) { assertTrue(it.tryOpenPrefilledEditor(original)) }
        assertDraft(original)
        assertTrue(store.list().isEmpty())

        rejectWrites()
        click(R.string.memory_save)
        waitFor(R.string.memory_save_failed)
        assertDraft(original)
        assertTrue(store.list().isEmpty())

        scenario.recreate()
        mount(scenario)
        assertDraft(original)
        database().use { it.execSQL("DROP TRIGGER fixture_reject_write") }
        click(R.string.memory_save)
        robot.waitUntil { original in robot.texts() }
        assertEquals(original, store.list().single().text)
        withPage(scenario) { assertFalse(it.reviewingChatFact) }

        scenario.recreate()
        mount(scenario)
        robot.waitUntil { original in robot.texts() }
        click(R.string.memory_add)
        edit("Another fact")
        click(R.string.memory_save)
        robot.waitFor("Another fact")
    }

    @Test fun prefillDeclinesBusyPageAndPreservesNewFactAndEditDrafts() {
        val fact = store.saveExplicitFact("Saved fact")
        workflow { scenario ->
            robot.waitFor(fact.text)
            withPage(scenario) {
                it.refresh()
                assertFalse(it.tryOpenPrefilledEditor("Chat text"))
            }
            click(R.string.memory_add)
            edit("New fact draft")
            withPage(scenario) {
                assertFalse(it.tryOpenPrefilledEditor("Chat text"))
            }
            assertDraft("New fact draft")
            click(R.string.memory_cancel)
            click(R.string.memory_discard)

            click(R.string.memory_edit_fact, 1)
            edit("Edited fact draft")
            withPage(scenario) {
                assertFalse(it.tryOpenPrefilledEditor("Chat text"))
            }
            assertDraft("Edited fact draft")
            assertEquals(fact, store.list().single())
        }
    }

    @Test fun discardedPrefillDoesNotAffectALaterSave() = workflow { scenario ->
        waitFor(R.string.memory_empty)
        withPage(scenario) { assertTrue(it.tryOpenPrefilledEditor("Chat text")) }
        click(R.string.memory_cancel)
        click(R.string.memory_discard)
        waitFor(R.string.memory_empty)
        click(R.string.memory_add)
        edit("A local fact")
        click(R.string.memory_save)
        robot.waitFor("A local fact")
        assertEquals("A local fact", store.list().single().text)
        withPage(scenario) { assertFalse(it.reviewingChatFact) }
    }

    private fun workflow(block: (ActivityScenario<CloudTestActivity>) -> Unit) {
        fixture.install()
        fixture.launch().use { scenario ->
            mount(scenario)
            block(scenario)
        }
        assertEquals(0, fixture.provider.calls)
    }

    private fun mount(scenario: ActivityScenario<CloudTestActivity>) {
        scenario.onActivity { activity ->
            val holder = pageHolder(activity)
            activity.setContent {
                AlphaTheme {
                    MemoryScreen(holder.state, onExit = activity::finish, modifier = Modifier.safeDrawingPadding().imePadding())
                }
            }
        }
    }

    private fun withPage(scenario: ActivityScenario<CloudTestActivity>, block: (MemoryPageState) -> Unit) {
        scenario.onActivity { block(pageHolder(it).state) }
    }

    private fun pageHolder(activity: CloudTestActivity): PageHolder =
        ViewModelProvider(activity, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PageHolder(store) as T
        })[PageHolder::class.java]

    private fun waitFor(id: Int) = robot.waitFor(robot.text(id))

    private fun click(id: Int, vararg args: Any) {
        val label = robot.text(id, *args)
        robot.diagnostics.target(label)
        robot.waitUntil { actionNode(label)?.isEnabled == true }
        robot.click(label)
    }

    private fun assertSaveDisabled() {
        val label = robot.text(R.string.memory_save)
        robot.waitFor(label)
        val action = checkNotNull(actionNode(label)) { "No clickable node for $label" }
        assertFalse("Save must be disabled for a blank or stale draft.", action.isEnabled)
    }

    private fun actionNode(label: String) = generateSequence(robot.find(label)) { it.parent }.firstOrNull { it.isClickable }

    private fun edit(value: String) = robot.edit(robot.text(R.string.memory_text), value)
    private fun assertDraft(value: String) = assertEquals(value, robot.field(robot.text(R.string.memory_text)).text.toString())

    private fun database(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        fixture.context.getDatabasePath(name).path, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    private fun rejectWrites() {
        database().use {
            it.execSQL("""CREATE TRIGGER fixture_reject_write BEFORE INSERT ON memory_facts
                BEGIN SELECT RAISE(ABORT, 'private fixture error'); END""")
        }
    }

    private class PageHolder(store: MemoryStore) : ViewModel() {
        val state = MemoryPageState(store, viewModelScope)
    }
}

package com.alpha.assistant.memory

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.R
import com.alpha.assistant.data.Draft
import com.alpha.assistant.surfaces.AssistContext
import com.alpha.assistant.ui.FixtureProvider
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import com.alpha.assistant.ui.ShellViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExplicitChatMemoryWorkflowTest {
    private val robot = ShellRobot("explicit-chat-memory")

    @Test fun reviewsAndSavesOnceWithoutAKeyAcrossRecreation() = workflow { fixture, provider, scenario ->
        val model = checkNotNull(fixture.model)
        val draft = Draft("зАпОмНи:  Мой город — Казань.  ")
        val revised = "Мой город — Москва."
        scenario.onActivity {
            model.editDraft(draft)
            model.send()
            assertNull(model.surfaceDestination)
            assertTrue(model.memoryPage.reviewingChatFact)
            assertEquals(" Мой город — Казань.  ", model.memoryPage.draft?.text)
            assertEquals(draft, model.snapshot.draft)
            assertTrue(model.snapshot.conversations.isEmpty())
            val surface = model.surfaceSequence
            model.send()
            assertEquals(surface, model.surfaceSequence)
            assertEquals(" Мой город — Казань.  ", model.memoryPage.draft?.text)
            assertNotNull(model.requestError)
            model.clearRequestError()
            model.memoryPage.changeDraft(revised)
        }
        scenario.recreate()
        assertEquals(revised, robot.field(robot.text(R.string.memory_text)).text.toString())
        robot.waitUntil { robot.find(robot.text(R.string.memory_save))?.isEnabled == true && !model.memoryPage.busy }
        scenario.onActivity {
            assertSame(model, fixture.model)
            assertEquals(revised, model.memoryPage.draft?.text)
            assertEquals(draft, model.snapshot.draft)
            model.memoryPage.saveDraft()
            model.memoryPage.saveDraft()
        }
        robot.waitUntil { model.memoryPage.facts?.singleOrNull()?.text == revised && !model.memoryPage.busy }
        robot.waitUntil { fixture.store.load().draft.text.isEmpty() }
        assertEquals(listOf(revised), model.memoryStore.list().map { it.text })
        assertEquals(0, provider.calls)
        assertTrue(fixture.store.load().conversations.isEmpty())
    }

    @Test fun cancelBusyAndFailedSaveKeepTheOriginalChatDraft() = workflow { fixture, provider, scenario ->
        val model = checkNotNull(fixture.model)
        val draft = Draft("Запомни Я изучаю русский язык.")
        val page = model.memoryPage
        scenario.onActivity {
            model.editDraft(draft)
            page.refresh()
            assertTrue(page.busy)
            model.send()
            assertNotNull(model.requestError)
            assertNull(page.draft)
            assertEquals(draft, model.snapshot.draft)
        }
        robot.waitUntil { page.facts != null && !page.busy }
        scenario.onActivity {
            page.openEditor()
            page.changeDraft("Несохранённая запись")
            model.send()
            assertEquals("Несохранённая запись", page.draft?.text)
            assertEquals(draft, model.snapshot.draft)
            page.closeEditor()

            model.send()
            page.cancelEditor()
            assertTrue(page.discardDraft)
            page.closeEditor()
            assertEquals(draft, model.snapshot.draft)
        }
        robot.waitGone(robot.text(R.string.memory_text))
        robot.waitFor(robot.text(R.string.composer_label))
        robot.waitUntil { !page.busy && page.draft == null }
        val path = fixture.context.getDatabasePath("${fixture.store.databaseName}-memory.db")
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("""CREATE TRIGGER fixture_reject_memory BEFORE INSERT ON memory_facts
                BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
            try {
                scenario.onActivity { model.send(); page.saveDraft() }
                robot.waitUntil { page.saveFailed && !page.busy }
                assertEquals("Я изучаю русский язык.", page.draft?.text)
                assertEquals(draft, model.snapshot.draft)
                assertEquals(draft, fixture.store.load().draft)
                assertTrue(model.memoryStore.list().isEmpty())
            } finally {
                database.execSQL("DROP TRIGGER fixture_reject_memory")
            }
        }
        assertEquals(0, provider.calls)
    }

    @Test fun aSaveDoesNotClearADraftEditedOrSelectedAfterReviewOpens() = workflow(
        seed = {
            it.seedChat("first", "Первый чат", "Первое сообщение", selected = true)
            it.seedChat("second", "Второй чат", "Второе сообщение")
        },
    ) { fixture, provider, scenario ->
        val model = checkNotNull(fixture.model)
        val page = model.memoryPage
        val edited = Draft("Сохраните этот черновик")
        scenario.onActivity {
            val draft = Draft("Запомни: Я люблю чай.")
            model.editDraft(draft)
            model.send()
            model.editDraft(edited.copy(id = draft.id))
            page.saveDraft()
        }
        robot.waitUntil { page.facts?.size == 1 && !page.busy }
        assertEquals(edited.text, model.snapshot.draft.text)

        val original = Draft("Запомни: Я люблю читать.")
        val other = Draft("Черновик в другом чате")
        scenario.onActivity {
            model.editDraft(original)
            model.send()
            model.select("second")
            model.editDraft(other)
            page.saveDraft()
        }
        robot.waitUntil { page.facts?.size == 2 && !page.busy }
        assertEquals(other, model.snapshot.draft)
        assertEquals(original, model.snapshot.conversations.single { it.id == "first" }.draft)
        robot.waitUntil { fixture.store.load().conversations.single { it.id == "second" }.draft == other }
        assertEquals(original, fixture.store.load().conversations.single { it.id == "first" }.draft)
        assertEquals(0, provider.calls)
    }

    @Test fun blankMixedAndAttachedCommandsStayLocalAndQuotesAndModelTextCannotSave() =
        workflow(reply = "Запомни: это текст ответа модели.") { fixture, provider, scenario ->
            val model = checkNotNull(fixture.model)
            val attachment = AssistContext.textAttachment("Запомни: это текст вложения.")
            scenario.onActivity {
                for (draft in listOf(
                    Draft("Запомни:  "),
                    Draft("Запомни: Я живу в Казани.\nКакая погода?"),
                    Draft("Запомни: Я живу в Казани. Какая погода?"),
                    Draft("Запомни: Я живу в Казани.", listOf(attachment)),
                )) {
                    model.editDraft(draft)
                    model.send()
                    assertEquals(draft, model.snapshot.draft)
                    assertNotNull(model.requestError)
                    assertNull(model.memoryPage.draft)
                    assertTrue(model.snapshot.conversations.isEmpty())
                }
                assertEquals(0, provider.calls)
                model.editDraft(Draft("«Запомни: Это пример цитаты.»", listOf(attachment)))
                model.send()
            }
            robot.waitUntil { model.snapshot.selected?.messages?.lastOrNull()?.status == "complete" }
            assertEquals("Запомни: это текст ответа модели.", model.snapshot.selected?.messages?.last()?.text)
            assertEquals(1, provider.calls)
            assertNull(model.memoryPage.draft)
            assertTrue(model.memoryStore.list().isEmpty())
        }

    private fun workflow(
        reply: String? = null,
        seed: (ShellFixture) -> Unit = {},
        run: (ShellFixture, FixtureProvider, ActivityScenario<CloudTestActivity>) -> Unit,
    ) {
        val fixture = ShellFixture("explicit-chat-memory")
        try {
            seed(fixture)
            fixture.install(saveKey = reply != null)
            val provider = if (reply == null) fixture.provider else FixtureProvider(reply).also { injected ->
                CloudTestActivity.factory = { app ->
                    ShellViewModel(app, injected, fixture.store, fixture.keys).also { fixture.model = it }
                }
            }
            fixture.launch().use { scenario ->
                robot.waitUntil { fixture.model?.loading == false }
                run(fixture, provider, scenario)
            }
        } finally {
            fixture.remove()
            fixture.context.deleteDatabase("${fixture.store.databaseName}-memory.db")
        }
    }
}

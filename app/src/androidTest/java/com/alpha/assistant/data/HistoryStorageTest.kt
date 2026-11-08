package com.alpha.assistant.data

import android.database.DatabaseUtils
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoryStorageTest {
    @Test fun renameAndDeletionPreserveOtherStateAcrossReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-storage-${UUID.randomUUID()}.db"
        var store = ShellStore(context, name)
        val a = Conversation("a", "First", 1, 1, Draft("first draft"), listOf(Message("m", "user", "message", 1, "sent")))
        val b = Conversation("b", "Second", 2, 2, Draft("second draft"))
        val draft = Draft("new draft", listOf(Attachment("content://fixture/source", "source", "text/plain")))
        try {
            store.saveConversation(a); store.saveConversation(b); store.saveNewDraft(draft)
            // Stale theme and mode rows from earlier versions must not block load.
            store.saveSetting("selected", "b"); store.saveSetting("theme", "dark"); store.saveSetting("mode", "cloud")
            assertTrue(runCatching { store.renameConversation("a", " ") }.isFailure)
            assertEquals(a, store.load().conversations.find { it.id == "a" })
            store.renameConversation("a", "  Renamed  ")
            store.close(); store = ShellStore(context, name)
            assertEquals(a.copy(title = "Renamed"), store.load().conversations.find { it.id == "a" })
            store.deleteConversation("a")
            assertEquals(b, store.load().conversations.single())
            assertEquals(draft, store.load().newDraft)
            store.writableDatabase.execSQL("CREATE TRIGGER fixture_reject_delete BEFORE DELETE ON conversations BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            assertTrue(runCatching { store.clearHistory() }.isFailure)
            assertEquals(b, store.load().conversations.single())
            store.writableDatabase.execSQL("DROP TRIGGER fixture_reject_delete")
            store.deleteConversation("b")
            assertNull(store.load().selectedId)
            store.saveConversation(a); store.saveSetting("selected", "a")
            store.clearHistory()
            store.close(); store = ShellStore(context, name)
            val cleared = store.load()
            // The app ignores stale rows but must not delete them.
            assertEquals(2L, DatabaseUtils.queryNumEntries(store.readableDatabase, "settings", "name IN ('theme','mode')"))
            assertTrue(cleared.conversations.isEmpty()); assertNull(cleared.selectedId)
            assertEquals(draft, cleared.newDraft)
        } finally { store.close(); context.deleteDatabase(name) }
    }
}

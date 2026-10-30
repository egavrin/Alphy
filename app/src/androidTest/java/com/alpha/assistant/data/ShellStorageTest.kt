package com.alpha.assistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun databaseReopenPreservesIndependentDrafts() {
        val name = "shell-test.db"
        context.deleteDatabase(name)
        var store = ShellStore(context, name)
        try {
            store.saveNewDraft(Draft("new text", listOf(Attachment("content://test/new", "new.txt", "text/plain"))))
            val chat = Conversation("chat-a", "A", 1L, 2L, Draft("follow-up"), listOf(Message("message-a", "user", "hello", 1L, "not_sent")))
            store.saveConversation(chat)
            store.saveSetting("selected", chat.id)
            store.saveSetting("theme", "dark")
            store.saveSetting("mode", "local")
            store.close()
            store = ShellStore(context, name)
            val restored = store.load()
            assertEquals("new text", restored.newDraft.text)
            assertEquals("new.txt", restored.newDraft.attachments.single().name)
            assertEquals(chat, restored.conversations.single())
            assertEquals("chat-a", restored.selectedId)
            assertEquals("dark", restored.theme)
            assertEquals("local", restored.mode)
            store.saveNewDraft(Draft("changed"))
            assertEquals(chat, store.load().conversations.single())
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun credentialEncryptionRetainsDataOnFailure() {
        val name = "shell-key-test"
        val preferences = context.getSharedPreferences(name, 0)
        preferences.edit().clear().commit()
        val store = SecureKeyStore(context, name, "alpha.test.deepseek")
        try {
            assertNull(store.read().getOrThrow())
            store.save("  test-only-key  ").getOrThrow()
            assertEquals("test-only-key", store.read().getOrThrow())
            assertFalse(preferences.all.values.any { it.toString().contains("test-only-key") })
            assertTrue(store.save(" ").isFailure)
            assertEquals("test-only-key", store.read().getOrThrow())
            preferences.edit().putString("ciphertext", "invalid").commit()
            assertTrue(store.read().isFailure)
            assertEquals("invalid", preferences.getString("ciphertext", null))
        } finally { preferences.edit().clear().commit() }
    }
}

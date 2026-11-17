package com.alpha.assistant.data

import android.database.DatabaseUtils
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
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
            // Stale theme and mode rows from earlier versions must not block load.
            store.saveSetting("theme", "dark")
            store.saveSetting("mode", "local")
            store.close()
            store = ShellStore(context, name)
            val restored = store.load()
            // The app ignores stale rows but must not delete them.
            assertEquals(2L, DatabaseUtils.queryNumEntries(store.readableDatabase, "settings", "name IN ('theme','mode')"))
            assertEquals("new text", restored.newDraft.text)
            assertEquals("new.txt", restored.newDraft.attachments.single().name)
            assertEquals(chat, restored.conversations.single())
            assertNull(restored.selectedId)
            assertEquals(1L, DatabaseUtils.queryNumEntries(store.readableDatabase, "settings", "name = 'selected'"))
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
            store.save("  test-only-key  ", AiProvider.OPENAI).getOrThrow()
            assertEquals("test-only-key", store.read().getOrThrow())
            assertFalse(preferences.all.values.any { it.toString().contains("test-only-key") })
            assertTrue(store.save(" ", AiProvider.OPENAI).isFailure)
            assertEquals("test-only-key", store.read().getOrThrow())
            preferences.edit().putString("ciphertext", "invalid").commit()
            assertTrue(store.read().isFailure)
            assertEquals("invalid", preferences.getString("ciphertext", null))
        } finally { preferences.edit().clear().commit() }
    }

    private fun withStore(block: (SecureKeyStore, android.content.SharedPreferences) -> Unit) {
        val name = "ai-account-test"
        val preferences = context.getSharedPreferences(name, 0)
        preferences.edit().clear().commit()
        try { block(SecureKeyStore(context, name, "alpha.test.ai"), preferences) } finally { preferences.edit().clear().commit() }
    }

    @Test fun saveWritesProviderWithKeyAndConsent() = withStore { keys, preferences ->
        assertNull(keys.account().getOrThrow())
        assertTrue(keys.save("sk-or-v1-abc", AiProvider.OPENROUTER).isSuccess)
        assertEquals(AiAccount(AiProvider.OPENROUTER, "sk-or-v1-abc"), keys.account().getOrThrow())
        assertEquals("openrouter", preferences.getString("provider", null))
        assertTrue(keys.consented())
    }

    @Test fun saveStoresTrimmedKey() = withStore { keys, _ ->
        keys.save("  sk-proj-abc\n", AiProvider.OPENAI)
        assertEquals("sk-proj-abc", keys.account().getOrThrow()!!.key)
    }

    @Test fun providerChangeChangesConsentIdentity() = withStore { keys, _ ->
        keys.save("sk-proj-abc", AiProvider.OPENAI); val first = keys.consentIdentity()
        keys.save("sk-ant-api03-abc", AiProvider.ANTHROPIC)
        assertNotEquals(first, keys.consentIdentity())
    }

    @Test fun storedKeyWithoutProviderIsFailure() = withStore { keys, preferences ->
        keys.save("sk-proj-abc", AiProvider.OPENAI)
        preferences.edit().remove("provider").commit()
        assertTrue(keys.account().isFailure)
    }

    @Test fun clearRemovesProvider() = withStore { keys, preferences ->
        keys.save("sk-proj-abc", AiProvider.OPENAI)
        assertTrue(keys.clear().isSuccess)
        assertFalse(preferences.contains("provider"))
        assertNull(keys.account().getOrThrow())
    }
}

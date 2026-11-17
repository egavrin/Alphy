package com.alpha.assistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class LegacyKeysTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val legacyName = "legacy-keys-deepseek"
    private val legacyAlias = "alpha.test.legacy.deepseek"
    private val imageName = "legacy-keys-images"
    private val imageAlias = "alpha.test.legacy.images"
    private val newName = "legacy-keys-new"
    private val newAlias = "alpha.test.legacy.new"
    private val names = listOf(legacyName, imageName, newName)

    private fun legacy() = LegacyKeys(context, legacyName, legacyAlias, imageName, imageAlias)
    private fun fresh() = SecureKeyStore(context, newName, newAlias)

    @Before fun clean() = names.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    private fun hasAlias(alias: String) = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    @After fun cleanUp() { clean(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run { listOf(legacyAlias, imageAlias, newAlias).forEach(::deleteEntry) } }

    @Test fun newStoreNeverReadsDeepSeekStore() {
        SecureKeyStore(context, legacyName, legacyAlias).save("sk-deepseek", AiProvider.DEEPSEEK)
        assertNull(fresh().account().getOrThrow())
        assertTrue(legacy().has(AiProvider.DEEPSEEK))
        assertEquals("sk-deepseek", legacy().key(AiProvider.DEEPSEEK).getOrThrow())
    }

    @Test fun legacyKeysStayUntilCleared() {
        SecureKeyStore(context, legacyName, legacyAlias).save("sk-deepseek", AiProvider.DEEPSEEK)
        SecureKeyStore(context, imageName, imageAlias).save("sk-or-v1-image", AiProvider.OPENROUTER)
        val keys = legacy()
        assertTrue(fresh().save("sk-proj-abc", AiProvider.OPENAI).isSuccess)
        assertTrue(keys.has(AiProvider.DEEPSEEK))
        assertTrue(keys.has(AiProvider.OPENROUTER))
        assertEquals("sk-or-v1-image", keys.key(AiProvider.OPENROUTER).getOrThrow())
        assertTrue(hasAlias(legacyAlias))
        assertTrue(hasAlias(imageAlias))
        assertTrue(keys.clear(AiProvider.DEEPSEEK).isSuccess)
        assertFalse(hasAlias(legacyAlias))
        assertTrue(hasAlias(imageAlias))
        assertFalse(keys.has(AiProvider.DEEPSEEK))
        assertNull(keys.key(AiProvider.DEEPSEEK).getOrThrow())
        assertTrue(keys.has(AiProvider.OPENROUTER))
        assertTrue(keys.clear(AiProvider.OPENROUTER).isSuccess)
        assertFalse(keys.has(AiProvider.OPENROUTER))
        assertFalse(hasAlias(imageAlias))
        assertEquals("sk-proj-abc", fresh().account().getOrThrow()!!.key)
    }
}

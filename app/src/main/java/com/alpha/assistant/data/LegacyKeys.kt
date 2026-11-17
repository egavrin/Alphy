package com.alpha.assistant.data

import android.content.Context
import com.alpha.assistant.cloud.AiProvider
import java.security.KeyStore

/** Read-only view of the two earlier key stores. Requests never read them. Only an explicit user action calls a clear method. */
internal class LegacyKeys(
    private val context: Context,
    private val deepSeekName: String = "alpha_credentials",
    private val deepSeekAlias: String = "alpha.deepseek.v1",
    private val imageName: String = "alpha_images_credentials",
    private val imageAlias: String = "alpha.alpha_images.openrouter.v1",
) {
    private class Store(val name: String, val alias: String, val keys: SecureKeyStore)
    private val stores = mapOf(
        AiProvider.DEEPSEEK to Store(deepSeekName, deepSeekAlias, SecureKeyStore(context, deepSeekName, deepSeekAlias)),
        AiProvider.OPENROUTER to Store(imageName, imageAlias, SecureKeyStore(context, imageName, imageAlias)),
    )

    /** The providers that have an old store, DeepSeek first. */
    val providers: Set<AiProvider> get() = stores.keys
    fun key(provider: AiProvider): Result<String?> = stores.getValue(provider).keys.read()
    fun has(provider: AiProvider): Boolean =
        context.getSharedPreferences(stores.getValue(provider).name, Context.MODE_PRIVATE).contains("ciphertext")
    fun clear(provider: AiProvider): Result<Unit> = stores.getValue(provider).let { store ->
        store.keys.clear().mapCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(store.alias) }
    }
}

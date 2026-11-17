package com.alpha.assistant.data

import android.content.Context
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.content.SharedPreferences
import java.io.Closeable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Errors preserve ciphertext. A failed read must never create a replacement key. The consent record is written and removed with the key. */
class SecureKeyStore(context: Context, name: String = "alpha_ai_credentials", private val alias: String = "alpha.ai.v1") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    // The user saves a key with «Согласиться и сохранить», so one commit stores the key and the consent.
    @Synchronized internal fun save(value: String, provider: AiProvider): Result<Unit> = runCatching {
        require(value.isNotBlank()) { "API key cannot be blank" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(create = true)) }
        val ciphertext = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("provider", provider.id).putString("consent", "1").commit()) { "Credential write failed" }
    }
    @Synchronized fun consented(): Boolean = preferences.getString("consent", null) == "1"
    // The IV changes with each credential write and contains no key material.
    internal fun consentIdentity(): String? = preferences.getString("iv", null)?.takeIf { consented() }
    internal fun observeConsent(changed: () -> Unit): Closeable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "iv" || key == "consent") changed()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return Closeable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @Synchronized fun read(): Result<String?> = runCatching {
        val ciphertext = preferences.getString("ciphertext", null)
        val iv = preferences.getString("iv", null)
        if (ciphertext == null && iv == null) return@runCatching null
        check(ciphertext != null && iv != null) { "Incomplete encrypted credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8).also { check(it.isNotBlank()) }
    }
    /** A stored key without a known provider is a failure, so a request never guesses the destination. */
    @Synchronized internal fun account(): Result<AiAccount?> = runCatching {
        val key = read().getOrThrow() ?: return@runCatching null
        val provider = AiProvider.entries.firstOrNull { it.id == preferences.getString("provider", null) }
        AiAccount(checkNotNull(provider) { "Stored credential has no provider" }, key)
    }
    /** The provider of the stored credential, read from the plain preference with no decryption. Null without a stored credential. */
    internal fun provider(): AiProvider? =
        if (preferences.contains("ciphertext")) AiProvider.entries.firstOrNull { it.id == preferences.getString("provider", null) } else null
    @Synchronized fun clear(): Result<Unit> = runCatching {
        check(preferences.edit().remove("ciphertext").remove("iv").remove("provider").remove("consent").commit()) { "Credential removal failed" }
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !preferences.contains("ciphertext") && !preferences.contains("iv")) { "Credential key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
}

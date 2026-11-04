package com.alpha.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Errors preserve ciphertext. A failed read must never create a replacement key. The consent record is written and removed with the key. */
class SecureKeyStore(context: Context, name: String = "alpha_credentials", private val alias: String = "alpha.deepseek.v1") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    // The user saves a key with «Согласиться и сохранить», so one commit stores the key and the consent.
    @Synchronized fun save(value: String): Result<Unit> = runCatching {
        require(value.isNotBlank()) { "API key cannot be blank" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(create = true)) }
        val ciphertext = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("consent", "1").commit()) { "Credential write failed" }
    }
    @Synchronized fun recordConsent(): Result<Unit> = runCatching {
        check(preferences.contains("ciphertext") && preferences.contains("iv")) { "No stored credential" }
        check(preferences.edit().putString("consent", "1").commit()) { "Consent write failed" }
    }
    @Synchronized fun consented(): Boolean = preferences.getString("consent", null) == "1"
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
    @Synchronized fun clear(): Result<Unit> = runCatching {
        check(preferences.edit().remove("ciphertext").remove("iv").remove("consent").commit()) { "Credential removal failed" }
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

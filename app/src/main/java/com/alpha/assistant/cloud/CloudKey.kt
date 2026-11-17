package com.alpha.assistant.cloud

import android.content.Context
import com.alpha.assistant.R
import com.alpha.assistant.data.SecureKeyStore

/** The only account read for a cloud request. Call it in an IO context. A failed read comes first, so it is never reported as a missing key. */
internal fun SecureKeyStore.cloudAccount(context: Context): AiAccount {
    val result = account()
    if (result.isFailure) throw CloudFailure("key_read", context.getString(R.string.cloud_key_read))
    val account = result.getOrNull() ?: throw CloudFailure("key_missing", context.getString(R.string.cloud_key_missing))
    if (account.key.any { it.isWhitespace() || it.isISOControl() }) throw CloudFailure("key_format", context.getString(R.string.cloud_key_format))
    if (!consented()) throw CloudFailure("consent", context.getString(R.string.cloud_consent))
    return account
}

internal fun SecureKeyStore.cloudKey(context: Context): String = cloudAccount(context).key

/**
 * The account of voice input and cloud voice. The consent gate of every cloud request also covers the recording and the
 * reply text (spec 14.7). Only a missing key or consent means no account. Other failures reach the caller.
 */
internal fun SecureKeyStore.voiceAccount(context: Context): AiAccount? = try { cloudAccount(context) }
    catch (failure: CloudFailure) { if (failure.code == "key_missing" || failure.code == "consent") null else throw failure }

/** «Открыть настройки» fixes the failures with these codes. */
internal val KEY_ERRORS = setOf("key_read", "key_missing", "key_format", "consent", "auth")

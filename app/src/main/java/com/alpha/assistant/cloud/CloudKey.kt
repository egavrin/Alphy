package com.alpha.assistant.cloud

import android.content.Context
import com.alpha.assistant.R
import com.alpha.assistant.data.SecureKeyStore

/** The only key read for a cloud request. Call it in an IO context. A failed read comes first, so it is never reported as a missing key. */
internal fun SecureKeyStore.cloudKey(context: Context): String {
    val result = read()
    if (result.isFailure) throw CloudFailure("key_read", context.getString(R.string.cloud_key_read))
    val key = result.getOrNull() ?: throw CloudFailure("key_missing", context.getString(R.string.cloud_key_missing))
    if (key.any { it.isWhitespace() || it.isISOControl() }) throw CloudFailure("key_format", context.getString(R.string.cloud_key_format))
    if (!consented()) throw CloudFailure("consent", context.getString(R.string.cloud_consent))
    return key
}

/** «Открыть настройки» fixes the failures with these codes. */
internal val KEY_ERRORS = setOf("key_read", "key_missing", "key_format", "consent", "auth")

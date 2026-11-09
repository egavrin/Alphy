package com.alpha.assistant.surfaces

import android.content.Intent
import android.net.Uri

internal sealed interface SharedDraft {
    data class Text(val value: String) : SharedDraft
    data class Image(val uri: Uri, val mime: String) : SharedDraft

    companion object {
        fun from(intent: Intent): SharedDraft? = runCatching {
            if (intent.action != Intent.ACTION_SEND || (intent.clipData?.itemCount ?: 0) > 1) return null
            val clip = intent.clipData?.getItemAt(0)
            val mime = intent.type.orEmpty()
            when {
                mime == "text/plain" -> {
                    val value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: clip?.text
                    value?.takeIf { it.length in 1..8000 && it.isNotBlank() }?.let { Text(it.toString()) }
                }
                mime.startsWith("image/") -> {
                    val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    if (stream == null && intent.hasExtra(Intent.EXTRA_STREAM)) return null
                    if (stream != null && clip?.uri != null && stream != clip.uri) return null
                    val uri = stream ?: clip?.uri
                    uri?.takeIf { it.scheme == "content" && !it.authority.isNullOrBlank() }?.let { Image(it, mime) }
                }
                else -> null
            }
        }.getOrNull()
    }
}

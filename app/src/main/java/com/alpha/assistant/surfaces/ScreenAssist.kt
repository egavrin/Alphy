package com.alpha.assistant.surfaces

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.text.InputType
import android.view.View
import com.alpha.assistant.data.ScreenContext
import com.alpha.assistant.data.screenPrefix
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal object ScreenAssist {
    private const val SCREEN_LIMIT = 8_000
    private const val SELECTION_LIMIT = 2_000
    private const val PAGE_LIMIT = 16_000
    private const val PAGE_BYTES = 128 * 1024
    private const val READ_TIMEOUT_MS = 10_000L
    private val chromePackages = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
    private val reader = CoroutineScope(Dispatchers.IO)

    data class Captured(val screen: ScreenContext, val pageReference: Uri?)
    data class Page(val text: String, val truncated: Boolean)

    fun capture(context: Context, structure: AssistStructure?, content: AssistContent?): Captured {
        val packageName = structure?.activityComponent?.packageName
        val label = packageName?.let { name ->
            runCatching { context.packageManager.getApplicationInfo(name, 0).loadLabel(context.packageManager) }
                .getOrNull().bounded(256)
        }
        val webUrl = content?.takeIf { it.isAppProvidedWebUri }?.webUri.validWebUrl()
        val intentUrl = content?.takeIf { it.isAppProvidedIntent }?.intent
            ?.takeIf { it.action == Intent.ACTION_VIEW }?.data.validWebUrl()
        val metadata = content?.structuredData?.takeIf { it.length <= 32_000 }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val text = StringBuilder()
        val selection = StringBuilder()
        var title: String? = null
        var count = 0
        var truncated = false

        fun append(target: StringBuilder, value: String, limit: Int) {
            val separator = if (target.isEmpty()) "" else "\n"
            val remaining = (limit - target.length - separator.length).coerceAtLeast(0)
            val part = value.screenPrefix(remaining)
            if (part.isNotEmpty()) target.append(separator).append(part)
            if (part.length < value.length) truncated = true
        }

        fun visit(node: AssistStructure.ViewNode, depth: Int) {
            count++
            if (node.isAssistBlocked || node.visibility != View.VISIBLE || node.isPassword()) return
            if (depth > 20) {
                truncated = true
                return
            }
            val raw = node.text
            val value = raw.bounded(SCREEN_LIMIT + 1) ?: node.contentDescription.bounded(SCREEN_LIMIT + 1)
            if (value != null) append(text, value, SCREEN_LIMIT)
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            if (raw != null && start >= 0 && end > start && end <= raw.length &&
                !(start > 0 && raw[start].isLowSurrogate() && raw[start - 1].isHighSurrogate())
            ) {
                val selected = raw.subSequence(start, minOf(end, start + SELECTION_LIMIT + 1)).toString()
                if (selected.isNotBlank()) append(selection, selected, SELECTION_LIMIT)
            }
            for (index in 0 until node.childCount) {
                if (count >= 2_000) {
                    truncated = true
                    break
                }
                visit(node.getChildAt(index), depth + 1)
            }
        }

        if (structure != null) {
            val windows = minOf(structure.windowNodeCount, 20)
            truncated = structure.windowNodeCount > windows
            for (index in 0 until windows) {
                if (count >= 2_000) {
                    truncated = true
                    break
                }
                val window = structure.getWindowNodeAt(index)
                val root = window.rootViewNode
                if (!root.isAssistBlocked && title == null) title = window.title.bounded(512)
                visit(root, 0)
            }
        }
        fun field(name: String, limit: Int) = (metadata?.opt(name) as? String).bounded(limit)
        val screen = ScreenContext(
            sourcePackage = packageName.bounded(256), appLabel = label, windowTitle = title,
            visibleText = text.toString().takeIf { it.isNotBlank() },
            selection = selection.toString().takeIf { it.isNotBlank() },
            pageUrl = webUrl ?: intentUrl,
            pageUrlSource = when {
                webUrl != null -> "app_web_uri"
                intentUrl != null -> "app_view_intent"
                else -> null
            },
            contentType = field("@type", 128), contentTitle = field("headline", 512) ?: field("name", 512),
            contentDescription = field("description", 1_000), truncated = truncated,
        )
        // The Chrome-specific URI is a local capability, never part of the snapshot.
        val reference = if (packageName in chromePackages && webUrl != null) {
            (metadata?.optJSONObject("page_metadata")?.opt("content_uri") as? String)
                ?.takeIf { it.length <= 512 }?.let(Uri::parse)?.takeIf { uri ->
                    uri.scheme == "content" && uri.encodedAuthority == "$packageName.PageContentProvider" &&
                        uri.encodedQuery == null && uri.encodedFragment == null &&
                        uri.encodedPath?.matches(Regex("/text/[0-9a-fA-F-]{36}")) == true
                }
        } else null
        return Captured(screen, reference)
    }

    suspend fun readChrome(context: Context, reference: Uri): Page? = withTimeoutOrNull(READ_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val descriptor = AtomicReference<ParcelFileDescriptor?>()
            val work = reader.launch {
                val page = runCatching {
                    val provider = context.packageManager.resolveContentProvider(checkNotNull(reference.authority), 0)
                    if (provider?.packageName !in chromePackages ||
                        reference.authority != "${provider?.packageName}.PageContentProvider" ||
                        context.checkUriPermission(reference, Process.myPid(), Process.myUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) return@runCatching null
                    val opened = context.contentResolver.openFileDescriptor(reference, "r", signal) ?: return@runCatching null
                    descriptor.set(opened)
                    try {
                        if (!continuation.isActive) return@runCatching null
                        ParcelFileDescriptor.AutoCloseInputStream(opened).use { stream ->
                            val bytes = stream.readNBytes(PAGE_BYTES + 1)
                            val count = minOf(bytes.size, PAGE_BYTES)
                            val decoded = String(bytes, 0, count, Charsets.UTF_8).trimEnd('\uFFFD')
                            val text = decoded.screenPrefix(PAGE_LIMIT).trim()
                            text.takeIf { it.isNotEmpty() }?.let {
                                Page(it, bytes.size > PAGE_BYTES || decoded.length > PAGE_LIMIT)
                            }
                        }
                    } finally {
                        descriptor.getAndSet(null)?.close()
                    }
                }.getOrNull()
                continuation.resume(page)
            }
            continuation.invokeOnCancellation {
                work.cancel()
                reader.launch { runCatching { descriptor.getAndSet(null)?.close() } }
                reader.launch { runCatching { signal.cancel() } }
            }
        }
    }

    private fun CharSequence?.bounded(limit: Int): String? =
        this?.subSequence(0, minOf(length, limit))?.toString()?.screenPrefix(limit)?.takeIf { it.isNotBlank() }

    private fun Uri?.validWebUrl(): String? {
        val value = this?.toString()?.takeIf { it.length <= 2_048 } ?: return null
        val parsed = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf { parsed.scheme?.lowercase() in setOf("https", "http") &&
            parsed.host != null && parsed.rawUserInfo == null }
    }

    private fun AssistStructure.ViewNode.isPassword(): Boolean {
        val kind = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
}

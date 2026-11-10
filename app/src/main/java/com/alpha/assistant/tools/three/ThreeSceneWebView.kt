package com.alpha.assistant.tools.three

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.Window
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.alpha.assistant.sandbox.SandboxContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.sqrt

internal data class ThreeSceneAsset(
    val id: String,
    val mediaType: String,
    val size: Long,
    val name: String,
    val open: () -> InputStream,
) {
    fun description() = JSONObject().put("id", id).put("mediaType", mediaType)
        .put("size", size).put("name", name)
}

/** One foreground scene. The shell serves exact local paths and owns the worker's only native channel. */
internal class ThreeSceneWebView(
    context: Context,
    private val artifactId: String,
    private val revision: Int,
    private val source: String,
    assets: List<ThreeSceneAsset>,
    private val textures: List<com.alpha.assistant.images.GeneratedImage> = emptyList(),
) {
    private val sessionId = UUID.randomUUID().toString()
    private val domain = "scene-$sessionId.invalid"
    private val origin = "https://$domain"
    private val assets = assets.associateBy { it.id }
    private val ready = CompletableDeferred<Unit>()
    private var pending: CompletableDeferred<JSONObject>? = null
    private var sequence = 0
    private var operation: ThreeSceneOperation? = null
    private var failure: ThreeSceneFailure? = null
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    val view = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    private val storage = try {
        check(available()) { "This WebView build is unavailable for 3D scenes." }
        WebViewCompat.setProfile(view, PROFILE)
        WebViewCompat.getProfile(view).also { it.cookieManager.setAcceptCookie(false) }.webStorage
    } catch (error: Exception) {
        view.destroy()
        throw error
    }

    init {
        view.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowContentAccess = false
            allowFileAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        view.webChromeClient = WebChromeClient()
        view.setDownloadListener { _, _, _, _, _ -> }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                response(context, request)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail(ThreeSceneProblem.RENDERER_CLOSED)
                close()
                return true
            }
        }
        WebViewCompat.addWebMessageListener(view, BRIDGE, setOf(origin)) { _, message, sourceOrigin, main, _ ->
            if (main && sourceOrigin.toString() == origin && !closed) receive(message.data)
        }
    }

    suspend fun start(state: JSONObject): JSONObject = withTimeoutOrNull(10_000) {
        check(view.isAttachedToWindow && view.width > 0 && view.height > 0) { "The 3D viewport is not attached." }
        view.loadUrl("$origin/index.html")
        ready.await()
        exchange("init", JSONObject().put("state", state).put("viewport", viewport()), 9_000)
    } ?: throw ThreeSceneFailure(ThreeSceneProblem.TIMEOUT, ThreeSceneOperation.INIT)

    suspend fun event(event: JSONObject): JSONObject = exchange("event", event, 500)

    suspend fun frame(timeMs: Long, dtMs: Int): JSONObject = exchange(
        "frame", JSONObject().put("timeMs", timeMs).put("dtMs", dtMs), 250)

    suspend fun dispose() = exchange("dispose", JSONObject(), 250)

    fun viewport(): JSONObject {
        val width = max(1, view.width)
        val height = max(1, view.height)
        val scale = minOf(1.0, 2048.0 / max(width, height), sqrt(2_000_000.0 / (width.toDouble() * height)))
        return JSONObject().put("width", max(1, (width * scale).toInt()))
            .put("height", max(1, (height * scale).toInt()))
    }

    suspend fun capture(window: Window): Bitmap = withTimeoutOrNull(1_500) {
        suspendCancellableCoroutine { continuation ->
            val xy = IntArray(2).also(view::getLocationInWindow)
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0 || !view.isShown) {
                continuation.resumeWithException(ThreeSceneFailure(ThreeSceneProblem.CAPTURE_FAILED))
                return@suspendCancellableCoroutine
            }
            val scale = minOf(1.0, 640.0 / max(width, height))
            val bitmap = Bitmap.createBitmap(max(1, (width * scale).toInt()),
                max(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(window, Rect(xy[0], xy[1], xy[0] + width, xy[1] + height), bitmap, { result ->
                    if (result == PixelCopy.SUCCESS && !closed && continuation.isActive) {
                        continuation.resume(bitmap) { _, image, _ -> image.recycle() }
                    } else {
                        bitmap.recycle()
                        if (continuation.isActive) {
                            continuation.resumeWithException(ThreeSceneFailure(ThreeSceneProblem.CAPTURE_FAILED))
                        }
                    }
                }, handler)
            } catch (_: Exception) {
                bitmap.recycle()
                continuation.resumeWithException(ThreeSceneFailure(ThreeSceneProblem.CAPTURE_FAILED))
            }
        }
    } ?: throw ThreeSceneFailure(ThreeSceneProblem.CAPTURE_FAILED)

    fun close() {
        if (closed) return
        closed = true
        fail(ThreeSceneProblem.CLOSED)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        WebStorageCompat.deleteBrowsingDataForSite(storage, origin) {}
    }

    private suspend fun exchange(type: String, payload: JSONObject, timeoutMs: Long): JSONObject {
        failure?.let { throw it }
        check(pending == null)
        val result = CompletableDeferred<JSONObject>()
        pending = result
        operation = when (type) {
            "init" -> ThreeSceneOperation.INIT
            "frame" -> ThreeSceneOperation.FRAME
            "dispose" -> ThreeSceneOperation.DISPOSE
            else -> if (payload.optString("type") == "open") ThreeSceneOperation.OPEN else ThreeSceneOperation.EVENT
        }
        val request = JSONObject().put("sequence", ++sequence).put("type", type).put("payload", payload)
        try {
            view.evaluateJavascript("window.alphySceneSend(" + JSONObject.quote(request.toString()) + ")", null)
            return withTimeoutOrNull(timeoutMs) { result.await() } ?: run {
                fail(ThreeSceneProblem.TIMEOUT)
                throw checkNotNull(failure)
            }
        } finally {
            pending = null
            operation = null
        }
    }

    private fun receive(raw: String?) {
        if (failure != null) return
        try {
            val message = SandboxContract.objectFrom(requireNotNull(raw), SandboxContract.MAX_RESULT_BYTES)
            require(message.keys().asSequence().toSet() ==
                setOf("protocolVersion", "artifactId", "revision", "sessionId", "sequence", "type", "payload"))
            require(message.get("protocolVersion") == 1 && message.get("artifactId") == artifactId &&
                message.get("revision") == revision && message.get("sessionId") == sessionId)
            val payload = message.getJSONObject("payload")
            when (message.get("type")) {
                "ready" -> {
                    require(message.get("sequence") == 0 && !ready.isCompleted)
                    ready.complete(Unit)
                }
                "snapshot" -> {
                    require(message.get("sequence") == sequence)
                    requireNotNull(pending).complete(payload)
                }
                "failed" -> {
                    require(message.get("sequence") == if (pending == null) 0 else sequence)
                    require(payload.keys().asSequence().toSet() == setOf("code"))
                    fail(ThreeSceneProblem.fromWorker(payload.getString("code")))
                }
                else -> fail(ThreeSceneProblem.INVALID_RESPONSE)
            }
        } catch (_: Exception) {
            fail(ThreeSceneProblem.INVALID_RESPONSE)
        }
    }

    private fun fail(problem: ThreeSceneProblem) {
        if (failure != null) return
        val error = ThreeSceneFailure(problem, operation)
        failure = error
        ready.completeExceptionally(error)
        pending?.completeExceptionally(error)
    }

    private fun response(context: Context, request: WebResourceRequest): WebResourceResponse {
        val url: Uri = request.url
        if (request.method != "GET" || url.scheme != "https" || url.encodedAuthority != domain ||
            url.encodedQuery != null || url.encodedFragment != null) return blocked()
        val path = url.encodedPath ?: return blocked()
        try {
            val asset = if (path.startsWith("/asset/")) assets[path.removePrefix("/asset/")] else null
            if (asset != null) return WebResourceResponse(asset.mediaType, null, 200, "OK",
                mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), asset.open())
            val (mime, stream) = when (path) {
                "/session.js" -> "text/javascript" to ("export default " + JSONObject()
                    .put("artifactId", artifactId).put("revision", revision).put("sessionId", sessionId)
                    .put("assets", JSONArray(assets.values.map { it.description() }))
                    .put("textures", JSONArray(textures.map {
                        JSONObject().put("target", it.slot).put("id", it.asset.id)
                            .put("mime", it.asset.mime).put("wrap", it.aspect == com.alpha.assistant.images.ImageAspect.EQUIRECTANGULAR)
                    })).toString()).byteInputStream()
                "/scene.js" -> "text/javascript" to source.byteInputStream()
                in localFiles -> localFiles.getValue(path) to context.assets.open("threejs$path")
                else -> return blocked()
            }
            val policy = if (path == "/index.html") SHELL_POLICY else WORKER_POLICY
            return WebResourceResponse(mime, "UTF-8", 200, "OK",
                mapOf("Content-Security-Policy" to policy, "Cache-Control" to "no-store",
                    "X-Content-Type-Options" to "nosniff"), stream)
        } catch (_: Exception) {
            return blocked()
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
        emptyMap(), ByteArray(0).inputStream())

    companion object {
        private const val PROFILE = "alphy-threejs"
        private const val BRIDGE = "AlphySceneHost"
        private const val SHELL_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; " +
            "worker-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        private const val WORKER_POLICY = "default-src 'none'; script-src 'self'; connect-src blob:; worker-src 'none'"
        private val localFiles = mapOf("/index.html" to "text/html", "/shell.css" to "text/css",
            "/shell.js" to "text/javascript", "/worker.js" to "text/javascript", "/addons.min.js" to "text/javascript",
            "/three.module.min.js" to "text/javascript", "/three.core.min.js" to "text/javascript")
        private val qualified = mapOf("145.0.7632.218" to 763221804L, "156.0.8078.47" to 807804703L)

        fun available(): Boolean {
            val provider = WebView.getCurrentWebViewPackage() ?: return false
            return provider.packageName == "com.google.android.webview" && qualified[provider.versionName] == provider.longVersionCode &&
                listOf(WebViewFeature.MULTI_PROFILE, WebViewFeature.DELETE_BROWSING_DATA,
                    WebViewFeature.WEB_MESSAGE_LISTENER).all(WebViewFeature::isFeatureSupported)
        }
    }
}

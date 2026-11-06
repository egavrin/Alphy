package com.alpha.assistant.web

import android.net.Uri
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun runWebProbe(
    probe: WebProbe,
    guarded: Boolean,
    address: InetAddress,
    attempt: JSONObject,
): WebObservation {
    val fixture = WebProbeFixture(probe, address)
    var browser: WebProbeBrowser? = null
    var outcome = "UNOBSERVED"
    var afterRevoke = "UNOBSERVED"
    attempt.put("origin", fixture.origin).put("sink", fixture.sink)
        .put("cleartextPermitted", NetworkSecurityPolicy.getInstance()
            .isCleartextTrafficPermitted(checkNotNull(address.hostAddress)))
        .put("startUrl", "${fixture.origin}/fixture")
        .put("script", probe.script())
        .put("expectedIfAllowed", JSONArray(probe.expected.map { it.json() }))
        .put("expectedIfGuarded", JSONArray(probe.expected.dropLast(1).map { it.json() }))
    if (probe == WebProbe.WORKER) attempt.put("workerScript", WORKER_SCRIPT)
    if (probe == WebProbe.REVOKE) {
        attempt.put("afterRevokeScript", AFTER_REVOKE_SCRIPT)
            .put("expectedNewRequestIfAllowed", Exchange("GET", "/after-revoke").json())
    }
    try {
        fixture.use {
            WebProbeBrowser(fixture, guarded && probe != WebProbe.REVOKE).use { active ->
                browser = active
                active.load()
                if (active.waitFor("document.title", setOf("READY")) == "READY") {
                    active.run(probe.script())
                    if (probe == WebProbe.REVOKE && fixture.heldRequest.await(5, TimeUnit.SECONDS)) {
                        if (guarded) {
                            attempt.put("revocation", JSONArray(listOf("stopLoading", "blockNetworkLoads=true")))
                            active.revoke()
                        }
                        fixture.releaseHeld.countDown()
                    }
                    outcome = active.waitFor("document.title", setOf("OK", "ERROR", "BLOCKED", "UNSUPPORTED"))
                    if (probe == WebProbe.REVOKE) {
                        active.run(AFTER_REVOKE_SCRIPT)
                        afterRevoke = active.waitFor("window.afterRevokeOutcome", setOf("OK", "ERROR", "BLOCKED"))
                    }
                }
            }
        }
    } finally {
        attempt.put("outcome", outcome)
            .put("afterRevokeOutcome", afterRevoke)
            .put("heldPeerClosedBeforeResponse", fixture.heldPeerClosed ?: JSONObject.NULL)
            .put("callbacks", JSONArray(browser?.callbacks.orEmpty().map { it.json() }))
            .put("endpointTraffic", JSONArray(fixture.traffic.map { it.json() }))
            .put("serverErrors", JSONArray(fixture.errors.toList()))
    }
    check(fixture.errors.isEmpty()) { fixture.errors.joinToString() }
    return WebObservation(
        fixture.traffic.filterNot { it.path in setOf("/fixture", "/favicon.ico") },
        browser?.callbacks.orEmpty().toList(),
        outcome,
        afterRevoke,
        fixture.heldPeerClosed,
    )
}

internal data class WebObservation(
    val traffic: List<Exchange>,
    val callbacks: List<Callback>,
    val outcome: String,
    val afterRevoke: String,
    val heldPeerClosed: Boolean?,
) {
    fun workerUnsupported(probe: WebProbe) = probe == WebProbe.WORKER && outcome == "UNSUPPORTED" && traffic.isEmpty()

    fun calibrated(probe: WebProbe): Boolean {
        val expected = if (probe == WebProbe.REVOKE) probe.expected + Exchange("GET", "/after-revoke") else probe.expected
        return outcome == "OK" && traffic == expected && (probe != WebProbe.REVOKE || afterRevoke == "OK")
    }

    fun coverage(probe: WebProbe): JSONArray = JSONArray(probe.expected.map { expected ->
        val classification = when {
            workerUnsupported(probe) -> "UNSUPPORTED"
            expected !in traffic -> "UNOBSERVED"
            callbacks.none { it.matches(expected) && it.decision == "ALLOW" } -> "BYPASS"
            !calibrated(probe) -> "UNOBSERVED"
            else -> "SUPPORTED"
        }
        expected.json().put("classification", classification)
    })

    fun guardVerdict(probe: WebProbe, allowed: WebObservation): JSONObject {
        val target = probe.expected.last()
        val reachedTarget = traffic.any { it.path == target.path && it.sink == target.sink }
        val reachedAfterRevoke = traffic.any { it.path == "/after-revoke" }
        val preTargetCompleted = traffic.filterNot { it.path == "/after-revoke" } == probe.expected.dropLast(1)
        val deniedByCallback = callbacks.any { it.matches(target) && it.decision == "DENY" }
        val terminalBlock = outcome in setOf("BLOCKED", "ERROR")
        val targetVerdict = when {
            reachedTarget -> "BYPASS"
            allowed.workerUnsupported(probe) -> "UNSUPPORTED"
            !allowed.calibrated(probe) || !preTargetCompleted || !terminalBlock -> "UNOBSERVED"
            deniedByCallback || probe == WebProbe.REVOKE -> "SUPPORTED"
            else -> "UNOBSERVED"
        }
        val verdict = target.json().put("classification", targetVerdict)
        if (probe == WebProbe.REVOKE) {
            verdict.put("newRequestAfterRevocation", when {
                reachedAfterRevoke -> "BYPASS"
                !allowed.calibrated(probe) || !preTargetCompleted -> "UNOBSERVED"
                afterRevoke in setOf("ERROR", "BLOCKED") -> "SUPPORTED"
                else -> "UNOBSERVED"
            }).put("activeConnectionClosed",
                if (allowed.calibrated(probe) && allowed.heldPeerClosed == false && heldPeerClosed == true) "SUPPORTED"
                else "UNOBSERVED")
        }
        return verdict
    }
}

private class WebProbeBrowser(private val fixture: WebProbeFixture, private val guard: Boolean) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val callbacks = ConcurrentLinkedQueue<Callback>()
    private lateinit var browser: WebView

    init {
        instrumentation.runOnMainSync {
            browser = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.safeBrowsingEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        observe("shouldOverrideUrlLoading", request)

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (observe("shouldInterceptRequest", request)) {
                            WebResourceResponse("text/html", "UTF-8", 403, "Fixture blocked", emptyMap(),
                                ByteArrayInputStream("<title>BLOCKED</title>".toByteArray()))
                        } else null

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        addCallback("onReceivedError", request, "ERROR_${error.errorCode}")
                    }
                }
            }
        }
    }

    fun load() = instrumentation.runOnMainSync { browser.loadUrl("${fixture.origin}/fixture") }

    fun run(script: String) = instrumentation.runOnMainSync { browser.evaluateJavascript(script, null) }

    fun revoke() = instrumentation.runOnMainSync {
        browser.stopLoading()
        browser.settings.blockNetworkLoads = true
    }

    fun waitFor(expression: String, values: Set<String>): String {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val complete = CountDownLatch(1)
            var value = ""
            instrumentation.runOnMainSync {
                browser.evaluateJavascript(expression) {
                    value = it?.removeSurrounding("\"").orEmpty()
                    complete.countDown()
                }
            }
            if (complete.await(1, TimeUnit.SECONDS) && value in values) return value
            if ("BLOCKED" in values && callbacks.any {
                    it.callback == "shouldOverrideUrlLoading" && it.mainFrame && it.decision == "DENY"
                }) return "BLOCKED"
            SystemClock.sleep(25)
        }
        return "UNOBSERVED"
    }

    override fun close() = instrumentation.runOnMainSync {
        browser.stopLoading()
        browser.destroy()
    }

    private fun observe(callback: String, request: WebResourceRequest): Boolean {
        val origin = fixture.findOrigin(request.url)
        val denied = origin == null || guard && request.url.path == "/target"
        addCallback(callback, request, if (denied) "DENY" else "ALLOW")
        return denied
    }

    private fun addCallback(callback: String, request: WebResourceRequest, decision: String) {
        val uri = request.url
        val origin = fixture.findOrigin(uri)
        val path = uri.encodedPath.orEmpty() + (uri.encodedQuery?.let { "?$it" } ?: "")
        callbacks += Callback(
            callback, request.method, fixture.safePath(path, origin), origin == fixture.sink,
            request.isForMainFrame, request.isRedirect, decision,
        )
    }
}

internal data class Callback(
    val callback: String,
    val method: String,
    val path: String,
    val sink: Boolean,
    val mainFrame: Boolean,
    val redirect: Boolean,
    val decision: String,
) {
    fun matches(exchange: Exchange) = method == exchange.method && path == exchange.path && sink == exchange.sink

    fun json() = JSONObject().put("callback", callback).put("method", method).put("path", path)
        .put("sink", sink).put("mainFrame", mainFrame).put("redirect", redirect).put("decision", decision)
}

private val AFTER_REVOKE_SCRIPT = """
    fetch('/after-revoke').then(response => window.afterRevokeOutcome = response.ok ? 'OK' : 'BLOCKED',
        () => window.afterRevokeOutcome = 'ERROR');
""".trimIndent()

package com.alpha.assistant.web

import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InteractiveWebFeasibilityTest {
    @Test
    fun compareCallbacksWithLoopbackTraffic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val webView = WebView.getCurrentWebViewPackage()
        val report = JSONObject()
            .put("testedRevision", InstrumentationRegistry.getArguments().getString("testedRevision", "not supplied"))
            .put("test", "${javaClass.name}#compareCallbacksWithLoopbackTraffic")
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("androidFingerprint", Build.FINGERPRINT)
            .put("targetSdk", context.applicationInfo.targetSdkVersion)
            .put("webViewPackage", webView?.packageName ?: JSONObject.NULL)
            .put("webViewVersion", webView?.versionName ?: JSONObject.NULL)
            .put("webViewVersionCode", webView?.longVersionCode ?: JSONObject.NULL)
            .put("requestBodyInWebResourceRequest", "UNSUPPORTED")
            .put("interactiveWebGate", "DENIED")
        val cases = JSONArray()
        val errors = JSONArray()
        report.put("cases", cases).put("fixtureErrors", errors)
        val artifact = File(
            checkNotNull(context.getExternalFilesDir(null)),
            "interactive-web-evidence/${UUID.randomUUID()}.json",
        )
        check(artifact.parentFile!!.isDirectory || artifact.parentFile!!.mkdirs())
        try {
            for (probe in WebProbe.entries) {
                val observation = JSONObject().put("name", probe.name)
                cases.put(observation)
                val allowed = observe(probe, guarded = false, observation, errors)
                val guarded = observe(probe, guarded = true, observation, errors)
                if (allowed != null && guarded != null) {
                    observation.put("allowedCoverage", allowed.coverage(probe))
                    observation.put("guardedBoundary", guarded.guardVerdict(probe, allowed))
                    if (!allowed.calibrated(probe) && !allowed.workerUnsupported(probe)) {
                        errors.put("${probe.name}: allowed fixture did not complete with the expected requests")
                    }
                }
            }
            assertTrue("Fixture errors: $errors. Evidence: $artifact", errors.length() == 0)
        } finally {
            artifact.writeText(report.toString(2) + "\n")
            instrumentation.sendStatus(0, Bundle().apply {
                putString("interactive_web_evidence", artifact.absolutePath)
                putString("interactive_web_gate", "DENIED")
            })
        }
    }

    private fun observe(
        probe: WebProbe,
        guarded: Boolean,
        observation: JSONObject,
        errors: JSONArray,
    ): WebObservation? {
        val attempt = JSONObject()
        observation.put(if (guarded) "guarded" else "allowed", attempt)
        // A fresh loopback host avoids the usual localhost origin in the default WebView profile.
        val seed = UUID.randomUUID().leastSignificantBits
        val address = InetAddress.getByAddress(byteArrayOf(127, (seed shr 16).toByte(), (seed shr 8).toByte(), seed.toByte()))
        return try {
            runWebProbe(probe, guarded, address, attempt)
        } catch (failure: Exception) {
            errors.put("${probe.name} guarded=$guarded: ${failure.javaClass.simpleName}: ${failure.message}")
            null
        }
    }
}

internal enum class WebProbe(val expected: List<Exchange>) {
    GET_FORM(listOf(Exchange("GET", "/target?fixture=get%2Bform"))),
    POST_FORM(listOf(Exchange("POST", "/target", "fixture=post+%2B+form"))),
    FETCH(listOf(Exchange("POST", "/target", "fetch=payload"))),
    XHR(listOf(Exchange("POST", "/target", "xhr=payload"))),
    REDIRECT(listOf(Exchange("GET", "/first"), Exchange("GET", "/middle"), Exchange("GET", "/target", sink = true))),
    WORKER(listOf(Exchange("GET", "/worker.js"), Exchange("GET", "/target"))),
    REVOKE(listOf(Exchange("POST", "/held", "revoke=payload"), Exchange("GET", "/target", sink = true)));

    fun script(): String = when (this) {
        GET_FORM -> form("get", "get+form")
        POST_FORM -> form("post", "post + form")
        FETCH -> fetch("'/target', {method: 'POST', body: 'fetch=payload'}")
        XHR -> """
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/target');
            xhr.onload = () => document.title = xhr.status === 200 ? 'OK' : 'BLOCKED';
            xhr.onerror = () => document.title = 'ERROR';
            xhr.send('xhr=payload');
        """.trimIndent()
        REDIRECT -> fetch("'/first'")
        WORKER -> """
            if (typeof Worker !== 'function') {
                document.title = 'UNSUPPORTED';
            } else {
                const worker = new Worker('/worker.js');
                worker.onmessage = ({data}) => { document.title = data; worker.terminate(); };
                worker.onerror = () => { document.title = 'ERROR'; worker.terminate(); };
            }
        """.trimIndent()
        REVOKE -> fetch("'/held', {method: 'POST', body: 'revoke=payload'}")
    }

    private fun form(method: String, value: String) = """
        document.body.innerHTML = '<form method="$method" action="/target">' +
            '<input name="fixture" value="$value"><button type="submit">Send</button></form>';
        document.querySelector('button').click();
    """.trimIndent()

    private fun fetch(arguments: String) = """
        fetch($arguments).then(response => document.title = response.ok ? 'OK' : 'BLOCKED',
            () => document.title = 'ERROR');
    """.trimIndent()
}

internal data class Exchange(val method: String, val path: String, val body: String = "", val sink: Boolean = false) {
    fun json() = JSONObject().put("method", method).put("path", path).put("body", body).put("sink", sink)
}

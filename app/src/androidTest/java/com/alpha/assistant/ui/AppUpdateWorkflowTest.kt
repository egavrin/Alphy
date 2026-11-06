package com.alpha.assistant.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.AssistantFixtureApplication
import com.alpha.assistant.MainActivity
import com.alpha.assistant.R
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class AppUpdateWorkflowTest {
    private val robot = ShellRobot("app-update")
    private val app = robot.instrumentation.targetContext.applicationContext as AssistantFixtureApplication
    private val apk = File(app.filesDir, "update-test/Alphy-v0.1.0-alpha.4.apk")

    @Test fun selectsNewestReleaseAndRejectsCorruptDownload() {
        val tag = "v0.1.0-alpha.10"
        val assetRequests = AtomicInteger()
        val checkRequests = AtomicInteger()
        val releases = JSONArray()
            .put(release("v0.1.0-alpha.20").put("draft", true))
            .put(release("v0.1.0-alpha.19", digest = "missing"))
            .put(release("v0.1.0-alpha.18", assetHost = "example.com"))
            .put(release("v0.1.0-alpha.3"))
            .put(release("v0.1.0-alpha.9"))
        val nextPage = JSONArray().put(release(tag))
        val client = fixtureClient(releases, checkRequests, assetRequests, nextPage) { "bad".toResponseBody() }
        withShell(client) { scenario ->
            openSettings(scenario)
            val available = robot.text(R.string.update_available, tag)
            if (robot.find(available) == null) robot.click(robot.text(R.string.update_check))
            robot.waitFor(available)
            assertEquals(0, assetRequests.get())

            val previousChecks = checkRequests.get()
            scenario.recreate()
            showUpdates()
            robot.waitFor(available)
            assertEquals(previousChecks, checkRequests.get())
            robot.click(robot.text(R.string.update_check))
            robot.waitFor(available)
            assertTrue(checkRequests.get() > previousChecks)

            robot.click(robot.text(R.string.update_download))
            robot.waitFor(robot.text(R.string.update_invalid_apk))
            assertEquals(1, assetRequests.get())
            assertEquals(null, robot.find(robot.text(R.string.update_install)))
            assertTrue(File(app.cacheDir, "app-updates").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun returnsFromInstallPermissionWithVerifiedUpdate() {
        assumeFalse("Set REQUEST_INSTALL_PACKAGES to default before this test", app.packageManager.canRequestPackageInstalls())
        withReadyUpdate { tag ->
            val allow = robot.text(R.string.update_allow_install)
            robot.click(allow)
            robot.waitUntil { robot.diagnostics.freshRoot()?.packageName == "com.android.settings" }
            robot.back()
            robot.waitFor(robot.text(R.string.update_ready, tag))
            robot.waitUntil {
                generateSequence(robot.find(allow)) { it.parent }.firstOrNull { it.isClickable }?.isEnabled == true
            }
            assertNull(robot.find(robot.text(R.string.update_install)))
        }
    }

    @Test fun opensAndroidInstallerWithVerifiedUpdate() {
        assumeTrue("Set REQUEST_INSTALL_PACKAGES to allow before this test", app.packageManager.canRequestPackageInstalls())
        withReadyUpdate { tag ->
            robot.click(robot.text(R.string.update_install))
            robot.waitUntil {
                robot.diagnostics.freshRoot()?.packageName?.contains("packageinstaller") == true
            }
            if (InstrumentationRegistry.getArguments().getString("holdInstaller") == "true") {
                robot.instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "\nVerified APK is open in Android installer.\n")
                })
                SystemClock.sleep(90_000)
            }
            robot.back()
            robot.waitFor(robot.text(R.string.update_ready, tag))
        }
    }

    private fun withReadyUpdate(run: (String) -> Unit) {
        assumeTrue("Stage the signed Alpha 4 APK in ${apk.path}", apk.isFile)
        val tag = "v0.1.0-alpha.4"
        val sha256 = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                sha256.update(buffer, 0, count)
            }
        }
        val digest = sha256.digest().joinToString("") { "%02x".format(it) }
        val releases = JSONArray().put(release(tag, digest, apk.length()))
        withShell(fixtureClient(releases) { apk.source().buffer().asResponseBody(contentLength = apk.length()) }) { scenario ->
            openSettings(scenario)
            val available = robot.text(R.string.update_available, tag)
            if (robot.find(available) == null) robot.click(robot.text(R.string.update_check))
            robot.waitFor(available)
            robot.click(robot.text(R.string.update_download))
            robot.waitFor(robot.text(R.string.update_ready, tag))
            run(tag)
        }
    }

    private fun withShell(client: OkHttpClient, run: (ActivityScenario<MainActivity>) -> Unit) {
        val fixture = ShellFixture("app-update")
        robot.instrumentation.runOnMainSync {
            app.installAssistantFixture {
                ShellViewModel(it, fixture.provider, fixture.store, fixture.keys, updateHttpClient = client)
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use(run)
        } finally {
            robot.instrumentation.runOnMainSync { app.removeAssistantFixture() }
            fixture.remove()
        }
    }

    private fun openSettings(scenario: ActivityScenario<MainActivity>) {
        robot.openDrawer(scenario)
        robot.click(robot.text(R.string.settings))
        robot.awaitDrawerClosed(scenario)
        robot.waitFor(robot.text(R.string.settings_deepseek))
        showUpdates()
    }

    private fun showUpdates() {
        val heading = robot.text(R.string.update_title)
        robot.waitUntil { robot.find(heading) != null || robot.scrollForward() }
        while (robot.scrollForward()) {
            // Keep the update actions in the viewport below the heading.
        }
        robot.waitFor(heading)
    }

    private fun release(
        tag: String,
        digest: String = "00".repeat(32),
        size: Long = 3,
        assetHost: String = "github.com",
    ): JSONObject {
        val name = "Alphy-$tag.apk"
        return JSONObject()
            .put("tag_name", tag)
            .put("draft", false)
            .put("prerelease", true)
            .put("body", "Update workflow fixture")
            .put("assets", JSONArray().put(JSONObject()
                .put("name", name)
                .put("state", "uploaded")
                .put("size", size)
                .put("digest", "sha256:$digest")
                .put("browser_download_url", "https://$assetHost/egavrin/Alphy/releases/download/$tag/$name")))
    }

    private fun fixtureClient(
        releases: JSONArray,
        checkRequests: AtomicInteger = AtomicInteger(),
        assetRequests: AtomicInteger = AtomicInteger(),
        nextPage: JSONArray? = null,
        asset: () -> ResponseBody,
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        val body = when (request.url.host) {
            "api.github.com" -> {
                checkRequests.incrementAndGet()
                if (request.url.queryParameter("page") == "2") {
                    checkNotNull(nextPage).toString().toResponseBody()
                } else {
                    if (nextPage != null) response.header(
                        "Link",
                        """<https://api.github.com/repos/egavrin/Alphy/releases?page=2>; rel="next" """.trim(),
                    )
                    releases.toString().toResponseBody()
                }
            }
            "github.com" -> {
                assetRequests.incrementAndGet()
                asset()
            }
            else -> error("Unexpected update host ${request.url.host}")
        }
        response.body(body).build()
    }).build()
}

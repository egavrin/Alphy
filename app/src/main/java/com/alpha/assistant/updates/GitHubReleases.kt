package com.alpha.assistant.updates

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppRelease(
    val tag: String,
    val notes: String,
    val size: Long,
    val digest: String,
    val url: HttpUrl,
) {
    val versionName: String get() = tag.removePrefix("v")
}

internal class InvalidUpdate : IOException()

internal class GitHubReleases(httpClient: OkHttpClient) {
    private val client = httpClient.newBuilder()
        .followSslRedirects(false)
        .build()

    suspend fun latestAfter(installedVersion: String): AppRelease? {
        var newestVersion = SemVer.parse(installedVersion) ?: throw IOException("Invalid installed version")
        var newest: AppRelease? = null
        var page = 1
        do {
            val request = Request.Builder()
                .url(releasesUrl.newBuilder().addQueryParameter("per_page", "100").addQueryParameter("page", page.toString()).build())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val hasNextPage = fetch(request, timeoutSeconds = 30) { response ->
                val releases = JSONArray(response.body.string())
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    val tag = release.optString("tag_name")
                    if (release.opt("draft") != false || !tag.startsWith("v")) continue
                    val version = SemVer.parse(tag.removePrefix("v")) ?: continue
                    if (version <= newestVersion) continue
                    val candidate = release.updateAsset(tag) ?: continue
                    newestVersion = version
                    newest = candidate
                }
                response.headers("Link").any { nextLink.containsMatchIn(it) }
            }
            page++
        } while (hasNextPage)
        return newest
    }

    suspend fun download(release: AppRelease, file: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(release.url).build()
        fetch(request, timeoutSeconds = 300) { response ->
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var percent = 0
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count > release.size - received) throw InvalidUpdate()
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        val progress = (received.toDouble() / release.size * 100).toInt()
                        if (progress != percent) {
                            percent = progress
                            onProgress(progress)
                        }
                    }
                }
            }
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
            if (received != release.size || !actualDigest.equals(release.digest, ignoreCase = true)) throw InvalidUpdate()
        }
    }

    private suspend fun <T> fetch(request: Request, timeoutSeconds: Long, read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val call = client.newCall(request)
                call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
                // Cancel the socket while execute() or a response-body read is blocked.
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                        ensureActive()
                        if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
                        read(response)
                    }
                } finally {
                    cancellation.cancel()
                }
            }
        }

    private fun JSONObject.updateAsset(tag: String): AppRelease? {
        val assets = optJSONArray("assets") ?: return null
        val name = "Alphy-$tag.apk"
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") != name || asset.optString("state") != "uploaded") continue
            val value = asset.opt("size")
            if (value !is Int && value !is Long) continue
            val size = (value as Number).toLong()
            val digest = digestPattern.matchEntire(asset.optString("digest"))?.groupValues?.get(1) ?: continue
            val url = asset.optString("browser_download_url").toHttpUrlOrNull() ?: continue
            val expected = downloadUrl.newBuilder().addPathSegment(tag).addPathSegment(name).build()
            if (size <= 0 || url != expected) continue
            return AppRelease(tag, optString("body").takeUnless { isNull("body") }.orEmpty(), size, digest, url)
        }
        return null
    }

    private companion object {
        val releasesUrl = "https://api.github.com/repos/egavrin/Alphy/releases".toHttpUrl()
        val downloadUrl = "https://github.com/egavrin/Alphy/releases/download".toHttpUrl()
        val digestPattern = Regex("sha256:([a-fA-F0-9]{64})")
        val nextLink = Regex(""";\s*rel="next"(?:\s*[,;]|\s*$)""")
    }
}

private class SemVer(private val numbers: List<BigInteger>, private val pre: List<String>) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        for (index in numbers.indices) {
            val comparison = numbers[index].compareTo(other.numbers[index])
            if (comparison != 0) return comparison
        }
        if (pre.isEmpty() || other.pre.isEmpty()) return pre.isEmpty().compareTo(other.pre.isEmpty())
        for ((left, right) in pre.zip(other.pre)) {
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        private val pattern = Regex(
            """(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*))?(?:\+[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)?""",
        )

        fun parse(value: String): SemVer? {
            val parts = pattern.matchEntire(value)?.groupValues ?: return null
            val pre = parts[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
            if (pre.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
            return SemVer(parts.subList(1, 4).map(String::toBigInteger), pre)
        }
    }
}

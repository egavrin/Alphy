package com.alpha.assistant

import android.os.Process
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Disk-based fixture for a host test that kills the app process during a transfer. */
internal fun updateRestartFixture(directory: File): OkHttpClient? {
    if (!File(directory, "restart-fixture").isFile) return null
    val tag = "v0.1.0-alpha.5"
    val name = "Alphy-$tag.apk"
    val bytes = File(directory, name).readBytes()
    val offset = bytes.size / 2
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val releases = JSONArray().put(JSONObject()
        .put("tag_name", tag).put("draft", false)
        .put("assets", JSONArray().put(JSONObject()
            .put("name", name).put("state", "uploaded").put("size", bytes.size)
            .put("digest", "sha256:$digest")
            .put("browser_download_url", "https://github.com/egavrin/Alphy/releases/download/$tag/$name"))))
        .toString()
    return OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        if (request.url.host == "api.github.com") return@addInterceptor response.body(releases.toResponseBody()).build()

        val range = request.header("Range")
        File(directory, "restart-requests.log").appendText("${Process.myPid()} ${range ?: "full"}\n")
        if (range == "bytes=$offset-") {
            response.code(206).header("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                .body(bytes.copyOfRange(offset, bytes.size).toResponseBody()).build()
        } else {
            check(range == null)
            val prefix = Buffer().write(bytes, 0, offset)
            val stalled = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!prefix.exhausted()) return prefix.read(sink, byteCount)
                    repeat(300) {
                        if (chain.call().isCanceled()) {
                            File(directory, "restart-stops.log").appendText("${Process.myPid()} cancelled\n")
                            throw IOException("Cancelled fixture response")
                        }
                        Thread.sleep(100)
                    }
                    throw IOException("Interrupted fixture response")
                }

                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }
            response.body(stalled.buffer().asResponseBody(contentLength = bytes.size.toLong())).build()
        }
    }.build()
}

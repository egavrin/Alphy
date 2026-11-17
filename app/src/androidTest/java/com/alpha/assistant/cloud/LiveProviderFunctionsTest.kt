package com.alpha.assistant.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.images.ImageAspect
import com.alpha.assistant.images.ImageIntent
import com.alpha.assistant.images.ImageModel
import com.alpha.assistant.images.imageProviders
import com.alpha.assistant.tools.liveChatModel
import com.alpha.assistant.tools.liveCheck
import com.alpha.assistant.voice.SAMPLE_RATE
import com.alpha.assistant.voice.audioResponse
import com.alpha.assistant.voice.cloudSpeechAudio
import com.alpha.assistant.voice.silentMp3
import com.alpha.assistant.voice.wav
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID

private const val TEST_KEY = "alpha-test-only"
private val ANTHROPIC = AiAccount(AiProvider.ANTHROPIC, TEST_KEY)

/** The first image model of [provider] in the app: the cheapest one that the app offers. */
private fun imageModel(provider: AiProvider) = ImageModel.entries.first { it.provider == provider }

/**
 * One live check of each provider function (plan Task 23): chat, search with sources, a photo question, image generation,
 * and the OpenRouter cloud voice. Voice input is in LiveVoiceInputTest. `run.py live-check` starts each paid method with the one-use account.
 * Each loopback test runs the body of its method against a canned reply: a good reply passes, and a wrong reply fails.
 * Failure cases: a request to a provider without the function, an answer without the expected word, a search without an
 * https source, a picture that does not decode, and audio of the wrong format or size or that the player cannot prepare.
 */
class LiveProviderFunctionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The paid check [method] on a provider with [capability]. [run] uses the [model] that the report names. */
    private fun live(method: String, capability: Capability, model: (AiAccount) -> String, run: (AiAccount, String) -> String) =
        liveCheck("LiveProviderFunctionsTest#$method",
            missing = { account -> "${account.provider.displayName} has no $capability.".takeIf { capability !in account.provider.profile.capabilities } },
            model = model) { account, chosen -> run(account, checkNotNull(chosen)) }

    @Test fun chatAnswers() = live("chatAnswers", Capability.CHAT, { it.liveChatModel() }) { account, model ->
        chat(account, Endpoints.PRODUCTION, model)
    }

    // DeepSeek search calls no model, so its report names a chat model that the search does not use.
    @Test fun searchCitesSources() = live("searchCitesSources", Capability.SEARCH, { it.liveChatModel() }) { account, model ->
        search(account, Endpoints.PRODUCTION, model)
    }

    @Test fun photoQuestion() = live("photoQuestion", Capability.VISION, { it.liveChatModel() }) { account, model ->
        photo(account, Endpoints.PRODUCTION, model)
    }

    @Test fun imageGenerates() = live("imageGenerates", Capability.IMAGES, { imageModel(it.provider).wire }) { account, _ ->
        image(account, Endpoints.PRODUCTION)
    }

    @Test fun cloudVoiceSpeaks() = live("cloudVoiceSpeaks", Capability.CLOUD_VOICE, { it.provider.profile.speechModels.first().wire }) { account, _ ->
        voice(account, Endpoints.PRODUCTION)
    }

    /** The answer of one plain chat request, without tools or search, so the check makes one provider call. */
    private fun answer(account: AiAccount, endpoints: Endpoints, model: String, turn: CloudTurn): String = runBlocking {
        var answer: String? = null
        CloudChatProvider({ account }, endpoints, chatModel = { model })
            .respond(account.key, CloudRequest(listOf(turn), search = false), CloudSession()) { event ->
                if (event is CloudEvent.RoundComplete && !event.hasToolCalls) answer = event.text
            }
        checkNotNull(answer) { "The provider gave no complete answer." }
    }

    private fun chat(account: AiAccount, endpoints: Endpoints, model: String): String =
        answer(account, endpoints, model, CloudTurn("user", "Ответь одним словом: столица Франции?")).also {
            assertTrue("The answer names neither «Париж» nor Paris: $it", it.lowercase().let { text -> "париж" in text || "paris" in text })
        }

    private fun search(account: AiAccount, endpoints: Endpoints, model: String): String {
        val sources = runBlocking {
            CloudChatProvider({ account }, endpoints, chatModel = { model }).search(account.key, "столица Франции", CloudSession())
        }
        val secure = sources.map { it.url }.filter { it.startsWith("https://") }
        assertTrue("No source has an https URL: ${sources.map { it.url }}", secure.isNotEmpty())
        return "${sources.size} sources: ${secure.joinToString(" ")}"
    }

    /** A white picture with one large red circle goes as the chat sends an attached photo: a data URL in the user turn. */
    private fun photo(account: AiAccount, endpoints: Endpoints, model: String): String {
        val picture = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val image = try {
            Canvas(picture).apply { drawColor(Color.WHITE); drawCircle(256f, 256f, 192f, Paint().apply { color = Color.RED }) }
            val png = ByteArrayOutputStream().also { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            ImageInput.forSending(png, "image/png").let { (sent, mime) -> "data:$mime;base64," + Base64.encodeToString(sent, Base64.NO_WRAP) }
        } finally { picture.recycle() }
        return answer(account, endpoints, model, CloudTurn("user", "Какого цвета круг? Ответь одним словом.", listOf(image))).also {
            val words = it.lowercase().split(Regex("[^\\p{L}]+"))
            assertTrue("The answer names no red: $it", "red" in words || words.any { word -> word.startsWith("красн") })
        }
    }

    /** One square image through the image provider of the app. ImageGeneration itself reads the key from the key store. */
    private fun image(account: AiAccount, endpoints: Endpoints): String {
        val output = runBlocking {
            imageProviders(endpoints).getValue(account.provider.id).generate(account.key, imageModel(account.provider),
                ImageIntent("check", "A red apple on a white background", "A red apple", ImageAspect.SQUARE))
        }
        val bitmap = BitmapFactory.decodeByteArray(output.bytes, 0, output.bytes.size)
            ?: throw AssertionError("The image does not decode: ${output.bytes.size} bytes.")
        try {
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            return "${bitmap.width}x${bitmap.height}, ${output.bytes.size} bytes"
        } finally { bitmap.recycle() }
    }

    /**
     * One short Russian sentence through the cloud speech request that CloudSpeech plays in the app. The MP3 audio must prepare
     * in a MediaPlayer, as the app plays it. Only OpenRouter has a cloud voice (user decision 2026-11-17).
     */
    private fun voice(account: AiAccount, endpoints: Endpoints): String {
        val audio = runBlocking {
            cloudSpeechAudio(account, endpoints, account.provider.profile.speechModels.first(), "Поставь таймер на пять минут.")
        }
        assertTrue("The audio has ${audio.size} bytes.", audio.size > 1024)
        val id3 = String(audio, 0, 3, Charsets.US_ASCII) == "ID3"
        val sync = audio[0] == 0xFF.toByte() && (audio[1].toInt() and 0xE0) == 0xE0
        assertTrue("The audio is not MP3.", id3 || sync)
        // The app plays the audio from a file, so the check prepares it from a file that this test owns.
        val file = File(context.cacheDir, "live-voice-${UUID.randomUUID()}.mp3")
        val player = MediaPlayer()
        try {
            file.writeBytes(audio)
            player.setDataSource(file.path)
            player.prepare()
            assertTrue("The player gives a duration of ${player.duration} ms.", player.duration > 0)
            return "mp3, ${audio.size} bytes, ${player.duration} ms"
        } finally {
            player.release()
            file.delete()
        }
    }

    /** Runs [body] against a loopback server that gives the canned [reply]. It returns the outcome and the requests. */
    private fun served(reply: (LoopbackRequest, OutputStream) -> Unit, body: (Endpoints) -> String): Pair<Result<String>, List<LoopbackRequest>> =
        LoopbackServer(reply).use { server -> runCatching { body(Endpoints { server.url }) } to server.requests.toList() }

    /** A wrong reply fails the [check] of the method that starts with this text, not the request or a different check. */
    private fun Result<String>.assertRejected(check: String) {
        val failure = exceptionOrNull()
        assertTrue("$this", failure is AssertionError && failure.message.orEmpty().startsWith(check))
    }

    private fun JSONObject.lastMessageParts(): List<JSONObject> {
        val messages = getJSONArray("messages")
        val content = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
        return List(content.length(), content::getJSONObject)
    }

    @Test fun chatAnswersOnLoopback() {
        val (answer, requests) = served({ _, output -> output.anthropicText("Париж.") }) { chat(ANTHROPIC, it, "claude-sonnet-5-5") }
        assertEquals("Париж.", answer.getOrThrow())
        assertEquals(listOf("/v1/messages"), requests.map { it.path })
        assertEquals("claude-sonnet-5-5", JSONObject(requests.single().body).getString("model"))
        // An English answer is also correct, so it does not cost a repeated paid run.
        assertEquals("Paris.", served({ _, output -> output.anthropicText("Paris.") }) { chat(ANTHROPIC, it, "claude-sonnet-5-5") }.first.getOrThrow())
        served({ _, output -> output.anthropicText("Лондон.") }) { chat(ANTHROPIC, it, "claude-sonnet-5-5") }.first
            .assertRejected("The answer names neither «Париж» nor Paris")
    }

    @Test fun searchCitesSourcesOnLoopback() {
        val deepSeek = AiAccount(AiProvider.DEEPSEEK, TEST_KEY)
        fun results(url: String): (LoopbackRequest, OutputStream) -> Unit = { _, output ->
            output.jsonResponse(JSONObject().put("results", JSONArray().put(JSONObject().put("url", url)
                .put("title", "Париж").put("content", "Париж — столица Франции."))))
        }
        val (sources, requests) = served(results("https://example.org/paris")) { search(deepSeek, it, "deepseek-flash") }
        assertTrue(sources.getOrThrow(), "https://example.org/paris" in sources.getOrThrow())
        assertEquals(listOf("/v1/tools/search"), requests.map { it.path })
        served(results("http://example.org/paris")) { search(deepSeek, it, "deepseek-flash") }.first.assertRejected("No source has an https URL")
    }

    @Test fun photoQuestionOnLoopback() {
        val (answer, requests) = served({ _, output -> output.anthropicText("Красный.") }) { photo(ANTHROPIC, it, "claude-sonnet-5-5") }
        assertEquals("Красный.", answer.getOrThrow())
        assertEquals(listOf("/v1/messages"), requests.map { it.path })
        // The picture goes as the chat sends an attached photo: an image block of the user turn, next to the question.
        val parts = JSONObject(requests.single().body).lastMessageParts()
        assertEquals(listOf("text", "image"), parts.map { it.getString("type") })
        assertEquals("image/png", parts[1].getJSONObject("source").getString("media_type"))
        assertEquals("Red.", served({ _, output -> output.anthropicText("Red.") }) { photo(ANTHROPIC, it, "claude-sonnet-5-5") }.first.getOrThrow())
        // "red" inside a different word is not the answer.
        served({ _, output -> output.anthropicText("It is considered blue.") }) { photo(ANTHROPIC, it, "claude-sonnet-5-5") }.first
            .assertRejected("The answer names no red")
    }

    @Test fun imageGeneratesOnLoopback() {
        val openRouter = AiAccount(AiProvider.OPENROUTER, TEST_KEY)
        val png = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        }
        fun picture(bytes: ByteArray): (LoopbackRequest, OutputStream) -> Unit = { _, output ->
            output.jsonResponse(JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", Base64.encodeToString(bytes, Base64.NO_WRAP)))))
        }
        val (size, requests) = served(picture(png)) { image(openRouter, it) }
        assertTrue(size.getOrThrow(), size.getOrThrow().startsWith("8x8"))
        assertEquals(listOf("/api/v1/images"), requests.map { it.path })
        // The first OpenRouter model of the app.
        assertEquals(ImageModel.QWEN.wire, JSONObject(requests.single().body).getString("model"))
        served(picture("not an image".toByteArray())) { image(openRouter, it) }.first.assertRejected("The image does not decode")
    }

    @Test fun openRouterVoiceSpeaksMp3OnLoopback() {
        val openRouter = AiAccount(AiProvider.OPENROUTER, TEST_KEY)
        val (result, requests) = served({ _, output -> output.audioResponse("audio/mpeg", silentMp3(40)) }) { voice(openRouter, it) }
        assertTrue(result.getOrThrow(), result.getOrThrow().startsWith("mp3"))
        assertEquals(listOf("/api/v1/audio/speech"), requests.map { it.path })
        served({ _, output -> output.audioResponse("audio/mpeg", wav(ShortArray(SAMPLE_RATE))) }) { voice(openRouter, it) }.first
            .assertRejected("The audio is not MP3.")
    }
}

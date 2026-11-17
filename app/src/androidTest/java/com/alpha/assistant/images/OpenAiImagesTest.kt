package com.alpha.assistant.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.CloudFailure
import com.alpha.assistant.cloud.Endpoints
import com.alpha.assistant.cloud.LoopbackServer
import com.alpha.assistant.cloud.jsonResponse
import com.alpha.assistant.data.SecureKeyStore
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 11: image generation through the account of the user. */
@RunWith(AndroidJUnit4::class)
class OpenAiImagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "openai-images-${UUID.randomUUID()}"
    private val keys = SecureKeyStore(context, name, name)
    private val settings = ImageSettings(context, keys, name)
    private val key = "alpha-test-only"
    private val intent = ImageIntent("hero", "A planet", "A planet", ImageAspect.SQUARE)

    @After fun remove() {
        keys.clear()
        context.deleteSharedPreferences(name)
    }

    private fun saveAccount(provider: AiProvider) = check(keys.save(key, provider).isSuccess)

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { return ByteArrayOutputStream().also { check(bitmap.compress(format, 95, it)) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    private fun size(bytes: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }.let { it.outWidth to it.outHeight }

    private fun reply(bytes: ByteArray) = JSONObject().put("created", 1)
        .put("data", JSONArray().put(JSONObject().put("b64_json", Base64.encodeToString(bytes, Base64.NO_WRAP))))

    @Test fun openAiImagesRequestShape() = runBlocking<Unit> {
        val png = image(16, 16)
        LoopbackServer { _, output -> output.jsonResponse(reply(png)) }.use { server ->
            val result = OpenAiImages(Endpoints { server.url }).generate(key, ImageModel.GPT_IMAGE_FLARE, intent)
            assertTrue(server.failures.toString(), server.failures.isEmpty())
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/images/generations", request.path)
            assertEquals("Bearer $key", request.headers["authorization"])
            val body = JSONObject(request.body)
            assertEquals(setOf("model", "prompt", "size", "n"), body.keys().asSequence().toSet())
            assertEquals("gpt-image-2.6-flare", body.getString("model"))
            assertEquals("A planet", body.getString("prompt"))
            assertEquals("1024x1024", body.getString("size"))
            assertEquals(1, body.getInt("n"))
            assertArrayEquals(png, result.bytes)
        }
    }

    @Test fun anthropicAndDeepSeekAccountsHaveNoImages() {
        for (provider in listOf(AiProvider.ANTHROPIC, AiProvider.DEEPSEEK)) {
            saveAccount(provider)
            assertFalse(settings.available)
            assertTrue(settings.models(provider).isEmpty())
            assertTrue(settings.setEnabled(true).isFailure)
            assertNull(settings.selection())
        }
    }

    @Test fun providerChangeInvalidatesSelection() {
        saveAccount(AiProvider.OPENROUTER)
        settings.setEnabled(true).getOrThrow()
        val selection = checkNotNull(settings.selection())
        assertTrue(settings.isCurrent(selection))
        saveAccount(AiProvider.OPENAI)
        assertFalse(settings.isCurrent(selection))
        assertTrue(runCatching { settings.key(selection) }.isFailure)
    }

    @Test fun modelFallsBackToProviderDefault() {
        saveAccount(AiProvider.OPENAI)
        settings.select(ImageModel.SEEDREAM)
        assertEquals(ImageModel.GPT_IMAGE_FLARE, settings.model)
        assertEquals(listOf(ImageModel.GPT_IMAGE_FLARE, ImageModel.GPT_IMAGE_SUNBURST), settings.models(AiProvider.OPENAI))
        settings.select(ImageModel.GPT_IMAGE_SUNBURST)
        assertEquals(ImageModel.GPT_IMAGE_SUNBURST, settings.model)
        saveAccount(AiProvider.OPENROUTER)
        assertEquals(listOf(ImageModel.QWEN, ImageModel.SEEDREAM), settings.models(AiProvider.OPENROUTER))
        assertEquals(ImageModel.QWEN, settings.model)
    }

    @Test fun equirectangularIsCroppedToTwoToOne() = runBlocking<Unit> {
        saveAccount(AiProvider.OPENAI)
        settings.setEnabled(true).getOrThrow()
        val intent = intent.copy(aspect = ImageAspect.EQUIRECTANGULAR)
        LoopbackServer { _, output -> output.jsonResponse(reply(image(1536, 1024))) }.use { server ->
            val service = ImageGeneration(context, settings, imageProviders(Endpoints { server.url }))
            service.lease().use {
                val result = service.generate(name, listOf(intent), checkNotNull(settings.selection()), 1).single()
                assertEquals(1536 to 768, result.width to result.height)
                assertEquals("openai", result.provider)
                assertEquals("1536x1024", JSONObject(server.requests.single().body).getString("size"))
            }
        }
    }

    @Test fun cropKeepsTheFormatAndTheCenter() {
        val wide = image(1536, 1024)
        assertArrayEquals(wide, cropToAspect(wide, ImageAspect.LANDSCAPE))
        assertEquals(1536 to 768, size(cropToAspect(wide, ImageAspect.EQUIRECTANGULAR)))
        assertEquals(1024 to 1024, size(cropToAspect(wide, ImageAspect.SQUARE)))
        val jpeg = cropToAspect(image(300, 200, Bitmap.CompressFormat.JPEG), ImageAspect.EQUIRECTANGULAR)
        assertEquals("image/jpeg", BitmapFactory.Options().also { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, it.apply { inJustDecodeBounds = true }) }.outMimeType)
        val garbage = byteArrayOf(1, 2, 3)
        assertArrayEquals(garbage, cropToAspect(garbage, ImageAspect.SQUARE))
    }

    @Test fun oversizedOffRatioImageIsRejectedUncropped() = runBlocking<Unit> {
        saveAccount(AiProvider.OPENAI)
        settings.setEnabled(true).getOrThrow()
        val huge = image(5000, 100)
        assertArrayEquals(huge, cropToAspect(huge, ImageAspect.EQUIRECTANGULAR))
        LoopbackServer { _, output -> output.jsonResponse(reply(huge)) }.use { server ->
            val service = ImageGeneration(context, settings, imageProviders(Endpoints { server.url }))
            service.lease().use {
                val failure = runCatching {
                    service.generate("$name-huge", listOf(intent.copy(aspect = ImageAspect.EQUIRECTANGULAR)), checkNotNull(settings.selection()), 1)
                }.exceptionOrNull()
                assertEquals(context.getString(com.alpha.assistant.R.string.image_invalid), (failure as ImageFailure).message)
            }
        }
    }

    @Test fun moderationBlockedIsRefusal() = runBlocking<Unit> {
        val body = """{"error":{"type":"image_generation_user_error","code":"moderation_blocked"}}"""
        LoopbackServer { _, output ->
            output.write("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$body".toByteArray())
            output.flush()
        }.use { server ->
            val failure = runCatching { OpenAiImages(Endpoints { server.url }).generate(key, ImageModel.GPT_IMAGE_FLARE, intent) }.exceptionOrNull()
            assertEquals("refusal", (failure as CloudFailure).code)
        }
    }

    @Test fun cancelDisconnectsTheRequest() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        LoopbackServer { _, _ -> entered.complete(Unit); Thread.sleep(8_000) }.use { server ->
            // A supervisor keeps the disconnect failure of the child away from the test.
            kotlinx.coroutines.supervisorScope {
                val call = async(kotlinx.coroutines.Dispatchers.IO) {
                    OpenAiImages(Endpoints { server.url }).generate(key, ImageModel.GPT_IMAGE_FLARE, intent)
                }
                entered.await()
                val started = System.nanoTime()
                call.cancel()
                // The disconnect ends the blocking read before the 8 second reply.
                call.join()
                assertTrue((System.nanoTime() - started) < 4_000_000_000L)
            }
        }
    }
}

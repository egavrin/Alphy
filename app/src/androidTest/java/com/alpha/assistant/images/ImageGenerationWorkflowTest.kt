package com.alpha.assistant.images

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageGenerationWorkflowTest {
    @Test fun consentLimitsDuplicateSubmissionAndRevocation() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "image-workflow-${UUID.randomUUID()}"
        val settings = ImageSettings(context, prefix)
        val count = AtomicInteger()
        val usedModel = java.util.concurrent.atomic.AtomicReference<ImageModel>()
        val entered = CompletableDeferred<Unit>()
        val service = ImageGeneration(context, settings, mapOf("openrouter" to ImageProvider { _, model, intent ->
            count.incrementAndGet()
            usedModel.set(model)
            if (intent.prompt == "Oversized") return@ImageProvider ProviderImage(ByteArray(10_485_761), "image/png")
            if (intent.prompt == "Wait") {
                entered.complete(Unit)
                awaitCancellation()
            }
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) }
            finally { bitmap.recycle() }
            ProviderImage(bytes.toByteArray(), if (intent.prompt == "Invalid") "image/jpeg" else "image/png")
        }))
        try {
            settings.saveKey("fixture-only").getOrThrow()
            assertNull(settings.selection())
            assertEquals(0, count.get())
            settings.setEnabled(true).getOrThrow()
            val selection = checkNotNull(settings.selection())
            val image = ImageIntent("hero", "A planet", "A planet", ImageAspect.SQUARE)
            settings.select(ImageModel.SEEDREAM)
            service.lease().use {
                val result = service.generate(prefix, listOf(image), selection, 1).single()
                assertEquals(16, result.width)
                assertEquals("image/png", result.asset.mime)
                assertEquals(ImageModel.QWEN, usedModel.get())
                assertEquals(ImageModel.SEEDREAM, settings.selection()?.model)
                assertTrue(runCatching { service.generate(prefix, listOf(image), selection, 1) }.isFailure)
                assertTrue(runCatching { service.generate(prefix, listOf(image.copy(slot = "renamed")), selection, 1) }.isFailure)
                assertEquals(1, count.get())
                assertTrue(runCatching {
                    service.generate("$prefix-limit", List(5) { image.copy(slot = "slot$it") }, selection, 4)
                }.isFailure)
                assertEquals(1, count.get())
                assertTrue(runCatching {
                    service.generate("$prefix-invalid", listOf(image.copy(prompt = "Invalid")), selection, 1)
                }.isFailure)
                assertTrue(runCatching {
                    service.generate("$prefix-oversize", listOf(image.copy(prompt = "Oversized")), selection, 1)
                }.exceptionOrNull() is ImageFailure)
                val waiting = async {
                    runCatching { service.generate("$prefix-cancel", listOf(image.copy(prompt = "Wait")), selection, 1) }
                }
                entered.await()
                settings.setEnabled(false).getOrThrow()
                assertTrue(waiting.await().isFailure)
                assertNull(settings.selection())
                assertFalse(settings.isCurrent(selection))
                val calls = count.get()
                assertTrue(runCatching { service.generate("$prefix-opt-out", listOf(image), selection, 1) }.isFailure)
                assertEquals(calls, count.get())
            }
        } finally {
            settings.clearKey()
        }
    }
}

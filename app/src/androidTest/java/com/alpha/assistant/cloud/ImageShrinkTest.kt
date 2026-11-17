package com.alpha.assistant.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.tools.agent.AppSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageShrinkTest {
    private fun encode(width: Int, height: Int, format: Bitmap.CompressFormat, config: Bitmap.Config = Bitmap.Config.ARGB_8888, fill: Int = Color.RED): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, config)
        bitmap.eraseColor(fill)
        val out = ByteArrayOutputStream()
        bitmap.compress(format, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
    private fun jpeg(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888) = encode(width, height, Bitmap.CompressFormat.JPEG, config)
    private fun png(width: Int, height: Int) = encode(width, height, Bitmap.CompressFormat.PNG)
    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test fun shrinksLargeJpeg() {
        val (bytes, mime) = ImageInput.forSending(jpeg(4000, 3000), "image/jpeg")
        assertEquals("image/jpeg", mime); assertEquals(2048 to 1536, bounds(bytes))
    }
    @Test fun keepsPngAsPng() {
        val (bytes, mime) = ImageInput.forSending(png(3000, 1000), "image/png")
        assertEquals("image/png", mime); assertEquals(2048 to 683, bounds(bytes))
    }
    @Test fun smallImageKeepsBytes() {
        val input = png(1000, 800)
        val (bytes, mime) = ImageInput.forSending(input, "image/png")
        assertArrayEquals(input, bytes); assertEquals("image/png", mime)
    }
    @Test fun fiftyMegapixelPhotoFitsOpenAiPatchLimit() {
        val (bytes, _) = ImageInput.forSending(jpeg(8160, 6120, Bitmap.Config.RGB_565), "image/jpeg")
        val (width, height) = bounds(bytes)
        assertEquals(2048, maxOf(width, height))
        assertTrue(((width + 31) / 32) * ((height + 31) / 32) < 30_000)
    }
    @Test fun webpBecomesJpegOnWhiteBackground() {
        val webp = encode(3000, 1000, Bitmap.CompressFormat.WEBP_LOSSLESS, fill = Color.TRANSPARENT)
        val (bytes, mime) = ImageInput.forSending(webp, "image/webp")
        assertEquals("image/jpeg", mime)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val pixel = decoded.getPixel(10, 10); decoded.recycle()
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }
    @Test fun rotatedJpegIsUprightAfterShrinking() {
        val file = File.createTempFile("rotated", ".jpg", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(jpeg(4000, 3000))
            ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
            val (bytes, mime) = ImageInput.forSending(file.readBytes(), "image/jpeg")
            assertEquals("image/jpeg", mime); assertEquals(1536 to 2048, bounds(bytes))
        } finally { file.delete() }
    }
    @Test fun wideAppScreenshotIsShrunk() {
        val bitmap = Bitmap.createBitmap(2560, 1050, Bitmap.Config.ARGB_8888)
        val url = try { AppSnapshot.dataUrl(bitmap) } finally { bitmap.recycle() }
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        val bytes = android.util.Base64.decode(url.substringAfter(','), android.util.Base64.DEFAULT)
        assertEquals(2048 to 840, bounds(bytes))
    }
    @Test fun undecodableLargeBytesFail() {
        val broken = ByteArray(5000) { 7 }
        val failure = runCatching { ImageInput.forSending(broken, "image/jpeg") }.exceptionOrNull() as CloudFailure
        assertEquals("image_format", failure.code)
    }
}

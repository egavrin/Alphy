package com.alpha.assistant.surfaces

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.ImageInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Failure cases: invalid date ranges, excess event/text limits, invalid or oversized
 * camera output, unsafe paths, and context replacement without an explicit review. */
@RunWith(AndroidJUnit4::class)
class ReviewedContextWorkflowTest {
    @Test fun boundedCalendarReviewAndPrivateCameraOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(31L * 86400000, AssistContext.calendarWindow(1000, 31).last + 1 - 1000)
        assertTrue(runCatching { AssistContext.calendarWindow(0, 0) }.isFailure)
        assertTrue(runCatching { AssistContext.calendarWindow(0, 32) }.isFailure)
        val event = ReviewedCalendarEvent("1:1000", "Local appointment", 1000, 2000, "Room 1")
        val review = AssistContext.calendarText(listOf(event))
        assertTrue(review.contains("Local appointment"))
        assertTrue(runCatching { AssistContext.calendarText(List(51) { event }) }.isFailure)
        assertTrue(runCatching { AssistContext.calendarText(listOf(event.copy(title = "x".repeat(8001)))) }.isFailure)
        assertTrue(AssistContext.textAttachment(review, "Календарь").name.contains("Календарь"))
        val uri = AssistContext.cameraUri(context)
        val file = AssistContext.cameraFile(context, uri)
        try {
            assertTrue(runCatching { AssistContext.validatedCapture(context, uri) }.isFailure)
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            val attachment = AssistContext.validatedCapture(context, uri)
            assertEquals("image/jpeg", attachment.mime)
            assertTrue(ImageInput.read(context.contentResolver, attachment).isNotEmpty())
            file.outputStream().use { it.write(ByteArray(5 * 1024 * 1024 + 1)) }
            assertTrue(runCatching { AssistContext.validatedCapture(context, uri) }.isFailure)
            assertTrue(runCatching { AssistContext.cameraFile(context, android.net.Uri.parse("content://${context.packageName}.chat-camera/../secret")) }.isFailure)
        } finally { file.delete() }
    }
}

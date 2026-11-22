package com.alpha.assistant.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan "one scene", Task 9: the mascot shows no cream square on a dark surface. */
class MascotPictureTest {
    @Test fun theMascotHasATransparentBackground() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val picture = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.alphy_welcome, BitmapFactory.Options().apply { inScaled = false }))
        assertEquals(320, picture.width)
        assertEquals(320, picture.height)
        val (right, bottom) = picture.width - 1 to picture.height - 1
        for ((x, y) in listOf(0 to 0, right to 0, 0 to bottom, right to bottom)) assertEquals("alpha at $x,$y", 0, Color.alpha(picture.getPixel(x, y)))
        assertEquals("alpha at the center", 255, Color.alpha(picture.getPixel(picture.width / 2, picture.height / 2)))
    }
}

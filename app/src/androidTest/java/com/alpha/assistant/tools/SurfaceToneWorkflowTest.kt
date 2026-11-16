package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Failure case: a drawn progress track in tone muted used the dark text ink, so a track at 0% looked full. */
@RunWith(AndroidJUnit4::class)
class SurfaceToneWorkflowTest {
    private val ink = Color.rgb(73, 69, 79)
    private val accent = Color.rgb(103, 80, 164)

    private fun draw(commands: String): Bitmap = Bitmap.createBitmap(100, 20, Bitmap.Config.ARGB_8888).also { target ->
        Canvas(target).apply {
            drawColor(Color.WHITE)
            drawSurfaceFrame(this, ToolSurface("track", 100, 20), JSONArray(commands), 100f, 20f,
                mapOf("muted" to ink, "accent" to accent))
        }
    }

    private fun luminance(color: Int) = ColorUtils.calculateLuminance(color)

    @Test fun aMutedShapeIsALightTrackAndMutedTextKeepsItsInk() {
        val frame = draw("""[{"type":"roundRect","x":0,"y":5,"width":100,"height":10,"radius":5,"color":"muted"},
            {"type":"rect","x":0,"y":5,"width":30,"height":10,"color":"accent"}]""")
        try {
            val track = frame.getPixel(70, 10)
            val fill = frame.getPixel(15, 10)
            assertTrue("track ${Integer.toHexString(track)}", luminance(track) > 0.6)
            assertTrue("fill ${Integer.toHexString(fill)}", luminance(fill) < 0.3)
        } finally { frame.recycle() }
        val text = draw("""[{"type":"text","text":"MMMM","x":2,"y":18,"size":18,"color":"muted"}]""")
        try {
            val darkest = (0 until 100).flatMap { x -> (0 until 20).map { y -> luminance(text.getPixel(x, y)) } }.min()
            assertTrue("darkest text pixel $darkest", darkest < 0.2)
        } finally { text.recycle() }
    }
}

package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Failure case: drawn text started at x on its baseline, and nothing else was possible. A 2048 game put each number
 * in the corner of its tile, and a moles game cut its centered message at the right edge.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceTextAlignWorkflowTest {
    private fun commands(json: String) = GraphicsContract.frame("{\"commands\":$json}", "frame").getJSONArray("commands")

    private fun draw(json: String): Bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).also { target ->
        Canvas(target).apply {
            drawColor(Color.WHITE)
            drawSurfaceFrame(this, ToolSurface("main", 200, 100), commands(json), 200f, 100f, emptyMap())
        }
    }

    /** The box of the pixels darker than mid gray. */
    private fun ink(bitmap: Bitmap): Rect {
        val box = Rect(bitmap.width, bitmap.height, -1, -1)
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) if (Color.red(bitmap.getPixel(x, y)) < 128) box.union(x, y)
        bitmap.recycle()
        return box
    }

    @Test fun textCentersAndEndsOnItsPoint() {
        val centered = ink(draw("""[{"type":"text","text":"2048","x":100,"y":50,"size":32,"color":"#000000","align":"center","baseline":"middle"}]"""))
        assertTrue("ink $centered is centered on x 100", abs(centered.exactCenterX() - 100) <= 2)
        assertTrue("ink $centered is centered on y 50", abs(centered.exactCenterY() - 50) <= 3)
        val ended = ink(draw("""[{"type":"text","text":"12 s","x":190,"y":10,"size":20,"color":"#000000","align":"end","baseline":"top"}]"""))
        assertTrue("ink $ended ends at x 190", abs(ended.right - 190) <= 2)
        assertTrue("ink $ended starts below y 10", ended.top in 10..17)
    }

    @Test fun unalignedTextDrawsAsBefore() {
        val drawn = draw("""[{"type":"text","text":"Score 7","x":12,"y":60,"size":24,"color":"#000000"}]""")
        val reference = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawText("Score 7", 12f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }) }
        }
        try { assertTrue("the default anchor is the left end of the baseline", drawn.sameAs(reference)) } finally { drawn.recycle(); reference.recycle() }
    }

    @Test fun alignAndBaselineTakeOnlyTheirValues() {
        val text = """{"type":"text","text":"7","x":10,"y":10,"size":12,"color":"#000000""""
        commands("""[$text,"align":"start","baseline":"alphabetic"}]""")
        for (wrong in listOf(""""align":"middle"""", """"baseline":"center"""", """"align":3"""))
            assertTrue(wrong, runCatching { commands("""[$text,$wrong}]""") }.isFailure)
    }
}

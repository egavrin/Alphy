package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * The smooth change of a template scene (block 2 plan, M5, M6, and C1) with a clock of the test: a press glides in 250 ms with an ease-out,
 * a motion step glides linearly over one step, the last frame is the new drawing, and a second change starts from the picture that shows.
 */
class SurfaceGlideTest {
    private val ms = 1_000_000L
    private fun circle(x: Double, color: String = "accent") = JSONObject().put("type", "circle").put("x", x).put("y", 50).put("radius", 10).put("color", color)
    private fun text(value: String) = JSONObject().put("type", "text").put("x", 10).put("y", 10).put("size", 12).put("text", value).put("color", "ink")
    private fun drawing(vararg commands: JSONObject) = JSONArray(commands.toList())
    /** The x of the first command of the only layer of [layers]. */
    private fun x(layers: List<GlideLayer>): Double { assertEquals(1, layers.size); return layers[0].commands.getJSONObject(0).getDouble("x") }

    @Test fun aPressGlidesEachNumberWithAnEaseOutAndEndsAtTheNewDrawing() {
        val glide = SurfaceGlide(75.0)
        val first = drawing(circle(0.0))
        assertTrue(glide.retarget(first, 0, motion = false))
        // The first drawing shows at once.
        assertFalse(glide.running(0))
        assertSame(first, glide.frame(0)[0].commands)
        val second = drawing(circle(100.0))
        assertTrue(glide.retarget(second, 1000 * ms, motion = false))
        assertEquals(0.0, x(glide.frame(1000 * ms)), 1e-9)
        // At half of 250 ms, an ease-out has gone more than half of the way.
        val half = x(glide.frame(1125 * ms))
        assertTrue("$half", half > 50 && half < 100)
        assertTrue(glide.running(1249 * ms))
        // The last frame is the new drawing itself, and the end of the press glide is reported once.
        assertFalse(glide.running(1250 * ms))
        assertSame(second, glide.frame(1250 * ms)[0].commands)
        assertTrue(glide.pressEnded(1250 * ms))
        assertFalse(glide.pressEnded(1300 * ms))
        // An equal drawing changes nothing.
        assertFalse(glide.retarget(drawing(circle(100.0)), 1400 * ms, motion = false))
    }

    /** The gate of M5: a drawing that comes 100 ms after its press glides until 250 ms after the press, and a late drawing glides at least 120 ms. */
    @Test fun aPressGlideEnds250MsAfterThePress() {
        val glide = SurfaceGlide(75.0)
        glide.retarget(drawing(circle(0.0)), 0, motion = false)
        glide.retarget(drawing(circle(100.0)), 1100 * ms, motion = false, pressNs = 1000 * ms)
        assertTrue(glide.running(1249 * ms))
        assertFalse(glide.running(1250 * ms))
        glide.retarget(drawing(circle(0.0)), 2400 * ms, motion = false, pressNs = 2000 * ms)
        assertTrue(glide.running(2519 * ms))
        assertFalse(glide.running(2520 * ms))
        // A press time is the last press within one second.
        val now = System.nanoTime()
        SurfaceGlide.pressed()
        assertTrue(SurfaceGlide.pressTime(System.nanoTime()) - now in 0..1_000_000_000L)
        assertEquals(now + 2_000_000_000L, SurfaceGlide.pressTime(now + 2_000_000_000L))
    }

    /** M6: a second change during a glide starts from the picture that shows at that moment, and its last frame is the second drawing. */
    @Test fun aSecondChangeStartsFromThePictureThatShows() {
        val glide = SurfaceGlide(75.0)
        glide.retarget(drawing(circle(0.0)), 0, motion = false)
        glide.retarget(drawing(circle(100.0)), 1000 * ms, motion = false)
        val shown = x(glide.frame(1100 * ms))
        val third = drawing(circle(-100.0))
        glide.retarget(third, 1100 * ms, motion = false)
        assertEquals(shown, x(glide.frame(1100 * ms)), 1e-9)
        assertTrue(glide.running(1349 * ms))
        assertSame(third, glide.frame(1350 * ms)[0].commands)
    }

    /** A shape whose color or text changes cross-fades alone, and each other shape glides. Another list of shapes cross-fades as a whole. */
    @Test fun aChangedShapeCrossFadesAndAnotherListCrossFadesAsAWhole() {
        val glide = SurfaceGlide(75.0)
        glide.retarget(drawing(circle(0.0), text("Вдох")), 0, motion = false)
        glide.retarget(drawing(circle(100.0), text("Выдох")), 1000 * ms, motion = false)
        val layers = glide.frame(1125 * ms)
        assertEquals(listOf(1, 1, 1), layers.map { it.commands.length() })
        assertEquals(listOf("Вдох", "Выдох"), layers.drop(1).map { it.commands.getJSONObject(0).getString("text") })
        assertEquals(1f, layers[1].alpha + layers[2].alpha, 1e-6f)
        assertTrue(layers[0].commands.getJSONObject(0).getDouble("x") in 50.0..100.0)
        // A shape that appears: the old picture at full alpha, and the new one in an opaque layer of the progress.
        val other = SurfaceGlide(75.0)
        other.retarget(drawing(circle(0.0)), 0, motion = false)
        val more = drawing(circle(0.0), circle(40.0, "ink"))
        other.retarget(more, 1000 * ms, motion = false)
        val fade = other.frame(1125 * ms)
        assertEquals(listOf(1f, false, true), listOf(fade[0].alpha, fade[0].opaque, fade[1].opaque))
        assertSame(more, fade[1].commands)
        assertTrue(fade[1].alpha in 0f..1f)
        assertSame(more, other.frame(1250 * ms).single().commands)
    }

    /**
     * C1: a step of the motion timer glides linearly over the time since the step before it, so each display frame between two steps is a new
     * picture. The wrap of a loop shows at once. A motion step inside a press glide keeps the end of the press glide.
     */
    @Test fun aMotionStepGlidesLinearlyAndAWrapShowsAtOnce() {
        val glide = SurfaceGlide(75.0)
        glide.retarget(drawing(circle(0.0)), 0, motion = true)
        glide.retarget(drawing(circle(10.0)), 50 * ms, motion = true)
        glide.retarget(drawing(circle(20.0)), 100 * ms, motion = true)
        assertEquals(10.0, x(glide.frame(100 * ms)), 1e-9)
        assertEquals(15.0, x(glide.frame(125 * ms)), 1e-9)
        assertFalse(glide.running(150 * ms))
        assertFalse(glide.pressEnded(150 * ms))
        // The loop goes from its end back to its start: a jump above a quarter of the surface shows at once.
        val wrap = drawing(circle(-200.0))
        glide.retarget(wrap, 150 * ms, motion = true)
        assertFalse(glide.running(150 * ms))
        assertSame(wrap, glide.frame(150 * ms)[0].commands)
        // A press, then a motion step 50 ms later: the glide still ends 250 ms after the press.
        glide.retarget(drawing(circle(0.0)), 1000 * ms, motion = false)
        glide.retarget(drawing(circle(10.0)), 1050 * ms, motion = true)
        assertTrue(glide.running(1249 * ms))
        assertFalse(glide.running(1250 * ms))
        assertEquals(10.0, x(glide.frame(1250 * ms)), 1e-9)
    }
}

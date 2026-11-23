package com.alpha.assistant.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.appfile.AppFileCompiler
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The pictures of the image entries of the library (stage 6, I4): each one loads from the assets and draws, and a missing one is a miss. */
@RunWith(AndroidJUnit4::class)
class LibraryImagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pictures by lazy {
        val objects = JSONObject(context.assets.open("library/catalog.json").use { it.readBytes().decodeToString() }).getJSONArray("objects")
        (0 until objects.length()).map(objects::getJSONObject).filter { it.has("image") }.map { it.getString("id") }
    }
    private fun image(id: String, side: Int) = JSONObject().put("type", "image").put("id", id).put("x", 0).put("y", 0).put("width", side).put("height", side)
    private fun checked(vararg commands: JSONObject) = GraphicsContract.frame(JSONObject().put("commands", JSONArray(commands.toList())).toString(), "frame").getJSONArray("commands")

    @Test fun eachPictureOfTheCatalogLoadsFromTheAssetsAndDrawsPixels() {
        assertEquals(20, pictures.size)
        assertEquals(emptySet<String>(), LibraryImages.missing(context.assets))
        for (id in pictures) {
            val picture = checkNotNull(LibraryImages.load(context.assets, id)) { id }
            assertEquals(id, 768 to 768, picture.width to picture.height)
            val target = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            drawSurfaceFrame(Canvas(target), ToolSurface("main", 64, 64), checked(image(id, 64)), 64f, 64f, emptyMap(), mapOf(LibraryImages.key(id) to picture))
            val drawn = IntArray(64 * 64).also { target.getPixels(it, 0, 64, 0, 0, 64, 64) }.count { Color.alpha(it) > 0 }
            // The thin skeleton covers the least: 778 of 4096 pixels on the emulator. The next one, the satellite, covers about 2/5.
            assertTrue("$id draws $drawn of 4096 pixels", drawn > 4096 / 8)
        }
        // A picture that is not decoded yet draws nothing, and a command that names no entry id is a fault of the contract.
        val empty = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        drawSurfaceFrame(Canvas(empty), ToolSurface("main", 16, 16), checked(image("body.heart", 16)), 16f, 16f, emptyMap())
        assertEquals(0, IntArray(256).also { empty.getPixels(it, 0, 16, 0, 0, 16, 16) }.count { Color.alpha(it) > 0 })
        assertTrue(runCatching { checked(image("../heart", 16)) }.isFailure)
        assertEquals(setOf("body.heart"), LibraryImages.ids(JSONObject().put("main", JSONObject().put("commands",
            JSONArray().put(JSONObject().put("type", "group").put("children", JSONArray().put(image("body.heart", 16))))))))
    }

    @Test fun aPictureThatTheAssetsDoNotHoldIsAMissOfTheKit() = runBlocking {
        val plan = """
            scene("Eye", lang="en", palette="blue")
            control = slider("Size", 1, 10, 1, 5)
            s1 = use("eye", 150, 150, 160, parts=true, visible=${'$'}/v > 0)
            card = card("The eye", "The lens bends the light.")
            say = "Look."
        """.trimIndent() + "\n"
        val compiler = AppFileCompiler(context, SandboxClient(context))
        assertEquals(emptyList<String>(), compiler.compile(plan).faults)
        compiler.missingImages = setOf("body.eye")
        // The miss comes first. The scene then has no shape that reads $/v, so the control and the scene get their usual faults too.
        val faults = compiler.compile(plan).faults
        assertTrue(faults.toString(), faults.first().contains("the library has no \"eye\". Draw it with shapes, or with an emoji"))
    }
}

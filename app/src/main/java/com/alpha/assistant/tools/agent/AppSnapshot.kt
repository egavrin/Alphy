package com.alpha.assistant.tools.agent

import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.alpha.assistant.cloud.ImageInput
import com.alpha.assistant.tools.ToolContent
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.three.ThreeSceneAssets
import com.alpha.assistant.ui.AlphaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Draws the first screen of a generated app as a phone screenshot, for the optional screenshot review. */
internal object AppSnapshot {
    const val WIDTH_DP = 360
    const val HEIGHT_DP = 760

    /** The app is drawn on a private virtual display, so no activity is needed and the user does not see it. */
    suspend fun capture(context: Context, preview: ToolPreview): Bitmap = withContext(Dispatchers.Main) {
        val metrics = context.resources.displayMetrics
        val width = (WIDTH_DP * metrics.density).roundToInt()
        val height = (HEIGHT_DP * metrics.density).roundToInt()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = context.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("alphy-app-snapshot", width, height, metrics.densityDpi, reader.surface, 0)
        val owner = SnapshotOwner()
        val assets = ThreeSceneAssets(context)
        val pictures = preview.spec.generatedImages.associateBy { "slot:" + it.slot }
        val presentation = Presentation(context, display.display)
        try {
            // The app draws at font scale 1.0 (AlphyActivity), so the review picture ignores the Android font size setting too.
            val view = ComposeView(presentation.context.createConfigurationContext(Configuration().apply { fontScale = 1f })).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    AlphaTheme {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column {
                                ToolContent(preview.ui, preview.state, enabled = true, imageVersion = pictures.keys,
                                    imageLoader = { handle -> pictures[handle]?.let { withContext(Dispatchers.IO) { assets.generatedBitmap(it.asset) } } },
                                    imageAvailable = pictures::containsKey,
                                    appLanguage = preview.spec.appLanguage) { _, _ -> }
                            }
                        }
                    }
                }
            }
            presentation.setContentView(view)
            presentation.show()
            owner.resume()
            // Compose needs a few frames to lay out and draw. The newest frame is the settled screen.
            var image: Image? = null
            var waited = 0
            while (waited < 3_000 && (image == null || waited < 900)) {
                delay(100)
                waited += 100
                reader.acquireLatestImage()?.let { image?.close(); image = it }
            }
            val frame = checkNotNull(image) { "The app screen was not drawn." }
            try { frame.toBitmap(width, height) } finally { frame.close() }
        } finally {
            presentation.dismiss()
            owner.destroy()
            display.release()
            reader.close()
        }
    }

    /** A JPEG data URL, small enough to send with a model turn. It goes through the 2048 px limit of every picture. */
    fun dataUrl(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes))
        val (sent, mime) = ImageInput.forSending(bytes.toByteArray(), "image/jpeg")
        return "data:$mime;base64," + Base64.encodeToString(sent, Base64.NO_WRAP)
    }

    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes[0]
        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private class SnapshotOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        init {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}

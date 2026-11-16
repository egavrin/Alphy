package com.alpha.assistant.tools.three

import android.app.AlertDialog
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.alpha.assistant.tools.ToolPreview
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.cloud.CloudFailure
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Starts the scene in a foreground preview and checks that its first frame is not blank. */
internal suspend fun checkThreeScene(
    spec: ToolSpec,
    id: String,
    revision: Int,
    initialState: String,
    checkActive: suspend () -> Unit,
): ToolPreview = withThreeScene(spec, id, revision, initialState, checkActive) { _, initial -> ToolPreview(spec, initial.state, initial.ui) }

/**
 * Runs [block] on a started scene in a foreground preview after its first frame shows content.
 * [block] gets the snapshot from the start. The user can decline the preview, which stops the work.
 */
internal suspend fun <T> withThreeScene(
    spec: ToolSpec,
    id: String,
    revision: Int,
    initialState: String,
    checkActive: suspend () -> Unit,
    block: suspend (ThreeSceneSession, ThreeSceneSnapshot) -> T,
): T = withContext(Dispatchers.Main.immediate) {
    val activity = ThreeSceneForeground.activity()
    val session = ThreeSceneSession(activity, spec, id, revision, initialState, spec.ui.toString())
    val accepted = CompletableDeferred<Boolean>()
    val lifecycle = (activity as LifecycleOwner).lifecycle
    fun decline() {
        if (accepted.complete(false)) session.close()
    }
    fun declined() = CloudFailure("three_declined", "The 3D preview was declined.")
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) decline()
    }
    val height = (300 * activity.resources.displayMetrics.density).toInt()
    val preview = FrameLayout(activity).apply {
        addView(session.web.view, FrameLayout.LayoutParams(-1, height))
    }
    val dialog = AlertDialog.Builder(activity).setTitle(spec.title).setView(preview)
        .setNegativeButton("Cancel") { _, _ -> decline() }
        .setOnCancelListener { decline() }.create()
    dialog.setOnDismissListener { decline() }
    try {
        lifecycle.addObserver(observer)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        dialog.show()
        val window = checkNotNull(dialog.window)
        withTimeoutOrNull(2_500) {
            while (!session.web.view.isShown || session.web.view.width == 0 || session.web.view.height == 0) {
                if (accepted.isCompleted && !accepted.await()) throw declined()
                delay(16)
            }
        } ?: throw ThreeSceneFailure(ThreeSceneProblem.CAPTURE_FAILED)
        session.start()
        val initial = session.snapshot
        suspend fun checkOpen() {
            checkActive()
            if (accepted.isCompleted && !accepted.await() ||
                !dialog.isShowing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                throw declined()
        }
        suspend fun capture(): ScenePixels {
            checkOpen()
            delay(34)
            val bitmap = session.web.capture(window)
            try {
                return ScenePixels.read(bitmap)
            } finally { bitmap.recycle() }
        }
        var firstFrame = capture()
        repeat(12) {
            if (!firstFrame.visible) {
                repeat(8) {
                    checkOpen()
                    session.frame(0)
                    delay(16)
                }
                firstFrame = capture()
            }
        }
        if (!firstFrame.visible) throw ThreeSceneFailure(ThreeSceneProblem.BLANK_FRAME)
        checkOpen()
        // A declined preview closes the session, and the block can end with that as an ordinary step failure.
        block(session, initial).also { checkOpen() }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        if (accepted.isCompleted && !accepted.await()) throw declined()
        throw failure
    } finally {
        lifecycle.removeObserver(observer)
        dialog.dismiss()
        session.close()
    }
}

private class ScenePixels(private val pixels: IntArray) {
    val visible: Boolean
        get() {
            val background = pixels.asIterable().groupingBy { it and 0x00f0f0f0 }.eachCount()
                .maxBy { it.value }.key
            return pixels.count { difference(it, background) > 48 } >= pixels.size / 100
        }

    private fun difference(a: Int, b: Int): Int = (0..16 step 8).sumOf { shift ->
        abs((a shr shift and 255) - (b shr shift and 255))
    }

    companion object {
        fun read(bitmap: Bitmap): ScenePixels {
            val width = bitmap.width
            val height = bitmap.height
            require(width > 0 && height > 0)
            return ScenePixels(IntArray(64 * 64) { i ->
                bitmap.getPixel(i % 64 * width / 64, i / 64 * height / 64)
            })
        }
    }
}

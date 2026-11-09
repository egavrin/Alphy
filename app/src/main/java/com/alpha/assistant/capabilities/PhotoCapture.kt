package com.alpha.assistant.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpha.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.json.JSONObject

internal const val PHOTO_CAPTURE = "camera.capture"
internal const val MAX_PHOTO_SIDE = 2048
internal const val MAX_PHOTO_BYTES = 10 * 1024 * 1024L

internal enum class PhotoLens(val wireName: String, val selector: CameraSelector, val facing: Int, val label: Int) {
    FRONT("front", CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.LENS_FACING_FRONT, R.string.camera_front),
    REAR("rear", CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.LENS_FACING_BACK, R.string.camera_rear);

    companion object {
        fun requested(args: JSONObject) = entries.single { it.wireName == args.optString("lens", "rear") }

        fun present(context: Context): Set<PhotoLens>? = runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            manager.cameraIdList.mapNotNull { id ->
                val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                entries.find { it.facing == facing }
            }.toSet()
        }.getOrNull()
    }
}

/** The caller retains the immutable request identity and authorizes the returned bitmap. */
internal class PhotoCapture(val lens: PhotoLens, val active: () -> Boolean,
    private val onResult: (String, Bitmap?) -> Unit) {
    var finished = false
        private set

    fun finish(status: String, photo: Bitmap? = null) {
        if (finished) {
            photo?.recycle()
            return
        }
        finished = true
        onResult(status, photo)
    }
}

@Composable
internal fun PhotoCaptureDialog(request: PhotoCapture) = key(request) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lens = stringResource(request.lens.label)
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var pending by remember { mutableStateOf(false) }
    var take by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun permitted() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun active() = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && request.active()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) request.finish("cancelled")
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            request.finish("cancelled")
        }
    }
    AlertDialog(
        onDismissRequest = { request.finish("cancelled") },
        title = { Text(lens) },
        text = {
            val bitmap = photo
            if (bitmap == null) {
                val view = remember { PreviewView(context) }
                AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(280.dp))
                DisposableEffect(view, owner) {
                    val main = context.mainExecutor
                    val future = ProcessCameraProvider.getInstance(context)
                    val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
                    val resolution = ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
                    val capture = ImageCapture.Builder().setResolutionSelector(resolution)
                        .setTargetRotation(view.display?.rotation ?: 0).build()
                    var disposed = false
                    future.addListener({
                        if (!disposed && !request.finished) {
                            try {
                                check(active())
                                if (!permitted()) throw SecurityException()
                                val provider = future.get()
                                val camera = provider.bindToLifecycle(owner, request.lens.selector, preview, capture)
                                check(camera.cameraInfo.lensFacing == request.lens.facing)
                                view.previewStreamState.observe(owner) { state ->
                                    take = if (state == PreviewView.StreamState.STREAMING) ({
                                        capture.targetRotation = view.display?.rotation ?: 0
                                        capture.takePicture(Dispatchers.Default.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
                                            override fun onCaptureSuccess(image: ImageProxy) {
                                                val result = runCatching { image.use(::boundedPhoto) }.getOrNull()
                                                main.execute {
                                                    when {
                                                        request.finished || !active() -> {
                                                            result?.recycle()
                                                            request.finish("cancelled")
                                                        }
                                                        !permitted() -> {
                                                            result?.recycle()
                                                            request.finish("denied")
                                                        }
                                                        result == null -> request.finish("failed")
                                                        else -> photo = result
                                                    }
                                                }
                                            }
                                            override fun onError(exception: ImageCaptureException) {
                                                main.execute { request.finish(if (permitted()) "failed" else "denied") }
                                            }
                                        })
                                    }) else null
                                }
                            } catch (_: SecurityException) {
                                request.finish("denied")
                            } catch (_: Exception) {
                                request.finish(if (active()) "unavailable" else "cancelled")
                            }
                        }
                    }, main)
                    onDispose {
                        disposed = true
                        view.previewStreamState.removeObservers(owner)
                        if (future.isDone) runCatching { future.get().unbind(preview, capture) }
                    }
                }
            } else {
                Column {
                    Image(bitmap.asImageBitmap(), stringResource(R.string.tool_camera_review_image),
                        Modifier.fillMaxWidth().height(280.dp))
                    Text(stringResource(R.string.tool_camera_review_details))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = photo != null || (take != null && !pending), onClick = {
                if (!active()) request.finish("cancelled")
                else if (!permitted()) request.finish("denied")
                else photo?.let { request.finish("succeeded", it) } ?: take?.let {
                    pending = true
                    runCatching(it).onFailure { request.finish(if (permitted()) "failed" else "denied") }
                }
            }) { Text(stringResource(if (photo == null) R.string.camera_shutter else R.string.tool_camera_keep)) }
        },
        dismissButton = { TextButton(onClick = { request.finish("cancelled") }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun boundedPhoto(image: ImageProxy): Bitmap {
    val bitmap = image.toBitmap()
    val scale = minOf(1f, MAX_PHOTO_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
    val transform = Matrix().apply {
        setRotate(image.imageInfo.rotationDegrees.toFloat())
        postScale(scale, scale)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

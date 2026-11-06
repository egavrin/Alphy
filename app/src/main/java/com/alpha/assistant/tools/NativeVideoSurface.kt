package com.alpha.assistant.tools

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.annotation.MainThread
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alpha.assistant.R
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NativeVideoStatus { Idle, Preparing, Playing, Unavailable, Failed }

/** Identity belongs to a single mounted control and resource binding. */
internal class NativeVideoControl(val actionId: String, val resourceId: String)

internal interface NativeVideoUiHost {
    @MainThread fun onSurfaceAvailable(control: NativeVideoControl, lease: NativeVideoSurfaceLease)

    /** Detach playback for this exact lease before returning. The view then releases its surface. */
    @MainThread fun onSurfaceLost(control: NativeVideoControl, lease: NativeVideoSurfaceLease)

    /** The host validates its current owner, revision, foreground state, resource, and exact lease. */
    @MainThread fun play(control: NativeVideoControl, lease: NativeVideoSurfaceLease)

    /** Read status for this lease. Use observable state when status changes asynchronously. */
    @MainThread fun status(control: NativeVideoControl, lease: NativeVideoSurfaceLease): NativeVideoStatus = NativeVideoStatus.Idle
}

internal interface NativeVideoSurfaceHost {
    /** Borrow the surface for the life of this lease. Recheck its identity before playback starts. */
    @MainThread fun onSurfaceAvailable(lease: NativeVideoSurfaceLease)

    /** Detach or close playback synchronously before this call returns. The view releases the surface. */
    @MainThread fun onSurfaceLost(lease: NativeVideoSurfaceLease)
}

internal class NativeVideoSurfaceLease internal constructor(val surface: Surface) {
    private val current = AtomicBoolean(true)
    val isCurrent: Boolean get() = current.get() && surface.isValid

    internal fun invalidate() { current.set(false) }
}

/** Binds a single foreground view producer to the host on Main. */
@MainThread
internal class NativeVideoSurfaceBinding(private val host: NativeVideoSurfaceHost) {
    private var producer: Any? = null
    private var texture: SurfaceTexture? = null
    private var hasSize = false
    private var active = false
    private var hardwareAccelerated = false
    private var lease by mutableStateOf<NativeVideoSurfaceLease?>(null)
    var platformUnavailable by mutableStateOf(false)
        private set
    val surfaceAvailable: Boolean get() = lease != null

    fun setActive(value: Boolean) {
        active = value
        if (value) publish() else revoke()
    }

    fun attach(source: Any, hardwareAccelerated: Boolean) {
        if (producer === source) return
        revoke()
        producer = source
        texture = null
        hasSize = false
        this.hardwareAccelerated = hardwareAccelerated
        platformUnavailable = !hardwareAccelerated
    }

    fun detach(source: Any) {
        if (producer !== source) return
        producer = null
        texture = null
        hasSize = false
        revoke()
    }

    fun present(source: Any, texture: SurfaceTexture, width: Int, height: Int) {
        if (producer !== source) return
        if (this.texture !== texture) {
            revoke()
            this.texture = texture
            platformUnavailable = !hardwareAccelerated
        }
        resize(source, texture, width, height)
    }

    fun resize(source: Any, texture: SurfaceTexture, width: Int, height: Int) {
        if (producer !== source || this.texture !== texture) return
        hasSize = width > 0 && height > 0
        if (hasSize) publish() else revoke()
    }

    fun destroy(source: Any, texture: SurfaceTexture) {
        if (producer !== source || this.texture !== texture) return
        this.texture = null
        hasSize = false
        revoke()
    }

    private fun publish() {
        if (!active || !hasSize || platformUnavailable || lease != null) return
        val currentTexture = texture ?: return
        if (currentTexture.isReleased) {
            platformUnavailable = true
            return
        }
        val surface = Surface(currentTexture)
        if (!surface.isValid) {
            surface.release()
            platformUnavailable = true
            return
        }
        val available = NativeVideoSurfaceLease(surface)
        lease = available
        host.onSurfaceAvailable(available)
    }

    private fun revoke() {
        val retired = lease ?: return
        retired.invalidate()
        lease = null
        try {
            host.onSurfaceLost(retired)
        } finally {
            retired.surface.release()
        }
    }
}

private class NativeVideoTextureView(context: Context, private val binding: NativeVideoSurfaceBinding) :
    TextureView(context), TextureView.SurfaceTextureListener {
    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binding.attach(this, isHardwareAccelerated)
        surfaceTexture?.let { binding.present(this, it, width, height) }
    }

    override fun onDetachedFromWindow() {
        binding.detach(this)
        super.onDetachedFromWindow()
    }

    fun release() {
        binding.detach(this)
        surfaceTextureListener = null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) =
        binding.present(this, texture, width, height)

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) =
        binding.resize(this, texture, width, height)

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        binding.destroy(this, texture)
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

/** Renders a video destination for an injected host. The host owns playback and its authority checks. */
@Composable
internal fun NativeVideoSurface(host: NativeVideoSurfaceHost?, active: Boolean, status: NativeVideoStatus,
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), overlay: @Composable BoxScope.() -> Unit = {}) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val binding = remember(host) { host?.let(::NativeVideoSurfaceBinding) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, binding, active) {
        fun update() {
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            binding?.setActive(active && resumed)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            binding?.setActive(false)
        }
    }
    val mounted = active && resumed && binding != null
    val message = when {
        !mounted || binding.platformUnavailable || status == NativeVideoStatus.Unavailable -> R.string.tool_video_unavailable
        status == NativeVideoStatus.Failed -> R.string.tool_video_failed
        binding.surfaceAvailable && status == NativeVideoStatus.Playing -> R.string.tool_video_playing
        binding.surfaceAvailable && status == NativeVideoStatus.Idle -> R.string.tool_video_ready
        else -> R.string.tool_video_preparing
    }
    Box(modifier.clipToBounds().background(Color.Black)) {
        if (mounted) key(binding) {
            AndroidView(factory = { NativeVideoTextureView(it, binding) }, modifier = Modifier.matchParentSize(),
                onRelease = NativeVideoTextureView::release)
        }
        val label = stringResource(message)
        if (message == R.string.tool_video_playing) {
            Box(Modifier.matchParentSize().semantics { contentDescription = label })
        } else {
            val colors = MaterialTheme.colorScheme
            Box(Modifier.matchParentSize().background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(label, Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.onSurfaceVariant)
            }
        }
        overlay()
    }
}

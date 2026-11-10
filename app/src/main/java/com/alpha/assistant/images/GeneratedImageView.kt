package com.alpha.assistant.images

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.alpha.assistant.tools.three.ThreeSceneAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GeneratedImageView(image: GeneratedImage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, image.asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { ThreeSceneAssets(context).generatedBitmap(image.asset)?.asImageBitmap() }.getOrNull()
        }
    }
    bitmap?.let { Image(it, image.alt, modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) }
}

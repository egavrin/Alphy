package com.alpha.assistant.tools

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The pictures of the image entries of the library (stage 6, I4): the kit draws an image entry as {type:"image", id, x, y, width, height},
 * and the host draws the WebP file of the entry from its assets (kit/build.js imageAsset), with its own colors in both themes.
 */
internal object LibraryImages {
    /** The id of an entry: its area and its name. */
    val ID=Regex("[a-z]+\\.[a-z_]+")
    /** The most pictures of the library in one drawing. A scene holds a few objects (kit: 512 path segments in one reply). */
    const val MAX_IN_DRAWING=8
    /** The key of a picture in the image map of a drawing (drawSurfaceFrame images). */
    fun key(id: String)="library:$id"
    private fun path(id: String)="library/${id.replace('.','/')}.webp"
    // Eight decoded pictures of 768 x 768 pixels: the pictures of a scene stay decoded while the stage changes.
    private val decoded=object: LruCache<String,Bitmap>(8*768*768*4) {
        override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount
    }

    /** The picture of [id], decoded once, or null when the assets have no such image. */
    fun load(assets: AssetManager,id: String): Bitmap? = decoded.get(id) ?: runCatching {assets.open(path(id)).use(BitmapFactory::decodeStream)}.getOrNull()?.also {decoded.put(id,it)}

    /** The ids of the pictures of the library in the commands of each surface. */
    fun ids(surfaces: JSONObject): Set<String> = ArtifactProtocol.commandValues(surfaces,"image","id")

    /**
     * The ids of the image entries of the catalog whose image the assets do not hold. The kit treats such a name as one that the library does
     * not have, so the repair draws it with shapes or an emoji (kit/src/library.js LIBRARY).
     */
    fun missing(assets: AssetManager): Set<String> {
        val objects=JSONObject(assets.open("library/catalog.json").use {it.readBytes().decodeToString()}).getJSONArray("objects")
        return (0 until objects.length()).map(objects::getJSONObject).filter {it.has("image")}
            .filterNot {entry -> runCatching {assets.open(entry.getString("image")).close()}.isSuccess}.map {it.getString("id")}.toSet()
    }
}

/** The pictures of the library that [surfaces] draw, by their key, decoded off the main thread. A picture that is not ready yet draws nothing. */
@Composable internal fun rememberLibraryImages(surfaces: JSONObject?): Map<String,Bitmap> {
    val assets=LocalContext.current.assets
    val ids=remember(surfaces) {surfaces?.let(LibraryImages::ids).orEmpty()}
    val images by produceState(emptyMap<String,Bitmap>(),ids) {
        value=withContext(Dispatchers.IO) {ids.mapNotNull {id -> LibraryImages.load(assets,id)?.let {LibraryImages.key(id) to it}}.toMap()}
    }
    return images
}

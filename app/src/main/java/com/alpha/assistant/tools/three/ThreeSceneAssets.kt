package com.alpha.assistant.tools.three

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.sandbox.SandboxContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ThreeSceneAssetRef(val id: String, val name: String, val mime: String, val size: Long) {
    fun json() = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size)
}

internal class ThreeSceneAssets(private val context: Context) {
    private val directory = File(context.filesDir, "threejs-assets")

    suspend fun import(uri: Uri, name: String, requestedMime: String): Attachment {
        require(uri.scheme == "content")
        check(directory.mkdirs() || directory.isDirectory)
        val temporary = File.createTempFile("import-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_BYTES) { "The 3D asset exceeds 64 MiB." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(size > 0) { "The 3D asset is empty." }
            val mime = when {
                requestedMime == GLB || name.endsWith(".glb", ignoreCase = true) -> {
                    validateGlb(temporary)
                    GLB
                }
                requestedMime in IMAGES -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temporary.path, bounds)
                    checkImage(bounds, requestedMime)
                    requestedMime
                }
                requestedMime in AUDIO -> {
                    MediaMetadataRetriever().use { player ->
                        player.setDataSource(temporary.path)
                        val duration = player.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                        require(player.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" &&
                            player.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes" &&
                            duration in 1L..30_000L && size <= 10 * 1024 * 1024) {
                            "Use a local audio clip of up to 30 seconds and 10 MiB."
                        }
                    }
                    requestedMime
                }
                else -> error("Choose a GLB, PNG, JPEG, WebP, or supported audio file.")
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val file = file(id)
            if (!file.exists()) check(temporary.renameTo(file) || file.length() == size) { "Alphy could not store the 3D asset." }
            val ref = ThreeSceneAssetRef(id, name.take(120).ifBlank { id }, mime, size)
            return Attachment("alphy-three-asset:$id", ref.name, ATTACHMENT_MIME, ref.json().toString())
        } finally {
            temporary.delete()
        }
    }

    fun workerAssets(refs: List<ThreeSceneAssetRef>): List<ThreeSceneAsset> = refs.map { ref ->
        val file = file(ref.id)
        require(file.isFile && file.length() == ref.size) { "A selected 3D asset is unavailable." }
        ThreeSceneAsset(ref.id, ref.mime, ref.size, ref.name) { FileInputStream(file) }
    } + catalog.map { (id, mime, name) ->
        val path = "threejs/catalog/$id"
        val size = context.assets.openFd(path).use { it.length }
        ThreeSceneAsset(id, mime, size, name) { context.assets.open(path) }
    }

    fun audio(refs: List<ThreeSceneAssetRef>, id: String): AssetFileDescriptor? {
        if (catalog.any { it.first == id && it.second in AUDIO }) return context.assets.openFd("threejs/catalog/$id")
        val ref = refs.firstOrNull { it.id == id && it.mime in AUDIO } ?: return null
        val file = file(ref.id)
        if (file.length() != ref.size) return null
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, ref.size)
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}")))
        return File(directory, id)
    }

    companion object {
        const val ATTACHMENT_MIME = "application/vnd.alphy.three-asset"
        private const val GLB = "model/gltf-binary"
        private const val MAX_BYTES = 64L * 1024 * 1024
        private val IMAGES = setOf("image/png", "image/jpeg", "image/webp")
        private val AUDIO = setOf("audio/wav", "audio/x-wav", "audio/mpeg", "audio/ogg", "audio/mp4")
        private val extensions = setOf("KHR_materials_unlit", "KHR_texture_transform")
        private val catalog = listOf(Triple("cue.wav", "audio/wav", "Confirmation tone"),
            Triple("robot.glb", GLB, "Animated low-poly robot"))

        fun read(array: JSONArray): List<ThreeSceneAssetRef> {
            require(array.length() <= 32)
            return (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                require(entry.keys().asSequence().toSet() == setOf("id", "name", "mime", "size"))
                ThreeSceneAssetRef(entry.getString("id"), entry.getString("name"), entry.getString("mime"),
                    entry.getLong("size")).also {
                    require(it.id.matches(Regex("[0-9a-f]{64}")) && it.name.length in 1..120 &&
                        it.size in 1..MAX_BYTES && (it.mime == GLB || it.mime in IMAGES || it.mime in AUDIO))
                }
            }.also { refs ->
                require(refs.map { it.id }.toSet().size == refs.size && refs.sumOf { it.size } <= 2 * MAX_BYTES)
            }
        }

        fun attachmentRefs(attachments: List<Attachment>) = read(JSONArray(
            attachments.filter { it.mime == ATTACHMENT_MIME }.map { JSONObject(requireNotNull(it.contextText)) }))

        private fun checkImage(bounds: BitmapFactory.Options, mime: String) {
            require(bounds.outMimeType == mime && mime in IMAGES &&
                bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "The 3D image type or dimensions are invalid." }
        }

        private fun validateGlb(file: File) {
            file.inputStream().buffered().use { input ->
                val header = ByteBuffer.wrap(input.readNBytes(20)).order(ByteOrder.LITTLE_ENDIAN)
                require(header.remaining() == 20 && header.int == 0x46546c67 && header.int == 2 &&
                    header.int.toLong() == file.length()) { "Use a glTF 2.0 binary file." }
                val length = header.int
                require(length in 2..(512 * 1024) && length % 4 == 0 && header.int == 0x4e4f534a)
                val model = SandboxContract.objectFrom(input.readNBytes(length).toString(Charsets.UTF_8), 512 * 1024)
                val binaryHeader = ByteBuffer.wrap(input.readNBytes(8)).order(ByteOrder.LITTLE_ENDIAN)
                require(binaryHeader.remaining() == 8)
                val binaryLength = binaryHeader.int
                require(binaryLength > 0 && binaryHeader.int == 0x004e4942 &&
                    file.length() == 28L + length + binaryLength)
                rejectExternalReferences(model)
                for (key in listOf("extensionsUsed", "extensionsRequired")) model.optJSONArray(key)?.let { used ->
                    for (i in 0 until used.length()) require(used.getString(i) in extensions) {
                        "Unsupported GLB extension " + used.getString(i)
                    }
                }
                val buffers = model.getJSONArray("buffers")
                require(buffers.length() == 1 && buffers.getJSONObject(0).getInt("byteLength") in 1..binaryLength)
                val views = model.getJSONArray("bufferViews")
                require(views.length() <= 2048)
                for (i in 0 until views.length()) {
                    val view = views.getJSONObject(i)
                    val offset = view.optLong("byteOffset")
                    require(view.getInt("buffer") == 0 && offset in 0..binaryLength.toLong() &&
                        view.getLong("byteLength") in 1..(binaryLength - offset))
                }
                val accessors = model.getJSONArray("accessors")
                require(accessors.length() <= 2048)
                var samples = 0L
                for (i in 0 until accessors.length()) {
                    val a = accessors.getJSONObject(i)
                    require(!a.has("sparse")) { "Sparse GLB accessors are unavailable." }
                    val view = views.getJSONObject(a.getInt("bufferView"))
                    val component = when (a.getInt("componentType")) {
                        5120, 5121 -> 1
                        5122, 5123 -> 2
                        5125, 5126 -> 4
                        else -> error("Invalid GLB component type.")
                    }
                    val width = when (a.getString("type")) {
                        "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4", "MAT2" -> 4
                        "MAT3" -> 9; "MAT4" -> 16
                        else -> error("Invalid GLB accessor type.")
                    } * component
                    val count = a.getLong("count")
                    val offset = a.optLong("byteOffset")
                    val stride = view.optInt("byteStride", width)
                    require(count in 1..1_000_000 && offset in 0..binaryLength.toLong() &&
                        stride in width..252 && offset + (count - 1) * stride + width <= view.getLong("byteLength"))
                    samples += count * width
                    require(samples <= 64 * 1024 * 1024)
                }
                val nodes = model.optJSONArray("nodes") ?: JSONArray()
                require(nodes.length() <= 512 && (model.optJSONArray("animations")?.length() ?: 0) <= 32)
                val parents = IntArray(nodes.length())
                for (i in 0 until nodes.length()) {
                    nodes.getJSONObject(i).optJSONArray("children")?.let { children ->
                        require(children.length() <= nodes.length())
                        for (index in 0 until children.length()) {
                            val child = children.getInt(index)
                            require(child in parents.indices && ++parents[child] == 1)
                        }
                    }
                }
                val seen = IntArray(nodes.length())
                fun visit(index: Int, depth: Int) {
                    require(depth <= 32 && index in seen.indices && seen[index] != 1) { "Invalid GLB node hierarchy." }
                    if (seen[index] == 2) return
                    seen[index] = 1
                    nodes.getJSONObject(index).optJSONArray("children")?.let { children ->
                        for (i in 0 until children.length()) visit(children.getInt(i), depth + 1)
                    }
                    seen[index] = 2
                }
                for (i in seen.indices) visit(i, 1)
                val images = model.optJSONArray("images") ?: JSONArray()
                require(images.length() <= 32)
                var pixels = 0L
                for (i in 0 until images.length()) {
                    val image = images.getJSONObject(i)
                    val view = views.getJSONObject(image.getInt("bufferView"))
                    val size = view.getInt("byteLength")
                    require(size <= 8 * 1024 * 1024)
                    val bytes = FileInputStream(file).use { stream ->
                        stream.skipNBytes(28L + length + view.optLong("byteOffset"))
                        stream.readNBytes(size)
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    checkImage(bounds, image.getString("mimeType"))
                    pixels += bounds.outWidth.toLong() * bounds.outHeight
                    require(pixels <= 32L * 1024 * 1024)
                }
            }
        }

        private fun rejectExternalReferences(value: Any?) {
            when (value) {
                is JSONObject -> {
                    require(!value.has("uri")) { "GLB assets must embed all resources." }
                    value.keys().forEach { rejectExternalReferences(value.get(it)) }
                }
                is JSONArray -> for (i in 0 until value.length()) rejectExternalReferences(value.get(i))
            }
        }
    }
}

package com.alpha.assistant.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import com.alpha.assistant.capabilities.MAX_PHOTO_BYTES
import com.alpha.assistant.capabilities.MAX_PHOTO_SIDE
import com.alpha.assistant.capabilities.PhotoLens
import java.io.File

internal data class AgentPhoto(val planId: String, val step: Int, val lens: PhotoLens)

internal class AgentPhotos(context: Context, database: String) {
    private val directory = File(context.filesDir, "agent-photos/$database")

    fun file(planId: String, step: Int): File {
        require(planId.matches(Regex("[a-f0-9-]{36}")) && step in 0..7)
        return File(directory, "$planId-$step.jpg")
    }

    fun save(file: File, bitmap: Bitmap) {
        check(directory.isDirectory || directory.mkdirs())
        require(bitmap.width in 1..MAX_PHOTO_SIDE && bitmap.height in 1..MAX_PHOTO_SIDE)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream))
            require(stream.channel.size() in 1..MAX_PHOTO_BYTES)
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun prune(referenced: Set<String>) {
        directory.listFiles()?.filter { it.name.removeSuffix(".new").removeSuffix(".bak") !in referenced }
            ?.forEach(File::delete)
    }
}

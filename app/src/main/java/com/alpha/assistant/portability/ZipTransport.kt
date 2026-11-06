package com.alpha.assistant.portability

import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

internal data class ZipLimits(
    val maxArchiveBytes: Long,
    val maxEntries: Int,
    val maxEntryBytes: Long,
    val maxTotalBytes: Long,
) {
    init {
        require(maxArchiveBytes >= 0 && maxEntries >= 0 && maxEntryBytes >= 0 && maxTotalBytes >= 0)
    }
}

internal class StagedZip internal constructor(
    val entries: List<Entry>,
    private val staging: Closeable,
) : Closeable {
    internal class Entry internal constructor(val name: String, val size: Long, private val path: Path) {
        fun openStream(): InputStream = Files.newInputStream(path)
    }

    override fun close() = staging.close()
}

internal object ZipTransport {
    /**
     * Stage a ZIP under an existing app-private directory. The caller owns [input].
     * Close entry streams before closing the returned stage.
     * [checkCancellation] can throw during processing. The caller controls any blocked input read.
     */
    fun stage(
        input: InputStream,
        privateDirectory: Path,
        limits: ZipLimits,
        checkCancellation: () -> Unit = {},
    ): StagedZip {
        checkCancellation()
        val staging = Staging(Files.createTempDirectory(privateDirectory, "zip-"))
        try {
            val archive = staging.createFile()
            val buffer = ByteArray(8192)
            Files.newOutputStream(archive).use {
                copyBounded(input, it, buffer, limits.maxArchiveBytes, checkCancellation)
            }
            val directory = ZipDirectory.read(archive, limits, checkCancellation)
            val entries = extract(archive, directory, staging, buffer, checkCancellation)
            staging.deleteFile(archive)
            checkCancellation()
            return StagedZip(entries, staging)
        } catch (failure: Throwable) {
            try {
                staging.close()
            } catch (cleanup: IOException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun extract(
        archive: Path,
        directory: List<ZipDirectory.Entry>,
        staging: Staging,
        buffer: ByteArray,
        checkCancellation: () -> Unit,
    ): List<StagedZip.Entry> {
        val input = object : FilterInputStream(Files.newInputStream(archive)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                checkCancellation()
                return super.read(bytes, offset, length)
            }
        }
        return ZipInputStream(input).use { zip ->
            directory.map { expected ->
                checkCancellation()
                zipCheck(zip.nextEntry?.name == expected.name, "Invalid local ZIP entry.")
                val file = staging.createFile()
                val size = Files.newOutputStream(file).use {
                    copyBounded(zip, it, buffer, expected.size, checkCancellation)
                }
                zipCheck(size == expected.size, "Invalid expanded ZIP size.")
                StagedZip.Entry(expected.name, size, file)
            }
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        buffer: ByteArray,
        limit: Long,
        checkCancellation: () -> Unit,
    ): Long {
        var total = 0L
        while (true) {
            checkCancellation()
            val remaining = limit - total
            val request = if (remaining >= buffer.size) buffer.size else remaining.toInt() + 1
            val count = input.read(buffer, 0, request)
            if (count == -1) return total
            zipCheck(count <= remaining, "ZIP byte limit exceeded.")
            output.write(buffer, 0, count)
            total += count
        }
    }

    private class Staging(private val directory: Path) : Closeable {
        private val files = mutableListOf<Path>()

        fun createFile(): Path = Files.createTempFile(directory, "entry-", ".tmp").also { files.add(it) }

        fun deleteFile(path: Path) {
            Files.delete(path)
            files.remove(path)
        }

        override fun close() {
            var failure: IOException? = null
            for (path in files + listOf(directory)) {
                try {
                    Files.deleteIfExists(path)
                } catch (error: IOException) {
                    val previous = failure
                    if (previous == null) failure = error else previous.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }
}

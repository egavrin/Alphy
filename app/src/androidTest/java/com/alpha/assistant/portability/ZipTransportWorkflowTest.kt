package com.alpha.assistant.portability

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZipTransportWorkflowTest {
    private val limits = ZipLimits(
        maxArchiveBytes = 8_192, maxEntries = 4, maxEntryBytes = 2_048, maxTotalBytes = 4_096,
    )

    @Test fun stagesRegularEntriesAndRemovesOnlyItsOwnFilesOnClose() = withStagingRoot { root ->
        val contents = "Запись".encodeToByteArray()
        val archive = zip("payload.txt" to contents, "assets/empty.bin" to byteArrayOf())
        val input = archive.inputStream()
        ZipTransport.stage(input, root, limits).use { staged ->
            assertEquals(listOf("payload.txt", "assets/empty.bin"), staged.entries.map { it.name })
            assertArrayEquals(contents, staged.entries[0].openStream().use { it.readBytes() })
            assertArrayEquals(byteArrayOf(), staged.entries[1].openStream().use { it.readBytes() })
            assertTrue("Archive paths must not become staging paths.", !Files.exists(root.resolve("assets")))
        }
    }

    @Test fun acceptsAnEmptyArchiveAndExactLimits() = withStagingRoot { root ->
        val archive = zip("entry" to byteArrayOf(1))
        val exact = ZipLimits(archive.size.toLong(), 1, 1, 1)
        ZipTransport.stage(archive.inputStream(), root, exact).use { staged ->
            assertEquals(listOf("entry"), staged.entries.map { it.name })
        }
        ZipTransport.stage(zip().inputStream(), root, exact.copy(maxEntries = 0)).use { staged ->
            assertTrue(staged.entries.isEmpty())
        }
    }

    @Test fun rejectsUnsafeOrAmbiguousNamesAndNonRegularEntries() = withStagingRoot { root ->
        for (name in listOf("../escape", "folder/../escape", "/absolute", "C:/absolute", "a\\b",
            "folder/./file", "folder//file", "folder/", "file.", "file ")) {
            reject(zip(name to byteArrayOf(1)), root)
        }
        val duplicate = zip("one" to byteArrayOf(1), "two" to byteArrayOf(2))
        replaceName(duplicate, 1, "one")
        reject(duplicate, root)
        reject(zip("File" to byteArrayOf(1), "file" to byteArrayOf(2)), root)
        reject(zip("file" to byteArrayOf(1), "file/child" to byteArrayOf(2)), root)

        for (mode in listOf(0xa000, 0x4000, 0x1000)) {
            val special = zip("entry" to byteArrayOf(1))
            val central = centralOffset(special)
            special[central + 5] = 3 // UNIX creator.
            special[central + 41] = (mode shr 8).toByte()
            reject(special, root)
        }
    }

    @Test fun rejectsTruncationCorruptionAndContradictoryHeaders() = withStagingRoot { root ->
        val regular = zip("first" to byteArrayOf(1), "later" to byteArrayOf(2), stored = true)
        reject(byteArrayOf(1, 2, 3), root)
        reject(regular.copyOf(regular.size - 1), root)
        reject(regular.copyOf(centralOffset(regular)), root)
        reject(regular + byteArrayOf(1), root)
        reject(regular.copyOf().apply { this[30] = 'x'.code.toByte() }, root)
        val secondLocal = 30 + "first".length + 1
        reject(regular.copyOf().apply { this[secondLocal + 30 + "later".length] = 0 }, root)
        val emptyStored = zip("entry" to byteArrayOf(), stored = true)
        emptyStored[14] = 1
        emptyStored[centralOffset(emptyStored) + 16] = 1
        reject(emptyStored, root)

        val deflated = zip("entry" to ByteArray(32))
        reject(deflated.copyOf().apply { this[centralOffset(this) - 16] = 0 }, root)
        reject(deflated.copyOf().apply { this[35] = 0x07 }, root)
        reject(deflated.copyOf().apply { this[centralOffset(this) + 10] = 99 }, root)
        val descriptor = centralOffset(deflated) - 16
        val extraCompressedByte = deflated.copyOfRange(0, descriptor) + byteArrayOf(0) +
            deflated.copyOfRange(descriptor, deflated.size)
        val central = centralOffset(deflated) + 1
        for (index in listOf(central - 8, central + 20, extraCompressedByte.size - 6)) {
            extraCompressedByte[index] = (extraCompressedByte[index] + 1).toByte()
        }
        reject(extraCompressedByte, root)
    }

    @Test fun rejectsEveryByteAndEntryLimitWithoutReturningPartialData() = withStagingRoot { root ->
        val archive = zip("a" to ByteArray(100), "b" to ByteArray(100))
        reject(archive, root, limits.copy(maxArchiveBytes = archive.size.toLong() - 1))
        reject(archive, root, limits.copy(maxEntries = 1))
        reject(archive, root, limits.copy(maxEntryBytes = 99))
        reject(archive, root, limits.copy(maxTotalBytes = 199))

        val falseSize = zip("entry" to ByteArray(100))
        val central = centralOffset(falseSize)
        falseSize[central - 4] = 1 // Report one expanded byte in the data descriptor.
        falseSize[central + 24] = 1 // Report the same size in the central directory.
        reject(falseSize, root, limits.copy(maxEntryBytes = 1))
    }

    @Test fun cancelsBeforeReadingDuringCopyAndDuringExtraction() = withStagingRoot { root ->
        val archive = zip("first" to ByteArray(32), "second" to ByteArray(32))
        fun cancel(input: InputStream, shouldCancel: () -> Boolean) {
            try {
                ZipTransport.stage(input, root, limits) {
                    if (shouldCancel()) throw CancellationException()
                }.close()
                fail("Expected cancellation.")
            } catch (_: CancellationException) {
                assertSentinelOnly(root)
            }
        }
        val unused = object : InputStream() {
            override fun read(): Int = error("Canceled input must remain unread.")
        }
        cancel(unused) { true }
        var read = false
        val source = object : InputStream() {
            override fun read(): Int {
                read = true
                return archive[0].toInt() and 255
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes[offset] = read().toByte()
                return 1
            }
        }
        cancel(source) { read }
        cancel(archive.inputStream()) {
            Files.list(root).use { dirs ->
                dirs.filter { Files.isDirectory(it) }.anyMatch { dir ->
                    Files.list(dir).use { files -> files.count() >= 2 }
                }
            }
        }
    }

    @Test fun cleansUpWhenTheInputThrowsAndLeavesInputOwnershipWithCaller() = withStagingRoot { root ->
        var closed = false
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Injected input failure.")
            override fun close() { closed = true }
        }
        try {
            ZipTransport.stage(broken, root, limits).close()
            fail("Expected input failure.")
        } catch (failure: IOException) {
            assertEquals("Injected input failure.", failure.message)
        }
        assertTrue("Caller owns the input stream.", !closed)
    }

    private fun reject(archive: ByteArray, root: Path, policy: ZipLimits = limits) {
        try {
            ZipTransport.stage(archive.inputStream(), root, policy).close()
            fail("Expected ZIP rejection.")
        } catch (_: IOException) {
            assertSentinelOnly(root)
        }
    }

    private fun withStagingRoot(block: (Path) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
        val root = Files.createTempDirectory(cache, "zip-workflow-")
        try {
            Files.write(root.resolve("existing-data"), byteArrayOf(47))
            block(root)
            assertSentinelOnly(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    private fun assertSentinelOnly(root: Path) {
        assertEquals(listOf("existing-data"), Files.list(root).use { paths ->
            paths.iterator().asSequence().map { it.fileName.toString() }.toList()
        })
        assertArrayEquals(byteArrayOf(47), Files.readAllBytes(root.resolve("existing-data")))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, contents) in entries) {
                val entry = ZipEntry(name)
                entry.time = 1_767_268_800_000L
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = contents.size.toLong()
                    entry.crc = CRC32().apply { update(contents) }.value
                }
                zip.putNextEntry(entry)
                zip.write(contents)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun centralOffset(archive: ByteArray): Int {
        val offsetField = archive.size - 6
        return (0..3).sumOf { (archive[offsetField + it].toInt() and 255) shl (8 * it) }
    }

    private fun replaceName(archive: ByteArray, entryIndex: Int, name: String) {
        val central = centralOffset(archive) + entryIndex * (46 + name.length)
        val local = (0..3).sumOf { (archive[central + 42 + it].toInt() and 255) shl (8 * it) }
        name.encodeToByteArray().copyInto(archive, central + 46)
        name.encodeToByteArray().copyInto(archive, local + 30)
    }
}

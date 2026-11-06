package com.alpha.assistant.portability

import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.Locale
import java.util.TreeSet
import java.util.zip.ZipEntry
import java.util.zip.ZipException

internal fun zipCheck(valid: Boolean, message: String) {
    if (!valid) throw ZipException(message)
}

/**
 * Check the directory and local headers before extraction. ZipInputStream checks entry data.
 * The transport accepts single-disk ZIP32 with STORED or DEFLATED regular files.
 * It accepts ASCII path segments without extras, comments, or filesystem metadata restoration.
 */
internal object ZipDirectory {
    private const val END_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val DESCRIPTOR_SIGNATURE = 0x08074b50
    private const val UTF8_FLAG = 0x0800
    private const val DESCRIPTOR_FLAG = 0x0008
    private const val DEFLATE_OPTIONS = 0x0006
    private const val ZIP32_SENTINEL = 0xffffffffL
    private val segment = Regex("[A-Za-z0-9_-](?:[A-Za-z0-9._-]*[A-Za-z0-9_-])?")

    data class Entry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val offset: Long,
        val version: Int,
        val flags: Int,
        val method: Int,
        val time: Int,
        val crc: Long,
    )

    fun read(path: Path, limits: ZipLimits, checkCancellation: () -> Unit): List<Entry> =
        RandomAccessFile(path.toFile(), "r").use { file ->
            val end = file.length() - 22
            zipCheck(end >= 0, "Missing ZIP directory.")
            file.seek(end)
            zipCheck(file.int() == END_SIGNATURE, "Missing ZIP directory.")
            zipCheck(file.int() == 0, "Multi-disk ZIP is unsupported.")
            val diskCount = file.short()
            val count = file.short()
            zipCheck(diskCount == count && count != 0xffff && count <= limits.maxEntries, "Invalid ZIP entry count.")
            val size = file.unsignedInt()
            val offset = file.unsignedInt()
            zipCheck(offset < ZIP32_SENTINEL && size < ZIP32_SENTINEL && offset + size == end,
                "Invalid ZIP directory bounds.")
            zipCheck(file.short() == 0, "ZIP comments are unsupported.")

            file.seek(offset)
            val names = TreeSet<String>()
            var total = 0L
            val entries = List(count) {
                checkCancellation()
                val entry = readEntry(file, end)
                zipCheck(entry.size <= limits.maxEntryBytes && entry.size <= limits.maxTotalBytes - total,
                    "ZIP expanded byte limit exceeded.")
                total += entry.size
                zipCheck(names.add(entry.name.lowercase(Locale.ROOT)), "Duplicate ZIP entry name.")
                entry
            }.sortedBy { it.offset }
            zipCheck(file.filePointer == end, "Invalid ZIP directory length.")
            for (name in names) {
                checkCancellation()
                val prefix = "$name/"
                zipCheck(names.ceiling(prefix)?.startsWith(prefix) != true, "Ambiguous ZIP entry path.")
            }

            var localOffset = 0L
            for ((index, entry) in entries.withIndex()) {
                checkCancellation()
                val nextOffset = entries.getOrNull(index + 1)?.offset ?: offset
                zipCheck(entry.offset == localOffset && nextOffset <= offset, "Invalid ZIP entry offset.")
                checkLocalEntry(file, entry, nextOffset)
                localOffset = nextOffset
            }
            zipCheck(localOffset == offset, "Invalid ZIP local entries.")
            entries
        }

    private fun readEntry(file: RandomAccessFile, end: Long): Entry {
        zipCheck(file.filePointer + 46 <= end && file.int() == CENTRAL_SIGNATURE, "Invalid ZIP directory entry.")
        file.short() // Creator version and platform.
        val version = file.short()
        val flags = file.short()
        val method = file.short()
        val time = file.int()
        val crc = file.unsignedInt()
        val compressedSize = file.unsignedInt()
        val size = file.unsignedInt()
        val nameLength = file.short()
        val extraLength = file.short()
        val commentLength = file.short()
        val disk = file.short()
        file.short() // Internal file attributes.
        val attributes = file.int()
        val offset = file.unsignedInt()

        val allowedFlags = UTF8_FLAG or if (method == ZipEntry.DEFLATED) DESCRIPTOR_FLAG or DEFLATE_OPTIONS else 0
        zipCheck(version in 10..20 && flags and allowedFlags.inv() == 0 && disk == 0,
            "Unsupported ZIP entry flags or version.")
        zipCheck(method == ZipEntry.DEFLATED || method == ZipEntry.STORED && size == compressedSize,
            "Unsupported ZIP compression.")
        // ZipInputStream skips CRC validation for empty STORED entries.
        zipCheck(method != ZipEntry.STORED || size != 0L || crc == 0L, "Invalid empty ZIP entry CRC.")
        val unixType = (attributes ushr 16) and 0xf000
        val dosSpecial = attributes and 0x0418 // Reparse point, directory, or volume label.
        zipCheck((unixType == 0 || unixType == 0x8000) && dosSpecial == 0, "ZIP entry must be a regular file.")
        zipCheck(compressedSize < ZIP32_SENTINEL && size < ZIP32_SENTINEL && offset < ZIP32_SENTINEL,
            "ZIP64 is unsupported.")
        zipCheck(extraLength == 0 && commentLength == 0 && file.filePointer + nameLength <= end,
            "Invalid ZIP entry name, extras, or comment.")
        val name = file.name(nameLength)
        zipCheck(name.split('/').all(segment::matches), "Unsafe ZIP entry name.")
        return Entry(name, size, compressedSize, offset, version, flags, method, time, crc)
    }

    private fun checkLocalEntry(file: RandomAccessFile, entry: Entry, nextOffset: Long) {
        file.seek(entry.offset)
        zipCheck(entry.offset + 30 <= nextOffset && file.int() == LOCAL_SIGNATURE &&
            file.short() == entry.version && file.short() == entry.flags &&
            file.short() == entry.method && file.int() == entry.time, "ZIP headers disagree.")
        val descriptor = entry.flags and DESCRIPTOR_FLAG != 0
        val crc = file.unsignedInt()
        val compressedSize = file.unsignedInt()
        val size = file.unsignedInt()
        val nameLength = file.short()
        val extraLength = file.short()
        val dataOffset = file.filePointer + nameLength
        val descriptorSize = if (descriptor) 16 else 0
        zipCheck(extraLength == 0 && dataOffset + entry.compressedSize + descriptorSize == nextOffset &&
            file.name(nameLength) == entry.name, "Invalid ZIP local entry bounds or name.")
        if (descriptor) {
            zipCheck(crc == 0L && compressedSize == 0L && size == 0L, "Invalid ZIP descriptor header.")
            file.seek(nextOffset - descriptorSize)
            zipCheck(file.int() == DESCRIPTOR_SIGNATURE && file.unsignedInt() == entry.crc &&
                file.unsignedInt() == entry.compressedSize && file.unsignedInt() == entry.size,
                "ZIP descriptor disagrees with directory.")
        } else {
            zipCheck(crc == entry.crc && compressedSize == entry.compressedSize && size == entry.size,
                "ZIP sizes or CRC disagree.")
        }
    }

    private fun RandomAccessFile.int(): Int = Integer.reverseBytes(readInt())
    private fun RandomAccessFile.unsignedInt(): Long = int().toLong() and ZIP32_SENTINEL
    private fun RandomAccessFile.short(): Int = java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff
    private fun RandomAccessFile.name(length: Int): String =
        ByteArray(length).also { readFully(it) }.toString(Charsets.US_ASCII)
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.parser

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object IsoBmffReader {

    fun hasFileTypeBox(file: File, offset: Long, length: Long): Boolean {
        if (offset < 0L || length < MIN_BOX_HEADER_BYTES || offset > file.length() - length) {
            return false
        }
        RandomAccessFile(file, "r").use { input ->
            val scanEnd = minOf(offset + length, offset + MAX_FILE_TYPE_SCAN_BYTES)
            var position = offset
            var boxCount = 0
            while (position <= scanEnd - MIN_BOX_HEADER_BYTES && boxCount < MAX_BOX_COUNT) {
                val box = readBox(input, position, scanEnd) ?: return false
                if (box.type == TYPE_FTYP) return true
                position = box.end
                boxCount++
            }
        }
        return false
    }

    /** Reads Apple's QuickTime content identifier from an `mdta` metadata item. */
    fun readAppleContentIdentifier(file: File): String? = RandomAccessFile(file, "r").use { input ->
        val metadataBoxes = mutableListOf<Box>()
        collectMetadataBoxes(input, 0L, input.length(), metadataBoxes, depth = 0)
        metadataBoxes.firstNotNullOfOrNull { meta -> readIdentifierFromMetadata(input, meta) }
    }

    private fun collectMetadataBoxes(
        input: RandomAccessFile,
        start: Long,
        end: Long,
        destination: MutableList<Box>,
        depth: Int,
    ) {
        if (depth > MAX_CONTAINER_DEPTH) return
        var position = start
        var boxCount = 0
        while (position <= end - MIN_BOX_HEADER_BYTES && boxCount < MAX_BOX_COUNT) {
            val box = readBox(input, position, end) ?: return
            when (box.type) {
                TYPE_META -> destination += box
                TYPE_MOOV, TYPE_UDTA -> collectMetadataBoxes(
                    input,
                    box.contentStart,
                    box.end,
                    destination,
                    depth + 1,
                )
            }
            position = box.end
            boxCount++
        }
    }

    private fun readIdentifierFromMetadata(input: RandomAccessFile, meta: Box): String? {
        val childStart = meta.contentStart + FULL_BOX_HEADER_BYTES
        if (childStart > meta.end) return null
        val children = readChildren(input, childStart, meta.end)
        val keysBox = children.firstOrNull { it.type == TYPE_KEYS } ?: return null
        val itemListBox = children.firstOrNull { it.type == TYPE_ILST } ?: return null
        val keys = readMetadataKeys(input, keysBox)
        val keyIndex = keys.indexOf(APPLE_CONTENT_IDENTIFIER_KEY)
        if (keyIndex < 0) return null
        return readMetadataValue(input, itemListBox, keyIndex + 1)
    }

    private fun readMetadataKeys(input: RandomAccessFile, box: Box): List<String> {
        var position = box.contentStart + FULL_BOX_HEADER_BYTES
        if (position > box.end - INT_BYTES) return emptyList()
        input.seek(position)
        val count = input.readInt()
        if (count < 0 || count > MAX_METADATA_KEYS) return emptyList()
        position += INT_BYTES
        return buildList {
            repeat(count) {
                if (position > box.end - (INT_BYTES * 2L)) return@buildList
                input.seek(position)
                val entrySize = input.readInt().toLong() and UNSIGNED_INT_MASK
                input.readInt()
                if (entrySize < INT_BYTES * 2L || entrySize > MAX_METADATA_VALUE_BYTES ||
                    position > box.end - entrySize
                ) {
                    return@buildList
                }
                val valueSize = (entrySize - INT_BYTES * 2L).toInt()
                val value = ByteArray(valueSize)
                input.readFully(value)
                add(String(value, StandardCharsets.UTF_8))
                position += entrySize
            }
        }
    }

    private fun readMetadataValue(
        input: RandomAccessFile,
        itemListBox: Box,
        requestedIndex: Int,
    ): String? {
        val children = readChildren(input, itemListBox.contentStart, itemListBox.end)
        val item = children.firstOrNull { it.typeInt == requestedIndex } ?: return null
        val dataBox = readChildren(input, item.contentStart, item.end)
            .firstOrNull { it.type == TYPE_DATA } ?: return null
        val valueStart = dataBox.contentStart + DATA_BOX_PREFIX_BYTES
        val valueLength = dataBox.end - valueStart
        if (valueLength <= 0L || valueLength > MAX_METADATA_VALUE_BYTES) return null
        val value = ByteArray(valueLength.toInt())
        input.seek(valueStart)
        input.readFully(value)
        return String(value, StandardCharsets.UTF_8).trim('\u0000', ' ', '\n', '\r', '\t')
            .takeIf { it.isNotEmpty() }
    }

    private fun readChildren(input: RandomAccessFile, start: Long, end: Long): List<Box> {
        val result = mutableListOf<Box>()
        var position = start
        var boxCount = 0
        while (position <= end - MIN_BOX_HEADER_BYTES && boxCount < MAX_BOX_COUNT) {
            val box = readBox(input, position, end) ?: break
            result += box
            position = box.end
            boxCount++
        }
        return result
    }

    private fun readBox(input: RandomAccessFile, start: Long, containerEnd: Long): Box? {
        if (start < 0L || start > containerEnd - MIN_BOX_HEADER_BYTES) return null
        input.seek(start)
        val size32 = input.readInt().toLong() and UNSIGNED_INT_MASK
        val typeBytes = ByteArray(INT_BYTES)
        input.readFully(typeBytes)
        val typeInt = ByteBuffer.wrap(typeBytes).order(ByteOrder.BIG_ENDIAN).int
        var headerSize = MIN_BOX_HEADER_BYTES
        val size = when (size32) {
            0L -> containerEnd - start
            1L -> {
                if (start > containerEnd - EXTENDED_BOX_HEADER_BYTES) return null
                headerSize = EXTENDED_BOX_HEADER_BYTES
                input.readLong().takeIf { it >= EXTENDED_BOX_HEADER_BYTES } ?: return null
            }
            else -> size32
        }
        if (size < headerSize || start > containerEnd - size) return null
        return Box(
            contentStart = start + headerSize,
            end = start + size,
            type = String(typeBytes, StandardCharsets.ISO_8859_1),
            typeInt = typeInt,
        )
    }

    private data class Box(
        val contentStart: Long,
        val end: Long,
        val type: String,
        val typeInt: Int,
    )

    private const val TYPE_FTYP = "ftyp"
    private const val TYPE_MOOV = "moov"
    private const val TYPE_UDTA = "udta"
    private const val TYPE_META = "meta"
    private const val TYPE_KEYS = "keys"
    private const val TYPE_ILST = "ilst"
    private const val TYPE_DATA = "data"
    private const val APPLE_CONTENT_IDENTIFIER_KEY = "com.apple.quicktime.content.identifier"
    private const val INT_BYTES = 4
    private const val MIN_BOX_HEADER_BYTES = 8L
    private const val EXTENDED_BOX_HEADER_BYTES = 16L
    private const val FULL_BOX_HEADER_BYTES = 4L
    private const val DATA_BOX_PREFIX_BYTES = 8L
    private const val MAX_CONTAINER_DEPTH = 4
    private const val MAX_BOX_COUNT = 10_000
    private const val MAX_METADATA_KEYS = 1_024
    private const val MAX_METADATA_VALUE_BYTES = 64L * 1024L
    private const val MAX_FILE_TYPE_SCAN_BYTES = 64L * 1024L
    private const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL
}

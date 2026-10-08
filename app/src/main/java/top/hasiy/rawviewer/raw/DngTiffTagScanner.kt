/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class DngTagScanResult(val tags: Set<Int>)

/** Reads only TIFF/DNG tag identifiers and follows SubIFD links without loading tag payloads. */
internal object DngTiffTagScanner {
    private const val TIFF_MAGIC = 42
    private const val BIG_TIFF_MAGIC = 43
    private const val SUB_IFDS_TAG = 330
    private const val MAX_IFD_COUNT = 128
    private const val MAX_ENTRIES_PER_IFD = 65_536L

    fun scan(file: File): DngTagScanResult = RandomAccessFile(file, "r").use { input ->
        val header = readBuffer(input, 0L, 16)
        val byteOrder = when {
            header.get(0) == 'I'.code.toByte() && header.get(1) == 'I'.code.toByte() ->
                ByteOrder.LITTLE_ENDIAN
            header.get(0) == 'M'.code.toByte() && header.get(1) == 'M'.code.toByte() ->
                ByteOrder.BIG_ENDIAN
            else -> throw IOException("DNG has an invalid TIFF byte-order marker")
        }
        header.order(byteOrder)
        val layout = when (header.getShort(2).toInt() and 0xffff) {
            TIFF_MAGIC -> TiffLayout(
                isBigTiff = false,
                firstIfdOffset = header.getInt(4).toLong() and 0xffff_ffffL,
            )
            BIG_TIFF_MAGIC -> {
                if ((header.getShort(4).toInt() and 0xffff) != Long.SIZE_BYTES ||
                    (header.getShort(6).toInt() and 0xffff) != 0
                ) {
                    throw IOException("DNG uses an unsupported BigTIFF layout")
                }
                TiffLayout(isBigTiff = true, firstIfdOffset = checkedUInt64(header.getLong(8)))
            }
            else -> throw IOException("DNG has an invalid TIFF magic value")
        }

        val tags = mutableSetOf<Int>()
        val pendingOffsets = ArrayDeque<Long>().apply { add(layout.firstIfdOffset) }
        val visitedOffsets = mutableSetOf<Long>()
        while (pendingOffsets.isNotEmpty() && visitedOffsets.size < MAX_IFD_COUNT) {
            val offset = pendingOffsets.removeFirst()
            if (offset == 0L || !visitedOffsets.add(offset)) continue
            scanIfd(input, byteOrder, layout.isBigTiff, offset, tags, pendingOffsets)
        }
        DngTagScanResult(tags)
    }

    private fun scanIfd(
        input: RandomAccessFile,
        byteOrder: ByteOrder,
        isBigTiff: Boolean,
        offset: Long,
        tags: MutableSet<Int>,
        pendingOffsets: ArrayDeque<Long>,
    ) {
        val countSize = if (isBigTiff) Long.SIZE_BYTES else Short.SIZE_BYTES
        val entrySize = if (isBigTiff) 20 else 12
        val valueFieldOffset = if (isBigTiff) 12 else 8
        val inlineCapacity = if (isBigTiff) Long.SIZE_BYTES else Int.SIZE_BYTES
        val countBuffer = readBuffer(input, offset, countSize).order(byteOrder)
        val entryCount = if (isBigTiff) {
            checkedUInt64(countBuffer.long)
        } else {
            (countBuffer.short.toInt() and 0xffff).toLong()
        }
        if (entryCount > MAX_ENTRIES_PER_IFD) throw IOException("DNG IFD is too large")

        repeat(entryCount.toInt()) { index ->
            val entryOffset = offset + countSize + index.toLong() * entrySize
            val entry = readBuffer(input, entryOffset, entrySize).order(byteOrder)
            val tag = entry.short.toInt() and 0xffff
            val type = entry.short.toInt() and 0xffff
            val valueCount = if (isBigTiff) checkedUInt64(entry.long) else
                (entry.int.toLong() and 0xffff_ffffL)
            tags += tag
            if (tag == SUB_IFDS_TAG) {
                readIfdOffsets(
                    input = input,
                    entry = entry,
                    byteOrder = byteOrder,
                    type = type,
                    count = valueCount,
                    valueFieldOffset = valueFieldOffset,
                    inlineCapacity = inlineCapacity,
                    isBigTiff = isBigTiff,
                ).forEach(pendingOffsets::addLast)
            }
        }

        val nextOffsetPosition = offset + countSize + entryCount * entrySize
        val nextOffset = readUnsignedOffset(input, byteOrder, isBigTiff, nextOffsetPosition)
        if (nextOffset != 0L) pendingOffsets.addLast(nextOffset)
    }

    private fun readIfdOffsets(
        input: RandomAccessFile,
        entry: ByteBuffer,
        byteOrder: ByteOrder,
        type: Int,
        count: Long,
        valueFieldOffset: Int,
        inlineCapacity: Int,
        isBigTiff: Boolean,
    ): List<Long> {
        val elementSize = when (type) {
            4, 13 -> Int.SIZE_BYTES
            16, 18 -> Long.SIZE_BYTES
            else -> return emptyList()
        }
        if (count <= 0L || count > MAX_IFD_COUNT) return emptyList()
        val byteCount = count * elementSize
        val data = if (byteCount <= inlineCapacity) {
            entry.duplicate().order(byteOrder).apply { position(valueFieldOffset) }
        } else {
            val pointerBuffer = entry.duplicate().order(byteOrder).apply { position(valueFieldOffset) }
            val dataOffset = if (isBigTiff) {
                checkedUInt64(pointerBuffer.long)
            } else {
                pointerBuffer.int.toLong() and 0xffff_ffffL
            }
            readBuffer(input, dataOffset, byteCount.toInt()).order(byteOrder)
        }
        return List(count.toInt()) {
            if (elementSize == Int.SIZE_BYTES) data.int.toLong() and 0xffff_ffffL else
                checkedUInt64(data.long)
        }
    }

    private fun readUnsignedOffset(
        input: RandomAccessFile,
        byteOrder: ByteOrder,
        isBigTiff: Boolean,
        offset: Long,
    ): Long {
        val size = if (isBigTiff) Long.SIZE_BYTES else Int.SIZE_BYTES
        val buffer = readBuffer(input, offset, size).order(byteOrder)
        return if (isBigTiff) checkedUInt64(buffer.long) else
            buffer.int.toLong() and 0xffff_ffffL
    }

    private fun readBuffer(input: RandomAccessFile, offset: Long, size: Int): ByteBuffer {
        if (offset < 0L || size < 0 || offset > input.length() - size) {
            throw IOException("DNG contains an invalid TIFF offset")
        }
        val bytes = ByteArray(size)
        input.seek(offset)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes)
    }

    private fun checkedUInt64(value: Long): Long {
        if (value < 0L) throw IOException("DNG offset exceeds the supported range")
        return value
    }

    private data class TiffLayout(
        val isBigTiff: Boolean,
        val firstIfdOffset: Long,
    )
}

/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A reproducible uncompressed Bayer DNG, independent of private camera samples. */
object SyntheticDng {
    private data class Tag(val id: Int, val type: Int, val count: Int, val bytes: ByteArray)
    private fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putShort(it.toShort()) } }.array()
    private fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putInt) }.array()
    fun write(file: File, preview: ByteArray? = null, brokenRaw: Boolean = false) {
        val width = 768; val height = 512
        val tags = mutableListOf<Tag>()
        fun short(id: Int, vararg value: Int) { tags += Tag(id, 3, value.size, shorts(*value)) }
        fun long(id: Int, value: Int) { tags += Tag(id, 4, 1, longs(value)) }
        fun bytes(id: Int, vararg value: Int) { tags += Tag(id, 1, value.size, value.map(Int::toByte).toByteArray()) }
        fun string(id: Int, value: String) { val data = (value + "\u0000").toByteArray(); tags += Tag(id, 2, data.size, data) }
        long(254, 0); long(256, width); long(257, height); short(258, 16); short(259, 1); short(262, 32803)
        string(271, "DecodePhoto"); string(272, "Synthetic Bayer")
        long(273, 0); short(274, 1); short(277, 1); long(278, height); long(279, width * height * 2)
        short(284, 1); short(33421, 2, 2); bytes(33422, 0, 1, 1, 2)
        bytes(50706, 1, 4, 0, 0); bytes(50707, 1, 1, 0, 0); string(50708, "DecodePhoto Synthetic Bayer")
        bytes(50710, 0, 1, 2); short(50711, 1); long(50714, 0); long(50717, 65535)
        val matrix = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0..8) { putInt(if (i % 4 == 0) 1 else 0); putInt(1) }
        }.array()
        tags += Tag(50721, 10, 9, matrix)
        short(50778, 21)
        val sorted = tags.sortedBy { it.id }
        val thumbTags = if (preview == null) emptyList() else listOf(
            Tag(254, 4, 1, longs(1)), Tag(256, 4, 1, longs(32)), Tag(257, 4, 1, longs(16)),
            Tag(259, 3, 1, shorts(6)), Tag(262, 3, 1, shorts(2)), Tag(277, 3, 1, shorts(3)),
            Tag(513, 4, 1, longs(0)), Tag(514, 4, 1, longs(preview.size))).sortedBy { it.id }
        val firstEnd = 8 + 2 + sorted.size * 12 + 4
        val secondEnd = if (preview == null) firstEnd else firstEnd + 2 + thumbTags.size * 12 + 4
        val capacity = secondEnd + sorted.sumOf { if (it.bytes.size > 4) it.bytes.size + 1 else 0 } + width * height * 2 + (preview?.size ?: 0)
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0x4949.toShort()); buffer.putShort(42); buffer.putInt(8)
        var payload = secondEnd
        var stripOffsetField = 0; var jpegOffsetField = 0
        fun writeDirectory(directory: List<Tag>, next: Int) {
            buffer.putShort(directory.size.toShort())
            directory.forEach { tag ->
                buffer.putShort(tag.id.toShort()); buffer.putShort(tag.type.toShort()); buffer.putInt(tag.count)
                if (tag.id == 273) stripOffsetField = buffer.position()
                if (tag.id == 513) jpegOffsetField = buffer.position()
                if (tag.bytes.size <= 4) { buffer.put(tag.bytes); repeat(4 - tag.bytes.size) { buffer.put(0) } }
                else {
                    buffer.putInt(payload)
                    val position = buffer.position()
                    buffer.position(payload); buffer.put(tag.bytes)
                    if (tag.bytes.size % 2 == 1) buffer.put(0)
                    payload = buffer.position(); buffer.position(position)
                }
            }
            buffer.putInt(next)
        }
        writeDirectory(sorted, if (preview == null) 0 else firstEnd)
        if (preview != null) writeDirectory(thumbTags, 0)
        buffer.putInt(stripOffsetField, if (brokenRaw) capacity + 4096 else payload)
        buffer.position(payload)
        for (y in 0 until height) for (x in 0 until width) buffer.putShort((1000 + x * 40 + y * 10).toShort())
        if (preview != null) { buffer.putInt(jpegOffsetField, buffer.position()); buffer.put(preview) }
        file.writeBytes(buffer.array().copyOf(buffer.position()))
    }
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.parser

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.charset.StandardCharsets

object XmpPacketReader {

    fun read(file: File): String? {
        val exifValue = runCatching {
            ExifInterface(file).getAttribute(ExifInterface.TAG_XMP)
        }.getOrNull()
        if (!exifValue.isNullOrBlank()) return exifValue

        val bytes = ByteArray(MAX_XMP_SCAN_BYTES)
        val readBytes = file.inputStream().buffered().use { input ->
            var total = 0
            while (total < bytes.size) {
                val count = input.read(bytes, total, bytes.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        if (readBytes <= 0) return null
        val scanned = String(bytes, 0, readBytes, StandardCharsets.ISO_8859_1)
        val start = scanned.indexOf(XMP_START)
        val end = scanned.indexOf(XMP_END, startIndex = maxOf(start, 0))
        if (start < 0 || end < start) return null
        return scanned.substring(start, end + XMP_END.length)
    }

    private const val XMP_START = "<x:xmpmeta"
    private const val XMP_END = "</x:xmpmeta>"
    private const val MAX_XMP_SCAN_BYTES = 8 * 1024 * 1024
}

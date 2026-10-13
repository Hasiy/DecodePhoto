/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream

/** A valid JPEG/Exif thumbnail payload can be deliberately mislabeled RAW for fallback testing. */
object JpegWithThumbnail {
    fun write(file: File, jpeg: ByteArray) {
        val thumbnailOffset = 68
        val tiff = ByteBuffer.allocate(thumbnailOffset + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
        tiff.putShort(0x4949.toShort()); tiff.putShort(42); tiff.putInt(8)
        tiff.putShort(1); tiff.putShort(274); tiff.putShort(3); tiff.putInt(1); tiff.putInt(1)
        tiff.putInt(26)
        tiff.putShort(3)
        tiff.putShort(259); tiff.putShort(3); tiff.putInt(1); tiff.putInt(6)
        tiff.putShort(513); tiff.putShort(4); tiff.putInt(1); tiff.putInt(thumbnailOffset)
        tiff.putShort(514); tiff.putShort(4); tiff.putInt(1); tiff.putInt(jpeg.size)
        tiff.putInt(0); tiff.put(jpeg)
        val app1 = "Exif\u0000\u0000".toByteArray() + tiff.array()
        val length = app1.size + 2
        check(length < 65536)
        val output = ByteArrayOutputStream()
        output.write(jpeg, 0, 2)
        output.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length shr 8).toByte(), length.toByte()))
        output.write(app1); output.write(jpeg, 2, jpeg.size - 2)
        file.writeBytes(output.toByteArray())
    }
}

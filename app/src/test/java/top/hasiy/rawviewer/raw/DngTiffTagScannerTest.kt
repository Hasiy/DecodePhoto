/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngTiffTagScannerTest {

    @Test
    fun scan_classicTiffWithProfileGainTableMap_returnsTag() {
        val file = File.createTempFile("proraw-tag", ".dng")
        try {
            val data = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('I'.code.toByte())
                put('I'.code.toByte())
                putShort(42.toShort())
                putInt(8)
                putShort(1.toShort())
                putShort(PROFILE_GAIN_TABLE_MAP_TAG.toShort())
                putShort(TIFF_TYPE_UNDEFINED.toShort())
                putInt(1)
                putInt(0)
                putInt(0)
            }.array()
            file.writeBytes(data)

            val result = DngTiffTagScanner.scan(file)

            assertTrue(PROFILE_GAIN_TABLE_MAP_TAG in result.tags)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val PROFILE_GAIN_TABLE_MAP_TAG = 52_525
        const val TIFF_TYPE_UNDEFINED = 7
    }
}

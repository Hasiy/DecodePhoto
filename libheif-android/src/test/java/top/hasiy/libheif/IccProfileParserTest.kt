/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.libheif

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IccProfileParserTest {

    @Test
    fun parse_validDisplayP3Profile_returnsHeaderDescriptionAndWhitePoint() {
        val profile = IccProfileParser.parse(createDisplayP3Profile())

        assertNotNull(profile)
        requireNotNull(profile)
        assertEquals("Display P3", profile.description)
        assertEquals("4.0.0", profile.version)
        assertEquals("RGB", profile.colorSpace)
        assertEquals("XYZ", profile.profileConnectionSpace)
        assertEquals("mntr", profile.deviceClass)
        assertEquals("APPL", profile.platform)
        assertEquals("OPPO", profile.manufacturer)
        assertEquals("APPL", profile.creator)
        assertEquals("APPL", profile.cmm)
        assertEquals(0, profile.renderingIntent)
        assertEquals("[0.9642, 1.0000, 0.8249]", profile.whitePoint)
    }

    @Test
    fun parse_truncatedProfile_returnsNull() {
        assertEquals(null, IccProfileParser.parse(ByteArray(64)))
    }

    private fun createDisplayP3Profile(): ByteArray {
        val bytes = ByteArray(PROFILE_SIZE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(0, PROFILE_SIZE)
        writeSignature(bytes, 4, "APPL")
        bytes[8] = 4
        writeSignature(bytes, 12, "mntr")
        writeSignature(bytes, 16, "RGB ")
        writeSignature(bytes, 20, "XYZ ")
        writeSignature(bytes, 40, "APPL")
        writeSignature(bytes, 48, "OPPO")
        buffer.putInt(64, 0)
        writeSignature(bytes, 80, "APPL")

        buffer.putInt(128, 2)
        writeTagRecord(buffer, 132, "desc", DESCRIPTION_OFFSET, DESCRIPTION_SIZE)
        writeTagRecord(buffer, 144, "wtpt", WHITE_POINT_OFFSET, WHITE_POINT_SIZE)

        writeSignature(bytes, DESCRIPTION_OFFSET, "desc")
        val description = "Display P3\u0000".toByteArray(Charsets.US_ASCII)
        buffer.putInt(DESCRIPTION_OFFSET + 8, description.size)
        description.copyInto(bytes, DESCRIPTION_OFFSET + 12)

        writeSignature(bytes, WHITE_POINT_OFFSET, "XYZ ")
        buffer.putInt(WHITE_POINT_OFFSET + 8, fixedPoint(0.9642))
        buffer.putInt(WHITE_POINT_OFFSET + 12, fixedPoint(1.0))
        buffer.putInt(WHITE_POINT_OFFSET + 16, fixedPoint(0.8249))
        return bytes
    }

    private fun writeTagRecord(
        buffer: ByteBuffer,
        offset: Int,
        signature: String,
        dataOffset: Int,
        size: Int,
    ) {
        writeSignature(buffer.array(), offset, signature)
        buffer.putInt(offset + 4, dataOffset)
        buffer.putInt(offset + 8, size)
    }

    private fun writeSignature(bytes: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
    }

    private fun fixedPoint(value: Double): Int = (value * FIXED_POINT_SCALE).toInt()

    private companion object {
        const val PROFILE_SIZE = 256
        const val DESCRIPTION_OFFSET = 156
        const val DESCRIPTION_SIZE = 32
        const val WHITE_POINT_OFFSET = 188
        const val WHITE_POINT_SIZE = 20
        const val FIXED_POINT_SCALE = 65_536.0
    }
}

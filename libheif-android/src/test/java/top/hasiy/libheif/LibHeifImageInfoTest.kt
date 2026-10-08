/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.libheif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibHeifImageInfoTest {

    @Test
    fun hasBt2020HdrEncoding_bt2020Pq_returnsTrue() {
        assertTrue(imageInfo(colorPrimaries = 9, transferCharacteristics = 16).hasBt2020HdrEncoding)
    }

    @Test
    fun hasBt2020HdrEncoding_nonBt2020Hlg_returnsFalse() {
        assertFalse(imageInfo(colorPrimaries = 12, transferCharacteristics = 18).hasBt2020HdrEncoding)
    }

    @Test
    fun hasBt2020HdrEncoding_bt2020Sdr_returnsFalse() {
        assertFalse(imageInfo(colorPrimaries = 9, transferCharacteristics = 13).hasBt2020HdrEncoding)
    }

    private fun imageInfo(
        colorPrimaries: Int,
        transferCharacteristics: Int,
    ): LibHeifImageInfo = LibHeifImageInfo(
        width = 4,
        height = 4,
        bitDepth = 10,
        colorPrimaries = colorPrimaries,
        transferCharacteristics = transferCharacteristics,
        matrixCoefficients = 9,
        isFullRange = false,
        hasIccProfile = false,
        primaryItemId = 1,
        topLevelImageCount = 1,
        encodedColorSpace = HeifEncodedColorSpace.YCBCR,
        chromaFormat = HeifChromaFormat.YUV_420,
        tileColumns = 1,
        tileRows = 1,
        tileWidth = 4,
        tileHeight = 4,
        colorProfileType = HeifColorProfileType.NCLX,
        colorProfileSizeBytes = 0,
        hasAlphaChannel = false,
    )
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProRawClassifierTest {

    @Test
    fun classify_appleIPhoneWithGainTable_returnsAppleProRaw() {
        val result = ProRawClassifier.classify(
            metadata = listOf(
                ExifEntry("Make", "Apple"),
                ExifEntry("Model", "iPhone 16 Pro"),
            ),
            tags = setOf(PROFILE_GAIN_TABLE_MAP_TAG),
        )

        assertEquals(DngPhotoType.APPLE_PRORAW, result.type)
        assertTrue(ProRawEvidence.PROFILE_GAIN_TABLE_MAP in result.evidence)
    }

    @Test
    fun classify_explicitProRawName_returnsAppleProRaw() {
        val result = ProRawClassifier.classify(
            metadata = listOf(ExifEntry("Software", "Apple ProRAW")),
            tags = emptySet(),
        )

        assertEquals(DngPhotoType.APPLE_PRORAW, result.type)
    }

    @Test
    fun classify_appleIPhoneWithoutStrongTag_returnsPossibleAppleProRaw() {
        val result = ProRawClassifier.classify(
            metadata = listOf(
                ExifEntry("Make", "Apple"),
                ExifEntry("Model", "iPhone 15 Pro"),
            ),
            tags = emptySet(),
        )

        assertEquals(DngPhotoType.POSSIBLE_APPLE_PRORAW, result.type)
    }

    @Test
    fun classify_otherCamera_returnsStandardDng() {
        val result = ProRawClassifier.classify(
            metadata = listOf(
                ExifEntry("Make", "FUJIFILM"),
                ExifEntry("Model", "X-T5"),
            ),
            tags = setOf(PROFILE_GAIN_TABLE_MAP_TAG),
        )

        assertEquals(DngPhotoType.STANDARD_DNG, result.type)
    }

    private companion object {
        const val PROFILE_GAIN_TABLE_MAP_TAG = 52_525
    }
}

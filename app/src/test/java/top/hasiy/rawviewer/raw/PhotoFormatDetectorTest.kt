/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoFormatDetectorTest {

    @Test
    fun detect_heicMime_returnsHeic() {
        assertEquals(PhotoFormat.HEIC, PhotoFormatDetector.detect("image/heic", null))
    }

    @Test
    fun detect_heifSequenceMime_returnsHeif() {
        assertEquals(PhotoFormat.HEIF, PhotoFormatDetector.detect("image/heif-sequence", null))
    }

    @Test
    fun detect_uppercaseExtension_returnsMatchingFormat() {
        assertEquals(PhotoFormat.HEIC, PhotoFormatDetector.detect(null, "PHOTO.HEIC"))
        assertEquals(PhotoFormat.DNG, PhotoFormatDetector.detect(null, "RAW.DNG"))
    }

    @Test
    fun detect_motionPhotoStillFormats_returnsMatchingFormat() {
        assertEquals(PhotoFormat.JPEG, PhotoFormatDetector.detect("image/jpeg", null))
        assertEquals(PhotoFormat.JPEG, PhotoFormatDetector.detect(null, "MOTION_MP.JPEG"))
        assertEquals(PhotoFormat.AVIF, PhotoFormatDetector.detect("image/avif", null))
    }

    @Test
    fun detect_unknownInput_returnsNull() {
        assertNull(PhotoFormatDetector.detect("image/png", "photo.png"))
    }
}

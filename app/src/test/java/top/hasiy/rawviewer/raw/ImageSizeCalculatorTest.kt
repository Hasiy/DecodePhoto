/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageSizeCalculatorTest {

    @Test
    fun fitInside_sourceWithinLimit_returnsOriginalSize() {
        assertEquals(1920 to 1080, ImageSizeCalculator.fitInside(1920, 1080, 4096))
    }

    @Test
    fun fitInside_landscapeOverLimit_preservesAspectRatio() {
        assertEquals(4096 to 2731, ImageSizeCalculator.fitInside(6000, 4000, 4096))
    }

    @Test
    fun fitInside_portraitOverLimit_preservesAspectRatio() {
        assertEquals(2731 to 4096, ImageSizeCalculator.fitInside(4000, 6000, 4096))
    }

    @Test
    fun fitInside_invalidLimit_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageSizeCalculator.fitInside(100, 100, 0)
        }
    }
}

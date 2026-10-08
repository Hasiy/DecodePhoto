/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorConverterTest {

    @Test
    fun rgbToHsb_knownSample_returnsExpectedComponents() {
        val result = ColorConverter.rgbToHsb(RgbColor(47, 129, 247))

        assertEquals(215.4, result.hue, 0.1)
        assertEquals(81.0, result.saturation, 0.1)
        assertEquals(96.9, result.brightness, 0.1)
    }

    @Test
    fun hsbToRgb_knownSample_returnsOriginalRgb() {
        val result = ColorConverter.hsbToRgb(HsbColor(215.4, 81.0, 96.9))

        assertEquals(RgbColor(47, 129, 247), result)
    }

    @Test
    fun hsbToRgb_wrappedHue_returnsEquivalentColor() {
        val negativeHue = ColorConverter.hsbToRgb(HsbColor(-120.0, 100.0, 100.0))
        val positiveHue = ColorConverter.hsbToRgb(HsbColor(240.0, 100.0, 100.0))

        assertEquals(positiveHue, negativeHue)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rgbColor_outOfRangeChannel_throwsIllegalArgumentException() {
        RgbColor(256, 0, 0)
    }
}

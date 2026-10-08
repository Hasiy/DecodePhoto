/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import org.junit.Assert.assertEquals
import org.junit.Test

class RgbHistogramCalculatorTest {

    @Test
    fun calculateColors_knownColors_countsEachChannelBin() {
        val histogram = RgbHistogramCalculator.calculateColors(
            intArrayOf(
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFFFF.toInt(),
                0xFF000000.toInt(),
            ),
        )

        assertEquals(5, histogram.sampleCount)
        assertEquals(3, histogram.red[0])
        assertEquals(2, histogram.red[255])
        assertEquals(3, histogram.green[0])
        assertEquals(2, histogram.green[255])
        assertEquals(3, histogram.blue[0])
        assertEquals(2, histogram.blue[255])
        assertEquals(3, histogram.maxBinCount)
    }

    @Test
    fun calculateSampleStride_smallImage_returnsOne() {
        assertEquals(1, RgbHistogramCalculator.calculateSampleStride(100, 100, 10_000))
    }

    @Test
    fun calculateSampleStride_largeImage_boundsSamplingWork() {
        assertEquals(4, RgbHistogramCalculator.calculateSampleStride(4_000, 4_000, 1_000_000))
    }
}

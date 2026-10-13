/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer.raw

import org.junit.Assert.*
import org.junit.Test

class RawRenderOptionsTest {
    @Test fun construct_invalidExposure_rejectsInput() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -3.1f, 3.1f)) {
            assertTrue(runCatching { RawRenderOptions(exposureEv = value) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun construct_validBoundaries_preservesSettings() {
        assertEquals(-3f, RawRenderOptions(-3f, RawWhiteBalance.AUTO, false).exposureEv)
        assertFalse(RawRenderOptions(3f, RawWhiteBalance.DAYLIGHT, false).halfSize)
    }
}

/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.libheif

import org.junit.Assert.*
import org.junit.Test

class HdrToneMapMathTest {
    @Test fun pqNits_referenceCodes_matchesSt2084() {
        assertEquals(0.0, HdrToneMapMath.pqNits(0.0), 1e-8)
        assertEquals(100.0, HdrToneMapMath.pqNits(0.5080784215), 0.001)
        assertEquals(1000.0, HdrToneMapMath.pqNits(0.7518270962), 0.001)
        assertEquals(10000.0, HdrToneMapMath.pqNits(1.0), 0.001)
    }
    @Test fun hlgScene_referenceCodes_matchesBt2100() {
        assertEquals(0.0, HdrToneMapMath.hlgScene(0.0), 1e-8)
        assertEquals(1.0 / 12.0, HdrToneMapMath.hlgScene(0.5), 1e-8)
        assertEquals(1.0, HdrToneMapMath.hlgScene(1.0), 1e-6)
    }
    @Test fun shoulder_highlights_isMonotonicAndRetainsDarkTones() {
        assertEquals(0.18, HdrToneMapMath.shoulder(0.18), 1e-8)
        var previous = 0.0
        for (step in 0..1000) {
            val value = HdrToneMapMath.shoulder(step / 100.0)
            assertTrue(value >= previous && value <= 1.0)
            previous = value
        }
    }
    @Test fun toSrgb_neutralHdr_remainsNeutralAndFinite() {
        for (range in listOf(HeifDynamicRange.PQ, HeifDynamicRange.HLG)) {
            for (step in 0..100) {
                val encoded = step / 100.0
                val rgb = HdrToneMapMath.toSrgb(HdrToneMapMath.linearRgb(encoded, encoded, encoded, range))
                rgb.forEach { assertTrue(it.isFinite() && it in 0.0..1.0) }
                assertEquals(rgb[0], rgb[1], 1e-4)
                assertEquals(rgb[1], rgb[2], 1e-4)
            }
        }
    }
}

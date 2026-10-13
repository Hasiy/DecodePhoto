/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer.hdr

import org.junit.Assert.*
import org.junit.Test

class HdrLuminanceAnalyzerTest {
    @Test fun bin_blackWhiteAndHighlights_mapsLogarithmically() {
        assertEquals(0, HdrLuminanceAnalyzer.bin(0.0))
        assertEquals(32, HdrLuminanceAnalyzer.bin(1.0))
        assertEquals(36, HdrLuminanceAnalyzer.bin(2.0))
        assertEquals(63, HdrLuminanceAnalyzer.bin(10000.0))
    }
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HdrDisplayPolicyTest {

    @Test
    fun resolveHdrPresentation_defaultMode_keepsOriginalAndDefaultWindow() {
        val decision = resolveHdrPresentation(
            requestedMode = HdrComparisonMode.DEFAULT,
            contentType = HdrContentType.ULTRA_HDR_GAINMAP,
            capability = HDR_CAPABILITY,
        )

        assertEquals(HdrWindowMode.DEFAULT, decision.windowMode)
        assertEquals(HdrBitmapVariant.ORIGINAL, decision.bitmapVariant)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun resolveHdrPresentation_sdrMode_usesSdrBaseAndDefaultWindow() {
        val decision = resolveHdrPresentation(
            requestedMode = HdrComparisonMode.SDR,
            contentType = HdrContentType.ULTRA_HDR_GAINMAP,
            capability = HDR_CAPABILITY,
        )

        assertEquals(HdrWindowMode.DEFAULT, decision.windowMode)
        assertEquals(HdrBitmapVariant.SDR_BASE, decision.bitmapVariant)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun resolveHdrPresentation_hdrModeWithSupportedGainmap_enablesHdrWindow() {
        val decision = resolveHdrPresentation(
            requestedMode = HdrComparisonMode.HDR,
            contentType = HdrContentType.ULTRA_HDR_GAINMAP,
            capability = HDR_CAPABILITY,
        )

        assertEquals(HdrWindowMode.HDR, decision.windowMode)
        assertEquals(HdrBitmapVariant.ORIGINAL, decision.bitmapVariant)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun resolveHdrPresentation_hdrModeOnOldApi_fallsBackToSdr() {
        val decision = resolveHdrPresentation(
            requestedMode = HdrComparisonMode.HDR,
            contentType = HdrContentType.ULTRA_HDR_GAINMAP,
            capability = HDR_CAPABILITY.copy(apiSupportsHdrUi = false),
        )

        assertEquals(HdrWindowMode.DEFAULT, decision.windowMode)
        assertEquals(HdrBitmapVariant.SDR_BASE, decision.bitmapVariant)
        assertEquals(HdrFallbackReason.API_LEVEL_UNSUPPORTED, decision.fallbackReason)
    }

    @Test
    fun resolveHdrPresentation_hdrModeOnSdrDisplay_fallsBackToSdr() {
        val decision = resolveHdrPresentation(
            requestedMode = HdrComparisonMode.HDR,
            contentType = HdrContentType.ULTRA_HDR_GAINMAP,
            capability = HDR_CAPABILITY.copy(displaySupportsHdr = false),
        )

        assertEquals(HdrWindowMode.DEFAULT, decision.windowMode)
        assertEquals(HdrBitmapVariant.SDR_BASE, decision.bitmapVariant)
        assertEquals(HdrFallbackReason.DISPLAY_NOT_HDR, decision.fallbackReason)
    }

    @Test
    fun resolveHdrPresentation_hdrModeWithoutHdrMetadata_doesNotGuessHdr() {
        val decision = resolveHdrPresentation(
            requestedMode = HdrComparisonMode.HDR,
            contentType = HdrContentType.SDR_OR_UNIDENTIFIED,
            capability = HDR_CAPABILITY,
        )

        assertEquals(HdrWindowMode.DEFAULT, decision.windowMode)
        assertEquals(HdrBitmapVariant.ORIGINAL, decision.bitmapVariant)
        assertEquals(HdrFallbackReason.CONTENT_NOT_HDR, decision.fallbackReason)
    }

    @Test
    fun resolveHdrPresentation_hdrModeWithPqContent_enablesHdrWindow() {
        val decision = resolveHdrPresentation(
            requestedMode = HdrComparisonMode.HDR,
            contentType = HdrContentType.PQ_10_BIT,
            capability = HDR_CAPABILITY,
        )

        assertEquals(HdrWindowMode.HDR, decision.windowMode)
        assertEquals(HdrBitmapVariant.ORIGINAL, decision.bitmapVariant)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun resolveHdrPresentation_hdrModeWithHlgContent_enablesHdrWindow() {
        val decision = resolveHdrPresentation(
            requestedMode = HdrComparisonMode.HDR,
            contentType = HdrContentType.HLG_10_BIT,
            capability = HDR_CAPABILITY,
        )

        assertEquals(HdrWindowMode.HDR, decision.windowMode)
        assertEquals(HdrBitmapVariant.ORIGINAL, decision.bitmapVariant)
        assertNull(decision.fallbackReason)
    }

    private companion object {
        val HDR_CAPABILITY = HdrDisplayCapability(
            apiSupportsHdrUi = true,
            displaySupportsHdr = true,
            isWideColorGamut = true,
            hdrSdrRatio = 3.0f,
            supportedHdrTypes = listOf("HDR10"),
        )
    }
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.hdr

data class HdrDisplayCapability(
    val apiSupportsHdrUi: Boolean,
    val displaySupportsHdr: Boolean,
    val isWideColorGamut: Boolean,
    val hdrSdrRatio: Float?,
    val supportedHdrTypes: List<String>,
)

enum class HdrWindowMode {
    DEFAULT,
    HDR,
}

enum class HdrBitmapVariant {
    ORIGINAL,
    SDR_BASE,
}

enum class HdrFallbackReason {
    API_LEVEL_UNSUPPORTED,
    DISPLAY_NOT_HDR,
    CONTENT_NOT_HDR,
}

data class HdrPresentationDecision(
    val windowMode: HdrWindowMode,
    val bitmapVariant: HdrBitmapVariant,
    val fallbackReason: HdrFallbackReason? = null,
)

/**
 * Resolves the requested comparison mode into an actual Window mode and bitmap rendition.
 *
 * @param requestedMode mode selected by the user.
 * @param contentType detected dynamic-range type of the decoded image.
 * @param capability API and current display capabilities.
 * @return a deterministic presentation decision with an explicit fallback reason when needed.
 */
fun resolveHdrPresentation(
    requestedMode: HdrComparisonMode,
    contentType: HdrContentType,
    capability: HdrDisplayCapability,
): HdrPresentationDecision = when (requestedMode) {
    HdrComparisonMode.DEFAULT -> HdrPresentationDecision(
        windowMode = HdrWindowMode.DEFAULT,
        bitmapVariant = HdrBitmapVariant.ORIGINAL,
    )

    HdrComparisonMode.SDR -> HdrPresentationDecision(
        windowMode = HdrWindowMode.DEFAULT,
        bitmapVariant = HdrBitmapVariant.SDR_BASE,
    )

    HdrComparisonMode.HDR -> when {
        !capability.apiSupportsHdrUi -> HdrPresentationDecision(
            windowMode = HdrWindowMode.DEFAULT,
            bitmapVariant = HdrBitmapVariant.SDR_BASE,
            fallbackReason = HdrFallbackReason.API_LEVEL_UNSUPPORTED,
        )

        contentType == HdrContentType.SDR_OR_UNIDENTIFIED -> HdrPresentationDecision(
            windowMode = HdrWindowMode.DEFAULT,
            bitmapVariant = HdrBitmapVariant.ORIGINAL,
            fallbackReason = HdrFallbackReason.CONTENT_NOT_HDR,
        )

        !capability.displaySupportsHdr -> HdrPresentationDecision(
            windowMode = HdrWindowMode.DEFAULT,
            bitmapVariant = HdrBitmapVariant.SDR_BASE,
            fallbackReason = HdrFallbackReason.DISPLAY_NOT_HDR,
        )

        else -> HdrPresentationDecision(
            windowMode = HdrWindowMode.HDR,
            bitmapVariant = HdrBitmapVariant.ORIGINAL,
        )
    }
}

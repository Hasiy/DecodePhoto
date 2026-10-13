/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.hdr

import android.graphics.Bitmap

enum class HdrComparisonMode {
    DEFAULT,
    HDR,
    SDR,
}

enum class HdrContentType {
    ULTRA_HDR_GAINMAP,
    HLG_10_BIT,
    PQ_10_BIT,
    SDR_OR_UNIDENTIFIED,
}

data class GainmapMetadata(
    val width: Int,
    val height: Int,
    val channelCount: Int,
    val ratioMin: List<Float>,
    val ratioMax: List<Float>,
    val gamma: List<Float>,
    val epsilonSdr: List<Float>,
    val epsilonHdr: List<Float>,
    val displayRatioForFullHdr: Float,
    val minDisplayRatioForHdrTransition: Float,
)

data class HdrComparisonImage(
    val originalBitmap: Bitmap,
    val sdrBitmap: Bitmap,
    val contentType: HdrContentType,
    val colorSpaceName: String,
    val bitmapConfigName: String,
    val gainmapMetadata: GainmapMetadata?,
    val luminance: HdrLuminanceHistogram? = null,
    val gainmapPreview: Bitmap? = null,
)

sealed interface HdrComparisonUiState {
    data object Empty : HdrComparisonUiState

    data object Loading : HdrComparisonUiState

    data class Success(val image: HdrComparisonImage) : HdrComparisonUiState

    data class Error(val reason: HdrComparisonError) : HdrComparisonUiState
}

enum class HdrComparisonError {
    UNSUPPORTED_IMAGE,
    INPUT_TOO_LARGE,
    DIMENSIONS_TOO_LARGE,
    OUT_OF_MEMORY,
    ACCESS_DENIED,
    UNKNOWN,
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.graphics.Bitmap
import android.os.Build

sealed interface RawDecodeResult {
    data class Success(
        val bitmap: Bitmap,
        val source: Source,
    ) : RawDecodeResult

    data class Failure(
        val reason: Reason,
        val cause: Throwable? = null,
    ) : RawDecodeResult

    enum class Source {
        EMBEDDED_PREVIEW,
        ANDROID_IMAGE_DECODER,
        ANDROID_HEIF_DECODER,
        LIBRAW,
        LIBHEIF,
    }

    enum class Reason {
        UNSUPPORTED_FORMAT,
        UNSUPPORTED_API_LEVEL,
        MISSING_EMBEDDED_PREVIEW,
        NON_SEEKABLE_INPUT,
        CORRUPTED_FILE,
        OUT_OF_MEMORY,
        CANCELLED,
        UNKNOWN,
    }
}

enum class PhotoFormat(
    val suffix: String,
    val isRaw: Boolean,
    val isHeif: Boolean,
    val supportsNativeDecoder: Boolean,
) {
    DNG(".dng", true, false, true),
    RAF(".raf", true, false, false),
    HEIF(".heif", false, true, true),
    HEIC(".heic", false, true, true),
    JPEG(".jpg", false, false, false),
    AVIF(".avif", false, false, false),
}

data class ExifEntry(
    val tag: String,
    val value: String,
)

enum class PhotoDynamicRange {
    SDR,
    HLG,
    PQ,
    ULTRA_HDR_GAINMAP,
    UNKNOWN_HDR;

    val isHdr: Boolean
        get() = this != SDR
}

data class DecodedColorInfo(
    val sourceBitDepth: Int?,
    val bitmapConfig: String,
    val colorSpaceName: String,
    val dynamicRange: PhotoDynamicRange,
    val isHdrDisplayReady: Boolean,
    val usedSdrFallback: Boolean = false,
) {
    companion object {
        fun fromBitmap(bitmap: Bitmap, sourceBitDepth: Int? = null): DecodedColorInfo {
            val colorSpaceName = bitmap.colorSpace?.name.orEmpty()
            val dynamicRange = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    bitmap.hasGainmap() -> PhotoDynamicRange.ULTRA_HDR_GAINMAP
                colorSpaceName.contains("Perceptual Quantizer", ignoreCase = true) ||
                    colorSpaceName.contains("PQ", ignoreCase = true) -> PhotoDynamicRange.PQ
                colorSpaceName.contains("Hybrid Log Gamma", ignoreCase = true) ||
                    colorSpaceName.contains("HLG", ignoreCase = true) -> PhotoDynamicRange.HLG
                else -> PhotoDynamicRange.SDR
            }
            val configName = bitmap.config?.name ?: UNKNOWN_VALUE
            val ready = dynamicRange == PhotoDynamicRange.ULTRA_HDR_GAINMAP ||
                dynamicRange.isHdr && bitmap.config == Bitmap.Config.RGBA_F16
            return DecodedColorInfo(
                sourceBitDepth = sourceBitDepth,
                bitmapConfig = configName,
                colorSpaceName = colorSpaceName.ifBlank { UNKNOWN_VALUE },
                dynamicRange = dynamicRange,
                isHdrDisplayReady = ready,
            )
        }

        private const val UNKNOWN_VALUE = "Unknown"
    }
}

data class PhotoDecodeOutput(
    val bitmap: Bitmap,
    val colorInfo: DecodedColorInfo,
)

class RawNativeException(message: String) : IllegalStateException(message)

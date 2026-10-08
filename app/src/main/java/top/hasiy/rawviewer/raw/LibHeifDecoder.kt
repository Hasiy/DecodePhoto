/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hasiy.libheif.HeifDynamicRange
import top.hasiy.libheif.LibHeifContainerInfo
import top.hasiy.libheif.LibHeifNativeDecoder

class LibHeifDecoder(private val applicationContext: Context) {
    private val nativeDecoder = LibHeifNativeDecoder()

    /** Reads HEIF container metadata from a private seekable copy of the selected document. */
    suspend fun inspect(uri: Uri, format: PhotoFormat): LibHeifContainerInfo {
        require(format.isHeif) { "libheif inspection only accepts HEIF/HEIC formats" }
        val cached = withContext(Dispatchers.IO) {
            PhotoInputResolver.copyToCache(applicationContext, uri, format.suffix)
        }
        return try {
            withContext(Dispatchers.Default) { nativeDecoder.inspect(cached) }
        } finally {
            PhotoInputResolver.deleteQuietly(cached)
        }
    }

    suspend fun decode(
        uri: Uri,
        format: PhotoFormat,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    ): PhotoDecodeOutput {
        require(format.isHeif) { "libheif only accepts HEIF/HEIC formats" }
        require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }
        val cached = withContext(Dispatchers.IO) {
            PhotoInputResolver.copyToCache(applicationContext, uri, format.suffix)
        }
        return try {
            withContext(Dispatchers.Default) {
                val decoded = nativeDecoder.decode(cached, maxLongEdge)
                val dynamicRange = when (decoded.sourceInfo.dynamicRange) {
                    HeifDynamicRange.SDR -> PhotoDynamicRange.SDR
                    HeifDynamicRange.HLG -> PhotoDynamicRange.HLG
                    HeifDynamicRange.PQ -> PhotoDynamicRange.PQ
                }
                PhotoDecodeOutput(
                    bitmap = decoded.bitmap,
                    colorInfo = DecodedColorInfo(
                        sourceBitDepth = decoded.sourceInfo.bitDepth,
                        bitmapConfig = decoded.bitmap.config?.name ?: UNKNOWN_VALUE,
                        colorSpaceName = decoded.bitmap.colorSpace?.name ?: UNKNOWN_VALUE,
                        dynamicRange = dynamicRange,
                        isHdrDisplayReady = decoded.usesHdrColorSpace,
                        usedSdrFallback = decoded.usedSdrFallback,
                    ),
                )
            }
        } finally {
            PhotoInputResolver.deleteQuietly(cached)
        }
    }

    private companion object {
        const val DEFAULT_MAX_LONG_EDGE = 4096
        const val UNKNOWN_VALUE = "Unknown"
    }
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.hasiy.libheif.HeifDynamicRange
import top.hasiy.libheif.LibHeifContainerInfo
import top.hasiy.libheif.LibHeifNativeDecoder

class LibHeifDecoder(private val applicationContext: Context) {
    private val nativeDecoder = LibHeifNativeDecoder()

    /** Reads HEIF container metadata from a private seekable copy of the selected document. */
    suspend fun inspect(uri: Uri, format: PhotoFormat): LibHeifContainerInfo {
        require(format.isHeif) { "libheif inspection only accepts HEIF/HEIC formats" }
        val input = PhotoInputResolver.acquire(applicationContext, uri, format.suffix)
        val cached = input.file
        return try {
            withContext(Dispatchers.Default) { nativeDecoder.inspect(cached) }
        } finally {
            input.close()
        }
    }

    suspend fun decode(
        uri: Uri,
        format: PhotoFormat,
        maxLongEdge: Int = PhotoMemoryPolicy.previewLongEdge(applicationContext),
        forceSdr: Boolean = false,
    ): PhotoDecodeOutput {
        require(format.isHeif) { "libheif only accepts HEIF/HEIC formats" }
        require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }
        val input = PhotoInputResolver.acquire(applicationContext, uri, format.suffix)
        val cached = input.file
        var owned: android.graphics.Bitmap? = null
        return try {
            withContext(Dispatchers.Default) {
                val workerContext = currentCoroutineContext()
                val decoded = NativeDecodeBudget.run {
                    workerContext.ensureActive()
                    nativeDecoder.decode(cached, maxLongEdge, forceSdr) { workerContext.ensureActive() }
                }
                owned = decoded.bitmap
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
        } catch (error: Throwable) {
            owned?.recycle()
            throw error
        } finally {
            input.close()
        }
    }

    private companion object {
        const val DEFAULT_MAX_LONG_EDGE = 4096
        const val UNKNOWN_VALUE = "Unknown"
    }
}

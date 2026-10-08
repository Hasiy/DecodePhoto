/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import top.hasiy.libraw.LibRawNativeDecoder

class LibRawDecoder(
    private val applicationContext: Context,
) : Closeable {

    private val nativeDecoder = LibRawNativeDecoder()
    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libraw-decoder")
    }
    private val nextRequestId = AtomicLong(1L)
    private val activeRequestIds = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Uses LibRaw to render a DNG or RAF into an 8-bit sRGB Bitmap.
     *
     * @param uri selected RAW URI.
     * @param format format used for the private temporary-file suffix.
     * @param halfSize whether LibRaw should use half-size processing.
     * @param maxLongEdge maximum returned width or height in pixels.
     * @return the rendered and size-limited Bitmap.
     */
    suspend fun decode(
        uri: Uri,
        format: PhotoFormat,
        halfSize: Boolean = true,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    ): Bitmap {
        require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }
        require(format.isRaw) { "LibRaw only accepts RAW formats" }
        val cachedRaw = withContext(Dispatchers.IO) {
            PhotoInputResolver.copyToCache(applicationContext, uri, format.suffix)
        }
        return try {
            val bitmap = decodeCachedFile(cachedRaw.absolutePath, halfSize)
            resizeIfNeeded(bitmap, maxLongEdge)
        } finally {
            PhotoInputResolver.deleteQuietly(cachedRaw)
        }
    }

    override fun close() {
        activeRequestIds.forEach(nativeDecoder::cancel)
        decodeExecutor.shutdownNow()
    }

    private suspend fun decodeCachedFile(
        path: String,
        halfSize: Boolean,
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        val requestId = nextRequestId.getAndIncrement()
        activeRequestIds += requestId
        nativeDecoder.prepareRequest(requestId)
        continuation.invokeOnCancellation { nativeDecoder.cancel(requestId) }

        decodeExecutor.execute {
            try {
                val bitmap = nativeDecoder.decode(File(path), halfSize, requestId)
                if (continuation.isActive) {
                    continuation.resume(bitmap)
                } else {
                    bitmap.recycle()
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                activeRequestIds -= requestId
            }
        }
    }

    private fun resizeIfNeeded(source: Bitmap, maxLongEdge: Int): Bitmap {
        val targetSize = ImageSizeCalculator.fitInside(
            width = source.width,
            height = source.height,
            maxLongEdge = maxLongEdge,
        )
        if (targetSize.first == source.width && targetSize.second == source.height) {
            return source
        }

        return Bitmap.createScaledBitmap(
            source,
            targetSize.first,
            targetSize.second,
            true,
        ).also { resized ->
            if (resized !== source) source.recycle()
        }
    }

    private companion object {
        const val DEFAULT_MAX_LONG_EDGE = 4096
    }
}

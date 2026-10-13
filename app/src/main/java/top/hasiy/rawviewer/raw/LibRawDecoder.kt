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
import android.util.Log

class LibRawDecoder(
    private val applicationContext: Context,
) : Closeable {

    private val nativeDecoder = LibRawNativeDecoder()
    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libraw-decoder")
    }
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
        options: RawRenderOptions = RawRenderOptions(),
        maxLongEdge: Int = PhotoMemoryPolicy.previewLongEdge(applicationContext),
    ): Bitmap {
        require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }
        require(format.isRaw) { "LibRaw only accepts RAW formats" }
        val input = PhotoInputResolver.acquire(applicationContext, uri, format.suffix)
        return decodeCachedFile(input, options, maxLongEdge)
    }

    override fun close() {
        activeRequestIds.forEach(nativeDecoder::cancel)
        decodeExecutor.shutdown()
    }

    private suspend fun decodeCachedFile(
        input: PhotoInputResolver.Lease,
        options: RawRenderOptions,
        maxLongEdge: Int,
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        val requestId = NEXT_REQUEST_ID.getAndIncrement()
        activeRequestIds += requestId
        try { nativeDecoder.prepareRequest(requestId) }
        catch (error: Throwable) { input.close(); activeRequestIds -= requestId; throw error }
        continuation.invokeOnCancellation { nativeDecoder.cancel(requestId) }

        try { decodeExecutor.execute {
            try {
                val bitmap = NativeDecodeBudget.run {
                    nativeDecoder.decode(input.file, options.halfSize, requestId,
                        maxLongEdge, options.exposureEv, options.whiteBalance.ordinal)
                }
                continuation.resume(bitmap, onCancellation = { _, value, _ -> value.recycle() })
            } catch (error: Throwable) {
                Log.w("LibRawDecoder", "RAW worker failed: ${error.javaClass.simpleName}")
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                input.close()
                activeRequestIds -= requestId
            }
        } } catch (error: Exception) {
            Log.w("LibRawDecoder", "RAW scheduling failed: ${error.javaClass.simpleName}")
            input.close()
            activeRequestIds -= requestId
            continuation.resumeWithException(error)
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
        val NEXT_REQUEST_ID = AtomicLong(1L)
    }
}

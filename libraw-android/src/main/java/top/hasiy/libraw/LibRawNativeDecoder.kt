/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.libraw

import android.graphics.Bitmap
import java.io.File

/** Decodes DNG and RAF files through the bundled LibRaw native library. */
class LibRawNativeDecoder {

    /**
     * Creates the native cancellation state for a decode request.
     *
     * @param requestId positive identifier that remains unique until decoding finishes.
     */
    fun prepareRequest(requestId: Long) {
        require(requestId > 0L) { "requestId must be greater than 0" }
        nativePrepareRequest(requestId)
    }

    /**
     * Requests cancellation of a prepared or currently running decode.
     *
     * @param requestId identifier previously passed to [prepareRequest].
     */
    fun cancel(requestId: Long) {
        require(requestId > 0L) { "requestId must be greater than 0" }
        nativeCancel(requestId)
    }

    /**
     * Decodes a local RAW file into an 8-bit sRGB ARGB_8888 bitmap.
     *
     * @param file readable local DNG or RAF file.
     * @param halfSize whether LibRaw should use half-size processing.
     * @param requestId identifier previously passed to [prepareRequest].
     * @return the rendered bitmap.
     */
    fun decode(
        file: File,
        halfSize: Boolean,
        requestId: Long,
        maxLongEdge: Int = 4096,
        exposureEv: Float = 0f,
        whiteBalance: Int = 0,
    ): Bitmap {
        require(file.isFile) { "RAW input must be a readable local file" }
        require(requestId > 0L) { "requestId must be greater than 0" }
        require(maxLongEdge > 0 && exposureEv.isFinite() && exposureEv in -3f..3f)
        require(whiteBalance in 0..2)
        return nativeDecodeFile(file.absolutePath, halfSize, requestId, maxLongEdge, exposureEv, whiteBalance)
            ?: throw LibRawException("LibRaw returned no image")
    }

    private external fun nativePrepareRequest(requestId: Long)

    private external fun nativeCancel(requestId: Long)

    private external fun nativeDecodeFile(
        path: String,
        halfSize: Boolean,
        requestId: Long,
        maxLongEdge: Int,
        exposureEv: Float,
        whiteBalance: Int,
    ): Bitmap?

    companion object {
        init {
            System.loadLibrary("libraw_android_bridge")
        }
    }
}

/** Reports a native LibRaw parsing, processing, cancellation, or bitmap access failure. */
class LibRawException(message: String) : IllegalStateException(message)

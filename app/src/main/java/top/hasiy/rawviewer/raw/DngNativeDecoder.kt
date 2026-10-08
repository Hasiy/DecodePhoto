/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidNativeImageDecoder(
    private val applicationContext: Context,
) {

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Uses Android's API 30+ NDK image decoder to render a DNG into ARGB_8888.
     *
     * @param uri DNG content URI.
     * @param maxLongEdge maximum output width or height in pixels.
     * @return a decoded Bitmap whose longest edge does not exceed the requested limit.
     */
    suspend fun decode(
        uri: Uri,
        format: PhotoFormat,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    ): Bitmap {
        check(isSupported) { "Android native image decoding requires API 30 or newer" }
        require(format == PhotoFormat.DNG || !format.isRaw) { "Unsupported native image format" }
        require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }
        ensureNativeLibraryLoaded()

        val directResult = withContext(Dispatchers.Default) {
            applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                decodeFromDescriptor(descriptor, maxLongEdge)
            }
        }
        if (directResult != null) return directResult

        val cachedRaw = withContext(Dispatchers.IO) {
            PhotoInputResolver.copyToCache(applicationContext, uri, format.suffix)
        }
        return try {
            withContext(Dispatchers.Default) {
                ParcelFileDescriptor.open(cachedRaw, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    requireNotNull(decodeFromDescriptor(descriptor, maxLongEdge)) {
                        nativeGetLastError().ifBlank { "Unable to decode cached photo" }
                    }
                }
            }
        } finally {
            PhotoInputResolver.deleteQuietly(cachedRaw)
        }
    }

    private fun decodeFromDescriptor(
        descriptor: ParcelFileDescriptor,
        maxLongEdge: Int,
    ): Bitmap? {
        val sourceSize = nativeReadSize(descriptor.fd) ?: return null
        if (sourceSize.size != DIMENSION_COUNT) return null

        val targetSize = ImageSizeCalculator.fitInside(
            width = sourceSize[0],
            height = sourceSize[1],
            maxLongEdge = maxLongEdge,
        )
        val bitmap = Bitmap.createBitmap(
            targetSize.first,
            targetSize.second,
            Bitmap.Config.ARGB_8888,
        )
        val result = nativeDecode(descriptor.fd, bitmap)
        if (result == NATIVE_SUCCESS) return bitmap

        bitmap.recycle()
        throw RawNativeException(
            nativeGetLastError().ifBlank { "Android native decoding failed with result=$result" },
        )
    }

    private external fun nativeReadSize(fd: Int): IntArray?

    private external fun nativeDecode(fd: Int, target: Bitmap): Int

    private external fun nativeGetLastError(): String

    private companion object {
        const val DEFAULT_MAX_LONG_EDGE = 4096
        const val DIMENSION_COUNT = 2
        const val NATIVE_SUCCESS = 0

        @Volatile
        private var isNativeLibraryLoaded = false

        @Synchronized
        fun ensureNativeLibraryLoaded() {
            if (!isNativeLibraryLoaded) {
                System.loadLibrary("android_image_decoder")
                isNativeLibraryLoaded = true
            }
        }
    }
}

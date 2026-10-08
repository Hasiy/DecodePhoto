/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class EmbeddedPreviewDecoder(
    private val applicationContext: Context,
) {

    /**
     * Reads a RAW file's embedded JPEG and applies its EXIF orientation.
     *
     * @param uri DNG or RAF content URI.
     * @return an oriented Bitmap, or null when no embedded preview is available.
     */
    suspend fun decode(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        applicationContext.contentResolver
            .openFileDescriptor(uri, "r")
            ?.use { descriptor ->
                coroutineContext.ensureActive()
                val exif = ExifInterface(descriptor.fileDescriptor)
                val jpegBytes = exif.thumbnailBytes ?: return@use null
                coroutineContext.ensureActive()

                val decoded = BitmapFactory.decodeByteArray(
                    jpegBytes,
                    0,
                    jpegBytes.size,
                ) ?: return@use null

                ExifOrientationTransformer.apply(
                    source = decoded,
                    rotationDegrees = exif.rotationDegrees,
                    isFlipped = exif.isFlipped,
                )
            }
    }

}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class PhotoFormatResolver(
    private val applicationContext: Context,
) {

    /**
     * Uses both MIME type and the provider display name to identify the selected RAW format.
     *
     * @param uri URI returned by the Storage Access Framework.
     * @return the supported RAW format, or null when it cannot be identified safely.
     */
    suspend fun resolve(uri: Uri): PhotoFormat? = withContext(Dispatchers.IO) {
        val mimeType = applicationContext.contentResolver.getType(uri)
            ?.lowercase(Locale.ROOT)
        PhotoFormatDetector.detect(mimeType, null)?.let { return@withContext it }

        val displayName = applicationContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            }
        }?.lowercase(Locale.ROOT)

        PhotoFormatDetector.detect(mimeType, displayName)
    }
}

internal object PhotoFormatDetector {
    fun detect(mimeType: String?, displayName: String?): PhotoFormat? {
        return when (mimeType?.lowercase(Locale.ROOT)) {
            "image/dng", "image/x-adobe-dng" -> PhotoFormat.DNG
            "image/raf", "image/x-fuji-raf" -> PhotoFormat.RAF
            "image/heif", "image/heif-sequence" -> PhotoFormat.HEIF
            "image/heic", "image/heic-sequence" -> PhotoFormat.HEIC
            "image/jpeg" -> PhotoFormat.JPEG
            "image/avif" -> PhotoFormat.AVIF
            else -> when {
                displayName?.lowercase(Locale.ROOT)?.endsWith(PhotoFormat.DNG.suffix) == true ->
                    PhotoFormat.DNG
                displayName?.lowercase(Locale.ROOT)?.endsWith(PhotoFormat.RAF.suffix) == true ->
                    PhotoFormat.RAF
                displayName?.lowercase(Locale.ROOT)?.endsWith(PhotoFormat.HEIF.suffix) == true ->
                    PhotoFormat.HEIF
                displayName?.lowercase(Locale.ROOT)?.endsWith(PhotoFormat.HEIC.suffix) == true ->
                    PhotoFormat.HEIC
                displayName?.lowercase(Locale.ROOT)?.let { name ->
                    name.endsWith(PhotoFormat.JPEG.suffix) || name.endsWith(".jpeg")
                } == true -> PhotoFormat.JPEG
                displayName?.lowercase(Locale.ROOT)?.endsWith(PhotoFormat.AVIF.suffix) == true ->
                    PhotoFormat.AVIF
                else -> null
            }
        }
    }
}

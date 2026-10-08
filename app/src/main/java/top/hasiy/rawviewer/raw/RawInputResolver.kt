/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException

object PhotoInputResolver {

    private const val TAG = "PhotoInputResolver"
    private const val COPY_BUFFER_SIZE_BYTES = 256 * 1024
    const val MAX_PHOTO_FILE_BYTES = 200L * 1024L * 1024L

    /**
     * Copies URI content into application-private cache for native code that needs a path.
     *
     * @param context application Context.
     * @param uri Storage Access Framework or MediaStore URI.
     * @param suffix trusted suffix for the temporary file.
     * @return a private temporary file that the caller must delete.
     */
    fun copyToCache(
        context: Context,
        uri: Uri,
        suffix: String,
    ): File {
        require(suffix in SUPPORTED_SUFFIXES) { "Unsupported photo suffix" }

        val target = File.createTempFile("photo_input_", suffix, context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open selected RAW file" }
                target.outputStream().buffered(COPY_BUFFER_SIZE_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
                    var totalBytes = 0L
                    while (true) {
                        val readBytes = input.read(buffer)
                        if (readBytes < 0) break
                        totalBytes += readBytes
                        if (totalBytes > MAX_PHOTO_FILE_BYTES) {
                            throw IOException("Photo input exceeds the configured size limit")
                        }
                        output.write(buffer, 0, readBytes)
                    }
                }
            }
            return target
        } catch (error: Exception) {
            Log.e(TAG, "Failed to copy photo input: ${error.javaClass.simpleName}")
            deleteQuietly(target)
            throw error
        }
    }

    fun deleteQuietly(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete a private photo cache file")
        }
    }

    private val SUPPORTED_SUFFIXES = PhotoFormat.entries.mapTo(mutableSetOf()) { it.suffix }
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.cache

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class LivePhotoCacheManager(context: Context) : Closeable {

    private val cacheDirectory = context.applicationContext.cacheDir
    private val contentResolver = context.applicationContext.contentResolver
    private val managedFiles = mutableSetOf<File>()
    private val staleFilesCleaned = AtomicBoolean(false)

    /** Copies a content URI into a bounded application-private cache file. */
    suspend fun copyUri(uri: Uri, suffix: String, maxBytes: Long): File = withContext(Dispatchers.IO) {
        require(suffix in ALLOWED_SUFFIXES) { "Unsupported Live Photo cache suffix" }
        require(maxBytes > 0L) { "maxBytes must be positive" }
        cleanupStaleFilesOnce()
        val target = createManagedFile(suffix)
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open Live Photo input" }
                target.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var totalBytes = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val readBytes = input.read(buffer)
                        if (readBytes < 0) break
                        totalBytes += readBytes
                        if (totalBytes > maxBytes) {
                            throw IOException("Live Photo input exceeds the configured limit")
                        }
                        output.write(buffer, 0, readBytes)
                    }
                }
            }
            target
        } catch (error: Exception) {
            Log.e(TAG, "Live Photo cache copy failed: ${error.javaClass.simpleName}")
            release(target)
            throw error
        }
    }

    /** Extracts a validated byte range into a private cache file. */
    suspend fun extractRange(
        source: File,
        offset: Long,
        length: Long,
        suffix: String,
    ): File = withContext(Dispatchers.IO) {
        require(suffix in VIDEO_SUFFIXES) { "Unsupported Live Photo video suffix" }
        require(length in 1..MAX_VIDEO_FILE_BYTES) { "Live Photo video length is outside limits" }
        require(offset >= 0L && offset <= source.length() - length) { "Invalid Live Photo byte range" }
        val target = createManagedFile(suffix)
        try {
            RandomAccessFile(source, "r").use { input ->
                input.seek(offset)
                target.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var remaining = length
                    while (remaining > 0L) {
                        coroutineContext.ensureActive()
                        val requested = minOf(buffer.size.toLong(), remaining).toInt()
                        val readBytes = input.read(buffer, 0, requested)
                        if (readBytes < 0) throw IOException("Live Photo video ended unexpectedly")
                        output.write(buffer, 0, readBytes)
                        remaining -= readBytes
                    }
                }
            }
            target
        } catch (error: Exception) {
            Log.e(TAG, "Live Photo range extraction failed: ${error.javaClass.simpleName}")
            release(target)
            throw error
        }
    }

    fun release(file: File?) {
        if (file == null) return
        synchronized(managedFiles) { managedFiles.remove(file) }
        LiveVideoFiles.release(file)
    }

    override fun close() {
        val files = synchronized(managedFiles) {
            managedFiles.toList().also { managedFiles.clear() }
        }
        files.forEach { file ->
            LiveVideoFiles.release(file)
        }
    }

    private fun createManagedFile(suffix: String): File {
        val file = File.createTempFile(CACHE_FILE_PREFIX, suffix, cacheDirectory)
        LiveVideoFiles.register(file)
        synchronized(managedFiles) { managedFiles += file }
        return file
    }

    private fun cleanupStaleFilesOnce() {
        if (!staleFilesCleaned.compareAndSet(false, true)) return
        cleanupStaleFiles()
    }

    private fun cleanupStaleFiles() {
        val expirationTime = System.currentTimeMillis() - CACHE_RETENTION_MS
        cacheDirectory.listFiles { file ->
            file.isFile && file.name.startsWith(CACHE_FILE_PREFIX) && file.lastModified() < expirationTime
        }.orEmpty().forEach { file ->
            if (!file.delete()) {
                Log.w(TAG, "Unable to delete a stale private Live Photo cache file")
            }
        }
    }

    companion object {
        const val MAX_VIDEO_FILE_BYTES = 256L * 1024L * 1024L
        const val MAX_CONTAINER_FILE_BYTES = 456L * 1024L * 1024L

        private const val TAG = "LivePhotoCache"
        private const val CACHE_FILE_PREFIX = "live_photo_"
        private const val CACHE_RETENTION_MS = 24L * 60L * 60L * 1_000L
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private val VIDEO_SUFFIXES = setOf(".mp4", ".mov")
        private val ALLOWED_SUFFIXES = VIDEO_SUFFIXES + setOf(
            ".jpg",
            ".jpeg",
            ".heic",
            ".heif",
            ".avif",
            ".bin",
        )
    }
}

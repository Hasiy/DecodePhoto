/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

object PhotoInputResolver {

    private const val TAG = "PhotoInputResolver"
    private const val COPY_BUFFER_SIZE_BYTES = 256 * 1024
    const val MAX_PHOTO_FILE_BYTES = 200L * 1024L * 1024L
    private const val MAX_IDLE_INPUTS = 2
    private val copyMutex = Mutex()
    private val entries = linkedMapOf<Uri, Entry>()
    private var cleaned = false
    private class Entry(val file: File, var references: Int = 0, var retired: Boolean = false)

    class Lease internal constructor(val file: File, private val release: () -> Unit) : Closeable {
        private var closed = false
        @Synchronized override fun close() { if (!closed) { closed = true; release() } }
    }

    /** Acquires a shared seekable input. Close only after native work actually finishes. */
    suspend fun acquire(context: Context, uri: Uri, suffix: String): Lease {
        var acquired: Lease? = null
        try {
            return withContext(Dispatchers.IO) {
                copyMutex.withLock {
                    if (!cleaned) {
                        context.cacheDir.listFiles { it.name.startsWith("photo_input_") }
                            .orEmpty().forEach(::deleteQuietly)
                        cleaned = true
                    }
                    // Pin existing inputs while holding the same lock used by invalidation
                    // and idle eviction. A published entry must never be temporarily unowned.
                    val existing = synchronized(entries) {
                        entries[uri]?.also { it.references++ }
                    }
                    val entry = existing ?: Entry(copyToCache(context, uri, suffix), references = 1).also {
                        synchronized(entries) { entries[uri] = it }
                    }
                    Lease(entry.file) {
                        synchronized(entries) {
                            entry.references--
                            if (entry.retired && entry.references == 0) deleteQuietly(entry.file)
                            val idle = entries.filterValues { it.references == 0 }.keys.toList()
                            idle.take((idle.size - MAX_IDLE_INPUTS).coerceAtLeast(0)).forEach { key ->
                                entries.remove(key)?.let { deleteQuietly(it.file) }
                            }
                        }
                    }.also { acquired = it }
                }
            }
        } catch (error: Throwable) { acquired?.close(); throw error }
    }

    fun invalidate(uri: Uri) = synchronized(entries) {
        entries.remove(uri)?.let {
            it.retired = true
            if (it.references == 0) deleteQuietly(it.file)
        }
    }

    /**
     * Copies URI content into application-private cache for native code that needs a path.
     *
     * @param context application Context.
     * @param uri Storage Access Framework or MediaStore URI.
     * @param suffix trusted suffix for the temporary file.
     * @return a private temporary file that the caller must delete.
     */
    suspend fun copyToCache(
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
                        currentCoroutineContext().ensureActive()
                        val readBytes = input.read(buffer)
                        if (readBytes < 0) break
                        totalBytes += readBytes
                        if (totalBytes > MAX_PHOTO_FILE_BYTES) {
                            throw PhotoInputTooLargeException()
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

class PhotoInputTooLargeException : IOException("Photo exceeds size limit")

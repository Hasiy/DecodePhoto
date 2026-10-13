/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer.live.cache

import android.util.Log
import java.io.Closeable
import java.io.File

/** Keeps extracted videos alive while playback selection or an export still owns them. */
object LiveVideoFiles {
    private val references = mutableMapOf<File, Int>()
    @Synchronized fun register(file: File) { references[file] = 1 }
    @Synchronized fun retain(file: File): Closeable {
        check(references.containsKey(file)) { "Live video is no longer available" }
        references[file] = references.getValue(file) + 1
        return object : Closeable {
            private var closed = false
            @Synchronized override fun close() { if (!closed) { closed = true; release(file) } }
        }
    }
    @Synchronized fun release(file: File) {
        val remaining = (references[file] ?: return) - 1
        if (remaining > 0) references[file] = remaining
        else {
            references.remove(file)
            if (file.exists() && !file.delete()) Log.w("LiveVideoFiles", "Unable to delete private video")
        }
    }
}

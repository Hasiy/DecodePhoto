/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer.raw

/** Serializes memory-intensive native pixel decoding across main, HDR and comparison screens. */
object NativeDecodeBudget {
    private val lock = Any()
    fun <T> run(block: () -> T): T = synchronized(lock) { block() }
}

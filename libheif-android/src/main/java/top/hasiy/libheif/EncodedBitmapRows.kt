/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.libheif

import android.graphics.Bitmap

/** Reads encoded, unpremultiplied float samples without getColor's API 29 requirement. */
class EncodedBitmapRows(private val bitmap: Bitmap) {
    private val values = FloatArray(bitmap.width * COMPONENTS)
    fun read(y: Int): FloatArray {
        require(y in 0 until bitmap.height)
        check(nativeReadRow(bitmap, y, bitmap.isPremultiplied, values)) { "Unsupported Bitmap pixel layout" }
        return values
    }
    private external fun nativeReadRow(bitmap: Bitmap, y: Int, premultiplied: Boolean, target: FloatArray): Boolean
    private companion object {
        const val COMPONENTS = 4
        init { System.loadLibrary("libheif_android_bridge") }
    }
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.graphics.Bitmap
import android.graphics.Matrix

object ExifOrientationTransformer {

    fun apply(source: Bitmap, rotationDegrees: Int, isFlipped: Boolean): Bitmap {
        if (rotationDegrees == 0 && !isFlipped) return source
        val matrix = Matrix().apply {
            if (isFlipped) postScale(-1f, 1f)
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        ).also { oriented ->
            if (oriented !== source) source.recycle()
        }
    }
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import kotlin.math.roundToInt

object ImageSizeCalculator {

    /**
     * Fits an image within a longest-edge limit while preserving its aspect ratio.
     *
     * @param width source width in pixels.
     * @param height source height in pixels.
     * @param maxLongEdge maximum output width or height in pixels.
     * @return the target width and height.
     */
    fun fitInside(
        width: Int,
        height: Int,
        maxLongEdge: Int,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid source dimensions" }
        require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }

        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return width to height

        val scale = maxLongEdge.toDouble() / longEdge
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }
}

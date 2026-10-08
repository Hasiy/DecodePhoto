/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.sqrt

/** RGB channel distribution calculated from a decoded image. */
data class RgbHistogram(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val sampleCount: Int,
) {
    val maxBinCount: Int = maxOf(
        red.maxOrNull() ?: 0,
        green.maxOrNull() ?: 0,
        blue.maxOrNull() ?: 0,
    )
}

object RgbHistogramCalculator {

    /**
     * Calculates a 256-bin RGB histogram without allocating an array for the entire bitmap.
     * Large images are sampled at a uniform stride to bound processing time.
     *
     * @param bitmap decoded bitmap whose displayed RGB values should be measured.
     * @param maxSampleCount approximate maximum number of pixels to inspect.
     * @return RGB channel counts and the actual number of sampled pixels.
     */
    suspend fun calculate(
        bitmap: Bitmap,
        maxSampleCount: Int = DEFAULT_MAX_SAMPLE_COUNT,
    ): RgbHistogram = withContext(Dispatchers.Default) {
        require(maxSampleCount > 0) { "maxSampleCount must be positive" }
        require(bitmap.width > 0 && bitmap.height > 0) { "Bitmap must not be empty" }

        val stride = calculateSampleStride(bitmap.width, bitmap.height, maxSampleCount)
        val red = IntArray(BIN_COUNT)
        val green = IntArray(BIN_COUNT)
        val blue = IntArray(BIN_COUNT)
        val row = IntArray(bitmap.width)
        var sampleCount = 0
        var y = 0

        while (y < bitmap.height) {
            currentCoroutineContext().ensureActive()
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                addColor(row[x], red, green, blue)
                sampleCount++
                x += stride
            }
            y += stride
        }

        RgbHistogram(red, green, blue, sampleCount)
    }

    internal fun calculateColors(colors: IntArray): RgbHistogram {
        val red = IntArray(BIN_COUNT)
        val green = IntArray(BIN_COUNT)
        val blue = IntArray(BIN_COUNT)
        colors.forEach { color -> addColor(color, red, green, blue) }
        return RgbHistogram(red, green, blue, colors.size)
    }

    internal fun calculateSampleStride(width: Int, height: Int, maxSampleCount: Int): Int {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(maxSampleCount > 0) { "maxSampleCount must be positive" }
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount <= maxSampleCount) return 1
        return ceil(sqrt(pixelCount.toDouble() / maxSampleCount.toDouble())).toInt()
    }

    private fun addColor(
        color: Int,
        red: IntArray,
        green: IntArray,
        blue: IntArray,
    ) {
        red[(color ushr RED_SHIFT) and CHANNEL_MASK]++
        green[(color ushr GREEN_SHIFT) and CHANNEL_MASK]++
        blue[color and CHANNEL_MASK]++
    }

    const val BIN_COUNT = 256
    private const val DEFAULT_MAX_SAMPLE_COUNT = 262_144
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF
}

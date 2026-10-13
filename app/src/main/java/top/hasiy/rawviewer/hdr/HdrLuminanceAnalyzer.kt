/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer.hdr

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.*
import top.hasiy.libheif.HeifDynamicRange
import top.hasiy.libheif.HdrToneMapMath
import top.hasiy.libheif.EncodedBitmapRows
import top.hasiy.rawviewer.raw.DecodedColorInfo
import top.hasiy.rawviewer.raw.PhotoDynamicRange

data class HdrLuminanceHistogram(val bins: IntArray, val samples: Int,
    val maximum: Double, val mean: Double)

object HdrLuminanceAnalyzer {
    const val BIN_COUNT = 64
    private const val MAX_SAMPLES = 65536
    private const val MIN_EV = -8.0
    private const val MAX_EV = 8.0
    internal fun bin(relativeLuminance: Double): Int {
        val ev = if (relativeLuminance > 0) log2(relativeLuminance) else MIN_EV
        return (((ev.coerceIn(MIN_EV, MAX_EV) - MIN_EV) / (MAX_EV - MIN_EV)) * BIN_COUNT)
            .toInt().coerceIn(0, BIN_COUNT - 1)
    }
    /** Source-linear sampling, never inferred from an 8-bit tone-mapped HDR image. */
    suspend fun calculate(source: Bitmap): HdrLuminanceHistogram = withContext(Dispatchers.Default) {
        val range = DecodedColorInfo.fromBitmap(source).dynamicRange
        val gainmap = if (Build.VERSION.SDK_INT >= 34 && source.hasGainmap()) source.gainmap else null
        val map = if (Build.VERSION.SDK_INT >= 34) gainmap?.gainmapContents else null
        val rgbSpace = source.colorSpace as? ColorSpace.Rgb ?: throw IllegalArgumentException("RGB required")
        val matrix = rgbSpace.transform
        val stride = ceil(sqrt(source.width.toDouble() * source.height / MAX_SAMPLES)).toInt().coerceAtLeast(1)
        val bins = IntArray(BIN_COUNT)
        var samples = 0; var maximum = 0.0; var sum = 0.0
        val reader = EncodedBitmapRows(source)
        val mapReader = map?.let(::EncodedBitmapRows)
        for (y in 0 until source.height step stride) {
            currentCoroutineContext().ensureActive()
            val encoded = reader.read(y)
            val gainRow = mapReader?.read((y.toLong() * requireNotNull(map).height / source.height).toInt())
            for (x in 0 until source.width step stride) {
                val index = x * 4
                if (encoded[index + 3] <= 0f) continue
                val rgb = if (range == PhotoDynamicRange.PQ || range == PhotoDynamicRange.HLG) {
                    HdrToneMapMath.linearRgb(encoded[index].toDouble(), encoded[index + 1].toDouble(), encoded[index + 2].toDouble(),
                        if (range == PhotoDynamicRange.PQ) HeifDynamicRange.PQ else HeifDynamicRange.HLG)
                } else doubleArrayOf(rgbSpace.eotf.applyAsDouble(encoded[index].toDouble()),
                    rgbSpace.eotf.applyAsDouble(encoded[index + 1].toDouble()), rgbSpace.eotf.applyAsDouble(encoded[index + 2].toDouble()))
                if (Build.VERSION.SDK_INT >= 34 && gainmap != null && map != null && gainRow != null) {
                    val gainIndex = (x.toLong() * map.width / source.width).toInt() * 4
                    val gains = if (map.config == Bitmap.Config.ALPHA_8)
                        floatArrayOf(gainRow[gainIndex + 3], gainRow[gainIndex + 3], gainRow[gainIndex + 3])
                        else floatArrayOf(gainRow[gainIndex], gainRow[gainIndex + 1], gainRow[gainIndex + 2])
                    for (c in 0..2) {
                        val logGain = ln(gainmap.ratioMin[c].toDouble()) * (1 - gains[c].toDouble().pow(gainmap.gamma[c].toDouble())) +
                            ln(gainmap.ratioMax[c].toDouble()) * gains[c].toDouble().pow(gainmap.gamma[c].toDouble())
                        rgb[c] = (rgb[c] + gainmap.epsilonSdr[c]) * exp(logGain) - gainmap.epsilonHdr[c]
                    }
                }
                val luminance = if (range == PhotoDynamicRange.PQ || range == PhotoDynamicRange.HLG)
                    HdrToneMapMath.luminance(rgb) else matrix[1] * rgb[0] + matrix[4] * rgb[1] + matrix[7] * rgb[2]
                val value = luminance.coerceAtLeast(0.0)
                bins[bin(value)]++; samples++; maximum = max(maximum, value); sum += value
            }
        }
        HdrLuminanceHistogram(bins, samples, maximum, if (samples > 0) sum / samples else 0.0)
    }

    suspend fun gainmapPreview(source: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < 34 || !source.hasGainmap()) return@withContext null
        val map = source.gainmap?.gainmapContents ?: return@withContext null
        val scale = min(1.0, 512.0 / max(map.width, map.height))
        val width = (map.width * scale).toInt().coerceAtLeast(1)
        val height = (map.height * scale).toInt().coerceAtLeast(1)
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                currentCoroutineContext().ensureActive()
                for (x in 0 until width) {
                    val color = map.getColor(x * map.width / width, y * map.height / height)
                    val value = if (map.config == Bitmap.Config.ALPHA_8) color.alpha()
                        else (color.red() + color.green() + color.blue()) / 3f
                    val gray = (value * 255).toInt().coerceIn(0, 255)
                    row[x] = -0x1000000 or (gray shl 16) or (gray shl 8) or gray
                }
                target.setPixels(row, 0, width, 0, y, width, 1)
            }
            target
        } catch (error: Throwable) { target.recycle(); throw error }
    }
}

/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.libheif

import android.graphics.Bitmap
import android.graphics.ColorSpace

object HdrToneMapper {
    /**
     * Maps encoded BT.2020 PQ/HLG pixels to SDR without an intermediate 8-bit HDR image.
     * @param source software Bitmap containing encoded RGB in its original color space.
     * @param range known HDR transfer function; unknown primaries must be rejected upstream.
     * @param checkActive worker cancellation check, called once per row.
     * @return new sRGB ARGB_8888 Bitmap; source remains owned by caller.
     */
    fun map(source: Bitmap, range: HeifDynamicRange, checkActive: () -> Unit = {}): Bitmap {
        require(range != HeifDynamicRange.SDR)
        val target = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888,
            true, ColorSpace.get(ColorSpace.Named.SRGB))
        try {
            val row = IntArray(source.width)
            val reader = EncodedBitmapRows(source)
            for (y in 0 until source.height) {
                checkActive()
                val encoded = reader.read(y)
                for (x in 0 until source.width) {
                    val index = x * 4
                    val rgb = HdrToneMapMath.toSrgb(HdrToneMapMath.linearRgb(
                        encoded[index].toDouble(), encoded[index + 1].toDouble(), encoded[index + 2].toDouble(), range))
                    row[x] = ((encoded[index + 3] * 255).toInt().coerceIn(0, 255) shl 24) or
                        ((rgb[0] * 255 + 0.5).toInt() shl 16) or
                        ((rgb[1] * 255 + 0.5).toInt() shl 8) or (rgb[2] * 255 + 0.5).toInt()
                }
                target.setPixels(row, 0, source.width, 0, y, source.width, 1)
            }
            return target
        } catch (error: Throwable) { target.recycle(); throw error }
    }
}

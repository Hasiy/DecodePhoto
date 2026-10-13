/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer.hdr

import android.graphics.*
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.hasiy.libheif.HeifDynamicRange
import top.hasiy.libheif.HdrToneMapper
import top.hasiy.rawviewer.raw.DecodedColorInfo
import top.hasiy.rawviewer.raw.PhotoDynamicRange

object HdrBitmapTools {
    /** Returns an independently owned SDR bitmap. Call only on a worker dispatcher. */
    suspend fun toSdr(source: Bitmap): Bitmap {
        val range = DecodedColorInfo.fromBitmap(source).dynamicRange
        if (range == PhotoDynamicRange.PQ || range == PhotoDynamicRange.HLG) {
            val context = currentCoroutineContext()
            return HdrToneMapper.map(source, if (range == PhotoDynamicRange.PQ) HeifDynamicRange.PQ else HeifDynamicRange.HLG) {
                context.ensureActive()
            }
        }
        var base: Bitmap? = null
        val input = if (Build.VERSION.SDK_INT >= 34 && source.hasGainmap()) {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, false).also {
                requireNotNull(it).setGainmap(null); base = it
            } ?: throw java.io.IOException("Unable to copy SDR base")
        } else source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888,
            true, ColorSpace.get(ColorSpace.Named.SRGB))
        try { Canvas(result).drawBitmap(input, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)); return result }
        catch (error: Throwable) { result.recycle(); throw error }
        finally { base?.recycle() }
    }
}

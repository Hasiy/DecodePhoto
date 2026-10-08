/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SystemPhotoDecoder(private val applicationContext: Context) {

    suspend fun decode(
        uri: android.net.Uri,
        format: PhotoFormat,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    ): PhotoDecodeOutput =
        withContext(Dispatchers.IO) {
            require(!format.isRaw) { "System photo decoder does not accept RAW formats" }
            require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }
            val cached = PhotoInputResolver.copyToCache(applicationContext, uri, format.suffix)
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    decodeWithImageDecoder(cached, maxLongEdge)
                } else {
                    decodeWithBitmapFactory(cached, maxLongEdge)
                }
                PhotoDecodeOutput(
                    bitmap = bitmap,
                    colorInfo = DecodedColorInfo.fromBitmap(bitmap),
                )
            } finally {
                PhotoInputResolver.deleteQuietly(cached)
            }
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(file: File, maxLongEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            ensureSafeDimensions(info.size.width, info.size.height)
            val target = ImageSizeCalculator.fitInside(
                info.size.width,
                info.size.height,
                maxLongEdge,
            )
            decoder.setTargetSize(target.first, target.second)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sourceColorSpace = info.colorSpace
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                sourceColorSpace.isHdrTransfer()
            ) {
                decoder.setTargetColorSpace(requireNotNull(sourceColorSpace))
            }
        }
    }

    private fun android.graphics.ColorSpace?.isHdrTransfer(): Boolean {
        val name = this?.name.orEmpty()
        return name.contains("Perceptual Quantizer", ignoreCase = true) ||
            name.contains("Hybrid Log Gamma", ignoreCase = true) ||
            name.contains("PQ", ignoreCase = true) ||
            name.contains("HLG", ignoreCase = true)
    }

    private fun decodeWithBitmapFactory(file: File, maxLongEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        ensureSafeDimensions(bounds.outWidth, bounds.outHeight)

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("Android system decoder returned no image")
        val resized = resizeIfNeeded(decoded, maxLongEdge)
        val exif = ExifInterface(file)
        return ExifOrientationTransformer.apply(resized, exif.rotationDegrees, exif.isFlipped)
    }

    private fun resizeIfNeeded(source: Bitmap, maxLongEdge: Int): Bitmap {
        val size = ImageSizeCalculator.fitInside(source.width, source.height, maxLongEdge)
        if (size.first == source.width && size.second == source.height) return source
        return Bitmap.createScaledBitmap(source, size.first, size.second, true).also {
            if (it !== source) source.recycle()
        }
    }

    private fun ensureSafeDimensions(width: Int, height: Int) {
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
            pixels > MAX_DECODED_PIXELS
        ) {
            throw IOException("Photo dimensions exceed the configured safety limit")
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        var sampleSize = 1
        while (maxOf(width / sampleSize, height / sampleSize) > maxLongEdge * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private companion object {
        const val DEFAULT_MAX_LONG_EDGE = 4096
        const val MAX_DIMENSION = 32768
        const val MAX_DECODED_PIXELS = 160_000_000L
    }
}

/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.hdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.hasiy.rawviewer.raw.ImageSizeCalculator
import top.hasiy.rawviewer.raw.PhotoFormatResolver
import top.hasiy.rawviewer.raw.SystemPhotoDecoder
import top.hasiy.rawviewer.raw.LibHeifDecoder
import top.hasiy.libheif.HeifDynamicRange
import kotlinx.coroutines.CancellationException
import android.util.Log

class HdrComparisonDecoder(context: Context) {

    private val applicationContext = context.applicationContext

    /**
     * Decodes one image for the default/HDR/SDR comparison page.
     *
     * The original Bitmap retains its gain map. The SDR rendition explicitly removes the gain map
     * on Android 14+, so selecting SDR cannot accidentally render with HDR headroom.
     *
     * @param uri persistable content URI selected by the user.
     * @return the original rendition, SDR base rendition, and inspectable HDR metadata.
     */
    suspend fun decode(uri: Uri): HdrComparisonImage = withContext(Dispatchers.IO) {
        ensureSafeInputSize(uri)
        val format = PhotoFormatResolver(applicationContext).resolve(uri)
            ?: throw HdrComparisonDecodeException(HdrComparisonError.UNSUPPORTED_IMAGE)
        if (format.isRaw) throw HdrComparisonDecodeException(HdrComparisonError.UNSUPPORTED_IMAGE)
        val original = if (format.isHeif) {
            val library = LibHeifDecoder(applicationContext)
            val preferLibrary = try {
                library.inspect(uri, format).imageInfo.dynamicRange != HeifDynamicRange.SDR
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Log.w("HdrComparisonDecoder", "HEIF inspection failed: ${error.javaClass.simpleName}")
                false
            }
            try {
                // SDR keeps the system's original ICC/P3 color space; PQ/HLG uses
                // the high-precision library path before any SDR tone mapping.
                if (preferLibrary) library.decode(uri, format, MAX_LONG_EDGE).bitmap
                else SystemPhotoDecoder(applicationContext).decode(uri, format, MAX_LONG_EDGE).bitmap
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Log.w("HdrComparisonDecoder", "HEIF fallback: ${error.javaClass.simpleName}")
                if (preferLibrary) SystemPhotoDecoder(applicationContext).decode(uri, format, MAX_LONG_EDGE).bitmap
                else library.decode(uri, format, MAX_LONG_EDGE).bitmap
            }
        } else SystemPhotoDecoder(applicationContext).decode(uri, format, MAX_LONG_EDGE).bitmap
        var ownedSdr: Bitmap? = null
        var ownedMap: Bitmap? = null

        try {
            val hasGainmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                original.hasGainmap()
            val gainmapMetadata = if (hasGainmap) readGainmapMetadata(original) else null
            val contentType = detectContentType(original, hasGainmap)
            val sdrBitmap = when (contentType) {
                HdrContentType.ULTRA_HDR_GAINMAP -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        HdrBitmapTools.toSdr(original)
                    } else {
                        throw IOException("Gain map requires Android 14 or newer")
                    }
                }
                HdrContentType.HLG_10_BIT,
                HdrContentType.PQ_10_BIT -> HdrBitmapTools.toSdr(original)
                HdrContentType.SDR_OR_UNIDENTIFIED -> original
            }
            ownedSdr = sdrBitmap.takeIf { it !== original }
            val luminance = HdrLuminanceAnalyzer.calculate(original)
            ownedMap = HdrLuminanceAnalyzer.gainmapPreview(original)
            HdrComparisonImage(
                originalBitmap = original,
                sdrBitmap = sdrBitmap,
                contentType = contentType,
                colorSpaceName = original.colorSpace?.name ?: UNKNOWN_VALUE,
                bitmapConfigName = original.config?.name ?: UNKNOWN_VALUE,
                gainmapMetadata = gainmapMetadata,
                luminance = luminance,
                gainmapPreview = ownedMap,
            )
        } catch (error: Throwable) {
            ownedSdr?.recycle()
            ownedMap?.recycle()
            original.recycle()
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(applicationContext.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            ensureSafeDimensions(info.size.width, info.size.height)
            val target = ImageSizeCalculator.fitInside(
                width = info.size.width,
                height = info.size.height,
                maxLongEdge = MAX_LONG_EDGE,
            )
            if (target.first != info.size.width || target.second != info.size.height) {
                decoder.setTargetSize(target.first, target.second)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sourceColorSpace = info.colorSpace
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                sourceColorSpace.isHdrTransfer()
            ) {
                decoder.setTargetColorSpace(requireNotNull(sourceColorSpace))
            }
        }
    }

    private fun detectContentType(bitmap: Bitmap, hasGainmap: Boolean): HdrContentType {
        if (hasGainmap) return HdrContentType.ULTRA_HDR_GAINMAP
        val name = bitmap.colorSpace?.name.orEmpty()
        return when {
            name.contains("Perceptual Quantizer", ignoreCase = true) ||
                name.contains("PQ", ignoreCase = true) -> HdrContentType.PQ_10_BIT
            name.contains("Hybrid Log Gamma", ignoreCase = true) ||
                name.contains("HLG", ignoreCase = true) -> HdrContentType.HLG_10_BIT
            else -> HdrContentType.SDR_OR_UNIDENTIFIED
        }
    }

    private fun ColorSpace?.isHdrTransfer(): Boolean {
        val name = this?.name.orEmpty()
        return name.contains("Perceptual Quantizer", ignoreCase = true) ||
            name.contains("Hybrid Log Gamma", ignoreCase = true) ||
            name.contains("PQ", ignoreCase = true) ||
            name.contains("HLG", ignoreCase = true)
    }

    private fun decodeWithBitmapFactory(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: throw IOException("Unable to open comparison image")
        ensureSafeDimensions(bounds.outWidth, bounds.outHeight)

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        }
        return applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IOException("Android system decoder returned no image")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readGainmapMetadata(bitmap: Bitmap): GainmapMetadata {
        val gainmap = bitmap.gainmap
            ?: throw IOException("Bitmap reported a gain map but returned no gain map metadata")
        val contents = gainmap.gainmapContents
        return GainmapMetadata(
            width = contents.width,
            height = contents.height,
            channelCount = if (contents.config == Bitmap.Config.ALPHA_8) {
                1
            } else {
                contents.colorSpace?.componentCount ?: DEFAULT_GAINMAP_CHANNEL_COUNT
            },
            ratioMin = gainmap.ratioMin.toList(),
            ratioMax = gainmap.ratioMax.toList(),
            gamma = gainmap.gamma.toList(),
            epsilonSdr = gainmap.epsilonSdr.toList(),
            epsilonHdr = gainmap.epsilonHdr.toList(),
            displayRatioForFullHdr = gainmap.displayRatioForFullHdr,
            minDisplayRatioForHdrTransition = gainmap.minDisplayRatioForHdrTransition,
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun createSdrBase(original: Bitmap): Bitmap {
        val copy = original.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IOException("Unable to create the SDR base rendition")
        copy.setGainmap(null)
        return copy
    }

    private fun createSdrRendition(original: Bitmap): Bitmap {
        val target = Bitmap.createBitmap(
            original.width,
            original.height,
            Bitmap.Config.ARGB_8888,
            true,
            ColorSpace.get(ColorSpace.Named.SRGB),
        )
        Canvas(target).drawBitmap(original, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return target
    }

    private fun ensureSafeInputSize(uri: Uri) {
        val length = applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")
            ?.use { descriptor -> descriptor.length }
            ?: return
        if (length > MAX_INPUT_BYTES) {
            throw HdrComparisonDecodeException(HdrComparisonError.INPUT_TOO_LARGE)
        }
    }

    private fun ensureSafeDimensions(width: Int, height: Int) {
        val pixelCount = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
            pixelCount > MAX_DECODED_PIXELS
        ) {
            throw HdrComparisonDecodeException(HdrComparisonError.DIMENSIONS_TOO_LARGE)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (maxOf(width / sampleSize, height / sampleSize) > MAX_LONG_EDGE * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private companion object {
        const val MAX_INPUT_BYTES = 200L * 1024L * 1024L
        const val MAX_DECODED_PIXELS = 160_000_000L
        const val MAX_DIMENSION = 32_768
        const val MAX_LONG_EDGE = 2_048
        const val DEFAULT_GAINMAP_CHANNEL_COUNT = 3
        const val UNKNOWN_VALUE = "Unknown"
    }
}

class HdrComparisonDecodeException(val reason: HdrComparisonError) : IOException(reason.name)

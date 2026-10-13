/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.libheif

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.function.DoubleUnaryOperator

enum class HeifDynamicRange {
    SDR,
    HLG,
    PQ,
}

data class LibHeifImageInfo(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int,
    val isFullRange: Boolean,
    val hasIccProfile: Boolean,
    val primaryItemId: Int,
    val topLevelImageCount: Int,
    val encodedColorSpace: HeifEncodedColorSpace,
    val chromaFormat: HeifChromaFormat,
    val tileColumns: Int,
    val tileRows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val colorProfileType: HeifColorProfileType,
    val colorProfileSizeBytes: Int,
    val hasAlphaChannel: Boolean,
) {
    val dynamicRange: HeifDynamicRange
        get() = when (transferCharacteristics) {
            TRANSFER_PQ -> HeifDynamicRange.PQ
            TRANSFER_HLG -> HeifDynamicRange.HLG
            else -> HeifDynamicRange.SDR
        }

    val hasBt2020HdrEncoding: Boolean
        get() = colorPrimaries == PRIMARIES_BT2020 && dynamicRange != HeifDynamicRange.SDR

    private companion object {
        const val TRANSFER_PQ = 16
        const val TRANSFER_HLG = 18
        const val PRIMARIES_BT2020 = 9
    }
}

enum class HeifEncodedColorSpace {
    YCBCR,
    RGB,
    MONOCHROME,
    FILTER_ARRAY,
    CUSTOM,
    UNKNOWN,
}

enum class HeifChromaFormat {
    YUV_420,
    YUV_422,
    YUV_444,
    PLANAR,
    INTERLEAVED_RGB,
    INTERLEAVED_RGBA,
    UNKNOWN,
}

enum class HeifColorProfileType {
    NONE,
    NCLX,
    RESTRICTED_ICC,
    ICC,
    UNKNOWN,
}

enum class HeifItemKind {
    IMAGE,
    METADATA,
}

data class LibHeifItemInfo(
    val id: Int,
    val kind: HeifItemKind,
    val type: String,
    val width: Int?,
    val height: Int?,
    val bitDepth: Int?,
    val sizeBytes: Long?,
    val contentType: String?,
    val isPrimary: Boolean,
)

data class LibHeifIccProfile(
    val description: String?,
    val version: String?,
    val colorSpace: String?,
    val profileConnectionSpace: String?,
    val deviceClass: String?,
    val platform: String?,
    val manufacturer: String?,
    val creator: String?,
    val cmm: String?,
    val renderingIntent: Int?,
    val whitePoint: String?,
)

data class LibHeifContainerInfo(
    val imageInfo: LibHeifImageInfo,
    val items: List<LibHeifItemInfo>,
    val iccProfile: LibHeifIccProfile?,
)

data class LibHeifDecodedImage(
    val bitmap: Bitmap,
    val sourceInfo: LibHeifImageInfo,
    val usesHighPrecisionBitmap: Boolean,
    val usesHdrColorSpace: Boolean,
    val usedSdrFallback: Boolean,
)

/** Decodes HEIF and HEIC files through the bundled libheif native library. */
class LibHeifNativeDecoder {

    /** Reads HEIF container, item, tiling, NCLX, and ICC metadata without decoding pixels. */
    fun inspect(file: File): LibHeifContainerInfo {
        require(file.isFile) { "HEIF input must be a readable local file" }
        val imageInfo = nativeInspectFile(file.absolutePath)?.toImageInfo()
            ?: throw LibHeifException("libheif returned no image information")
        val items = nativeInspectItems(file.absolutePath)
            ?.mapNotNull(::parseItemInfo)
            .orEmpty()
        val iccProfile = nativeReadIccProfile(file.absolutePath)?.let(IccProfileParser::parse)
        return LibHeifContainerInfo(
            imageInfo = imageInfo,
            items = items,
            iccProfile = iccProfile,
        )
    }

    /**
     * Decodes the primary image while preserving high-bit-depth PQ/HLG data on Android 14+.
     *
     * @param file readable local HEIF or HEIC file.
     * @param maxLongEdge maximum output width or height in pixels.
     * @return a decoded bitmap plus source bit depth and NCLX color information.
     */
    fun decode(
        file: File,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
        forceSdr: Boolean = false,
        checkActive: () -> Unit = {},
    ): LibHeifDecodedImage {
        require(file.isFile) { "HEIF input must be a readable local file" }
        require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }
        val info = nativeInspectFile(file.absolutePath)?.toImageInfo()
            ?: throw LibHeifException("libheif returned no image information")
        val hasHdrTransfer = info.dynamicRange != HeifDynamicRange.SDR
        if (hasHdrTransfer && !info.hasBt2020HdrEncoding) {
            throw LibHeifException("Unsupported HDR color primaries")
        }
        val canRenderHdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val useHdrColorSpace = info.hasBt2020HdrEncoding
        val useHighPrecision = info.bitDepth > 8
        val targetSize = fitInside(info.width, info.height, maxLongEdge)
        val bitmap = Bitmap.createBitmap(
            targetSize.first,
            targetSize.second,
            if (useHighPrecision) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888,
            true,
            colorSpaceFor(info, useHdrColorSpace),
        )
        try {
            check(nativeDecodeFile(file.absolutePath, bitmap, useHdrColorSpace)) {
                "libheif returned no image"
            }
            checkActive()
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
        val fallback = hasHdrTransfer && (!canRenderHdr || forceSdr)
        val output = if (fallback) {
            try { HdrToneMapper.map(bitmap, info.dynamicRange, checkActive) }
            finally { bitmap.recycle() }
        } else bitmap
        return LibHeifDecodedImage(
            bitmap = output,
            sourceInfo = info,
            usesHighPrecisionBitmap = useHighPrecision,
            usesHdrColorSpace = useHdrColorSpace && !fallback,
            usedSdrFallback = fallback,
        )
    }

    private fun colorSpaceFor(info: LibHeifImageInfo, useHdrColorSpace: Boolean): ColorSpace {
        if (!useHdrColorSpace) return ColorSpace.get(ColorSpace.Named.SRGB)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The temporary Bitmap is only read as encoded samples before CPU tone mapping.
            return ColorSpace.Rgb("BT.2020 encoded ${info.dynamicRange}",
                floatArrayOf(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f),
                floatArrayOf(0.3127f, 0.3290f), DoubleUnaryOperator { it },
                DoubleUnaryOperator { it }, 0f, 1f)
        }
        return hdrColorSpace(info.dynamicRange)
    }

    @SuppressLint("NewApi")
    private fun hdrColorSpace(dynamicRange: HeifDynamicRange): ColorSpace = ColorSpace.get(
        when (dynamicRange) {
            HeifDynamicRange.PQ -> ColorSpace.Named.BT2020_PQ
            HeifDynamicRange.HLG -> ColorSpace.Named.BT2020_HLG
            HeifDynamicRange.SDR -> ColorSpace.Named.SRGB
        },
    )

    private fun fitInside(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / longest.toDouble()
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    private fun IntArray.toImageInfo(): LibHeifImageInfo {
        if (size != INFO_FIELD_COUNT) throw LibHeifException("Invalid libheif image information")
        return LibHeifImageInfo(
            width = this[0],
            height = this[1],
            bitDepth = this[2],
            colorPrimaries = this[3],
            transferCharacteristics = this[4],
            matrixCoefficients = this[5],
            isFullRange = this[6] != 0,
            hasIccProfile = this[7] != 0,
            primaryItemId = this[8],
            topLevelImageCount = this[9],
            encodedColorSpace = encodedColorSpace(this[10]),
            chromaFormat = chromaFormat(this[11]),
            tileColumns = this[12],
            tileRows = this[13],
            tileWidth = this[14],
            tileHeight = this[15],
            colorProfileType = colorProfileType(this[16]),
            colorProfileSizeBytes = this[17],
            hasAlphaChannel = this[18] != 0,
        )
    }

    private fun parseItemInfo(encoded: String): LibHeifItemInfo? {
        val fields = encoded.split(ITEM_FIELD_SEPARATOR)
        if (fields.size != ITEM_FIELD_COUNT) return null
        val kind = when (fields[0]) {
            ITEM_KIND_IMAGE -> HeifItemKind.IMAGE
            ITEM_KIND_METADATA -> HeifItemKind.METADATA
            else -> return null
        }
        return LibHeifItemInfo(
            id = fields[1].toIntOrNull() ?: return null,
            kind = kind,
            type = fields[2],
            width = fields[3].toPositiveIntOrNull(),
            height = fields[4].toPositiveIntOrNull(),
            bitDepth = fields[5].toPositiveIntOrNull(),
            sizeBytes = fields[6].toLongOrNull()?.takeIf { it >= 0L },
            contentType = fields[7].ifBlank { null },
            isPrimary = fields[8] == "1",
        )
    }

    private fun String.toPositiveIntOrNull(): Int? = toIntOrNull()?.takeIf { it > 0 }

    private fun encodedColorSpace(value: Int): HeifEncodedColorSpace = when (value) {
        COLORSPACE_YCBCR -> HeifEncodedColorSpace.YCBCR
        COLORSPACE_RGB -> HeifEncodedColorSpace.RGB
        COLORSPACE_MONOCHROME -> HeifEncodedColorSpace.MONOCHROME
        COLORSPACE_FILTER_ARRAY -> HeifEncodedColorSpace.FILTER_ARRAY
        COLORSPACE_CUSTOM -> HeifEncodedColorSpace.CUSTOM
        else -> HeifEncodedColorSpace.UNKNOWN
    }

    private fun chromaFormat(value: Int): HeifChromaFormat = when (value) {
        CHROMA_420 -> HeifChromaFormat.YUV_420
        CHROMA_422 -> HeifChromaFormat.YUV_422
        CHROMA_444 -> HeifChromaFormat.YUV_444
        CHROMA_PLANAR -> HeifChromaFormat.PLANAR
        CHROMA_INTERLEAVED_RGB -> HeifChromaFormat.INTERLEAVED_RGB
        CHROMA_INTERLEAVED_RGBA -> HeifChromaFormat.INTERLEAVED_RGBA
        else -> HeifChromaFormat.UNKNOWN
    }

    private fun colorProfileType(value: Int): HeifColorProfileType = when (value) {
        PROFILE_NONE -> HeifColorProfileType.NONE
        PROFILE_NCLX -> HeifColorProfileType.NCLX
        PROFILE_RICC -> HeifColorProfileType.RESTRICTED_ICC
        PROFILE_ICC -> HeifColorProfileType.ICC
        else -> HeifColorProfileType.UNKNOWN
    }

    private external fun nativeInspectFile(path: String): IntArray?

    private external fun nativeInspectItems(path: String): Array<String>?

    private external fun nativeReadIccProfile(path: String): ByteArray?

    private external fun nativeDecodeFile(
        path: String,
        target: Bitmap,
        preserveHdrColorSpace: Boolean,
    ): Boolean

    companion object {
        const val DEFAULT_MAX_LONG_EDGE = 4096
        private const val INFO_FIELD_COUNT = 19
        private const val ITEM_FIELD_SEPARATOR = '\u001f'
        private const val ITEM_FIELD_COUNT = 9
        private const val ITEM_KIND_IMAGE = "I"
        private const val ITEM_KIND_METADATA = "M"
        private const val COLORSPACE_YCBCR = 0
        private const val COLORSPACE_RGB = 1
        private const val COLORSPACE_MONOCHROME = 2
        private const val COLORSPACE_FILTER_ARRAY = 3
        private const val COLORSPACE_CUSTOM = 4
        private const val CHROMA_PLANAR = 0
        private const val CHROMA_420 = 1
        private const val CHROMA_422 = 2
        private const val CHROMA_444 = 3
        private const val CHROMA_INTERLEAVED_RGB = 10
        private const val CHROMA_INTERLEAVED_RGBA = 11
        private const val PROFILE_NONE = 0
        private const val PROFILE_NCLX = 1
        private const val PROFILE_RICC = 2
        private const val PROFILE_ICC = 3

        init {
            System.loadLibrary("libheif_android_bridge")
        }
    }
}

internal object IccProfileParser {
    private const val HEADER_SIZE = 128
    private const val TAG_TABLE_OFFSET = 128
    private const val TAG_RECORD_SIZE = 12
    private const val FIXED_POINT_SCALE = 65_536.0
    private const val MAX_TAG_COUNT = 4_096

    fun parse(bytes: ByteArray): LibHeifIccProfile? {
        if (bytes.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return LibHeifIccProfile(
            description = readDescription(buffer),
            version = readVersion(bytes),
            colorSpace = readSignature(bytes, 16),
            profileConnectionSpace = readSignature(bytes, 20),
            deviceClass = readSignature(bytes, 12),
            platform = readSignature(bytes, 40),
            manufacturer = readSignature(bytes, 48),
            creator = readSignature(bytes, 80),
            cmm = readSignature(bytes, 4),
            renderingIntent = buffer.getInt(64).takeIf { it in 0..3 },
            whitePoint = readXyzTag(buffer, "wtpt"),
        )
    }

    private fun readVersion(bytes: ByteArray): String {
        val major = bytes[8].toInt() and 0xff
        val minor = (bytes[9].toInt() ushr 4) and 0x0f
        val bugfix = bytes[9].toInt() and 0x0f
        return "$major.$minor.$bugfix"
    }

    private fun readDescription(buffer: ByteBuffer): String? {
        val record = findTag(buffer, "desc") ?: return null
        val type = readSignature(buffer.array(), record.first)
        return when (type) {
            "desc" -> readAsciiDescription(buffer.array(), record.first, record.second)
            "mluc" -> readMultilingualDescription(buffer, record.first, record.second)
            else -> null
        }
    }

    private fun readAsciiDescription(bytes: ByteArray, offset: Int, size: Int): String? {
        if (size < 12 || offset > bytes.size - size) return null
        val length = ByteBuffer.wrap(bytes, offset + 8, 4).order(ByteOrder.BIG_ENDIAN).int
        if (length <= 1 || length > size - 12) return null
        return bytes.copyOfRange(offset + 12, offset + 12 + length - 1)
            .toString(Charsets.US_ASCII)
            .trim()
            .ifBlank { null }
    }

    private fun readMultilingualDescription(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
    ): String? {
        if (size < 28 || offset > buffer.limit() - size) return null
        val count = buffer.getInt(offset + 8)
        val recordSize = buffer.getInt(offset + 12)
        if (count <= 0 || recordSize < 12 || count > MAX_TAG_COUNT) return null
        val firstRecord = offset + 16
        val textLength = buffer.getInt(firstRecord + 4)
        val textOffset = buffer.getInt(firstRecord + 8)
        if (textLength <= 0 || textOffset < 0 || textOffset > size - textLength) return null
        return buffer.array().copyOfRange(
            offset + textOffset,
            offset + textOffset + textLength,
        ).toString(Charsets.UTF_16BE).trim().ifBlank { null }
    }

    private fun readXyzTag(buffer: ByteBuffer, signature: String): String? {
        val record = findTag(buffer, signature) ?: return null
        val offset = record.first
        if (record.second < 20 || readSignature(buffer.array(), offset) != "XYZ") return null
        val x = buffer.getInt(offset + 8) / FIXED_POINT_SCALE
        val y = buffer.getInt(offset + 12) / FIXED_POINT_SCALE
        val z = buffer.getInt(offset + 16) / FIXED_POINT_SCALE
        return "[%.4f, %.4f, %.4f]".format(java.util.Locale.ROOT, x, y, z)
    }

    private fun findTag(buffer: ByteBuffer, signature: String): Pair<Int, Int>? {
        if (buffer.limit() < TAG_TABLE_OFFSET + Int.SIZE_BYTES) return null
        val count = buffer.getInt(TAG_TABLE_OFFSET)
        if (count < 0 || count > MAX_TAG_COUNT) return null
        repeat(count) { index ->
            val recordOffset = TAG_TABLE_OFFSET + Int.SIZE_BYTES + index * TAG_RECORD_SIZE
            if (recordOffset > buffer.limit() - TAG_RECORD_SIZE) return null
            if (readSignature(buffer.array(), recordOffset) == signature) {
                val dataOffset = buffer.getInt(recordOffset + 4)
                val dataSize = buffer.getInt(recordOffset + 8)
                if (dataOffset >= 0 && dataSize >= 0 && dataOffset <= buffer.limit() - dataSize) {
                    return dataOffset to dataSize
                }
            }
        }
        return null
    }

    private fun readSignature(bytes: ByteArray, offset: Int): String? {
        if (offset < 0 || offset > bytes.size - 4) return null
        return bytes.copyOfRange(offset, offset + 4)
            .toString(Charsets.US_ASCII)
            .trim()
            .ifBlank { null }
    }
}

/** Reports a native libheif allocation, parsing, decoding, or bitmap access failure. */
class LibHeifException(message: String) : IllegalStateException(message)

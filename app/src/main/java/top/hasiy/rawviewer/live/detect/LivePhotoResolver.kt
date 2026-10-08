/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.detect

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import top.hasiy.rawviewer.live.cache.LivePhotoCacheManager
import top.hasiy.rawviewer.live.model.LiveDetectionEvidence
import top.hasiy.rawviewer.live.model.LivePhotoAsset
import top.hasiy.rawviewer.live.model.LivePhotoKind
import top.hasiy.rawviewer.live.model.PairingConfidence
import top.hasiy.rawviewer.live.parser.IsoBmffReader
import top.hasiy.rawviewer.live.parser.LiveVideoInspector
import top.hasiy.rawviewer.live.parser.MotionPhotoXmpParser
import top.hasiy.rawviewer.live.parser.XmpPacketReader
import top.hasiy.rawviewer.raw.PhotoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

class LivePhotoResolver(context: Context) : Closeable {

    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver
    private val cacheManager = LivePhotoCacheManager(applicationContext)

    suspend fun resolveSingle(
        stillUri: Uri,
        format: PhotoFormat,
    ): LivePhotoAsset? = withContext(Dispatchers.IO) {
        val source = cacheManager.copyUri(
            uri = stillUri,
            suffix = format.suffix,
            maxBytes = LivePhotoCacheManager.MAX_CONTAINER_FILE_BYTES,
        )
        try {
            val xmp = XmpPacketReader.read(source) ?: return@withContext null
            val descriptor = MotionPhotoXmpParser.parse(xmp) ?: return@withContext null
            val videoOffset = source.length() - descriptor.videoLength
            if (!IsoBmffReader.hasFileTypeBox(source, videoOffset, descriptor.videoLength)) {
                throw IOException("Motion Photo video has no valid file type box")
            }
            val video = cacheManager.extractRange(
                source = source,
                offset = videoOffset,
                length = descriptor.videoLength,
                suffix = ".mp4",
            )
            try {
                val metadata = LiveVideoInspector.inspect(video)
                val make = readExifMake(source)
                return@withContext LivePhotoAsset(
                    stillUri = stillUri,
                    videoFile = video,
                    kind = detectKind(make, descriptor.isLegacy),
                    presentationTimestampUs = descriptor.presentationTimestampUs,
                    videoMetadata = metadata,
                    pairingConfidence = null,
                    detectionEvidence = listOf(
                        if (descriptor.isLegacy) {
                            LiveDetectionEvidence.LEGACY_MICRO_VIDEO_XMP
                        } else {
                            LiveDetectionEvidence.MOTION_PHOTO_XMP
                        },
                        LiveDetectionEvidence.ISO_BMFF_FILE_TYPE,
                        LiveDetectionEvidence.VIDEO_TRACK_VERIFIED,
                    ),
                )
            } catch (error: Exception) {
                Log.e(TAG, "Motion Photo video verification failed: ${error.javaClass.simpleName}")
                cacheManager.release(video)
                throw error
            }
        } finally {
            cacheManager.release(source)
        }
    }

    suspend fun resolveApplePair(
        stillUri: Uri,
        videoUri: Uri,
    ): LivePhotoAsset = withContext(Dispatchers.IO) {
        val videoSuffix = if (contentResolver.getType(videoUri) == QUICKTIME_MIME_TYPE) ".mov" else ".mp4"
        val video = cacheManager.copyUri(
            uri = videoUri,
            suffix = videoSuffix,
            maxBytes = LivePhotoCacheManager.MAX_VIDEO_FILE_BYTES,
        )
        try {
            if (!IsoBmffReader.hasFileTypeBox(video, 0L, video.length())) {
                throw IOException("Apple paired video has no valid file type box")
            }
            val metadata = LiveVideoInspector.inspect(video)
            val stillIdentifier = readAppleStillIdentifier(stillUri)
            val videoIdentifier = IsoBmffReader.readAppleContentIdentifier(video)
            val confidence = pairingConfidence(
                stillIdentifier = stillIdentifier,
                videoIdentifier = videoIdentifier,
                stillName = displayName(stillUri),
                videoName = displayName(videoUri),
            )
            return@withContext LivePhotoAsset(
                stillUri = stillUri,
                videoFile = video,
                kind = LivePhotoKind.APPLE_PAIRED,
                presentationTimestampUs = null,
                videoMetadata = metadata,
                pairingConfidence = confidence,
                detectionEvidence = buildList {
                    add(LiveDetectionEvidence.EXPLICIT_PAIR_SELECTION)
                    if (stillIdentifier != null) {
                        add(LiveDetectionEvidence.STILL_MAKER_NOTE_IDENTIFIER)
                    }
                    if (videoIdentifier != null) {
                        add(LiveDetectionEvidence.QUICKTIME_CONTENT_IDENTIFIER)
                    }
                    add(LiveDetectionEvidence.ISO_BMFF_FILE_TYPE)
                    add(LiveDetectionEvidence.VIDEO_TRACK_VERIFIED)
                },
            )
        } catch (error: Exception) {
            Log.e(TAG, "Apple Live Photo verification failed: ${error.javaClass.simpleName}")
            cacheManager.release(video)
            throw error
        }
    }

    fun release(asset: LivePhotoAsset?) {
        cacheManager.release(asset?.videoFile)
    }

    override fun close() {
        cacheManager.close()
    }

    private fun readExifMake(file: java.io.File): String? = runCatching {
        ExifInterface(file).getAttribute(ExifInterface.TAG_MAKE)
    }.getOrNull()

    private fun detectKind(make: String?, legacy: Boolean): LivePhotoKind {
        val normalized = make.orEmpty().lowercase(Locale.ROOT)
        return when {
            normalized.contains("xiaomi") || normalized.contains("redmi") ||
                normalized.contains("poco") -> LivePhotoKind.XIAOMI
            normalized.contains("vivo") -> LivePhotoKind.VIVO
            normalized.contains("oppo") -> LivePhotoKind.OPPO
            legacy -> LivePhotoKind.LEGACY_MICRO_VIDEO
            else -> LivePhotoKind.ANDROID_MOTION_PHOTO
        }
    }

    private fun readAppleStillIdentifier(uri: Uri): String? = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val makerNote = ExifInterface(descriptor.fileDescriptor)
                .getAttributeBytes(ExifInterface.TAG_MAKER_NOTE)
                ?: return@use null
            UUID_PATTERN.findAll(String(makerNote, StandardCharsets.ISO_8859_1))
                .map { match -> match.value }
                .distinctBy { value -> value.lowercase(Locale.ROOT) }
                .singleOrNull()
        }
    }.getOrNull()

    private fun pairingConfidence(
        stillIdentifier: String?,
        videoIdentifier: String?,
        stillName: String?,
        videoName: String?,
    ): PairingConfidence {
        if (stillIdentifier != null && videoIdentifier != null) {
            if (!stillIdentifier.equals(videoIdentifier, ignoreCase = true)) {
                throw IOException("Apple Live Photo identifiers do not match")
            }
            return PairingConfidence.VERIFIED
        }
        val stillBase = stillName?.substringBeforeLast('.')
        val videoBase = videoName?.substringBeforeLast('.')
        return if (!stillBase.isNullOrBlank() && stillBase.equals(videoBase, ignoreCase = true)) {
            PairingConfidence.PROBABLE
        } else {
            PairingConfidence.UNVERIFIED
        }
    }

    private fun displayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0) cursor.getString(column) else null
    }

    private companion object {
        const val TAG = "LivePhotoResolver"
        const val QUICKTIME_MIME_TYPE = "video/quicktime"
        val UUID_PATTERN = Regex(
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}",
        )
    }
}

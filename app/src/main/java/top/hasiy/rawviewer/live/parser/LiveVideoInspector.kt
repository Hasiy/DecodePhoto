/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.parser

import android.media.MediaExtractor
import android.media.MediaFormat
import top.hasiy.rawviewer.live.model.LiveVideoMetadata
import java.io.File
import java.io.IOException

object LiveVideoInspector {

    fun inspect(file: File): LiveVideoMetadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoMime: String? = null
            var width = 0
            var height = 0
            var durationUs = 0L
            var hasAudio = false
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith(VIDEO_MIME_PREFIX) && videoMime == null -> {
                        videoMime = mime
                        width = format.getIntegerOrZero(MediaFormat.KEY_WIDTH)
                        height = format.getIntegerOrZero(MediaFormat.KEY_HEIGHT)
                        durationUs = format.getLongOrZero(MediaFormat.KEY_DURATION)
                    }
                    mime.startsWith(AUDIO_MIME_PREFIX) -> hasAudio = true
                }
            }
            val resolvedMime = videoMime ?: throw IOException("Live Photo contains no video track")
            if (width <= 0 || height <= 0 || width > MAX_VIDEO_DIMENSION || height > MAX_VIDEO_DIMENSION) {
                throw IOException("Live Photo video dimensions exceed limits")
            }
            if (durationUs <= 0L || durationUs > MAX_VIDEO_DURATION_US) {
                throw IOException("Live Photo video duration exceeds limits")
            }
            return LiveVideoMetadata(
                mimeType = resolvedMime,
                width = width,
                height = height,
                durationUs = durationUs,
                hasAudio = hasAudio,
            )
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOrZero(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private fun MediaFormat.getLongOrZero(key: String): Long =
        if (containsKey(key)) getLong(key) else 0L

    private const val VIDEO_MIME_PREFIX = "video/"
    private const val AUDIO_MIME_PREFIX = "audio/"
    private const val MAX_VIDEO_DIMENSION = 8192
    private const val MAX_VIDEO_DURATION_US = 30L * 1_000_000L
}

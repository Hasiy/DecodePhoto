/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.model

import android.net.Uri
import java.io.File

enum class LivePhotoKind {
    APPLE_PAIRED,
    ANDROID_MOTION_PHOTO,
    LEGACY_MICRO_VIDEO,
    XIAOMI,
    VIVO,
    OPPO,
}

enum class PairingConfidence {
    VERIFIED,
    PROBABLE,
    UNVERIFIED,
}

enum class LiveDetectionEvidence {
    MOTION_PHOTO_XMP,
    LEGACY_MICRO_VIDEO_XMP,
    EXPLICIT_PAIR_SELECTION,
    STILL_MAKER_NOTE_IDENTIFIER,
    QUICKTIME_CONTENT_IDENTIFIER,
    ISO_BMFF_FILE_TYPE,
    VIDEO_TRACK_VERIFIED,
}

data class LiveVideoMetadata(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val hasAudio: Boolean,
    val colorTransfer: String = "SDR/UNKNOWN",
    val decoderSupportsFormat: Boolean = false,
)

data class LivePhotoAsset(
    val stillUri: Uri,
    val videoFile: File,
    val kind: LivePhotoKind,
    val presentationTimestampUs: Long?,
    val videoMetadata: LiveVideoMetadata,
    val pairingConfidence: PairingConfidence?,
    val detectionEvidence: List<LiveDetectionEvidence>,
)

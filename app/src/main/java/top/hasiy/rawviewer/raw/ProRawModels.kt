/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

/** Classification shown for a selected DNG file. */
enum class DngPhotoType {
    APPLE_PRORAW,
    POSSIBLE_APPLE_PRORAW,
    STANDARD_DNG,
}

/** Result of the ProRAW feature inspection. */
data class ProRawAssessment(
    val type: DngPhotoType,
    val evidence: Set<ProRawEvidence>,
)

enum class ProRawEvidence {
    EXPLICIT_PRORAW_NAME,
    APPLE_IPHONE_IDENTITY,
    PROFILE_GAIN_TABLE_MAP,
}

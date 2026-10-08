/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class ProRawDetector(context: Context) {
    private val applicationContext = context.applicationContext

    /** Classifies a DNG using public metadata plus structural DNG 1.6 tags. */
    suspend fun detect(
        uri: Uri,
        format: PhotoFormat,
        metadata: List<ExifEntry>,
    ): ProRawAssessment? {
        if (format != PhotoFormat.DNG) return null
        val tags = withContext(Dispatchers.IO) {
            val cached = PhotoInputResolver.copyToCache(applicationContext, uri, format.suffix)
            try {
                DngTiffTagScanner.scan(cached).tags
            } catch (error: Exception) {
                Log.w(TAG, "DNG structural inspection failed: ${error.javaClass.simpleName}")
                emptySet()
            } finally {
                PhotoInputResolver.deleteQuietly(cached)
            }
        }
        return ProRawClassifier.classify(metadata, tags)
    }

    private companion object {
        const val TAG = "ProRawDetector"
    }
}

internal object ProRawClassifier {
    private const val PROFILE_GAIN_TABLE_MAP_TAG = 52_525

    fun classify(metadata: List<ExifEntry>, tags: Set<Int>): ProRawAssessment {
        val normalized = metadata.associate { entry ->
            entry.tag.lowercase(Locale.ROOT) to entry.value.trim()
        }
        val make = normalized["make"].orEmpty()
        val model = normalized["model"].orEmpty()
        val allValues = normalized.values.joinToString(separator = " ")
        val hasExplicitName = allValues.contains("Apple ProRAW", ignoreCase = true)
        val hasAppleIPhoneIdentity = make.contains("Apple", ignoreCase = true) &&
            model.contains("iPhone", ignoreCase = true)
        val hasProfileGainTableMap = PROFILE_GAIN_TABLE_MAP_TAG in tags
        val evidence = buildSet {
            if (hasExplicitName) add(ProRawEvidence.EXPLICIT_PRORAW_NAME)
            if (hasAppleIPhoneIdentity) add(ProRawEvidence.APPLE_IPHONE_IDENTITY)
            if (hasProfileGainTableMap) add(ProRawEvidence.PROFILE_GAIN_TABLE_MAP)
        }
        val type = when {
            hasExplicitName || hasAppleIPhoneIdentity && hasProfileGainTableMap ->
                DngPhotoType.APPLE_PRORAW
            hasAppleIPhoneIdentity -> DngPhotoType.POSSIBLE_APPLE_PRORAW
            else -> DngPhotoType.STANDARD_DNG
        }
        return ProRawAssessment(type = type, evidence = evidence)
    }
}

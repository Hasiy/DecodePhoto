/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Modifier

class ExifMetadataReader(private val applicationContext: Context) {

    /** Returns every non-null EXIF/XMP attribute exposed by this ExifInterface version. */
    suspend fun read(uri: Uri): List<ExifEntry> = withContext(Dispatchers.IO) {
        applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            supportedTags.mapNotNull { tag ->
                exif.getAttribute(tag)?.let { value -> ExifEntry(tag = tag, value = value) }
            }
        }.orEmpty()
    }

    private companion object {
        const val TAG = "ExifMetadataReader"

        val supportedTags: List<String> by lazy {
            try {
                ExifInterface::class.java.fields
                    .asSequence()
                    .filter { field ->
                        field.type == String::class.java &&
                            Modifier.isStatic(field.modifiers) &&
                            field.name.startsWith("TAG_")
                    }
                    .mapNotNull { field -> field.get(null) as? String }
                    .distinct()
                    .sorted()
                    .toList()
            } catch (error: ReflectiveOperationException) {
                Log.e(TAG, "Unable to enumerate EXIF tags: ${error.javaClass.simpleName}")
                emptyList()
            }
        }
    }
}

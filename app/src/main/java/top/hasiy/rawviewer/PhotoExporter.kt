/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hasiy.rawviewer.hdr.HdrBitmapTools
import top.hasiy.rawviewer.live.model.LivePhotoAsset
import top.hasiy.rawviewer.live.cache.LiveVideoFiles
import top.hasiy.rawviewer.raw.ExifEntry
import top.hasiy.rawviewer.raw.ImageSizeCalculator

data class PhotoExportOptions(val png: Boolean = false, val maxLongEdge: Int = 2048,
    val quality: Int = 90, val removeGps: Boolean = true) {
    init { require(maxLongEdge in 256..4096 && quality in 1..100) }
    val mimeType: String get() = if (png) "image/png" else "image/jpeg"
}

object MetadataReport {
    fun text(entries: List<ExifEntry>): String = entries.joinToString("\n") { "${it.tag}: ${it.value}" }
    fun json(entries: List<ExifEntry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("tag", it.tag).put("value", it.value)) }
    }.toString(2)
}

object PhotoExporter {
    private const val RETENTION_MS = 24L * 60 * 60 * 1000
    private const val COPY_BUFFER_BYTES = 256 * 1024

    /**
     * Encodes an independently owned SDR rendition in private cache, then saves or shares it.
     * @param context application context.
     * @param source currently displayed Bitmap, kept alive by this call.
     * @param metadata original readable EXIF entries.
     * @param options output format, dimensions, JPEG quality and GPS policy.
     * @param destination SAF document; null creates a FileProvider share URI.
     * @return saved document or read-only share URI.
     */
    suspend fun photo(context: Context, source: Bitmap, metadata: List<ExifEntry>,
        options: PhotoExportOptions, destination: Uri? = null): Uri = withContext(Dispatchers.IO) {
        val file = newExportFile(context, if (options.png) ".png" else ".jpg")
        var owned: Bitmap? = null
        try {
            owned = withContext(Dispatchers.Default) { HdrBitmapTools.toSdr(source).also { owned = it } }
            val dimensions = ImageSizeCalculator.fitInside(owned.width, owned.height, options.maxLongEdge)
            if (dimensions.first != owned.width || dimensions.second != owned.height) {
                val scaled = Bitmap.createScaledBitmap(owned, dimensions.first, dimensions.second, true)
                owned.recycle(); owned = scaled
            }
            if (!options.png && owned.hasAlpha()) {
                val opaque = Bitmap.createBitmap(owned.width, owned.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(owned, 0f, 0f, null) }
                owned.recycle(); owned = opaque
            }
            currentCoroutineContext().ensureActive()
            file.outputStream().use { output ->
                if (!owned.compress(if (options.png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    options.quality, output)) throw IOException("Encoder rejected bitmap")
            }
            if (!options.png) writeExif(file, metadata, options.removeGps)
            currentCoroutineContext().ensureActive()
            if (destination != null) { copy(context, file, destination); destination }
            else FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        } catch (error: Throwable) { file.delete(); throw error }
        finally { owned?.recycle(); if (destination != null) file.delete() }
    }

    suspend fun report(context: Context, destination: Uri, entries: List<ExifEntry>, json: Boolean) =
        withContext(Dispatchers.IO) {
            val content = if (json) MetadataReport.json(entries) else MetadataReport.text(entries)
            context.contentResolver.openOutputStream(destination, "wt")?.bufferedWriter()?.use { it.write(content) }
                ?: throw IOException("Unable to write metadata report")
        }

    suspend fun video(context: Context, asset: LivePhotoAsset, destination: Uri) {
        val lease = LiveVideoFiles.retain(asset.videoFile)
        try { withContext(Dispatchers.IO) { copy(context, asset.videoFile, destination) } }
        finally { lease.close() }
    }

    suspend fun frame(context: Context, asset: LivePhotoAsset, positionUs: Long, destination: Uri): Uri {
        val lease = LiveVideoFiles.retain(asset.videoFile)
        var owned: Bitmap? = null
        try {
        val bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val frame = try {
                retriever.setDataSource(asset.videoFile.absolutePath)
                val position = positionUs.coerceIn(0L, (asset.videoMetadata.durationUs - 1).coerceAtLeast(0L))
                val size = ImageSizeCalculator.fitInside(asset.videoMetadata.width, asset.videoMetadata.height, 2048)
                if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(position,
                    MediaMetadataRetriever.OPTION_CLOSEST, size.first, size.second)
                else retriever.getFrameAtTime(position, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally { retriever.release() }
            frame?.also { owned = it } ?: throw IOException("Video returned no frame")
        }
        return photo(context, bitmap, emptyList(), PhotoExportOptions(), destination)
        } finally { owned?.recycle(); lease.close() }
    }

    private suspend fun copy(context: Context, file: File, destination: Uri) {
        file.inputStream().use { input ->
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Unable to open export destination")
            output.use {
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    it.write(buffer, 0, read)
                }
            }
        }
    }
    private fun newExportFile(context: Context, suffix: String): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - RETENTION_MS }
            ?.forEach { it.delete() }
        return File.createTempFile("photo_", suffix, directory)
    }
    private fun writeExif(file: File, entries: List<ExifEntry>, removeGps: Boolean) {
        val allowed = setOf("Make", "Model", "LensModel", "DateTimeOriginal", "DateTimeDigitized",
            "FNumber", "ExposureTime", "PhotographicSensitivity", "FocalLength", "Artist", "Copyright")
        ExifInterface(file).apply {
            entries.filter { it.tag in allowed || (!removeGps && it.tag.startsWith("GPS")) }
                .forEach { setAttribute(it.tag, it.value) }
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }
    }
}

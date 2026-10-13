/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import top.hasiy.rawviewer.hdr.*
import top.hasiy.rawviewer.raw.*
import top.hasiy.libheif.HeifDynamicRange
import top.hasiy.libheif.HdrToneMapper
import top.hasiy.libheif.LibHeifNativeDecoder
import top.hasiy.libraw.LibRawNativeDecoder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import top.hasiy.rawviewer.live.model.*
import top.hasiy.rawviewer.live.parser.LiveVideoInspector
import top.hasiy.rawviewer.live.cache.LiveVideoFiles

class PhotoPipelineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name: String): File = File(context.cacheDir, "exports/$name").apply { parentFile!!.mkdirs() }
    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)

    @Test fun systemDecode_rotatedJpeg_appliesOrientationAndBound() = runBlocking<Unit> {
        val file = fixture("orientation.jpg")
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        source.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
        }
        val bitmap = SystemPhotoDecoder(context).decode(uri(file), PhotoFormat.JPEG, 40).bitmap
        try { assertEquals(20, bitmap.width); assertEquals(40, bitmap.height) }
        finally { bitmap.recycle(); PhotoInputResolver.invalidate(uri(file)); file.delete() }
    }

    @Test fun nativeHeif_fixedFixture_decodesAndPreservesAlpha() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        for (name in listOf("rainbow-451x461.heic", "with-alpha-512x512.heic")) {
            val file = fixture(name)
            testContext.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            try {
                val result = LibHeifNativeDecoder().decode(file, 256)
                try {
                    assertTrue(result.bitmap.width <= 256 && result.bitmap.height <= 256)
                    if (name.startsWith("with-alpha")) {
                        assertTrue(result.sourceInfo.hasAlphaChannel)
                        var translucent = false
                        for (y in 0 until result.bitmap.height step 8) for (x in 0 until result.bitmap.width step 8) {
                            if (Color.alpha(result.bitmap.getPixel(x, y)) < 255) translucent = true
                        }
                        assertTrue(translucent)
                    }
                } finally { result.bitmap.recycle() }
            } finally { file.delete() }
        }
    }

    @Test fun nativeRaw_syntheticDng_boundsOutputAndAppliesExposure() {
        val file = fixture("synthetic.dng")
        SyntheticDng.write(file)
        val decoder = LibRawNativeDecoder()
        try {
            decoder.prepareRequest(900001)
            val base = decoder.decode(file, false, 900001, 32, -1f, 2)
            decoder.prepareRequest(900002)
            val bright = decoder.decode(file, false, 900002, 32, 1f, 2)
            try {
                assertTrue(base.width <= 32 && base.height <= 32)
                assertEquals(base.width, bright.width)
                assertTrue(Color.red(bright.getPixel(bright.width / 2, bright.height / 2)) >
                    Color.red(base.getPixel(base.width / 2, base.height / 2)))
            } finally { base.recycle(); bright.recycle() }
        } finally { file.delete() }
    }

    @Test fun liveVideo_extractAndSaveFrame_preservesContainerAndDecodesFrame() = runBlocking<Unit> {
        val source = fixture("synthetic-live.mp4")
        SyntheticVideo.write(source)
        LiveVideoFiles.register(source)
        val output = fixture("extracted-live.mp4")
        val frame = fixture("live-frame.jpg")
        try {
            val metadata = LiveVideoInspector.inspect(source)
            val asset = LivePhotoAsset(uri(source), source, LivePhotoKind.ANDROID_MOTION_PHOTO,
                500000L, metadata, null, emptyList())
            PhotoExporter.video(context, asset, uri(output))
            assertArrayEquals(source.readBytes(), output.readBytes())
            PhotoExporter.frame(context, asset, 500000L, uri(frame))
            val image = BitmapFactory.decodeFile(frame.absolutePath)
            assertNotNull(image)
            try { assertEquals(256, image.width); assertEquals(256, image.height) }
            finally { image.recycle() }
            assertTrue(metadata.decoderSupportsFormat)
        } finally { LiveVideoFiles.release(source); output.delete(); frame.delete() }
    }

    @Test fun progressiveRaw_mislabeledPayload_keepsUsablePreviewInViewModel() = runBlocking<Unit> {
        val file = fixture("broken-with-preview.dng")
        val thumb = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val bytes = ByteArrayOutputStream().apply { thumb.compress(Bitmap.CompressFormat.JPEG, 95, this) }.toByteArray()
        thumb.recycle()
        JpegWithThumbnail.write(file, bytes)
        val fixturePreview = EmbeddedPreviewDecoder(context).decode(uri(file))
        assertNotNull("Mislabeled RAW fixture must expose a decodable JPEG thumbnail", fixturePreview)
        fixturePreview?.recycle()
        // Use a model without automatic session restore, preserving the user's persisted history.
        lateinit var model: RawViewerViewModel
        val viewModelStore = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RawViewerViewModel(context.applicationContext as android.app.Application, false, false) as T
            })[RawViewerViewModel::class.java]
            model.onFileSelected(uri(file))
        }
        try {
            val result = withTimeout(15000) {
                model.uiState.first { (it.content as? RawContentState.Success)?.warningRes != null || it.content is RawContentState.Error }
            }.content
            assertTrue("Expected retained preview, got ${result.javaClass.simpleName}", result is RawContentState.Success)
            result as RawContentState.Success
            assertEquals(RawDecodeResult.Source.EMBEDDED_PREVIEW, result.source)
            assertFalse(result.bitmap.isRecycled)
            assertFalse(result.isRefining)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModelStore.clear() }
            PhotoInputResolver.invalidate(uri(file)); file.delete()
        }
    }

    @Test fun export_jpegNormalizesOrientationAndRemovesGps() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val target = fixture("export-gps.jpg")
        val entries = listOf(ExifEntry("Make", "Synthetic"), ExifEntry("GPSLatitude", "25/1,0/1,0/1"),
            ExifEntry("GPSLatitudeRef", "N"), ExifEntry("Orientation", "6"))
        try {
            PhotoExporter.photo(context, source, entries, PhotoExportOptions(maxLongEdge = 256), uri(target))
            val exif = ExifInterface(target)
            assertEquals("Synthetic", exif.getAttribute(ExifInterface.TAG_MAKE))
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertEquals(ExifInterface.ORIENTATION_NORMAL,
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        } finally { source.recycle(); target.delete() }
    }

    @Test fun export_pngPreservesAlphaAndJsonEscapesMetadata() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0x8044AA66.toInt()) }
        val target = fixture("alpha.png")
        try {
            PhotoExporter.photo(context, source, emptyList(), PhotoExportOptions(png = true), uri(target))
            val result = BitmapFactory.decodeFile(target.absolutePath)
            try { assertEquals(128, Color.alpha(result.getPixel(0, 0))) } finally { result.recycle() }
            val report = MetadataReport.json(listOf(ExifEntry("Xmp", "\"quoted\"\n<xml>")))
            assertEquals("\"quoted\"\n<xml>", JSONArray(report).getJSONObject(0).getString("value"))
        } finally { source.recycle(); target.delete() }
    }

    @Test fun inputs_twoLeasesRetirement_keepsFileUntilFinalRelease() = runBlocking<Unit> {
        val source = fixture("cache.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val document = uri(source)
        val first = PhotoInputResolver.acquire(context, document, ".jpg")
        val second = PhotoInputResolver.acquire(context, document, ".jpg")
        assertEquals(first.file, second.file)
        PhotoInputResolver.invalidate(document)
        first.close()
        assertTrue(second.file.exists())
        second.close()
        assertFalse(second.file.exists())
        source.delete()
    }

    @Test fun sessions_encryptedStore_restoresModeAndRawSettings() = runBlocking<Unit> {
        val store = PhotoSessionStore(context, "test_sessions")
        store.clear()
        val session = PhotoSession(Uri.parse("content://fixtures/private.dng"), format = "DNG",
            mode = DecodeMode.LIBRARY, options = RawRenderOptions(1f, RawWhiteBalance.AUTO, false))
        try {
            store.remember(session)
            assertEquals(session, store.read().single())
            val packet = context.getSharedPreferences("test_sessions", 0).getString("data", "")!!
            assertFalse(packet.contains("private.dng"))
            assertFalse(String(android.util.Base64.decode(packet, 0)).contains("private.dng"))
        } finally { store.clear() }
    }

    @Test fun systemDecode_truncatedJpeg_failsWithoutLeavingActiveLease() = runBlocking<Unit> {
        val file = fixture("truncated.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1)) }
        val document = uri(file)
        assertTrue(runCatching { SystemPhotoDecoder(context).decode(document, PhotoFormat.JPEG) }.isFailure)
        PhotoInputResolver.invalidate(document)
        file.delete()
    }

    @Test fun pqToneMapping_f16ReferenceWhite_producesFiniteSdr() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT < 34) return@runBlocking
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.BT2020_PQ))
        val pixel = Color.valueOf(0.5080784f, 0.5080784f, 0.5080784f, 1f, source.colorSpace!!)
        source.eraseColor(pixel.pack())
        val target = HdrToneMapper.map(source, HeifDynamicRange.PQ)
        try {
            val histogram = HdrLuminanceAnalyzer.calculate(source)
            assertEquals(100.0 / 203.0, histogram.mean, 0.01)
            val color = target.getPixel(0, 0)
            assertTrue(Color.red(color) in 180..190)
            assertEquals(Color.red(color), Color.green(color))
        } finally { source.recycle(); target.recycle() }
    }

    @Test fun ultraHdr_gainmapReconstructionAndSdrExport_areIndependent() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT < 34) return@runBlocking
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val map = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        source.gainmap = Gainmap(map).apply { setRatioMax(4f, 4f, 4f) }
        val histogram = HdrLuminanceAnalyzer.calculate(source)
        val base = HdrBitmapTools.toSdr(source)
        val preview = HdrLuminanceAnalyzer.gainmapPreview(source)!!
        try {
            assertEquals(4.0, histogram.mean, 0.01)
            assertTrue(source.hasGainmap()); assertFalse(base.hasGainmap())
            assertEquals(Color.WHITE, preview.getPixel(0, 0))
        } finally { source.recycle(); map.recycle(); base.recycle(); preview.recycle() }
    }
}

/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import top.hasiy.rawviewer.hdr.HdrComparisonDecoder
import top.hasiy.rawviewer.hdr.HdrComparisonUiState
import top.hasiy.rawviewer.raw.PhotoFormat
import top.hasiy.rawviewer.raw.PhotoInputResolver
import top.hasiy.rawviewer.raw.SystemPhotoDecoder

class PhotoReviewRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(suffix: String): File = File.createTempFile("review_", suffix,
        File(context.cacheDir, "exports").apply { mkdirs() })
    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)

    @Test fun acquire_concurrentInvalidationAndEviction_keepsLeasedFilesReadable() = runBlocking<Unit> {
        val files = List(4) { fixture(".jpg").apply { writeBytes(ByteArray(1024) { 42 }) } }
        val documents = files.map(::uri)
        val started = CompletableDeferred<Unit>()
        val invalidator = launch(Dispatchers.IO) {
            started.complete(Unit)
            while (isActive) {
                PhotoInputResolver.invalidate(documents.first())
                yield()
            }
        }
        try {
            started.await()
            withTimeout(TEST_TIMEOUT_MS) {
                coroutineScope {
                    List(3) { worker -> launch(Dispatchers.IO) {
                        repeat(LEASE_ITERATIONS) { iteration ->
                            val document = documents[if (worker == 0) 0 else (iteration % 3) + 1]
                            PhotoInputResolver.acquire(context, document, ".jpg").use { lease ->
                                yield()
                                assertArrayEquals(ByteArray(1024) { 42 }, lease.file.readBytes())
                            }
                        }
                    } }.joinAll()
                }
            }
        } finally {
            invalidator.cancelAndJoin()
            documents.forEach(PhotoInputResolver::invalidate)
            files.forEach { it.delete() }
        }
    }

    @Test fun restore_hiddenHdrModel_waitsUntilScreenOpenedAndRunsOnce() = runBlocking<Unit> {
        val file = fixture(".jpg")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val store = PhotoSessionStore(context, TEST_SESSION_NAMESPACE)
        store.clear()
        store.remember(PhotoSession(uri(file), format = "HDR"))
        val viewModelStore = ViewModelStore()
        lateinit var model: HdrComparisonViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(viewModelStore, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HdrComparisonViewModel(context.applicationContext as Application, TEST_SESSION_NAMESPACE) as T
            })[HdrComparisonViewModel::class.java]
        }
        try {
            // Allow queued Main dispatcher tasks to run; construction must not decode the saved URI.
            instrumentation.waitForIdleSync()
            delay(RESTORE_OBSERVATION_MS)
            assertSame(HdrComparisonUiState.Empty, model.uiState.value)
            instrumentation.runOnMainSync { model.onScreenOpened() }
            val loaded = withTimeout(TEST_TIMEOUT_MS) {
                model.uiState.first { it is HdrComparisonUiState.Success || it is HdrComparisonUiState.Error }
            }
            assertTrue("Expected restored HDR image, got $loaded", loaded is HdrComparisonUiState.Success)
            instrumentation.runOnMainSync { model.onScreenOpened() }
            instrumentation.waitForIdleSync()
            assertSame(loaded, model.uiState.value)
        } finally {
            instrumentation.runOnMainSync { viewModelStore.clear() }
            store.clear()
            PhotoInputResolver.invalidate(uri(file))
            file.delete()
        }
    }

    @Test fun decode_sdrHeifWithP3Icc_preservesOriginalWideGamut() = runBlocking<Unit> {
        val file = fixture(".heic")
        val bytes = instrumentation.context.assets.open("rainbow-451x461.heic").use { it.readBytes() }
        replaceIccPrimariesWithP3(bytes)
        file.writeBytes(bytes)
        var system: Bitmap? = null
        var original: Bitmap? = null
        try {
            system = SystemPhotoDecoder(context).decode(uri(file), PhotoFormat.HEIC, 2048).bitmap
            assertTrue("Fixture must be decoded as wide gamut by the platform", system.colorSpace?.isWideGamut == true)
            val image = HdrComparisonDecoder(context).decode(uri(file))
            original = image.originalBitmap
            assertTrue(image.originalBitmap.colorSpace?.isWideGamut == true)
            assertEquals(system.colorSpace, image.originalBitmap.colorSpace)
            assertEquals(system.getPixel(100, 100), image.originalBitmap.getPixel(100, 100))
            assertSame(image.originalBitmap, image.sdrBitmap)
        } finally {
            system?.recycle(); original?.recycle()
            PhotoInputResolver.invalidate(uri(file)); file.delete()
        }
    }

    /** Changes only the existing ICC XYZ primary tags; HEIF pixels and transfer curve stay fixed. */
    private fun replaceIccPrimariesWithP3(bytes: ByteArray) {
        val profileStart = String(bytes, Charsets.ISO_8859_1).indexOf("prof") + 4
        check(profileStart >= 4)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = data.getInt(profileStart + 128)
        val p3 = ColorSpace.adapt(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), ColorSpace.ILLUMINANT_D50) as ColorSpace.Rgb
        val matrix = p3.transform
        val tags = listOf("rXYZ", "gXYZ", "bXYZ")
        val replaced = mutableSetOf<String>()
        repeat(count) { index ->
            val record = profileStart + 132 + index * 12
            val tag = String(bytes, record, 4, Charsets.US_ASCII)
            val column = tags.indexOf(tag)
            if (column >= 0) {
                val xyz = profileStart + data.getInt(record + 4) + 8
                repeat(3) { row -> data.putInt(xyz + row * 4, (matrix[column * 3 + row] * 65536f).roundToInt()) }
                replaced += tag
            }
        }
        check(replaced.size == 3)
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 30_000L
        const val LEASE_ITERATIONS = 300
        const val RESTORE_OBSERVATION_MS = 250L
        const val TEST_SESSION_NAMESPACE = "test_hdr_lazy_restore"
    }
}

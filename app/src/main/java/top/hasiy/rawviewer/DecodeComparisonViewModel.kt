/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import top.hasiy.rawviewer.hdr.HdrBitmapTools
import top.hasiy.rawviewer.raw.*

data class DecodeComparisonState(val left: Bitmap? = null, val right: Bitmap? = null,
    val loading: Boolean = false, val errorRes: Int? = null)

class DecodeComparisonViewModel(application: Application) : AndroidViewModel(application) {
    private val raw = LibRawDecoder(application)
    private val system = SystemPhotoDecoder(application)
    private val heif = LibHeifDecoder(application)
    private val preview = EmbeddedPreviewDecoder(application)
    private val state = MutableStateFlow(DecodeComparisonState())
    val uiState = state.asStateFlow()
    private var job: Job? = null

    fun load(uri: Uri, format: PhotoFormat, left: DecodeMode, right: DecodeMode, options: RawRenderOptions) {
        job?.cancel()
        state.value = DecodeComparisonState(loading = true)
        job = viewModelScope.launch {
            var a: Bitmap? = null
            var b: Bitmap? = null
            try {
                a = decode(uri, format, left, options)
                b = decode(uri, format, right, options)
                ensureActive()
                state.value = DecodeComparisonState(a, b)
            } catch (error: CancellationException) {
                a?.recycle(); b?.recycle(); throw error
            } catch (error: Throwable) {
                a?.recycle(); b?.recycle()
                Log.w("DecodeCompare", "Comparison failed: ${error.javaClass.simpleName}")
                state.value = DecodeComparisonState(errorRes = if (error is NoEmbeddedPreviewException)
                    R.string.compare_no_preview else PhotoFailure.message(error))
            }
        }
    }
    fun clear() { job?.cancel(); state.value = DecodeComparisonState() }
    private suspend fun decode(uri: Uri, format: PhotoFormat, mode: DecodeMode, options: RawRenderOptions): Bitmap {
        val result = when (mode) {
            DecodeMode.PREVIEW -> preview.decode(uri) ?: throw NoEmbeddedPreviewException()
            DecodeMode.SYSTEM -> system.decode(uri, format, PhotoMemoryPolicy.COMPARISON_LONG_EDGE).bitmap
            else -> if (format.isRaw) raw.decode(uri, format, options, PhotoMemoryPolicy.COMPARISON_LONG_EDGE)
                else heif.decode(uri, format, PhotoMemoryPolicy.COMPARISON_LONG_EDGE, forceSdr = true).bitmap
        }
        return try { withContext(Dispatchers.Default) { HdrBitmapTools.toSdr(result) } }
        finally { result.recycle() }
    }
    override fun onCleared() { job?.cancel(); raw.close(); super.onCleared() }
    private class NoEmbeddedPreviewException : Exception()
}

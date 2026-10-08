/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.hasiy.rawviewer.hdr.HdrComparisonDecodeException
import top.hasiy.rawviewer.hdr.HdrComparisonDecoder
import top.hasiy.rawviewer.hdr.HdrComparisonError
import top.hasiy.rawviewer.hdr.HdrComparisonImage
import top.hasiy.rawviewer.hdr.HdrComparisonUiState

class HdrComparisonViewModel(application: Application) : AndroidViewModel(application) {

    private val decoder = HdrComparisonDecoder(application)
    private val mutableUiState = MutableStateFlow<HdrComparisonUiState>(HdrComparisonUiState.Empty)
    val uiState: StateFlow<HdrComparisonUiState> = mutableUiState.asStateFlow()

    private var decodeJob: Job? = null
    private var selectedUri: Uri? = null

    fun onPhotoSelected(uri: Uri) {
        selectedUri = uri
        decode(uri)
    }

    fun retry() {
        selectedUri?.let(::decode)
    }

    /** Recycles a rendition only after Compose stopped displaying it and state no longer owns it. */
    fun onImageDisposed(image: HdrComparisonImage) {
        val currentImage = (mutableUiState.value as? HdrComparisonUiState.Success)?.image
        if (currentImage !== image) recycleImage(image)
    }

    private fun decode(uri: Uri) {
        decodeJob?.cancel()
        mutableUiState.value = HdrComparisonUiState.Loading
        decodeJob = viewModelScope.launch {
            try {
                val image = decoder.decode(uri)
                mutableUiState.value = HdrComparisonUiState.Success(image)
            } catch (error: CancellationException) {
                throw error
            } catch (error: HdrComparisonDecodeException) {
                Log.e(TAG, "HDR comparison decode rejected: ${error.reason}", error)
                mutableUiState.value = HdrComparisonUiState.Error(error.reason)
            } catch (error: SecurityException) {
                Log.e(TAG, "HDR comparison image access denied", error)
                mutableUiState.value = HdrComparisonUiState.Error(HdrComparisonError.ACCESS_DENIED)
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "HDR comparison decode ran out of memory", error)
                mutableUiState.value = HdrComparisonUiState.Error(HdrComparisonError.OUT_OF_MEMORY)
            } catch (error: IOException) {
                Log.e(TAG, "HDR comparison image is unsupported or damaged", error)
                mutableUiState.value = HdrComparisonUiState.Error(HdrComparisonError.UNSUPPORTED_IMAGE)
            } catch (error: RuntimeException) {
                Log.e(TAG, "HDR comparison decode failed: ${error.javaClass.simpleName}", error)
                mutableUiState.value = HdrComparisonUiState.Error(HdrComparisonError.UNKNOWN)
            }
        }
    }

    private fun releaseCurrentImage() {
        val success = mutableUiState.value as? HdrComparisonUiState.Success ?: return
        recycleImage(success.image)
    }

    private fun recycleImage(image: HdrComparisonImage) {
        if (!image.originalBitmap.isRecycled) image.originalBitmap.recycle()
        if (image.sdrBitmap !== image.originalBitmap && !image.sdrBitmap.isRecycled) {
            image.sdrBitmap.recycle()
        }
    }

    override fun onCleared() {
        decodeJob?.cancel()
        releaseCurrentImage()
        super.onCleared()
    }

    private companion object {
        const val TAG = "HdrComparisonVM"
    }
}

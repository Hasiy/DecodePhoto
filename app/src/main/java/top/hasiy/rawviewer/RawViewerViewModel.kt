/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import top.hasiy.rawviewer.live.detect.LivePhotoResolver
import top.hasiy.rawviewer.live.model.LivePhotoAsset
import top.hasiy.rawviewer.raw.AndroidNativeImageDecoder
import top.hasiy.rawviewer.raw.EmbeddedPreviewDecoder
import top.hasiy.rawviewer.raw.ExifEntry
import top.hasiy.rawviewer.raw.ExifMetadataReader
import top.hasiy.rawviewer.raw.LibHeifDecoder
import top.hasiy.rawviewer.raw.LibRawDecoder
import top.hasiy.rawviewer.raw.PhotoFormat
import top.hasiy.rawviewer.raw.PhotoFormatResolver
import top.hasiy.rawviewer.raw.DecodedColorInfo
import top.hasiy.rawviewer.raw.PhotoDecodeOutput
import top.hasiy.rawviewer.raw.ProRawAssessment
import top.hasiy.rawviewer.raw.ProRawDetector
import top.hasiy.rawviewer.raw.ProgressiveRawImageDecoder
import top.hasiy.rawviewer.raw.RawDecodeResult
import top.hasiy.rawviewer.raw.SystemPhotoDecoder
import top.hasiy.rawviewer.raw.RgbHistogram
import top.hasiy.rawviewer.raw.RgbHistogramCalculator
import top.hasiy.libheif.LibHeifContainerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import top.hasiy.rawviewer.raw.PhotoInputResolver
import top.hasiy.rawviewer.raw.PhotoFailure
import top.hasiy.rawviewer.raw.RawRenderOptions

class RawViewerViewModel @JvmOverloads constructor(application: Application,
    private val restoreSession: Boolean = true, private val persistSession: Boolean = true) : AndroidViewModel(application) {

    private val formatResolver = PhotoFormatResolver(application)
    private val metadataReader = ExifMetadataReader(application)
    private val proRawDetector = ProRawDetector(application)
    private val embeddedPreviewDecoder = EmbeddedPreviewDecoder(application)
    private val nativeImageDecoder = AndroidNativeImageDecoder(application)
    private val systemPhotoDecoder = SystemPhotoDecoder(application)
    private val libHeifDecoder = LibHeifDecoder(application)
    private val libRawDecoder = LibRawDecoder(application)
    private val livePhotoResolver = LivePhotoResolver(application)
    private val progressiveDecoder = ProgressiveRawImageDecoder(
        embeddedPreviewDecoder = embeddedPreviewDecoder,
        libRawDecoder = libRawDecoder,
    )
    private val operationCounter = AtomicLong(0L)

    private var selectedPhoto: SelectedPhoto? = null
    private var selectedLiveAsset: LivePhotoAsset? = null
    private var decodeJob: Job? = null
    private var selectionJob: Job? = null
    private var histogramJob: Job? = null
    private var selectionVersion = 0L
    private var lastMode = DecodeMode.AUTO
    private var selectedVideoUri: Uri? = null
    private val sessionStore = PhotoSessionStore(application)

    private val mutableUiState = MutableStateFlow(RawViewerUiState())
    val uiState: StateFlow<RawViewerUiState> = mutableUiState.asStateFlow()

    fun compareWith(model: DecodeComparisonViewModel, left: DecodeMode, right: DecodeMode) {
        selectedPhoto?.let { model.load(it.uri, it.format, left, right, mutableUiState.value.rawOptions) }
    }

    init {
        viewModelScope.launch {
            val version = selectionVersion
            val sessions = sessionStore.read()
            mutableUiState.update { it.copy(recentSessions = sessions) }
            if (restoreSession && selectionVersion == version) sessions.firstOrNull()?.let(::onRecentSelected)
        }
    }

    fun onRecentSelected(session: PhotoSession) {
        mutableUiState.update { it.copy(rawOptions = session.options) }
        lastMode = session.mode
        startSelection(R.string.photo_error_access, session.mode) {
            PendingSelection(session.uri, session.videoUri)
        }
    }

    fun onClearRecent() {
        viewModelScope.launch {
            sessionStore.clear()
            mutableUiState.update { it.copy(recentSessions = emptyList()) }
        }
    }

    fun onRawOptionsChanged(options: RawRenderOptions) {
        mutableUiState.update { it.copy(rawOptions = options) }
        onDecodeRequested(DecodeMode.LIBRARY)
    }

    private fun rememberSelection() {
        if (!persistSession) return
        val photo = selectedPhoto ?: return
        val session = PhotoSession(photo.uri, selectedVideoUri, photo.format.name,
            mode = lastMode, options = mutableUiState.value.rawOptions)
        viewModelScope.launch {
            try {
                val recent = sessionStore.remember(session)
                mutableUiState.update { it.copy(recentSessions = recent) }
            } catch (error: Exception) {
                Log.w(TAG, "Session save failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun onFileSelected(uri: Uri) {
        startSelection(invalidSelectionMessage = R.string.unsupported_file) {
            PendingSelection(stillUri = uri, pairedVideoUri = null)
        }
    }

    fun onAppleLivePhotoSelected(uris: List<Uri>) {
        startSelection(invalidSelectionMessage = R.string.apple_pair_error) {
            classifyAppleSelection(uris)
        }
    }

    fun onDecodeRequested(mode: DecodeMode) {
        val selection = selectedPhoto
        if (selection == null) {
            mutableUiState.update { state ->
                state.copy(content = RawContentState.Empty(R.string.no_file_selected))
            }
            return
        }
        if (
            mode == DecodeMode.NATIVE &&
            (!selection.format.supportsNativeDecoder || !nativeImageDecoder.isSupported)
        ) {
            mutableUiState.update { state ->
                state.copy(content = RawContentState.Error(R.string.decode_android_hint))
            }
            return
        }

        cancelCurrentOperation()
        lastMode = mode
        rememberSelection()
        val operationId = operationCounter.incrementAndGet()
        decodeJob = viewModelScope.launch {
            mutableUiState.update { state -> state.copy(content =
                (state.content as? RawContentState.Success)?.copy(isRefining = true, warningRes = null)
                    ?: RawContentState.Loading) }
            try {
                when (mode) {
                    DecodeMode.PREVIEW -> decodeEmbedded(selection, operationId)
                    DecodeMode.SYSTEM -> decodeWithSystem(selection, operationId)
                    DecodeMode.NATIVE -> decodeWithNative(selection, operationId)
                    DecodeMode.LIBRARY -> decodeWithLibrary(selection, operationId)
                    DecodeMode.AUTO -> decodeAutomatically(selection, operationId)
                }
            } catch (error: CancellationException) {
                if (operationId == operationCounter.get()) {
                    mutableUiState.update { state ->
                        state.copy(content = RawContentState.Empty(R.string.status_cancelled))
                    }
                }
                throw error
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "RAW decoding failed: OutOfMemoryError")
                showErrorIfCurrent(operationId, PhotoFailure.message(error))
            } catch (error: Exception) {
                Log.e(TAG, "RAW decoding failed: ${error.javaClass.simpleName}")
                showErrorIfCurrent(operationId, PhotoFailure.message(error))
            }
        }
    }

    fun onCancelClick() {
        operationCounter.incrementAndGet()
        cancelCurrentOperation()
        if (selectedPhoto == null) selectionJob?.cancel()
        mutableUiState.update { state ->
            state.copy(content = (state.content as? RawContentState.Success)
                ?.copy(isRefining = false, warningRes = R.string.status_cancelled)
                ?: RawContentState.Empty(R.string.status_cancelled))
        }
    }

    fun onPlayLiveClick() {
        if (mutableUiState.value.liveContent is LiveContentState.Ready) {
            mutableUiState.update { state -> state.copy(isLivePlaying = true) }
        }
    }

    fun onStopLivePlayback() {
        mutableUiState.update { state -> state.copy(isLivePlaying = false) }
    }

    fun onToggleLiveMute() {
        mutableUiState.update { state -> state.copy(isLiveMuted = !state.isLiveMuted) }
    }

    fun onLivePlaybackError() {
        Log.e(TAG, "Live Photo playback failed")
        mutableUiState.update { state ->
            state.copy(
                isLivePlaying = false,
                hasLivePlaybackError = true,
            )
        }
    }

    /** Published bitmaps are shared safely; Android releases storage after all owners let go. */
    fun onBitmapDisposed(bitmap: Bitmap) {
        // Published bitmaps may still be used by full-screen, comparison or export workers.
        // Android 8+ releases their native storage when the last strong reference is collected.
    }

    override fun onCleared() {
        cancelCurrentOperation()
        selectionJob?.cancel()
        histogramJob?.cancel()
        releaseCurrentLiveAsset()
        livePhotoResolver.close()
        libRawDecoder.close()
        super.onCleared()
    }

    private fun startSelection(
        @StringRes invalidSelectionMessage: Int,
        autoMode: DecodeMode = DecodeMode.AUTO,
        selectionProvider: suspend () -> PendingSelection,
    ) {
        cancelCurrentOperation()
        selectionJob?.cancel()
        releaseCurrentLiveAsset()
        selectedPhoto = null
        selectedVideoUri = null
        operationCounter.incrementAndGet()
        val operationId = ++selectionVersion
        selectionJob = viewModelScope.launch {
            mutableUiState.value = RawViewerUiState(
                recentSessions = mutableUiState.value.recentSessions,
                rawOptions = mutableUiState.value.rawOptions,
                isMetadataLoading = true,
                liveContent = LiveContentState.Detecting,
                content = RawContentState.Loading,
            )
            try {
                val pending = selectionProvider()
                val format = formatResolver.resolve(pending.stillUri)
                    ?: throw IllegalArgumentException("Unsupported still image")
                if (pending.pairedVideoUri != null && format !in APPLE_STILL_FORMATS) {
                    throw IllegalArgumentException("Unsupported Apple still image")
                }
                if (operationId != selectionVersion) return@launch

                PhotoInputResolver.invalidate(pending.stillUri)
                selectedPhoto = SelectedPhoto(pending.stillUri, format)
                selectedVideoUri = pending.pairedVideoUri
                mutableUiState.update { it.copy(format = format,
                    isNativeDecoderAvailable = nativeImageDecoder.isSupported) }
                onDecodeRequested(autoMode)
                val metadataResult = captureResult { metadataReader.read(pending.stillUri) }
                metadataResult.exceptionOrNull()?.let { error ->
                    Log.e(TAG, "EXIF reading failed: ${error.javaClass.simpleName}")
                }
                val metadata = metadataResult.getOrDefault(emptyList())
                val proRawResult = captureResult {
                    proRawDetector.detect(pending.stillUri, format, metadata)
                }
                proRawResult.exceptionOrNull()?.let { error ->
                    Log.e(TAG, "ProRAW detection failed: ${error.javaClass.simpleName}")
                }
                val heifInfoResult: Result<LibHeifContainerInfo?> = if (format.isHeif) {
                    captureResult { libHeifDecoder.inspect(pending.stillUri, format) }
                } else {
                    Result.success(null)
                }
                heifInfoResult.exceptionOrNull()?.let { error ->
                    Log.e(TAG, "HEIF inspection failed: ${error.javaClass.simpleName}")
                }

                val liveResult = captureResult {
                    pending.pairedVideoUri?.let { videoUri ->
                        livePhotoResolver.resolveApplePair(pending.stillUri, videoUri)
                    } ?: if (format.isRaw) {
                        null
                    } else {
                        livePhotoResolver.resolveSingle(pending.stillUri, format)
                    }
                }
                liveResult.exceptionOrNull()?.let { error ->
                    Log.e(TAG, "Live Photo detection failed: ${error.javaClass.simpleName}")
                }
                if (operationId != selectionVersion) {
                    liveResult.getOrNull()?.let(livePhotoResolver::release)
                    return@launch
                }

                selectedLiveAsset = liveResult.getOrNull()
                val liveState = when {
                    liveResult.isFailure -> LiveContentState.Error
                    selectedLiveAsset != null -> LiveContentState.Ready(requireNotNull(selectedLiveAsset))
                    else -> LiveContentState.Unavailable
                }
                mutableUiState.update { state -> state.copy(
                    format = format,
                    isNativeDecoderAvailable = nativeImageDecoder.isSupported,
                    metadata = metadata,
                    proRawAssessment = proRawResult.getOrNull(),
                    heifInfo = heifInfoResult.getOrNull(),
                    hasHeifInfoError = heifInfoResult.isFailure,
                    isMetadataLoading = false,
                    hasMetadataError = metadataResult.isFailure,
                    liveContent = liveState,
                ) }
            } catch (error: CancellationException) {
                if (operationId == selectionVersion) mutableUiState.update {
                    it.copy(isMetadataLoading = false, liveContent = LiveContentState.Unavailable)
                }
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Photo selection failed: ${error.javaClass.simpleName}")
                if (operationId == selectionVersion) {
                    selectedPhoto = null
                    mutableUiState.update { it.copy(isMetadataLoading = false,
                        liveContent = LiveContentState.Unavailable,
                        content = RawContentState.Error(if (error is SecurityException)
                            R.string.photo_error_access else invalidSelectionMessage)) }
                }
            }
        }
    }

    private suspend fun classifyAppleSelection(uris: List<Uri>): PendingSelection {
        if (uris.size != APPLE_PAIR_FILE_COUNT) {
            throw IllegalArgumentException("Apple Live Photo requires exactly two files")
        }
        val candidates = uris.map { uri -> uri to formatResolver.resolve(uri) }
        val stillCandidates = candidates.filter { (_, format) -> format in APPLE_STILL_FORMATS }
        val videoCandidates = withContext(Dispatchers.IO) { uris.filter(::isVideoUri) }
        if (stillCandidates.size != 1 || videoCandidates.size != 1) {
            throw IllegalArgumentException("Apple Live Photo requires one still and one video")
        }
        return PendingSelection(
            stillUri = stillCandidates.single().first,
            pairedVideoUri = videoCandidates.single(),
        )
    }

    private suspend fun <T> captureResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun isVideoUri(uri: Uri): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        if (mimeType == QUICKTIME_MIME_TYPE || mimeType == MP4_MIME_TYPE) return true
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0) cursor.getString(column) else null
        }?.lowercase(Locale.ROOT)
        return displayName?.endsWith(".mov") == true || displayName?.endsWith(".mp4") == true
    }

    private suspend fun decodeEmbedded(selection: SelectedPhoto, operationId: Long) {
        val bitmap = embeddedPreviewDecoder.decode(selection.uri)
        if (bitmap == null) {
            if (operationId == operationCounter.get()) {
                mutableUiState.update { state ->
                    state.copy(content = RawContentState.Empty(R.string.status_no_preview))
                }
            }
            return
        }
        showBitmapIfCurrent(
            operationId = operationId,
            bitmap = bitmap,
            source = RawDecodeResult.Source.EMBEDDED_PREVIEW,
        )
    }

    private suspend fun decodeWithNative(selection: SelectedPhoto, operationId: Long) {
        val bitmap = nativeImageDecoder.decode(selection.uri, selection.format)
        showBitmapIfCurrent(
            operationId = operationId,
            bitmap = bitmap,
            source = RawDecodeResult.Source.ANDROID_IMAGE_DECODER,
        )
    }

    private suspend fun decodeWithSystem(selection: SelectedPhoto, operationId: Long) {
        check(!selection.format.isRaw) { "System mode requires a non-RAW image" }
        val decoded = systemPhotoDecoder.decode(selection.uri, selection.format)
        showBitmapIfCurrent(
            operationId = operationId,
            bitmap = decoded.bitmap,
            source = RawDecodeResult.Source.ANDROID_HEIF_DECODER,
            colorInfo = decoded.colorInfo,
        )
    }

    private suspend fun decodeWithLibrary(selection: SelectedPhoto, operationId: Long) {
        val result: Pair<PhotoDecodeOutput, RawDecodeResult.Source> = when {
            selection.format.isRaw -> {
                val bitmap = libRawDecoder.decode(selection.uri, selection.format, mutableUiState.value.rawOptions)
                PhotoDecodeOutput(bitmap, DecodedColorInfo.fromBitmap(bitmap)) to
                    RawDecodeResult.Source.LIBRAW
            }
            selection.format.isHeif -> libHeifDecoder.decode(selection.uri, selection.format) to
                RawDecodeResult.Source.LIBHEIF
            else -> systemPhotoDecoder.decode(selection.uri, selection.format) to
                RawDecodeResult.Source.ANDROID_HEIF_DECODER
        }
        showBitmapIfCurrent(
            operationId = operationId,
            bitmap = result.first.bitmap,
            source = result.second,
            colorInfo = result.first.colorInfo,
        )
    }

    private suspend fun decodeAutomatically(selection: SelectedPhoto, operationId: Long) {
        if (!selection.format.isRaw) {
            val requiresHdrLibrary = if (selection.format.isHeif && Build.VERSION.SDK_INT < 34) {
                try { libHeifDecoder.inspect(selection.uri, selection.format).imageInfo.dynamicRange !=
                    top.hasiy.libheif.HeifDynamicRange.SDR }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    Log.w(TAG, "HDR input inspection failed: ${error.javaClass.simpleName}"); false
                }
            } else false
            if (selection.format.isHeif && (Build.VERSION.SDK_INT >= 34 || requiresHdrLibrary)) {
                try {
                    decodeWithLibrary(selection, operationId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "High-precision libheif decode failed: ${error.javaClass.simpleName}")
                    decodeWithSystem(selection, operationId)
                }
                return
            }
            try {
                decodeWithSystem(selection, operationId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "System photo decode failed: ${error.javaClass.simpleName}")
                if (!selection.format.isHeif) throw error
                decodeWithLibrary(selection, operationId)
            }
            return
        }
        decodeProgressively(selection, operationId)
    }

    private suspend fun decodeProgressively(selection: SelectedPhoto, operationId: Long) {
        progressiveDecoder.decode(selection.uri, selection.format, mutableUiState.value.rawOptions).collect { result ->
            when (result) {
                is RawDecodeResult.Success -> showBitmapIfCurrent(
                    operationId = operationId,
                    bitmap = result.bitmap,
                    source = result.source,
                    isRefining = result.source == RawDecodeResult.Source.EMBEDDED_PREVIEW,
                )
                is RawDecodeResult.Failure -> if (operationId == operationCounter.get()) {
                    showErrorIfCurrent(operationId, result.cause?.let(PhotoFailure::message) ?: R.string.photo_error_corrupt)
                }
            }
        }
    }

    private suspend fun showBitmapIfCurrent(
        operationId: Long,
        bitmap: Bitmap,
        source: RawDecodeResult.Source,
        isRefining: Boolean = false,
        colorInfo: DecodedColorInfo = DecodedColorInfo.fromBitmap(bitmap),
    ) {
        if (operationId != operationCounter.get()) {
            bitmap.recycle()
            return
        }
        mutableUiState.update { state ->
            state.copy(
                content = RawContentState.Success(
                    bitmap = bitmap,
                    source = source,
                    isRefining = isRefining,
                    histogram = null,
                    colorInfo = colorInfo,
                ),
            )
        }
        histogramJob?.cancel()
        histogramJob = viewModelScope.launch {
            val histogram = calculateHistogram(bitmap)
            mutableUiState.update { state ->
                val content = state.content as? RawContentState.Success
                if (content?.bitmap === bitmap) state.copy(content = content.copy(histogram = histogram)) else state
            }
        }
    }

    private suspend fun calculateHistogram(bitmap: Bitmap): RgbHistogram? = try {
        RgbHistogramCalculator.calculate(bitmap)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "RGB histogram calculation failed: ${error.javaClass.simpleName}")
        null
    } catch (error: OutOfMemoryError) {
        Log.w(TAG, "RGB histogram ran out of memory")
        null
    }

    private fun showErrorIfCurrent(operationId: Long, @StringRes message: Int = R.string.status_error) {
        if (operationId == operationCounter.get()) {
            mutableUiState.update { state ->
                state.copy(content = (state.content as? RawContentState.Success)
                    ?.copy(isRefining = false, warningRes = message) ?: RawContentState.Error(message))
            }
        }
    }

    private fun releaseCurrentLiveAsset() {
        livePhotoResolver.release(selectedLiveAsset)
        selectedLiveAsset = null
    }

    private fun releaseCurrentBitmap() {
        val bitmap = (mutableUiState.value.content as? RawContentState.Success)?.bitmap ?: return
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun cancelCurrentOperation() {
        histogramJob?.cancel()
        decodeJob?.cancel()
        decodeJob = null
    }

    private data class SelectedPhoto(
        val uri: Uri,
        val format: PhotoFormat,
    )

    private data class PendingSelection(
        val stillUri: Uri,
        val pairedVideoUri: Uri?,
    )

    private companion object {
        const val TAG = "RawViewerViewModel"
        const val APPLE_PAIR_FILE_COUNT = 2
        const val QUICKTIME_MIME_TYPE = "video/quicktime"
        const val MP4_MIME_TYPE = "video/mp4"
        val APPLE_STILL_FORMATS = setOf(PhotoFormat.JPEG, PhotoFormat.HEIC, PhotoFormat.HEIF)
    }
}

data class RawViewerUiState(
    val recentSessions: List<PhotoSession> = emptyList(),
    val rawOptions: RawRenderOptions = RawRenderOptions(),
    val format: PhotoFormat? = null,
    val isNativeDecoderAvailable: Boolean = false,
    val metadata: List<ExifEntry> = emptyList(),
    val proRawAssessment: ProRawAssessment? = null,
    val heifInfo: LibHeifContainerInfo? = null,
    val hasHeifInfoError: Boolean = false,
    val isMetadataLoading: Boolean = false,
    val hasMetadataError: Boolean = false,
    val liveContent: LiveContentState = LiveContentState.Unavailable,
    val isLivePlaying: Boolean = false,
    val isLiveMuted: Boolean = true,
    val hasLivePlaybackError: Boolean = false,
    val content: RawContentState = RawContentState.Empty(R.string.no_file_selected),
)

sealed interface LiveContentState {
    data object Unavailable : LiveContentState

    data object Detecting : LiveContentState

    data class Ready(val asset: LivePhotoAsset) : LiveContentState

    data object Error : LiveContentState
}

sealed interface RawContentState {
    data object Loading : RawContentState

    data class Empty(@StringRes val messageRes: Int) : RawContentState

    data class Error(@StringRes val messageRes: Int) : RawContentState

    data class Success(
        val bitmap: Bitmap,
        val source: RawDecodeResult.Source,
        val isRefining: Boolean = false,
        @StringRes val warningRes: Int? = null,
        val histogram: RgbHistogram? = null,
        val colorInfo: DecodedColorInfo = DecodedColorInfo.fromBitmap(bitmap),
    ) : RawContentState
}

enum class DecodeMode {
    PREVIEW,
    SYSTEM,
    NATIVE,
    LIBRARY,
    AUTO,
}

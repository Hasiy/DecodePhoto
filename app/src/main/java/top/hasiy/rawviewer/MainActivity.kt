/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.Window
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.hasiy.rawviewer.live.model.LiveDetectionEvidence
import top.hasiy.rawviewer.live.model.LivePhotoAsset
import top.hasiy.rawviewer.live.model.LivePhotoKind
import top.hasiy.rawviewer.live.model.PairingConfidence
import top.hasiy.rawviewer.live.ui.LivePhotoPlayer
import top.hasiy.rawviewer.raw.DngPhotoType
import top.hasiy.rawviewer.raw.PhotoDynamicRange
import top.hasiy.rawviewer.raw.RawDecodeResult

class MainActivity : ComponentActivity() {

    private val viewModel: RawViewerViewModel by viewModels()
    private val hdrComparisonViewModel: HdrComparisonViewModel by viewModels()
    private val comparisonViewModel: DecodeComparisonViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RawViewerTheme {
                var destination by rememberSaveable { mutableStateOf(
                    runCatching { AppDestination.valueOf(getPreferences(MODE_PRIVATE)
                        .getString("destination", AppDestination.PHOTO_VIEWER.name)!!) }
                        .getOrDefault(AppDestination.PHOTO_VIEWER)) }
                LaunchedEffect(destination) {
                    getPreferences(MODE_PRIVATE).edit().putString("destination", destination.name).apply()
                }
                var exifBackDestination by rememberSaveable {
                    mutableStateOf(AppDestination.PHOTO_VIEWER)
                }
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val comparisonState by comparisonViewModel.uiState.collectAsStateWithLifecycle()
                val liveHdr = (uiState.liveContent as? LiveContentState.Ready)?.asset?.videoMetadata?.let {
                    uiState.isLivePlaying && it.decoderSupportsFormat && it.colorTransfer in listOf("PQ", "HLG")
                } == true
                val mainHdrReady = (destination == AppDestination.PHOTO_VIEWER || destination == AppDestination.FULLSCREEN) &&
                    ((uiState.content as? RawContentState.Success)?.colorInfo?.isHdrDisplayReady == true || liveHdr)
                if (destination != AppDestination.HDR_COMPARISON) ApplyViewerHdrWindowMode(window, mainHdrReady)
                val filePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        takePersistableReadPermission(uri)
                        viewModel.onFileSelected(uri)
                    }
                }
                val appleLivePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        uris.forEach(::takePersistableReadPermission)
                        viewModel.onAppleLivePhotoSelected(uris)
                    }
                }
                val hdrComparisonPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        takePersistableReadPermission(uri)
                        hdrComparisonViewModel.onPhotoSelected(uri)
                    }
                }

                when (destination) {
                    AppDestination.PHOTO_VIEWER -> RawViewerScreen(
                        uiState = uiState,
                        onSelectFile = { filePicker.launch(PHOTO_MIME_TYPES) },
                        onSelectAppleLive = { appleLivePicker.launch(APPLE_LIVE_MIME_TYPES) },
                        onOpenColorConverter = { destination = AppDestination.COLOR_CONVERTER },
                        onOpenHdrComparison = { destination = AppDestination.HDR_COMPARISON },
                        onOpenExif = {
                            exifBackDestination = AppDestination.PHOTO_VIEWER
                            destination = AppDestination.EXIF_DETAILS
                        },
                        onOpenHeifDetails = { destination = AppDestination.HEIF_DETAILS },
                        onOpenFullscreen = { destination = AppDestination.FULLSCREEN },
                        onOpenCompare = { destination = AppDestination.DECODE_COMPARISON },
                        onOpenRecent = { destination = AppDestination.RECENT },
                        onRawOptions = viewModel::onRawOptionsChanged,
                        onDecode = viewModel::onDecodeRequested,
                        onCancel = viewModel::onCancelClick,
                        onPlayLive = viewModel::onPlayLiveClick,
                        onStopLive = viewModel::onStopLivePlayback,
                        onToggleLiveMute = viewModel::onToggleLiveMute,
                        onLivePlaybackError = viewModel::onLivePlaybackError,
                        onBitmapDisposed = viewModel::onBitmapDisposed,
                    )
                    AppDestination.FULLSCREEN -> {
                        BackHandler { destination = AppDestination.PHOTO_VIEWER }
                        FullscreenPhotoScreen(uiState.content) { destination = AppDestination.PHOTO_VIEWER }
                    }
                    AppDestination.DECODE_COMPARISON -> {
                        BackHandler { comparisonViewModel.clear(); destination = AppDestination.PHOTO_VIEWER }
                        DecodeComparisonScreen(uiState.format, comparisonState,
                            { a, b -> viewModel.compareWith(comparisonViewModel, a, b) },
                            { comparisonViewModel.clear(); destination = AppDestination.PHOTO_VIEWER })
                    }
                    AppDestination.RECENT -> {
                        BackHandler { destination = AppDestination.PHOTO_VIEWER }
                        RecentPhotosScreen(uiState.recentSessions,
                            { viewModel.onRecentSelected(it); destination = AppDestination.PHOTO_VIEWER },
                            viewModel::onClearRecent, { destination = AppDestination.PHOTO_VIEWER })
                    }
                    AppDestination.COLOR_CONVERTER -> {
                        BackHandler { destination = AppDestination.PHOTO_VIEWER }
                        ColorConverterScreen(
                            onNavigateBack = { destination = AppDestination.PHOTO_VIEWER },
                        )
                    }
                    AppDestination.EXIF_DETAILS -> {
                        BackHandler { destination = exifBackDestination }
                        ExifDetailsScreen(
                            uiState = uiState,
                            onNavigateBack = { destination = exifBackDestination },
                        )
                    }
                    AppDestination.HEIF_DETAILS -> {
                        BackHandler { destination = AppDestination.PHOTO_VIEWER }
                        HeifDetailsScreen(
                            uiState = uiState,
                            onNavigateBack = { destination = AppDestination.PHOTO_VIEWER },
                            onOpenExif = {
                                exifBackDestination = AppDestination.HEIF_DETAILS
                                destination = AppDestination.EXIF_DETAILS
                            },
                        )
                    }
                    AppDestination.HDR_COMPARISON -> {
                        val hdrComparisonUiState by hdrComparisonViewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(hdrComparisonViewModel) { hdrComparisonViewModel.onScreenOpened() }
                        BackHandler { destination = AppDestination.PHOTO_VIEWER }
                        HdrComparisonScreen(
                            uiState = hdrComparisonUiState,
                            onNavigateBack = { destination = AppDestination.PHOTO_VIEWER },
                            onSelectPhoto = { hdrComparisonPicker.launch(HDR_PHOTO_MIME_TYPES) },
                            onRetry = hdrComparisonViewModel::retry,
                            onImageDisposed = hdrComparisonViewModel::onImageDisposed,
                        )
                    }
                }
            }
        }
    }

    private fun takePersistableReadPermission(uri: android.net.Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Provider did not grant persistent access")
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        val PHOTO_MIME_TYPES = arrayOf(
            "image/x-adobe-dng",
            "image/dng",
            "image/x-fuji-raf",
            "image/raf",
            "image/heif",
            "image/heic",
            "image/heif-sequence",
            "image/heic-sequence",
            "image/jpeg",
            "image/avif",
            "application/octet-stream",
        )
        val APPLE_LIVE_MIME_TYPES = arrayOf(
            "image/jpeg",
            "image/heif",
            "image/heic",
            "video/quicktime",
            "video/mp4",
            "application/octet-stream",
        )
        val HDR_PHOTO_MIME_TYPES = arrayOf(
            "image/jpeg",
            "image/heif",
            "image/heic",
            "image/avif",
            "application/octet-stream",
        )
    }
}

private enum class AppDestination {
    FULLSCREEN,
    DECODE_COMPARISON,
    RECENT,
    PHOTO_VIEWER,
    COLOR_CONVERTER,
    HDR_COMPARISON,
    EXIF_DETAILS,
    HEIF_DETAILS,
}

@Composable
private fun RawViewerTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
        }
    }
}

@Composable
private fun RawViewerScreen(
    uiState: RawViewerUiState,
    onSelectFile: () -> Unit,
    onSelectAppleLive: () -> Unit,
    onOpenColorConverter: () -> Unit,
    onOpenHdrComparison: () -> Unit,
    onOpenExif: () -> Unit,
    onOpenHeifDetails: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onOpenCompare: () -> Unit,
    onOpenRecent: () -> Unit,
    onRawOptions: (top.hasiy.rawviewer.raw.RawRenderOptions) -> Unit,
    onDecode: (DecodeMode) -> Unit,
    onCancel: () -> Unit,
    onPlayLive: () -> Unit,
    onStopLive: () -> Unit,
    onToggleLiveMute: () -> Unit,
    onLivePlaybackError: () -> Unit,
    onBitmapDisposed: (android.graphics.Bitmap) -> Unit,
) {
    val window = LocalContext.current.findActivity()?.window
    val hdrDisplayReady = (uiState.content as? RawContentState.Success)
        ?.colorInfo
        ?.isHdrDisplayReady == true
    val liveMetadata = (uiState.liveContent as? LiveContentState.Ready)?.asset
        ?.let { asset -> liveMetadataRows(asset) }
        .orEmpty()
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.screen_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item(key = "subtitle") {
                Text(
                    text = stringResource(R.string.screen_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "picker") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onOpenColorConverter,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_color_converter))
                    }
                    OutlinedButton(
                        onClick = onOpenHdrComparison,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_hdr_comparison))
                    }
                    Button(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.select_file))
                    }
                    OutlinedButton(onClick = onSelectAppleLive, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.select_apple_live_photo))
                    }
                    OutlinedButton(onClick = onOpenRecent, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.recent_title))
                    }
                }
            }
            item(key = "content") {
                DecodeContent(content = uiState.content, uiState = uiState, onCancel = onCancel,
                    onLivePlaybackEnded = onStopLive, onLivePlaybackError = onLivePlaybackError,
                    onBitmapDisposed = onBitmapDisposed)
            }
            if (uiState.content is RawContentState.Success) item("photo-tools") {
                OutlinedButton(onClick = onOpenFullscreen, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_fullscreen))
                }
                OutlinedButton(onClick = onOpenCompare, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_compare))
                }
            }
            uiState.format?.let { format ->
                item(key = "format") {
                    Text(
                        text = stringResource(R.string.selected_format, format.name),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (uiState.format?.isHeif == true) {
                item(key = "heif-details-entry") {
                    OutlinedButton(
                        onClick = onOpenHeifDetails,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_heif_details))
                    }
                }
            }
            uiState.proRawAssessment?.let { assessment ->
                item(key = "dng-photo-type") {
                    MetadataCard(
                        tag = stringResource(R.string.dng_photo_type_label),
                        value = stringResource(assessment.type.labelRes()),
                    )
                }
            }
            item(key = "methods") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.method_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    DecodeButtons(uiState = uiState, onDecode = onDecode)
                    Text(
                        text = stringResource(R.string.decode_android_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (uiState.content is RawContentState.Error) item("retry") {
                Button(onClick = { onDecode(DecodeMode.AUTO) }) { Text(stringResource(R.string.photo_retry)) }
            }
            if (uiState.content is RawContentState.Success) {
                item("export") { PhotoExportControls(uiState) }
            }
            if (uiState.format?.isRaw == true) item("raw-options") {
                RawRenderControls(uiState.rawOptions, onRawOptions)
            }
            item(key = "live-section") {
                LivePhotoSection(
                    uiState = uiState,
                    onPlayLive = onPlayLive,
                    onStopLive = onStopLive,
                    onToggleLiveMute = onToggleLiveMute,
                )
            }
            item("live-export") { LiveExportControls(uiState) }
            if (liveMetadata.isNotEmpty()) {
                item(key = "live-metadata-title") {
                    Text(
                        text = stringResource(R.string.live_metadata_title, liveMetadata.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(items = liveMetadata, key = { entry -> "live-${entry.first}" }) { entry ->
                    MetadataCard(tag = entry.first, value = entry.second)
                }
            }
            item(key = "metadata-entry") {
                OutlinedButton(
                    onClick = onOpenExif,
                    enabled = uiState.format != null || uiState.isMetadataLoading || uiState.hasMetadataError,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_exif_details, uiState.metadata.size))
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun DecodeButtons(
    uiState: RawViewerUiState,
    onDecode: (DecodeMode) -> Unit,
) {
    val hasSelection = uiState.format != null
    val isLoading = uiState.content is RawContentState.Loading
    val enabled = hasSelection && !isLoading
    val rawFormat = uiState.format?.isRaw == true
    val heifFormat = uiState.format?.isHeif == true
    val nativeEnabled = enabled &&
        uiState.format?.supportsNativeDecoder == true &&
        uiState.isNativeDecoderAvailable
    val libraryEnabled = enabled && (rawFormat || heifFormat)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { onDecode(DecodeMode.PREVIEW) },
                enabled = enabled && rawFormat,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.decode_embedded))
            }
            OutlinedButton(
                onClick = { onDecode(DecodeMode.SYSTEM) },
                enabled = enabled && !rawFormat,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.decode_system))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { onDecode(DecodeMode.NATIVE) },
                enabled = nativeEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.decode_native))
            }
            Button(
                onClick = { onDecode(DecodeMode.LIBRARY) },
                enabled = libraryEnabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                ),
            ) {
                Text(
                    stringResource(
                        when {
                            rawFormat -> R.string.decode_libraw
                            heifFormat -> R.string.decode_libheif
                            else -> R.string.decode_library_unavailable
                        },
                    ),
                )
            }
        }
        Button(
            onClick = { onDecode(DecodeMode.AUTO) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.decode_auto))
        }
    }
}

@Composable
private fun DecodeContent(
    content: RawContentState,
    uiState: RawViewerUiState,
    onCancel: () -> Unit,
    onLivePlaybackEnded: () -> Unit,
    onLivePlaybackError: () -> Unit,
    onBitmapDisposed: (android.graphics.Bitmap) -> Unit,
) {
    when (content) {
        RawContentState.Loading -> LoadingContent(onCancel)
        is RawContentState.Empty -> MessageCard(content.messageRes)
        is RawContentState.Error -> MessageCard(content.messageRes, isError = true)
        is RawContentState.Success -> SuccessContent(
            content = content,
            uiState = uiState,
            onCancel = onCancel,
            onLivePlaybackEnded = onLivePlaybackEnded,
            onLivePlaybackError = onLivePlaybackError,
            onBitmapDisposed = onBitmapDisposed,
        )
    }
}

@Composable
private fun LoadingContent(onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.status_loading))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun MessageCard(@StringRes messageRes: Int, isError: Boolean = false) {
    val backgroundColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(text = stringResource(messageRes), color = contentColor)
    }
}

@Composable
private fun SuccessContent(
    content: RawContentState.Success,
    uiState: RawViewerUiState,
    onCancel: () -> Unit,
    onLivePlaybackEnded: () -> Unit,
    onLivePlaybackError: () -> Unit,
    onBitmapDisposed: (android.graphics.Bitmap) -> Unit,
) {
    DisposableEffect(content.bitmap) {
        onDispose { onBitmapDisposed(content.bitmap) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(
                R.string.status_success,
                sourceLabel(content.source),
                content.bitmap.width,
                content.bitmap.height,
            ),
            style = MaterialTheme.typography.labelLarge,
        )
        content.warningRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        val liveAsset = (uiState.liveContent as? LiveContentState.Ready)?.asset
        if (uiState.isLivePlaying && liveAsset != null) {
            LivePhotoPlayer(
                videoFile = liveAsset.videoFile,
                isMuted = uiState.isLiveMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 560.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp),
                    ),
                onPlaybackEnded = onLivePlaybackEnded,
                onPlaybackError = onLivePlaybackError,
            )
        } else {
            Image(
                bitmap = content.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.image_preview_description),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 560.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp),
                    ),
                contentScale = ContentScale.Fit,
            )
        }
        DecodedColorInfoCard(content)
        content.histogram?.let { histogram ->
            RgbHistogramChart(
                histogram = histogram,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (content.isRefining) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.status_loading),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun DecodedColorInfoCard(content: RawContentState.Success) {
    val info = content.colorInfo
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.decoded_color_info_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(
                R.string.decoded_source_bit_depth,
                info.sourceBitDepth?.toString() ?: stringResource(R.string.value_unknown),
            ),
        )
        Text(stringResource(R.string.decoded_bitmap_config, info.bitmapConfig))
        Text(stringResource(R.string.decoded_color_space, info.colorSpaceName))
        Text(
            stringResource(
                R.string.decoded_dynamic_range,
                stringResource(info.dynamicRange.labelRes()),
            ),
        )
        Text(
            stringResource(
                when {
                    info.usedSdrFallback -> R.string.decoded_hdr_sdr_fallback
                    info.isHdrDisplayReady -> R.string.decoded_hdr_ready
                    else -> R.string.decoded_sdr_output
                },
            ),
            color = if (info.isHdrDisplayReady) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LivePhotoSection(
    uiState: RawViewerUiState,
    onPlayLive: () -> Unit,
    onStopLive: () -> Unit,
    onToggleLiveMute: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.live_photo_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        when (val liveContent = uiState.liveContent) {
            LiveContentState.Detecting -> Text(stringResource(R.string.live_detecting))
            LiveContentState.Error -> MessageCard(R.string.live_detection_error, isError = true)
            LiveContentState.Unavailable -> Text(
                text = stringResource(R.string.live_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is LiveContentState.Ready -> {
                Text(
                    text = stringResource(
                        R.string.live_ready,
                        stringResource(liveContent.asset.kind.labelRes()),
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = if (uiState.isLivePlaying) onStopLive else onPlayLive,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (uiState.isLivePlaying) R.string.live_stop else R.string.live_play,
                            ),
                        )
                    }
                    if (liveContent.asset.videoMetadata.hasAudio) {
                        OutlinedButton(
                            onClick = onToggleLiveMute,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (uiState.isLiveMuted) R.string.live_unmute else R.string.live_mute,
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (uiState.hasLivePlaybackError) {
            MessageCard(R.string.live_playback_error, isError = true)
        }
    }
}

@Composable
private fun MetadataCard(tag: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(tag, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun liveMetadataRows(asset: LivePhotoAsset): List<Pair<String, String>> = buildList {
    add(
        stringResource(R.string.live_metadata_kind) to
            stringResource(asset.kind.labelRes()),
    )
    add(stringResource(R.string.live_metadata_mime) to asset.videoMetadata.mimeType)
    add(
        stringResource(R.string.live_metadata_dimensions) to stringResource(
            R.string.live_metadata_dimensions_value,
            asset.videoMetadata.width,
            asset.videoMetadata.height,
        ),
    )
    add(
        stringResource(R.string.live_metadata_duration) to stringResource(
            R.string.live_metadata_duration_value,
            asset.videoMetadata.durationUs,
        ),
    )
    add(
        stringResource(R.string.live_metadata_audio) to stringResource(
            if (asset.videoMetadata.hasAudio) R.string.live_yes else R.string.live_no,
        ),
    )
    asset.presentationTimestampUs?.let { timestamp ->
        add(
            stringResource(R.string.live_metadata_presentation_timestamp) to stringResource(
                R.string.live_metadata_duration_value,
                timestamp,
            ),
        )
    }
    asset.pairingConfidence?.let { confidence ->
        add(
            stringResource(R.string.live_metadata_pairing_confidence) to
                stringResource(confidence.labelRes()),
        )
    }
    asset.detectionEvidence.forEachIndexed { index, evidence ->
        add(
            stringResource(R.string.live_metadata_evidence, index + 1) to
                stringResource(evidence.labelRes()),
        )
    }
}

@Composable
private fun sourceLabel(source: RawDecodeResult.Source): String = when (source) {
    RawDecodeResult.Source.EMBEDDED_PREVIEW -> stringResource(R.string.source_embedded)
    RawDecodeResult.Source.ANDROID_IMAGE_DECODER -> stringResource(R.string.source_native)
    RawDecodeResult.Source.ANDROID_HEIF_DECODER -> stringResource(R.string.source_system_heif)
    RawDecodeResult.Source.LIBRAW -> stringResource(R.string.source_libraw)
    RawDecodeResult.Source.LIBHEIF -> stringResource(R.string.source_libheif)
}

@Composable
private fun ApplyViewerHdrWindowMode(window: Window?, hdrDisplayReady: Boolean) {
    DisposableEffect(window, hdrDisplayReady) {
        window?.colorMode = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hdrDisplayReady &&
                window?.decorView?.display?.isHdr == true
        ) {
            ActivityInfo.COLOR_MODE_HDR
        } else {
            ActivityInfo.COLOR_MODE_DEFAULT
        }
        onDispose {
            window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@StringRes
private fun DngPhotoType.labelRes(): Int = when (this) {
    DngPhotoType.APPLE_PRORAW -> R.string.dng_photo_type_proraw
    DngPhotoType.POSSIBLE_APPLE_PRORAW -> R.string.dng_photo_type_possible_proraw
    DngPhotoType.STANDARD_DNG -> R.string.dng_photo_type_standard
}

@StringRes
private fun LivePhotoKind.labelRes(): Int = when (this) {
    LivePhotoKind.APPLE_PAIRED -> R.string.live_kind_apple
    LivePhotoKind.ANDROID_MOTION_PHOTO -> R.string.live_kind_android
    LivePhotoKind.LEGACY_MICRO_VIDEO -> R.string.live_kind_legacy
    LivePhotoKind.XIAOMI -> R.string.live_kind_xiaomi
    LivePhotoKind.VIVO -> R.string.live_kind_vivo
    LivePhotoKind.OPPO -> R.string.live_kind_oppo
}

@StringRes
private fun PairingConfidence.labelRes(): Int = when (this) {
    PairingConfidence.VERIFIED -> R.string.live_pairing_verified
    PairingConfidence.PROBABLE -> R.string.live_pairing_probable
    PairingConfidence.UNVERIFIED -> R.string.live_pairing_unverified
}

@StringRes
private fun LiveDetectionEvidence.labelRes(): Int = when (this) {
    LiveDetectionEvidence.MOTION_PHOTO_XMP -> R.string.live_evidence_motion_xmp
    LiveDetectionEvidence.LEGACY_MICRO_VIDEO_XMP -> R.string.live_evidence_legacy_xmp
    LiveDetectionEvidence.EXPLICIT_PAIR_SELECTION -> R.string.live_evidence_explicit_pair
    LiveDetectionEvidence.STILL_MAKER_NOTE_IDENTIFIER -> R.string.live_evidence_still_identifier
    LiveDetectionEvidence.QUICKTIME_CONTENT_IDENTIFIER -> R.string.live_evidence_video_identifier
    LiveDetectionEvidence.ISO_BMFF_FILE_TYPE -> R.string.live_evidence_file_type
    LiveDetectionEvidence.VIDEO_TRACK_VERIFIED -> R.string.live_evidence_video_track
}

@StringRes
private fun PhotoDynamicRange.labelRes(): Int = when (this) {
    PhotoDynamicRange.SDR -> R.string.dynamic_range_sdr
    PhotoDynamicRange.HLG -> R.string.dynamic_range_hlg
    PhotoDynamicRange.PQ -> R.string.dynamic_range_pq
    PhotoDynamicRange.ULTRA_HDR_GAINMAP -> R.string.dynamic_range_ultra_hdr
    PhotoDynamicRange.UNKNOWN_HDR -> R.string.dynamic_range_unknown_hdr
}

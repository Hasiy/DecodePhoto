/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.hasiy.rawviewer.raw.RawRenderOptions
import top.hasiy.rawviewer.raw.RawWhiteBalance

@Composable
fun RawRenderControls(options: RawRenderOptions, onApply: (RawRenderOptions) -> Unit) {
    var ev by rememberSaveable(options) { mutableFloatStateOf(options.exposureEv) }
    var wb by rememberSaveable(options) { mutableStateOf(options.whiteBalance) }
    var half by rememberSaveable(options) { mutableStateOf(options.halfSize) }
    val applyCurrent by rememberUpdatedState(onApply)
    LaunchedEffect(ev, wb, half) {
        val draft = RawRenderOptions(ev, wb, half)
        if (draft != options) { kotlinx.coroutines.delay(600); applyCurrent(draft) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.raw_options_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.raw_ev, ev))
        Slider(ev, { ev = it }, valueRange = RawRenderOptions.MIN_EV..RawRenderOptions.MAX_EV, steps = 23)
        RawWhiteBalance.entries.forEach { value ->
            Row {
                RadioButton(wb == value, { wb = value })
                TextButton(onClick = { wb = value }) { Text(stringResource(when (value) {
                    RawWhiteBalance.CAMERA -> R.string.raw_wb_camera
                    RawWhiteBalance.AUTO -> R.string.raw_wb_auto
                    RawWhiteBalance.DAYLIGHT -> R.string.raw_wb_daylight
                })) }
            }
        }
        Row { Checkbox(half, { half = it }); Text(stringResource(R.string.raw_half_size)) }
        Button(onClick = { onApply(RawRenderOptions(ev, wb, half)) }) { Text(stringResource(R.string.raw_apply)) }
    }
}

@Composable
fun PhotoExportControls(uiState: RawViewerUiState) {
    val content = uiState.content as? RawContentState.Success ?: return
    val context = LocalContext.current
    var png by rememberSaveable { mutableStateOf(false) }
    var edge by rememberSaveable { mutableIntStateOf(2048) }
    var quality by rememberSaveable { mutableIntStateOf(90) }
    var removeGps by rememberSaveable { mutableStateOf(true) }
    val operation = rememberPhotoOperation()
    val options = PhotoExportOptions(png, edge, quality, removeGps)
    val saveJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) operation.run { PhotoExporter.photo(context, content.bitmap, uiState.metadata,
            options.copy(png = false), uri) }
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) operation.run { PhotoExporter.photo(context, content.bitmap, uiState.metadata,
            options.copy(png = true), uri) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleMedium)
        Row {
            FilterChip(!png, { png = false }, { Text(stringResource(R.string.export_jpeg)) })
            Spacer(Modifier.width(8.dp))
            FilterChip(png, { png = true }, { Text(stringResource(R.string.export_png)) })
        }
        Text(stringResource(R.string.export_edge, edge))
        Slider(edge.toFloat(), { edge = it.toInt() }, valueRange = 256f..4096f)
        if (!png) {
            Text(stringResource(R.string.export_quality, quality))
            Slider(quality.toFloat(), { quality = it.toInt() }, valueRange = 1f..100f)
            Row { Checkbox(removeGps, { removeGps = it }); Text(stringResource(R.string.export_remove_gps)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (png) savePng.launch("photo.png") else saveJpeg.launch("photo.jpg") }, enabled = !operation.busy) {
                Text(stringResource(R.string.export_save))
            }
            OutlinedButton(onClick = { operation.run {
                val uri = PhotoExporter.photo(context, content.bitmap, uiState.metadata, options)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = options.mimeType; putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("photo", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, context.getString(R.string.export_share)))
            } }, enabled = !operation.busy) { Text(stringResource(R.string.export_share)) }
        }
        Text(stringResource(R.string.export_scope), style = MaterialTheme.typography.bodySmall)
        OperationStatus(operation)
    }
}

@Composable
fun LiveExportControls(uiState: RawViewerUiState) {
    val asset = (uiState.liveContent as? LiveContentState.Ready)?.asset ?: return
    val context = LocalContext.current
    val operation = rememberPhotoOperation()
    var position by rememberSaveable(asset.stillUri.toString()) {
        mutableFloatStateOf((asset.presentationTimestampUs ?: 0L) / 1_000_000f)
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(
        if (asset.videoFile.extension == "mov") "video/quicktime" else "video/mp4")) { uri ->
        if (uri != null) operation.run { PhotoExporter.video(context, asset, uri) }
    }
    val frame = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) operation.run { PhotoExporter.frame(context, asset, (position * 1_000_000).toLong(), uri) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.live_hdr_info, asset.videoMetadata.colorTransfer,
            context.getString(if (asset.videoMetadata.decoderSupportsFormat) R.string.value_yes else R.string.value_no)))
        Text(stringResource(R.string.live_frame_position, position))
        Slider(position, { position = it }, valueRange = 0f..(asset.videoMetadata.durationUs / 1_000_000f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { video.launch("live.${asset.videoFile.extension}") }, enabled = !operation.busy) {
                Text(stringResource(R.string.live_export_video))
            }
            OutlinedButton(onClick = { frame.launch("frame.jpg") }, enabled = !operation.busy) {
                Text(stringResource(R.string.live_export_frame))
            }
        }
        OperationStatus(operation)
    }
}

class PhotoOperation(private val launch: (suspend () -> Unit) -> Unit) {
    var busy by mutableStateOf(false)
    var status by mutableStateOf<Int?>(null)
    fun run(block: suspend () -> Unit) { if (!busy) launch(block) }
}
@Composable
fun rememberPhotoOperation(): PhotoOperation {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        lateinit var operation: PhotoOperation
        operation = PhotoOperation { block ->
            operation.busy = true; operation.status = null
            scope.launch {
                try { block(); operation.status = R.string.operation_success }
                catch (error: CancellationException) { throw error }
                catch (error: Throwable) {
                    Log.w("PhotoOperation", "Operation failed: ${error.javaClass.simpleName}")
                    operation.status = R.string.operation_error
                } finally { operation.busy = false }
            }
        }
        operation
    }
}
@Composable
fun OperationStatus(operation: PhotoOperation) {
    if (operation.busy) Text(stringResource(R.string.operation_busy))
    operation.status?.let { Text(stringResource(it)) }
}

@Composable
fun RecentPhotosScreen(sessions: List<PhotoSession>, onSelect: (PhotoSession) -> Unit,
    onClear: () -> Unit, onBack: () -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("header") {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.navigate_back)) }
            Text(stringResource(R.string.recent_title), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onClear) { Text(stringResource(R.string.recent_clear)) }
        }
        if (sessions.isEmpty()) item("empty") { Text(stringResource(R.string.recent_empty)) }
        items(sessions.size, key = { sessions[it].uri.toString() }) { index ->
            val session = sessions[index]
            val timestamp = remember(session.openedAt) { java.text.DateFormat.getDateTimeInstance()
                .format(java.util.Date(session.openedAt)) }
            OutlinedButton(onClick = { onSelect(session) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.recent_entry, session.format, timestamp))
            }
        }
    }
}

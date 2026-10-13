/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect

/** Both comparison layers share this transform, including accessible button controls. */
class PhotoTransform {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    fun reset() { scale = 1f; offset = Offset.Zero }
    fun update(zoom: Float, pan: Offset, size: IntSize) {
        scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX),
            (offset.y + pan.y).coerceIn(-maxY, maxY))
    }
    private companion object { const val MAX_SCALE = 8f }
}

@Composable
fun ZoomablePhoto(bitmap: Bitmap, modifier: Modifier = Modifier, second: Bitmap? = null,
    split: Float = 0.5f, transform: PhotoTransform = remember(bitmap, second) { PhotoTransform() }) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        .onSizeChanged { size = it }
        .graphicsLayer { clip = true }
        .pointerInput(transform, size) {
            detectTransformGestures { _, pan, zoom, _ -> transform.update(zoom, pan, size) }
        }.pointerInput(transform, size) {
            detectTapGestures(onDoubleTap = {
                if (transform.scale > 1f) transform.reset() else transform.update(2f, Offset.Zero, size)
            })
        }) {
        PhotoLayer(second ?: bitmap, transform)
        if (second != null) {
            // Clip in viewport coordinates before applying the shared image transform.
            Box(Modifier.fillMaxSize().drawWithContent {
                clipRect(right = size.width * split.coerceIn(0f, 1f)) { this@drawWithContent.drawContent() }
            }) { PhotoLayer(bitmap, transform) }
        }
    }
}

@Composable
private fun PhotoLayer(bitmap: Bitmap, transform: PhotoTransform) {
    Image(bitmap.asImageBitmap(), stringResource(R.string.image_preview_description),
        Modifier.fillMaxSize().graphicsLayer {
            scaleX = transform.scale; scaleY = transform.scale
            translationX = transform.offset.x; translationY = transform.offset.y
        }, contentScale = ContentScale.Fit)
}

@Composable
fun PhotoTransformControls(transform: PhotoTransform) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { transform.update(1.5f, Offset.Zero, IntSize.Zero) }) {
            Text(stringResource(R.string.viewer_zoom_in))
        }
        OutlinedButton(onClick = { transform.update(1f / 1.5f, Offset.Zero, IntSize.Zero) }) {
            Text(stringResource(R.string.viewer_zoom_out))
        }
        OutlinedButton(onClick = transform::reset) { Text(stringResource(R.string.viewer_reset)) }
    }
}

@Composable
fun FullscreenPhotoScreen(content: RawContentState, onBack: () -> Unit) {
    val success = content as? RawContentState.Success
    val transform = remember(success?.bitmap) { PhotoTransform() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.navigate_back)) }
            if (success != null) {
                ZoomablePhoto(success.bitmap, Modifier.fillMaxWidth().weight(1f), transform = transform)
                PhotoTransformControls(transform)
                Text(stringResource(R.string.viewer_hint), style = MaterialTheme.typography.bodySmall)
            } else Text(stringResource(R.string.no_file_selected))
        }
    }
}

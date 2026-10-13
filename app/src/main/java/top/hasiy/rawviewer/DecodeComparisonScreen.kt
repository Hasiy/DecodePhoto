/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.hasiy.rawviewer.raw.PhotoFormat

@Composable
fun DecodeComparisonScreen(format: PhotoFormat?, state: DecodeComparisonState,
    onLoad: (DecodeMode, DecodeMode) -> Unit, onBack: () -> Unit) {
    val choices = if (format?.isRaw == true) listOf(DecodeMode.PREVIEW, DecodeMode.LIBRARY)
        else if (format?.isHeif == true) listOf(DecodeMode.SYSTEM, DecodeMode.LIBRARY)
        else listOf(DecodeMode.SYSTEM)
    var left by rememberSaveable(format) { mutableStateOf(choices.first()) }
    var right by rememberSaveable(format) { mutableStateOf(choices.last()) }
    var split by rememberSaveable { mutableFloatStateOf(0.5f) }
    val transform = remember(state.left, state.right) { PhotoTransform() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.navigate_back)) }
            Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompareMode("A", left, { left = it }, choices)
                CompareMode("B", right, { right = it }, choices)
            }
            Button(onClick = { onLoad(left, right) }, enabled = format != null && !state.loading) {
                Text(stringResource(R.string.compare_load))
            }
            if (state.loading) CircularProgressIndicator()
            state.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            val a = state.left; val b = state.right
            if (a != null && b != null) {
                ZoomablePhoto(a, Modifier.fillMaxWidth().weight(1f), b, split, transform)
                Text(stringResource(R.string.compare_split, (split * 100).toInt()))
                Slider(split, { split = it })
                PhotoTransformControls(transform)
            }
            Text(stringResource(R.string.compare_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompareMode(side: String, mode: DecodeMode, onMode: (DecodeMode) -> Unit, choices: List<DecodeMode>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.compare_side, side, stringResource(modeLabel(mode))))
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { option -> DropdownMenuItem(text = { Text(stringResource(modeLabel(option))) },
                onClick = { onMode(option); expanded = false }) }
        }
    }
}
private fun modeLabel(mode: DecodeMode): Int = when (mode) {
    DecodeMode.PREVIEW -> R.string.decode_embedded
    DecodeMode.SYSTEM -> R.string.decode_system
    else -> R.string.compare_library
}

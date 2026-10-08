/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import android.view.Window
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.function.Consumer
import top.hasiy.rawviewer.hdr.GainmapMetadata
import top.hasiy.rawviewer.hdr.HdrBitmapVariant
import top.hasiy.rawviewer.hdr.HdrComparisonError
import top.hasiy.rawviewer.hdr.HdrComparisonImage
import top.hasiy.rawviewer.hdr.HdrComparisonMode
import top.hasiy.rawviewer.hdr.HdrComparisonUiState
import top.hasiy.rawviewer.hdr.HdrContentType
import top.hasiy.rawviewer.hdr.HdrDisplayCapability
import top.hasiy.rawviewer.hdr.HdrFallbackReason
import top.hasiy.rawviewer.hdr.HdrPresentationDecision
import top.hasiy.rawviewer.hdr.HdrWindowMode
import top.hasiy.rawviewer.hdr.resolveHdrPresentation

@Composable
fun HdrComparisonScreen(
    uiState: HdrComparisonUiState,
    onNavigateBack: () -> Unit,
    onSelectPhoto: () -> Unit,
    onRetry: () -> Unit,
    onImageDisposed: (HdrComparisonImage) -> Unit,
) {
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    val capability = rememberHdrDisplayCapability()
    val success = uiState as? HdrComparisonUiState.Success
    var selectedMode by rememberSaveable { mutableStateOf(HdrComparisonMode.DEFAULT) }

    LaunchedEffect(success?.image?.originalBitmap?.generationId) {
        selectedMode = HdrComparisonMode.DEFAULT
    }

    val presentation = success?.let { state ->
        resolveHdrPresentation(selectedMode, state.image.contentType, capability)
    }
    ApplyHdrWindowMode(window, presentation?.windowMode ?: HdrWindowMode.DEFAULT)

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "hdr-header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.navigate_back))
                    }
                    Text(
                        text = stringResource(R.string.hdr_comparison_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.hdr_comparison_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onSelectPhoto, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.hdr_select_photo))
                    }
                }
            }

            item(key = "hdr-content") {
                when (uiState) {
                    HdrComparisonUiState.Empty -> HdrEmptyContent(onSelectPhoto)
                    HdrComparisonUiState.Loading -> HdrLoadingContent()
                    is HdrComparisonUiState.Error -> HdrErrorContent(uiState.reason, onRetry)
                    is HdrComparisonUiState.Success -> HdrSuccessContent(
                        image = uiState.image,
                        selectedMode = selectedMode,
                        capability = capability,
                        presentation = requireNotNull(presentation),
                        onModeSelected = { mode -> selectedMode = mode },
                        onImageDisposed = onImageDisposed,
                    )
                }
            }
        }
    }
}

@Composable
private fun HdrEmptyContent(onSelectPhoto: () -> Unit) {
    ComparisonMessageCard {
        Text(stringResource(R.string.hdr_empty_message))
        Button(onClick = onSelectPhoto, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hdr_select_photo))
        }
    }
}

@Composable
private fun HdrLoadingContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.hdr_loading))
    }
}

@Composable
private fun HdrErrorContent(error: HdrComparisonError, onRetry: () -> Unit) {
    ComparisonMessageCard(isError = true) {
        Text(stringResource(error.messageRes()))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hdr_retry))
        }
    }
}

@Composable
private fun HdrSuccessContent(
    image: HdrComparisonImage,
    selectedMode: HdrComparisonMode,
    capability: HdrDisplayCapability,
    presentation: HdrPresentationDecision,
    onModeSelected: (HdrComparisonMode) -> Unit,
    onImageDisposed: (HdrComparisonImage) -> Unit,
) {
    DisposableEffect(image) {
        onDispose { onImageDisposed(image) }
    }
    val displayedBitmap = remember(image, presentation.bitmapVariant) {
        when (presentation.bitmapVariant) {
            HdrBitmapVariant.ORIGINAL -> image.originalBitmap
            HdrBitmapVariant.SDR_BASE -> image.sdrBitmap
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(R.string.hdr_mode_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        HdrModeSelector(selectedMode, onModeSelected)
        Text(
            text = stringResource(selectedMode.descriptionRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PresentationStatus(selectedMode, presentation, capability)
        Image(
            bitmap = displayedBitmap.asImageBitmap(),
            contentDescription = stringResource(
                R.string.hdr_preview_description,
                stringResource(selectedMode.labelRes()),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 620.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = stringResource(R.string.hdr_content_details_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ComparisonInfoCard(
            rows = buildList {
                add(
                    stringResource(R.string.hdr_dynamic_range_label) to
                        stringResource(image.contentType.labelRes()),
                )
                add(
                    stringResource(R.string.hdr_dimensions_label) to
                        stringResource(
                            R.string.hdr_dimensions_value,
                            image.originalBitmap.width,
                            image.originalBitmap.height,
                        ),
                )
                add(stringResource(R.string.hdr_color_space_label) to image.colorSpaceName)
                add(stringResource(R.string.hdr_bitmap_config_label) to image.bitmapConfigName)
                add(
                    stringResource(R.string.hdr_gainmap_label) to
                        stringResource(
                            if (image.gainmapMetadata == null) {
                                R.string.hdr_gainmap_absent
                            } else {
                                R.string.hdr_gainmap_present
                            },
                        ),
                )
            },
        )
        image.gainmapMetadata?.let { metadata ->
            GainmapMetadataCard(metadata)
        }
        Text(
            text = stringResource(R.string.hdr_display_details_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ComparisonInfoCard(
            rows = listOf(
                stringResource(R.string.hdr_api_support_label) to
                    capability.apiSupportsHdrUi.yesNoText(),
                stringResource(R.string.hdr_display_support_label) to
                    capability.displaySupportsHdr.yesNoText(),
                stringResource(R.string.hdr_wide_gamut_label) to
                    capability.isWideColorGamut.yesNoText(),
                stringResource(R.string.hdr_headroom_label) to
                    (capability.hdrSdrRatio?.let { ratio ->
                        stringResource(R.string.hdr_ratio_value, ratio)
                    } ?: stringResource(R.string.hdr_ratio_unavailable)),
                stringResource(R.string.hdr_supported_types_label) to
                    capability.supportedHdrTypes.ifEmpty {
                        listOf(stringResource(R.string.hdr_none))
                    }.joinToString(),
            ),
        )
        Text(
            text = stringResource(R.string.hdr_scope_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HdrModeSelector(
    selectedMode: HdrComparisonMode,
    onModeSelected: (HdrComparisonMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HdrComparisonMode.entries.forEach { mode ->
            if (selectedMode == mode) {
                Button(
                    onClick = { onModeSelected(mode) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(mode.labelRes()))
                }
            } else {
                OutlinedButton(
                    onClick = { onModeSelected(mode) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(mode.labelRes()))
                }
            }
        }
    }
}

@Composable
private fun PresentationStatus(
    selectedMode: HdrComparisonMode,
    presentation: HdrPresentationDecision,
    capability: HdrDisplayCapability,
) {
    val text = presentation.fallbackReason?.let { reason ->
        stringResource(reason.messageRes())
    } ?: when (selectedMode) {
        HdrComparisonMode.DEFAULT -> stringResource(R.string.hdr_status_default)
        HdrComparisonMode.SDR -> stringResource(R.string.hdr_status_sdr)
        HdrComparisonMode.HDR -> capability.hdrSdrRatio?.let { ratio ->
            stringResource(R.string.hdr_status_hdr_ratio, ratio)
        } ?: stringResource(R.string.hdr_status_hdr)
    }
    val isFallback = presentation.fallbackReason != null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isFallback) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                shape = MaterialTheme.shapes.medium,
            )
            .padding(14.dp),
    ) {
        Text(
            text = text,
            color = if (isFallback) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        )
    }
}

@Composable
private fun GainmapMetadataCard(metadata: GainmapMetadata) {
    ComparisonInfoCard(
        rows = listOf(
            stringResource(R.string.hdr_gainmap_size_label) to
                stringResource(R.string.hdr_dimensions_value, metadata.width, metadata.height),
            stringResource(R.string.hdr_gainmap_channels_label) to metadata.channelCount.toString(),
            stringResource(R.string.hdr_ratio_min_label) to metadata.ratioMin.formatFloats(),
            stringResource(R.string.hdr_ratio_max_label) to metadata.ratioMax.formatFloats(),
            stringResource(R.string.hdr_gamma_label) to metadata.gamma.formatFloats(),
            stringResource(R.string.hdr_epsilon_sdr_label) to metadata.epsilonSdr.formatFloats(),
            stringResource(R.string.hdr_epsilon_hdr_label) to metadata.epsilonHdr.formatFloats(),
            stringResource(R.string.hdr_full_ratio_label) to
                metadata.displayRatioForFullHdr.formatFloat(),
            stringResource(R.string.hdr_transition_ratio_label) to
                metadata.minDisplayRatioForHdrTransition.formatFloat(),
        ),
    )
}

@Composable
private fun ComparisonInfoCard(rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    modifier = Modifier.weight(1.4f),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ComparisonMessageCard(
    isError: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.large,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun rememberHdrDisplayCapability(): HdrDisplayCapability {
    val display = LocalView.current.display
    var capability by remember(display) { mutableStateOf(display.inspectHdrCapability()) }

    DisposableEffect(display) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !display.isHdrSdrRatioAvailable
        ) {
            return@DisposableEffect onDispose { }
        }

        val listener = Consumer<Display> { changedDisplay ->
            capability = changedDisplay.inspectHdrCapability()
        }
        display.registerHdrSdrRatioChangedListener(
            { command -> command.run() },
            listener,
        )
        onDispose {
            display.unregisterHdrSdrRatioChangedListener(listener)
        }
    }
    return capability
}

@Composable
private fun ApplyHdrWindowMode(window: Window?, mode: HdrWindowMode) {
    DisposableEffect(window, mode) {
        window?.colorMode = when (mode) {
            HdrWindowMode.DEFAULT -> ActivityInfo.COLOR_MODE_DEFAULT
            HdrWindowMode.HDR -> ActivityInfo.COLOR_MODE_HDR
        }
        onDispose {
            window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
    }
}

private fun Display.inspectHdrCapability(): HdrDisplayCapability {
    val hdrTypes = supportedHdrTypesForCurrentMode().map(::hdrTypeName)
    val ratio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        isHdrSdrRatioAvailable
    ) {
        hdrSdrRatio
    } else {
        null
    }
    return HdrDisplayCapability(
        apiSupportsHdrUi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        displaySupportsHdr = hdrTypes.isNotEmpty(),
        isWideColorGamut = isWideColorGamut,
        hdrSdrRatio = ratio,
        supportedHdrTypes = hdrTypes,
    )
}

@Suppress("DEPRECATION")
private fun Display.supportedHdrTypesForCurrentMode(): IntArray =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        mode.supportedHdrTypes
    } else {
        hdrCapabilities.supportedHdrTypes
    }

private fun hdrTypeName(type: Int): String = when (type) {
    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
    else -> "HDR($type)"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@StringRes
private fun HdrComparisonMode.labelRes(): Int = when (this) {
    HdrComparisonMode.DEFAULT -> R.string.hdr_mode_default
    HdrComparisonMode.HDR -> R.string.hdr_mode_hdr
    HdrComparisonMode.SDR -> R.string.hdr_mode_sdr
}

@StringRes
private fun HdrComparisonMode.descriptionRes(): Int = when (this) {
    HdrComparisonMode.DEFAULT -> R.string.hdr_mode_default_description
    HdrComparisonMode.HDR -> R.string.hdr_mode_hdr_description
    HdrComparisonMode.SDR -> R.string.hdr_mode_sdr_description
}

@StringRes
private fun HdrContentType.labelRes(): Int = when (this) {
    HdrContentType.ULTRA_HDR_GAINMAP -> R.string.hdr_content_ultra_hdr
    HdrContentType.HLG_10_BIT -> R.string.hdr_content_hlg
    HdrContentType.PQ_10_BIT -> R.string.hdr_content_pq
    HdrContentType.SDR_OR_UNIDENTIFIED -> R.string.hdr_content_sdr_or_unknown
}

@StringRes
private fun HdrFallbackReason.messageRes(): Int = when (this) {
    HdrFallbackReason.API_LEVEL_UNSUPPORTED -> R.string.hdr_fallback_api
    HdrFallbackReason.DISPLAY_NOT_HDR -> R.string.hdr_fallback_display
    HdrFallbackReason.CONTENT_NOT_HDR -> R.string.hdr_fallback_not_hdr
}

@StringRes
private fun HdrComparisonError.messageRes(): Int = when (this) {
    HdrComparisonError.UNSUPPORTED_IMAGE -> R.string.hdr_error_unsupported
    HdrComparisonError.INPUT_TOO_LARGE -> R.string.hdr_error_input_too_large
    HdrComparisonError.DIMENSIONS_TOO_LARGE -> R.string.hdr_error_dimensions
    HdrComparisonError.OUT_OF_MEMORY -> R.string.hdr_error_memory
    HdrComparisonError.ACCESS_DENIED -> R.string.hdr_error_access
    HdrComparisonError.UNKNOWN -> R.string.hdr_error_unknown
}

@Composable
private fun Boolean.yesNoText(): String = stringResource(
    if (this) R.string.hdr_yes else R.string.hdr_no,
)

private fun List<Float>.formatFloats(): String = joinToString(prefix = "[", postfix = "]") {
    it.formatFloat()
}

private fun Float.formatFloat(): String = String.format(Locale.US, "%.3f", this)

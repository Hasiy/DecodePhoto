/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.hasiy.libheif.HeifChromaFormat
import top.hasiy.libheif.HeifColorProfileType
import top.hasiy.libheif.HeifEncodedColorSpace
import top.hasiy.libheif.HeifItemKind
import top.hasiy.libheif.LibHeifContainerInfo
import top.hasiy.libheif.LibHeifImageInfo
import top.hasiy.libheif.LibHeifItemInfo

private const val PIXELS_PER_MEGAPIXEL = 1_000_000.0

private data class HeifDetailRow(
    @StringRes val label: Int,
    val value: String,
)

private data class CameraExifField(
    @StringRes val label: Int,
    val tags: List<String>,
)

private val CAMERA_EXIF_FIELDS = listOf(
    CameraExifField(R.string.exif_make, listOf("Make")),
    CameraExifField(R.string.exif_model, listOf("Model")),
    CameraExifField(R.string.exif_lens, listOf("LensModel")),
    CameraExifField(R.string.exif_f_number, listOf("FNumber")),
    CameraExifField(R.string.exif_aperture, listOf("ApertureValue")),
    CameraExifField(R.string.exif_focal_35mm, listOf("FocalLengthIn35mmFilm")),
    CameraExifField(R.string.exif_focal_length, listOf("FocalLength")),
    CameraExifField(
        R.string.exif_iso,
        listOf("PhotographicSensitivity", "ISOSpeedRatings"),
    ),
    CameraExifField(R.string.exif_exposure_time, listOf("ExposureTime")),
    CameraExifField(R.string.exif_exposure_bias, listOf("ExposureBiasValue")),
    CameraExifField(R.string.exif_exposure_mode, listOf("ExposureMode")),
    CameraExifField(R.string.exif_metering, listOf("MeteringMode")),
    CameraExifField(R.string.exif_sensitivity, listOf("SensitivityType")),
)

/** Displays structured HEIF container, color-profile, item, and camera metadata. */
@Composable
fun HeifDetailsScreen(
    uiState: RawViewerUiState,
    onNavigateBack: () -> Unit,
    onOpenExif: () -> Unit,
) {
    BackHandler(onBack = onNavigateBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.navigate_back))
                    }
                    Text(
                        text = stringResource(R.string.heif_details_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            val info = uiState.heifInfo
            if (uiState.format?.isHeif != true) {
                item(key = "not-heif") {
                    Text(stringResource(R.string.heif_details_no_selection))
                }
            } else if (info == null) {
                item(key = "inspection-unavailable") {
                    Text(
                        text = stringResource(
                            if (uiState.hasHeifInfoError) {
                                R.string.heif_details_error
                            } else {
                                R.string.heif_details_unavailable
                            },
                        ),
                        color = if (uiState.hasHeifInfoError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            } else {
                item(key = "overview") { HeifOverviewSection(info) }
                item(key = "color-profile") { HeifColorProfileSection(info) }
                item(key = "internal-items") { HeifItemsSection(info.items) }
                item(key = "camera-exif") {
                    CameraExifSection(uiState = uiState, onOpenExif = onOpenExif)
                }
            }
        }
    }
}

@Composable
private fun HeifOverviewSection(info: LibHeifContainerInfo) {
    val image = info.imageInfo
    ExpandableHeifSection(title = stringResource(R.string.heif_overview_title)) {
        val megapixels = image.width.toDouble() * image.height / PIXELS_PER_MEGAPIXEL
        Text(
            text = stringResource(
                R.string.heif_primary_summary,
                image.primaryItemId,
                image.width,
                image.height,
                megapixels,
            ),
            modifier = Modifier.padding(vertical = 8.dp),
            fontWeight = FontWeight.SemiBold,
        )
        HeifDetailTable(
            rows = listOf(
                HeifDetailRow(R.string.heif_field_id, image.primaryItemId.toString()),
                HeifDetailRow(
                    R.string.heif_field_dimensions,
                    stringResource(R.string.heif_dimensions_value, image.width, image.height, megapixels),
                ),
                HeifDetailRow(
                    R.string.heif_field_bit_depth,
                    stringResource(R.string.heif_bits_value, image.bitDepth),
                ),
                HeifDetailRow(
                    R.string.heif_field_color_space,
                    stringResource(image.encodedColorSpace.labelRes()),
                ),
                HeifDetailRow(
                    R.string.heif_field_chroma,
                    stringResource(image.chromaFormat.labelRes()),
                ),
                HeifDetailRow(
                    R.string.heif_field_alpha,
                    stringResource(if (image.hasAlphaChannel) R.string.value_yes else R.string.value_no),
                ),
                HeifDetailRow(
                    R.string.heif_field_tiles,
                    stringResource(R.string.heif_tiles_value, image.tileColumns, image.tileRows),
                ),
                HeifDetailRow(
                    R.string.heif_field_tile_size,
                    stringResource(R.string.heif_size_value, image.tileWidth, image.tileHeight),
                ),
                HeifDetailRow(
                    R.string.heif_field_top_level_count,
                    image.topLevelImageCount.toString(),
                ),
            ),
        )
    }
}

@Composable
private fun HeifColorProfileSection(info: LibHeifContainerInfo) {
    val image = info.imageInfo
    ExpandableHeifSection(title = stringResource(R.string.heif_color_profile_title)) {
        val profile = info.iccProfile
        val rows = buildList {
            add(
                HeifDetailRow(
                    R.string.heif_profile_type,
                    stringResource(image.colorProfileType.labelRes()),
                ),
            )
            if (image.colorProfileSizeBytes > 0) {
                add(
                    HeifDetailRow(
                        R.string.heif_profile_size,
                        formatByteSize(image.colorProfileSizeBytes.toLong()),
                    ),
                )
            }
            if (profile != null) {
                profile.description?.let {
                    add(HeifDetailRow(R.string.heif_profile_description, it))
                }
                profile.version?.let { add(HeifDetailRow(R.string.heif_profile_version, it)) }
                profile.colorSpace?.let { add(HeifDetailRow(R.string.heif_profile_color_space, it)) }
                profile.creator?.let { add(HeifDetailRow(R.string.heif_profile_creator, it)) }
                profile.platform?.let { add(HeifDetailRow(R.string.heif_profile_platform, it)) }
                profile.manufacturer?.let {
                    add(HeifDetailRow(R.string.heif_profile_manufacturer, it))
                }
                profile.deviceClass?.let {
                    add(HeifDetailRow(R.string.heif_profile_device_class, it))
                }
                profile.renderingIntent?.let {
                    add(
                        HeifDetailRow(
                            R.string.heif_profile_rendering_intent,
                            stringResource(renderingIntentLabelRes(it)),
                        ),
                    )
                }
                profile.cmm?.let { add(HeifDetailRow(R.string.heif_profile_cmm, it)) }
                profile.profileConnectionSpace?.let {
                    add(HeifDetailRow(R.string.heif_profile_pcs, it))
                }
                profile.whitePoint?.let {
                    add(HeifDetailRow(R.string.heif_profile_white_point, it))
                }
            } else {
                add(
                    HeifDetailRow(
                        R.string.heif_profile_primaries,
                        colorPrimariesLabel(image.colorPrimaries),
                    ),
                )
                add(
                    HeifDetailRow(
                        R.string.heif_profile_transfer,
                        transferLabel(image.transferCharacteristics),
                    ),
                )
                add(
                    HeifDetailRow(
                        R.string.heif_profile_matrix,
                        matrixLabel(image.matrixCoefficients),
                    ),
                )
                add(
                    HeifDetailRow(
                        R.string.heif_profile_range,
                        stringResource(
                            if (image.isFullRange) R.string.heif_range_full
                            else R.string.heif_range_limited,
                        ),
                    ),
                )
            }
        }
        HeifDetailTable(rows)
    }
}

@Composable
private fun HeifItemsSection(items: List<LibHeifItemInfo>) {
    ExpandableHeifSection(title = stringResource(R.string.heif_internal_items_title)) {
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.heif_internal_items_empty),
                modifier = Modifier.padding(10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = items, key = { item -> "${item.kind}-${item.id}" }) { item ->
                    HeifItemCard(item)
                }
            }
        }
    }
}

@Composable
private fun HeifItemCard(item: LibHeifItemInfo) {
    val background = if (item.kind == HeifItemKind.IMAGE) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    Column(
        modifier = Modifier
            .width(164.dp)
            .background(background, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.heif_item_id, item.id),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (item.kind == HeifItemKind.IMAGE) {
                stringResource(R.string.heif_item_image)
            } else {
                stringResource(R.string.heif_item_metadata)
            },
            style = MaterialTheme.typography.labelMedium,
        )
        Text(item.type, style = MaterialTheme.typography.bodySmall)
        val width = item.width
        val height = item.height
        if (width != null && height != null) {
            Text(
                stringResource(R.string.heif_size_value, width, height),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item.bitDepth?.let {
            Text(stringResource(R.string.heif_bits_value, it), style = MaterialTheme.typography.bodySmall)
        }
        item.sizeBytes?.let {
            Text(formatByteSize(it), style = MaterialTheme.typography.bodySmall)
        }
        item.contentType?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (item.isPrimary) {
            Text(
                text = stringResource(R.string.heif_item_primary),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CameraExifSection(uiState: RawViewerUiState, onOpenExif: () -> Unit) {
    val attributes = remember(uiState.metadata) {
        uiState.metadata.associate { entry -> entry.tag to entry.value }
    }
    val rows = remember(attributes) {
        CAMERA_EXIF_FIELDS.mapNotNull { field ->
            val value = field.tags.firstNotNullOfOrNull(attributes::get) ?: return@mapNotNull null
            field to value
        }
    }
    ExpandableHeifSection(title = stringResource(R.string.heif_camera_title)) {
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.exif_section_empty),
                modifier = Modifier.padding(10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rows.forEach { (field, value) ->
                HeifDetailTableRow(
                    label = stringResource(field.label),
                    value = formatCameraExifValue(field.label, value),
                )
            }
        }
        TextButton(onClick = onOpenExif) {
            Text(stringResource(R.string.exif_more))
        }
    }
}

@Composable
private fun ExpandableHeifSection(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (expanded) R.string.heif_section_expanded
                    else R.string.heif_section_collapsed,
                    title,
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun HeifDetailTable(rows: List<HeifDetailRow>) {
    rows.forEach { row ->
        HeifDetailTableRow(label = stringResource(row.label), value = row.value)
    }
}

@Composable
private fun HeifDetailTableRow(label: String, value: String) {
    SelectionContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text = label,
                modifier = Modifier
                    .weight(0.43f)
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                modifier = Modifier
                    .weight(0.57f)
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun formatByteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> stringResource(R.string.heif_megabyte_value, bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> stringResource(R.string.heif_kilobyte_value, bytes / 1024.0)
    else -> stringResource(R.string.heif_byte_value, bytes)
}

@Composable
private fun formatCameraExifValue(@StringRes label: Int, value: String): String = when (label) {
    R.string.exif_f_number -> stringResource(R.string.heif_f_number_value, value)
    R.string.exif_focal_35mm,
    R.string.exif_focal_length -> stringResource(R.string.heif_millimeter_value, value)
    R.string.exif_iso -> stringResource(R.string.heif_iso_value, value)
    R.string.exif_exposure_time -> formatExposureTime(value)
    R.string.exif_exposure_bias -> stringResource(R.string.heif_ev_value, value)
    R.string.exif_exposure_mode -> formatExposureMode(value)
    R.string.exif_metering -> formatMeteringMode(value)
    R.string.exif_sensitivity -> formatSensitivityType(value)
    else -> value
}

@Composable
private fun formatExposureTime(value: String): String {
    if ('/' in value) return stringResource(R.string.heif_seconds_value, value)
    val seconds = value.toDoubleOrNull() ?: return value
    return if (seconds in 0.0..0.5 && seconds > 0.0) {
        stringResource(R.string.heif_fractional_seconds_value, (1.0 / seconds).roundToInt())
    } else {
        stringResource(R.string.heif_seconds_value, value)
    }
}

@Composable
private fun formatExposureMode(value: String): String = when (value) {
    "0" -> stringResource(R.string.heif_exposure_auto)
    "1" -> stringResource(R.string.heif_exposure_manual)
    "2" -> stringResource(R.string.heif_exposure_bracket)
    else -> value
}

@Composable
private fun formatMeteringMode(value: String): String = when (value) {
    "1" -> stringResource(R.string.heif_metering_average)
    "2" -> stringResource(R.string.heif_metering_center)
    "3" -> stringResource(R.string.heif_metering_spot)
    "4" -> stringResource(R.string.heif_metering_multi_spot)
    "5" -> stringResource(R.string.heif_metering_pattern)
    "6" -> stringResource(R.string.heif_metering_partial)
    "255" -> stringResource(R.string.heif_metering_other)
    else -> value
}

@Composable
private fun formatSensitivityType(value: String): String = when (value) {
    "1" -> stringResource(R.string.heif_sensitivity_sos)
    "2" -> stringResource(R.string.heif_sensitivity_rei)
    "3" -> stringResource(R.string.heif_sensitivity_iso)
    "4" -> stringResource(R.string.heif_sensitivity_sos_rei)
    "5" -> stringResource(R.string.heif_sensitivity_sos_iso)
    "6" -> stringResource(R.string.heif_sensitivity_rei_iso)
    "7" -> stringResource(R.string.heif_sensitivity_all)
    else -> value
}

@Composable
private fun colorPrimariesLabel(value: Int): String = when (value) {
    1 -> stringResource(R.string.heif_primaries_bt709)
    9 -> stringResource(R.string.heif_primaries_bt2020)
    12 -> stringResource(R.string.heif_primaries_display_p3)
    else -> value.takeIf { it >= 0 }?.toString() ?: stringResource(R.string.value_unknown)
}

@Composable
private fun transferLabel(value: Int): String = when (value) {
    1 -> stringResource(R.string.heif_transfer_bt709)
    8 -> stringResource(R.string.heif_transfer_linear)
    13 -> stringResource(R.string.heif_transfer_srgb)
    16 -> stringResource(R.string.heif_transfer_pq)
    18 -> stringResource(R.string.heif_transfer_hlg)
    else -> value.takeIf { it >= 0 }?.toString() ?: stringResource(R.string.value_unknown)
}

@Composable
private fun matrixLabel(value: Int): String = when (value) {
    0 -> stringResource(R.string.heif_matrix_rgb)
    1 -> stringResource(R.string.heif_matrix_bt709)
    9 -> stringResource(R.string.heif_matrix_bt2020_ncl)
    10 -> stringResource(R.string.heif_matrix_bt2020_cl)
    14 -> stringResource(R.string.heif_matrix_ictcp)
    else -> value.takeIf { it >= 0 }?.toString() ?: stringResource(R.string.value_unknown)
}

@StringRes
private fun renderingIntentLabelRes(value: Int): Int = when (value) {
    0 -> R.string.heif_rendering_perceptual
    1 -> R.string.heif_rendering_relative
    2 -> R.string.heif_rendering_saturation
    3 -> R.string.heif_rendering_absolute
    else -> R.string.value_unknown
}

@StringRes
private fun HeifEncodedColorSpace.labelRes(): Int = when (this) {
    HeifEncodedColorSpace.YCBCR -> R.string.heif_colorspace_ycbcr
    HeifEncodedColorSpace.RGB -> R.string.heif_colorspace_rgb
    HeifEncodedColorSpace.MONOCHROME -> R.string.heif_colorspace_monochrome
    HeifEncodedColorSpace.FILTER_ARRAY -> R.string.heif_colorspace_filter_array
    HeifEncodedColorSpace.CUSTOM -> R.string.heif_colorspace_custom
    HeifEncodedColorSpace.UNKNOWN -> R.string.value_unknown
}

@StringRes
private fun HeifChromaFormat.labelRes(): Int = when (this) {
    HeifChromaFormat.YUV_420 -> R.string.heif_chroma_420
    HeifChromaFormat.YUV_422 -> R.string.heif_chroma_422
    HeifChromaFormat.YUV_444 -> R.string.heif_chroma_444
    HeifChromaFormat.PLANAR -> R.string.heif_chroma_planar
    HeifChromaFormat.INTERLEAVED_RGB -> R.string.heif_chroma_rgb
    HeifChromaFormat.INTERLEAVED_RGBA -> R.string.heif_chroma_rgba
    HeifChromaFormat.UNKNOWN -> R.string.value_unknown
}

@StringRes
private fun HeifColorProfileType.labelRes(): Int = when (this) {
    HeifColorProfileType.NONE -> R.string.heif_profile_none
    HeifColorProfileType.NCLX -> R.string.heif_profile_nclx
    HeifColorProfileType.RESTRICTED_ICC -> R.string.heif_profile_ricc
    HeifColorProfileType.ICC -> R.string.heif_profile_icc
    HeifColorProfileType.UNKNOWN -> R.string.value_unknown
}

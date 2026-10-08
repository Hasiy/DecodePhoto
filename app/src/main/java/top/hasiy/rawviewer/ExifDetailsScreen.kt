/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.hasiy.rawviewer.raw.ExifEntry

private const val SUMMARY_PAGE = -2
private const val ALL_TAGS_PAGE = -1
private const val PIXELS_PER_MEGAPIXEL = 1_000_000.0

private data class ExifField(val tag: String, @StringRes val label: Int)
private data class ExifSection(@StringRes val title: Int, val fields: List<ExifField>)

private val EXIF_SECTIONS = listOf(
    ExifSection(R.string.exif_overview, listOf(
        ExifField("ImageWidth", R.string.exif_width),
        ExifField("ImageLength", R.string.exif_height),
        ExifField("PixelXDimension", R.string.exif_pixel_width),
        ExifField("PixelYDimension", R.string.exif_pixel_height),
        ExifField("XResolution", R.string.exif_x_resolution),
        ExifField("YResolution", R.string.exif_y_resolution),
        ExifField("ResolutionUnit", R.string.exif_resolution_unit),
    )),
    ExifSection(R.string.exif_camera, listOf(
        ExifField("Make", R.string.exif_make),
        ExifField("Model", R.string.exif_model),
        ExifField("LensModel", R.string.exif_lens),
        ExifField("FNumber", R.string.exif_f_number),
        ExifField("ApertureValue", R.string.exif_aperture),
        ExifField("FocalLengthIn35mmFilm", R.string.exif_focal_35mm),
        ExifField("FocalLength", R.string.exif_focal_length),
        ExifField("PhotographicSensitivity", R.string.exif_iso),
        ExifField("ISOSpeedRatings", R.string.exif_iso),
        ExifField("ExposureTime", R.string.exif_exposure_time),
        ExifField("ExposureBiasValue", R.string.exif_exposure_bias),
        ExifField("ExposureMode", R.string.exif_exposure_mode),
        ExifField("MeteringMode", R.string.exif_metering),
        ExifField("SensitivityType", R.string.exif_sensitivity),
    )),
    ExifSection(R.string.exif_image_details, listOf(
        ExifField("BrightnessValue", R.string.exif_brightness),
        ExifField("LightSource", R.string.exif_light_source),
        ExifField("WhiteBalance", R.string.exif_white_balance),
        ExifField("ColorSpace", R.string.exif_color_space),
        ExifField("DateTimeOriginal", R.string.exif_date_taken),
        ExifField("Orientation", R.string.exif_orientation),
    )),
    ExifSection(R.string.exif_other_metadata, listOf(
        ExifField("Software", R.string.exif_software),
        ExifField("UserComment", R.string.exif_user_comment),
        ExifField("Artist", R.string.exif_artist),
        ExifField("Copyright", R.string.exif_copyright),
    )),
)

@Composable
fun ExifDetailsScreen(uiState: RawViewerUiState, onNavigateBack: () -> Unit) {
    var selectedSection by rememberSaveable { mutableIntStateOf(SUMMARY_PAGE) }
    val attributes = remember(uiState.metadata) { uiState.metadata.associate { it.tag to it.value } }
    val displayedEntries = remember(uiState.metadata, selectedSection) {
        if (selectedSection >= 0) {
            val tags = EXIF_SECTIONS[selectedSection].fields.map { it.tag }.toSet()
            uiState.metadata.filter { it.tag in tags }
        } else {
            uiState.metadata
        }
    }
    val navigateBack = {
        if (selectedSection == SUMMARY_PAGE) onNavigateBack() else selectedSection = SUMMARY_PAGE
    }
    BackHandler(enabled = selectedSection != SUMMARY_PAGE) { selectedSection = SUMMARY_PAGE }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = navigateBack) { Text(stringResource(R.string.navigate_back)) }
                Text(
                    text = stringResource(
                        if (selectedSection == SUMMARY_PAGE) R.string.exif_details_title
                        else R.string.exif_raw_title,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (uiState.isMetadataLoading) {
                    item(key = "loading") {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        Text(stringResource(R.string.exif_loading))
                    }
                } else if (uiState.hasMetadataError) {
                    item(key = "error") {
                        Text(stringResource(R.string.exif_error), color = MaterialTheme.colorScheme.error)
                    }
                } else if (uiState.metadata.isEmpty()) {
                    item(key = "empty") { Text(stringResource(R.string.exif_empty)) }
                } else if (selectedSection == SUMMARY_PAGE) {
                    EXIF_SECTIONS.forEachIndexed { index, section ->
                        item(key = section.title) {
                            ExifSectionContent(section, attributes) { selectedSection = index }
                        }
                    }
                    item(key = "raw-link") {
                        Text(
                            stringResource(R.string.exif_raw_title),
                            modifier = Modifier.padding(top = 24.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = { selectedSection = ALL_TAGS_PAGE }) {
                            Text(stringResource(R.string.exif_view_all, uiState.metadata.size))
                        }
                    }
                } else {
                    item(key = "raw-count") {
                        Text(
                            stringResource(R.string.exif_title, displayedEntries.size),
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    items(displayedEntries, key = ExifEntry::tag) { entry ->
                        ExifTableRow(rawExifLabel(entry.tag), entry.value)
                    }
                }
            }
        }
    }
}

@Composable
private fun rawExifLabel(tag: String): String = stringResource(
    if (tag.startsWith("GPS")) R.string.exif_raw_gps_tag else R.string.exif_raw_exif_tag,
    tag,
)

@Composable
private fun ExifSectionContent(
    section: ExifSection,
    attributes: Map<String, String>,
    onViewMore: () -> Unit,
) {
    var expanded by rememberSaveable(section.title) { mutableStateOf(true) }
    val fields = remember(section, attributes) { section.fields.filter { it.tag in attributes } }
    Column {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                stringResource(
                    if (expanded) R.string.exif_section_expanded else R.string.exif_section_collapsed,
                    stringResource(section.title),
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (expanded) {
            if (section.title == R.string.exif_overview) {
                val width = (attributes["PixelXDimension"] ?: attributes["ImageWidth"])?.toLongOrNull()
                val height = (attributes["PixelYDimension"] ?: attributes["ImageLength"])?.toLongOrNull()
                if (width != null && height != null && width > 0 && height > 0) {
                    Text(
                        stringResource(R.string.exif_dimensions_summary, width, height,
                            width.toDouble() * height / PIXELS_PER_MEGAPIXEL),
                        modifier = Modifier.padding(bottom = 12.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (fields.isEmpty()) {
                Text(
                    stringResource(R.string.exif_section_empty),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                fields.forEach { field ->
                    ExifTableRow(stringResource(field.label), attributes.getValue(field.tag))
                }
                TextButton(onClick = onViewMore) { Text(stringResource(R.string.exif_more)) }
            }
        }
    }
}

@Composable
private fun ExifTableRow(label: String, value: String) {
    SelectionContainer {
        Row(
            modifier = Modifier.fillMaxWidth()
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                label,
                modifier = Modifier.weight(0.4f).padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                modifier = Modifier.weight(0.6f).padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

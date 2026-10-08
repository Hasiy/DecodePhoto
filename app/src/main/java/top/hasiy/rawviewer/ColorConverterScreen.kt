/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import top.hasiy.rawviewer.util.ColorConverter
import top.hasiy.rawviewer.util.HsbColor
import top.hasiy.rawviewer.util.RgbColor

@Composable
fun ColorConverterScreen(
    onNavigateBack: () -> Unit,
) {
    var redText by rememberSaveable { mutableStateOf("47") }
    var greenText by rememberSaveable { mutableStateOf("129") }
    var blueText by rememberSaveable { mutableStateOf("247") }
    var hueText by rememberSaveable { mutableStateOf("215.4") }
    var saturationText by rememberSaveable { mutableStateOf("81.0") }
    var brightnessText by rememberSaveable { mutableStateOf("96.9") }
    var hasRgbError by rememberSaveable { mutableStateOf(false) }
    var hasHsbError by rememberSaveable { mutableStateOf(false) }
    var previewRed by rememberSaveable { mutableStateOf(47) }
    var previewGreen by rememberSaveable { mutableStateOf(129) }
    var previewBlue by rememberSaveable { mutableStateOf(247) }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onNavigateBack) {
                        Text(stringResource(R.string.navigate_back))
                    }
                    Text(
                        text = stringResource(R.string.color_converter_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.color_converter_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "preview") {
                ColorPreview(
                    red = previewRed,
                    green = previewGreen,
                    blue = previewBlue,
                )
            }
            item(key = "rgb-input") {
                ColorInputSection(
                    title = stringResource(R.string.rgb_section_title),
                    hint = stringResource(R.string.rgb_range_hint),
                    error = if (hasRgbError) stringResource(R.string.invalid_rgb_input) else null,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ColorComponentField(
                            value = redText,
                            label = stringResource(R.string.color_red),
                            isError = hasRgbError,
                            keyboardType = KeyboardType.Number,
                            onValueChange = { redText = it; hasRgbError = false },
                            modifier = Modifier.weight(1f),
                        )
                        ColorComponentField(
                            value = greenText,
                            label = stringResource(R.string.color_green),
                            isError = hasRgbError,
                            keyboardType = KeyboardType.Number,
                            onValueChange = { greenText = it; hasRgbError = false },
                            modifier = Modifier.weight(1f),
                        )
                        ColorComponentField(
                            value = blueText,
                            label = stringResource(R.string.color_blue),
                            isError = hasRgbError,
                            keyboardType = KeyboardType.Number,
                            onValueChange = { blueText = it; hasRgbError = false },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            val rgb = parseRgb(redText, greenText, blueText)
                            hasRgbError = rgb == null
                            if (rgb != null) {
                                val hsb = ColorConverter.rgbToHsb(rgb)
                                hueText = hsb.hue.toDisplayNumber()
                                saturationText = hsb.saturation.toDisplayNumber()
                                brightnessText = hsb.brightness.toDisplayNumber()
                                previewRed = rgb.red
                                previewGreen = rgb.green
                                previewBlue = rgb.blue
                                hasHsbError = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.convert_rgb_to_hsb))
                    }
                }
            }
            item(key = "hsb-input") {
                ColorInputSection(
                    title = stringResource(R.string.hsb_section_title),
                    hint = stringResource(R.string.hsb_range_hint),
                    error = if (hasHsbError) stringResource(R.string.invalid_hsb_input) else null,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ColorComponentField(
                            value = hueText,
                            label = stringResource(R.string.color_hue),
                            isError = hasHsbError,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { hueText = it; hasHsbError = false },
                            modifier = Modifier.weight(1f),
                        )
                        ColorComponentField(
                            value = saturationText,
                            label = stringResource(R.string.color_saturation),
                            isError = hasHsbError,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { saturationText = it; hasHsbError = false },
                            modifier = Modifier.weight(1f),
                        )
                        ColorComponentField(
                            value = brightnessText,
                            label = stringResource(R.string.color_brightness),
                            isError = hasHsbError,
                            keyboardType = KeyboardType.Decimal,
                            onValueChange = { brightnessText = it; hasHsbError = false },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            val hsb = parseHsb(hueText, saturationText, brightnessText)
                            hasHsbError = hsb == null
                            if (hsb != null) {
                                val rgb = ColorConverter.hsbToRgb(hsb)
                                redText = rgb.red.toString()
                                greenText = rgb.green.toString()
                                blueText = rgb.blue.toString()
                                previewRed = rgb.red
                                previewGreen = rgb.green
                                previewBlue = rgb.blue
                                hasRgbError = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.convert_hsb_to_rgb))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorInputSection(
    title: String,
    hint: String,
    error: String?,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        content()
        Text(
            text = error ?: hint,
            style = MaterialTheme.typography.bodySmall,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun ColorComponentField(
    value: String,
    label: String,
    isError: Boolean,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
    )
}

@Composable
private fun ColorPreview(
    red: Int,
    green: Int,
    blue: Int,
) {
    val hex = String.format(Locale.US, "#%02X%02X%02X", red, green, blue)
    val description = stringResource(R.string.color_preview_description, red, green, blue)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.color_preview_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Color(red, green, blue), MaterialTheme.shapes.large)
                .semantics { contentDescription = description }
                .testTag(COLOR_PREVIEW_TEST_TAG),
        )
        Text(stringResource(R.string.color_preview_value, hex, red, green, blue))
    }
}

private fun parseRgb(red: String, green: String, blue: String): RgbColor? {
    val components = listOf(red, green, blue).map { value -> value.toIntOrNull() ?: return null }
    if (components.any { component -> component !in RGB_COMPONENT_RANGE }) return null
    return RgbColor(components[0], components[1], components[2])
}

private fun parseHsb(hue: String, saturation: String, brightness: String): HsbColor? {
    val hueValue = hue.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    val saturationValue = saturation.toDoubleOrNull()?.takeIf { it in PERCENT_RANGE } ?: return null
    val brightnessValue = brightness.toDoubleOrNull()?.takeIf { it in PERCENT_RANGE } ?: return null
    return HsbColor(hueValue, saturationValue, brightnessValue)
}

private fun Double.toDisplayNumber(): String = String.format(Locale.US, "%.1f", this)

private val RGB_COMPONENT_RANGE = 0..255
private val PERCENT_RANGE = 0.0..100.0
private const val COLOR_PREVIEW_TEST_TAG = "color-preview"

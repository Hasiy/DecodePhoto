/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.hasiy.rawviewer.raw.RgbHistogram
import top.hasiy.rawviewer.raw.RgbHistogramCalculator
import kotlin.math.ln
import kotlin.math.max

@Composable
fun RgbHistogramChart(
    histogram: RgbHistogram,
    modifier: Modifier = Modifier,
) {
    val chartDescription = stringResource(
        R.string.histogram_content_description,
        histogram.sampleCount,
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.histogram_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HistogramLegend(stringResource(R.string.histogram_red), HISTOGRAM_RED)
            HistogramLegend(stringResource(R.string.histogram_green), HISTOGRAM_GREEN)
            HistogramLegend(stringResource(R.string.histogram_blue), HISTOGRAM_BLUE)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(HISTOGRAM_BACKGROUND)
                .semantics { contentDescription = chartDescription }
                .padding(8.dp),
        ) {
            drawGrid()
            if (histogram.maxBinCount > 0) {
                drawChannel(histogram.red, histogram.maxBinCount, HISTOGRAM_RED)
                drawChannel(histogram.green, histogram.maxBinCount, HISTOGRAM_GREEN)
                drawChannel(histogram.blue, histogram.maxBinCount, HISTOGRAM_BLUE)
            }
        }
        Text(
            text = stringResource(R.string.histogram_note, histogram.sampleCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistogramLegend(label: String, color: Color) {
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
    )
}

private fun DrawScope.drawGrid() {
    repeat(GRID_LINE_COUNT) { index ->
        val fraction = index.toFloat() / (GRID_LINE_COUNT - 1)
        drawLine(
            color = GRID_COLOR,
            start = Offset(size.width * fraction, 0f),
            end = Offset(size.width * fraction, size.height),
        )
        drawLine(
            color = GRID_COLOR,
            start = Offset(0f, size.height * fraction),
            end = Offset(size.width, size.height * fraction),
        )
    }
}

private fun DrawScope.drawChannel(bins: IntArray, maxBinCount: Int, color: Color) {
    val columnWidth = size.width / RgbHistogramCalculator.BIN_COUNT
    val strokeWidth = max(MIN_STROKE_WIDTH_PX, columnWidth)
    val logMaximum = ln(maxBinCount.toFloat() + 1f)
    bins.forEachIndexed { index, count ->
        if (count == 0) return@forEachIndexed
        val normalizedHeight = ln(count.toFloat() + 1f) / logMaximum
        val x = (index + 0.5f) * columnWidth
        drawLine(
            color = color.copy(alpha = CHANNEL_ALPHA),
            start = Offset(x, size.height),
            end = Offset(x, size.height * (1f - normalizedHeight)),
            strokeWidth = strokeWidth,
        )
    }
}

private const val GRID_LINE_COUNT = 5
private const val MIN_STROKE_WIDTH_PX = 1f
private const val CHANNEL_ALPHA = 0.72f
private val HISTOGRAM_BACKGROUND = Color(0xFF111318)
private val GRID_COLOR = Color.White.copy(alpha = 0.12f)
private val HISTOGRAM_RED = Color(0xFFFF5C5C)
private val HISTOGRAM_GREEN = Color(0xFF45D483)
private val HISTOGRAM_BLUE = Color(0xFF5C8FFF)

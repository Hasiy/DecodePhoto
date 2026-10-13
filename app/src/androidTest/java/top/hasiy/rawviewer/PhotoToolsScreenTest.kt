/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.hasiy.rawviewer.raw.*

class PhotoToolsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun zoomControls_zoomAndReset_updateSharedTransform() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val transform = PhotoTransform()
        rule.setContent { MaterialTheme { Column {
            ZoomablePhoto(bitmap, Modifier.size(240.dp), transform = transform)
            PhotoTransformControls(transform)
        } } }
        rule.onNodeWithText("放大").performClick()
        rule.runOnIdle { assertEquals(1.5f, transform.scale, 0.001f) }
        rule.onNodeWithText("重置缩放").performClick()
        rule.runOnIdle { assertEquals(1f, transform.scale, 0.001f) }
    }

    @Test fun metadataSearch_valueQuery_preservesUnclassifiedXmp() {
        rule.setContent { MaterialTheme { ExifDetailsScreen(RawViewerUiState(metadata = listOf(
            ExifEntry("Make", "Synthetic camera"), ExifEntry("Xmp", "unique marker"))), {}) } }
        rule.onNodeWithText("搜索标签或内容").performTextInput("marker")
        rule.onNodeWithText("unique marker").assertIsDisplayed()
        rule.onNodeWithText("Synthetic camera").assertDoesNotExist()
        rule.onNodeWithText("导出 JSON").assertIsDisplayed()
    }

    @Test fun comparison_emptyState_loadsRequestedDecoderPair() {
        var requested: Pair<DecodeMode, DecodeMode>? = null
        rule.setContent { MaterialTheme {
            DecodeComparisonScreen(PhotoFormat.DNG, DecodeComparisonState(), { a, b -> requested = a to b }, {})
        } }
        rule.onNodeWithText("加载对比结果").performClick()
        rule.runOnIdle { assertEquals(DecodeMode.PREVIEW to DecodeMode.LIBRARY, requested) }
    }
}

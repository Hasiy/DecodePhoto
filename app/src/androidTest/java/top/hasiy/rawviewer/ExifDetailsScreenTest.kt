/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.hasiy.rawviewer.raw.ExifEntry

class ExifDetailsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun viewAll_unclassifiedTags_preservesValuesAndReturnsToSummary() {
        var returnedToViewer = false
        composeRule.setContent {
            MaterialTheme {
                ExifDetailsScreen(
                    uiState = RawViewerUiState(metadata = listOf(
                        ExifEntry("Make", "OPPO"),
                        ExifEntry("GPSLatitude", "31/1,4/1,576539/10000"),
                        ExifEntry("Xmp", "<xmp>original</xmp>"),
                    )),
                    onNavigateBack = { returnedToViewer = true },
                )
            }
        }
        composeRule.onNodeWithText("OPPO").assertIsDisplayed()
        composeRule.onNodeWithText("查看全部标签（3 项）").performScrollTo().performClick()
        composeRule.onNodeWithText("GPSLatitude").assertIsDisplayed()
        composeRule.onNodeWithText("31/1,4/1,576539/10000").assertIsDisplayed()
        composeRule.onNodeWithText("<xmp>original</xmp>").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("EXIF 信息").assertIsDisplayed()
        composeRule.onNodeWithText("GPSLatitude").assertDoesNotExist()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.runOnIdle { assertTrue(returnedToViewer) }
    }
}

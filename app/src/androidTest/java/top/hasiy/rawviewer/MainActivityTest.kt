/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.ExternalResource
import androidx.test.platform.app.InstrumentationRegistry

class MainActivityTest {

    val composeRule = createAndroidComposeRule<MainActivity>()
    private val resetDestination = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("MainActivity", 0).edit().remove("destination").commit()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(resetDestination).around(composeRule)

    @Test
    fun launch_showsSupportedPhotoFormats() {
        val title = composeRule.activity.getString(R.string.screen_title)
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun colorConverterEntry_click_showsConverterPage() {
        val entry = composeRule.activity.getString(R.string.open_color_converter)
        val title = composeRule.activity.getString(R.string.color_converter_title)

        composeRule.onNodeWithText(entry).performClick()
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun hdrComparisonEntry_click_showsComparisonPage() {
        val entry = composeRule.activity.getString(R.string.open_hdr_comparison)
        val title = composeRule.activity.getString(R.string.hdr_comparison_title)

        composeRule.onNodeWithText(entry).performClick()
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }
}

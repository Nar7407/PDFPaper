package com.pdfpaper.app.ui.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.theme.PDFPaperTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T68b: Device UI tests for [LayersSheet] and annotation layer controls.
 */
@RunWith(AndroidJUnit4::class)
class LayersSheetDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun layersSheetDisplaysControlsAndRows() {
        val vm = ViewerViewModel(ApplicationProvider.getApplicationContext())
        composeRule.setContent {
            PDFPaperTheme {
                LayersSheet(
                    viewModel = vm,
                    onDismiss = {}
                )
            }
        }

        // Sheet container
        composeRule.onNodeWithTag(UiTags.LAYERS_SHEET).assertIsDisplayed()

        // 7 group rows in plan order
        composeRule.onNodeWithTag(UiTags.LAYERS_ROW_HIGHLIGHT).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.LAYERS_ROW_UNDERLINE).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.LAYERS_ROW_STRIKEOUT).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.LAYERS_ROW_INK).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.LAYERS_ROW_NOTE).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.LAYERS_ROW_STAMP).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.LAYERS_ROW_FORM_FIELD).assertIsDisplayed()

        // Action buttons
        composeRule.onNodeWithTag(UiTags.LAYERS_BTN_SHOW_ALL).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.LAYERS_BTN_HIDE_ALL).assertIsDisplayed()
    }
}
